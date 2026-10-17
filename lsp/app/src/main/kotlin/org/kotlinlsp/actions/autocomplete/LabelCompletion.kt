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
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaScopeImplicitReceiverValue
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhileExpression

private val LABEL_BEFORE_CURSOR = Regex("""\b(this|super|return|break|continue)@([\p{L}_][\p{L}\p{N}_]*)?$""")

/**
 * The labels in reach after `this@`, `super@`, `return@`, `break@` or `continue@`; null when the cursor isn't after one.
 * - `this@`: every receiver in scope, innermost first, with its type: lambdas with a receiver named by their call
 *   (`lifecycleScope.apply { this@apply }`) or explicit label, enclosing classes, extension functions;
 * - `return@`: the enclosing lambdas and the function;
 * - `break@`, `continue@`: the labelled loops around the cursor.
 */
fun labelCompletion(ktFile: KtFile, offset: Int): List<CompletionItem>? {
    val text = ktFile.text
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
    val match = LABEL_BEFORE_CURSOR.find(text.substring(lineStart, offset)) ?: return null
    val keyword = match.groupValues[1]
    val typed = match.groupValues[2]
    val at = ktFile.findElementAt((offset - 1).coerceAtLeast(0)) ?: return emptyList()
    val element = at.parentOfType<KtElement>(withSelf = true) ?: return emptyList()

    val labels: List<Pair<String, String?>> = when (keyword) {
        "this", "super" -> receiverLabels(ktFile, element)
        "return" -> returnLabels(at)
        else -> loopLabels(at)
    }
    val start = StringUtil.offsetToLineColumn(text, offset - typed.length).let { Position(it.line, it.column) }
    val end = StringUtil.offsetToLineColumn(text, offset).let { Position(it.line, it.column) }
    return labels.distinctBy { it.first }.filter { it.first.startsWith(typed) }.mapIndexed { i, (label, type) ->
        CompletionItem("$keyword@$label").apply {
            kind = CompletionItemKind.Keyword
            if (type != null) labelDetails = CompletionItemLabelDetails().apply { description = type }
            textEdit = Either.forLeft(TextEdit(Range(start, end), label))
            filterText = label
            sortText = "0%03d".format(i) // innermost first
        }
    }
}

/** Receivers in scope, innermost first, as (label, rendered type). */
private fun receiverLabels(ktFile: KtFile, element: KtElement): List<Pair<String, String?>> = analyze(ktFile) {
    ktFile.scopeContext(element).implicitReceivers.mapNotNull { receiver: KaScopeImplicitReceiverValue ->
        val owner = receiver.ownerSymbol.psi
        val label = when (owner) {
            is KtFunctionLiteral -> lambdaLabel(owner)
            is KtClassOrObject -> owner.name
            is KtNamedFunction -> owner.name
            else -> null
        } ?: return@mapNotNull null
        label to render(receiver.type)
    }
}

private fun returnLabels(at: PsiElement): List<Pair<String, String?>> = generateSequence(at) { it.parent }
    .takeWhile { it !is KtFile }
    .mapNotNull { element ->
        when (element) {
            is KtFunctionLiteral -> lambdaLabel(element)
            is KtNamedFunction -> element.name
            else -> null
        }
    }
    .map { it to null }
    .toList()

private fun loopLabels(at: PsiElement): List<Pair<String, String?>> = generateSequence(at) { it.parent }
    .takeWhile { it !is KtFile && it !is KtFunctionLiteral && it !is KtNamedFunction }
    .filterIsInstance<KtLabeledExpression>()
    .filter { it.baseExpression is KtForExpression || it.baseExpression is KtWhileExpression || it.baseExpression is KtDoWhileExpression }
    .mapNotNull { it.getLabelName() }
    .map { it to null }
    .toList()

/** A lambda's label: explicit (`outer@ { }`), else the function it's passed to (`apply { }` → `apply`). */
private fun lambdaLabel(literal: KtFunctionLiteral): String? {
    val lambda = literal.parent as? KtLambdaExpression ?: return null
    (lambda.parent as? KtLabeledExpression)?.getLabelName()?.let { return it }
    val call = when (val argument = lambda.parent) {
        is KtLambdaArgument -> argument.parent as? KtCallExpression
        is KtValueArgument -> (argument.parent as? KtValueArgumentList)?.parent as? KtCallExpression
        else -> null
    }
    return call?.calleeExpression?.text
}
