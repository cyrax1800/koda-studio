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

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kotlinlsp.lsp.KotlinLanguageServer
import org.kotlinlsp.lsp.KotlinLanguageServerNotifier
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A module's compiler plugins come from its `-Xplugin=` arguments; the project's jar (any Kotlin version) is replaced
 * by the bundled build of the same plugin. Here kotlinx-serialization generates `serializer()`.
 */
class CompilerPlugins {
    private fun jar(group: String, name: String) =
        File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/$group/$name")
            .walkTopDown().firstOrNull { it.name.matches(Regex("$name-[0-9.]+\\.jar")) }

    @Test
    fun `serialization plugin generates serializer()`() {
        val stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib") ?: return // nothing to test without the jars in the Gradle cache
        val core = jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm") ?: return
        val root = Files.createTempDirectory("plugins").toFile()
        val text = "import kotlinx.serialization.Serializable\n\n@Serializable\nclass Point(val x: Int)\n\nval serializer = Point.serializer()\n"
        val with = File(root, "with/Point.kt").apply { parentFile.mkdirs(); writeText(text) }
        val without = File(root, "without/Point.kt").apply { parentFile.mkdirs(); writeText(text) }
        // The project's own plugin jar needn't exist: only its name says which bundled plugin to use.
        val projectPlugin = "/gradle/caches/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.3.10/x/kotlin-serialization-compiler-plugin-embeddable-2.3.10.jar"
        fun module(id: String, arguments: List<String>) =
            """{"id": "$id", "dependencies": ["core", "stdlib", "jdk"], "javaVersion": "17", "contentRoots": ["${File(root, id)}"], "isSource": true, "kotlinVersion": "2.2", "compilerArguments": [${arguments.joinToString { "\"$it\"" }}]}"""
        val modules = File(root, "modules.json").apply {
            writeText(
                """
                [
                  ${module("with", listOf("-Xplugin=$projectPlugin"))},
                  ${module("without", emptyList())},
                  {"id": "core", "dependencies": ["jdk"], "javaVersion": "17", "contentRoots": ["${core.absolutePath}"], "isSource": false, "isJdk": false},
                  {"id": "stdlib", "dependencies": ["jdk"], "javaVersion": "17", "contentRoots": ["${stdlib.absolutePath}"], "isSource": false, "isJdk": false},
                  {"id": "jdk", "dependencies": [], "javaVersion": "17", "isJdk": true, "isSource": false, "contentRoots": ["${System.getProperty("java.home")}"]}
                ]
                """.trimIndent()
            )
        }

        val diagnostics = CopyOnWriteArrayList<PublishDiagnosticsParams>()
        val client = mock(LanguageClient::class.java)
        `when`(client.publishDiagnostics(any())).thenAnswer { diagnostics.add(it.getArgument(0)); null }
        val server = KotlinLanguageServer(mock(KotlinLanguageServerNotifier::class.java))
        server.connect(client)
        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder("file://${root.absolutePath}", "root"))
            initializationOptions = com.google.gson.JsonObject().apply {
                addProperty("modulesFile", modules.absolutePath)
                addProperty("indexDir", File(root, "index").absolutePath)
            }
        }).join()
        server.initialized(InitializedParams())
        try {
            fun errors(file: File): List<String> {
                return diagnostics.reportAfter("/${file.parentFile.name}/${file.name}") {
                    server.didOpen(DidOpenTextDocumentParams(TextDocumentItem("file://${file.absolutePath}", "kotlin", 1, file.readText())))
                }.diagnostics
                    .filter { it.severity == DiagnosticSeverity.Error }.map { it.message }
            }
            assertEquals(emptyList<String>(), errors(with))
            val withoutPlugin = errors(without)
            assertTrue(withoutPlugin.any { "serializer" in it }, "errors without the plugin: $withoutPlugin")
        } finally {
            server.shutdown().join()
            root.deleteRecursively()
        }
    }
}
