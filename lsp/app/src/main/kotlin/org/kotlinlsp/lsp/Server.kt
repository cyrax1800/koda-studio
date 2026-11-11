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

package org.kotlinlsp.lsp

import org.kotlinlsp.common.uriToPath
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinReadActionConfinementLifetimeToken
import org.kotlinlsp.analysis.AnalysisSession
import org.kotlinlsp.analysis.AnalysisSessionNotifier
import org.kotlinlsp.common.LaunchOptions
import org.kotlinlsp.common.getLspVersion
import org.kotlinlsp.common.info
import org.kotlinlsp.common.setupLogger
import org.kotlinlsp.common.normalizeUri
import org.kotlinlsp.common.isCancellation
import com.intellij.openapi.progress.ProcessCanceledException
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.Executors
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import java.util.concurrent.ScheduledFuture
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** How long typing must pause before background analysis (highlighting, hints, errors) starts. */
private const val TYPING_PAUSE_MS = 300L

/** Completion, hover or navigation slower than this is logged. */
private const val SLOW_REQUEST_MS = 1000L

interface KotlinLanguageServerNotifier {
    fun onExit() {}
    fun onBackgroundIndexingFinished() {}
}

class KotlinLanguageServer(
    private val notifier: KotlinLanguageServerNotifier
) : LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware {
    private lateinit var client: LanguageClient
    private lateinit var loadedSession: AnalysisSession
    /** Requests after a failed load answered with a stack trace each (the client asks for code actions on every cursor move). */
    private val analysisSession: AnalysisSession
        get() = if (::loadedSession.isInitialized) loadedSession
            else throw ResponseErrorException(ResponseError(ResponseErrorCode.ServerNotInitialized, "The Kotlin project isn't loaded", null))
    private lateinit var rootPath: String
    private val searchExecutor = Executors.newFixedThreadPool(2) { Thread(it, "KotlinLSP-search").apply { isDaemon = true } }
    private val lintExecutor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "KotlinLSP-Lint")
    }
    private var lintFuture: ScheduledFuture<*>? = null

    /**
     * Requests run off the thread that reads messages, which must stay free for edits and the next request: before,
     * diagnostics of a file just opened (10 s on a large activity, the first time) and semantic tokens of the whole file
     * ran on it, and completion queued behind them. What the user waits for (completion, hover, navigation) has its own
     * threads; highlighting, hints, code actions and the outline run beside it and an edit cancels them.
     */
    private val interactiveExecutor = Executors.newFixedThreadPool(2) { Thread(it, "KotlinLSP-interactive").apply { isDaemon = true } }
    private val backgroundExecutor = Executors.newFixedThreadPool(3) { Thread(it, "KotlinLSP-background").apply { isDaemon = true } }

    /** Edits so far per document, and when the last one came: background requests wait for a pause in typing. */
    private val editsByDocument = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastEditAt = 0L

    private fun <T> interactive(name: String, compute: () -> T): CompletableFuture<T> {
        val received = System.nanoTime()
        return CompletableFutures.computeAsync(interactiveExecutor) {
            compute().also {
                val ms = (System.nanoTime() - received) / 1_000_000
                if (ms > SLOW_REQUEST_MS) info("Slow $name: $ms ms")
            }
        }
    }

    /**
     * A request about [uri] that its own edits or the client cancel: answered "content modified", which the client
     * drops and asks again (it does after an edit of that file). An edit of another file also interrupts it, as every
     * write cancels the reads in flight, but the client wouldn't ask again then: it runs again after the pause.
     */
    private fun <T> background(uri: String, compute: (isCanceled: () -> Boolean) -> T): CompletableFuture<T> {
        val document = uri.normalizeUri()
        val editsBefore = editsOf(document)
        val changed = { editsOf(document) != editsBefore }
        return CompletableFutures.computeAsync(backgroundExecutor) { cancel ->
            val contentModified = { ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "Content modified", null)) }
            while (true) {
                // Not while typing: the compiler notices a cancellation only every few hundred milliseconds, and the
                // next keystroke would wait for that.
                while (true) {
                    if (cancel.isCanceled || changed()) throw contentModified()
                    val quiet = lastEditAt + TYPING_PAUSE_MS - System.currentTimeMillis()
                    if (quiet <= 0) break
                    try { Thread.sleep(quiet) } catch (_: InterruptedException) { throw contentModified() }
                }
                try {
                    return@computeAsync compute { cancel.isCanceled || changed() }
                } catch (e: Throwable) {
                    if (!isCancellation(e)) throw e
                    if (cancel.isCanceled || changed()) throw contentModified()
                    // Interrupted by an edit of another file: again.
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }
    }

    private fun editsOf(uri: String): Long = editsByDocument[uri] ?: 0L

    private val analysisSessionNotifier = object : AnalysisSessionNotifier {
        override fun onBackgroundIndexFinished() {
            notifier.onBackgroundIndexingFinished()
        }

        override fun onDiagnostics(params: PublishDiagnosticsParams) {
            client.publishDiagnostics(params)
        }

        override fun onReportProgress(phase: WorkDoneProgressKind, progressToken: String, text: String) {
            val notification = when (phase) {
                WorkDoneProgressKind.begin -> WorkDoneProgressBegin().apply { title = text }
                WorkDoneProgressKind.report -> WorkDoneProgressReport().apply { message = text }
                WorkDoneProgressKind.end -> WorkDoneProgressEnd().apply { message = text }
            }
            val params = ProgressParams().apply {
                token = Either.forLeft(progressToken)
                value = Either.forLeft(notification)
            }
            client.notifyProgress(params)
        }
    }

    /** Whether the client lets us register file watchers (`workspace/didChangeWatchedFiles` dynamic registration). */
    private var canWatchFiles = false

    /**
     * Asks the client to report Kotlin and Java files changed outside the editor (git checkout, generated sources).
     * VS Code's extension sets up its own watcher; clients like Zed only watch what a server registers.
     */
    private fun watchSourceFiles() {
        val options = DidChangeWatchedFilesRegistrationOptions(listOf(FileSystemWatcher(Either.forLeft("**/*.{kt,java}"))))
        val registration = Registration("koda-source-watcher", "workspace/didChangeWatchedFiles", options)
        client.registerCapability(RegistrationParams(listOf(registration))).exceptionally {
            info("Couldn't register file watchers: ${it.message}")
            null
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Incremental)
            hoverProvider = Either.forLeft(true)
            definitionProvider = Either.forLeft(true)
            // `@` opens the labels after `this@`, `return@`, ….
            completionProvider = CompletionOptions(false, listOf(".", "@"))
            implementationProvider = Either.forLeft(true)
            // Quick fixes, and Optimize Imports (also on save, with `editor.codeActionsOnSave`).
            codeActionProvider = Either.forRight(CodeActionOptions(listOf(CodeActionKind.QuickFix, CodeActionKind.SourceOrganizeImports)))
            // Reformat Code with ktlint.
            documentFormattingProvider = Either.forLeft(true)
            referencesProvider = Either.forLeft(true)
            documentSymbolProvider = Either.forLeft(true)
            renameProvider = Either.forLeft(true)
            // Compose stability of composable parameters, and parameter names before literal arguments.
            inlayHintProvider = Either.forLeft(true)
            // Parameter info (IntelliJ's Cmd+P), opened by `(` and `,`.
            signatureHelpProvider = SignatureHelpOptions(listOf("(", ","), listOf(",", "="))
            semanticTokensProvider = SemanticTokensWithRegistrationOptions().apply {
                legend = SemanticTokensLegend().apply {
                    tokenTypes = listOf(
                        "namespace", "class", "enum", "interface", "struct", "typeParameter", "type",
                        "parameter", "variable", "property", "enumMember", "decorator", "event",
                        "function", "method", "macro", "label", "comment", "string", "keyword",
                        "number", "regexp", "operator"
                    )
                    tokenModifiers = listOf(
                        "declaration", "definition", "readonly", "static", "deprecated", "abstract",
                        "async", "modification", "documentation", "defaultLibrary"
                    )
                }
                full = Either.forLeft(true)
                range = Either.forLeft(true)
            }
        }
        val serverInfo = ServerInfo().apply {
            name = "kotlin-lsp"
            version = getLspVersion()
        }

        LaunchOptions.read(params.initializationOptions)
        canWatchFiles = params.capabilities?.workspace?.didChangeWatchedFiles?.dynamicRegistration == true
        // Unencoded when it has a space (`~/Android Projects/app`); `URI(…)` threw on that.
        rootPath = params.workspaceFolders.first().uri.uriToPath()

        return completedFuture(InitializeResult(capabilities, serverInfo))
    }

    override fun initialized(params: InitializedParams) {
        setupLogger(client)
        info(rootPath)
        if (LaunchOptions.watchFiles && canWatchFiles) watchSourceFiles()

        try {
            loadedSession = AnalysisSession(analysisSessionNotifier, rootPath)
        } catch (t: Throwable) {
            // Thrown inside a notification, this was only logged, and the client kept showing "loading" forever.
            val message = "The Kotlin server couldn't load the project: ${t.message ?: t.javaClass.simpleName}"
            org.kotlinlsp.common.error("$message\n${t.stackTraceToString()}")
            org.kotlinlsp.common.ServerStatus.failed(message)
            client.showMessage(MessageParams(MessageType.Error, message))
        }
    }

    /**
     * Answers first and exits on the client's `exit` notification. Exiting inside `shutdown` closed the connection
     * before the reply, which clients report as a crash. Some clients (Nvim) never send `exit`, so the server also
     * exits a moment after the reply.
     */
    override fun shutdown(): CompletableFuture<Any> {
        Thread({
            Thread.sleep(2000)
            exit()
        }, "KotlinLSP-Shutdown").apply { isDaemon = true }.start()
        return completedFuture(null)
    }

    private val exited = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun exit() {
        if (!exited.compareAndSet(false, true)) return
        lintExecutor.shutdownNow()
        interactiveExecutor.shutdownNow()
        backgroundExecutor.shutdownNow()
        if (::loadedSession.isInitialized) loadedSession.dispose()
        notifier.onExit()
    }

    override fun getTextDocumentService(): TextDocumentService = this
    override fun getWorkspaceService(): WorkspaceService = this

    override fun didOpen(params: DidOpenTextDocumentParams) {
        analysisSession.onOpenFile(params.textDocument.uri)
        // Its errors in the background: the first analysis of a file can take seconds.
        val uri = params.textDocument.uri.normalizeUri()
        lintExecutor.execute { analysisSession.lintFile(uri, edited = false) }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri.normalizeUri()
        editsByDocument.merge(uri, 1L, Long::plus)
        lastEditAt = System.currentTimeMillis()
        analysisSession.editFile(uri, params.textDocument.version, params.contentChanges)

        // Debounce the linting so it is not triggered on every keystroke
        lintFuture?.cancel(false)
        lintFuture = lintExecutor.schedule({
            analysisSession.lintFile(uri)
        }, TYPING_PAUSE_MS, TimeUnit.MILLISECONDS)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        analysisSession.onCloseFile(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {

    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {

    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        if (analysisSession.onFilesChanged(params.changes)) lintExecutor.execute { analysisSession.lintOpenFiles() }
    }

     override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
         val smart = org.kotlinlsp.actions.autocomplete.SmartCompletion.consume()
         return interactive("completion") {
             var partial = false
             val completions = analysisSession.autocomplete(params.textDocument.uri, params.position, smart) { partial = true }
             // A list cut at the limit, or left partial on purpose, is incomplete: the client asks again as you type.
             val incomplete = partial || completions.size >= org.kotlinlsp.actions.autocomplete.MAX_COMPLETION_ITEMS
             Either.forRight(CompletionList(incomplete, completions))
         }
     }

    /** Ctrl+Shift+Space: the next completion lists only what fits the expected type (IntelliJ's smart completion). */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("koda/smartCompletion")
    fun smartCompletion() = org.kotlinlsp.actions.autocomplete.SmartCompletion.request()

    override fun connect(params: LanguageClient) {
        client = params
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> = interactive("hover") { hoverAt(params) }

    private fun hoverAt(params: HoverParams): Hover? {
        // TODO Add javadoc
        val (text, range) = analysisSession.hover(params.textDocument.uri, params.position) ?: return null
        val content = MarkupContent().apply {
            kind = "markdown"
            value = "```kotlin\n${text}\n```"
        }

        return Hover().apply {
            contents = Either.forRight(content)
            this.range = range
        }
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>?> =
        interactive("go to definition") {
            val locations = analysisSession.goToDefinition(params.textDocument.uri, params.position)?.filterNotNull()?.toMutableList()
            if (locations.isNullOrEmpty()) null else Either.forLeft(locations)
        }

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>?> =
        CompletableFutures.computeAsync(searchExecutor) {
            val locations = analysisSession.goToImplementation(params.textDocument.uri, params.position)?.filterNotNull()
            if (locations.isNullOrEmpty()) null else Either.forLeft<List<Location>, List<LocationLink>>(locations)
        }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
        background(params.textDocument.uri) { isCanceled -> codeActionsFor(params, isCanceled) }

    private fun codeActionsFor(params: CodeActionParams, isCanceled: () -> Boolean): List<Either<Command, CodeAction>> {
        val uri = params.textDocument.uri
        // `only` is set on save (`source.organizeImports`) and by the Source Action menu (`source`).
        val only = params.context.only
        // Only when asked for (on save, the Source Action menu): it resolves every reference in the file, and the
        // client asks for code actions on every cursor move.
        val wantsOrganize = only != null && only.any { CodeActionKind.SourceOrganizeImports.startsWith(it) }
        val wantsOthers = only == null || only.any { !it.startsWith(CodeActionKind.Source) }
        val actions = mutableListOf<CodeAction>()
        if (wantsOthers) actions += analysisSession.getCodeActions(uri, params.range, params.context.diagnostics, isCanceled)
        if (wantsOrganize) {
            val importEdits = analysisSession.organizeImports(uri, isCanceled)
            // An empty edit still answers a save, which then doesn't wait for a timeout.
            actions += CodeAction("Optimize imports").apply {
                kind = CodeActionKind.SourceOrganizeImports
                edit = WorkspaceEdit(mapOf(uri to importEdits))
            }
        }
        return actions.map { Either.forRight(it) }
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> =
        interactive("formatting") { analysisSession.format(params.textDocument.uri) }

    // Searches run on their own threads: CodeLens asks for every declaration in a file, and completion or edits must
    // not wait behind them. A search the client cancels stops between files.
    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp?> =
        interactive("signature help") { analysisSession.signatureHelp(params.textDocument.uri, params.position) }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> =
        background(params.textDocument.uri) { isCanceled -> analysisSession.inlayHints(params.textDocument.uri, params.range, isCanceled) }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>?> =
        CompletableFutures.computeAsync(searchExecutor) { cancel ->
            analysisSession.findReferences(params.textDocument.uri, params.position, params.context?.isIncludeDeclaration ?: true) {
                cancel.checkCanceled()
            }
        }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
        background(params.textDocument.uri) { isCanceled -> analysisSession.documentSymbols(params.textDocument.uri, isCanceled).map { Either.forRight(it) } }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> =
        interactive("rename") { analysisSession.rename(params.textDocument.uri, params.position, params.newName) }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens?> =
        background(params.textDocument.uri) { isCanceled -> analysisSession.semanticTokens(params.textDocument.uri, isCanceled) }

    override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens?> =
        background(params.textDocument.uri) { isCanceled -> analysisSession.semanticTokensRange(params.textDocument.uri, params.range, isCanceled) }
}
