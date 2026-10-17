/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.services

import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCliJavaFileManagerImpl
import org.kotlinlsp.common.warn
import java.util.Collections

/**
 * [KotlinCliJavaFileManagerImpl] caches class lookups in plain (fastutil) hash maps. That is fine for the
 * single-threaded compiler, but this server resolves on several threads at once (index workers and LSP requests).
 * Concurrent puts corrupted a map, after which every Java class lookup failed with
 * "ArrayIndexOutOfBoundsException: Index -1 out of bounds" until the server restarted. The class is final, so its
 * cache fields are replaced with synchronized maps right after it is created.
 */
/** Forgets cached Java class lookups, which also remember misses, after Java files were added or removed. */
fun clearCaches(manager: KotlinCliJavaFileManagerImpl) {
    for (name in listOf("topLevelClassesCache", "binaryCache")) {
        try {
            val field = KotlinCliJavaFileManagerImpl::class.java.getDeclaredField(name)
            field.isAccessible = true
            (field.get(manager) as MutableMap<*, *>).clear()
        } catch (e: ReflectiveOperationException) {
            warn("Could not clear KotlinCliJavaFileManagerImpl.$name: $e")
        }
    }
}

fun makeThreadSafe(manager: KotlinCliJavaFileManagerImpl) {
    for (name in listOf("topLevelClassesCache", "binaryCache")) {
        try {
            val field = KotlinCliJavaFileManagerImpl::class.java.getDeclaredField(name)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val current = field.get(manager) as MutableMap<Any?, Any?>
            field.set(manager, Collections.synchronizedMap(HashMap(current)))
        } catch (e: ReflectiveOperationException) {
            warn("Could not make KotlinCliJavaFileManagerImpl.$name thread-safe: $e")
        }
    }
}
