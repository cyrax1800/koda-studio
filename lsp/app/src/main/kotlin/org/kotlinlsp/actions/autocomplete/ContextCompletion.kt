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

import com.intellij.openapi.util.text.StringUtil
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InsertTextFormat
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaCallCandidateInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/** Where the word being typed stands, which decides the keywords that fit. */
enum class CompletionPosition { TOP_LEVEL, CLASS_BODY, STATEMENT, EXPRESSION }

fun completionPosition(element: PsiElement): CompletionPosition {
    // The typed word's own expression, then what contains it.
    var node: PsiElement = element
    if (node is KtNameReferenceExpression) node = node.parent ?: return CompletionPosition.EXPRESSION
    return when (node) {
        is KtFile -> CompletionPosition.TOP_LEVEL
        is KtClassBody -> CompletionPosition.CLASS_BODY
        is KtBlockExpression -> CompletionPosition.STATEMENT
        else -> when (node.parent) {
            is KtFile -> CompletionPosition.TOP_LEVEL
            is KtClassBody -> CompletionPosition.CLASS_BODY
            else -> CompletionPosition.EXPRESSION
        }
    }
}

private val DECLARATIONS = listOf("fun", "val", "var", "class", "interface", "object", "typealias")
private val MODIFIERS = listOf(
    "private", "internal", "public", "abstract", "open", "sealed", "data", "enum", "annotation", "inline", "value",
    "suspend", "const",
)
private val MEMBER_KEYWORDS = listOf("override", "protected", "final", "lateinit", "companion", "inner", "init", "constructor", "operator", "infix")
private val EXPRESSIONS = listOf("if", "when", "try", "this", "super", "null", "true", "false", "object", "throw")
private val STATEMENTS = listOf("val", "var", "fun", "return", "for", "while", "do", "break", "continue", "class")

/** Keywords as they are usually followed: a name after `fun`, a condition after `if`, nothing after `null`. */
private fun keywordInsert(keyword: String): Pair<String, InsertTextFormat> = when (keyword) {
    "if", "while", "for", "when" -> "$keyword (\$1) {\n\t\$0\n}" to InsertTextFormat.Snippet
    "try" -> "try {\n\t\$0\n} catch (e: Exception) {\n}" to InsertTextFormat.Snippet
    "init" -> "init {\n\t\$0\n}" to InsertTextFormat.Snippet
    "do" -> "do {\n\t\$0\n} while (\$1)" to InsertTextFormat.Snippet
    "this", "super", "null", "true", "false", "break", "continue", "return" -> keyword to InsertTextFormat.PlainText
    else -> "$keyword " to InsertTextFormat.PlainText
}

fun keywordItems(position: CompletionPosition, prefix: String): List<CompletionItem> {
    if (prefix.isEmpty()) return emptyList()
    val keywords = when (position) {
        CompletionPosition.TOP_LEVEL -> listOf("package", "import") + DECLARATIONS + MODIFIERS
        CompletionPosition.CLASS_BODY -> DECLARATIONS + MODIFIERS + MEMBER_KEYWORDS
        CompletionPosition.STATEMENT -> STATEMENTS + EXPRESSIONS
        CompletionPosition.EXPRESSION -> EXPRESSIONS
    }
    return keywords.distinct().filter { it.startsWith(prefix) && it != prefix }.map { keyword ->
        val (text, format) = keywordInsert(keyword)
        CompletionItem(keyword).apply {
            kind = CompletionItemKind.Keyword
            insertText = text
            insertTextFormat = format
            sortText = "1$keyword"
        }
    }
}

/**
 * `name =` for the parameters of the call being typed into that aren't passed yet (`Row(mod` → `modifier =`), from
 * every candidate overload. They sort first, as in Android Studio.
 */
fun KaSession.namedArgumentItems(element: PsiElement, prefix: String): List<CompletionItem> {
    val argument = element.parentOfType<KtValueArgument>(withSelf = true) ?: return emptyList()
    if (argument is KtLambdaArgument || argument.getArgumentName() != null) return emptyList()
    if (argument.getArgumentExpression() != element) return emptyList()
    val call = (argument.parent as? KtValueArgumentList)?.parent as? KtCallElement ?: return emptyList()
    val passed = call.valueArguments.mapNotNullTo(HashSet()) { it.getArgumentName()?.asName?.asString() }
    val candidates = try {
        call.resolveToCallCandidates().mapNotNull<KaCallCandidateInfo, KaFunctionSymbol> { info ->
            (info.candidate as? KaFunctionCall<*>)?.partiallyAppliedSymbol?.signature?.symbol as? KaFunctionSymbol
        }
    } catch (e: Exception) {
        return emptyList()
    }
    val items = LinkedHashMap<String, CompletionItem>()
    for (function in candidates) {
        if (!function.hasStableParameterNames) continue // Java parameters have no names to pass
        for (parameter in function.valueParameters) {
            val name = parameter.name.asString()
            if (name in passed || !name.startsWith(prefix, ignoreCase = true) || name in items) continue
            items[name] = CompletionItem("$name =").apply {
                kind = CompletionItemKind.Variable
                labelDetails = CompletionItemLabelDetails().apply { description = render(parameter.returnType) }
                insertText = "$name = "
                filterText = name
                sortText = "0$name"
            }
        }
    }
    return items.values.toList()
}

internal val DEFAULT_IMPORTS = setOf(
    "kotlin", "kotlin.annotation", "kotlin.collections", "kotlin.comparisons", "kotlin.io", "kotlin.ranges",
    "kotlin.sequences", "kotlin.text", "kotlin.jvm", "java.lang",
)

/**
 * In a class body, each open or abstract inherited member that isn't overridden yet, completed as its whole signature:
 * abstract members get a `TODO` body, open ones call `super`. Offered when typing `override` or the member's name.
 */
@OptIn(KaExperimentalApi::class)
fun KaSession.overrideItems(ktFile: KtFile, element: PsiElement, prefix: String, offset: Int): List<CompletionItem> {
    val owner = element.parentOfType<KtClassOrObject>(withSelf = true) ?: return emptyList()
    val classSymbol = owner.classSymbol as? KaClassSymbol ?: return emptyList()
    // `override on|`, `override fun on|`, `fun on|`: the header written so far is replaced by the whole signature.
    val wordStart = offset - prefix.length
    val header = HEADER_BEFORE_NAME.find(ktFile.text.substring(0, wordStart))?.takeIf { it.value.isNotBlank() }
    val keywordTyped = header != null
    val headerKind = header?.groups?.get(1)?.value // fun, val or var
    val overridden = classSymbol.declaredMemberScope.callables.mapNotNullTo(HashSet()) { it.name?.asString() }
    val imports = ImportInserter(ktFile)
    val items = mutableListOf<CompletionItem>()
    for (member in classSymbol.memberScope.callables) {
        if (member !is KaNamedFunctionSymbol && member !is KaPropertySymbol) continue
        if (headerKind == "fun" && member !is KaNamedFunctionSymbol) continue
        if ((headerKind == "val" || headerKind == "var") && member !is KaPropertySymbol) continue
        val name = member.name?.asString() ?: continue
        if (member.containingSymbol == classSymbol || name in overridden) continue
        if (member.modality != KaSymbolModality.OPEN && member.modality != KaSymbolModality.ABSTRACT) continue
        if (member.visibility == KaSymbolVisibility.PRIVATE) continue
        val matches = name.startsWith(prefix, ignoreCase = true) || (!keywordTyped && "override".startsWith(prefix))
        if (!matches) continue
        val abstract = member.modality == KaSymbolModality.ABSTRACT
        val (signature, body) = when (member) {
            is KaNamedFunctionSymbol -> {
                val typeParameters = member.typeParameters.joinToString(", ", "<", "> ") { it.name.asString() }.takeIf { member.typeParameters.isNotEmpty() } ?: ""
                // `onCreate(savedInstanceState: Bundle?)`, not `onCreate(p0: Bundle?)`, when the SDK's sources are installed.
                val names = parameterNames(member)
                val parameters = member.valueParameters.withIndex().joinToString(", ") { (i, it) -> (if (it.isVararg) "vararg " else "") + "${names[i]}: ${render(it.returnType)}" }
                val returns = if (member.returnType.isUnitType) "" else ": ${render(member.returnType)}"
                val suspend = if (member.isSuspend) "suspend " else ""
                val superCall = "super.$name(${member.valueParameters.withIndex().joinToString(", ") { (i, it) -> (if (it.isVararg) "*" else "") + names[i] }})"
                "${suspend}fun $typeParameters$name($parameters)$returns" to
                    " {\n\t\${0:${if (abstract) "TODO(\"Not yet implemented\")" else if (member.returnType.isUnitType) superCall else "return $superCall"}}\n}"
            }
            is KaPropertySymbol -> {
                val kind = if (member.isVal) "val" else "var"
                "$kind $name: ${render(member.returnType)}" to
                    "\n\tget() = \${0:${if (abstract) "TODO(\"Not yet implemented\")" else "super.$name"}}"
            }
            else -> continue
        }
        val typesToImport = (listOf(member.returnType) + ((member as? KaNamedFunctionSymbol)?.valueParameters?.map { it.returnType } ?: emptyList()))
            .flatMap { classNames(it) }
            .filter { it.substringBeforeLast('.', "") !in DEFAULT_IMPORTS }
            .distinct()
        items += CompletionItem("override $signature").apply {
            kind = if (member is KaPropertySymbol) CompletionItemKind.Property else CompletionItemKind.Method
            labelDetails = CompletionItemLabelDetails().apply { description = (member.containingSymbol as? KaClassSymbol)?.name?.asString() }
            insertTextFormat = InsertTextFormat.Snippet
            if (header != null) {
                // Replace `override fun onSa` with the whole `override fun onSaveInstanceState(…) { … }`.
                val headerText = ktFile.text.substring(header.range.first, wordStart)
                textEdit = Either.forLeft(TextEdit(rangeOf(ktFile, header.range.first, offset), "override $signature$body"))
                filterText = headerText + name
            } else {
                insertText = "override $signature$body"
                filterText = if (!"override".startsWith(prefix)) name else "override $name"
            }
            additionalTextEdits = typesToImport.mapNotNull { imports.editFor(it) }
            sortText = "0$name"
        }
    }
    return items
}

/** `override `, `override fun `, `fun `, `override suspend fun `… right before the member name being typed. */
private val HEADER_BEFORE_NAME = Regex("(?<![\\w])(?:override[ \\t]+)?(?:suspend[ \\t]+)?(fun|val|var)[ \\t]+$|(?<![\\w])override[ \\t]+$")

private fun rangeOf(ktFile: KtFile, start: Int, end: Int): Range {
    val text = ktFile.text
    fun position(offset: Int) = StringUtil.offsetToLineColumn(text, offset).let { Position(it.line, it.column) }
    return Range(position(start), position(end))
}

/** Classes a type mentions, including type arguments, as importable names (the outermost class of a nested one). */
internal fun KaSession.classNames(type: KaType): List<String> {
    val classType = type as? KaClassType ?: return emptyList()
    val own = classType.classId.outermostClassId.asSingleFqName().asString()
    return listOf(own) + classType.typeArguments.mapNotNull { it.type }.flatMap { classNames(it) }
}
