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

package org.kotlinlsp.actions.autocomplete

import org.jetbrains.kotlin.analysis.api.analyze
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.leavesAroundOffset
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.CompletionItem
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.kotlinlsp.index.Index

private val WORD_BEFORE_CURSOR = Regex("[\\p{L}\\p{N}_]*$")

/** [onPartial] is called when the list is deliberately partial (see dot completion), so the client asks again. */
fun autocompleteAction(ktFile: KtFile, offset: Int, index: Index, onPartial: () -> Unit = {}, smart: Boolean = false): Sequence<CompletionItem> {
    labelCompletion(ktFile, offset)?.let { return it.asSequence() }
    // A type (`val x: Frag|`, `object : FragmentManager.Fragm|`, `@Compo|`): only classes, interfaces and objects.
    typeCompletionTarget(ktFile, offset)?.let { userType ->
        val prefix = WORD_BEFORE_CURSOR.find(ktFile.text.substring(0, offset))?.value.orEmpty()
        return typeCompletion(ktFile, offset, index, userType, prefix, onPartial)
    }
    qualifiedCompletionTarget(ktFile, offset)?.let { (expression, prefix) ->
        return autoCompletionDotExpression(ktFile, offset, index, expression, prefix, onPartial, smart)
    }
    // The word being typed ends at the cursor; the token *at* the cursor can be what follows it (`Ro|@Composable`, `Ro|(`).
    var leaf = ktFile.findElementAt(offset - 1)?.takeIf { it.node.elementType == KtTokens.IDENTIFIER && it.textRange.endOffset == offset }
        ?: ktFile.findElementAt(offset)
    if(leaf == null) return emptySequence()
    while(leaf?.text == "" || leaf is PsiWhiteSpace){
        leaf = leaf.prevSibling ?: break
    }

    if(leaf is KtProperty){
        leaf = leaf.children.find { it is KtNameReferenceExpression } ?: leaf
    }

    // The word being completed is the identifier right before the cursor, if any. Taking the leaf's text instead made
    // the prefix "=" or "(" when nothing was typed yet (`val x: T = |`), which matched nothing.
    val prefix = WORD_BEFORE_CURSOR.find(ktFile.text.substring(0, offset))?.value.orEmpty()
    val completingElement = leaf.parentOfType<KtElement>() ?: ktFile
    // The name of a member being declared (`override fun onSa|`, `fun onSa|` in a class): only the inherited members to
    // override, written out in full. Calls of `onSaveInstanceState(…)` were offered there before.
    if ((completingElement is KtNamedFunction || completingElement is KtProperty) &&
        (completingElement as KtNamedDeclaration).nameIdentifier == leaf
    ) {
        if (completingElement.parent !is KtClassBody) return emptySequence()
        return analyze(completingElement) { overrideItems(ktFile, completingElement, prefix, offset) }.asSequence()
    }
    if (completingElement is KtNameReferenceExpression) {
        return if (completingElement.parent is KtQualifiedExpression) {
            autoCompletionDotExpression(ktFile, offset, index, completingElement.parent as KtQualifiedExpression, prefix, onPartial, smart)
        } else {
            autoCompletionGeneric(ktFile, offset, index, completingElement, prefix, onPartial, smart)
        }
    }

    if(completingElement is KtQualifiedExpression){
        return autoCompletionDotExpression(ktFile, offset, index, completingElement, prefix, onPartial, smart)
    }

    if (completingElement is KtFile) {
        if(leaf is KtPackageDirective){
            return autoCompletionPackage(ktFile, offset, index, leaf)
        }
        if(leaf is KtImportDirective){
            return autoCompletionImport(ktFile, offset, index, leaf)
        }
        return autoCompletionGeneric(ktFile, offset, index, ktFile, prefix, onPartial, smart)
    }

    if (completingElement is KtImportList){
        return autoCompletionImport(ktFile, offset, index, leaf)
    }

    if (completingElement is KtValueArgumentList) {
        // `call(|)` before anything is typed: what's in scope, ranked by the parameter's type.
        return autoCompletionGeneric(ktFile, offset, index, completingElement, prefix, onPartial, smart)
    }

    return autoCompletionGeneric(ktFile, offset, index, completingElement, prefix, onPartial, smart)
}

/** The type being written at the cursor: after `Outer.` or in its name. */
private fun typeCompletionTarget(ktFile: KtFile, offset: Int): KtUserType? {
    if (offset == 0) return null
    val token = ktFile.findElementAt(offset - 1) ?: return null
    return when (token.node.elementType) {
        KtTokens.DOT -> token.parent as? KtUserType
        KtTokens.IDENTIFIER -> ((token.parent as? KtNameReferenceExpression)?.parent as? KtUserType)
            ?.takeIf { token.textRange.endOffset == offset }
        else -> null
    }
}

/**
 * The qualified expression being completed when the cursor is right after `receiver.` or `receiver?.`, or inside the
 * name after it, with the part of the name before the cursor. Looking at the token before the cursor also works when
 * the dot ends a line, where the token at the cursor is the whitespace of the next statement.
 */
private fun qualifiedCompletionTarget(ktFile: KtFile, offset: Int): Pair<KtQualifiedExpression, String>? {
    if (offset == 0) return null
    val token = ktFile.findElementAt(offset - 1) ?: return null
    val type = token.node.elementType
    if (type == KtTokens.DOT || type == KtTokens.SAFE_ACCESS) {
        val qualified = token.parent as? KtQualifiedExpression ?: return null
        return qualified to ""
    }
    if (type == KtTokens.IDENTIFIER) {
        val name = token.parent as? KtNameReferenceExpression ?: return null
        val qualified = name.parent as? KtQualifiedExpression ?: return null
        if (qualified.selectorExpression != name) return null
        return qualified to token.text.substring(0, offset - token.textRange.startOffset)
    }
    return null
}
