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

import com.intellij.psi.PsiMethod
import org.kotlinlsp.analysis.services.LibraryParameterNames
import org.kotlinlsp.actions.importGroup
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.kotlinlsp.common.LaunchOptions
import com.intellij.openapi.util.text.StringUtil
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.types.Variance

/** Most items a completion returns; the list is marked incomplete when cut, so the client asks again as you type. */
const val MAX_COMPLETION_ITEMS = 300

/** Shared conversion of Analysis API symbols to completion items. */
@OptIn(KaExperimentalApi::class)
fun KaSession.render(type: KaType): String = type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)

@OptIn(KaExperimentalApi::class)
fun KaSession.callableItem(signature: KaCallableSignature<*>, expectation: Expectation? = null): CompletionItem? =
    rank(callableItemOf(signature), signature.returnType, expectation)

@OptIn(KaExperimentalApi::class)
private fun KaSession.callableItemOf(signature: KaCallableSignature<*>): CompletionItem? {
    val symbol: KaCallableSymbol = signature.symbol
    if (symbol.visibility == KaSymbolVisibility.PRIVATE && symbol.psi?.containingFile == null) return null
    val name = symbol.name?.asString() ?: return null
    val type = render(signature.returnType)
    return CompletionItem().apply {
        label = name
        if (signature is KaFunctionSignature<*>) {
            val parameters = signature.valueParameters
            kind = if (symbol.isExtension) CompletionItemKind.Function else CompletionItemKind.Method
            // Type parameters too: `entry<K>(…)`.
            val typeParameters = (symbol as? KaNamedFunctionSymbol)?.typeParameters.orEmpty()
                .takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { it.name.asString() } ?: ""
            // `observe(owner, observer)`, not `observe(p0, p1)`: Java library names restored from debug info or sources.
            val names = (symbol as? KaFunctionSymbol)?.let { parameterNames(it) }?.takeIf { it.size == parameters.size }
                ?: parameters.map { it.name.asString() }
            labelDetails = CompletionItemLabelDetails().apply {
                detail = typeParameters + parameters.indices.joinToString(", ", "(", ")") { "${names[it]}: ${render(parameters[it].returnType)}" }
                description = type
            }
            // Kotlin parameters are passed by name (`Text(text = …)`); Java ones have no reliable names.
            val named = LaunchOptions.namedArguments && (symbol as? KaFunctionSymbol)?.hasStableParameterNames != false
            // A reified type parameter that no argument (other than a lambda) mentions can't be inferred, so it's typed
            // out: `entry<|> { }`, `viewModel<|>()`.
            val explicitTypes = (symbol as? KaNamedFunctionSymbol)?.typeParameters.orEmpty()
                .filter { typeParameter ->
                    typeParameter.isReified && parameters.none { parameter ->
                        !isFunctionalType(parameter.returnType) &&
                            Regex("\\b${Regex.escape(typeParameter.name.asString())}\\b").containsMatchIn(render(parameter.returnType))
                    }
                }
                .map { it.name.asString() }
            insertText = callSnippet(name, parameters.mapIndexed { i, it ->
                SnippetParameter(
                    names[i],
                    isRequired = !it.symbol.hasDefaultValue && !it.symbol.isVararg,
                    // A Java functional interface (`Observer`, `Runnable`, `OnClickListener`) takes a lambda too.
                    isFunctional = isFunctionalType(it.returnType) || runCatching { it.returnType.isFunctionalInterface }.getOrDefault(false),
                )
            }, named, explicitTypes)
            insertTextFormat = InsertTextFormat.Snippet
        } else {
            kind = if (symbol is KaValueParameterSymbol || symbol.psi?.parent is org.jetbrains.kotlin.psi.KtBlockExpression) {
                CompletionItemKind.Variable
            } else {
                CompletionItemKind.Property
            }
            labelDetails = CompletionItemLabelDetails().apply { description = type }
            insertText = name
            insertTextFormat = InsertTextFormat.PlainText
        }
        sortText = sortKey(symbol, name)
    }
}

/** A class fits when it's an object of the expected type or a class whose constructor builds one. */
fun KaSession.classifierItem(symbol: KaClassifierSymbol, expectation: Expectation? = null): CompletionItem? {
    val fitting = (symbol as? KaClassSymbol)?.takeIf { it.classKind == KaClassKind.OBJECT || it.classKind == KaClassKind.CLASS }
    return rank(classifierItemOf(symbol), fitting?.defaultType, expectation)
}

private fun KaSession.classifierItemOf(symbol: KaClassifierSymbol): CompletionItem? {
    val name = symbol.name?.asString() ?: return null
    return CompletionItem().apply {
        label = name
        kind = when (symbol) {
            is KaTypeParameterSymbol -> CompletionItemKind.TypeParameter
            is KaTypeAliasSymbol -> CompletionItemKind.Class
            is KaClassSymbol -> when (symbol.classKind) {
                KaClassKind.INTERFACE -> CompletionItemKind.Interface
                KaClassKind.ENUM_CLASS -> CompletionItemKind.Enum
                KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT -> CompletionItemKind.Module
                KaClassKind.ANNOTATION_CLASS -> CompletionItemKind.Interface
                else -> CompletionItemKind.Class
            }
        }
        val packageName = (symbol as? KaClassSymbol)?.classId?.packageFqName?.asString()
        if (!packageName.isNullOrEmpty()) labelDetails = CompletionItemLabelDetails().apply { description = packageName }
        insertText = name
        insertTextFormat = InsertTextFormat.PlainText
        sortText = "2$name"
    }
}

private fun KaSession.isFunctionalType(type: KaType): Boolean = type.isFunctionType || type.isSuspendFunctionType

/**
 * [function]'s parameter names. A Java library method compiled without them has `p0`, `p1`; its real names come from
 * the class file's debug info or the library's sources when they can be found.
 */
fun parameterNames(function: KaFunctionSymbol): List<String> {
    val declared = function.valueParameters.map { it.name.asString() }
    if (declared.none(LibraryParameterNames::isPlaceholder)) return declared
    val method = function.psi as? PsiMethod ?: return declared
    return LibraryParameterNames.namesOf(method, declared.size) ?: declared
}

/** A parameter as a call snippet needs it. */
class SnippetParameter(val name: String, val isRequired: Boolean, val isFunctional: Boolean)

/**
 * The text a call completes to, as in Android Studio: required arguments become placeholders, optional ones are left
 * out, and a required last lambda is written as a block. `launch { }`, `Row { }`, `LaunchedEffect(key1) { }`,
 * `copy()` with the cursor inside.
 */
fun callSnippet(name: String, parameters: List<SnippetParameter>, named: Boolean = false, typeArguments: List<String> = emptyList()): String {
    val typed = if (typeArguments.isEmpty()) name else typeArguments.withIndex().joinToString(", ", "$name<", ">") { (i, t) -> "\${${i + 1}:$t}" }
    return callSnippetOf(typed, parameters, named, firstPlaceholder = typeArguments.size + 1)
}

private fun callSnippetOf(name: String, parameters: List<SnippetParameter>, named: Boolean, firstPlaceholder: Int): String {
    if (parameters.isEmpty()) return "$name()"
    val trailing = parameters.last().takeIf { it.isFunctional && it.isRequired }
    val arguments = mutableListOf<String>()
    var skipped = false
    for (parameter in if (trailing != null) parameters.dropLast(1) else parameters) {
        if (!parameter.isRequired) {
            skipped = true
            continue
        }
        val placeholder = "\${${arguments.size + firstPlaceholder}:${parameter.name.replace(Regex("[$}\\\\]"), "")}}"
        // Named when asked for, and once an optional parameter is left out: later arguments must then be named to land
        // on the right parameter.
        arguments += if (named || skipped) "${parameter.name} = $placeholder" else placeholder
    }
    return when {
        trailing != null && arguments.isEmpty() -> "$name { \$0 }"
        trailing != null -> "$name(${arguments.joinToString(", ")}) { \$0 }"
        arguments.isEmpty() -> "$name(\$0)"
        else -> "$name(${arguments.joinToString(", ")})\$0"
    }
}

/**
 * Whether an extension can be called on [type]. Type arguments are ignored (`List<T>.map` accepts any `List`). A
 * generic receiver accepts what its bounds accept: `T.let` anything, `C.ifEmpty` where `C : CharSequence` only text.
 */
fun KaSession.acceptsReceiver(symbol: KaCallableSymbol, type: KaType): Boolean = acceptsReceiver(symbol, ReceiverClasses(this, type))

/** The classes a receiver type is or extends, computed once per completion instead of a subtype check per extension. */
class ReceiverClasses(session: KaSession, type: KaType) {
    val classIds: Set<org.jetbrains.kotlin.name.ClassId> = with(session) {
        (sequenceOf(type) + type.allSupertypes).mapNotNull { (it as? KaClassType)?.classId }.toSet()
    }
}

fun KaSession.acceptsReceiver(symbol: KaCallableSymbol, receiver: ReceiverClasses): Boolean {
    val receiverType = symbol.receiverParameter?.returnType ?: return false
    return accepts(receiverType, receiver, depth = 0)
}

private fun KaSession.accepts(expected: KaType, receiver: ReceiverClasses, depth: Int): Boolean = when (expected) {
    is KaClassType -> expected.classId in receiver.classIds
    is KaTypeParameterType -> depth > 2 || expected.symbol.upperBounds.all { accepts(it, receiver, depth + 1) }
    else -> false
}

/** Short names of the type's class and all its supertypes, to match index entries. */
fun KaSession.receiverClassNames(type: KaType): Set<String> =
    (sequenceOf(type) + type.allSupertypes)
        .mapNotNull { (it as? KaClassType)?.classId?.shortClassName?.asString() }
        .toSet()

/** The index stores receivers as short type text, e.g. "Flow<T>" or "LifecycleOwner?". */
fun shortTypeName(typeText: String): String = typeText.substringBefore('<').removeSuffix("?").substringAfterLast('.').trim()

/** Locals and parameters first, then members, then everything else; ties sort by name. */
private fun sortKey(symbol: KaCallableSymbol, name: String): String {
    val rank = when {
        symbol is KaValueParameterSymbol || symbol.psi?.parent is org.jetbrains.kotlin.psi.KtBlockExpression -> 0
        !symbol.isExtension -> 1
        else -> 3
    }
    return "$rank$name"
}

/** Where `import` lines go, and an edit adding `import fqName` unless it is already imported. */
class ImportInserter(private val ktFile: KtFile) {
    private val imports = ktFile.importList?.children?.filterIsInstance<KtImportDirective>() ?: emptyList()
    private val imported = imports.mapNotNull { it.importedFqName?.asString() }.toSet()
    private val starImported = imports.filter { it.isAllUnder }.mapNotNull { it.importedFqName?.asString() }.toSet()
    private val filePackage = ktFile.packageFqName.asString()

    /** Null when the name is visible already (same package, imported, or star-imported). */
    fun editFor(fqName: String): TextEdit? {
        val packageName = fqName.substringBeforeLast('.', "")
        if (packageName == filePackage || fqName in imported || packageName in starImported) return null
        if (imports.isEmpty()) {
            val offset = ktFile.packageDirective?.textRange?.endOffset?.takeIf { it > 0 }
            return if (offset != null) TextEdit(rangeAt(offset), "\n\nimport $fqName")
            else TextEdit(rangeAt(0), "import $fqName\n\n")
        }
        // In order, as Optimize Imports sorts them, so saving doesn't move it again.
        val key = importGroup(fqName, false) to fqName
        val next = imports.firstOrNull { directive ->
            val path = directive.importPath?.pathStr ?: return@firstOrNull false
            val other = importGroup(path, directive.aliasName != null) to path
            compareValuesBy(other, key, { it.first }, { it.second }) > 0
        }
        return if (next != null) TextEdit(rangeAt(next.textRange.startOffset), "import $fqName\n")
        else TextEdit(rangeAt(imports.last().textRange.endOffset), "\nimport $fqName")
    }

    private fun rangeAt(offset: Int): Range =
        StringUtil.offsetToLineColumn(ktFile.text, offset).let { Position(it.line, it.column) }.let { Range(it, it) }
}

/**
 * JDK internals (`sun.*`, `com.sun.*`, `jdk.*`): not API, and not on Android. Hidden from completion and imports, as
 * IntelliJ hides them; `jdk.internal.….Log` came before `android.util.Log` for `Log`.
 */
fun isJdkInternal(fqName: String): Boolean =
    fqName.startsWith("sun.") || fqName.startsWith("com.sun.") || fqName.startsWith("jdk.")

/** Platform and AndroidX first, then everything else, for classes that share a name (`Log`, `View`). */
fun importPreference(fqName: String): Int = when {
    fqName.startsWith("android.") || fqName.startsWith("androidx.") -> 0
    fqName.startsWith("kotlin.") || fqName.startsWith("kotlinx.") -> 1
    fqName.startsWith("java.") || fqName.startsWith("javax.") -> 3
    else -> 2
}
