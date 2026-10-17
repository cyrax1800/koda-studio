/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import org.kotlinlsp.common.info
import java.io.File
import java.nio.file.Path

/**
 * Which source files (`.kt`, `.java`) contain an identifier, for the first pass of find references. Searches used to
 * walk every source folder and read every file: 64 MB in 15,000 files on a large project, for each CodeLens. The
 * index reads them once, then [update] keeps a file current when it changes on disk. Text in open editors is checked
 * by the caller, since it changes with every keystroke.
 */
class WordIndex(private val roots: () -> List<Path>, private val saveFile: Path? = null) {
    private val lock = Any()
    private var built = false
    private val idByPath = HashMap<String, Int>()
    private val paths = ArrayList<String?>()
    private val wordsOfFile = ArrayList<Array<String>?>()
    private val filesOfWord = HashMap<String, IntOpenHashSet>()

    /** Absolute paths of the files whose text contains [word] as a whole identifier. */
    fun filesMentioning(word: String): List<String> {
        synchronized(lock) {
            build()
            val ids = filesOfWord[word] ?: return emptyList()
            val result = ArrayList<String>(ids.size)
            val iterator = ids.iterator()
            while (iterator.hasNext()) paths[iterator.nextInt()]?.let(result::add)
            return result
        }
    }

    /** Builds the index ahead of the first search. */
    fun warmUp() = synchronized(lock) { build() }

    /** Re-reads a file after it was created or changed on disk, or drops it when it no longer exists. */
    fun update(path: String) {
        synchronized(lock) {
            if (!built) return
            val file = File(path)
            if (!file.isFile) remove(path) else put(path, file)
        }
    }

    /** When each file was read (modification time and size), to reuse the saved words of unchanged files. */
    private val stamps = HashMap<String, Long>()
    private var dirty = false

    private fun build() {
        if (built) return
        val start = System.currentTimeMillis()
        // The last run's words, by path: a file whose stamp is unchanged isn't read again. Reading 15,000 files took
        // 6 s on an idle machine and over a minute while the server started.
        val saved = load()
        var read = 0
        roots().distinct().forEach { root ->
            root.toFile().walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val path = file.absolutePath
                    val previous = saved[path]
                    if (previous != null && previous.first == stampOf(file)) {
                        add(path, previous.second, previous.first)
                    } else {
                        put(path, file)
                        read++
                    }
                }
        }
        built = true
        if (read > 0 || saved.size != idByPath.size) dirty = true
        save()
        info("Word index: ${idByPath.size} files (${read} read), ${filesOfWord.size} identifiers in ${System.currentTimeMillis() - start} ms")
    }

    private fun stampOf(file: File): Long = file.lastModified() * 31 + file.length()

    private fun put(path: String, file: File) {
        val stamp = stampOf(file)
        val text = runCatching { file.readText() }.getOrNull() ?: return remove(path)
        add(path, identifiers(text), stamp)
    }

    private fun add(path: String, words: Array<String>, stamp: Long) {
        val id = idByPath.getOrPut(path) { paths.add(path); wordsOfFile.add(null); paths.size - 1 }
        wordsOfFile[id]?.forEach { filesOfWord[it]?.remove(id) }
        wordsOfFile[id] = words
        stamps[path] = stamp
        dirty = true
        for (word in words) filesOfWord.getOrPut(word) { IntOpenHashSet(4) }.add(id)
    }

    /** Writes the index for the next start, if it changed. */
    fun save() = synchronized(lock) {
        val target = saveFile ?: return@synchronized
        if (!built || !dirty) return@synchronized
        runCatching {
            val temp = target.resolveSibling("${target.fileName}.tmp").toFile()
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(temp.outputStream()), 1 shl 16)).use { out ->
                out.writeInt(FORMAT)
                val live = idByPath.entries.filter { wordsOfFile[it.value] != null }
                out.writeInt(live.size)
                for ((path, id) in live) {
                    out.writeUTF(path)
                    out.writeLong(stamps[path] ?: 0)
                    val words = wordsOfFile[id]!!
                    out.writeInt(words.size)
                    for (word in words) out.writeUTF(word)
                }
            }
            java.nio.file.Files.move(temp.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            dirty = false
        }.onFailure { info("Word index not saved: ${it.message}") }
    }

    private fun load(): Map<String, Pair<Long, Array<String>>> {
        val source = saveFile?.toFile()?.takeIf { it.isFile } ?: return emptyMap()
        return runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(source.inputStream()), 1 shl 16)).use { input ->
                if (input.readInt() != FORMAT) return emptyMap()
                val count = input.readInt()
                val result = HashMap<String, Pair<Long, Array<String>>>(count * 2)
                repeat(count) {
                    val path = input.readUTF()
                    val stamp = input.readLong()
                    val words = Array(input.readInt()) { input.readUTF().intern() }
                    result[path] = stamp to words
                }
                result
            }
        }.getOrElse { emptyMap() }
    }

    private fun remove(path: String) {
        val id = idByPath.remove(path) ?: return
        wordsOfFile[id]?.forEach { filesOfWord[it]?.remove(id) }
        wordsOfFile[id] = null
        paths[id] = null
        stamps.remove(path)
        dirty = true
    }

    companion object {
        private const val FORMAT = 1

        /** Distinct identifiers of a source text; words are interned so files share their strings. */
        fun identifiers(text: String): Array<String> {
            val words = HashSet<String>()
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (Character.isLetter(c) || c == '_') {
                    val start = i
                    while (i < n && (Character.isLetterOrDigit(text[i]) || text[i] == '_')) i++
                    words.add(text.substring(start, i).intern())
                } else if (Character.isDigit(c)) {
                    // A number such as 0x1F or 10L, not an identifier.
                    while (i < n && (Character.isLetterOrDigit(text[i]) || text[i] == '_')) i++
                } else {
                    i++
                }
            }
            return words.toTypedArray()
        }
    }
}
