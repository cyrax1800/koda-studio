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

import com.intellij.mock.MockProject
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.name.ClassId
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

/** Kotlin classes from a plain library jar (no sources jar) must resolve. */
class LibraryResolution {
    private val coroutinesJar: File? = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm")
        .walkTopDown().firstOrNull { it.name.matches(Regex("kotlinx-coroutines-core-jvm-[0-9.]+\\.jar")) }

    /** Debug aid: `-Dlibres.modules=<modules.json> -Dlibres.file=<file.kt>` runs the same checks on a real project. */
    @Test
    fun `resolves library classes in a real project`() {
        val modulesFile = System.getProperty("libres.modules") ?: return
        val file = File(System.getProperty("libres.file"))
        check(modulesFile, file, File(file.parentFile, "x"))
    }

    private val stdlibJar: File? = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib")
        .walkTopDown().firstOrNull { it.name.matches(Regex("kotlin-stdlib-2\\.[0-9.]+\\.jar")) }

    @Test
    fun `resolves classes from a library jar`() {
        val jar = coroutinesJar ?: return // nothing to test without the jars in the Gradle cache
        val stdlib = stdlibJar ?: return
        val root = Files.createTempDirectory("libres").toFile()
        val source = File(root, "src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("import kotlinx.coroutines.CoroutineDispatcher\n\nfun use(dispatcher: CoroutineDispatcher): String = dispatcher.toString()\n")
        }
        val modules = File(root, "modules.json").apply {
            writeText(
                """
                [
                  {"id": "main", "dependencies": ["coroutines", "stdlib", "jdk"], "javaVersion": "17", "contentRoots": ["${source.parent}"], "isSource": true, "kotlinVersion": "2.2"},
                  {"id": "coroutines", "dependencies": ["jdk"], "javaVersion": "17", "contentRoots": ["${jar.absolutePath}"], "isSource": false, "isJdk": false},
                  {"id": "stdlib", "dependencies": ["jdk"], "javaVersion": "17", "contentRoots": ["${stdlib.absolutePath}"], "isSource": false, "isJdk": false},
                  {"id": "jdk", "dependencies": [], "javaVersion": "17", "isJdk": true, "isSource": false, "contentRoots": ["${System.getProperty("java.home")}"]}
                ]
                """.trimIndent()
            )
        }
        check(modules.absolutePath, source, root)
    }

    private fun check(modulesPath: String, source: File, root: File) {
        val modules = File(modulesPath)
        val diagnostics = CopyOnWriteArrayList<PublishDiagnosticsParams>()
        val client = mock(LanguageClient::class.java)
        `when`(client.publishDiagnostics(any())).thenAnswer { diagnostics.add(it.getArgument(0)); null }
        val server = KotlinLanguageServer(mock(KotlinLanguageServerNotifier::class.java))
        server.connect(client)
        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder("file://${modules.parentFile.absolutePath}", "root"))
            initializationOptions = com.google.gson.JsonObject().apply {
                addProperty("modulesFile", modules.absolutePath)
                addProperty("indexDir", File(System.getProperty("java.io.tmpdir"), "libres-index-" + modules.absolutePath.hashCode()).absolutePath)
            }
        }).join()
        server.initialized(InitializedParams())
        try {
            val session = KotlinLanguageServer::class.java.getDeclaredField("loadedSession").apply { isAccessible = true }.get(server)
            val project = session.javaClass.getDeclaredField("project").apply { isAccessible = true }.get(session) as MockProject
            val scope = GlobalSearchScope.allScope(project)
            val classId = ClassId.fromString("kotlinx/coroutines/CoroutineDispatcher")
            println("DEBUG virtual file finder: " + VirtualFileFinderFactory.getInstance(project).create(scope).findVirtualFileWithHeader(classId))
            println("DEBUG java facade: " + JavaPsiFacade.getInstance(project).findClass("kotlinx.coroutines.CoroutineDispatcher", scope))

            server.didOpen(DidOpenTextDocumentParams(TextDocumentItem("file://${source.absolutePath}", "kotlin", 1, source.readText())))
            val errors = diagnostics.flatMap { it.diagnostics }.filter { it.severity == DiagnosticSeverity.Error }.map { it.message }
            println("DEBUG errors: $errors")
            assertEquals(emptyList<String>(), errors)
        } finally {
            server.shutdown().join()
            if (root.name.startsWith("libres")) root.deleteRecursively()
        }
    }
}
