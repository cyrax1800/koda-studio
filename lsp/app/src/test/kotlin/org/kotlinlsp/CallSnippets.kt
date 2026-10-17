/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.kotlinlsp.actions.autocomplete.SnippetParameter
import org.kotlinlsp.actions.autocomplete.callSnippet

/** What a completed call inserts: required arguments only, and a required last lambda as a block. */
class CallSnippets {
    private fun required(name: String) = SnippetParameter(name, isRequired = true, isFunctional = false)
    private fun optional(name: String) = SnippetParameter(name, isRequired = false, isFunctional = false)
    private fun lambda(name: String) = SnippetParameter(name, isRequired = true, isFunctional = true)

    @Test
    fun `no parameters`() = assertEquals("toString()", callSnippet("toString", emptyList()))

    @Test
    fun `optional arguments are left out before a trailing lambda`() {
        assertEquals("launch { \$0 }", callSnippet("launch", listOf(optional("context"), optional("start"), lambda("block"))))
        assertEquals("Row { \$0 }", callSnippet("Row", listOf(optional("modifier"), optional("horizontalArrangement"), optional("verticalAlignment"), lambda("content"))))
        assertEquals("apply { \$0 }", callSnippet("apply", listOf(lambda("block"))))
    }

    @Test
    fun `required arguments become placeholders`() {
        assertEquals("LaunchedEffect(\${1:key1}) { \$0 }", callSnippet("LaunchedEffect", listOf(required("key1"), lambda("block"))))
        assertEquals("Text(\${1:text})\$0", callSnippet("Text", listOf(required("text"), optional("modifier"), optional("color"))))
    }

    @Test
    fun `only optional arguments put the cursor inside the parentheses`() =
        assertEquals("copy(\$0)", callSnippet("copy", listOf(optional("name"), optional("url"))))

    @Test
    fun `a required argument after a left-out one is named`() =
        assertEquals("Button(onClick = \${1:onClick}) { \$0 }", callSnippet("Button", listOf(optional("modifier"), required("onClick"), lambda("content"))))

    @Test
    fun `an optional last lambda stays inside the parentheses`() =
        assertEquals("Icon(\${1:imageVector})\$0", callSnippet("Icon", listOf(required("imageVector"), SnippetParameter("onClick", isRequired = false, isFunctional = true))))

    @Test
    fun `named arguments`() =
        assertEquals("Text(text = \${1:text})\$0", callSnippet("Text", listOf(required("text"), optional("modifier")), named = true))

    @Test
    fun `a reified type parameter nothing infers is typed out first`() =
        assertEquals("entry<\${1:K}> { \$0 }", callSnippet("entry", listOf(optional("metadata"), lambda("content")), typeArguments = listOf("K")))

    @Test
    fun `type arguments come before argument placeholders`() =
        assertEquals("viewModel<\${1:VM}>(key = \${2:key})\$0", callSnippet("viewModel", listOf(required("key")), named = true, typeArguments = listOf("VM")))
}
