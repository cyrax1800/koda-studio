/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions.autocomplete

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtUserType
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions

/**
 * Completion where a type is written: `val x: Frag|`, `object : FragmentManager.Fragm|() { }`, `@Compo|`. After
 * `Outer.` only Outer's nested classes; after `some.package.` that package's classes; otherwise the classes in scope
 * and, from the index, the ones not imported yet. Functions, properties and keywords don't belong there: offering
 * them put a `fragmentLifecycleCallbacks` property above the `FragmentLifecycleCallbacks` class being typed.
 */
fun typeCompletion(
    ktFile: KtFile,
    offset: Int,
    index: Index,
    userType: KtUserType,
    prefix: String,
    onPartial: () -> Unit,
): Sequence<CompletionItem> {
    val matches = { name: Name -> name.asString().startsWith(prefix, ignoreCase = true) }
    val qualifier = userType.qualifier
        ?: return autoCompletionGeneric(ktFile, offset, index, userType, prefix, onPartial, typesOnly = true)

    val packageName = analyze(userType) {
        when (val target = qualifier.referenceExpression?.mainReference?.resolveToSymbol()) {
            // `FragmentManager.` → FragmentLifecycleCallbacks, BackStackEntry, …
            is KaClassSymbol -> return target.staticMemberScope.classifiers(matches)
                .filter { isVisibleNested(it) }
                .mapNotNull { classifierItem(it)?.apply { sortText = "0$label" } }
                .toList().asSequence()
            is KaPackageSymbol -> target.fqName.asString()
            else -> null
        }
    } ?: return emptySequence()

    // `androidx.fragment.app.F` → that package's classes, written out in full, so no import. Right after the dot the
    // whole index would match; the list fills in from the first letter.
    if (prefix.isEmpty()) {
        onPartial()
        return emptySequence()
    }
    return index.getCompletions(prefix)
        .filterIsInstance<Declaration.Class>()
        .filter { it.isTopLevel && !it.isPrivate && it.fqName == "$packageName.${it.name}" }
        .distinctBy { it.fqName }
        .take(MAX_COMPLETION_ITEMS)
        .map { declaration ->
            CompletionItem(declaration.name).apply {
                kind = if (declaration.type == Declaration.Class.Type.INTERFACE) CompletionItemKind.Interface else CompletionItemKind.Class
                labelDetails = CompletionItemLabelDetails().apply { description = packageName }
                insertText = declaration.name
                sortText = "0${declaration.name}"
            }
        }
}

/** Nested classes usable from outside: not private, internal or package-private (`FragmentManager.OpGenerator`). */
fun isVisibleNested(symbol: KaClassifierSymbol): Boolean {
    val visibility = (symbol as? KaClassSymbol)?.visibility ?: return true
    return visibility == KaSymbolVisibility.PUBLIC || visibility == KaSymbolVisibility.PROTECTED
}
