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

package org.kotlinlsp.index

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.psi.KtFile

sealed class Command {
    data object Stop : Command()
    data object SourceScanningFinished: Command()
    data object SourceIndexingFinished: Command()
    /** [onComplete] runs once every queued file has been indexed, and not when indexing is stopped before that. */
    class IndexingFinished(val onComplete: () -> Unit = {}) : Command()
    data class ScanSourceFile(val virtualFile: VirtualFile) : Command()
    data class IndexSource(val virtualFile: VirtualFile) : Command()
    data class IndexModifiedFile(val ktFile: KtFile) : Command()
    data class IndexFile(val virtualFile: VirtualFile) : Command()
}
