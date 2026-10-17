/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Modified 2026 by Koda Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kotlinlsp.index.db.adapters.put

@Serializable
sealed class Declaration() {
    abstract val name: String
    abstract val file: String
    abstract val startOffset: Int
    abstract val endOffset: Int

    @Serializable
    @SerialName("function")
    data class Function(
        override val name: String,
        val fqName: String,
        val isExtension: Boolean,
        val isTopLevel: Boolean,
        val isPrivate: Boolean,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val parameters: List<Parameter>,
        val returnType: String,
        val parentFqName: String,
        val receiverFqName: String,
    ) : Declaration() {
        @Serializable
        data class Parameter(
            val name: String,
            val type: String,
            /** Optional arguments are left out of the completion snippet. */
            val hasDefault: Boolean = false,
        )
    }

    @Serializable
    @SerialName("class")
    data class Class(
        override val name: String,
        val isTopLevel: Boolean,
        val isPrivate: Boolean,
        val type: Type,
        val fqName: String,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
    ) : Declaration() {
        enum class Type {
            CLASS,
            ABSTRACT_CLASS,
            INTERFACE,
            ENUM_CLASS,
            OBJECT,
            ANNOTATION_CLASS,
        }
    }

    @Serializable
    @SerialName("enumEntry")
    data class EnumEntry(
        override val name: String,
        val fqName: String,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val enumFqName: String,
    ) : Declaration()

    @Serializable
    @SerialName("field")
    data class Field(
        override val name: String,
        val fqName: String,
        val isExtension: Boolean,
        val isTopLevel: Boolean,
        override val file: String,
        override val startOffset: Int,
        override val endOffset: Int,
        val type: String,
        val parentFqName: String,
        /** An extension property's receiver as short type text, e.g. "LifecycleOwner"; empty otherwise. */
        val receiverFqName: String = "",
        val isPrivate: Boolean = false,
    ) : Declaration()

    // @Serializable
    // @SerialName("constructor")
    // data class Constructor(
    //     override val name: String,
    //     val fqName: String,
    //     override val file: String,
    //     override val startOffset: Int,
    //     override val endOffset: Int,
    //     val parameters: List<Parameter>,
    //     val parentFqName: String,
    // ) : Declaration() {
    //     @Serializable
    //     data class Parameter(
    //         val name: String,
    //         val type: String,
    //     )
    // }
    //
    // @Serializable
    // @SerialName("typeParameter")
    // data class TypeParameter(
    //     override val name: String,
    //     override val file: String,
    //     override val startOffset: Int,
    //     override val endOffset: Int,
    //     val parentFqName: String,
    // ) : Declaration()
}

/**
 * The storage key. Declarations read from class files all sit at offset 0 of their file, so a function's parameter
 * names are part of the key; without them the overloads in one class (`LaunchedEffect(key1)`, `LaunchedEffect(key1,
 * key2)`, … in `EffectsKt`) overwrote each other and only one was found.
 */
fun Declaration.id(): String {
    val overload = (this as? Declaration.Function)?.parameters?.joinToString(",", "(", ")") { it.name } ?: ""
    return "${name}${overload}:${file}:${startOffset}:${endOffset}"
}

fun Database.putDeclarations(declarations: Iterable<Declaration>) {
    declarationsDb.put(declarations.map { Pair(it.id(), it) })
}
