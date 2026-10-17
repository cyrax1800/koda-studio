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

package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.isAbstract
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.psi.psiUtil.isProtected
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.jetbrains.kotlin.psi.stubs.impl.KotlinClassStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinFunctionStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinParameterStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinPropertyStubImpl
import com.intellij.psi.impl.cache.ModifierFlags
import com.intellij.psi.impl.java.stubs.JavaStubElementTypes
import com.intellij.psi.impl.java.stubs.PsiModifierListStub
import com.intellij.psi.impl.java.stubs.PsiClassStub
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.kotlinlsp.analysis.services.JavaStubBuilder
import org.kotlinlsp.analysis.services.KotlinStubBuilder
import org.kotlinlsp.index.db.*
import java.net.URI
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.appendText

fun indexClassFile(project: Project, virtualFile: VirtualFile, db: Database) {
    // Only process .class files
    if (virtualFile.isDirectory || virtualFile.extension != "class") return
    
    // CRITICAL: Use virtualFile.url consistently (not .path)
    val existingFile = db.file(virtualFile.url)
    val newFile = File(
        path = virtualFile.url,
        packageFqName = existingFile?.packageFqName ?: "",
        lastModified = Instant.ofEpochMilli(virtualFile.timeStamp),
        modificationStamp = 0L,
        indexed = true,
        declarationKeys = mutableListOf()
    )

    if (
        File.shouldBeSkipped(existingFile = existingFile, newFile = newFile) &&
        existingFile?.indexed == true
    ) return
    
    // PERFORMANCE: Fast path - extract FQN from path first
    val internalPath = virtualFile.url
        .substringAfter("!/")
        .removePrefix("file://")
        .removeSuffix(".class")
        .trimStart('/')
    
    // Skip inner/anonymous classes
    if (internalPath.contains("$")) return
    
    val fqName = internalPath.replace('/', '.').replace('\\', '.')
    if (fqName.isBlank()) return
    
    val simpleName = fqName.substringAfterLast('.')
    
    val declarations = mutableListOf<Declaration>()

    // Full stub-based indexing for important API classes
    val kotlinStubBuilder = KotlinStubBuilder.instance(project)
    val javaStubBuilder = JavaStubBuilder.instance(project)
    val binaryClassCache = ClsKotlinBinaryClassCache.getInstance()

    val stub = kotlinStubBuilder.createStub(virtualFile, binaryClassCache) ?: javaStubBuilder.createStub(virtualFile, binaryClassCache)

    if (stub != null) {
        val children = stub.childrenStubs
        children.forEach { childStub ->
            when(childStub){
                // A compiled Java class (android.jar, Java libraries): its name, so it can be completed and imported
                // (`Log`, `Context`, `Intent`). Members come from analysis once the class resolves.
                is PsiClassStub<*> -> {
                    // From the stub alone: building PSI here (`childStub.psi`) gave the class file a PSI analysis could
                    // later pick up.
                    val modifiers = childStub.findChildStubByElementType(JavaStubElementTypes.MODIFIER_LIST) as? PsiModifierListStub
                    val mask = modifiers?.modifiersMask ?: 0
                    if (mask and ModifierFlags.PUBLIC_MASK == 0) return@forEach
                    val name = childStub.name ?: return@forEach
                    val type = when {
                        childStub.isAnnotationType -> Declaration.Class.Type.ANNOTATION_CLASS
                        childStub.isEnum -> Declaration.Class.Type.ENUM_CLASS
                        childStub.isInterface -> Declaration.Class.Type.INTERFACE
                        mask and ModifierFlags.ABSTRACT_MASK != 0 -> Declaration.Class.Type.ABSTRACT_CLASS
                        else -> Declaration.Class.Type.CLASS
                    }
                    declarations.add(
                        Declaration.Class(
                            name = name,
                            isTopLevel = true,
                            isPrivate = false,
                            type = type,
                            fqName = childStub.qualifiedName ?: return@forEach,
                            file = virtualFile.url,
                            startOffset = 0,
                            endOffset = name.length,
                        )
                    )
                }
                is KotlinFunctionStubImpl -> {
                    declarations.add(
                        Declaration.Function(
                            childStub.name ?: "",
                            childStub.fqName.toString(),
                            childStub.isExtension,
                            childStub.isTopLevel,
                            childStub.psi.isHidden(),
                            virtualFile.url,
                            0,
                            0 + (childStub.name?.length ?: 0),
                            childStub.psi.valueParameters.map {
                                Declaration.Function.Parameter(
                                    it.nameAsSafeName.asString(),
                                    it.typeReference?.getShortTypeText() ?: "",
                                    it.hasDefaultValue() || it.isVarArg,
                                )
                            },
                            childStub.psi.typeReference?.getShortTypeText() ?: "",
                            "",
                            childStub.psi.receiverTypeReference?.getShortTypeText() ?: ""
                        )
                    )
                }
                is KotlinClassStubImpl -> {
                    if (childStub.psi is KtEnumEntry){
                        declarations.add(Declaration.EnumEntry(
                            childStub.name ?: "",
                            childStub.fqName.toString(),
                            virtualFile.url,
                            0,
                            0 + (childStub.name?.length ?: 0),
                            childStub.parentStub.psi.parentOfType<KtClass>()?.fqName?.asString() ?: ""
                        ))
                        return@forEach
                    }

                    val type = if (childStub.psi.isEnum()) {
                        Declaration.Class.Type.ENUM_CLASS
                    } else if (childStub.psi.isAnnotation()) {
                        Declaration.Class.Type.ANNOTATION_CLASS
                    } else if (childStub.psi.isInterface()) {
                        Declaration.Class.Type.INTERFACE
                    } else if (childStub.psi.isAbstract()) {
                        Declaration.Class.Type.ABSTRACT_CLASS
                    } else {
                        Declaration.Class.Type.CLASS
                    }

                    declarations.add(
                        Declaration.Class(
                            childStub.psi.name ?: "",
                            childStub.psi.isTopLevelKtOrJavaMember(),
                            childStub.psi.isHidden(),
                            type,
                            childStub.fqName.toString(),
                            virtualFile.url,
                            0,
                            (childStub.name?.length ?: 0),
                        )
                    )

                }
                is KotlinPropertyStubImpl -> {
                    if (childStub.psi.isLocal) return@forEach
                    val clazz = childStub.psi.parentOfType<KtClass>()
                    
                    declarations.add(
                        Declaration.Field(
                            childStub.psi.name ?: "",
                            childStub.fqName.toString(),
                            childStub.psi.isExtensionDeclaration(),
                            childStub.psi.isTopLevelKtOrJavaMember(),
                            virtualFile.url,
                            0,
                            0 + (childStub.name?.length ?: 0),
                            childStub.psi.typeReference?.getShortTypeText() ?: "",
                            clazz?.fqName?.asString() ?: "",
                            receiverFqName = childStub.psi.receiverTypeReference?.getShortTypeText() ?: "",
                            isPrivate = childStub.psi.isHidden(),
                        )
                    )
                }
                is KotlinParameterStubImpl -> {
                    if (!childStub.psi.hasValOrVar()) return@forEach
                    val constructor = childStub.psi.parentOfType<KtPrimaryConstructor>() ?: return@forEach
                    val clazz = constructor.parentOfType<KtClass>() ?: return@forEach

                    declarations.add(
                        Declaration.Field(
                            childStub.name ?: "",
                            childStub.fqName.toString(),
                            childStub.psi.isExtensionDeclaration(),
                            childStub.psi.isTopLevelKtOrJavaMember(),
                            virtualFile.url,
                            0,
                            0 + (childStub.name?.length ?: 0),
                            childStub.psi.typeReference?.getShortTypeText() ?: "",
                            clazz.fqName?.asString() ?: ""
                        )
                    )
                }
            }
        }
    } else {
        // Stub creation failed - add lightweight class declaration
        declarations.add(Declaration.Class(
            name = simpleName,
            isTopLevel = true,
            isPrivate = false,
            type = Declaration.Class.Type.CLASS,
            fqName = fqName,
            file = virtualFile.url,
            startOffset = -1,
            endOffset = -1
        ))
    }

    db.putDeclarations(declarations)

    val fileRecord = File(
        path = virtualFile.url,
        packageFqName = fqName.substringBeforeLast('.', ""),
        lastModified = Instant.ofEpochMilli(virtualFile.timeStamp),
        modificationStamp = 0L,
        indexed = true,
        declarationKeys = declarations.map { it.id() }.toMutableList()
    )
    db.setFile(fileRecord)
}

/** Not usable from the project: private, protected, or `internal` to the library it was compiled in. */
private fun KtModifierListOwner.isHidden(): Boolean =
    hasModifier(KtTokens.PRIVATE_KEYWORD) || hasModifier(KtTokens.PROTECTED_KEYWORD) || hasModifier(KtTokens.INTERNAL_KEYWORD)
