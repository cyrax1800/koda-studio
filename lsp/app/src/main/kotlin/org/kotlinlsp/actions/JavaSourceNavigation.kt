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

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.kotlinlsp.common.normalizeUri
import org.kotlinlsp.common.uriToPath
import org.kotlinlsp.index.Index
import java.io.File

/**
 * Go to definition inside a library's Java source that go to definition opened (`AppCompatActivity.java`, under the
 * index's `decompiled/` folder): the file is parsed as Java and its references resolved against all libraries, so a
 * click leads to a declaration in the same file, or to another library class's source (or decompiled class).
 */
fun javaSourceDefinition(project: Project, uri: String, position: Position, index: Index): List<Location>? {
    val file = runCatching { File(uri.uriToPath()) }.getOrNull()?.takeIf { it.isFile } ?: return null
    val text = file.readText()
    val psiFile = PsiFileFactory.getInstance(project).createFileFromText(file.name, JavaLanguage.INSTANCE, text, false, false)
    val offset = offsetOf(text, position)
    val reference = psiFile.findReferenceAt(offset) ?: return null
    val targets = (reference as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(reference.resolve())
    return targets.mapNotNull { target ->
        when {
            // A declaration in this file: a field, method, inner class or local variable.
            target.containingFile == psiFile -> locationIn(uri, text, target)
            else -> libraryLocation(target, index)
        }
    }.distinctBy { it.uri + it.range.start }
}

private fun locationIn(uri: String, text: String, target: PsiElement): Location {
    val offset = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: target.textRange.startOffset
    val start = positionOf(text, offset)
    return Location(uri.normalizeUri(), Range(start, start))
}

private fun offsetOf(text: String, position: Position): Int {
    var offset = 0
    repeat(position.line) { offset = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 } }
    return (offset + position.character).coerceAtMost(text.length)
}

private fun positionOf(text: String, offset: Int): Position {
    val line = text.substring(0, offset).count { it == '\n' }
    return Position(line, offset - (text.lastIndexOf('\n', offset - 1) + 1))
}

