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

package org.kotlinlsp.analysis

import org.kotlinlsp.common.getCachePath
import org.kotlinlsp.actions.kotlinSourceDefinition
import org.kotlinlsp.common.uriToPath
import org.kotlinlsp.actions.javaSourceDefinition
import org.kotlinlsp.analysis.modules.LibraryModule
import org.kotlinlsp.analysis.services.LibraryParameterNames
import org.kotlinlsp.common.ServerStatus
import java.nio.file.Paths
import java.net.URI
import org.kotlinlsp.actions.Formatting
import org.kotlinlsp.actions.organizeImportsEdits
import org.eclipse.lsp4j.TextEdit
import org.kotlinlsp.actions.parameterNameHints
import org.kotlinlsp.actions.signatureHelpAction
import org.eclipse.lsp4j.SignatureHelp
import org.kotlinlsp.common.LaunchOptions
import org.kotlinlsp.actions.compose.unstableParameterWarnings
import org.kotlinlsp.actions.compose.stabilityInlayHints
import org.eclipse.lsp4j.InlayHint
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.psi.util.PsiModificationTracker
import org.kotlinlsp.analysis.services.clearCaches
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinGlobalSourceModuleStateModificationEvent
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import com.intellij.core.CorePackageIndex
import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.messages.ListenerDescriptor
import org.picocontainer.PicoContainer
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.roots.PackageIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.platform.analysisMessageBus
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.api.platform.modification.handleElementModification
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModuleOutOfBlockModificationEvent
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinModuleDependentsProvider
import org.kotlinlsp.analysis.services.CompilerPluginsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.kotlinlsp.analysis.services.AllMatchesJvmDependenciesIndex
import org.kotlinlsp.analysis.services.makeThreadSafe
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleFinder
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.cli.jvm.modules.JavaModuleGraph
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.actions.autocomplete.autocompleteAction
import org.kotlinlsp.actions.autocomplete.warmUpDefaultImports
import org.kotlinlsp.actions.codeaction.CodeActionContext
import org.kotlinlsp.actions.codeaction.CodeActionRegistry
import org.kotlinlsp.actions.documentSymbolsAction
import org.kotlinlsp.actions.findReferencesAction
import org.kotlinlsp.actions.goToDefinitionAction
import org.kotlinlsp.actions.goToImplementationAction
import org.kotlinlsp.actions.hover.hoverAction
import org.kotlinlsp.actions.renameAction
import org.kotlinlsp.actions.semanticHighlightingAction
import org.kotlinlsp.actions.semanticHighlightingRangeAction
import org.kotlinlsp.analysis.modules.NotUnderContentRootModule
import org.kotlinlsp.analysis.modules.asFlatSequence
import org.kotlinlsp.analysis.registration.Registrar
import org.kotlinlsp.analysis.registration.lspPlatform
import org.kotlinlsp.analysis.registration.lspPlatformPostInit
import org.kotlinlsp.analysis.services.*
import org.kotlinlsp.buildsystem.BuildSystemResolver
import org.kotlinlsp.common.*
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.IndexNotifier

interface DiagnosticsNotifier {
    fun onDiagnostics(params: PublishDiagnosticsParams)
}

interface ProgressNotifier {
    fun onReportProgress(phase: WorkDoneProgressKind, progressToken: String, text: String)
}

interface AnalysisSessionNotifier: IndexNotifier, DiagnosticsNotifier, ProgressNotifier

@OptIn(KaImplementationDetail::class)
class AnalysisSession(private val notifier: AnalysisSessionNotifier, rootPath: String) {
    private val app: MockApplication
    private val project: MockProject
    private val commandProcessor: CommandProcessor
    private val psiDocumentManager: PsiDocumentManager
    private val buildSystemResolver: BuildSystemResolver
    private val index: Index
    /** The module that sees the most libraries (usually the app), for looking names up from library sources. */
    private val broadestModule: Lazy<KaModule?>
    private val codeActionRegistry: CodeActionRegistry
    private var projectStructureProvider: ProjectStructureProvider
    private val javaFileManager: KotlinCliJavaFileManagerImpl
    private lateinit var javaClassIndex: AllMatchesJvmDependenciesIndex
    private lateinit var directInheritors: DirectInheritorsProvider

    init {
        System.setProperty("java.awt.headless", "true")
        setupIdeaStandaloneExecution()

        // Create core objects for Analysis API
        val projectDisposable = Disposer.newDisposable("LSPAnalysisAPISession.project")
        val compilerConfiguration = CompilerConfiguration()
        val appEnvironment = KotlinCoreEnvironment.getOrCreateApplicationEnvironment(
            projectDisposable,
            compilerConfiguration,
            KotlinCoreApplicationEnvironmentMode.Production
        )
        val coreEnvironment = object : KotlinCoreProjectEnvironment(projectDisposable, appEnvironment) {
            // The Analysis API declares its listeners in plugin descriptors; the mock project must be able to create them.
            override fun createProject(parent: PicoContainer, parentDisposable: Disposable): MockProject =
                object : MockProject(parent, parentDisposable) {
                    @Suppress("UnstableApiUsage")
                    override fun createListener(descriptor: ListenerDescriptor): Any {
                        val listenerClass = loadClass<Any>(descriptor.listenerClassName, descriptor.pluginDescriptor)
                        return listenerClass.getDeclaredConstructor(Project::class.java).newInstance(this)
                    }
                }
        }
        project = coreEnvironment.project
        project.registerRWLock()
        app = appEnvironment.application

        // Register the LSP platform in the Analysis API
        val registrar = Registrar(project, app, projectDisposable)
        registrar.lspPlatform()

        // Get the modules to analyze calling the appropriate build system
        buildSystemResolver = BuildSystemResolver(project, appEnvironment, notifier, rootPath)
        val modules = buildSystemResolver.resolveModules()
        broadestModule = lazy {
            modules.asFlatSequence().filter { it.isSourceModule }
                .maxByOrNull { listOf(it).asFlatSequence().count() }?.kaModule
        }

        // Create the index
        index = Index(modules, project, rootPath, notifier)
        // Real parameter names of Java library methods (`observe(owner, observer)`), from class files or sources jars.
        LibraryParameterNames.setup(project, modules.asFlatSequence().filterIsInstance<LibraryModule>()
            .filter { !it.sourceRoots.isNullOrEmpty() }
            .flatMap { library -> library.contentRoots.map { it.toString() to library.sourceRoots.orEmpty() } }
            .toMap())

        // Initialize code action registry
        codeActionRegistry = CodeActionRegistry.createDefault()

        // Prepare the dependencies index for the Analysis API
        project.setupHighestLanguageLevel()
        val librariesScope = ProjectScope.getLibrariesScope(project)
        val libraryRoots = modules
            .asFlatSequence()
            .filter { !it.isSourceModule }
            .map { it.computeFiles(extended = false).map { JavaRoot(it, JavaRoot.RootType.BINARY) } }
            .flatten()
            .toList()
        // Java sources of the project's own modules: generated BuildConfig, protobuf/AIDL output, KSP/kapt Java, and
        // hand-written .java files. Without them Kotlin saw only Java classes from jars.
        val sourceJavaRoots = modules
            .asFlatSequence()
            .filter { it.isSourceModule }
            .flatMap { it.contentRoots.asSequence() }
            .distinct()
            .mapNotNull { root -> project.read { VirtualFileManager.getInstance().findFileByNioPath(root) }?.takeIf { it.isDirectory } }
            .map { JavaRoot(it, JavaRoot.RootType.SOURCE) }
            .toList()

        javaFileManager = project.getService(JavaFileManager::class.java) as KotlinCliJavaFileManagerImpl
        makeThreadSafe(javaFileManager)
        val javaModuleFinder = CliJavaModuleFinder(null, stubReportingConfiguration(), javaFileManager, project, null)
        val javaModuleGraph = JavaModuleGraph(javaModuleFinder)
        val delegateJavaModuleResolver = CliJavaModuleResolver(
            javaModuleGraph,
            emptyList(),
            emptyList(),    // This is always empty in standalone platform
            project,
        )

        val corePackageIndex = project.getService(PackageIndex::class.java) as CorePackageIndex

        val packagePartProvider = JvmPackagePartProvider(latestLanguageVersionSettings, librariesScope).apply {
            addRoots(libraryRoots, stubReportingConfiguration())
        }
        val rootsIndex = JvmDependenciesDynamicCompoundIndex(shouldOnlyFindFirstClass = false).apply {
            addIndex(AllMatchesJvmDependenciesIndex(libraryRoots + sourceJavaRoots).also { javaClassIndex = it })
            indexedRoots.forEach { javaRoot ->
                if (javaRoot.file.isDirectory) {
                    if (javaRoot.type == JavaRoot.RootType.SOURCE) {
                        javaFileManager.addToClasspath(javaRoot.file)
                        corePackageIndex.addToClasspath(javaRoot.file)
                    } else {
                        coreEnvironment.addSourcesToClasspath(javaRoot.file)
                    }
                }
            }
        }

        javaFileManager.initialize(
            index = rootsIndex,
            packagePartProviders = listOf(packagePartProvider),
            singleJavaFileRootsIndex = SingleJavaFileRootsIndex(emptyList()),
            usePsiClassFilesReading = true,
            perfManager = null,
        )
        val fileFinderFactory = CliVirtualFileFinderFactory(rootsIndex, false, perfManager = null)

        // Register remaining services after setting up dependencies index
        registrar.lspPlatformPostInit(
            cliJavaModuleResolver = delegateJavaModuleResolver,
            cliVirtualFileFinderFactory = fileFinderFactory
        )

        // Setup platform services
        (project.getService(KotlinModuleDependentsProvider::class.java) as ModuleDependentsProvider).setup(
            modules
        )
        (project.getService(KotlinCompilerPluginsProvider::class.java) as CompilerPluginsProvider).setup(modules)
        projectStructureProvider = project.getService(KotlinProjectStructureProvider::class.java) as ProjectStructureProvider
        projectStructureProvider.setup(
            modules,
            project
        )
        (project.getService(KotlinPackageProviderFactory::class.java) as PackageProviderFactory).setup(project, index)
        (project.getService(KotlinDeclarationProviderFactory::class.java) as DeclarationProviderFactory).setup(
            project,
            index
        )
        (project.getService(KotlinPackagePartProviderFactory::class.java) as PackagePartProviderFactory).setup(
            libraryRoots
        )
        (project.getService(KotlinAnnotationsResolverFactory::class.java) as AnnotationsResolverFactory).setup(project, index)
        directInheritors = project.getService(KotlinDirectInheritorsProvider::class.java) as DirectInheritorsProvider
        directInheritors.setup(project, index, modules)

        commandProcessor = app.getService(CommandProcessor::class.java)
        psiDocumentManager = PsiDocumentManager.getInstance(project)


        // Sync the index in the background
        index.syncIndexInBackground()
        index.warmUpWordIndex()
    }

    /**
     * A copy of a library's source that go to definition wrote under the index (`ComponentActivity.kt`). It isn't part
     * of any module: analysed on its own it would show errors on every library reference, so it isn't checked.
     */
    private fun isLibrarySourceCopy(path: String): Boolean =
        path.uriToPath().startsWith(getCachePath(index.rootFolder).resolve("decompiled").toString())

    fun onOpenFile(path: String) {
        if (isLibrarySourceCopy(path)) return
        val ktFile = loadKtFile(path) ?: return
        index.openKtFile(path, ktFile)
        var kaModule: KaModule? = projectStructureProvider.getModule(ktFile, null)
        if(kaModule !is NotUnderContentRootModule){
            return
        } else {
            // Register the file into the owning module's content scope so Analysis API can resolve it
            index.addVirtualFileToModuleScope(ktFile.virtualFile)
            projectStructureProvider.clearModuleCache()
            kaModule = projectStructureProvider.getModule(ktFile, null)
        }

        // Under the server's write lock, as all modifications: analyses run under its read lock.
        project.write {
            val bus = project.analysisMessageBus.syncPublisher(KotlinModificationEvent.TOPIC)
            bus.onModification(KotlinModuleOutOfBlockModificationEvent(kaModule))
        }
    }

    /**
     * Kotlin and Java files created, changed or deleted outside the editor: a `git checkout`, generated code, a file
     * added in the explorer. Without this the server kept analysing the files as they were when it started. Files open
     * in the editor are skipped unless deleted: the editor's text is the current one.
     */
    /** Returns whether any change applied (files open in the editor are skipped). */
    fun onFilesChanged(changes: List<FileEvent>): Boolean {
        val sourceChanges = changes
            .map { it.uri.normalizeUri() to it.type }
            .filter { (uri, _) -> uri.endsWith(".kt") || uri.endsWith(".java") }
            // Library copies go to definition writes: Zed's index (and so its watcher) is inside the project.
            .filterNot { (uri, _) -> isLibrarySourceCopy(uri) }
            .filter { (uri, type) -> type == FileChangeType.Deleted || index.getOpenedKtFile(uri) == null }
        if (sourceChanges.isEmpty()) return false
        val psiManager = PsiManager.getInstance(project)
        val reindex = mutableListOf<KtFile>()
        project.write {
            for ((uri, type) in sourceChanges) {
                index.sourceFileChangedOnDisk(uri)
                if (type == FileChangeType.Deleted) {
                    index.removeFile(uri)
                    directInheritors.fileChanged(uri, null)
                    continue
                }
                val virtualFile = VirtualFileManager.getInstance().findFileByUrl(uri) ?: continue
                index.addVirtualFileToModuleScope(virtualFile)
                index.forgetCachedFile(uri)
                val psiFile = psiManager.findFile(virtualFile) ?: continue
                psiManager.reloadFromDisk(psiFile)
                (psiManager.findFile(virtualFile) as? KtFile)?.let {
                    reindex += it
                    directInheritors.fileChanged(uri, it)
                }
            }
            projectStructureProvider.clearModuleCache()
            if (sourceChanges.any { (uri, _) -> uri.endsWith(".java") }) {
                javaClassIndex.invalidate()
                clearCaches(javaFileManager)
            }
            // Java lookups (KotlinJavaPsiFacade) are cached until PSI's global modification count moves.
            (PsiModificationTracker.getInstance(project) as? PsiModificationTrackerImpl)?.incCounter()
        }
        // Declarations come from the index, so it must be current before the sessions are rebuilt.
        reindex.forEach { index.indexNow(it) }
        project.write {
            // Files joined or left modules: every source session is rebuilt.
            project.analysisMessageBus.syncPublisher(KotlinModificationEvent.TOPIC)
                .onModification(KotlinGlobalSourceModuleStateModificationEvent)
        }
        info("Applied ${sourceChanges.size} file changes from disk")
        return true
    }

    /** Errors of every open file again, e.g. after files changed on disk. An edit cancels it. */
    fun lintOpenFiles() {
        try {
            index.openedKtFiles.forEach { (_, file) -> updateDiagnostics(file) }
        } catch (e: Throwable) {
            if (!isCancellation(e)) throw e
        }
    }

    fun onCloseFile(path: String) {
        index.closeKtFile(path)
        // While open, the file was searched by its editor text; the saved file is what counts again.
        index.sourceFileChangedOnDisk(path.normalizeUri())
    }

    private fun loadKtFile(path: String): KtFile? {
        val virtualFile = project.read { VirtualFileManager.getInstance()
            .findFileByUrl(path) } ?: return null
        return project.read { PsiManager.getInstance(project).findFile(virtualFile) as? KtFile }
    }

    private fun updateDiagnostics(ktFile: KtFile) = ServerStatus.analyzing(ktFile.name) {
        val syntaxDiagnostics = project.cancellableRead {
            PsiTreeUtil.collectElementsOfType(ktFile, PsiErrorElement::class.java).map {
                return@map Diagnostic(
                    it.textRange.toLspRange(ktFile),
                    it.errorDescription,
                    DiagnosticSeverity.Error,
                    "Kotlin LSP",
                    "SYNTAX"
                )
            }
        }
        val analysisDiagnostics = project.cancellableRead {
            analyze(ktFile) {
                val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)

                val lspDiagnostics = diagnostics.map {
                    return@map Diagnostic(
                        it.textRanges.first().toLspRange(ktFile),
                        it.defaultMessage,
                        it.severity.toLspSeverity(),
                        "Kotlin LSP",
                        it.factoryName
                    )
                }

                return@analyze lspDiagnostics
            }
        }
        val stabilityDiagnostics = if (!LaunchOptions.composeStability) emptyList() else project.cancellableRead {
            analyze(ktFile) { unstableParameterWarnings(ktFile) }
        }
        notifier.onDiagnostics(PublishDiagnosticsParams(ktFile.virtualFile.url.normalizeUri(), syntaxDiagnostics + analysisDiagnostics + stabilityDiagnostics))
        logProfileInfo()
    }

    // TODO Use version to avoid conflicts
    fun editFile(path: String, version: Int, changes: List<TextDocumentContentChangeEvent>) {
        val ktFile = index.getOpenedKtFile(path) ?: return
        val doc = project.read { psiDocumentManager.getDocument(ktFile) } ?: return

        project.write {
            changes.forEach {
                commandProcessor.executeCommand(project, {
                    val startOffset = it.range.start.toOffset(ktFile)
                    val endOffset = it.range.end.toOffset(ktFile)

                    // VS Code usually sends edits with CRLF ("\r\n"). The PSI Document counts
                    // offsets based on LF ("\n") line delimiters, and StringUtil's line/column
                    // helpers do the same.  Converting to LF before applying keeps offsets
                    // consistent and prevents losing or mis-placing newlines.
                    val normalizedText = it.text.replace("\r\n", "\n")
                    // The command processor swallows exceptions, which once hid a missing platform jar: every edit was
                    // silently dropped and the server kept analysing the file as it was when opened.
                    try {
                        doc.replaceString(startOffset, endOffset, normalizedText)
                        psiDocumentManager.commitDocument(doc)
                        ktFile.onContentReload()
                    } catch (t: Throwable) {
                        warn("Applying an edit to $path failed: $t")
                        throw t
                    }
                }, "onChangeFile", null)
            }

            // The whole file, not the edited element: committing the document reparses the file into new PSI, so an
            // "in-block" modification left the engine with declarations of the old tree ("Classifier was found in
            // KtFile but was not found in FirFile"). Precise invalidation needs incremental reparsing first.
            // `onContentReload` dropped the syntax tree. Rebuild it here, under the write lock: built lazily, the first
            // readers after an edit (Optimize Imports and the error check, say) each started building it at once and
            // could link their halves into a loop that every later walk of the file never left.
            ktFile.node
            // Java lookups (a package's classes, light classes) are cached until PSI's global modification count moves;
            // left as it was, find references resolved Java code to the old tree's classes and failed after any edit.
            (PsiModificationTracker.getInstance(project) as? PsiModificationTrackerImpl)?.incCounter()
            KaSourceModificationService.getInstance(project)
                .handleElementModification(ktFile, KaElementModificationType.Unknown)
            directInheritors.fileChanged(ktFile.virtualFile.url, ktFile)
        }
    }

    /**
     * Errors of [path] after it was opened or edited ([edited]), then of the other open files, which an edit can affect.
     * An edit cancels this (see [cancellableRead]) and lints again after it.
     */
    fun lintFile(path: String, edited: Boolean = true) {
        val ktFile = index.getOpenedKtFile(path) ?: return
        if (edited) index.queueOnFileChanged(ktFile)

        // Update diagnostics on the edited file first so feedback is faster
        try {
            if (!edited) warmUpCompletion(ktFile)
            updateDiagnostics(ktFile)
            if (edited) index.openedKtFiles.forEach { (key, file) ->
                if(key != path) updateDiagnostics(file)
            }
        } catch (e: Throwable) {
            if (!isCancellation(e)) throw e
        }
    }

    /**
     * Loads what completion in [ktFile] lists, right after it's opened: the members of `this` and of its supertypes
     * (an activity has hundreds, from a dozen library classes) and of the default imports. The first completion in an
     * activity took 1.5 s for that, and the edits typed meanwhile waited for it.
     */
    private fun warmUpCompletion(ktFile: KtFile) {
        val body = project.cancellableRead {
            PsiTreeUtil.findChildrenOfType(ktFile, org.jetbrains.kotlin.psi.KtNamedFunction::class.java)
                .firstNotNullOfOrNull { it.bodyBlockExpression }
        } ?: return
        project.cancellableRead {
            analyze(ktFile) {
                val context = ktFile.scopeContext(body)
                for (scope in context.scopes) {
                    scope.scope.callables { true }.forEach { _ -> }
                }
                warmUpDefaultImports(ktFile, body)
            }
        }
    }

    fun dispose() {
        index.close()
    }

    private fun getKtFile(path: String): KtFile? {
        // Library source copies have no module; features that analyse them (highlighting, hints) would see only errors.
        if (isLibrarySourceCopy(path)) return null
        val openedFile = index.getOpenedKtFile(path)
        if (openedFile != null) {
            return openedFile
        }

        val virtualFile = project.read { 
            VirtualFileManager.getInstance().findFileByUrl(path) 
        } ?: return null
        
        val result = index.getKtFile(virtualFile)
        return result
    }

    /** Optimize Imports: unused imports removed, the rest sorted. */
    fun organizeImports(path: String, isCanceled: () -> Boolean = { false }): List<TextEdit> {
        val ktFile = getKtFile(path) ?: return emptyList()
        return project.cancellableRead(isCanceled) { organizeImportsEdits(ktFile) }
    }

    /** Reformat Code with ktlint (Android Studio code style unless the project's `.editorconfig` says otherwise). */
    fun format(path: String): List<TextEdit> {
        val ktFile = getKtFile(path) ?: return emptyList()
        val text = project.read { ktFile.text }
        return Formatting.formatEdits(text, path.uriToPath())
    }

    fun signatureHelp(path: String, position: Position): SignatureHelp? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { signatureHelpAction(ktFile, position.toOffset(ktFile), index) }
    }

    /** Parameter names before literal arguments, and Compose stability after composable parameters, in [range]. */
    fun inlayHints(path: String, range: Range, isCanceled: () -> Boolean = { false }): List<InlayHint> {
        val ktFile = getKtFile(path) ?: return emptyList()
        return project.cancellableRead(isCanceled) {
            analyze(ktFile) {
                val visible = com.intellij.openapi.util.TextRange(range.start.toOffset(ktFile), range.end.toOffset(ktFile))
                parameterNameHints(ktFile, visible, LaunchOptions.parameterNameHints) +
                    if (LaunchOptions.composeStability) stabilityInlayHints(ktFile, range) else emptyList()
            }
        }
    }

    fun hover(path: String, position: Position): Pair<String, Range>? {
        if (isLibrarySourceCopy(path)) return null
        val ktFile = getKtFile(path) ?: return null
        return project.read { hoverAction(ktFile, position, index) }
    }

    fun goToDefinition(path: String, position: Position): List<Location?>? {
        // A library's Java source that go to definition opened (`AppCompatActivity.java`).
        if (path.endsWith(".java")) return project.read { javaSourceDefinition(project, path, position, index) }
        // A library's Kotlin source opened the same way (`ComponentActivity.kt`).
        if (isLibrarySourceCopy(path)) return project.read { kotlinSourceDefinition(project, path, position, index, broadestModule.value) }
        val ktFile = getKtFile(path) ?: return null
        return project.read { goToDefinitionAction(ktFile, position, index) }
    }

    fun goToImplementation(path: String, position: Position): List<Location?>? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { goToImplementationAction(ktFile, position) }
    }

    fun autocomplete(path: String, position: Position, smart: Boolean = false, onPartial: () -> Unit = {}): List<CompletionItem> {
        val ktFile = getKtFile(path) ?: return emptyList()
        val offset = position.toOffset(ktFile)

        return project.read { autocompleteAction(ktFile, offset, index, onPartial, smart).toList() }
    }

    fun getCodeActions(path: String, range: Range, diagnostics: List<Diagnostic>, isCanceled: () -> Boolean = { false }): List<CodeAction> {
        val ktFile = getKtFile(path) ?: return emptyList()

        return project.cancellableRead(isCanceled) {
            val context = CodeActionContext(ktFile, range, diagnostics, path, index)
            codeActionRegistry.getCodeActions(context)
        }
    }

    fun findReferences(path: String, position: Position, includeDeclaration: Boolean = true, checkCanceled: () -> Unit = {}): List<Location>? {
        val ktFile = getKtFile(path) ?: return null
        return findReferencesAction(project, ktFile, position, index, includeDeclaration, checkCanceled)
    }

    fun documentSymbols(path: String, isCanceled: () -> Boolean = { false }): List<DocumentSymbol> {
        val ktFile = getKtFile(path) ?: return emptyList()
        return project.cancellableRead(isCanceled) { documentSymbolsAction(ktFile) }
    }

    fun rename(path: String, position: Position, newName: String): WorkspaceEdit? {
        val ktFile = getKtFile(path) ?: return null
        return project.read { renameAction(ktFile, position, newName, index) }
    }

    fun semanticTokens(path: String, isCanceled: () -> Boolean = { false }): SemanticTokens? {
        librarySourceCopy(path)?.let { return project.read { semanticHighlightingAction(it, resolve = false) } }
        val ktFile = getKtFile(path) ?: return null
        return project.cancellableRead(isCanceled) { semanticHighlightingAction(ktFile) }
    }

    fun semanticTokensRange(path: String, range: Range, isCanceled: () -> Boolean = { false }): SemanticTokens? {
        librarySourceCopy(path)?.let { return project.read { semanticHighlightingRangeAction(it, range, resolve = false) } }
        val ktFile = getKtFile(path) ?: return null
        return project.cancellableRead(isCanceled) { semanticHighlightingRangeAction(ktFile, range) }
    }

    /** A library's Kotlin source that go to definition opened, parsed on its own (it belongs to no module). */
    private fun librarySourceCopy(path: String): KtFile? {
        if (!isLibrarySourceCopy(path) || !path.endsWith(".kt")) return null
        val file = java.io.File(path.uriToPath()).takeIf { it.isFile } ?: return null
        return project.read {
            com.intellij.psi.PsiFileFactory.getInstance(project)
                .createFileFromText(file.name, org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE, file.readText(), false, false) as? KtFile
        }
    }
}

/** Configuration that only carries a message collector, as the standalone platform passes for reporting. */
internal fun stubReportingConfiguration(): CompilerConfiguration =
    CompilerConfiguration.create(BaseDiagnosticsCollector.DoNothing, MessageCollector.NONE)
