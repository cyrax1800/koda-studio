/*
 * Copyright 2026  Kumarapu Hemram
 * Modified 2026 by Koda Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions.autocomplete

import org.jetbrains.kotlin.psi.KtClassBody
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions

/**
 * Completion of a name being typed without a receiver:
 * - everything in scope at the cursor: locals and parameters declared before it, members of `this` and of lambda
 *   receivers (e.g. a Compose scope), the file's package, explicit and default imports (kotlin.*, java.lang.*);
 * - named arguments inside a call (`modifier =`), overrides in a class body, and the keywords that fit the position;
 * - extensions on `this` or a lambda receiver (`lifecycleScope` in an activity, `apply`, `launch` in a coroutine);
 * - classes, top-level functions and extensions on those receivers from the index that aren't imported yet, with an
 *   edit adding the import.
 */
@OptIn(KaExperimentalApi::class)
fun autoCompletionGeneric(
    ktFile: KtFile,
    offset: Int,
    index: Index,
    completingElement: KtElement,
    prefix: String,
    onPartial: () -> Unit = {},
    smart: Boolean = false,
    /** A type is being written (see [typeCompletion]): classes only. */
    typesOnly: Boolean = false,
): Sequence<CompletionItem> {
    val matches = { name: Name -> name.asString().startsWith(prefix, ignoreCase = true) }
    var expectation: Expectation? = null
    val (receiverNames, inScope) = analyze(ktFile) {
        val items = LinkedHashMap<String, CompletionItem>()
        val add = { item: CompletionItem? -> if (item != null) items.putIfAbsent(item.label + (item.labelDetails?.detail ?: ""), item) }
        // What the code here expects: fitting items sort first, and its enum entries, sealed subclasses, … are offered.
        if (!typesOnly) {
            expectation = expectationAt(completingElement, offset)
            expectation?.let { smartItems(it, ktFile).forEach(add) }
            // Named arguments and overrides first: in their places they are what you most likely want.
            namedArgumentItems(completingElement, prefix).forEach(add)
            if (completionPosition(completingElement) == CompletionPosition.CLASS_BODY || completingElement is KtClassBody) {
                overrideItems(ktFile, completingElement, prefix, offset).forEach(add)
            }
            keywordItems(completionPosition(completingElement), prefix).forEach(add)
        }
        val context = ktFile.scopeContext(completingElement)
        // `this` and lambda receivers: extensions on them are called without a receiver (`lifecycleScope`, `apply`).
        val receiverTypes = context.implicitReceivers.map { it.type }
        for (scopeWithKind in context.scopes) {
            val local = scopeWithKind.kind is KaScopeKind.LocalScope
            if (!typesOnly) for (symbol in scopeWithKind.scope.callables(matches)) {
                if (symbol.isExtension && receiverTypes.none { acceptsReceiver(symbol, it) }) continue
                val psi = symbol.psi
                if (local && psi != null && psi.containingFile == ktFile && psi.textRange.startOffset >= offset) continue
                add(callableItem(symbol.asSignature(), expectation))
                if (items.size >= MAX_COMPLETION_ITEMS) break
            }
            // An empty prefix would list every class of every star import; classes come once something is typed.
            if (prefix.isNotEmpty() || typesOnly) {
                val classifiers = if (scopeWithKind.kind is KaScopeKind.DefaultStarImportingScope) {
                    defaultImportClassifiers(scopeWithKind.scope, matches)
                } else {
                    scopeWithKind.scope.classifiers(matches).toList()
                }
                for (symbol in classifiers) add(classifierItem(symbol, expectation))
            }
            if (items.size >= MAX_COMPLETION_ITEMS) break
        }
        // Overrides alone can pass the cap (an Activity has hundreds); the list is then marked incomplete.
        receiverTypes.flatMapTo(HashSet()) { receiverClassNames(it) } to items.values.take(MAX_COMPLETION_ITEMS)
    }
    // Smart completion (Ctrl+Shift+Space): only what fits the expected type, when there is one.
    val fitting = expectation
    val inScopeShown = if (smart && fitting != null) inScope.filter { it in fitting.matched } else inScope
    if (prefix.length < 2) {
        // Classes and functions that aren't imported come from the second letter on (one letter matches too much).
        // The list must say it's incomplete: otherwise the editor only filters it as you type, and `Row` or
        // `rememberScrollState` never appeared unless the completion was opened again.
        onPartial()
        return inScopeShown.asSequence()
    }

    val known = inScope.mapTo(HashSet()) { it.label }
    val imports = ImportInserter(ktFile)
    val capitalized = prefix.replaceFirstChar { it.uppercaseChar() }
    val candidates = (index.getCompletions(prefix) + if (capitalized != prefix) index.getCompletions(capitalized) else emptySequence())
    val external = candidates
        .filter { decl ->
            when (decl) {
                is Declaration.Class -> decl.isTopLevel && !decl.isPrivate
                is Declaration.Function -> !typesOnly && decl.isTopLevel && !decl.isPrivate &&
                    (!decl.isExtension || decl.extensionReceiver?.let(::shortTypeName) in receiverNames)
                // Extension properties on `this` or a lambda receiver, e.g. `lifecycleScope` in an activity.
                is Declaration.Field -> !typesOnly && decl.extensionReceiver?.let(::shortTypeName) in receiverNames
                else -> false
            }
        }
        .filter { it.name !in known && !isJdkInternal(declarationFqName(it)) }
        // Overloads stay apart (`LaunchedEffect(key1) { }` next to `LaunchedEffect(key1, key2) { }`); the same declaration
        // indexed twice (two library versions, or a jar and its sources) is listed once.
        .distinctBy { declarationFqName(it) + overloadKey(it) }
        .mapNotNull { decl ->
            val fqName = declarationFqName(decl)
            val (inserted, insertionType) = decl.insertInfo()
            CompletionItem().apply {
                label = decl.name
                labelDetails = CompletionItemLabelDetails().apply {
                    detail = (decl.details().detail ?: "").substringBefore(" (")
                    description = fqName.substringBeforeLast('.', "")
                }
                kind = decl.completionKind()
                insertText = inserted
                insertTextFormat = insertionType
                additionalTextEdits = listOfNotNull(imports.editFor(fqName))
                val returns = when (decl) {
                    is Declaration.Function -> decl.returnType
                    is Declaration.Field -> decl.type
                    is Declaration.Class -> decl.name
                    else -> null
                }
                // Same names sort by where they come from: `android.util.Log` before `org.slf4j.Log`.
                val rank = "4${decl.name}\u0000${importPreference(fqName)}"
                sortText = if (fitsByName(returns, fitting)) "!$rank" else rank
            }.takeIf { !smart || fitting == null || it.sortText.startsWith("!") }
        }
        .take((MAX_COMPLETION_ITEMS - inScopeShown.size).coerceAtLeast(0))
    return inScopeShown.asSequence() + external
}

/**
 * Names of the classes and type aliases the default imports bring (`kotlin.*`, `kotlin.collections.*`, `java.lang.*`,
 * …), by module: listing them through their scope took 75 ms on every completion, and 0.7 s the first time in a file.
 * Only the names are kept; the matching ones are looked up in the default packages.
 */
private val defaultImportClassifierNames = ConcurrentHashMap<KaModule, Set<Name>>()

private fun KaSession.defaultImportNames(scope: KaScope): Set<Name> =
    defaultImportClassifierNames.getOrPut(useSiteModule) { scope.getPossibleClassifierNames() }

private fun KaSession.defaultImportClassifiers(scope: KaScope, matches: (Name) -> Boolean): List<KaClassifierSymbol> =
    defaultImportNames(scope).filter(matches).flatMap { name ->
        DEFAULT_IMPORT_PACKAGES.mapNotNull { pkg ->
            val classId = ClassId(pkg, name)
            findClass(classId) ?: findTypeAlias(classId)
        }
    }

/** The packages Kotlin/JVM imports by default; a name in several (`kotlin.Exception`, `java.lang.Exception`) is listed once per package. */
private val DEFAULT_IMPORT_PACKAGES = listOf(
    "kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons", "kotlin.io", "kotlin.ranges",
    "kotlin.sequences", "kotlin.text", "kotlin.jvm", "java.lang",
).map { FqName(it) }

/** Lists the default imports' class names of [ktFile]'s module ahead of the first completion (see [defaultImportNames]). */
fun KaSession.warmUpDefaultImports(ktFile: KtFile, at: KtElement) {
    ktFile.scopeContext(at).scopes.firstOrNull { it.kind is KaScopeKind.DefaultStarImportingScope }?.let { defaultImportNames(it.scope) }
}

fun overloadKey(declaration: Declaration): String =
    (declaration as? Declaration.Function)?.parameters?.joinToString(",", "(", ")") { it.name } ?: ""

fun declarationFqName(declaration: Declaration): String = when (declaration) {
    is Declaration.Class -> declaration.fqName
    is Declaration.Function -> declaration.fqName
    is Declaration.Field -> declaration.fqName
    is Declaration.EnumEntry -> "${declaration.enumFqName}.${declaration.name}"
}
