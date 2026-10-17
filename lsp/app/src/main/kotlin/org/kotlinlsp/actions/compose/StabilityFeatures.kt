/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions.compose

import com.intellij.openapi.util.TextRange
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset

/** Shown in the editor as the analyzer reports it. */
private fun label(kind: StabilityKind) = when (kind) {
    StabilityKind.STABLE -> "stable"
    StabilityKind.RUNTIME -> "runtime"
    StabilityKind.UNSTABLE -> "unstable"
}

/** A warning on each unstable parameter of a restartable composable: it is why the composable can't skip. */
fun KaSession.unstableParameterWarnings(ktFile: KtFile): List<Diagnostic> =
    composablesIn(ktFile).filter { it.restartable }.flatMap { composable ->
        composable.parameters.filter { it.second.kind == StabilityKind.UNSTABLE }.map { (parameter, stability) ->
            val target = parameter.nameIdentifier ?: parameter
            Diagnostic(
                target.textRange.toLspRange(ktFile),
                "`${parameter.name}` is unstable: ${stability.reason}. `${composable.function.name}` can't skip recomposition.",
                DiagnosticSeverity.Warning,
                "Compose stability",
                "UnstableParameter",
            )
        }
    }

/** `stable`, `runtime` or `unstable` after each parameter of the composables in [range], with the reason on hover. */
fun KaSession.stabilityInlayHints(ktFile: KtFile, range: Range): List<InlayHint> {
    val visible = TextRange(range.start.toOffset(ktFile), range.end.toOffset(ktFile))
    return composablesIn(ktFile)
        .filter { it.function.textRange.intersects(visible) }
        .flatMap { composable ->
            composable.parameters.map { (parameter, stability) ->
                val end = (parameter.typeReference ?: parameter).textRange.endOffset
                InlayHint(TextRange(end, end).toLspRange(ktFile).start, Either.forLeft(label(stability.kind))).apply {
                    paddingLeft = true
                    tooltip = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "Compose stability: **${stability.kind}** — ${stability.reason}"))
                }
            }
        }
}

/** Markdown added to the hover of a composable function or one of its parameters. */
fun KaSession.stabilityHover(target: Any?): String? = when (target) {
    is KtNamedFunction -> composableStability(target)?.let { composable ->
        buildString {
            append("**Compose stability**: ")
            append(if (composable.skippable) "skippable" else "not skippable")
            append(" · ")
            append(if (composable.restartable) "restartable" else "not restartable")
            for ((parameter, stability) in composable.parameters) {
                append("\n- `${parameter.name}`: **${stability.kind}** — ${stability.reason}")
            }
        }
    }
    is KtParameter -> (target.parent?.parent as? KtNamedFunction)?.let { composableStability(it) }
        ?.parameters?.firstOrNull { it.first == target }
        ?.let { (_, stability) -> "**Compose stability**: ${stability.kind} — ${stability.reason}" }
    else -> null
}
