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
import org.kotlinlsp.common.uriToPath

/** VS Code sends file URIs unencoded; VS Code's storage on macOS is under `Application Support`. */
class UriToPath {
    private val path = "/Users/me/Library/Application Support/Code/kotlin-index/decompiled/android/os/Bundle.java"

    @Test
    fun `unencoded, as the client sends it`() = assertEquals(path, "file://$path".uriToPath())

    @Test
    fun `percent-encoded`() = assertEquals(path, "file://${path.replace(" ", "%20")}".uriToPath())

    @Test
    fun `a plain path stays`() = assertEquals(path, path.uriToPath())
}
