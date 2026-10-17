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

package org.kotlinlsp.analysis.services

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.addIfNotNull
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.fir.utils.isSubclassOf
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.cache.LLFirSessionCache
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.contains
import org.jetbrains.kotlin.psi.psiUtil.getImportedSimpleNameByImportAlias
import org.jetbrains.kotlin.psi.psiUtil.getSuperNames
import org.kotlinlsp.analysis.modules.Module
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.common.profile
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.filesForPackage

class DirectInheritorsProvider: KotlinDirectInheritorsProvider {
    private lateinit var index: Index
    private lateinit var modules: List<Module>
    private lateinit var project: Project

    /**
     * Per source file: its classes by the simple names of their supertypes, and its type aliases by the names they
     * expand to, read when a query first needs the file and kept current by [fileChanged]. A class can only inherit
     * from `X` if its file mentions `X` (or an alias of it), so a query reads only those files, found through the word
     * index. This used to parse every source file of the project on each query, in shared maps that parallel queries
     * corrupted (ConcurrentModificationException): 1–2% of a large project failed to index, and the first sealed
     * `when` took seconds.
     *
     * Only names are kept: holding the classes themselves pinned the syntax tree of every file a query ever read (all
     * files mentioning `ViewModel`, say), and memory grew for as long as the server ran. The few files that do mention
     * a supertype are walked again for their classes.
     */
    private class FileEntries(
        val supertypeNames: Set<Name>,
        /** Names of the file's type aliases by the names they expand to. */
        val aliasNamesByAliasedName: Map<Name, List<Name>>,
    )

    private val entriesByFile = java.util.concurrent.ConcurrentHashMap<String, FileEntries>()

    /** The source files that mention [name], with their entries. */
    private fun entriesMentioning(name: Name): List<Pair<KtFile, FileEntries>> =
        index.sourceFilesMentioning(name.asString()).map { file -> file to entriesFor(file) }

    private fun entriesFor(file: KtFile): FileEntries = entriesByFile.computeIfAbsent(file.virtualFile.url) { entriesOf(file) }

    fun setup(project: Project, index: Index, modules: List<Module>) {
        this.project = project
        this.index = index
        this.modules = modules
    }

    override fun getDirectKotlinInheritors(
        ktClass: KtClass,
        scope: GlobalSearchScope,
        includeLocalInheritors: Boolean
    ): Iterable<KtClassOrObject> = profile("getDirectKotlinInheritors") {
        val classId = ktClass.getClassId() ?: return@profile emptyList()
        val baseModule = KotlinProjectStructureProvider.getModule(project, ktClass, useSiteModule = null)

        // A sealed class's direct subclasses are in its module and package: the package's files are enough, without
        // the word index, which a large project builds for a minute after a start while this (for a `when`) waited.
        val files = if (ktClass.isSealed()) packageFiles(ktClass.containingKtFile) else null
        getDirectKotlinInheritorsByClassId(classId, baseModule, scope, includeLocalInheritors, files)
    }

    @OptIn(SymbolInternals::class)
    fun getDirectKotlinInheritorsByClassId(
        classId: ClassId,
        baseModule: KaModule,
        scope: GlobalSearchScope,
        includeLocalInheritors: Boolean,
        /** The only files that can hold inheritors, if known (a sealed class's package); else found by name. */
        files: List<KtFile>? = null,
    ): Iterable<KtClassOrObject> = profile("getDirectKotlinInheritorsByClassId") {
        val baseFirClass = classId.toFirSymbol(baseModule)?.fir as? FirClass ?: return@profile emptyList()

        val entries: (Name) -> List<Pair<KtFile, FileEntries>> = if (files != null) {
            val inFiles = files.map { file -> file to entriesFor(file) }
            ({ inFiles })
        } else {
            ::entriesMentioning
        }
        val baseClassNames = mutableSetOf(classId.shortClassName)
        calculateAliases(classId.shortClassName, baseClassNames, entries)

        val possibleInheritors = baseClassNames
            .flatMap { name -> entries(name).filter { (_, entries) -> name in entries.supertypeNames } }
            .map { it.first }
            .distinct()
            .flatMap { file -> classesExtending(file, baseClassNames) }
            .distinct()
        if (possibleInheritors.isEmpty()) {
            return@profile emptyList()
        }

        // Handle both regular classes/objects (with ClassId) and anonymous/local objects (no ClassId)
        val (withClassId, withoutClassId) = possibleInheritors.partition { it.getClassId() != null }

        val validatedWithId = withClassId.filter { isValidInheritor(it, baseFirClass, scope, includeLocalInheritors) }

        // For anonymous objects and local objects lacking ClassId, rely on prior super-name matching and scope/local filters
        val validatedAnonymous = withoutClassId.filter { candidate ->
            if (!includeLocalInheritors && candidate.isLocal) return@filter false
            if (!scope.contains(candidate)) return@filter false
            true
        }

        (validatedWithId + validatedAnonymous)
    }

    /** Re-reads one file's classes after it was edited or changed on disk; [ktFile] null means it was deleted. */
    fun fileChanged(url: String, ktFile: KtFile?) {
        // Read again when next needed; a file that didn't mention a name before may now.
        entriesByFile.remove(url)
    }

    private fun entriesOf(ktFile: KtFile): FileEntries {
        val supertypes = HashSet<Name>()
        val aliases = HashMap<Name, MutableList<Name>>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                classOrObject.getSuperNames().forEach { superName -> supertypes += Name.identifier(superName) }
                super.visitClassOrObject(classOrObject)
            }

            override fun visitTypeAlias(typeAlias: KtTypeAlias) {
                val typeElement = typeAlias.getTypeReference()?.typeElement ?: return
                findInheritableSimpleNames(typeElement).forEach { expandedName ->
                    aliases.getOrPut(Name.identifier(expandedName)) { mutableListOf() }.add(typeAlias.nameAsSafeName)
                }
                super.visitTypeAlias(typeAlias)
            }
        })
        return FileEntries(supertypes, aliases)
    }

    /** The classes of [ktFile] that name one of [supertypeNames] as a supertype. */
    private fun classesExtending(ktFile: KtFile, supertypeNames: Set<Name>): List<KtClassOrObject> {
        val classes = mutableListOf<KtClassOrObject>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                if (classOrObject.getSuperNames().any { Name.identifier(it) in supertypeNames }) classes += classOrObject
                super.visitClassOrObject(classOrObject)
            }
        })
        return classes
    }

    private fun calculateAliases(aliasedName: Name, aliases: MutableSet<Name>, entries: (Name) -> List<Pair<KtFile, FileEntries>>) {
        entries(aliasedName).flatMap { (_, entries) -> entries.aliasNamesByAliasedName[aliasedName].orEmpty() }.forEach { aliasName ->
            val isNewAliasName = aliases.add(aliasName)
            if (isNewAliasName) {
                calculateAliases(aliasName, aliases, entries)
            }
        }
    }

    /** The source files of [file]'s package (from the index), [file] included. */
    private fun packageFiles(file: KtFile): List<KtFile> {
        val urls = index.filesForPackage(file.packageFqName)
        val manager = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
        return (urls.mapNotNull { url -> manager.findFileByUrl(url)?.let { index.getKtFile(it) } } + file).distinct()
    }

    @OptIn(KaImplementationDetail::class, SymbolInternals::class)
    private fun isValidInheritor(
        candidate: KtClassOrObject,
        baseFirClass: FirClass,
        scope: GlobalSearchScope,
        includeLocalInheritors: Boolean,
    ): Boolean {
        if (!includeLocalInheritors && candidate.isLocal) {
            return false
        }

        if (!scope.contains(candidate)) {
            return false
        }

        val candidateClassId = candidate.getClassId() ?: return false
        val candidateModule = KotlinProjectStructureProvider.getModule(project, candidate, useSiteModule = null)
        val candidateFirSymbol = candidateClassId.toFirSymbol(candidateModule) ?: return false
        val candidateFirClass = candidateFirSymbol.fir as? FirClass ?: return false

        return isSubclassOf(candidateFirClass, baseFirClass, candidateFirClass.moduleData.session, allowIndirectSubtyping = false)
    }

    @OptIn(LLFirInternals::class)
    private fun ClassId.toFirSymbol(module: KaModule): FirClassLikeSymbol<*>? {
        val session = LLFirSessionCache.getInstance(project).getSession(module, preferBinary = true)
        return session.symbolProvider.getClassLikeSymbolByClassId(this)
    }
}

private fun findInheritableSimpleNames(typeElement: KtTypeElement): List<String> {
    return when (typeElement) {
        is KtUserType -> {
            val referenceName = typeElement.referencedName ?: return emptyList()

            buildList {
                add(referenceName)

                val ktFile = typeElement.containingKtFile
                if (!ktFile.isCompiled) {
                    addIfNotNull(getImportedSimpleNameByImportAlias(typeElement.containingKtFile, referenceName))
                }
            }
        }
        is KtNullableType -> typeElement.innerType?.let(::findInheritableSimpleNames) ?: emptyList()
        else -> emptyList()
    }
}
