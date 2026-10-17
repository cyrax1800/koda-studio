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

package org.kotlinlsp.analysis.modules

import org.jetbrains.kotlin.cli.common.arguments.toLanguageVersionSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.kotlinlsp.common.read
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString

class SourceModule(
    override val id: String,
    override val contentRoots: List<Path>,
    override val dependencies: List<Module>,
    val javaVersion: JvmTarget,
    val kotlinVersion: LanguageVersion,
    private val project: Project,
    /** The module's Kotlin compiler arguments (`-Xexplicit-backing-fields`, `-opt-in=…`, …); language version excluded. */
    val compilerArguments: List<String> = emptyList(),
    /** Ids of the [dependencies] whose `internal` declarations this module may use (a test module's main module). */
    val friendIds: Set<String> = emptySet(),
) : Module {
    private val languageSettings: LanguageVersionSettings by lazy { languageVersionSettingsOf(kotlinVersion, compilerArguments) }

    override val sourceRoots: List<Path>?
        get() = contentRoots // For source modules, sourceRoots are the same as contentRoots
    override val isSourceModule: Boolean
        get() = true

    override fun computeFiles(extended: Boolean): Sequence<VirtualFile> =
        contentRoots
            .asSequence()
            .map { File(it.absolutePathString()).walk() }
            .flatten()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { "file://${it.absolutePath}" }
            .mapNotNull { project.read { VirtualFileManager.getInstance().findFileByUrl(it) } }

    private val extraFiles: MutableSet<VirtualFile> = Collections.newSetFromMap(ConcurrentHashMap())

    fun addFileToContentScope(file: VirtualFile) {
        // Only add Kotlin/Java files under this module's roots
        val isKtOrJava = file.extension.equals("kt", true) || file.extension.equals("java", true)
        if (!isKtOrJava) return
        extraFiles.add(file)
    }

    override val kaModule: KaModule by lazy {
        object : KaSourceModule, KaModuleBase() {
            private val baseFiles: Set<VirtualFile> by lazy { computeFiles(extended = true).toSet() }

            private val scope: GlobalSearchScope by lazy {
                object : GlobalSearchScope(this@SourceModule.project) {
                    override fun contains(file: VirtualFile): Boolean {
                        return baseFiles.contains(file) || extraFiles.contains(file)
                    }

                    override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module): Boolean = true
                    override fun isSearchInLibraries(): Boolean = false
                    override fun compare(file1: VirtualFile, file2: VirtualFile): Int = 0
                }
            }

            @KaPlatformInterface
            override val baseContentScope: GlobalSearchScope
                get() = scope
            override val directDependsOnDependencies: List<KaModule>
                get() = emptyList() // Not supporting KMP right now
            override val directFriendDependencies: List<KaModule>
                get() = dependencies.filter { it.id in friendIds }.map { it.kaModule }
            override val directRegularDependencies: List<KaModule>
                get() = dependencies.map { it.kaModule }
            override val languageVersionSettings: LanguageVersionSettings
                get() = languageSettings

            @KaExperimentalApi
            override val moduleDescription: String
                get() = "Source module: $name"
            override val name: String
                get() = id
            override val project: Project
                get() = this@SourceModule.project
            override val targetPlatform: TargetPlatform
                get() = JvmPlatforms.jvmPlatformByTargetVersion(javaVersion)
        }
    }
}

/**
 * Language settings as the compiler derives them from the module's arguments, so language features (explicit backing
 * fields, context parameters, …) and opt-ins match the build. The language version is the one already capped to
 * what this compiler supports; an API version above it is dropped.
 */
internal fun languageVersionSettingsOf(languageVersion: LanguageVersion, compilerArguments: List<String>): LanguageVersionSettings =
    // Modules mostly share their arguments, and parsing them goes through reflection: the first completion in a file
    // creates the sessions of its module's dependencies too, and spent seconds here.
    languageSettingsByArguments.getOrPut(languageVersion to compilerArguments) { parseLanguageVersionSettings(languageVersion, compilerArguments) }

private val languageSettingsByArguments = java.util.concurrent.ConcurrentHashMap<Pair<LanguageVersion, List<String>>, LanguageVersionSettings>()

private fun parseLanguageVersionSettings(languageVersion: LanguageVersion, compilerArguments: List<String>): LanguageVersionSettings {
    val fallback = LanguageVersionSettingsImpl(languageVersion, ApiVersion.createByLanguageVersion(languageVersion))
    if (compilerArguments.isEmpty()) return fallback
    return try {
        val arguments = org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments()
        org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments(compilerArguments, arguments)
        arguments.languageVersion = languageVersion.versionString
        val apiVersion = arguments.apiVersion?.let { LanguageVersion.fromVersionString(it) }
        if (apiVersion == null || apiVersion > languageVersion) arguments.apiVersion = null
        arguments.toLanguageVersionSettings(org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArgumentsConfigurator.Reporter.DoNothing)
    } catch (e: Exception) {
        org.kotlinlsp.common.warn("Ignoring compiler arguments $compilerArguments: ${e.message}")
        fallback
    }
}
