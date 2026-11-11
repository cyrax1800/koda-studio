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

package org.kotlinlsp.actions

import com.intellij.psi.PsiElement
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.java.decompiler.main.Fernflower
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.kotlinlsp.analysis.services.LibraryParameterNames
import com.intellij.psi.ClassFileViewProvider
import com.intellij.psi.FileViewProvider
import org.eclipse.lsp4j.Range
import com.intellij.psi.ResolveResult
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.index.Index
import org.jetbrains.kotlin.psi.psiUtil.textRangeWithoutComments
import org.kotlinlsp.common.getCachePath
import org.kotlinlsp.common.findSourceSymbols
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.kotlinlsp.common.normalizeUri


fun KtReference.mutlipleResolve(): List<PsiElement?> {
    val resolveResults: Array<ResolveResult> = this.multiResolve(false)
    val psiResults = mutableListOf<PsiElement?>()
    resolveResults.forEach { resolveResult -> resolveResult.element?.let { psiResults.add(it) }}
    return psiResults
}

fun goToDefinitionAction(ktFile: KtFile, position: Position, index: Index): List<Location?>? = analyze(ktFile) {
    val offset = position.toOffset(ktFile)
    val ref = ktFile.findReferenceAt(offset) as? KtReference ?: return null
    val elements = ref.mutlipleResolve()

    val isFromSource = elements.map { it?.containingFile }.distinct().any { ele -> ele?.viewProvider?.document != null }

    // Source Jar Decompilation Logic
    if(!isFromSource) {
        val possibleLocations = getFromSourcesJar(ktFile, ref, index)
        if (possibleLocations.isNotEmpty()) return possibleLocations
    }

    // A compiled Kotlin library without sources (an internal AAR, the stdlib) resolves to symbols without PSI.
    if (elements.isEmpty()) {
        return listOfNotNull(compiledLibraryElement(ktFile, ref)?.let { libraryLocation(it, index) })
    }

    // Regular source file logic
    val locations = mutableListOf<Location?>()
    
    for (element in elements) {
        val file = element?.containingFile ?: continue
        // A compiled Java class without sources (no `-sources.jar` in the Gradle cache) has no text to point into:
        // asking for its range threw. It is decompiled instead, as Android Studio does.
        if (file.virtualFile?.extension == "class") {
            libraryLocation(element, index)?.let(locations::add)
            continue
        }
        val range = runCatching { element.textRange.toLspRange(file) }.getOrNull() ?: continue
        locations.add(Location().apply {
            uri = file.virtualFile.url.normalizeUri()
            setRange(range)
        })
    }
    
    return locations
}

private fun KaSession.getFromSourcesJar(ktFile: KtFile, ref: KtReference, index: Index): List<Location> {
    val possibleVal = findSourceSymbols(ktFile, ref, index)
    if (possibleVal.isEmpty()) return emptyList()

    val targetElement = possibleVal.first()
    val cachedFile = cacheSourcesJarFile(targetElement.containingFile, index)

    return listOf(Location().apply {
        uri = cachedFile.toURI().toString().normalizeUri()
        range = targetElement.textRangeWithoutComments.toLspRange(targetElement.containingFile)
    })
}

/** Qualified names of the part classes of a multifile facade class (`kotlin.collections.MapsKt__MapsKt`), if it is one. */
private fun multifileParts(facadeClass: PsiClass): List<String> {
    val path = facadeClass.containingFile?.virtualFile?.path ?: return emptyList()
    val jar = path.substringBefore("!/", "").takeIf { it.isNotEmpty() } ?: return emptyList()
    val prefix = path.substringAfter("!/").removeSuffix(".class") + "__"
    return runCatching {
        java.util.zip.ZipFile(jar).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.startsWith(prefix) && it.endsWith(".class") && '$' !in it }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .toList()
        }
    }.getOrDefault(emptyList())
}

/**
 * The declaration [ref] resolves to, as a member of its class file: Kotlin classes compile to class files that Java
 * PSI reads like any other, and top-level declarations live in their file's facade class (`emptyMap` in `MapsKt`).
 * Properties are found through their getter or backing field.
 */
@OptIn(KaExperimentalApi::class)
private fun KaSession.compiledLibraryElement(ktFile: KtFile, ref: KtReference): PsiElement? {
    val symbol = ref.resolveToSymbols().firstOrNull() ?: return null
    val facade = JavaPsiFacade.getInstance(ktFile.project)
    val scope = ktFile.resolveScope
    fun findClass(classId: ClassId?): PsiClass? = classId?.let { facade.findClass(it.asSingleFqName().asString(), scope) }
    return when (symbol) {
        is KaConstructorSymbol -> findClass(symbol.containingClassId)
        is KaClassSymbol -> findClass(symbol.classId)
        is KaCallableSymbol -> {
            val target = ((symbol as? KaSyntheticJavaPropertySymbol)?.javaGetterSymbol ?: symbol).fakeOverrideOriginal
            val callableId = target.callableId ?: return null
            val owner = findClass(callableId.classId)
                ?: target.containingJvmClassName?.let { facade.findClass(it, scope) }
                ?: return null
            val name = callableId.callableName.asString()
            val capitalized = name.replaceFirstChar(Char::uppercaseChar)
            fun member(cls: PsiClass): PsiElement? = listOf(name, "get$capitalized", "is$capitalized")
                .firstNotNullOfOrNull { cls.findMethodsByName(it, false).firstOrNull() }
                ?: cls.findFieldByName(name, false)
            // A multifile facade (`MapsKt`) only delegates: the declaration's text is in one of its parts (`MapsKt__MapsKt`).
            multifileParts(owner).mapNotNull { facade.findClass(it, scope) }.firstNotNullOfOrNull(::member)
                ?: member(owner)
                ?: owner
        }
        else -> null
    }
}

/**
 * Where a declaration from a library class file is, as a file the editor can open (written under `<index>/decompiled/`):
 * the class's source from its library's sources jar (`.java` or `.kt`), else the Android SDK's sources for android.jar,
 * else the class decompiled with method bodies, else its signatures. Null for elements that aren't in a class file.
 */
fun libraryLocation(element: PsiElement, index: Index): Location? {
    val classFile = element.containingFile?.virtualFile?.takeIf { it.extension == "class" } ?: return null
    val (fileName, text) = LibraryParameterNames.sourceTextFor(classFile)
        ?: LibraryParameterNames.sdkSourceFile(classFile)?.let { it.name to it.readText() }
        ?: (runCatching { decompileWithBodies(classFile) ?: (element.containingFile as? ClsFileImpl)?.let(::decompile) }
            .onFailure { org.kotlinlsp.common.warn("Decompiling ${classFile.url} failed: $it") }
            .getOrNull()?.let { text ->
                // Vineflower writes Kotlin classes as Kotlin (`package a.b` without `;`), so the editor highlights them as Kotlin.
                val kotlin = Regex("^package\\s+[\\w.]+\\s*$", RegexOption.MULTILINE).containsMatchIn(text)
                classFile.nameWithoutExtension.substringBefore('$') + (if (kotlin) ".decompiled.kt" else ".decompiled.java") to text
            })
        ?: return null

    val packagePath = classFile.path.substringAfter("!/").substringBeforeLast('/', "")
    val dir = getCachePath(index.rootFolder).resolve("decompiled").let { if (packagePath.isEmpty()) it else it.resolve(packagePath) }
    Files.createDirectories(dir)
    val target = dir.resolve(fileName).toFile()
    if (!target.exists() || target.readText() != text) target.writeText(text)

    val offset = declarationOffset(text, element)
    val line = text.substring(0, offset).count { it == '\n' }
    val column = offset - (text.lastIndexOf('\n', offset - 1) + 1)
    return Location().apply {
        uri = target.toURI().toString().normalizeUri()
        range = Range(Position(line, column), Position(line, column))
    }
}

/** Where [element] is declared in [text] (Java or Kotlin source): its classes, outermost first, then the member. */
private fun declarationOffset(text: String, element: PsiElement): Int {
    val name = (element as? PsiNamedElement)?.name
    val classes = generateSequence(element as? PsiClass ?: (element as? PsiMember)?.containingClass) { it.containingClass }
        .toList().reversed()
    val classStart = classes.fold(0) { from, cls ->
        val declaration = Regex("\\b(class|interface|enum|record|@interface|object)\\s+${Regex.escape(cls.name ?: "")}\\b")
        declaration.find(text, from)?.range?.first ?: from
    }
    if (name == null || element is PsiClass) return classStart
    val n = Regex.escape(name)
    // A Kotlin property's getter (`getStart` for `val Start`, `isEnabled` for `val isEnabled`) points at the property.
    val property = (element as? PsiMethod)?.let { Regex("^(?:get|is)([A-Z]\\w*)$").find(name)?.groupValues?.get(1) }
        ?.let { listOf(it, it.replaceFirstChar(Char::lowercase), name) }
    val declarations = when (element) {
        // Kotlin `fun name(` / `fun <T> name(`, Java `Type name(` (not a call `name(`).
        is PsiMethod -> listOf(Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?$n\\s*\\("), Regex("[\\w>\\]?]\\s+$n\\s*\\(")) +
            property.orEmpty().map { Regex("\\b(?:val|var)\\s+(?:[\\w.]+\\.)?${Regex.escape(it)}\\b") }
        else -> listOf(Regex("\\b(?:val|var)\\s+$n\\b"), Regex("[\\w>\\]?]\\s+$n\\s*[=;]"))
    }
    return declarations.firstNotNullOfOrNull { it.find(text, classStart)?.range }?.let { range ->
        val matched = text.substring(range.first, range.last + 1)
        range.first + (listOf(name) + property.orEmpty()).map { matched.lastIndexOf(it) }.firstOrNull { it >= 0 }.let { it ?: 0 }
    } ?: Regex("\\b$n\\b").find(text, classStart)?.range?.first ?: classStart
}

/**
 * The class (with its nested classes) decompiled to Java with method bodies by Vineflower, the maintained fork of
 * IntelliJ's Fernflower decompiler. Null when it fails; the signatures-only view is used then.
 */
private fun decompileWithBodies(classFile: VirtualFile): String? {
    val jarPath = classFile.path.substringBefore("!/", "").takeIf { it.isNotEmpty() } ?: return null
    val entry = classFile.path.substringAfter("!/")
    val outer = entry.removeSuffix(".class").substringBefore('$')
    val work = Files.createTempDirectory("koda-decompile")
    try {
        val inputs = mutableListOf<File>()
        java.util.zip.ZipFile(jarPath).use { zip ->
            for (zipEntry in zip.entries()) {
                val name = zipEntry.name
                if (name == "$outer.class" || (name.startsWith("$outer$") && name.endsWith(".class"))) {
                    val out = work.resolve(name).toFile()
                    out.parentFile.mkdirs()
                    zip.getInputStream(zipEntry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    inputs += out
                }
            }
        }
        if (inputs.isEmpty()) return null
        var result: String? = null
        val saver = object : IResultSaver {
            override fun saveFolder(path: String) {}
            override fun copyFile(source: String, path: String, entryName: String) {}
            override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String?, mapping: IntArray?) {
                if (qualifiedName == outer && content != null) result = content
            }
            override fun createArchive(path: String, archiveName: String, manifest: java.util.jar.Manifest?) {}
            override fun saveDirEntry(path: String, archiveName: String, entryName: String) {}
            override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {}
            override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String, entryName: String, content: String?) {}
            override fun closeArchive(path: String, archiveName: String) {}
        }
        val logger = object : IFernflowerLogger() {
            override fun writeMessage(message: String, severity: Severity) {}
            override fun writeMessage(message: String, severity: Severity, t: Throwable) {}
        }
        val options = mapOf<String, Any>(
            IFernflowerPreferences.INDENT_STRING to "    ",
            IFernflowerPreferences.REMOVE_SYNTHETIC to "1",
            IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES to "1",
        )
        val fernflower = Fernflower(saver, options, logger)
        inputs.forEach(fernflower::addSource)
        // The rest of the jar for naming types and resolving calls.
        fernflower.addLibrary(File(jarPath))
        try {
            fernflower.decompileContext()
        } finally {
            fernflower.clearContext()
        }
        return result
    } catch (t: Throwable) {
        org.kotlinlsp.common.warn("Decompiling ${classFile.url} failed: $t")
        return null
    } finally {
        work.toFile().deleteRecursively()
    }
}

/**
 * The class file as Java source, as IntelliJ shows it without sources. `ClsFileImpl.decompile` does this through the
 * application's default project, which this server doesn't have; the same steps run here with the file's project.
 */
private fun decompile(file: ClsFileImpl): String {
    val constructor = ClsFileImpl::class.java.getDeclaredConstructor(FileViewProvider::class.java, Boolean::class.javaPrimitiveType)
    constructor.isAccessible = true
    val decompiling = constructor.newInstance(ClassFileViewProvider(file.manager, file.virtualFile), true)
    val append = ClsFileImpl::class.java.getDeclaredMethod("appendMirrorText", StringBuilder::class.java)
    append.isAccessible = true
    val text = StringBuilder()
    append.invoke(decompiling, text)
    return text.toString()
}

internal fun cacheSourcesJarFile(referenceFile: PsiFile, index: Index): File {
    val packagePath = sourcePackagePath(referenceFile)
    val cacheDir = getCachePath(index.rootFolder)
        .resolve("decompiled")
        .let { baseDir -> if (packagePath == Path.of("")) baseDir else baseDir.resolve(packagePath) }

    Files.createDirectories(cacheDir)

    val cachedFile = cacheDir.resolve(referenceFile.name).toFile()
    if (!cachedFile.exists()) {
        cachedFile.writeBytes(referenceFile.virtualFile.contentsToByteArray())
    }

    return cachedFile
}

private fun sourcePackagePath(referenceFile: PsiFile): Path {
    val packageName = when (referenceFile) {
        is KtFile -> referenceFile.packageFqName.asString()
        is PsiJavaFile -> referenceFile.packageName
        else -> ""
    }

    if (packageName.isBlank()) return Path.of("")

    val packageSegments = packageName.split('.').filter { it.isNotBlank() }.toTypedArray()
    return Path.of("", *packageSegments)
}