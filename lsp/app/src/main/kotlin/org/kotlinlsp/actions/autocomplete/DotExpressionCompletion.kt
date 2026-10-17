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

import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions

/**
 * Completion after `receiver.`:
 * - the members of the receiver's type, including inherited ones, generic members with the receiver's type
 *   arguments, and Java getters as properties;
 * - static members and the companion when the receiver is a class or Java type name;
 * - extension functions and properties in scope (imports, default imports such as `let`, `apply`, `map`, the file's
 *   package) whose receiver accepts the receiver's type;
 * - extension functions and properties from the index that aren't imported yet, with an edit adding the import.
 */
@OptIn(KaExperimentalApi::class)
fun autoCompletionDotExpression(
    ktFile: KtFile,
    offset: Int,
    index: Index,
    completingElement: KtQualifiedExpression,
    prefix: String,
    onPartial: () -> Unit = {},
    smart: Boolean = false,
): Sequence<CompletionItem> {
    var expectation: Expectation? = null
    val matches = { name: Name -> name.asString().startsWith(prefix, ignoreCase = true) }
    val (receiverNames, members) = analyze(completingElement) {
        val receiver = completingElement.receiverExpression
        // `PokedexScreen.` or `a.b.PokedexScreen.`: the receiver may name a class rather than a value.
        val receiverName = when (receiver) {
            is KtNameReferenceExpression -> receiver
            is KtDotQualifiedExpression -> receiver.selectorExpression as? KtNameReferenceExpression
            else -> null
        }
        val resolved = receiverName?.mainReference?.resolveToSymbol()
        // `Log.` before `android.util.Log` is imported: the static members of the classes of that name, each with an
        // edit adding the class's import, as Android Studio offers them.
        if (resolved == null && receiver is KtNameReferenceExpression && receiver.getReferencedName().first().isUpperCase()) {
            val items = unimportedClassMembers(ktFile, receiver.getReferencedName(), index, matches)
            if (items.isNotEmpty()) return@analyze emptySet<String>() to items
        }
        val classSymbol = resolved as? KaClassSymbol
        // A class or interface name has no instance members (`equals`, `let`, …); only an object's name is a value.
        // Its companion's members are added below, and extensions apply to the companion as a value.
        val namesType = classSymbol != null && classSymbol.classKind != KaClassKind.OBJECT
        val type = if (namesType) (classSymbol as? KaNamedClassSymbol)?.companionObject?.defaultType else receiver.expressionType
        val items = LinkedHashMap<String, CompletionItem>()
        val add = { item: CompletionItem? -> if (item != null) items.putIfAbsent(item.label + (item.labelDetails?.detail ?: ""), item) }
        // `foo(Color.|)`: members of the expected type sort first.
        expectation = expectationAt(completingElement, offset)
        if (type != null) {
            type.scope?.getCallableSignatures(matches)?.forEach { add(callableItem(it, expectation)) }
            type.syntheticJavaPropertiesScope?.getCallableSignatures(matches)?.forEach { add(callableItem(it, expectation)) }
        }
        if (classSymbol != null) {
            // Nested classes and objects first: `PokedexScreen.Home`, `Outer.Builder`.
            classSymbol.staticMemberScope.classifiers(matches).filter { isVisibleNested(it) }.forEach { add(classifierItem(it)?.apply { sortText = "0$label" }.let { item -> rank(item, (it as? KaClassSymbol)?.takeIf { s -> s.classKind == KaClassKind.OBJECT }?.defaultType, expectation) }) }
            classSymbol.staticMemberScope.callables(matches).forEach { add(callableItem(it.asSignature(), expectation)) }
            (classSymbol as? KaNamedClassSymbol)?.companionObject?.let { companion ->
                companion.memberScope.callables(matches).forEach { add(callableItem(it.asSignature(), expectation)) }
            }
        }
        if (type != null) {
            val receiverClasses = ReceiverClasses(this, type)
            for (scopeWithKind in ktFile.scopeContext(completingElement).scopes) {
                if (scopeWithKind.kind is KaScopeKind.TypeScope) continue
                // Right after the dot, the default imports' thousands of extensions (kotlin.collections, kotlin.text, …)
                // took most of a second to load on a large project. Only the commonly used ones are offered from them;
                // the list is marked incomplete, and the first letter typed brings the full, filtered list.
                val defaultImport = scopeWithKind.kind is KaScopeKind.DefaultSimpleImportingScope ||
                    scopeWithKind.kind is KaScopeKind.DefaultStarImportingScope
                if (prefix.isEmpty() && defaultImport) {
                    onPartial()
                    for (name in COMMON_EXTENSIONS) {
                        for (symbol in scopeWithKind.scope.callables(name)) {
                            if (symbol.isExtension && acceptsReceiver(symbol, receiverClasses)) add(callableItem(symbol.asSignature(), expectation))
                        }
                    }
                    continue
                }
                for (symbol in scopeWithKind.scope.callables(matches)) {
                    if (symbol.isExtension && acceptsReceiver(symbol, receiverClasses)) add(callableItem(symbol.asSignature(), expectation))
                }
                if (items.size >= MAX_COMPLETION_ITEMS) break
            }
        }
        (type?.let { receiverClassNames(it) } ?: emptySet()) to items.values.toList()
    }
    // Smart completion (Ctrl+Shift+Space): only members that fit the expected type, when there is one.
    val fitting = expectation
    if (smart && fitting != null) return members.filter { it in fitting.matched }.asSequence()
    if (prefix.isEmpty() || receiverNames.isEmpty()) return members.asSequence()

    // Extensions that aren't imported: the index only knows the receiver's short type name, e.g. "Flow<T>".
    val known = members.mapTo(HashSet()) { it.label }
    val imports = ImportInserter(ktFile)
    val external = index.getCompletions(prefix)
        .filter { it.name !in known && it.extensionReceiver?.let(::shortTypeName) in receiverNames }
        .distinctBy { declarationFqName(it) + overloadKey(it) }
        .mapNotNull { decl ->
            val fqName = declarationFqName(decl)
            val edit = imports.editFor(fqName) ?: return@mapNotNull null
            val (inserted, insertionType) = decl.insertInfo()
            CompletionItem().apply {
                label = decl.name
                labelDetails = CompletionItemLabelDetails().apply {
                    detail = when (decl) {
                        is Declaration.Function -> "(${decl.parameters.joinToString(", ") { "${it.name}: ${it.type}" }})"
                        is Declaration.Field -> ": ${decl.type}"
                        else -> null
                    }
                    description = fqName.substringBeforeLast('.', "")
                }
                kind = decl.completionKind()
                insertText = inserted
                insertTextFormat = insertionType
                additionalTextEdits = listOf(edit)
                sortText = "4${decl.name}"
            }
        }
        .take(MAX_COMPLETION_ITEMS)
    return members.asSequence() + external
}

/**
 * Static members (Java statics, an object's or a companion's members) of the not-yet-imported classes named [name],
 * found through the index. Classes from `android.*` and AndroidX come first, as the usual meaning of `Log`, `View`, `Intent`.
 */
private fun KaSession.unimportedClassMembers(ktFile: KtFile, name: String, index: Index, matches: (Name) -> Boolean): List<CompletionItem> {
    val classes = runCatching { index.getCompletions(name).filter { it.name == name }.take(50).toList() }.getOrDefault(emptyList())
        .filterIsInstance<Declaration.Class>()
        .filter { it.isTopLevel && !it.isPrivate }
        .map { it.fqName }
        .filterNot(::isJdkInternal)
        .distinct()
        .sortedWith(compareBy({ importPreference(it) }, { it.length }, { it }))
    val imports = ImportInserter(ktFile)
    val items = LinkedHashMap<String, CompletionItem>()
    for (fqName in classes) {
        val symbol = findClass(ClassId.topLevel(FqName(fqName))) as? KaNamedClassSymbol ?: continue
        val edit = imports.editFor(fqName)
        val callables = when (symbol.classKind) {
            KaClassKind.OBJECT -> symbol.memberScope.callables(matches)
            else -> symbol.staticMemberScope.callables(matches) + symbol.companionObject?.memberScope?.callables(matches).orEmpty()
        }
        for (callable in callables) {
            if (callable.visibility != KaSymbolVisibility.PUBLIC) continue
            val item = callableItem(callable.asSignature(), null) ?: continue
            item.labelDetails = (item.labelDetails ?: CompletionItemLabelDetails()).apply { description = fqName }
            item.additionalTextEdits = listOfNotNull(edit)
            items.putIfAbsent(fqName + item.label + (item.labelDetails?.detail ?: ""), item)
        }
    }
    return items.values.take(MAX_COMPLETION_ITEMS)
}

/**
 * Extensions offered right after a dot from the default imports, before anything is typed: the scope functions and
 * the most used collection, text and nullability helpers. Each is still checked against the receiver's type.
 */
private val COMMON_EXTENSIONS = listOf(
    "let", "also", "apply", "run", "takeIf", "takeUnless", "to", "runCatching",
    "map", "mapNotNull", "filter", "filterNot", "filterIsInstance", "forEach", "forEachIndexed", "first", "firstOrNull",
    "last", "lastOrNull", "find", "any", "all", "none", "count", "sumOf", "groupBy", "associateBy", "associate",
    "sortedBy", "sortedByDescending", "distinct", "distinctBy", "flatMap", "joinToString", "toList", "toMutableList",
    "toSet", "toMap", "getOrNull", "getOrElse", "orEmpty", "isNullOrEmpty", "isNullOrBlank", "isNotEmpty", "isEmpty",
    "isNotBlank", "isBlank", "lowercase", "uppercase", "trim", "split", "replace", "contains", "startsWith", "endsWith",
    "substringBefore", "substringAfter", "toInt", "toIntOrNull", "toLong", "toDouble", "toBoolean", "format",
    "removeLastOrNull", "removeFirstOrNull", "plus", "minus", "zip", "chunked", "windowed", "take", "drop",
    "collect", "onEach", "catch", "launchIn", "stateIn", "first", "combine", "flowOn",
).distinct().map { Name.identifier(it) }
