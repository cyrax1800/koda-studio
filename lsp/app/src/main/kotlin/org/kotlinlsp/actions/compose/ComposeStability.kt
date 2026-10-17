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

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Compose stability, worked out live as the Compose compiler does it, labelled like skydoves' Compose Stability
 * Analyzer reports (`STABLE`, `RUNTIME`, `UNSTABLE`). A composable is skippable when it is restartable and all its
 * parameters are stable.
 */
enum class StabilityKind { STABLE, RUNTIME, UNSTABLE }

class Stability(val kind: StabilityKind, val reason: String) {
    override fun toString() = "$kind ($reason)"
}

class ComposableStability(
    val function: KtNamedFunction,
    val restartable: Boolean,
    val skippable: Boolean,
    val parameters: List<Pair<KtParameter, Stability>>,
)

private val COMPOSABLE = ClassId.fromString("androidx/compose/runtime/Composable")
private val STABLE_MARKERS = listOf("Stable", "Immutable", "StableMarker").map { ClassId.fromString("androidx/compose/runtime/$it") }
private val NOT_RESTARTABLE = listOf("NonRestartableComposable", "ReadOnlyComposable").map { ClassId.fromString("androidx/compose/runtime/$it") }
private val NOT_SKIPPABLE = ClassId.fromString("androidx/compose/runtime/NonSkippableComposable")
private val STABILITY_INFERRED = ClassId.fromString("androidx/compose/runtime/internal/StabilityInferred")

private fun KaNamedClassSymbol?.isValueClass() = this != null && (isInline || annotations.contains(ClassId.fromString("kotlin/jvm/JvmInline")))

/** Types that are stable by definition. */
private val KNOWN_STABLE = setOf(
    "kotlin.String", "kotlin.Boolean", "kotlin.Char", "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
    "kotlin.Float", "kotlin.Double", "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong", "kotlin.Unit",
    "kotlin.Nothing", "kotlin.Number", "kotlin.ranges.IntRange", "kotlin.ranges.LongRange", "kotlin.ranges.CharRange",
    "kotlin.time.Duration", "kotlin.text.Regex", "java.lang.String", "java.math.BigInteger", "java.math.BigDecimal",
    "kotlin.coroutines.EmptyCoroutineContext", "kotlinx.coroutines.Dispatchers", "kotlinx.datetime.Instant",
    "kotlinx.datetime.LocalDate", "kotlinx.datetime.LocalDateTime", "kotlinx.datetime.LocalTime",
)

/** Stable when their type arguments are. */
private val KNOWN_STABLE_GENERIC = setOf("kotlin.Pair", "kotlin.Triple", "kotlin.Result", "kotlin.Comparator", "dagger.Lazy")
private val IMMUTABLE_COLLECTIONS = FqName("kotlinx.collections.immutable")

/** Read-only interfaces that may still be backed by mutable collections. */
private val STANDARD_COLLECTIONS = FqName("kotlin.collections")

fun KaSession.composablesIn(ktFile: KtFile): List<ComposableStability> {
    val inferencer = StabilityInferencer()
    return ktFile.collectDescendantsOfType<KtNamedFunction>().mapNotNull { composableStability(it, inferencer) }
}

fun KaSession.composableStability(function: KtNamedFunction, inferencer: StabilityInferencer = StabilityInferencer()): ComposableStability? {
    val symbol = function.symbol as? KaNamedFunctionSymbol ?: return null
    if (COMPOSABLE !in symbol.annotations) return null
    // Only Unit-returning composables with a body restart on their own; inline and abstract ones don't.
    val restartable = symbol.returnType.isUnitType && !symbol.isInline && function.hasBody() &&
        symbol.modality != KaSymbolModality.ABSTRACT && NOT_RESTARTABLE.none { it in symbol.annotations }
    val parameters = function.valueParameters.map { it to inferencer.stabilityOf(this, it.symbol.returnType) }
    val skippable = restartable && NOT_SKIPPABLE !in symbol.annotations && parameters.all { it.second.kind == StabilityKind.STABLE }
    return ComposableStability(function, restartable, skippable, parameters)
}

class StabilityInferencer {
    /** Non-generic classes, which make up most parameters, are worked out once per file. */
    private val classCache = HashMap<ClassId, Stability>()

    fun stabilityOf(session: KaSession, type: KaType): Stability = with(session) { infer(type, depth = 0, visiting = HashSet()) }

    private fun KaSession.infer(type: KaType, depth: Int, visiting: MutableSet<ClassId>): Stability {
        when (type) {
            is KaFunctionType -> return Stability(StabilityKind.STABLE, "function type")
            is KaTypeParameterType -> return Stability(StabilityKind.RUNTIME, "type parameter `${type.name}`, checked at runtime")
            is KaFlexibleType -> return infer(type.lowerBound, depth, visiting)
            !is KaClassType -> return Stability(StabilityKind.UNSTABLE, "unknown type")
            else -> {}
        }
        val classId = type.classId
        val fqName = classId.asSingleFqName()
        val name = classId.shortClassName.asString()
        if (fqName.asString() in KNOWN_STABLE) return Stability(StabilityKind.STABLE, "known stable type")
        if (fqName.asString() in KNOWN_STABLE_GENERIC || classId.packageFqName == IMMUTABLE_COLLECTIONS) {
            return combineArguments(type, depth, visiting, "known stable type")
        }
        if (classId.packageFqName == STANDARD_COLLECTIONS) {
            return Stability(StabilityKind.UNSTABLE, "`$name` may be a mutable collection; use an immutable collection or mark the holder @Immutable")
        }
        val symbol = type.symbol as? KaClassSymbol ?: return Stability(StabilityKind.UNSTABLE, "unknown type")
        if (STABLE_MARKERS.any { it in symbol.annotations } || hasStableMarkerAnnotation(symbol)) {
            return Stability(StabilityKind.STABLE, "marked @Stable or @Immutable")
        }
        if (symbol.classKind == KaClassKind.ENUM_CLASS) return Stability(StabilityKind.STABLE, "enum")
        if (symbol.origin == KaSymbolOrigin.JAVA_SOURCE || symbol.origin == KaSymbolOrigin.JAVA_LIBRARY) {
            return Stability(StabilityKind.UNSTABLE, "Java class `$name`")
        }
        if (symbol.classKind == KaClassKind.INTERFACE) {
            return Stability(StabilityKind.RUNTIME, "interface `$name`, its implementation is checked at runtime")
        }
        if (symbol.modality != KaSymbolModality.FINAL) {
            return Stability(StabilityKind.RUNTIME, "${symbol.modality.name.lowercase()} class `$name`, its subclass is checked at runtime")
        }
        // A library class counts when it was compiled with Compose, which stamps it with @StabilityInferred (its
        // stability then follows from its fields, as for project classes), or when it's a value class.
        if (symbol.origin == KaSymbolOrigin.LIBRARY && STABILITY_INFERRED !in symbol.annotations && !(symbol as? KaNamedClassSymbol).isValueClass()) {
            return Stability(StabilityKind.UNSTABLE, "`$name` comes from a library compiled without Compose")
        }
        if (classId in visiting) return Stability(StabilityKind.STABLE, "recursive type")
        if (depth > 8) return Stability(StabilityKind.RUNTIME, "nested too deeply to tell")
        val generic = type.typeArguments.isNotEmpty()
        if (!generic) classCache[classId]?.let { return it }

        visiting += classId
        val result = inferClass(symbol, type, depth, visiting)
        visiting -= classId
        if (!generic) classCache[classId] = result
        return result
    }

    /** A class from the project: stable when it holds only stable `val`s. */
    private fun KaSession.inferClass(symbol: KaClassSymbol, type: KaClassType, depth: Int, visiting: MutableSet<ClassId>): Stability {
        val arguments: Map<KaTypeParameterSymbol, KaType?> = ((symbol as? KaNamedClassSymbol)?.typeParameters ?: emptyList()).zip(type.typeArguments.map { it.type }).toMap()
        var runtime: Stability? = null
        for (property in symbol.memberScope.callables.filterIsInstance<KaPropertySymbol>()) {
            // Only state stored in the object counts; computed properties hold nothing.
            if ((property as? KaKotlinPropertySymbol)?.hasBackingField == false) continue
            val propertyName = property.name.asString()
            if (!property.isVal) return Stability(StabilityKind.UNSTABLE, "has mutable property `$propertyName`")
            val propertyType = property.returnType.let { declared ->
                if (declared is KaTypeParameterType) arguments[declared.symbol] ?: return@let null else declared
            } ?: return Stability(StabilityKind.RUNTIME, "property `$propertyName` has a star-projected type")
            val stability = infer(propertyType, depth + 1, visiting)
            when (stability.kind) {
                StabilityKind.UNSTABLE -> return Stability(StabilityKind.UNSTABLE, "property `$propertyName` is unstable: ${stability.reason}")
                StabilityKind.RUNTIME -> if (runtime == null) runtime = Stability(StabilityKind.RUNTIME, "property `$propertyName`: ${stability.reason}")
                StabilityKind.STABLE -> {}
            }
        }
        return runtime ?: Stability(StabilityKind.STABLE, "only stable `val` properties")
    }

    private fun KaSession.combineArguments(type: KaClassType, depth: Int, visiting: MutableSet<ClassId>, reason: String): Stability {
        for (argument in type.typeArguments) {
            val argumentType = argument.type ?: return Stability(StabilityKind.RUNTIME, "star-projected type argument")
            val stability = infer(argumentType, depth + 1, visiting)
            if (stability.kind != StabilityKind.STABLE) return Stability(stability.kind, "type argument: ${stability.reason}")
        }
        return Stability(StabilityKind.STABLE, reason)
    }

    /** Annotations that are themselves marked `@StableMarker` (custom stability annotations). */
    private fun KaSession.hasStableMarkerAnnotation(symbol: KaClassSymbol): Boolean = symbol.annotations.any { annotation ->
        val annotationClass = annotation.classId?.let { findClass(it) } ?: return@any false
        STABLE_MARKERS.any { it in annotationClass.annotations }
    }
}
