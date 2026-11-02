/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Modified 2026 by Koda Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.common

import com.intellij.openapi.diagnostic.ExceptionWithAttachments
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val key = Key.create<ReentrantReadWriteLock>("org.kotlinlsp.rwlock")
private val lock = ReentrantReadWriteLock()

fun Project.registerRWLock() {
    putUserData(key, lock)
}

fun <T> Project.read(fn: () -> T): T {
    val lock = getUserData(key)!!
    return lock.read { withAttachmentsLogged(fn) }
}

/** Analysis API errors explain themselves only in their attachments (module, session state), which stack traces omit. */
private inline fun <T> withAttachmentsLogged(fn: () -> T): T = try {
    fn()
} catch (e: Throwable) {
    if (e is ExceptionWithAttachments) {
        e.attachments.forEach { warn("${e.message} [${it.path}]: ${it.displayText.take(2000)}") }
    }
    throw e
}

/**
 * Whether this thread holds the read lock while a write waits for it. Readers that come after the write queue behind
 * it, so waiting here for another thread's read (the source scan) waits forever.
 */
fun Project.readHeldWhileWriteWaits(): Boolean {
    val lock = getUserData(key)!!
    return lock.readHoldCount > 0 && lock.hasQueuedThreads()
}

fun <T> Project.write(fn: () -> T): T {
    val lock = getUserData(key)!!
    cancelBackgroundReads()
    return lock.write(fn)
}

/** Background reads in flight, which an edit cancels. */
private val backgroundReads: MutableSet<AbstractProgressIndicatorBase> = ConcurrentHashMap.newKeySet()

/**
 * A read that an edit cancels, as IntelliJ cancels background read actions when you type: diagnostics, semantic
 * highlighting, inlay hints, code actions. Their results would be for the old text, and the edit (then the completion
 * after it) would wait for them: opening a large file held every keystroke for seconds. The Analysis API checks for
 * cancellation as it resolves and throws [ProcessCanceledException]; [isCanceled] also stops it, e.g. when the client
 * cancelled the request.
 */
fun <T> Project.cancellableRead(isCanceled: () -> Boolean = { false }, fn: () -> T): T {
    val indicator = object : AbstractProgressIndicatorBase() {
        override fun isCanceled(): Boolean = super.isCanceled() || isCanceled()
    }
    backgroundReads += indicator
    try {
        var result: Result<T>? = null
        ProgressManager.getInstance().executeProcessUnderProgress({ result = runCatching { read(fn) } }, indicator)
        return result!!.getOrThrow()
    } finally {
        backgroundReads -= indicator
    }
}

/** Cancels the background reads in flight: an edit is coming. */
fun cancelBackgroundReads() {
    backgroundReads.forEach { it.cancel() }
}

/** Whether [e] is (or wraps) a cancellation of a read. */
fun isCancellation(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(10).any { it is ProcessCanceledException || it is java.util.concurrent.CancellationException }
