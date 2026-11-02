/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.kotlinlsp.common.setupLogger
import org.kotlinlsp.index.db.Database
import org.kotlinlsp.index.db.FileDto
import org.kotlinlsp.index.db.file
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.adapters.put
import org.mockito.Mockito.mock
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Files

class DatabaseTests {
    @Test
    fun `removeFile should remove stale paths from files and package indexes`() {
        setupLogger(mock(LanguageClient::class.java))
        val tempDir = Files.createTempDirectory("kotlinlsp-db-test")
        val db = Database(tempDir.toAbsolutePath().toString())

        try {
            val filePath = "file:///tmp/sample.kt"
            val packageName = "com.example"
            val fileDto = FileDto(packageFqName = packageName, lastModified = 0, modificationStamp = 0, indexed = true, declarationKeys = listOf("decl1"))

            db.filesDb.put(filePath, fileDto)
            db.packagesDb.put(packageName, listOf(filePath))

            db.removeFile(filePath)

            assertNull(db.file(filePath), "Removed file should no longer exist in filesDb")
            assertNull(db.packagesDb.get<List<String>>(packageName), "Package entry should be removed when no files remain")
        } finally {
            db.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a damaged index is recreated instead of failing the start`() {
        setupLogger(mock(LanguageClient::class.java))
        val tempDir = Files.createTempDirectory("kotlinlsp-db-test")
        try {
            Database(tempDir.toAbsolutePath().toString()).close()
            // An .sst file the MANIFEST lists, then deleted: what a VS Code storage cleanup left behind.
            val packages = tempDir.resolve(".kotlin-lsp/packages").toString()
            org.rocksdb.RocksDB.open(packages).use { raw ->
                raw.put("com.example".toByteArray(), "x".toByteArray())
                org.rocksdb.FlushOptions().setWaitForFlush(true).use { raw.flush(it) }
            }
            val ssts = java.io.File(packages).listFiles { f -> f.name.endsWith(".sst") }!!
            check(ssts.isNotEmpty())
            ssts.forEach { it.delete() }

            val db = Database(tempDir.toAbsolutePath().toString())
            try {
                db.packagesDb.put("com.example", listOf("file:///tmp/a.kt"))
                assertEquals(listOf("file:///tmp/a.kt"), db.packagesDb.get<List<String>>("com.example"))
            } finally {
                db.close()
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
