/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Modified 2026 by Koda Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.declarations.*
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.kotlinlsp.common.profile
import org.kotlinlsp.common.read
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.filesForPackage

class DeclarationProvider(
    val scope: GlobalSearchScope,
    private val project: Project,
    private val index: Index
): KotlinDeclarationProvider {
    private val KtElement.inScope: Boolean
        get() = containingKtFile.virtualFile in scope

    override val hasSpecificCallablePackageNamesComputation: Boolean
        get() = false
    override val hasSpecificClassifierPackageNamesComputation: Boolean
        get() = false

    override fun findFilesForFacade(facadeFqName: FqName): Collection<KtFile> =
        profile("findFilesForFacade") {
            if (facadeFqName.shortNameOrSpecial().isSpecial) return@profile emptyList()
            // According to standalone platform, this does not work with classes with @JvmPackageName
            findFilesForFacadeByPackage(facadeFqName.parent())
                .filter { it.javaFileFacadeFqName == facadeFqName }
        }

    override fun findInternalFilesForFacade(facadeFqName: FqName): Collection<KtFile> =
        profile("findInternalFilesForFacade") {
            // We don't deserialize libraries from stubs so we can return empty here safely
            // We don't take the KaBuiltinsModule into account for simplicity,
            // that means we expect the kotlin stdlib to be included on the project
            emptyList()
        }

    override fun findFilesForFacadeByPackage(packageFqName: FqName): Collection<KtFile> =
        profile("findFilesForFacadeByPackage") {
            ktFilesForPackage(packageFqName).toList()
        }

    override fun findFilesForScript(scriptFqName: FqName): Collection<KtScript> =
        profile("findFilesForScript") {
            ktFilesForPackage(scriptFqName).mapNotNull { it.script }.toList()
        }

    override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> =
        profile("getAllClassesByClassId") {
            declarationsOf(classId.packageFqName).classes[classId].orEmpty()
        }

    override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> =
        profile("getAllTypeAliasesByClassId") {
            declarationsOf(classId.packageFqName).typeAliases[classId].orEmpty()
        }

    override fun getClassLikeDeclarationByClassId(classId: ClassId): KtClassLikeDeclaration? =
        profile("getClassLikeDeclarationByClassId") {
            getAllClassesByClassId(classId).firstOrNull()
                ?: getAllTypeAliasesByClassId(classId).firstOrNull()
        }

    override fun getTopLevelCallableFiles(callableId: CallableId): Collection<KtFile> =
        profile("getTopLevelCallableFiles") {
            buildSet {
                getTopLevelProperties(callableId).mapTo(this) { it.containingKtFile }
                getTopLevelFunctions(callableId).mapTo(this) { it.containingKtFile }
            }
        }

    override fun getTopLevelFunctions(callableId: CallableId): Collection<KtNamedFunction> =
        profile("getTopLevelFunctions") {
            declarationsOf(callableId.packageName).functions[callableId.callableName].orEmpty()
        }

    override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> =
        profile("getTopLevelKotlinClassLikeDeclarationNamesInPackage") {
            declarationsOf(packageFqName).classLikeNames
        }

    override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> =
        profile("getTopLevelCallableNamesInPackage") {
            declarationsOf(packageFqName).callableNames
        }

    override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> =
        profile("getTopLevelProperties") {
            declarationsOf(callableId.packageName).properties[callableId.callableName].orEmpty()
        }

    /**
     * A package's declarations in [scope], read from its files once. Every query used to walk the whole PSI of every
     * file in the package; on a large project that was most of a completion's time. A provider lives as long as its
     * analysis session, which every edit replaces, so this can't go stale.
     */
    private class PackageDeclarations(
        val classes: Map<ClassId, List<KtClassOrObject>>,
        val typeAliases: Map<ClassId, List<KtTypeAlias>>,
        val functions: Map<Name, List<KtNamedFunction>>,
        val properties: Map<Name, List<KtProperty>>,
        val classLikeNames: Set<Name>,
        val callableNames: Set<Name>,
    )

    private val packages = java.util.concurrent.ConcurrentHashMap<FqName, PackageDeclarations>()

    private fun declarationsOf(packageFqName: FqName): PackageDeclarations = packages.computeIfAbsent(packageFqName) {
        val classes = HashMap<ClassId, MutableList<KtClassOrObject>>()
        val typeAliases = HashMap<ClassId, MutableList<KtTypeAlias>>()
        val functions = HashMap<Name, MutableList<KtNamedFunction>>()
        val properties = HashMap<Name, MutableList<KtProperty>>()
        val classLikeNames = HashSet<Name>()
        val callableNames = HashSet<Name>()
        for (file in ktFilesForPackage(packageFqName)) {
            val declarations = project.read { fileDeclarations(file) }
            for ((id, declaration) in declarations.classes) classes.getOrPut(id) { mutableListOf() }.add(declaration)
            for ((id, declaration) in declarations.typeAliases) typeAliases.getOrPut(id) { mutableListOf() }.add(declaration)
            for ((name, declaration) in declarations.functions) functions.getOrPut(name) { mutableListOf() }.add(declaration)
            for ((name, declaration) in declarations.properties) properties.getOrPut(name) { mutableListOf() }.add(declaration)
            classLikeNames += declarations.classLikeNames
            callableNames += declarations.callableNames
        }
        PackageDeclarations(classes, typeAliases, functions, properties, classLikeNames, callableNames)
    }

    private fun ktFilesForPackage(fqName: FqName): Sequence<KtFile> {
        return index.filesForPackage(fqName)
            .asSequence()
            .mapNotNull { VirtualFileManager.getInstance().findFileByUrl(it) }
            .filter { it in scope }
            .mapNotNull { index.getKtFile(it) }
    }
}

class DeclarationProviderFactory: KotlinDeclarationProviderFactory {
    private lateinit var project: Project
    private lateinit var index: Index

    fun setup(project: Project, index: Index) {
        this.project = project
        this.index = index
    }

    override fun createDeclarationProvider(
        scope: GlobalSearchScope,
        contextualModule: KaModule?
    ): KotlinDeclarationProvider {
        return DeclarationProvider(scope, project, index)
    }
}

class DeclarationProviderMerger(private val project: Project) : KotlinDeclarationProviderMerger {
    override fun merge(providers: List<KotlinDeclarationProvider>): KotlinDeclarationProvider =
        providers.mergeSpecificProviders<_, DeclarationProvider>(KotlinCompositeDeclarationProvider.factory) { targetProviders ->
            val combinedScope = GlobalSearchScope.union(targetProviders.map { it.scope })
            project.createDeclarationProvider(combinedScope, contextualModule = null).apply {
                check(this is DeclarationProvider) {
                    "`DeclarationProvider` can only be merged into a combined declaration provider of the same type."
                }
            }
        }
}

/** One file's declarations that a declaration provider serves (see [fileDeclarations]). */
private class FileDeclarations(
    val stamp: Long,
    val classes: List<Pair<ClassId, KtClassOrObject>>,
    val typeAliases: List<Pair<ClassId, KtTypeAlias>>,
    val functions: List<Pair<Name, KtNamedFunction>>,
    val properties: List<Pair<Name, KtProperty>>,
    val classLikeNames: Set<Name>,
    val callableNames: Set<Name>,
)

private val FILE_DECLARATIONS = com.intellij.openapi.util.Key.create<FileDeclarations>("org.kotlinlsp.fileDeclarations")

/**
 * [file]'s top-level and member declarations, read once per version of the file and kept on it. Every module's
 * session has its own declaration provider, and each read the files of a package again: 40,000 lookups took 40 s
 * while usages were searched across many modules.
 */
private fun fileDeclarations(file: KtFile): FileDeclarations {
    val stamp = file.modificationStamp
    file.getUserData(FILE_DECLARATIONS)?.takeIf { it.stamp == stamp }?.let { return it }
    val classes = mutableListOf<Pair<ClassId, KtClassOrObject>>()
    val typeAliases = mutableListOf<Pair<ClassId, KtTypeAlias>>()
    val functions = mutableListOf<Pair<Name, KtNamedFunction>>()
    val properties = mutableListOf<Pair<Name, KtProperty>>()
    val classLikeNames = HashSet<Name>()
    val callableNames = HashSet<Name>()
    val pending = ArrayDeque<KtDeclaration>(file.declarations)
    while (pending.isNotEmpty()) {
        val declaration = pending.removeFirst()
        if (declaration is KtClassOrObject) pending.addAll(declaration.declarations)
        when (declaration) {
            is KtClassOrObject -> declaration.getClassId()?.let { classes += it to declaration }
            is KtTypeAlias -> declaration.getClassId()?.let { typeAliases += it to declaration }
            is KtNamedFunction -> if (declaration.isTopLevel) declaration.nameAsName?.let { functions += it to declaration }
            is KtProperty -> if (declaration.isTopLevel) declaration.nameAsName?.let { properties += it to declaration }
        }
        if (declaration is KtClassLikeDeclaration && declaration.isTopLevelKtOrJavaMember()) declaration.nameAsName?.let(classLikeNames::add)
        if (declaration is KtCallableDeclaration && declaration.isTopLevelKtOrJavaMember()) declaration.nameAsName?.let(callableNames::add)
    }
    return FileDeclarations(stamp, classes, typeAliases, functions, properties, classLikeNames, callableNames)
        .also { file.putUserData(FILE_DECLARATIONS, it) }
}
