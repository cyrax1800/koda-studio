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

package org.kotlinlsp.analysis.registration

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.FirStandaloneServiceRegistrar
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment

/**
 * Registers the Analysis API engine (FIR) from its own plugin descriptors, as the standalone platform does: services,
 * extension points, listeners, reference providers and the Java element finders. Copying that list by hand broke on
 * every Kotlin release, because services move and get renamed. The LSP platform's own services are registered
 * afterwards and replace the standalone defaults (see [lspPlatform]).
 */
private val applicationServicesRegistered = Key.create<Boolean>("org.kotlinlsp.applicationServicesRegistered")

fun Registrar.analysisApiFir() {
    // Java PSI extension points (element finders, etc.) must exist before the engine registers extensions to them.
    KotlinCoreEnvironment.registerProjectExtensionPoints(project.extensionArea)
    // Reloading a file changed on disk notifies PSI tree listeners, an extension point the CLI environment leaves out.
    if (!project.extensionArea.hasExtensionPoint("com.intellij.psi.treeChangeListener")) {
        projectExtensionPoint("com.intellij.psi.treeChangeListener", PsiTreeChangeListener::class.java)
    }
    with(FirStandaloneServiceRegistrar) {
        // The application is shared by every session in this JVM (KotlinCoreEnvironment.getOrCreateApplicationEnvironment);
        // registering its services again for a second session fails with "Key ... duplicated".
        if (app.getUserData(applicationServicesRegistered) != true) {
            registerApplicationServices(app, disposable)
            app.putUserData(applicationServicesRegistered, true)
        }
        registerProjectExtensionPoints(project)
        registerProjectServices(project)
        registerProjectModelServices(project, disposable)
    }
    Registry.get("kotlin.analysis.lowMemoryCacheCleanup").setValue(true)
}
