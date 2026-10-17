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

import com.intellij.openapi.util.TextRange
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.kotlinlsp.common.toLspRange

/**
 * Parameter names before positional call arguments, as IntelliJ shows them: `Text(text: "Hi")`, `padding(all: 16.dp)`.
 * [mode] `literals` (strings, numbers, `true`, `false`, `null`) or `all`. Skipped where the argument already says it
 * (`foo(name)` for a parameter `name`) and for Java methods, whose parameter names aren't reliable.
 */
fun KaSession.parameterNameHints(ktFile: KtFile, visible: TextRange, mode: String): List<InlayHint> {
    if (mode != "literals" && mode != "all") return emptyList()
    val hints = mutableListOf<InlayHint>()
    for (call in ktFile.collectDescendantsOfType<KtCallElement>()) {
        // Annotation arguments (`@SuppressLint("…")`) get no hints, as in IntelliJ.
        if (call is KtAnnotationEntry || !call.textRange.intersects(visible)) continue
        val positional = call.valueArguments.filter { it !is KtLambdaArgument && it.getArgumentName() == null }
            .mapNotNull { it.getArgumentExpression() }
            .filter { mode == "all" || isLiteral(it) }
        if (positional.isEmpty()) continue
        val resolved = runCatching { call.resolveToCall()?.successfulFunctionCallOrNull() }.getOrNull() ?: continue
        if (!resolved.partiallyAppliedSymbol.signature.symbol.hasStableParameterNames) continue
        val mapping = resolved.valueArgumentMapping
        for (argument in positional) {
            val name = mapping[argument]?.name?.asString() ?: continue
            if ((argument as? KtNameReferenceExpression)?.getReferencedName() == name) continue
            val start = argument.textRange.startOffset
            hints += InlayHint(TextRange(start, start).toLspRange(ktFile).start, Either.forLeft("$name:")).apply {
                kind = InlayHintKind.Parameter
                paddingRight = true
            }
        }
    }
    return hints
}

private fun isLiteral(expression: KtExpression): Boolean = when (expression) {
    is KtConstantExpression, is KtStringTemplateExpression -> true
    is KtPrefixExpression -> expression.baseExpression is KtConstantExpression // -1
    else -> false
}
