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

package org.kotlinlsp.index.db

import org.kotlinlsp.common.getCachePath
import org.kotlinlsp.common.info
import org.kotlinlsp.common.warn
import org.kotlinlsp.index.db.adapters.DatabaseAdapter
import org.kotlinlsp.index.db.adapters.RocksDBAdapter
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.adapters.prefixSearch
import org.kotlinlsp.index.db.adapters.put
import org.rocksdb.RocksDBException
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.absolutePathString
import kotlin.concurrent.withLock

const val CURRENT_SCHEMA_VERSION = 11    // 7: index built with Analysis API 2.4.20; 8: parameter defaults; 9: property receivers; 10: compiled Java classes, library internals hidden; 11: source types as written, no local functions    // Increment on schema changes
const val VERSION_KEY = "__version"

class Database(rootFolder: String) {
    private val cachePath = getCachePath(rootFolder)
    private val packageListLock = ReentrantLock()
    val artifactsDb: DatabaseAdapter
    val filesDb: DatabaseAdapter
    val packagesDb: DatabaseAdapter
    val declarationsDb: DatabaseAdapter
    val sourcesDb: DatabaseAdapter
    val sourceFilesDb: DatabaseAdapter

    init {
        try {
            var projectDb = RocksDBAdapter(cachePath.resolve("project"))
            val schemaVersion = projectDb.get<Int>(VERSION_KEY)

            if(schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION) {
                // Schema version mismatch, wipe the db
                info("Index DB schema version mismatch, recreating!")
                projectDb.close()
                deleteAll()

                projectDb = RocksDBAdapter(cachePath.resolve("project"))
                projectDb.put(VERSION_KEY, CURRENT_SCHEMA_VERSION)
            }

            artifactsDb = RocksDBAdapter(cachePath.resolve("artifacts"))
            filesDb = RocksDBAdapter(cachePath.resolve("files"))
            packagesDb = RocksDBAdapter(cachePath.resolve("packages"))
            declarationsDb = RocksDBAdapter(cachePath.resolve("declarations"))
            sourcesDb = RocksDBAdapter(cachePath.resolve("sources"))
            sourceFilesDb = RocksDBAdapter(cachePath.resolve("sourceFiles"))
            projectDb.close()
        } catch (e: RocksDBException) {
            warn("Failed to initialize database: ${e.message}")
            warn("This might be due to another Kotlin LSP instance running. Please ensure only one instance is active.")
            throw e
        } catch (e: Exception) {
            warn("Unexpected error during database initialization: ${e.message}")
            throw e
        }
    }

    fun close() {
        artifactsDb.close()
        filesDb.close()
        packagesDb.close()
        declarationsDb.close()
        sourcesDb.close()
        sourceFilesDb.close()
    }

    /**
     * [packagesDb] in memory: each source package's files, and every package with its parent packages. Analysis asks
     * whether a package exists and what it holds hundreds of thousands of times while a project's files are analysed
     * (find usages over many modules); each answer read RocksDB, together a minute and a half. Writes go through
     * [setPackageFiles], which keeps it current.
     */
    private val packageFiles: java.util.concurrent.ConcurrentHashMap<String, List<String>> by lazy {
        java.util.concurrent.ConcurrentHashMap<String, List<String>>().also { map ->
            packagesDb.prefixSearch<List<String>>("").forEach { (key, files) -> map[key] = files }
        }
    }
    @Volatile private var knownPackages: Set<String>? = null

    private fun setPackageFiles(packageFqName: String, files: List<String>) {
        if (files.isEmpty()) {
            packagesDb.remove(packageFqName)
            packageFiles.remove(packageFqName)
        } else {
            packagesDb.put(packageFqName, files)
            packageFiles[packageFqName] = files
        }
        knownPackages = null
    }

    /** The source files of a package (urls). */
    fun filesOfPackage(packageFqName: String): List<String> = packageFiles[packageFqName].orEmpty()

    /** Whether source files declare [packageFqName] or one of its subpackages. */
    fun packageExists(packageFqName: String): Boolean = packageFqName in packages()

    /** The next segment of each source package under [packageFqName]. */
    fun subpackageNames(packageFqName: String): Set<String> {
        val prefix = if (packageFqName.isEmpty()) "" else "$packageFqName."
        return packages().asSequence()
            .filter { it.startsWith(prefix) && it.length > prefix.length }
            .map { it.substring(prefix.length).substringBefore('.') }
            .toSet()
    }

    private fun packages(): Set<String> = knownPackages ?: HashSet<String>().also { all ->
        for (name in packageFiles.keys) {
            var current = name
            while (all.add(current) && current.isNotEmpty()) current = current.substringBeforeLast('.', "")
        }
        knownPackages = all
    }

    fun addSourceFile(packageFqName: String, fileUrl: String): Boolean = packageListLock.withLock {
        val existing = sourcesDb.get<List<String>>(packageFqName)?.toMutableList() ?: mutableListOf()
        if (existing.contains(fileUrl)) {
            return@withLock false
        }

        existing.add(fileUrl)
        sourcesDb.put(packageFqName, existing)
        true
    }

    fun updatePackageFiles(filePath: String, previousPackageFqName: String?, packageFqName: String) {
        packageListLock.withLock {
            if (previousPackageFqName == packageFqName) return

            if (previousPackageFqName != null) {
                setPackageFiles(previousPackageFqName, filesOfPackage(previousPackageFqName) - filePath)
            }

            val files = filesOfPackage(packageFqName)
            if (!files.contains(filePath)) setPackageFiles(packageFqName, files + filePath)
        }
    }

    fun removeFile(filePath: String) {
        packageListLock.withLock {
            val fileDto = filesDb.get<FileDto>(filePath) ?: return

            setPackageFiles(fileDto.packageFqName, filesOfPackage(fileDto.packageFqName) - filePath)

            if (fileDto.declarationKeys.isNotEmpty()) {
                declarationsDb.remove(fileDto.declarationKeys)
            }

            filesDb.remove(filePath)
        }
    }

    fun getTrackedFilePaths(): Sequence<String> = filesDb.prefixSearchRaw("").map { (key, _) -> key }

    private fun deleteAll() {
        File(cachePath.resolve("project").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("artifacts").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("files").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("packages").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("declarations").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("sources").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("sourceFiles").absolutePathString()).deleteRecursively()
    }
}
