/*
 * Copyright 2026  Kumarapu Hemram
 * Modified 2026 by Koda Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.common

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

fun getCachePath(rootPath: String): Path {
    val cachePath = LaunchOptions.indexDir?.let { Paths.get(it) } ?: Paths.get(rootPath).resolve(".kotlin-lsp")

    if (!Files.exists(cachePath)) Files.createDirectories(cachePath)

    return cachePath
}

fun removeCacheFolder(rootPath: String) {
    val cachePath = getCachePath(rootPath).absolutePathString()
    File(cachePath).deleteRecursively()
}
