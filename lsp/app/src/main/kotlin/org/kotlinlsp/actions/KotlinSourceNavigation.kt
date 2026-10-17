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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.kotlinlsp.common.normalizeUri
import org.kotlinlsp.common.uriToPath
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.sourcesForPackage
import java.io.File

/**
 * Go to definition inside a library's Kotlin source that go to definition opened (`ComponentActivity.kt`, under the
 * index's `decompiled/` folder). Library sources aren't analysed as modules, so references are found by name, as far as
 * that is reliable: a parameter or local in scope, a declaration of that name in the same file (the closest enclosing
 * class first), else a class or top-level function by the file's imports, its own package, or Kotlin's default imports,
 * looked up from [useSiteModule] (a module that sees the libraries) and opened through the library's sources or
 * decompiled class. A qualified name (`Arrangement.Horizontal`, `Arrangement.Start`) is looked up in its qualifier's class.
 */
fun kotlinSourceDefinition(project: Project, uri: String, position: Position, index: Index, useSiteModule: KaModule?): List<Location>? {
    val file = File(uri.uriToPath()).takeIf { it.isFile } ?: return null
    val text = file.readText()
    val ktFile = PsiFileFactory.getInstance(project).createFileFromText(file.name, KotlinLanguage.INSTANCE, text, false, false) as? KtFile
        ?: return null
    val offset = offsetIn(text, position)
    val reference = ktFile.findElementAt(offset)?.parentOfType<KtSimpleNameExpression>(withSelf = true) ?: return null
    val name = reference.getReferencedName()
    val inThisFile = { declaration: KtNamedDeclaration ->
        val start = positionIn(text, declaration.nameIdentifier?.textRange?.startOffset ?: declaration.textRange.startOffset)
        listOf(Location(uri.normalizeUri(), Range(start, start)))
    }
    val javaFacade = JavaPsiFacade.getInstance(project)
    val everywhere = GlobalSearchScope.allScope(project)

    // `Arrangement.Horizontal`, `Arrangement.Start`, `Alignment.Top`: the member of the qualifier's class.
    val qualifiers = qualifiersOf(reference)
    if (qualifiers != null) {
        val owner = (listOf(qualifiers.joinToString(".")) + candidateNames(ktFile, qualifiers.first()).map { fqName ->
            (listOf(fqName.asString()) + qualifiers.drop(1)).joinToString(".")
        }).firstNotNullOfOrNull { javaFacade.findClass(it, everywhere) }
        val member = owner?.let { memberNamed(it, name) ?: it.findInnerClassByName("Companion", false)?.let { companion -> memberNamed(companion, name) } }
        if (member != null) return listOfNotNull(locationOf(member, index))
        // `RowScopeInstance.content()` calls the `content` parameter with a receiver; else a class of this file, or a
        // value whose type isn't known without analysis.
        (localDeclaration(reference, name) ?: sameFileDeclaration(ktFile, reference, name))?.let { return inThisFile(it) }
        return owner?.let { listOfNotNull(locationOf(it, index)) }
    }

    (localDeclaration(reference, name) ?: sameFileDeclaration(ktFile, reference, name))?.let { return inThisFile(it) }

    val module = useSiteModule ?: return null
    val candidates = candidateNames(ktFile, name)

    // Classes through the Java class finder: it gives the class file's PSI, which the source lookup takes (the Analysis
    // API's symbols of compiled classes have none, nor do its top-level functions, so `Layout` and `remember` are looked
    // up in the package's sources).
    val targets: List<PsiElement> = candidates.firstNotNullOfOrNull { fqName -> javaFacade.findClass(fqName.asString(), everywhere) }
        ?.let { listOf(it) }
        ?: candidates.firstNotNullOfOrNull { fqName -> topLevelInSources(project, index, fqName).takeIf { it.isNotEmpty() } }
            ?.let { found -> return found.map { sourcesJarLocation(it, index) }.distinctBy { it.uri + it.range.start } }
        ?: analyze(module) {
            candidates.firstNotNullOfOrNull { fqName ->
                findTopLevelCallables(fqName.parent(), fqName.shortName()).mapNotNull { it.psi }.toList().takeIf { it.isNotEmpty() }
            }
        }
        ?: return null
    return targets.mapNotNull { locationOf(it, index) }.distinctBy { it.uri + it.range.start }
}

/** Where [name] may be declared, in lookup order: an explicit import, this package, star imports, default imports. */
private fun candidateNames(ktFile: KtFile, name: String): List<FqName> = buildList {
    ktFile.importDirectives.filter { !it.isAllUnder && (it.aliasName ?: it.importedName?.asString()) == name }
        .mapNotNullTo(this) { it.importedFqName }
    add(ktFile.packageFqName.child(Name.identifier(name)))
    ktFile.importDirectives.filter { it.isAllUnder }.mapNotNullTo(this) { it.importedFqName?.child(Name.identifier(name)) }
    DEFAULT_PACKAGES.forEach { add(FqName(it).child(Name.identifier(name))) }
}.distinct()

private fun locationOf(target: PsiElement, index: Index): Location? =
    libraryLocation(target, index) ?: target.containingFile?.virtualFile?.takeIf { it.isInLocalFileSystem }?.let { source ->
        val start = positionIn(target.containingFile.text, (target as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset ?: target.textRange.startOffset)
        Location(source.url.normalizeUri(), Range(start, start))
    }

/** A nested class, field (a Kotlin `val` compiles to one) or method of a compiled class, by its Kotlin name. */
private fun memberNamed(owner: PsiClass, name: String): PsiElement? =
    owner.findInnerClassByName(name, false)
        ?: owner.findFieldByName(name, false)
        ?: owner.findMethodsByName(name, false).firstOrNull()
        ?: owner.findMethodsByName("get" + name.replaceFirstChar { it.uppercase() }, false).firstOrNull()

/** Top-level functions and properties named [fqName] in its package's library sources (`-sources.jar`). */
private fun topLevelInSources(project: Project, index: Index, fqName: FqName): List<KtNamedDeclaration> {
    val name = fqName.shortName().asString()
    val manager = PsiManager.getInstance(project)
    return index.sourcesForPackage(fqName.parent())
        .mapNotNull { url -> VirtualFileManager.getInstance().findFileByUrl(url)?.let { manager.findFile(it) as? KtFile } }
        .flatMap { file -> file.declarations.filterIsInstance<KtNamedDeclaration>().filter { (it is KtNamedFunction || it is KtProperty) && it.name == name } }
}

/** [declaration] (in a library's sources jar) in the copy of its file that the editor opens. */
private fun sourcesJarLocation(declaration: KtNamedDeclaration, index: Index): Location {
    val file = cacheSourcesJarFile(declaration.containingFile, index)
    val start = positionIn(declaration.containingFile.text, declaration.nameIdentifier?.textRange?.startOffset ?: declaration.textRange.startOffset)
    return Location(file.toURI().toString().normalizeUri(), Range(start, start))
}

/**
 * The names [reference] is qualified with, outermost first (`Arrangement` in `Arrangement.Horizontal`, as a type or an
 * expression), or null if it isn't qualified by plain names.
 */
private fun qualifiersOf(reference: KtSimpleNameExpression): List<String>? {
    (reference.parent as? KtUserType)?.takeIf { it.referenceExpression == reference }?.let { type ->
        val qualifiers = generateSequence(type.qualifier) { it.qualifier }.map { it.referencedName }.toList()
        return qualifiers.reversed().takeIf { it.isNotEmpty() && null !in it }?.filterNotNull()
    }
    val selector = (reference.parent as? KtCallExpression)?.takeIf { it.calleeExpression == reference } ?: reference
    val qualified = (selector.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == selector } ?: return null
    return namesOf(qualified.receiverExpression)
}

private fun namesOf(expression: KtExpression?): List<String>? = when (expression) {
    is KtNameReferenceExpression -> listOf(expression.getReferencedName())
    is KtDotQualifiedExpression -> {
        val selector = expression.selectorExpression as? KtNameReferenceExpression
        if (selector == null) null else namesOf(expression.receiverExpression)?.plus(selector.getReferencedName())
    }
    else -> null
}

/** The parameter or local variable [name] that [reference] sees: the innermost scope's, declared before it. */
private fun localDeclaration(reference: KtSimpleNameExpression, name: String): KtNamedDeclaration? {
    for (scope in generateSequence(reference.parent) { it.parent }) {
        when (scope) {
            is KtBlockExpression -> scope.statements.filterIsInstance<KtNamedDeclaration>()
                .lastOrNull { it.name == name && it.textRange.endOffset <= reference.textRange.startOffset }?.let { return it }
            // Lambdas too (`{ index -> … }`).
            is KtFunction -> scope.valueParameters.firstOrNull { it.name == name }?.let { return it }
            // A constructor's parameters, `val`s included, throughout the class.
            is KtClass -> scope.primaryConstructorParameters.firstOrNull { it.name == name }?.let { return it }
            is KtFile -> return null
        }
    }
    return null
}

private val DEFAULT_PACKAGES = listOf("kotlin", "kotlin.collections", "kotlin.coroutines", "kotlin.ranges", "kotlin.text", "kotlin.jvm")

/** A declaration named [name] in this file, preferring the classes around the reference, innermost first. */
private fun sameFileDeclaration(ktFile: KtFile, reference: KtSimpleNameExpression, name: String): KtNamedDeclaration? {
    val declarations = ktFile.collectDescendantsOfType<KtNamedDeclaration> { it.name == name && it !is KtParameter }
    if (declarations.isEmpty()) return null
    val enclosing = generateSequence(reference.parentOfType<KtClassOrObject>()) { it.parentOfType<KtClassOrObject>() }.toList()
    for (owner in enclosing) {
        declarations.firstOrNull { it.parentOfType<KtClassOrObject>() == owner || it == owner }?.let { return it }
    }
    // Parameters and locals of the enclosing function come before members elsewhere in the file.
    return declarations.firstOrNull { it.parentOfType<KtClassOrObject>() == null } ?: declarations.first()
}

private fun offsetIn(text: String, position: Position): Int {
    var offset = 0
    repeat(position.line) { offset = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 } }
    return (offset + position.character).coerceAtMost(text.length)
}

private fun positionIn(text: String, offset: Int): Position {
    val line = text.substring(0, offset).count { it == '\n' }
    return Position(line, offset - (text.lastIndexOf('\n', offset - 1) + 1))
}
