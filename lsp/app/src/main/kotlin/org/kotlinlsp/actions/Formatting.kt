/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.kotlinlsp.common.warn
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader

/**
 * Reformat Code (Android Studio's Cmd+Option+L) with ktlint, which ships in the server's `formatter/` folder with its
 * own Kotlin compiler and runs in a class loader of its own.
 */
object Formatting {
    private val format: Method? by lazy {
        val explicit = System.getProperty("kotlinlsp.formatter")
        val jars = if (explicit != null) {
            explicit.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
        } else {
            // <install>/lib/app.jar → <install>/formatter
            val libDir = File(Formatting::class.java.protectionDomain.codeSource.location.toURI()).parentFile
            File(libDir.parentFile, "formatter").listFiles { file -> file.extension == "jar" }?.toList().orEmpty()
        }
        if (jars.isEmpty()) {
            warn("No formatter found; Format Document is off")
            return@lazy null
        }
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
        loader.loadClass("org.kotlinlsp.formatter.KtlintFormatter").getMethod("format", String::class.java, String::class.java)
    }

    /** The edit that formats [text], the contents of the file at [path]; none when it's formatted or doesn't parse. */
    fun formatEdits(text: String, path: String): List<TextEdit> {
        val method = format ?: return emptyList()
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        val formatted = try {
            thread.contextClassLoader = method.declaringClass.classLoader
            method.invoke(null, text, path) as String
        } catch (e: InvocationTargetException) {
            // Code that doesn't parse yet is left alone, as Android Studio does.
            warn("Format: ${e.targetException.message?.lineSequence()?.firstOrNull()}")
            return emptyList()
        } finally {
            thread.contextClassLoader = previous
        }
        return minimalEdit(text, formatted)
    }
}

/** One edit replacing only the part between the common start and end, so the cursor and folds stay where they are. */
fun minimalEdit(old: String, new: String): List<TextEdit> {
    if (old == new) return emptyList()
    var start = 0
    val shortest = minOf(old.length, new.length)
    while (start < shortest && old[start] == new[start]) start++
    var oldEnd = old.length
    var newEnd = new.length
    while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return listOf(TextEdit(Range(positionOf(old, start), positionOf(old, oldEnd)), new.substring(start, newEnd)))
}

private fun positionOf(text: String, offset: Int): Position {
    var line = 0
    var lineStart = 0
    for (i in 0 until offset) if (text[i] == '\n') {
        line++
        lineStart = i + 1
    }
    return Position(line, offset - lineStart)
}
