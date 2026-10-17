/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.calls
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtOperationExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtPackageDirective

/**
 * Optimize Imports (Android Studio's Ctrl+Option+O): drops the imports nothing in the file uses and sorts the rest in
 * IntelliJ's Kotlin layout (others, then `java.*`, `javax.*`, `kotlin.*`, then aliases). An import is used when a
 * reference in the code or KDoc resolves to what it imports, including the implicit ones (`by` delegates' `getValue`,
 * operators such as Gradle's `assign`, `componentN`). An import whose name has an unresolved reference is kept, and so are
 * operator imports in a file with unresolved code, so a file with errors doesn't lose imports it will need.
 */
fun organizeImportsEdits(ktFile: KtFile): List<TextEdit> {
    val importList = ktFile.importList ?: return emptyList()
    val directives = importList.imports
    if (directives.isEmpty()) return emptyList()
    // Comments between imports belong to someone; the list is left alone rather than lose them.
    if (importList.children.any { it is PsiComment }) return emptyList()

    val (used, unresolved) = analyze(ktFile) { usedNames(ktFile) }
    // Operators are used without their name (`x = y` calling Gradle's `assign`, `a + b`, `by`): with code that doesn't
    // resolve, such a call can't be seen, so their imports stay.
    val keepOperators = unresolved.isNotEmpty()
    val packageName = ktFile.packageFqName
    val kept = directives
        .filter { directive -> isUsed(directive, used, unresolved, packageName, keepOperators) }
        .distinctBy { it.text.replace(Regex("\\s+"), " ") }
        .sortedWith(compareBy({ group(it) }, { it.importPath?.pathStr ?: "" }))
    val text = kept.joinToString("\n") { it.text.trim() }

    val original = ktFile.text
    val start = importList.textRange.startOffset
    var end = importList.textRange.endOffset
    // Nothing left: the blank lines after the imports go too.
    if (text.isEmpty()) while (end < original.length && original[end].isWhitespace()) end++
    val updated = original.substring(0, start) + text + original.substring(end)
    return minimalEdit(original, updated)
}

private fun isUsed(directive: KtImportDirective, used: Set<FqName>, unresolved: Set<String>, packageName: FqName, keepOperators: Boolean): Boolean {
    val fqName = directive.importedFqName ?: return true
    if (directive.isAllUnder) return true
    val name = directive.aliasName ?: fqName.shortName().asString()
    if (keepOperators && (name in OPERATOR_NAMES || name.startsWith("component"))) return true
    // Same package: the import adds nothing.
    if (directive.aliasName == null && fqName.parent() == packageName) return false
    return fqName in used || name in unresolved
}

/** IntelliJ's default Kotlin import layout: `*`, `java.*`, `javax.*`, `kotlin.*`, then alias imports. */
fun importGroup(path: String, isAlias: Boolean): Int = when {
    isAlias -> 4
    path.startsWith("java.") -> 1
    path.startsWith("javax.") -> 2
    path.startsWith("kotlin.") -> 3
    else -> 0
}

private fun group(directive: KtImportDirective): Int =
    importGroup(directive.importedFqName?.asString() ?: "", directive.aliasName != null)

/** What the file's references resolve to, and the names of the references that don't resolve. */
private fun KaSession.usedNames(ktFile: KtFile): Pair<Set<FqName>, Set<String>> {
    val used = HashSet<FqName>()
    val unresolved = HashSet<String>()
    ktFile.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element is KtImportList || element is KtPackageDirective) return
            for (reference in element.references) {
                if (reference !is KtReference) continue
                val symbols = runCatching { reference.resolveToSymbols() }.getOrDefault(emptyList())
                if (symbols.isEmpty()) {
                    if (reference is KtSimpleNameReference) unresolved += reference.expression.getReferencedName()
                    continue
                }
                // `Alignment.Center` resolves `Alignment` to its companion object, and `Outer.Inner` names Outer too:
                // the classes around a class count as used.
                for (symbol in symbols) {
                    var name = importableName(symbol)
                    while (name != null && !name.isRoot) {
                        used += name
                        name = if (symbol is KaClassLikeSymbol || symbol is KaConstructorSymbol) name.parent() else null
                    }
                }
            }
            // Calls without a name: `x = y` (Gradle's `assign`), `a + b`, `a[i]`, `in`, `for`, destructuring.
            if (element is KtOperationExpression || element is KtArrayAccessExpression || element is KtForExpression ||
                element is KtDestructuringDeclaration
            ) {
                val call = runCatching { (element as KtElement).resolveToCall()?.calls }.getOrNull().orEmpty()
                call.filterIsInstance<KaCallableMemberCall<*, *>>().forEach { importableName(it.partiallyAppliedSymbol.signature.symbol)?.let(used::add) }
                if (element is KtDestructuringDeclaration) element.entries.forEach { entry ->
                    runCatching { entry.resolveToCall()?.calls }.getOrNull().orEmpty()
                        .filterIsInstance<KaCallableMemberCall<*, *>>().forEach { importableName(it.partiallyAppliedSymbol.signature.symbol)?.let(used::add) }
                }
            }
            super.visitElement(element)
        }
    })
    return used to unresolved
}

private val OPERATOR_NAMES = setOf(
    "assign", "getValue", "setValue", "provideDelegate", "plus", "minus", "times", "div", "rem", "unaryPlus", "unaryMinus",
    "not", "inc", "dec", "contains", "get", "set", "invoke", "iterator", "next", "hasNext", "compareTo", "rangeTo",
    "rangeUntil", "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign",
)

private fun importableName(symbol: KaSymbol): FqName? = when (symbol) {
    is KaConstructorSymbol -> symbol.containingClassId?.asSingleFqName()
    is KaSamConstructorSymbol -> symbol.callableId?.asSingleFqName()
    is KaClassLikeSymbol -> symbol.classId?.asSingleFqName()
    is KaCallableSymbol -> symbol.callableId?.asSingleFqName()
    else -> null
}
