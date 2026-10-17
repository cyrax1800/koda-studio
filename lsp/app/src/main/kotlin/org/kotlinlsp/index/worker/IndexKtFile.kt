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
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isAbstract
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.kotlin.psi.psiUtil.isProtected
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.kotlinlsp.common.read
import org.kotlinlsp.common.warn
import org.kotlinlsp.index.db.*

fun indexKtFile(project: Project, ktFile: KtFile, db: Database) {
    val newFile = File.fromKtFile(ktFile, project, indexed = true)

    // Check if skipping the indexing of that file
    val existingFile = db.file(newFile.path)
    if (
        File.shouldBeSkipped(existingFile = existingFile, newFile = newFile) &&
        existingFile?.indexed == true  // Already indexed
    ) return

    // Remove declarations for this file first
    existingFile?.declarationKeys?.let {
        db.declarationsDb.remove(it)
    }

    // Get declarations metadata, from the syntax alone. Resolving types here (to record return types) analysed every
    // changed file at startup, and the file being edited while the user typed, where it raced with the edits and left
    // the compiler with a stale view of the file ("Classifier was found in KtFile but was not found in FirFile").
    val declarations = project.read {
        val list = mutableListOf<Declaration>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitDeclaration(dcl: KtDeclaration) {
                val record = declarationRecord(newFile.path, dcl) ?: return
                list.add(record)
                super.visitDeclaration(dcl)
            }
        })
        return@read list
    }

    // Save declarations
    db.putDeclarations(declarations)

    // Update the file timestamp, package and declaration names
    newFile.declarationKeys.addAll(declarations.map { it.id() })
    db.setFile(newFile)
}

/** The type as written, for completion's labels; an inferred type is left out, except a block body's `Unit`. */
private fun KtCallableDeclaration.writtenType(): String = typeReference?.text
    ?: if (this is KtNamedFunction && hasBlockBody()) "Unit" else ""

private fun declarationRecord(path: String, dcl: KtDeclaration): Declaration? {
    val name = dcl.name ?: return null
    val startOffset = dcl.textOffset
    val endOffset = dcl.textOffset + name.length

    return when (dcl) {
        is KtNamedFunction -> {
            if (dcl.isLocal) return null
            val parentFqName = if (dcl.parent is KtClassBody) {
                (dcl.parent.parent as? KtClassOrObject)?.fqName?.asString() ?: ""
            } else ""

            Declaration.Function(
                name,
                dcl.fqName?.asString() ?: "",
                dcl.isExtensionDeclaration(),
                dcl.isTopLevel,
                dcl.isPrivate() || dcl.isProtected(),
                path,
                startOffset,
                endOffset,
                dcl.valueParameters.map {
                    Declaration.Function.Parameter(
                        it.nameAsSafeName.asString(),
                        it.writtenType(),
                        it.hasDefaultValue() || it.isVarArg,
                    )
                },
                dcl.writtenType(),
                parentFqName,
                dcl.receiverTypeReference?.getShortTypeText() ?: ""
            )
        }

        is KtClass -> {
            if (dcl is KtEnumEntry) {
                return Declaration.EnumEntry(
                    name,
                    dcl.fqName?.asString() ?: "",
                    path,
                    startOffset,
                    endOffset,
                    dcl.parentOfType<KtClass>()?.fqName?.asString() ?: ""
                )
            }

            val type = if (dcl.isEnum()) {
                Declaration.Class.Type.ENUM_CLASS
            } else if (dcl.isAnnotation()) {
                Declaration.Class.Type.ANNOTATION_CLASS
            } else if (dcl.isInterface()) {
                Declaration.Class.Type.INTERFACE
            } else if (dcl.isAbstract()) {
                Declaration.Class.Type.ABSTRACT_CLASS
            } else {
                Declaration.Class.Type.CLASS
            }

            Declaration.Class(
                name,
                dcl.isTopLevelKtOrJavaMember(),
                dcl.isPrivate() || dcl.isProtected(),
                type,
                dcl.fqName?.asString() ?: "",
                path,
                startOffset,
                endOffset
            )
        }

        is KtParameter -> {
            if (!dcl.hasValOrVar()) return null
            val constructor = dcl.parentOfType<KtPrimaryConstructor>() ?: return null
            val clazz = constructor.parentOfType<KtClass>() ?: return null

            Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                dcl.isExtensionDeclaration(),
                dcl.isTopLevelKtOrJavaMember(),
                path,
                startOffset,
                endOffset,
                dcl.writtenType(),
                clazz.fqName?.asString() ?: "",
            )
        }

        is KtProperty -> {
            if (dcl.isLocal) return null
            val clazz = dcl.parentOfType<KtClass>() ?: return Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                dcl.isExtensionDeclaration(),
                dcl.isTopLevelKtOrJavaMember(),
                path,
                startOffset,
                endOffset,
                dcl.writtenType(),
                "",
                receiverFqName = dcl.receiverTypeReference?.getShortTypeText() ?: "",
                isPrivate = dcl.isPrivate(),
            )

            Declaration.Field(
                name,
                dcl.fqName?.asString() ?: "",
                dcl.isExtensionDeclaration(),
                dcl.isTopLevelKtOrJavaMember(),
                path,
                startOffset,
                endOffset,
                dcl.writtenType(),
                clazz.fqName?.asString() ?: "",
                receiverFqName = dcl.receiverTypeReference?.getShortTypeText() ?: "",
                isPrivate = dcl.isPrivate() || dcl.isProtected(),
            )
        }

        else -> {
            null
        }
    }
}
