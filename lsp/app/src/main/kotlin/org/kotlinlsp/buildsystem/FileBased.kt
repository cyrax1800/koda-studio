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

package org.kotlinlsp.buildsystem

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.kotlinlsp.analysis.modules.deserializeModules
import java.io.File

// This build system is used to integrate projects are not supported by the LSP
// Also used for testing purposes
class FileBasedBuildSystem(
    private val project: Project,
    private val appEnvironment: KotlinCoreApplicationEnvironment,
    private val rootFolder: String,
    private val modulesFile: String = "$rootFolder/.kotlinlsp-modules.json",
): BuildSystem {
    override val markerFiles: List<String>
        get() = listOf(modulesFile)

    override fun resolveModulesIfNeeded(cachedMetadata: String?): BuildSystem.Result? {
        val file = File(modulesFile)
        val contents = file.readText()
        // The file's path and content, not its modification time: a newer cache from another modules file (or a
        // restored older file) once made the server keep stale compiler arguments and plugins after a sync.
        val currentVersion = "${file.absolutePath}:${sha256(contents)}"
        if (cachedMetadata == currentVersion) return null

        val rootModule = deserializeModules(
            contents,
            project = project,
            appEnvironment = appEnvironment
        )
        return BuildSystem.Result(rootModule, currentVersion)
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
