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

import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.ClassId
import org.kotlinlsp.index.queries.getCompletions
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.Index
import org.kotlinlsp.actions.autocomplete.parameterNames
import com.intellij.psi.util.parentsOfType
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Tuple
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.types.Variance

/**
 * Parameter info for the call around the cursor (IntelliJ's Cmd+P): every overload with its parameters, types and
 * defaults, the overload that fits best, and the parameter being typed, by name (`modifier = |`) or by position.
 */
@OptIn(KaExperimentalApi::class)
fun signatureHelpAction(ktFile: KtFile, offset: Int, index: Index): SignatureHelp? {
    val element = ktFile.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
    // The innermost call whose parentheses contain the cursor.
    val list = element.parentsOfType<KtValueArgumentList>(withSelf = true).firstOrNull { list ->
        offset > list.textRange.startOffset && (list.rightParenthesis == null || offset <= list.rightParenthesis!!.textRange.startOffset)
    } ?: return null
    val call = list.parent as? KtCallElement ?: return null

    return analyze(call) {
        val candidates = runCatching { call.resolveToCallCandidates() }.getOrNull().orEmpty()
            .mapNotNull { info ->
                val function = (info.candidate as? KaFunctionCall<*>)?.partiallyAppliedSymbol?.signature?.symbol as? KaFunctionSymbol
                function?.let { it to info.isInBestCandidates }
            }
            .distinctBy { (function, _) -> function }
            // A call that doesn't resolve yet (`Row(` before its import is added): the functions and classes of that
            // name from the index, as completion offers them.
            .ifEmpty { byName(call, index).map { it to false } }
        if (candidates.isEmpty()) return@analyze null

        // The argument the cursor is in: by name, else its position among the arguments before it.
        val arguments = list.arguments
        val current = arguments.indexOfFirst { offset <= it.textRange.endOffset }.let { if (it < 0) arguments.size else it }
        val typedPosition = list.text.substring(0, offset - list.textRange.startOffset).count { it == ',' }
        val position = maxOf(current, typedPosition).coerceAtLeast(0)
        val currentName = arguments.getOrNull(position)?.getArgumentName()?.asName?.asString()
            ?: NAMED.find(list.text.substring(0, offset - list.textRange.startOffset).substringAfterLast(','))?.groupValues?.get(1)

        val signatures = candidates.map { (function, _) -> signatureOf(function) }
        val activeSignature = candidates.indexOfFirst { it.second }.takeIf { it >= 0 }
            ?: candidates.indexOfFirst { (function, _) -> function.valueParameters.size > position }.coerceAtLeast(0)
        val active = candidates[activeSignature].first
        val activeParameter = currentName?.let { name -> parameterNames(active).indexOf(name) }
            ?.takeIf { it >= 0 }
            ?: position.coerceAtMost(active.valueParameters.lastIndex.coerceAtLeast(0))
        SignatureHelp(signatures, activeSignature, activeParameter)
    }
}

/** Top-level functions, and constructors of classes, named like the callee, found through the index. */
private fun KaSession.byName(call: KtCallElement, index: Index): List<KaFunctionSymbol> {
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
    val declarations = runCatching { index.getCompletions(name).filter { it.name == name }.take(50).toList() }.getOrDefault(emptyList())
    val packages = declarations.mapNotNull { (it as? Declaration.Function)?.takeIf { f -> f.isTopLevel && !f.isPrivate }?.fqName?.substringBeforeLast('.', "") }.distinct()
    val functions = packages.flatMap { findTopLevelCallables(FqName(it), Name.identifier(name)).filterIsInstance<KaNamedFunctionSymbol>() }
    val constructors = declarations.filterIsInstance<Declaration.Class>().filter { it.isTopLevel && !it.isPrivate }
        .mapNotNull { findClass(ClassId.topLevel(FqName(it.fqName))) }
        .flatMap { it.declaredMemberScope.constructors }
    return (functions + constructors).distinct()
}

private val NAMED = Regex("""(\w+)\s*=\s*[^=]*$""")

@OptIn(KaExperimentalApi::class)
private fun KaSession.signatureOf(function: KaFunctionSymbol): SignatureInformation {
    val name = when (function) {
        is KaConstructorSymbol -> function.containingClassId?.shortClassName?.asString() ?: "constructor"
        is KaNamedFunctionSymbol -> function.name.asString()
        else -> "function"
    }
    val typeParameters = (function as? KaNamedFunctionSymbol)?.typeParameters.orEmpty()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ", "<", ">") { parameter ->
            val bound = parameter.upperBounds.firstOrNull { !(it.isAnyType && it.isMarkedNullable) }
            parameter.name.asString() + (bound?.let { ": ${render(it)}" } ?: "")
        } ?: ""
    val label = StringBuilder("$name$typeParameters(")
    val names = parameterNames(function)
    val parameters = function.valueParameters.mapIndexed { i, parameter ->
        if (i > 0) label.append(", ")
        val start = label.length
        label.append(parameterText(parameter, names.getOrNull(i)))
        ParameterInformation().apply { this.label = Either.forRight(Tuple.two(start, label.length)) }
    }
    label.append(")")
    if (!function.returnType.isUnitType) label.append(": ").append(render(function.returnType))
    return SignatureInformation(label.toString()).apply { this.parameters = parameters }
}

/** `name: Type = default`, with the default's text when the source is at hand. */
@OptIn(KaExperimentalApi::class)
private fun KaSession.parameterText(parameter: KaValueParameterSymbol, name: String?): String {
    val vararg = if (parameter.isVararg) "vararg " else ""
    val text = "$vararg${name ?: parameter.name.asString()}: ${render(parameter.returnType)}"
    if (!parameter.hasDefaultValue) return text
    val default = (parameter.psi as? KtParameter)?.defaultValue?.text?.takeIf { it.length <= 40 } ?: "…"
    return "$text = $default"
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.render(type: KaType): String = type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
