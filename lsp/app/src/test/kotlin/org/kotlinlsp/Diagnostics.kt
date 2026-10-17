/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp

import org.eclipse.lsp4j.PublishDiagnosticsParams

/**
 * Diagnostics are worked out in the background after a file is opened, edited or changed on disk: runs [action], then
 * waits for the next report on the file whose uri ends with [suffix].
 */
fun MutableList<PublishDiagnosticsParams>.reportAfter(suffix: String, action: () -> Unit): PublishDiagnosticsParams {
    val before = count { it.uri.endsWith(suffix) }
    action()
    val deadline = System.currentTimeMillis() + 120_000
    while (count { it.uri.endsWith(suffix) } <= before) {
        check(System.currentTimeMillis() < deadline) { "no diagnostics for $suffix" }
        Thread.sleep(50)
    }
    return last { it.uri.endsWith(suffix) }
}
