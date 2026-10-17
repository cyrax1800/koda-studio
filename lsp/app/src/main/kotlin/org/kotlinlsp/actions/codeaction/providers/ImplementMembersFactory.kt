/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions.codeaction.providers

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.kotlinlsp.actions.autocomplete.DEFAULT_IMPORTS
import org.kotlinlsp.actions.autocomplete.ImportInserter
import org.kotlinlsp.actions.autocomplete.classNames
import org.kotlinlsp.actions.autocomplete.parameterNames
import org.kotlinlsp.actions.autocomplete.render
import org.kotlinlsp.actions.codeaction.CodeActionContext
import org.kotlinlsp.actions.codeaction.CodeActionFactory
import org.kotlinlsp.common.toOffset

/**
 * Implement Members (Cmd+. on a class or `object : LifecycleEventObserver { }` that doesn't implement everything): the
 * abstract members still missing, with their full signatures (real parameter names, also for Java libraries) and
 * `TODO("Not yet implemented")` bodies, plus the imports they need. Offered for all members at once and for each.
 */
class ImplementMembersFactory : CodeActionFactory() {
    /**
     * The client asks for code actions on every cursor move: members are only worked out on a "not implemented" error,
     * or with the cursor in the header (`object : LifecycleEventObserver`, `class Foo : Bar()`), not anywhere in a body.
     */
    override fun isApplicable(context: CodeActionContext): Boolean {
        val owner = ownerAt(context) ?: return false
        if (context.diagnostics.any { it.code?.left?.contains("NOT_IMPLEMENTED") == true }) return true
        val offset = runCatching { context.range.start.toOffset(context.ktFile) }.getOrNull() ?: return false
        val headerEnd = owner.body?.textRange?.startOffset ?: owner.textRange.endOffset
        return offset in owner.textRange.startOffset..headerEnd
    }

    override fun doCreateCodeActions(context: CodeActionContext): List<CodeAction> {
        val owner = ownerAt(context) ?: return emptyList()
        return analyze(owner) {
            val missing = missingMembers(owner)
            if (missing.isEmpty()) return@analyze emptyList()
            val actions = mutableListOf<CodeAction>()
            actions += action(context, owner, missing, "Implement members (${missing.size})", preferred = true)
            if (missing.size > 1) for (member in missing) {
                actions += action(context, owner, listOf(member), "Implement '${member.name?.asString()}'", preferred = false)
            }
            actions
        }
    }

    private fun ownerAt(context: CodeActionContext): KtClassOrObject? {
        val offset = runCatching { context.range.start.toOffset(context.ktFile) }.getOrNull() ?: return null
        val element = context.ktFile.findElementAt(offset) ?: return null
        return element.parentOfType<KtClassOrObject>(withSelf = true)
    }

    /** Abstract members inherited and not implemented, in a class that must implement them (not abstract, not an interface). */
    private fun KaSession.missingMembers(owner: KtClassOrObject): List<KaCallableSymbol> {
        val symbol = owner.classSymbol as? KaClassSymbol ?: return emptyList()
        if (symbol.classKind == KaClassKind.INTERFACE || symbol.modality == KaSymbolModality.ABSTRACT || symbol.modality == KaSymbolModality.SEALED) return emptyList()
        return symbol.memberScope.callables
            .filter { (it is KaNamedFunctionSymbol || it is KaPropertySymbol) && it.modality == KaSymbolModality.ABSTRACT && it.containingSymbol != symbol }
            .distinctBy { member -> member.name?.asString() + ((member as? KaNamedFunctionSymbol)?.valueParameters?.joinToString { render(it.returnType) } ?: "") }
            .toList()
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.action(
        context: CodeActionContext,
        owner: KtClassOrObject,
        members: List<KaCallableSymbol>,
        title: String,
        preferred: Boolean,
    ): CodeAction {
        val file = owner.containingKtFile
        val text = file.text
        val ownerIndent = indentOfLine(text, owner.textRange.startOffset)
        val unit = indentUnit(text)
        val memberIndent = ownerIndent + unit
        val stubs = members.joinToString("\n\n") { stub(it, memberIndent, unit) }

        val edits = mutableListOf<TextEdit>()
        val body = owner.body
        val rBrace = body?.rBrace
        if (body != null && rBrace != null) {
            // From the last thing in the body (or the `{`) to the `}`: the members on their own lines, `}` back in place.
            val lastContent = (rBrace.prevSibling?.let { generateSequence(it) { s -> s.prevSibling } }
                ?.firstOrNull { it.text.isNotBlank() })
            val start = lastContent?.textRange?.endOffset ?: body.textRange.startOffset + 1
            val separator = if (lastContent != null && lastContent != body.lBrace) "\n\n" else "\n"
            edits += TextEdit(range(text, start, rBrace.textRange.startOffset), "$separator$stubs\n$ownerIndent")
        } else {
            // `object : Runnable` without a body; the declaration can take in the blank lines after it.
            var end = owner.textRange.endOffset
            while (end > owner.textRange.startOffset && text[end - 1].isWhitespace()) end--
            edits += TextEdit(range(text, end, end), " {\n$stubs\n$ownerIndent}")
        }

        val imports = ImportInserter(file)
        members.flatMap { member ->
            listOf(member.returnType) + ((member as? KaNamedFunctionSymbol)?.valueParameters?.map { it.returnType } ?: emptyList())
        }
            .flatMap { classNames(it) }
            .filter { it.substringBeforeLast('.', "") !in DEFAULT_IMPORTS }
            .distinct()
            .mapNotNullTo(edits) { imports.editFor(it) }

        return createCodeAction(title, CodeActionKind.QuickFix, createWorkspaceEdit(context.uri, edits), preferred)
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.stub(member: KaCallableSymbol, indent: String, unit: String): String {
        val todo = "TODO(\"Not yet implemented\")"
        return when (member) {
            is KaNamedFunctionSymbol -> {
                val typeParameters = member.typeParameters.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", "> ") { it.name.asString() } ?: ""
                val names = parameterNames(member)
                val parameters = member.valueParameters.withIndex().joinToString(", ") { (i, it) ->
                    (if (it.isVararg) "vararg " else "") + "${names[i]}: ${render(it.returnType)}"
                }
                val returns = if (member.returnType.isUnitType) "" else ": ${render(member.returnType)}"
                val suspend = if (member.isSuspend) "suspend " else ""
                "${indent}override ${suspend}fun $typeParameters${member.name.asString()}($parameters)$returns {\n$indent$unit$todo\n$indent}"
            }
            is KaPropertySymbol -> {
                val kind = if (member.isVal) "val" else "var"
                "${indent}override $kind ${member.name.asString()}: ${render(member.returnType)}\n$indent${unit}get() = $todo"
            }
            else -> ""
        }
    }

    private fun indentOfLine(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
    }

    /** The file's indentation step: tabs, or the smallest indent of an indented line (pokedex uses 2, most 4). */
    private fun indentUnit(text: String): String {
        val indents = text.lineSequence().filter { it.isNotBlank() }.map { line -> line.takeWhile { it == ' ' || it == '\t' } }
            .filter { it.isNotEmpty() }.take(200).toList()
        if (indents.any { it.startsWith('\t') }) return "\t"
        val smallest = indents.minOfOrNull { it.length } ?: 4
        return " ".repeat(smallest.coerceIn(2, 8))
    }

    private fun range(text: String, start: Int, end: Int) = Range(position(text, start), position(text, end))

    private fun position(text: String, offset: Int): Position {
        val line = text.substring(0, offset).count { it == '\n' }
        return Position(line, offset - (text.lastIndexOf('\n', offset - 1) + 1))
    }
}

