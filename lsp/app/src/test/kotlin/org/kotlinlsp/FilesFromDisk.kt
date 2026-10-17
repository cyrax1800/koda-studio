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
import org.junit.jupiter.api.Test
import org.kotlinlsp.lsp.KotlinLanguageServer
import org.kotlinlsp.lsp.KotlinLanguageServerNotifier
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Kotlin and Java files created or deleted outside the editor (`didChangeWatchedFiles`) are seen without a restart. */
class FilesFromDisk {
    private val stdlibJar: File? = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib")
        .walkTopDown().firstOrNull { it.name.matches(Regex("kotlin-stdlib-2\\.[0-9.]+\\.jar")) }

    @Test
    fun `new and deleted files are picked up`() {
        val stdlib = stdlibJar ?: return // nothing to test without the jar in the Gradle cache
        val root = Files.createTempDirectory("disk").toFile()
        val app = File(root, "app/demo").apply { mkdirs() }
        val user = File(app, "A.kt").apply { writeText("package demo\n\nfun a(): Int = b() + J().value()\n") }
        val modules = File(root, "modules.json").apply {
            writeText(
                """
                [
                  {"id": "app", "dependencies": ["stdlib", "jdk"], "javaVersion": "17", "contentRoots": ["${File(root, "app")}"], "isSource": true, "kotlinVersion": "2.2"},
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
            fun errors() = diagnostics.last { it.uri.endsWith("/A.kt") }.diagnostics.filter { it.severity == DiagnosticSeverity.Error }.map { it.message }
            fun changed(vararg files: Pair<File, FileChangeType>) = diagnostics.reportAfter("/A.kt") {
                server.didChangeWatchedFiles(DidChangeWatchedFilesParams(files.map { (file, type) -> FileEvent("file://${file.absolutePath}", type) }))
            }

            diagnostics.reportAfter("/A.kt") {
                server.didOpen(DidOpenTextDocumentParams(TextDocumentItem("file://${user.absolutePath}", "kotlin", 1, user.readText())))
            }
            assertEquals(2, errors().size, "before the files exist: ${errors()}")

            val kotlin = File(app, "B.kt").apply { writeText("package demo\n\nfun b(): Int = 1\n") }
            val java = File(app, "J.java").apply { writeText("package demo;\n\npublic class J {\n  public int value() { return 2; }\n}\n") }
            changed(kotlin to FileChangeType.Created, java to FileChangeType.Created)
            assertEquals(emptyList<String>(), errors())

            kotlin.delete()
            changed(kotlin to FileChangeType.Deleted)
            assertEquals(1, errors().size, "after deleting B.kt: ${errors()}")
        } finally {
            server.shutdown().join()
            root.deleteRecursively()
        }
    }
}
