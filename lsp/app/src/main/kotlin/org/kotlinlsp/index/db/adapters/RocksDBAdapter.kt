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

package org.kotlinlsp.index.db.adapters

import org.rocksdb.*
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import org.kotlinlsp.common.warn
import org.kotlinlsp.common.info

class RocksDBAdapter(private val path: Path): DatabaseAdapter {
    companion object {
        init {
            RocksDB.loadLibrary()
        }

        val options = Options().apply {
            setCreateIfMissing(true)
            setKeepLogFileNum(1)
            setInfoLogLevel(InfoLogLevel.FATAL_LEVEL)
        }

        /** One for every write: each `WriteOptions()` is a native object that was never freed. */
        private val writeOptions = WriteOptions()

        /** Entries read per open iterator in [prefixSearchRaw]. */
        private const val CHUNK = 256
    }

    private val db = initializeDatabase()

    private fun initializeDatabase(): RocksDB {
        var attempts = 0
        val maxAttempts = 3
        val retryDelayMs = 1000L

        while (attempts < maxAttempts) {
            try {
                return RocksDB.open(options, path.absolutePathString())
            } catch (e: RocksDBException) {
                attempts++
                if (e.message?.contains("Resource temporarily unavailable") == true || 
                    e.message?.contains("LOCK") == true) {
                    warn("RocksDB lock conflict detected (attempt $attempts/$maxAttempts): ${e.message}")
                    
                    if (attempts < maxAttempts) {
                        // Try to clean up any stale lock files
                        cleanupStaleLocks()
                        Thread.sleep(retryDelayMs * attempts) // Exponential backoff
                    } else {
                        warn("Failed to acquire RocksDB lock after $maxAttempts attempts. Cleaning up and retrying once more...")
                        cleanupStaleLocks()
                        // Final attempt after cleanup
                        return RocksDB.open(options, path.absolutePathString())
                    }
                } else {
                    throw e // Re-throw if it's not a lock-related error
                }
            }
        }
        
        throw RocksDBException("Failed to initialize RocksDB after $maxAttempts attempts")
    }

    private fun cleanupStaleLocks() {
        try {
            val lockFile = File(path.absolutePathString(), "LOCK")
            if (lockFile.exists()) {
                info("Removing stale RocksDB lock file: ${lockFile.absolutePath}")
                lockFile.delete()
            }
        } catch (e: Exception) {
            warn("Failed to cleanup stale lock file: ${e.message}")
        }
    }

    override fun putRawData(key: String, value: ByteArray) {
        db.put(key.toByteArray(), value)
    }

    override fun putRawData(values: Iterable<Pair<String, ByteArray>>) {
        val batch = WriteBatch()
        values.forEach { (key, value) ->
            batch.put(key.toByteArray(), value)
        }
        batch.use { db.write(writeOptions, it) }
    }

    override fun getRawData(key: String): ByteArray? {
        val data = db.get(key.toByteArray()) ?: return null
        return data
    }

    /**
     * Read in chunks, each with its own iterator that is closed before the chunk is handed out. Callers stop early
     * (`take`, `first`), which left the single iterator this used to keep open, and its `finally`, never run: every
     * search leaked a native iterator, and each pins the memtables it saw, so native memory grew without bound while
     * the index was written (a 4 GB heap server reached ~20 GB).
     */
    override fun prefixSearchRaw(prefix: String): Sequence<Pair<String, ByteArray>> = sequence {
        val prefixBytes = prefix.toByteArray()
        var from = prefixBytes
        var afterFrom = false
        while (true) {
            val chunk = ArrayList<Pair<ByteArray, ByteArray>>(CHUNK)
            var more = false
            ReadOptions().use { readOptions ->
                db.newIterator(readOptions).use { iterator ->
                    iterator.seek(from)
                    if (afterFrom && iterator.isValid && iterator.key().contentEquals(from)) iterator.next()
                    while (iterator.isValid) {
                        val key = iterator.key()
                        if (!key.startsWith(prefixBytes)) break
                        if (chunk.size == CHUNK) {
                            more = true
                            break
                        }
                        chunk += key to iterator.value()
                        iterator.next()
                    }
                }
            }
            for ((key, value) in chunk) yield(key.toString(Charset.defaultCharset()) to value)
            if (!more) break
            from = chunk.last().first
            afterFrom = true
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    override fun remove(key: String) {
        db.delete(key.toByteArray())
    }

    override fun remove(keys: Iterable<String>) {
        val batch = WriteBatch()
        keys.forEach {
            batch.delete(it.toByteArray())
        }
        batch.use { db.write(writeOptions, it) }
    }

    override fun close() {
        db.close()
    }

    override fun deleteDb() {
        if(!db.isClosed) db.close()
        File(path.absolutePathString()).deleteRecursively()
    }
}
