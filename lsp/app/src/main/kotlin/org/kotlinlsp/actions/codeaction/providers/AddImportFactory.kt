/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions.codeaction.providers

import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.kotlinlsp.actions.codeaction.CodeActionContext
import org.kotlinlsp.actions.codeaction.CodeActionFactory
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.queries.getCompletions
import org.kotlinlsp.index.Index
import org.kotlinlsp.actions.autocomplete.ImportInserter
import org.kotlinlsp.actions.autocomplete.importPreference
import org.kotlinlsp.actions.autocomplete.isJdkInternal
import org.kotlinlsp.common.toOffset

class AddImportFactory : CodeActionFactory() {
    /**
     * Subclasses implement this method to create specific code actions
     */
    override fun doCreateCodeActions(context: CodeActionContext): List<CodeAction> {
        val codeActions = mutableListOf<CodeAction>()

        for (diagnostic in unresolvedReferenceDiagnostics(context)) {
            val offset = diagnostic.range.start.toOffset(context.ktFile)
            val unresolvedSymbolName = getUnresolvedSymbolName(context.ktFile, offset) ?: continue

            val candidates = findImportCandidates(context.index, unresolvedSymbolName)

            val imports = ImportInserter(context.ktFile)
            candidates.forEachIndexed { i, fqn ->
                val textEdit = imports.editFor(fqn) ?: return@forEachIndexed
                codeActions.add(CodeAction().apply {
                    title = "Import '$fqn'"
                    kind = CodeActionKind.QuickFix
                    edit = WorkspaceEdit(mapOf(context.uri to listOf(textEdit)))
                    // The first, preferred by where it comes from (`android.util.Log`), is the one Quick Fix applies.
                    isPreferred = i == 0
                    diagnostics = listOf(diagnostic)
                })
            }
        }

        return codeActions.distinctBy { it.title }
    }

    /** Importable classes and top-level functions of that name: not private, internal to a library, or JDK internals. */
    private fun findImportCandidates(index: Index, symbolName: String): List<String> =
        index.getCompletions(symbolName)
            .filter { it.name == symbolName }
            .take(200)
            .mapNotNull {
                when (it) {
                    is Declaration.Function -> it.fqName.takeIf { _ -> it.isTopLevel && !it.isPrivate }
                    is Declaration.Class -> it.fqName.takeIf { _ -> it.isTopLevel && !it.isPrivate }
                    else -> null
                }
            }
            .filterNot(::isJdkInternal)
            .distinct()
            .sortedWith(compareBy({ importPreference(it) }, { it.length }, { it }))
            .toList()

    private fun getUnresolvedSymbolName(ktFile: KtFile, offset: Int): String? {
        val element = ktFile.findElementAt(offset)
        return element?.parentOfType<KtReferenceExpression>(withSelf = true)?.text
    }

    private fun unresolvedReferenceDiagnostics(context: CodeActionContext): List<Diagnostic> {
        return context.diagnostics.filter {
            it.code.left == "UNRESOLVED_REFERENCE"
        }
    }

    /**
     * Check if this provider can handle the given context
     */
    override fun isApplicable(context: CodeActionContext): Boolean {
        return unresolvedReferenceDiagnostics(context).isNotEmpty()
    }
}
