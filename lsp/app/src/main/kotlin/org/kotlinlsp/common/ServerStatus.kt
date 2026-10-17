/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.common

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the server is busy with, sent to the client as `koda/status` for its status bar: loading the project, indexing
 * (files done of those queued), analysing a file, or ready. Sent at most twice a second, and only when it changed.
 */
object ServerStatus {
    enum class Phase { LOADING, INDEXING, READY, FAILED }

    /** The `koda/status` payload. */
    data class Status(
        val state: String,
        val indexed: Int,
        val toIndex: Int,
        val analyzing: String?,
        /** Why the project couldn't be loaded, when [state] is `failed`. */
        val message: String? = null,
        /**
         * Set when `ready` follows indexing library jars (first open, new dependencies): the client should restart the
         * server once. Java signatures of library classes are read through PSI, and a reference to a Kotlin library
         * class that wasn't indexed yet stayed unresolved in IntelliJ's caches, under the analysis sessions:
         * `LiveData.observe(owner, Observer<in T>)` became a raw `Observer`, so `observe { f.onNewIntent(it) }` got
         * `it: T` and a false type mismatch. A server started on a complete index doesn't have this; rebuilding those
         * caches in place raced with the source indexer.
         */
        val restartRecommended: Boolean = false,
        /**
         * A module being set up for analysis (its session and compiler plugins), the first time a file needs it: the
         * first analysis after a start sets up the file's module and every module it depends on.
         */
        val preparing: String? = null,
        /** Modules set up so far. */
        val modulesPrepared: Int = 0,
    )

    @Volatile private var send: ((Status) -> Unit)? = null
    @Volatile private var phase = Phase.LOADING
    private val indexed = AtomicInteger()
    private val toIndex = AtomicInteger()
    private val libraryFilesIndexed = AtomicInteger()
    private val analyzing = ConcurrentHashMap<String, Int>()
    @Volatile private var lastSent: Status? = null
    @Volatile private var failure: String? = null

    private val publisher = Executors.newSingleThreadScheduledExecutor { Thread(it, "KotlinLSP-Status").apply { isDaemon = true } }

    /** The client to tell; a later client (TCP mode) replaces the earlier one. */
    @Synchronized
    fun connect(sender: (Status) -> Unit) {
        val first = send == null
        send = sender
        lastSent = null
        if (first) publisher.scheduleWithFixedDelay(::publish, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun setPhase(value: Phase) {
        phase = value
        if (value == Phase.READY) publish()
    }

    /** The project couldn't be loaded: the client shows [message] instead of "loading" forever. */
    fun failed(message: String) {
        failure = message
        setPhase(Phase.FAILED)
        publish()
    }

    fun fileQueued() = toIndex.incrementAndGet()

    fun fileIndexed() = indexed.incrementAndGet()

    /** A class file of a library jar was indexed: the index changed under analysis already done. */
    fun libraryFileIndexed() = libraryFilesIndexed.incrementAndGet()

    private val preparing = ConcurrentHashMap<String, Int>()
    private val modulesPrepared = AtomicInteger()
    @Volatile private var lastPrepared: String? = null
    @Volatile private var lastPreparedAt = 0L

    /** Runs [block] while the status names module [id] as being set up. */
    fun <T> preparingModule(id: String, block: () -> T): T {
        preparing.merge(id, 1, Int::plus)
        try {
            return block()
        } finally {
            preparing.computeIfPresent(id) { _, count -> if (count > 1) count - 1 else null }
            modulesPrepared.incrementAndGet()
            lastPrepared = id
            lastPreparedAt = System.currentTimeMillis()
        }
    }

    /** Runs [block] while the status names [fileName] as being analysed. */
    fun <T> analyzing(fileName: String, block: () -> T): T {
        analyzing.merge(fileName, 1, Int::plus)
        try {
            return block()
        } finally {
            analyzing.computeIfPresent(fileName) { _, count -> if (count > 1) count - 1 else null }
        }
    }

    @Synchronized
    private fun publish() {
        val sender = send ?: return
        val status = Status(
            state = phase.name.lowercase(),
            indexed = indexed.get(),
            toIndex = toIndex.get(),
            analyzing = analyzing.keys.firstOrNull(),
            message = failure,
            restartRecommended = phase == Phase.READY && libraryFilesIndexed.get() > 0,
            // Shown a moment after the last one too, so a run of quick ones doesn't flicker.
            preparing = preparing.keys.firstOrNull() ?: lastPrepared?.takeIf { System.currentTimeMillis() - lastPreparedAt < 1500 },
            modulesPrepared = modulesPrepared.get(),
        )
        if (status == lastSent) return
        lastSent = status
        runCatching { sender(status) }
    }
}
