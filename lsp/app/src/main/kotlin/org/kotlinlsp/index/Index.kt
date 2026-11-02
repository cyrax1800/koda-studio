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

package org.kotlinlsp.index

import org.kotlinlsp.common.ServerStatus
import com.intellij.psi.PsiJavaFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.analysis.modules.SourceModule
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.common.read
import org.kotlinlsp.index.db.Database
import org.kotlinlsp.index.worker.WorkerThread
import org.kotlinlsp.index.worker.WorkerThreadNotifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import org.kotlinlsp.common.readHeldWhileWriteWaits

interface IndexNotifier {
    fun onBackgroundIndexFinished()
}

class Index(
    private val modules: List<Module>,
    private val project: Project,
    val rootFolder: String,
    notifier: IndexNotifier
) {
    private val sourceFileScanningFinishedSignal = CountDownLatch(1)
    private val workerThreadNotifier = object : WorkerThreadNotifier {
        override fun onSourceFileScanningFinished() {
            sourceFileScanningFinishedSignal.countDown()
        }

        override fun onBackgroundIndexFinished() = notifier.onBackgroundIndexFinished()
    }
    private val db = Database(rootFolder)
    private val workerThreadRunner = WorkerThread(db, project, workerThreadNotifier)
    private val workerThread = Thread(workerThreadRunner, "KotlinLSP-Worker")
    private val scanFilesThreadRunner = ScanFilesThread(workerThreadRunner, modules, db)
    private val scanFilesThread = Thread(scanFilesThreadRunner, "KotlinLSP-ScanFiles")
    private val openedFiles: MutableMap<String, KtFile> = ConcurrentHashMap()

    // This cache prevents parsing KtFiles over and over
    private val ktFileCache = Caffeine.newBuilder()
        .maximumSize(100)
        .build<String, KtFile>()

    fun syncIndexInBackground() {
        // We have 2 threads here
        // Scan files -> It scans files to index, loads them as a KtFile and submits them to a work queue
        // Worker     -> Takes each KtFile, fetches its symbols and writes them to the index database
        // The scan files thread will stop when the background indexing is done, while the worker is kept alive
        // for the duration of the server. It will keep receiving work whenever a KtFile is edited by the user so the
        // index is always up to date
        ServerStatus.setPhase(ServerStatus.Phase.INDEXING)
        workerThread.start()
        scanFilesThread.start()
    }

    /** Drops a deleted file's declarations, and the parsed copy kept for files that aren't open. */
    fun removeFile(url: String) {
        ktFileCache.invalidate(url)
        db.removeFile(url)
    }

    /** Forgets the parsed copy of a file that changed on disk. */
    fun forgetCachedFile(url: String) = ktFileCache.invalidate(url)

    /** Indexes [ktFile] on this thread, for changes that must be visible before the next analysis. */
    fun indexNow(ktFile: KtFile) = org.kotlinlsp.index.worker.indexKtFile(project, ktFile, db)

    fun queueOnFileChanged(ktFile: KtFile) {
        workerThreadRunner.submitCommand(Command.IndexModifiedFile(ktFile))
    }

    fun close() {
        scanFilesThreadRunner.signalToStop()
        workerThreadRunner.submitCommand(Command.Stop)
        scanFilesThread.join()
        workerThread.join()

        wordIndex.save()
        db.close()
    }

    fun <T> query(block: (connection: Database) -> T): T {
        awaitSourceScan()
        return block(db)
    }

    /**
     * Waits for the first scan of source files. Diagnostics waited here holding the read lock; a changed file queued
     * the write lock, the scan's next read queued behind that, and the three waited for each other for good: the
     * server stopped reading messages, `shutdown` too, and outlived its window. Such a read now backs out as cancelled
     * (diagnostics and background requests run again after the write), as does any read an edit cancels.
     */
    private fun awaitSourceScan() {
        while (!sourceFileScanningFinishedSignal.await(50, TimeUnit.MILLISECONDS)) {
            ProgressManager.checkCanceled()
            if (project.readHeldWhileWriteWaits()) throw ProcessCanceledException()
        }
    }

    fun openKtFile(path: String, ktFile: KtFile) {
        openedFiles[path] = ktFile
    }

    fun closeKtFile(path: String) {
        openedFiles.remove(path)
    }

    fun getOpenedKtFile(path: String): KtFile? = openedFiles[path]

    val openedKtFiles: Sequence<Map.Entry<String, KtFile>>
        get() = openedFiles.asSequence()

    fun getKtFile(virtualFile: VirtualFile): KtFile? {
        // First check opened files
        val openedFile = openedFiles.get(virtualFile.url)
        if(openedFile != null) return openedFile

        // Then check the cache
        val cachedKtFile = ktFileCache.getIfPresent(virtualFile.url)
        if(cachedKtFile != null) return cachedKtFile

        // If not, load from disk and store in cache
        val ktFile = project.read { PsiManager.getInstance(project).findFile(virtualFile) as? KtFile } ?: return null
        ktFileCache.put(virtualFile.url, ktFile)
        return ktFile
    }

    /**
     * Kotlin source files of all source modules whose text contains [word] as a whole identifier. Open files are checked
     * with their current (possibly unsaved) text. This is the cheap first pass of find-references: only these files
     * are then resolved.
     */
    fun sourceFilesMentioning(word: String): List<KtFile> {
        if (word.isEmpty()) return emptyList()
        val pattern = wordPattern(word)
        // Open files by their current, possibly unsaved, text; the others through the word index.
        val opened = openedFiles.filter { (_, file) -> pattern.containsMatchIn(file.text) }.values
        val onDisk = wordIndex.filesMentioning(word)
            .filter { it.endsWith(".kt") && "file://$it" !in openedFiles }
            .mapNotNull { path -> VirtualFileManager.getInstance().findFileByUrl("file://$path")?.let(::getKtFile) }
        return opened + onDisk
    }

    /** Java files of the source modules (including generated Hilt/Dagger/Room code) that contain [word] as a word. */
    fun javaFilesMentioning(word: String): List<PsiJavaFile> {
        if (word.isEmpty()) return emptyList()
        return wordIndex.filesMentioning(word)
            .filter { it.endsWith(".java") }
            .mapNotNull { path ->
                val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$path") ?: return@mapNotNull null
                PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
            }
    }

    /** Which source files contain an identifier; see [WordIndex]. */
    private val wordIndex = WordIndex(
        roots = { modules.asFlatSequence().filterIsInstance<SourceModule>().flatMap { it.contentRoots.asSequence() }.toList() },
        saveFile = org.kotlinlsp.common.getCachePath(rootFolder).resolve("word-index.bin"),
    )

    /**
     * Reads the source files for find references in the background, so the first search doesn't wait for it. Analysis
     * uses it too (sealed classes' inheritors), so it starts right away.
     */
    fun warmUpWordIndex() {
        Thread({
            runCatching { wordIndex.warmUp() }.onFailure { org.kotlinlsp.common.warn("Word index failed: $it") }
        }, "KotlinLSP-word-index").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Keeps the word index current for a file created, changed or deleted on disk. */
    fun sourceFileChangedOnDisk(url: String) = wordIndex.update(url.removePrefix("file://"))

    private fun wordPattern(word: String) = Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(word)}(?![\\p{L}\\p{N}_])")

    fun addVirtualFileToModuleScope(virtualFile: VirtualFile) {
        modules.asFlatSequence()
            .filterIsInstance<SourceModule>()
            .firstOrNull { module ->
                // Rough check: if any of the module's content roots is a prefix of the file path
                // we treat it as belonging to that module
                val path = virtualFile.url
                module.contentRoots.any { path.startsWith("file://${it.toFile().absolutePath}") }
            }
            ?.addFileToContentScope(virtualFile)
    }
}
