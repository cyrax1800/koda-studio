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

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InsertTextFormat
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtValueArgumentList
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The type the code at the cursor expects (an argument, `val x: T =`, `return`, an assignment, a `when` subject, an
 * `if` condition), and the completion items that fit it. Fitting items sort first; in smart completion (Ctrl+Shift+Space)
 * they are the only ones, as in IntelliJ.
 */
class Expectation(val type: KaType, val classShortName: String?) {
    val matched: MutableSet<CompletionItem> = Collections.newSetFromMap(IdentityHashMap())
}

/** Ctrl+Shift+Space: the client asks for smart completion right before its next completion request. */
object SmartCompletion {
    @Volatile private var requestedAt = 0L

    fun request() {
        requestedAt = System.currentTimeMillis()
    }

    /** True once, for the completion that follows a request within two seconds. */
    fun consume(): Boolean {
        val requested = System.currentTimeMillis() - requestedAt < 2000
        requestedAt = 0
        return requested
    }
}

fun KaSession.expectationAt(element: KtElement, offset: Int): Expectation? {
    val type = (element as? KtExpression)?.takeIf { it !is KtValueArgumentList }?.let { runCatching { it.expectedType }.getOrNull() }
        ?: argumentType(element, offset)
        ?: valuePositionType(element, offset)
        ?: return null
    // `Any` fits everything and a type parameter or Unit (a statement) tells nothing.
    if (type is KaTypeParameterType || type.isUnitType || type.isAnyType || type.isNothingType) return null
    val shortName = (type as? KaClassType)?.classId?.shortClassName?.asString()
    return Expectation(type, shortName)
}

/**
 * Positions with nothing typed yet, found from the token before the cursor: `val x: T = |`, a parameter default
 * `p: T = |`, an assignment `x = |`, and `return |`.
 */
private fun KaSession.valuePositionType(element: KtElement, offset: Int): KaType? {
    val file = element.containingKtFile
    val text = file.text
    var i = offset - 1
    while (i >= 0 && text[i].isWhitespace()) i--
    if (i < 0) return null
    val token = file.findElementAt(i) ?: return null
    return when (token.node.elementType) {
        KtTokens.EQ -> when (val owner = token.parent) {
            is KtProperty -> if (owner.typeReference != null) owner.returnType else null
            is KtParameter -> owner.typeReference?.let { owner.returnType }
            is KtBinaryExpression -> owner.left?.expressionType
            else -> null
        }
        KtTokens.RETURN_KEYWORD -> token.parentOfType<KtNamedFunction>()?.takeIf { it.typeReference != null }?.returnType
        else -> null
    }
}

/**
 * `call(a, |)` or `call(name = |)` before anything is typed: the parameter by name, else at that position, of the first
 * candidate that has one.
 */
private fun KaSession.argumentType(element: KtElement, offset: Int): KaType? {
    val list = element as? KtValueArgumentList ?: element.parentOfType<KtValueArgumentList>() ?: return null
    val call = list.parent as? KtCallElement ?: return null
    val before = list.text.substring(0, (offset - list.textRange.startOffset).coerceIn(0, list.textLength))
    val position = before.count { it == ',' }
    // `name =` right before the cursor, in the argument being typed.
    val name = NAMED_ARGUMENT.find(before.substringAfterLast(','))?.groupValues?.get(1)
    val candidates = runCatching { call.resolveToCallCandidates() }.getOrNull() ?: return null
    return candidates.firstNotNullOfOrNull { info ->
        val function = (info.candidate as? KaFunctionCall<*>)?.partiallyAppliedSymbol?.signature?.symbol as? KaFunctionSymbol
        val parameters = function?.valueParameters ?: return@firstNotNullOfOrNull null
        (if (name != null) parameters.firstOrNull { it.name.asString() == name } else parameters.getOrNull(position))?.returnType
    }
}

private val NAMED_ARGUMENT = Regex("(\\w+)\\s*=\\s*\\w*$")

/** Whether a value of [type] fits; ranks [item] first and remembers it when it does. */
fun KaSession.rank(item: CompletionItem?, type: KaType?, expectation: Expectation?): CompletionItem? {
    if (item == null || expectation == null || type == null) return item
    if (type is KaTypeParameterType || type.isNothingType || type.isUnitType) return item
    if (runCatching { type.isSubtypeOf(expectation.type) }.getOrDefault(false)) {
        item.sortText = "!" + (item.sortText ?: item.label)
        expectation.matched += item
    }
    return item
}

/**
 * Values IntelliJ offers for the expected type without them being in scope: enum entries, sealed subclasses, the
 * matching members of the type's companion or of its enclosing object (`Alignment.CenterVertically`,
 * `Arrangement.Center`), `true`/`false`, `null`, and a lambda for a function type.
 */
fun KaSession.smartItems(expectation: Expectation, ktFile: KtFile): List<CompletionItem> {
    val items = mutableListOf<CompletionItem>()
    val imports = ImportInserter(ktFile)
    val type = expectation.type
    fun add(item: CompletionItem) {
        item.sortText = "!0" + item.label
        expectation.matched += item
        items += item
    }
    if (type.isMarkedNullable) add(keyword("null"))
    if (type.isBooleanType) {
        add(keyword("true"))
        add(keyword("false"))
    }
    if (type is KaFunctionType) {
        val parameters = type.parameterTypes.size
        add(CompletionItem("{ … }").apply {
            kind = CompletionItemKind.Snippet
            insertText = when (parameters) {
                0, 1 -> "{ \$0 }"
                else -> (1..parameters).joinToString(", ", "{ ", " -> \$0 }") { "\${$it:p$it}" }
            }
            insertTextFormat = InsertTextFormat.Snippet
            filterText = "{"
        })
    }
    val symbol = (type as? KaClassType)?.symbol as? KaNamedClassSymbol ?: return items
    val qualifier = symbol.classId?.relativeClassName?.asString() ?: return items
    val importEdit = symbol.classId?.outermostClassId?.asSingleFqName()?.asString()?.let { imports.editFor(it) }

    fun member(owner: String, name: String, callable: Boolean, detail: String?) = CompletionItem("$owner.$name").apply {
        kind = if (callable) CompletionItemKind.Method else CompletionItemKind.EnumMember
        insertText = if (callable) "$owner.$name(\$0)" else "$owner.$name"
        insertTextFormat = if (callable) InsertTextFormat.Snippet else InsertTextFormat.PlainText
        filterText = "$owner.$name"
        if (detail != null) labelDetails = CompletionItemLabelDetails().apply { description = detail }
        additionalTextEdits = listOfNotNull(importEdit)
    }

    if (symbol.classKind == KaClassKind.ENUM_CLASS) {
        symbol.staticDeclaredMemberScope.callables.filterIsInstance<KaEnumEntrySymbol>().forEach {
            add(member(qualifier, it.name.asString(), callable = false, detail = null))
        }
    }
    if (symbol.modality == KaSymbolModality.SEALED) {
        for (inheritor in symbol.sealedClassInheritors) {
            val name = inheritor.classId?.relativeClassName?.asString() ?: continue
            val isObject = inheritor.classKind == KaClassKind.OBJECT
            add(CompletionItem(name).apply {
                kind = if (isObject) CompletionItemKind.EnumMember else CompletionItemKind.Class
                insertText = if (isObject) name else "$name(\$0)"
                insertTextFormat = if (isObject) InsertTextFormat.PlainText else InsertTextFormat.Snippet
                additionalTextEdits = listOfNotNull(inheritor.classId?.outermostClassId?.asSingleFqName()?.asString()?.let { imports.editFor(it) })
            })
        }
    }
    // Members of the companion, and of the enclosing object or companion when the type is nested (Alignment.Vertical).
    val owners = buildList {
        symbol.companionObject?.let { add(qualifier to it) }
        val outerId = symbol.classId?.outerClassId
        val outer = outerId?.let { findClass(it) } as? KaNamedClassSymbol
        if (outer != null) {
            val outerName = outerId.relativeClassName.asString()
            if (outer.classKind == KaClassKind.OBJECT) add(outerName to outer)
            outer.companionObject?.let { add(outerName to it) }
        }
    }
    for ((ownerName, owner) in owners) {
        for (callable in owner.declaredMemberScope.callables) {
            if (callable.visibility != KaSymbolVisibility.PUBLIC) continue
            val fits = runCatching { callable.returnType.isSubtypeOf(type) }.getOrDefault(false)
            if (!fits) continue
            when (callable) {
                is KaPropertySymbol -> add(member(ownerName, callable.name.asString(), callable = false, detail = render(callable.returnType)))
                is KaNamedFunctionSymbol -> if (!callable.isExtension) add(member(ownerName, callable.name.asString(), callable = true, detail = render(callable.returnType)))
                else -> {}
            }
        }
    }
    return items
}

private fun keyword(word: String) = CompletionItem(word).apply {
    kind = CompletionItemKind.Keyword
    insertText = word
}

/** Whether an index item (not imported, so not analysed) returns the expected type, by its type's short name. */
fun fitsByName(returnType: String?, expectation: Expectation?): Boolean {
    val expected = expectation?.classShortName ?: return false
    return returnType != null && shortTypeName(returnType) == expected
}
