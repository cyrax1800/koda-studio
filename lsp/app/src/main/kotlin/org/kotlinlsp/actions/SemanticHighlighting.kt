/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.kotlinlsp.common.toLspRange

data class SemanticToken(
    val deltaLine: Int,
    val deltaStart: Int,
    val length: Int,
    val tokenType: Int,
    val tokenModifiers: Int
)

// Standard LSP semantic token types
enum class TokenType(val value: Int) {
    NAMESPACE(0), CLASS(1), ENUM(2), INTERFACE(3), STRUCT(4), TYPE_PARAMETER(5), TYPE(6),
    PARAMETER(7), VARIABLE(8), PROPERTY(9), ENUM_MEMBER(10), DECORATOR(11), EVENT(12),
    FUNCTION(13), METHOD(14), MACRO(15), LABEL(16), COMMENT(17), STRING(18), KEYWORD(19),
    NUMBER(20), REGEXP(21), OPERATOR(22)
}

// Standard LSP semantic token modifiers
enum class TokenModifier(val value: Int) {
    DECLARATION(0), DEFINITION(1), READONLY(2), STATIC(3), DEPRECATED(4), ABSTRACT(5),
    ASYNC(6), MODIFICATION(7), DOCUMENTATION(8), DEFAULT_LIBRARY(9)
}

/**
 * [resolve] false: references are classified by name and position instead of by analysis, for a library's source that
 * go to definition opened (`Row.kt`), which belongs to no module. Without this it got no highlighting beyond keywords.
 */
fun semanticHighlightingAction(ktFile: KtFile, resolve: Boolean = true): SemanticTokens {
    val tokens = mutableListOf<SemanticToken>()
    
    // Collect all semantic tokens from the file
    collectSemanticTokens(ktFile, tokens, resolve)
    
    // Convert to LSP format (delta encoding)
    val data = mutableListOf<Int>()
    var lastLine = 0
    var lastChar = 0
    
    for (token in tokens.sortedWith(compareBy({ it.deltaLine }, { it.deltaStart }))) {
        data.add(token.deltaLine - lastLine)
        if (token.deltaLine == lastLine) {
            data.add(token.deltaStart - lastChar)
        } else {
            data.add(token.deltaStart)
            lastChar = 0
        }
        data.add(token.length)
        data.add(token.tokenType)
        data.add(token.tokenModifiers)
        
        lastLine = token.deltaLine
        lastChar = token.deltaStart
    }
    
    return SemanticTokens(data)
}

fun semanticHighlightingRangeAction(ktFile: KtFile, range: Range, resolve: Boolean = true): SemanticTokens {
    val tokens = mutableListOf<SemanticToken>()
    
    // Convert LSP range to offset range
    val startOffset = range.start.line * 1000 + range.start.character // Simplified conversion
    val endOffset = range.end.line * 1000 + range.end.character
    
    // Collect semantic tokens only in the specified range
    collectSemanticTokensInRange(ktFile, tokens, startOffset, endOffset, resolve)
    
    // Convert to LSP format (delta encoding)
    val data = mutableListOf<Int>()
    var lastLine = range.start.line
    var lastChar = range.start.character
    
    for (token in tokens.sortedWith(compareBy({ it.deltaLine }, { it.deltaStart }))) {
        data.add(token.deltaLine - lastLine)
        if (token.deltaLine == lastLine) {
            data.add(token.deltaStart - lastChar)
        } else {
            data.add(token.deltaStart)
            lastChar = 0
        }
        data.add(token.length)
        data.add(token.tokenType)
        data.add(token.tokenModifiers)
        
        lastLine = token.deltaLine
        lastChar = token.deltaStart
    }
    
    return SemanticTokens(data)
}

private fun collectSemanticTokens(ktFile: KtFile, tokens: MutableList<SemanticToken>, resolve: Boolean = true) {
    val declared = if (resolve) emptyMap() else declaredKinds(ktFile)
    // Traverse all elements in the file
    PsiTreeUtil.processElements(ktFile) { element ->
        when (element) {
            is KtClass -> addClassToken(element, tokens)
            is KtFunction -> addFunctionToken(element, tokens)
            is KtProperty -> addPropertyToken(element, tokens)
            is KtParameter -> addParameterToken(element, tokens)
            is KtTypeReference -> addTypeToken(element, tokens)
            is KtNameReferenceExpression ->
                if (resolve) addReferenceToken(element, tokens, ktFile) else addSyntacticReferenceToken(element, tokens, declared)
            // Add more element types as needed
        }
        true
    }
}

private fun collectSemanticTokensInRange(
    ktFile: KtFile, 
    tokens: MutableList<SemanticToken>, 
    startOffset: Int, 
    endOffset: Int,
    resolve: Boolean = true,
) {
    // For simplicity, collect all tokens and filter by range
    // In a real implementation, you'd want to be more efficient
    collectSemanticTokens(ktFile, tokens, resolve)
    tokens.removeAll { token ->
        val tokenStart = token.deltaLine * 1000 + token.deltaStart
        tokenStart < startOffset || tokenStart > endOffset
    }
}

private fun addClassToken(element: KtClass, tokens: MutableList<SemanticToken>) {
    val nameIdentifier = element.nameIdentifier ?: return
    val range = nameIdentifier.textRange.toLspRange(element.containingFile)
    
    val tokenType = when {
        element.isInterface() -> TokenType.INTERFACE
        element.isEnum() -> TokenType.ENUM
        else -> TokenType.CLASS
    }
    
    var modifiers = 0
    if (element.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
        modifiers = modifiers or (1 shl TokenModifier.ABSTRACT.value)
    }
    modifiers = modifiers or (1 shl TokenModifier.DECLARATION.value)
    
    tokens.add(SemanticToken(
        deltaLine = range.start.line,
        deltaStart = range.start.character,
        length = nameIdentifier.textLength,
        tokenType = tokenType.value,
        tokenModifiers = modifiers
    ))
}

private fun addFunctionToken(element: KtFunction, tokens: MutableList<SemanticToken>) {
    val nameIdentifier = element.nameIdentifier ?: return
    val range = nameIdentifier.textRange.toLspRange(element.containingFile)
    
    val tokenType = if (element.parent is KtFile) TokenType.FUNCTION else TokenType.METHOD
    
    var modifiers = 0
    modifiers = modifiers or (1 shl TokenModifier.DECLARATION.value)
    if (element.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
        modifiers = modifiers or (1 shl TokenModifier.ABSTRACT.value)
    }
    
    tokens.add(SemanticToken(
        deltaLine = range.start.line,
        deltaStart = range.start.character,
        length = nameIdentifier.textLength,
        tokenType = tokenType.value,
        tokenModifiers = modifiers
    ))
}

private fun addPropertyToken(element: KtProperty, tokens: MutableList<SemanticToken>) {
    val nameIdentifier = element.nameIdentifier ?: return
    val range = nameIdentifier.textRange.toLspRange(element.containingFile)
    
    val tokenType = if (element.parent is KtFile) TokenType.VARIABLE else TokenType.PROPERTY
    
    var modifiers = 0
    modifiers = modifiers or (1 shl TokenModifier.DECLARATION.value)
    if (!element.isVar) {
        modifiers = modifiers or (1 shl TokenModifier.READONLY.value)
    }
    
    tokens.add(SemanticToken(
        deltaLine = range.start.line,
        deltaStart = range.start.character,
        length = nameIdentifier.textLength,
        tokenType = tokenType.value,
        tokenModifiers = modifiers
    ))
}

private fun addParameterToken(element: KtParameter, tokens: MutableList<SemanticToken>) {
    val nameIdentifier = element.nameIdentifier ?: return
    val range = nameIdentifier.textRange.toLspRange(element.containingFile)
    
    val tokenType = if (element.hasValOrVar()) TokenType.PROPERTY else TokenType.PARAMETER
    
    var modifiers = 0
    modifiers = modifiers or (1 shl TokenModifier.DECLARATION.value)
    if (element.hasValOrVar() && !element.isMutable) {
        modifiers = modifiers or (1 shl TokenModifier.READONLY.value)
    }
    
    tokens.add(SemanticToken(
        deltaLine = range.start.line,
        deltaStart = range.start.character,
        length = nameIdentifier.textLength,
        tokenType = tokenType.value,
        tokenModifiers = modifiers
    ))
}

private fun addTypeToken(element: KtTypeReference, tokens: MutableList<SemanticToken>) {
    val typeElement = element.typeElement
    if (typeElement is KtUserType) {
        val referenceExpression = typeElement.referenceExpression ?: return
        val range = referenceExpression.textRange.toLspRange(element.containingFile)
        
        tokens.add(SemanticToken(
            deltaLine = range.start.line,
            deltaStart = range.start.character,
            length = referenceExpression.textLength,
            tokenType = TokenType.TYPE.value,
            tokenModifiers = 0
        ))
    }
}

private fun addReferenceToken(element: KtNameReferenceExpression, tokens: MutableList<SemanticToken>, ktFile: KtFile) {
    val range = element.textRange.toLspRange(element.containingFile)
    
    analyze(ktFile) {
        val symbol = element.mainReference?.resolveToSymbol()
        
        val (tokenType, modifiers) = when (symbol) {
            is KaClassLikeSymbol -> {
                val type = when (symbol) {
                    is KaClassSymbol -> when (symbol.classKind) {
                        KaClassKind.CLASS -> TokenType.CLASS
                        KaClassKind.INTERFACE -> TokenType.INTERFACE
                        KaClassKind.ENUM_CLASS -> TokenType.ENUM
                        KaClassKind.OBJECT -> TokenType.CLASS
                        KaClassKind.ANNOTATION_CLASS -> TokenType.CLASS
                        else -> TokenType.CLASS
                    }
                    is KaTypeAliasSymbol -> TokenType.TYPE
                    else -> TokenType.TYPE
                }
                type to 0
            }
            is KaFunctionSymbol -> {
                // Check if it's a top-level function by checking the containing symbol
                val type = if (symbol.containingSymbol == null) {
                    TokenType.FUNCTION
                } else {
                    TokenType.METHOD
                }
                type to 0
            }
            is KaPropertySymbol -> {
                val mods = if (!symbol.isVal) 0 
                          else (1 shl TokenModifier.READONLY.value)
                TokenType.PROPERTY to mods
            }
            is KaLocalVariableSymbol -> {
                TokenType.VARIABLE to 0
            }
            is KaValueParameterSymbol -> {
                TokenType.PARAMETER to 0
            }
            else -> return@analyze // Skip unsupported symbol types
        }
        
        tokens.add(SemanticToken(
            deltaLine = range.start.line,
            deltaStart = range.start.character,
            length = element.textLength,
            tokenType = tokenType.value,
            tokenModifiers = modifiers
        ))
    }
} 
/** What each name declared in [ktFile] is, for highlighting its uses without analysis. */
private fun declaredKinds(ktFile: KtFile): Map<String, TokenType> {
    val kinds = HashMap<String, TokenType>()
    PsiTreeUtil.processElements(ktFile) { element ->
        val kind = when (element) {
            is KtClass -> when {
                element.isInterface() -> TokenType.INTERFACE
                element.isEnum() -> TokenType.ENUM
                else -> TokenType.CLASS
            }
            is KtObjectDeclaration -> TokenType.CLASS
            is KtTypeAlias -> TokenType.TYPE
            is KtNamedFunction -> if (element.parent is KtFile) TokenType.FUNCTION else TokenType.METHOD
            is KtProperty -> if (element.isLocal) TokenType.VARIABLE else TokenType.PROPERTY
            is KtParameter -> if (element.hasValOrVar()) TokenType.PROPERTY else TokenType.PARAMETER
            else -> null
        }
        if (kind != null) (element as KtNamedDeclaration).name?.let { kinds.putIfAbsent(it, kind) }
        true
    }
    return kinds
}

/**
 * A reference highlighted by its name and position: a call is a function (a class only if this file declares one by
 * that name; composables are capitalized functions), a name declared in this file is what it declares, a capitalized
 * name is a class, a name after a dot a property. Names in types are highlighted as types already.
 */
private fun addSyntacticReferenceToken(element: KtNameReferenceExpression, tokens: MutableList<SemanticToken>, declared: Map<String, TokenType>) {
    if (element.parent is KtUserType) return
    val name = element.getReferencedName()
    // In `import`s and the `package` line only classes are colored, as with analysis; package names stay plain.
    if (PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java, KtPackageDirective::class.java) != null) {
        if (name.firstOrNull()?.isUpperCase() != true) return
        val range = element.textRange.toLspRange(element.containingFile)
        tokens.add(SemanticToken(range.start.line, range.start.character, element.textLength, TokenType.CLASS.value, 0))
        return
    }
    val isCallee = (element.parent as? KtCallExpression)?.calleeExpression == element
    val ownKind = declared[name]
    val type = when {
        isCallee -> if (ownKind == TokenType.CLASS || ownKind == TokenType.ENUM) ownKind else if (ownKind == TokenType.METHOD) TokenType.METHOD else TokenType.FUNCTION
        ownKind != null -> ownKind
        name.firstOrNull()?.isUpperCase() == true -> TokenType.CLASS
        (element.parent as? KtQualifiedExpression)?.selectorExpression == element -> TokenType.PROPERTY
        else -> return
    }
    val range = element.textRange.toLspRange(element.containingFile)
    tokens.add(SemanticToken(range.start.line, range.start.character, element.textLength, type.value, 0))
}
