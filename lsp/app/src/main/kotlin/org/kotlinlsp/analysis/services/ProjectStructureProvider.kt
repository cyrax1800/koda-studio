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

package org.kotlinlsp.analysis.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.NotUnderContentRootModule
import org.kotlinlsp.common.profile
import org.kotlinlsp.common.trace

class ProjectStructureProvider: KotlinProjectStructureProviderBase() {
    private lateinit var modules: List<Module>
    private lateinit var project: Project

    private val notUnderContentRootModuleWithoutPsiFile by lazy {
        NotUnderContentRootModule(
            name = "unnamed-outside-content-root",
            moduleDescription = "not-under-content-root module without a PSI file.",
            project = project,
        )
    }

    fun setup(modules: List<Module>, project: Project) {
        this.modules = modules
        this.project = project
    }

    override fun getImplementingModules(module: KaModule): List<KaModule> = profile("getImplementingModules") {
        emptyList()  // TODO Implement for KMP support
    }

    /**
     * Module of each file (per use-site module), as finding it walks the module graph: called over a thousand times per
     * completion on a large project. Files that belong to no module aren't cached, as they may join one later.
     */
    private val moduleByFile = java.util.concurrent.ConcurrentHashMap<Pair<VirtualFile, KaModule?>, KaModule>()

    /**
     * What decides a file's module: the jar (or JDK image) of a class file, the folder of a source file. Keyed by the
     * file itself, each class of a library walked the module graph (hundreds of modules) once: 1.5 s on a first analysis.
     */
    private fun containerOf(file: VirtualFile): VirtualFile =
        if (file.isInLocalFileSystem) file.parent ?: file
        else com.intellij.openapi.vfs.VfsUtilCore.getRootFile(file)

    /** Forgets cached modules after files joined modules (new files on disk). */
    fun clearModuleCache() = moduleByFile.clear()

    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule = profile("getModule") {
        val virtualFile = element.containingFile.virtualFile
        val key = virtualFile?.let { containerOf(it) to useSiteModule }
        if (key != null) moduleByFile[key]?.let { return@profile it }
        val visited = mutableSetOf<KaModule>()

        modules.forEach {
            val moduleFound = searchVirtualFileInModule(virtualFile, useSiteModule ?: it.kaModule, visited)
            if (moduleFound != null) {
                if (key != null) moduleByFile[key] = moduleFound
                return@profile moduleFound
            }
        }

        return@profile NotUnderContentRootModule(
            file = element.containingFile,
            name = "unnamed-outside-content-root",
            moduleDescription = "Standalone not-under-content-root module with a PSI file.",
            project = project,
        )
    }

    private fun searchVirtualFileInModule(virtualFile: VirtualFile, module: KaModule, visited: MutableSet<KaModule>): KaModule? {
        if(visited.contains(module)) return null
        if(module.contentScope.contains(virtualFile)) return module

        for(it in module.directRegularDependencies) {
            val submodule = searchVirtualFileInModule(virtualFile, it, visited)
            if(submodule != null) return submodule
        }

        visited.add(module)
        return null
    }

    @OptIn(KaPlatformInterface::class)
    override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule {
        trace("getNotUnderContentRootModule")
        return notUnderContentRootModuleWithoutPsiFile
    }
}
