/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Ported 2026 by Koda Studio contributors from KotlinStandaloneJvmDependenciesIndex (Kotlin v2.4.20,
 * analysis/analysis-api-standalone/analysis-api-standalone-base), which is internal to the Analysis API.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.services

import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.SmartList
import org.jetbrains.kotlin.cli.jvm.index.JavaFileExtension
import org.jetbrains.kotlin.cli.jvm.index.JavaFileExtensions
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexBase
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File
import java.util.EnumSet

/**
 * Finds *every* class file with a given name across all roots. The compiler's `JvmDependenciesIndexImpl` stops at the
 * first one, which is right for a compiler with one classpath but wrong here: the index spans all modules, and when
 * two modules use different versions of a library (e.g. kotlinx-coroutines 1.9.0 and 1.10.2), the first match may lie
 * outside the use-site module's scope, which then filters it out and the class doesn't resolve at all.
 */
class AllMatchesJvmDependenciesIndex(roots: List<JavaRoot>) : JvmDependenciesIndexBase(roots) {
    /** Per package: dot-separated relative class name → all virtual files of that class. */
    private val classVirtualFilesByPackage = Caffeine.newBuilder()
        .maximumSize(CACHE_SIZE)
        .build<FqName, Map<String, List<VirtualFile>>>()

    /** Forgets the per-package listings, after Java files were added or removed. */
    fun invalidate() = classVirtualFilesByPackage.invalidateAll()

    override fun findClassVirtualFiles(classId: ClassId, acceptedExtensions: JavaFileExtensions): Collection<VirtualFile> {
        val files = virtualFilesForClass(classId, classVirtualFiles(classId.packageFqName))
        if (files.isEmpty()) return emptyList()
        if (acceptedExtensions.extensions.size == JavaFileExtension.entries.size) return files
        return files.filter { file -> file.extension?.let { it in acceptedExtensions } == true }
    }

    /** Nested classes are `A$B` on disk; names are stored with dots, so `$` in a requested name is treated as a dot too. */
    private fun virtualFilesForClass(classId: ClassId, files: Map<String, List<VirtualFile>>): List<VirtualFile> {
        val relativeClassName = classId.relativeClassName.asString()
        files[relativeClassName]?.let { return it }
        if (!relativeClassName.contains('$')) return emptyList()
        return files[relativeClassName.replace('$', '.')] ?: emptyList()
    }

    override fun traverseClassVirtualFilesInPackage(
        packageFqName: FqName,
        acceptedExtensions: JavaFileExtensions,
        continueSearch: (VirtualFile) -> Boolean,
    ) {
        for (files in classVirtualFiles(packageFqName).values) {
            for (file in files) {
                val extension = file.extension
                if (extension != null && extension in acceptedExtensions && !continueSearch(file)) return
            }
        }
    }

    private fun classVirtualFiles(packageFqName: FqName): Map<String, List<VirtualFile>> =
        classVirtualFilesByPackage.get(packageFqName) {
            val result = HashMap<String, SmartList<VirtualFile>>()
            val add = { virtualFile: VirtualFile ->
                if (!virtualFile.isDirectory && virtualFile.extension in CACHED_EXTENSIONS) {
                    result.getOrPut(virtualFile.nameWithoutExtension.replace('$', '.'), ::SmartList).add(virtualFile)
                }
            }
            traverseVirtualFilesInPackage(packageFqName, BINARY_ROOT_TYPES) { virtualFile, _ ->
                add(virtualFile)
                true
            }
            // Source folders are listed from disk: the base index keeps the directories it found, and those keep their
            // first listing, so a Java file added later (generated code, a new file) was never found.
            val relativePath = packageFqName.asString().replace('.', File.separatorChar)
            for (root in sourceRoots) {
                File(root, relativePath).listFiles { file -> file.isFile }?.forEach { file ->
                    VirtualFileManager.getInstance().findFileByNioPath(file.toPath())?.let(add)
                }
            }
            result
        } ?: emptyMap()

    private val sourceRoots: List<File> = roots.filter { it.type == JavaRoot.RootType.SOURCE }.map { File(it.file.path) }

    private companion object {
        const val CACHE_SIZE: Long = 5000
        val BINARY_ROOT_TYPES: EnumSet<JavaRoot.RootType> = EnumSet.complementOf(EnumSet.of(JavaRoot.RootType.SOURCE))
        val CACHED_EXTENSIONS = JavaFileExtension.entries.mapTo(HashSet()) { it.extension }
    }
}
