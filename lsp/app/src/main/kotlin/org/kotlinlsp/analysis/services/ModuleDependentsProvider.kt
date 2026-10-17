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

import com.intellij.util.containers.ContainerUtil.createConcurrentSoftMap
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProviderBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.allDirectDependencies
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.common.profile

class ModuleDependentsProvider: KotlinModuleDependentsProviderBase() {
    private lateinit var modules: List<Module>

    fun setup(modules: List<Module>) {
        this.modules = modules
    }

    /** Every module in the graph; [modules] holds only the roots, which hid the dependents of inner modules. */
    private val allModules: List<KaModule> by lazy { modules.asFlatSequence().map { it.kaModule }.toList() }

    // Merged per key: `Map + Map` kept only the last module's dependents, so an edit in a shared module (core:model)
    // left most of its dependents' analysis sessions stale ("An analysis session acquired via `getAnalysisSession`
    // must be valid").
    private val directDependentsByKtModule: Map<KaModule, Set<KaModule>> by lazy {
        buildDependentsMap(allModules) { it.allDirectDependencies() }
    }

    private val transitiveDependentsByKtModule = createConcurrentSoftMap<KaModule, Set<KaModule>>()

    private val refinementDependentsByKtModule: Map<KaModule, Set<KaModule>> by lazy {
        buildDependentsMap(allModules) { it.transitiveDependsOnDependencies.asSequence() }
    }

    override fun getDirectDependents(module: KaModule): Set<KaModule> = profile("getDirectDependents") {
        directDependentsByKtModule[module].orEmpty()
    }

    override fun getRefinementDependents(module: KaModule): Set<KaModule> =
        profile("getRefinementDependents") {
            refinementDependentsByKtModule[module].orEmpty()
        }

    override fun getTransitiveDependents(module: KaModule): Set<KaModule> =
        profile("getTransitiveDependents") {
            transitiveDependentsByKtModule.computeIfAbsent(module) { computeTransitiveDependents(it) }
        }
}

private inline fun buildDependentsMap(
    modules: List<KaModule>,
    getDependencies: (KaModule) -> Sequence<KaModule>,
): Map<KaModule, MutableSet<KaModule>> = buildMap {
    for (module in modules) {
        for (dependency in getDependencies(module)) {
            if (dependency == module) continue

            val dependents = computeIfAbsent(dependency) { mutableSetOf() }
            dependents.add(module)
        }
    }
}
