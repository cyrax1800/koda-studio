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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.kotlinlsp.common.read
import org.kotlinlsp.lsp.KotlinLanguageServer
import org.kotlinlsp.lsp.KotlinLanguageServerNotifier
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An edit in a module must invalidate every module that depends on it, and only those. The dependents map once kept one
 * dependent per module, and only for the root modules; and the lifetime token once died on any edit, so the untouched
 * modules' cached sessions failed with "An analysis session acquired via `getAnalysisSession` must be valid".
 */
class ModuleDependents {
    private val stdlibJar: File? = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib")
        .walkTopDown().firstOrNull { it.name.matches(Regex("kotlin-stdlib-2\\.[0-9.]+\\.jar")) }

    @Test
    fun `an edit in a shared module reaches all its dependents`() {
        val stdlib = stdlibJar ?: return // nothing to test without the jar in the Gradle cache
        val root = Files.createTempDirectory("dependents").toFile()
        fun source(module: String, name: String, text: String) = File(root, "$module/$name").apply {
            parentFile.mkdirs()
            writeText(text)
        }
        val model = source("model", "Model.kt", "class Model {\n    fun a() = 1\n}\n")
        val data = source("data", "Data.kt", "fun d(m: Model) = m.a()\n")
        val feature = source("feature", "Feature.kt", "fun f(m: Model) = m.a()\n")
        val app = source("app", "App.kt", "fun main() = d(Model()) + f(Model())\n")
        val util = source("util", "Util.kt", "fun u(): Int = 1\n")
        fun module(id: String, vararg dependencies: String) =
            """{"id": "$id", "dependencies": [${(dependencies.toList() + listOf("stdlib", "jdk")).joinToString { "\"$it\"" }}], "javaVersion": "17", "contentRoots": ["${File(root, id)}"], "isSource": true, "kotlinVersion": "2.2"}"""
        val modules = File(root, "modules.json").apply {
            writeText(
                """
                [
                  ${module("app", "data", "feature", "model")},
                  ${module("data", "model")},
                  ${module("feature", "model")},
                  ${module("model")},
                  ${module("util")},
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
            val session = KotlinLanguageServer::class.java.getDeclaredField("analysisSession").apply { isAccessible = true }.get(server)
            val project = session.javaClass.getDeclaredField("project").apply { isAccessible = true }.get(session) as MockProject
            fun kaModule(file: File): KaModule = project.read {
                val psi = PsiManager.getInstance(project).findFile(VirtualFileManager.getInstance().findFileByNioPath(file.toPath())!!)!!
                KotlinProjectStructureProvider.getModule(project, psi, null)
            }
            val dependents = KotlinModuleDependentsProvider.getInstance(project)
            fun ids(modules: Set<KaModule>) = modules.map { it.toString() }.sorted()
            assertEquals(ids(setOf(kaModule(app), kaModule(data), kaModule(feature))), ids(dependents.getDirectDependents(kaModule(model))))
            assertEquals(ids(setOf(kaModule(app))), ids(dependents.getDirectDependents(kaModule(data))))

            fun errors(file: File) = diagnostics.lastOrNull { it.uri.endsWith(file.name) }?.diagnostics
                ?.filter { it.severity == DiagnosticSeverity.Error }?.map { it.message }
            for (file in listOf(model, data, feature, util)) diagnostics.reportAfter(file.name) {
                server.didOpen(DidOpenTextDocumentParams(TextDocumentItem("file://${file.absolutePath}", "kotlin", 1, file.readText())))
            }
            assertEquals(emptyList<String>(), errors(feature))

            // Sessions, not just their tokens, are kept: a token tracks its session weakly and reads as invalid once it is collected.
            fun session(file: File) = project.read {
                analyze(PsiManager.getInstance(project).findFile(VirtualFileManager.getInstance().findFileByNioPath(file.toPath())!!) as KtFile) { this }
            }
            val utilSession = session(util)
            val featureSession = session(feature)
            // Rename Model.a to Model.b: both dependents must now see the change.
            server.didChange(DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier("file://${model.absolutePath}", 2),
                listOf(TextDocumentContentChangeEvent(Range(Position(1, 8), Position(1, 9)), "b")),
            ))
            // Diagnostics follow an edit after a short debounce.
            val deadline = System.currentTimeMillis() + 20_000
            while (errors(feature).isNullOrEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            Thread.sleep(500)
            assertEquals(listOf("Unresolved reference 'a' on receiver of type 'Model'."), errors(data))
            assertEquals(listOf("Unresolved reference 'a' on receiver of type 'Model'."), errors(feature))

            // A module the edit doesn't affect keeps its cached session, which must still be usable; a dependent's must not.
            assertEquals(true, utilSession.token.isValid(), "session of the untouched module")
            assertEquals(false, featureSession.token.isValid(), "session of a dependent module")
            val hover = server.hover(HoverParams(TextDocumentIdentifier("file://${util.absolutePath}"), Position(0, 4))).join()
            assertEquals(true, hover?.contents?.right?.value?.contains("fun u(): Int"), "hover: $hover")
        } finally {
            server.shutdown().join()
            root.deleteRecursively()
        }
    }
}
