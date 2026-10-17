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

package org.kotlinlsp.common

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.sourcesForPackage

/**
 * The declaration [ref] resolves to, in the library's sources (`-sources.jar` files of the package). Matched by name
 * and by the class it belongs to: by name alone, `liveData.value` went to `OnLifecycleEvent.value()`, the first
 * `value` in `androidx.lifecycle`. A Java getter used as a Kotlin property (`value`) is looked up as `getValue`.
 */
public fun KaSession.findSourceSymbols(ktFile: KtFile, ref: KtReference, index: Index): List<PsiElement> {
    val symbol = ref.resolveToSymbols().firstOrNull()
    val packageName: String
    val names: Set<String>
    val owner: String?
    val kinds: List<Class<out PsiElement>>

    when (symbol) {
        // `object : FragmentManager.FragmentLifecycleCallbacks() { }`: a constructor call opens its class.
        is KaConstructorSymbol -> {
            val classId = symbol.containingClassId ?: return emptyList()
            packageName = classId.packageFqName.asString()
            names = setOf(classId.shortClassName.asString())
            owner = classId.outerClassId?.shortClassName?.asString()
            kinds = listOf(PsiClass::class.java, KtClassOrObject::class.java)
        }
        is KaCallableSymbol -> {
            // `liveData.value` is `getValue()`, declared in LiveData even when used on a MutableLiveData.
            // Inherited members resolve to copies in the receiver's class (`MutableLiveData.observe`); their original
            // declaration is the one in the sources.
            val target = ((symbol as? KaSyntheticJavaPropertySymbol)?.javaGetterSymbol ?: symbol).fakeOverrideOriginal
            val callableId = target.callableId ?: return emptyList()
            packageName = callableId.packageName.asString()
            owner = callableId.className?.shortName()?.asString()
            names = setOf(callableId.callableName.asString())
            kinds = listOf(PsiMethod::class.java, PsiField::class.java, KtNamedFunction::class.java, KtProperty::class.java, KtParameter::class.java)
        }
        is KaClassSymbol -> {
            val classId = symbol.classId ?: return emptyList()
            packageName = classId.packageFqName.asString()
            names = setOf(classId.shortClassName.asString())
            owner = classId.outerClassId?.shortClassName?.asString()
            kinds = listOf(PsiClass::class.java, KtClassOrObject::class.java)
        }
        else -> return emptyList()
    }

    val files = index.sourcesForPackage(FqName(packageName))
        .mapNotNull { VirtualFileManager.getInstance().findFileByUrl(it) }
        .mapNotNull { PsiManager.getInstance(ktFile.project).findFile(it) }
    val candidates = files.flatMap { file -> kinds.flatMap { PsiTreeUtil.collectElementsOfType(file, it) } }
        .filter { (it as? PsiNamedElement)?.name in names }
        // A member of a constructor's `val`s counts only as a property.
        .filter { it !is KtParameter || it.hasValOrVar() }
    // In the right class: the member's class (or a class nesting the class) has the owner's name.
    val inOwner = candidates.filter { candidate -> ownerName(candidate) == owner }
    return inOwner.ifEmpty { if (owner == null) candidates else emptyList() }
}

private fun ownerName(element: PsiElement): String? = when (element) {
    is PsiMember -> element.containingClass?.name
    is KtParameter -> element.parentOfType<KtClassOrObject>()?.name
    else -> element.parentOfType<KtClassOrObject>()?.takeIf { it != element }?.name
}
