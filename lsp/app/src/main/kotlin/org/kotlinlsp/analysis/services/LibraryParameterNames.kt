/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.services

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.ClassUtil
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Real parameter names of Java library methods, where the compiler front end only has `p0`, `p1` (class files keep
 * names only as debug info): `observe(owner, observer)` instead of `observe(p0, p1)`, as Android Studio shows them.
 * From the class file's `MethodParameters` or local variable table, else from the library's sources (`-sources.jar`,
 * or the Android SDK's `sources/android-NN` for `android.jar`).
 */
object LibraryParameterNames {
    private val PLACEHOLDER = Regex("p\\d+")

    /** Library jar → its source roots (sources jars), from the module graph. */
    @Volatile private var sourcesByJar: Map<String, List<Path>> = emptyMap()
    private lateinit var project: Project

    /** Class file url → method `name + descriptor` → parameter names. */
    private val fromClassFiles = ConcurrentHashMap<String, Map<String, List<String>>>()
    /** Source file url → parsed file, or null when there is none. */
    private val sourceFiles = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    fun setup(project: Project, sourcesByJar: Map<String, List<Path>>) {
        this.project = project
        this.sourcesByJar = sourcesByJar
        fromClassFiles.clear()
        sourceFiles.clear()
    }

    fun isPlaceholder(name: String): Boolean = PLACEHOLDER.matches(name)

    /** The names of [method]'s parameters when they were lost in compilation, else null. */
    fun namesOf(method: PsiMethod, count: Int): List<String>? {
        if (!::project.isInitialized) return null
        val classFile = method.containingFile?.virtualFile ?: return null
        if (classFile.extension != "class") return null
        return runCatching { fromClassFile(method, classFile, count) ?: fromSources(method, classFile, count) }.getOrNull()
    }

    private fun fromClassFile(method: PsiMethod, classFile: VirtualFile, count: Int): List<String>? {
        val table = fromClassFiles.computeIfAbsent(classFile.url) { readClassFile(classFile) }
        val methodName = if (method.isConstructor) "<init>" else method.name
        val descriptor = runCatching { ClassUtil.getAsmMethodSignature(method) }.getOrNull()
        val names = descriptor?.let { table[methodName + it] }
            ?: table.entries.filter { (key, value) -> key.startsWith("$methodName(") && value.size == count }.singleOrNull()?.value
        return names?.takeIf { it.size == count && it.none(::isPlaceholder) }
    }

    /** Names from `MethodParameters`, else from the local variable table (methods with a body, compiled with `-g`). */
    private fun readClassFile(classFile: VirtualFile): Map<String, List<String>> {
        val result = HashMap<String, List<String>>()
        ClassReader(classFile.contentsToByteArray()).accept(object : ClassVisitor(Opcodes.API_VERSION) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val arguments = Type.getArgumentTypes(descriptor)
                val isStatic = access and Opcodes.ACC_STATIC != 0
                // Local variable slot of each argument.
                val slots = IntArray(arguments.size)
                var slot = if (isStatic) 0 else 1
                arguments.forEachIndexed { i, type -> slots[i] = slot; slot += type.size }
                val fromParameters = ArrayList<String?>()
                val fromLocals = arrayOfNulls<String>(arguments.size)
                return object : MethodVisitor(Opcodes.API_VERSION) {
                    override fun visitParameter(parameterName: String?, parameterAccess: Int) {
                        fromParameters += parameterName
                    }

                    override fun visitLocalVariable(variable: String, desc: String, sig: String?, start: Label, end: Label, index: Int) {
                        val i = slots.indexOf(index)
                        if (i >= 0 && fromLocals[i] == null) fromLocals[i] = variable
                    }

                    override fun visitEnd() {
                        val names = fromParameters.takeIf { it.size == arguments.size && it.all { n -> n != null } }?.filterNotNull()
                            ?: fromLocals.takeIf { it.all { n -> n != null } }?.filterNotNull()
                        if (names != null && arguments.isNotEmpty()) result[name + descriptor] = names
                    }
                }
            }
        }, ClassReader.SKIP_FRAMES)
        return result
    }

    private fun fromSources(method: PsiMethod, classFile: VirtualFile, count: Int): List<String>? {
        val containing = method.containingClass ?: return null
        val outermost = generateSequence(containing) { it.containingClass }.last()
        val qualifiedName = outermost.qualifiedName ?: return null
        val relative = qualifiedName.replace('.', '/') + ".java"
        val source = sourceRootsFor(classFile).firstNotNullOfOrNull { root -> sourceFile(root, relative) } ?: return null
        // The same class in the source, by its path of names from the outermost class.
        val path = generateSequence(containing) { it.containingClass }.map { it.name }.toList().reversed()
        var cls: PsiClass? = source.classes.firstOrNull { it.name == path.first() }
        for (name in path.drop(1)) cls = cls?.innerClasses?.firstOrNull { it.name == name }
        val candidates = (if (method.isConstructor) cls?.constructors else cls?.findMethodsByName(method.name, false))
            ?.filter { it.parameterList.parametersCount == count }
            .orEmpty()
        val wanted = method.parameterList.parameters.map { shortType(it.type.presentableText) }
        val match = candidates.singleOrNull()
            ?: candidates.firstOrNull { candidate -> candidate.parameterList.parameters.map { shortType(it.type.presentableText) } == wanted }
            ?: return null
        return match.parameterList.parameters.map { it.name }.takeIf { it.size == count && it.none(::isPlaceholder) }
    }

    /** `Observer<? super T>` → `Observer`; `int[]` stays `int[]`; `T...` → `T[]`. */
    private fun shortType(text: String): String =
        text.replace(Regex("<.*>"), "").replace("...", "[]").substringAfterLast('.').trim()

    private fun sourceRootsFor(classFile: VirtualFile): List<String> {
        val jar = classFile.path.substringBefore("!/")
        val roots = sourcesByJar[jar].orEmpty().map { it.toString() }
        return roots + listOfNotNull(sdkSourcesDir(jar)?.path)
    }

    /** android.jar's sources, installed with the SDK manager: `<sdk>/platforms/android-NN/android.jar` → `<sdk>/sources/android-NN`. */
    fun sdkSourcesDir(jarPath: String): File? {
        val android = File(jarPath).takeIf { it.name == "android.jar" } ?: return null
        val platform = android.parentFile ?: return null
        return File(platform.parentFile?.parentFile, "sources/${platform.name}").takeIf { it.isDirectory }
    }

    /**
     * The source of the class in [classFile] from its library's sources jar: `<Outer>.java` or `<Outer>.kt`, else the
     * file of that package declaring it (a Kotlin file can be named apart from its classes). Name and text, or null.
     */
    fun sourceTextFor(classFile: VirtualFile): Pair<String, String>? {
        val jar = classFile.path.substringBefore("!/")
        val entry = classFile.path.substringAfter("!/").removeSuffix(".class").substringBefore('$')
        val outer = entry.substringAfterLast('/')
        val packageDir = entry.substringBeforeLast('/', "")
        val declares = Regex("\\b(class|interface|object|enum class)\\s+${Regex.escape(outer)}\\b")
        for (root in sourcesByJar[jar].orEmpty().map { it.toString() }) {
            val base = if (root.endsWith(".jar") || root.endsWith(".zip")) StandardFileSystems.jar()?.findFileByPath("$root!/") else StandardFileSystems.local()?.findFileByPath(root)
            for (sourceSet in sourceSetRoots(base)) {
                val dir = (if (packageDir.isEmpty()) sourceSet else sourceSet.findFileByRelativePath(packageDir)) ?: continue
                for (name in listOf("$outer.java", "$outer.kt")) {
                    dir.findChild(name)?.let { file -> return name to String(file.contentsToByteArray(), Charsets.UTF_8) }
                }
                for (file in dir.children.orEmpty()) {
                    if (file.extension != "kt" && file.extension != "java") continue
                    val text = String(file.contentsToByteArray(), Charsets.UTF_8)
                    if (declares.containsMatchIn(text)) return file.name to text
                }
            }
        }
        return null
    }

    /**
     * Where packages start in a sources root: the root itself, or, in a Kotlin Multiplatform library's sources jar (all
     * of Compose), its source sets (`commonMain/androidx/compose/…`). Android's first, as `actual` declarations live
     * there, then the JVM's, then the shared ones. Without these, every Compose class was shown decompiled.
     */
    private fun sourceSetRoots(base: VirtualFile?): List<VirtualFile> {
        if (base == null) return emptyList()
        val sourceSets = base.children.orEmpty().filter { it.isDirectory && it.name.endsWith("Main") }
            .sortedBy { set ->
                val name = set.name.lowercase()
                when {
                    name.startsWith("android") -> 0
                    "android" in name -> 1
                    name.startsWith("jvm") -> 2
                    name == "commonmain" -> 4
                    else -> 3
                }
            }
        return listOf(base) + sourceSets
    }

    /** The SDK source file of a class in android.jar, if the SDK's sources are installed. */
    fun sdkSourceFile(classFile: VirtualFile): File? {
        val dir = sdkSourcesDir(classFile.path.substringBefore("!/")) ?: return null
        val relative = classFile.path.substringAfter("!/").removeSuffix(".class").substringBefore('$') + ".java"
        return File(dir, relative).takeIf { it.isFile }
    }

    private fun sourceFile(root: String, relative: String): PsiJavaFile? {
        val url = "$root!$relative"
        val cached = sourceFiles.computeIfAbsent(url) {
            val file = if (root.endsWith(".jar") || root.endsWith(".zip")) {
                StandardFileSystems.jar()?.findFileByPath("$root!/$relative")
            } else {
                StandardFileSystems.local()?.findFileByPath("$root/$relative")
            }
            val text = file?.let { runCatching { String(it.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() }
            text?.let {
                PsiFileFactory.getInstance(project).createFileFromText(relative.substringAfterLast('/'), JavaLanguage.INSTANCE, it) as? PsiJavaFile
            } ?: NONE
        }
        return cached as? PsiJavaFile
    }
}
