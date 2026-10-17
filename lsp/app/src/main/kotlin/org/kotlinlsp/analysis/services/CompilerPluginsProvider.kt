/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

@file:OptIn(org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class, org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
package org.kotlinlsp.analysis.services

import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ExtensionPointDescriptor
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.SourceModule
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.common.ServerStatus
import org.kotlinlsp.common.info
import org.kotlinlsp.common.warn
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin compiler plugins per source module, from its compiler arguments (`-Xplugin=<jar>`, `-P plugin:<id>:<k>=<v>`
 * as the Gradle build passes them): Compose's checks, the `serializer()` that kotlinx-serialization generates, Parcelize.
 *
 * A project's plugin jars are built for its Kotlin version and can't run in this front end, so each is replaced by
 * the bundled plugin built for this compiler ([BundledCompilerPlugins]); plugins without a bundled build are skipped.
 * Each module's plugins load in their own class loader, configured with that module's options.
 */
class CompilerPluginsProvider : KotlinCompilerPluginsProvider {
    private var modulesByKaModule: Map<KaModule, SourceModule> = emptyMap()
    private val storages = ConcurrentHashMap<KaModule, Extensions>()

    fun setup(modules: List<Module>) {
        modulesByKaModule = modules.asFlatSequence().filterIsInstance<SourceModule>().associateBy { it.kaModule }
    }

    override fun <T : Any> getRegisteredExtensions(module: KaModule, extensionType: ExtensionPointDescriptor<T>): List<T> {
        val extensions = extensionsOf(module) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return extensions.storage.registeredExtensions[extensionType] as List<T>? ?: emptyList()
    }

    // The only plugin type asked about is ASSIGNMENT (the `assignment` plugin), which isn't bundled.
    override fun isPluginOfTypeRegistered(module: KaModule, pluginType: KotlinCompilerPluginsProvider.CompilerPluginType): Boolean = false

    private class Extensions(val storage: CompilerPluginRegistrar.ExtensionStorage)

    private val loaders = java.util.concurrent.ConcurrentHashMap<List<File>, URLClassLoader>()

    private fun extensionsOf(module: KaModule): Extensions? {
        val source = modulesByKaModule[module] ?: return null
        return storages.computeIfAbsent(module) { ServerStatus.preparingModule(source.id) { load(source) } }
            .takeIf { it.storage.registeredExtensions.isNotEmpty() }
    }

    private fun load(module: SourceModule): Extensions {
        val storage = CompilerPluginRegistrar.ExtensionStorage()
        val arguments = K2JVMCompilerArguments()
        try {
            parseCommandLineArguments(module.compilerArguments, arguments)
        } catch (e: Exception) {
            warn("${module.id}: unreadable compiler arguments, no compiler plugins: ${e.message}")
            return Extensions(storage)
        }
        val projectJars = arguments.pluginClasspaths.orEmpty().flatMap { it.split(File.pathSeparatorChar, ',') }.filter { it.isNotBlank() }
        val jars = projectJars.mapNotNull { BundledCompilerPlugins.forProjectJar(it) }.distinct()
        if (jars.isEmpty()) return Extensions(storage)

        // One loader per set of plugin jars: a loader per module loaded the plugins' classes again for each of
        // hundreds of modules (time, and metaspace).
        val loader = loaders.computeIfAbsent(jars) { URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), CompilerPluginsProvider::class.java.classLoader) }
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, module.id)
        }
        val processors = ServiceLoader.load(CommandLineProcessor::class.java, loader).stream()
            .filter { it.type().classLoader === loader }
            .map { it.get() }
            .toList()
        for (option in arguments.pluginOptions.orEmpty()) applyOption(module, option, processors, configuration)

        val registrars = ServiceLoader.load(CompilerPluginRegistrar::class.java, loader).stream()
            .filter { it.type().classLoader === loader }
            .map { it.get() }
            .toList()
        val loaded = mutableListOf<String>()
        for (registrar in registrars) {
            if (!registrar.supportsK2) continue
            // Each plugin registers into its own storage first and has its FIR part configured once: a plugin that
            // can't run here (missing classes, an incompatible build) would otherwise fail every analysis of the module.
            val own = CompilerPluginRegistrar.ExtensionStorage()
            try {
                with(registrar) { own.registerExtensions(configuration) }
                own[FirExtensionRegistrarAdapter].forEach { (it as FirExtensionRegistrar).configure() }
            } catch (e: Throwable) {
                warn("${module.id}: compiler plugin ${registrar.pluginId} can't be used: $e")
                continue
            }
            for ((point, extensions) in own.registeredExtensions) {
                @Suppress("UNCHECKED_CAST")
                with(storage) { extensions.forEach { (point as ExtensionPointDescriptor<Any>).registerExtension(it) } }
            }
            loaded += registrar.pluginId
        }
        info("${module.id}: compiler plugins ${loaded.joinToString().ifEmpty { "none" }}")
        return Extensions(storage)
    }

    /** `plugin:<id>:<name>=<value>`, as Gradle passes it after `-P`. */
    private fun applyOption(module: SourceModule, option: String, processors: List<CommandLineProcessor>, configuration: CompilerConfiguration) {
        val match = Regex("^plugin:([^:]+):([^=]+)=(.*)$", RegexOption.DOT_MATCHES_ALL).find(option) ?: return
        val (pluginId, name, value) = match.destructured
        val processor = processors.firstOrNull { it.pluginId == pluginId } ?: return
        val cliOption = processor.pluginOptions.firstOrNull { it.optionName == name } ?: return
        try {
            processor.processOption(cliOption, value, configuration)
        } catch (e: Exception) {
            // Options of a newer or older plugin version than the bundled one; the defaults apply.
            info("${module.id}: ignoring compiler plugin option $pluginId:$name: ${e.message}")
        }
    }
}

/** The compiler plugins built for this compiler, shipped in the server's `plugins/` folder. */
object BundledCompilerPlugins {
    /** Bundled jar name prefix → what a project's jar path contains for the same plugin, across Kotlin versions. */
    private val plugins = listOf(
        "compose-compiler-plugin-for-ide" to listOf("kotlin-compose-compiler-plugin", "/androidx.compose.compiler/"),
        "kotlinx-serialization-compiler-plugin-for-ide" to listOf("kotlin-serialization-compiler-plugin", "kotlinx-serialization-compiler-plugin"),
        "parcelize-compiler-plugin-for-ide" to listOf("kotlin-parcelize-compiler"),
    )

    private val jars: List<File> by lazy {
        val explicit = System.getProperty("kotlinlsp.compilerPlugins")
        val files = if (explicit != null) {
            explicit.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
        } else {
            // <install>/lib/app.jar → <install>/plugins
            val libDir = File(BundledCompilerPlugins::class.java.protectionDomain.codeSource.location.toURI()).parentFile
            File(libDir.parentFile, "plugins").listFiles { file -> file.extension == "jar" }?.toList().orEmpty()
        }
        if (files.isEmpty()) warn("No bundled compiler plugins found; Compose, serialization and Parcelize code won't be checked")
        files
    }

    fun forProjectJar(path: String): File? {
        val normalized = path.replace('\\', '/')
        val (prefix, _) = plugins.firstOrNull { (_, markers) -> markers.any { it in normalized } } ?: return null
        return jars.firstOrNull { it.name.startsWith(prefix) }
    }
}
