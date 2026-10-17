/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.formatter

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigDefaults
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.ec4j.core.model.EditorConfig
import org.ec4j.core.model.Glob
import org.ec4j.core.model.Property
import org.ec4j.core.model.Section
import java.io.File
import java.nio.file.Paths

/**
 * Formats Kotlin like Android Studio's Reformat Code. Only Java types cross the class loader boundary.
 *
 * Reformat Code changes whitespace (indentation, spaces, blank lines) and the import order, and keeps everything else:
 * line breaks, trailing commas, block bodies. So by default only ktlint's whitespace rules run, in its `android_studio`
 * style. A project whose `.editorconfig` configures ktlint gets its full ktlint setup instead.
 */
object KtlintFormatter {
    /** ktlint's rules that only change whitespace or import order, as Reformat Code does. */
    private val WHITESPACE_RULES = setOf(
        "indent", "no-trailing-spaces", "no-multi-spaces", "no-consecutive-blank-lines", "no-blank-line-before-rbrace",
        "no-empty-first-line-in-method-block", "final-newline", "import-ordering",
        "annotation-spacing", "block-comment-initial-star-alignment", "comment-spacing", "fun-keyword-spacing",
        "function-return-type-spacing", "function-start-of-body-spacing", "function-type-modifier-spacing",
        "function-type-reference-spacing", "modifier-list-spacing", "nullable-type-spacing", "parameter-list-spacing",
        "spacing-around-angle-brackets", "colon-spacing", "comma-spacing", "curly-spacing", "dot-spacing",
        "double-colon-spacing", "keyword-spacing", "op-spacing", "paren-spacing", "range-spacing",
        "square-brackets-spacing", "unary-op-spacing", "spacing-between-function-name-and-opening-parenthesis",
        "type-argument-list-spacing", "type-parameter-list-spacing", "try-catch-finally-spacing",
    )

    private val defaults by lazy {
        val section = Section.builder().glob(Glob("*.{kt,kts}"))
        listOf(
            "ktlint_code_style" to "android_studio",
            // Android Studio's import order (and Optimize Imports'): java, javax and kotlin after the rest, aliases last.
            "ij_kotlin_imports_layout" to "*,java.**,javax.**,kotlin.**,^",
        ).forEach { (name, value) -> section.properties(Property.builder().name(name).value(value)) }
        EditorConfigDefaults(EditorConfig.builder().section(section).build())
    }

    private val reformatCode by lazy {
        KtLintRuleEngine(
            ruleProviders = StandardRuleSetProvider().getRuleProviders().filter { it.ruleId.value.substringAfter(':') in WHITESPACE_RULES }.toSet(),
            editorConfigDefaults = defaults,
        )
    }

    private val projectKtlint by lazy {
        KtLintRuleEngine(ruleProviders = StandardRuleSetProvider().getRuleProviders(), editorConfigDefaults = defaults)
    }

    /** The formatted [text] of the file at [path] (which locates its `.editorconfig`); throws on code that doesn't parse. */
    @JvmStatic
    fun format(text: String, path: String): String {
        val engine = if (usesKtlint(File(path))) projectKtlint else reformatCode
        return engine.format(Code.fromSnippetWithPath(text, Paths.get(path))) { _, _ -> }
    }

    /** The rule ids the default formatting runs, for checking [WHITESPACE_RULES] against the ktlint version. */
    @JvmStatic
    fun ruleIds(): String = StandardRuleSetProvider().getRuleProviders().joinToString(" ") {
        val id = it.ruleId.value.substringAfter(':')
        if (id in WHITESPACE_RULES) "[$id]" else id
    }

    /** Whether an `.editorconfig` above [file] (up to one marked `root = true`) sets any ktlint property. */
    private fun usesKtlint(file: File): Boolean {
        var dir = file.absoluteFile.parentFile
        while (dir != null) {
            val config = File(dir, ".editorconfig")
            if (config.isFile) {
                val text = runCatching { config.readText() }.getOrDefault("")
                if (text.contains("ktlint")) return true
                if (Regex("""(?im)^\s*root\s*=\s*true""").containsMatchIn(text)) return false
            }
            dir = dir.parentFile
        }
        return false
    }
}
