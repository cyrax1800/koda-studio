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

/** A test module sees `internal` declarations of its main module (the compiler's friend paths); other modules don't. */
class FriendModules {
    private val stdlibJar: File? = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib")
        .walkTopDown().firstOrNull { it.name.matches(Regex("kotlin-stdlib-2\\.[0-9.]+\\.jar")) }

    @Test
    fun `test modules see internal declarations of main`() {
        val stdlib = stdlibJar ?: return // nothing to test without the jar in the Gradle cache
        val root = Files.createTempDirectory("friends").toFile()
        fun source(module: String, text: String) = File(root, "$module/${module.replaceFirstChar { it.uppercase() }}.kt").apply {
            parentFile.mkdirs()
            writeText(text)
        }
        source("main", "internal fun secret(): Int = 1\n")
        val test = source("test", "fun check(): Int = secret()\n")
        val other = source("other", "fun peek(): Int = secret()\n")
        fun module(id: String, dependencies: List<String>, friends: List<String> = emptyList()) =
            """{"id": "$id", "dependencies": [${(dependencies + listOf("stdlib", "jdk")).joinToString { "\"$it\"" }}], "friends": [${friends.joinToString { "\"$it\"" }}], "javaVersion": "17", "contentRoots": ["${File(root, id)}"], "isSource": true, "kotlinVersion": "2.2"}"""
        val modules = File(root, "modules.json").apply {
            writeText(
                """
                [
                  ${module("test", listOf("main"), friends = listOf("main"))},
                  ${module("other", listOf("main"))},
                  ${module("main", emptyList())},
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
                return diagnostics.reportAfter(file.name) {
                    server.didOpen(DidOpenTextDocumentParams(TextDocumentItem("file://${file.absolutePath}", "kotlin", 1, file.readText())))
                }.diagnostics
                    .filter { it.severity == DiagnosticSeverity.Error }.map { it.message }
            }
            assertEquals(emptyList<String>(), errors(test))
            val otherErrors = errors(other)
            assertTrue(otherErrors.any { "secret" in it && "internal" in it }, "errors in a non-friend module: $otherErrors")
        } finally {
            server.shutdown().join()
            root.deleteRecursively()
        }
    }
}
