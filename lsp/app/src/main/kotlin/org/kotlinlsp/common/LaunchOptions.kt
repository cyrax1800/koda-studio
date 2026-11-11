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

import com.google.gson.JsonObject

/**
 * Options a client passes in `initializationOptions`.
 *
 * - `modulesFile`: a module graph in the [org.kotlinlsp.analysis.modules.SerializedModule] format, written by the
 *   client. When set, the server reads only this file and never runs its own Gradle import.
 * - `indexDir`: where the index and caches live, instead of `<workspace>/.kotlin-lsp`.
 * - `composeStability`: Compose stability hints, warnings and hover (default on).
 * - `namedArguments`: completed calls pass required arguments by name, `Text(text = …)` (default on).
 * - `parameterNameHints`: `literals` (default), `all` or `none`: parameter names shown before call arguments.
 * - `watchFiles`: register a watcher for Kotlin and Java files with the client (default off; VS Code's extension watches
 *   files itself, Zed only reports changes to watchers a server registers).
 */
object LaunchOptions {
    @Volatile var modulesFile: String? = null
        private set
    @Volatile var indexDir: String? = null
        private set
    @Volatile var composeStability: Boolean = true
        private set
    @Volatile var namedArguments: Boolean = true
        private set
    @Volatile var parameterNameHints: String = "literals"
        private set

    @Volatile var watchFiles: Boolean = false
        private set

    fun read(initializationOptions: Any?) {
        val json = initializationOptions as? JsonObject ?: return
        modulesFile = json.get("modulesFile")?.takeIf { it.isJsonPrimitive }?.asString
        indexDir = json.get("indexDir")?.takeIf { it.isJsonPrimitive }?.asString
        composeStability = json.get("composeStability")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        namedArguments = json.get("namedArguments")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        parameterNameHints = json.get("parameterNameHints")?.takeIf { it.isJsonPrimitive }?.asString ?: "literals"
        watchFiles = json.get("watchFiles")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    }
}
