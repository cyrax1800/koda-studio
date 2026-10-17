// Runs our Kotlin language server (lsp/, shipped as server/kotlin-lsp) for one Android project.
import fs from 'node:fs';
import path from 'node:path';
import * as vscode from 'vscode';
import { CloseAction, ErrorAction, LanguageClient, State, type LanguageClientOptions, type ServerOptions } from 'vscode-languageclient/node.js';

import { readConfig } from '../config.ts';
import { findJavaRuntime } from '../gradle/javaHome.ts';
import { log } from '../log.ts';

export type ServerState = 'stopped' | 'starting' | 'running' | 'failed';

/** What the server is busy with, from its `koda/status` notification. */
export interface ServerProgress {
  state: 'loading' | 'indexing' | 'ready' | 'failed';
  indexed: number;
  toIndex: number;
  /** A file whose errors are being worked out, if any. */
  analyzing?: string;
  /** Why the project couldn't be loaded, when `state` is `failed`. */
  message?: string;
  /**
   * Set on `ready` after library jars were indexed (first open, new dependencies). Analysis done while they were being
   * indexed can keep wrong library types, e.g. a false `observe { }` type mismatch, until the server restarts.
   */
  restartRecommended?: boolean;
  /** A module being set up for analysis (`app.features.feature_home.prodDebug`), the first time a file needs it. */
  preparing?: string;
  /** Modules set up since the server started. */
  modulesPrepared?: number;
}

export class KotlinServer implements vscode.Disposable {
  private client: LanguageClient | undefined;
  private starting: Promise<void> | undefined;
  private readonly stateEmitter = new vscode.EventEmitter<ServerState>();
  private readonly progressEmitter = new vscode.EventEmitter<ServerProgress>();
  private _progress: ServerProgress | undefined;
  private _state: ServerState = 'stopped';
  /** Set while we stop the server on purpose, so the closed connection isn't reported as a crash. */
  private stopping = false;
  private crashes: number[] = [];
  /** When the server was last restarted because indexing finished, so that it happens at most once in a while. */
  private lastIndexRestart = 0;
  private readonly outputChannel = vscode.window.createOutputChannel('Kotlin Language Server', { log: true });

  readonly onDidChangeState = this.stateEmitter.event;
  readonly onDidChangeProgress = this.progressEmitter.event;

  /** Requests the user is waiting for (completion, go to definition) that take a while, for the status bar. */
  private readonly waiting = new Map<number, string>();
  private nextWait = 0;
  private readonly waitingEmitter = new vscode.EventEmitter<void>();
  readonly onDidChangeWaiting = this.waitingEmitter.event;

  /** What the user is waiting for, e.g. "completing…", if a request has taken more than a moment. */
  get waitingFor(): string | undefined {
    return [...this.waiting.values()].at(-1);
  }

  /** Shows [label] in the status bar while [result] is pending, once it has taken 300 ms. */
  private track<T>(label: string, result: vscode.ProviderResult<T>): vscode.ProviderResult<T> {
    if (!result || typeof (result as Thenable<T>).then !== 'function') return result;
    const id = this.nextWait++;
    const timer = setTimeout(() => {
      this.waiting.set(id, label);
      this.waitingEmitter.fire();
    }, 300);
    const done = () => {
      clearTimeout(timer);
      if (this.waiting.delete(id)) this.waitingEmitter.fire();
    };
    return Promise.resolve(result as Thenable<T>).finally(done);
  }

  get progress(): ServerProgress | undefined {
    return this._progress;
  }

  /** The server's own log. */
  showOutput(): void {
    this.outputChannel.show(true);
  }

  private readonly context: vscode.ExtensionContext;
  private readonly folder: vscode.WorkspaceFolder;
  private readonly modulesFile: string;
  /**
   * Kotlin and Java files created, changed or deleted outside the editor (git checkout, KSP output, the explorer); the
   * server re-reads them. One watcher serves every server restart.
   */
  private readonly sourceWatcher: vscode.FileSystemWatcher;

  constructor(context: vscode.ExtensionContext, folder: vscode.WorkspaceFolder, modulesFile: string) {
    this.context = context;
    this.folder = folder;
    this.modulesFile = modulesFile;
    this.sourceWatcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(folder, '**/*.{kt,java}'));
  }

  /** Makes the next completion list only suggestions of the expected type (smart completion). */
  async requestSmartCompletion(): Promise<void> {
    if (this.client?.isRunning()) await this.client.sendNotification('koda/smartCompletion');
  }

  get state(): ServerState {
    return this._state;
  }

  /** Index and caches live in VS Code's storage for this workspace, not in the project. */
  get indexDir(): string {
    const base = this.context.storageUri ?? this.context.globalStorageUri;
    return path.join(base.fsPath, 'kotlin-index');
  }

  /** Starts the server unless it is running. Does nothing until the first sync has written modules.json. */
  start(): Promise<void> {
    if (this.client?.isRunning()) return Promise.resolve();
    if (!fs.existsSync(this.modulesFile)) return Promise.resolve();
    this.starting ??= this.doStart().finally(() => (this.starting = undefined));
    return this.starting;
  }

  /** The server reads modules.json once at startup, so a new project model needs a restart. */
  async restart(): Promise<void> {
    await this.stop();
    await this.start();
  }

  /** Deletes the index, then restarts so the server indexes everything again. */
  async rebuildIndex(): Promise<void> {
    await this.stop();
    fs.rmSync(this.indexDir, { recursive: true, force: true });
    log().info(`Deleted the Kotlin index at ${this.indexDir}`);
    await this.start();
  }

  async stop(): Promise<void> {
    await this.starting?.catch(() => undefined);
    const client = this.client;
    this.client = undefined;
    if (client && client.state !== State.Stopped) {
      this.stopping = true;
      try {
        await client.stop(10_000);
      } catch (e) {
        log().warn(`Stopping the Kotlin server failed: ${String(e)}`);
      } finally {
        this.stopping = false;
      }
    }
    this._progress = undefined;
    this.setState('stopped');
  }

  private async doStart(): Promise<void> {
    this.setState('starting');
    try {
      const config = readConfig(this.folder.uri);
      const java = await findJavaRuntime(config.server.javaHome, this.folder.uri.fsPath, 'kodaStudio.server.javaHome');
      const launcher = path.join(this.context.extensionPath, 'server', 'kotlin-lsp', 'bin', process.platform === 'win32' ? 'kotlin-lsp.bat' : 'kotlin-lsp');
      if (!fs.existsSync(launcher)) throw new Error(`Kotlin server not found at ${launcher}. Run "npm run build:lsp".`);
      if (process.platform !== 'win32') ensureExecutable(launcher);
      fs.mkdirSync(this.indexDir, { recursive: true });
      log().info(`Starting the Kotlin server with Java ${java.version} (${java.source}), ${config.server.jvmArgs.join(' ')}`);

      const serverOptions: ServerOptions = {
        command: launcher,
        args: [],
        options: {
          cwd: this.folder.uri.fsPath,
          env: { ...process.env, JAVA_HOME: java.home, JAVA_OPTS: config.server.jvmArgs.join(' ') },
        },
      };
      const clientOptions: LanguageClientOptions = {
        documentSelector: [
          { scheme: 'file', language: 'kotlin' },
          // Library sources the server opened for go to definition (`AppCompatActivity.java`): navigation inside them.
          // Only that folder, so the server stays out of a Java project's own files.
          { scheme: 'file', language: 'java', pattern: `${this.indexDir.replace(/\\/g, '/')}/decompiled/**/*.java` },
        ],
        workspaceFolder: this.folder,
        outputChannel: this.outputChannel,
        initializationOptions: {
          modulesFile: this.modulesFile,
          indexDir: this.indexDir,
          composeStability: config.composeStability,
          namedArguments: config.namedArguments,
          parameterNameHints: config.parameterNameHints,
        },
        synchronize: { fileEvents: this.sourceWatcher },
        middleware: {
          provideCompletionItem: (document, position, context, token, next) =>
            this.track('completing…', next(document, position, context, token)),
          provideDefinition: (document, position, token, next) => this.track('finding the declaration…', next(document, position, token)),
          provideImplementation: (document, position, token, next) =>
            this.track('finding implementations…', next(document, position, token)),
          provideHover: (document, position, token, next) => this.track('loading the documentation…', next(document, position, token)),
        },
        // The server matches files by plain paths, so URIs must not be percent-encoded.
        uriConverters: {
          code2Protocol: (uri) => uri.toString(true),
          protocol2Code: (value) => vscode.Uri.parse(value),
        },
        errorHandler: {
          error: () => ({ action: ErrorAction.Continue }),
          closed: () => {
            if (this.stopping) return { action: CloseAction.DoNotRestart, handled: true };
            // Restart after a crash, but not in a loop: at most 3 times in 3 minutes.
            const now = Date.now();
            this.crashes = [...this.crashes.filter((t) => now - t < 180_000), now];
            log().warn('The Kotlin server stopped unexpectedly; see the "Kotlin Language Server" output.');
            if (this.crashes.length <= 3) return { action: CloseAction.Restart, handled: true };
            this.setState('failed');
            return {
              action: CloseAction.DoNotRestart,
              message: 'The Kotlin server keeps stopping. See the "Kotlin Language Server" output, then run "Koda: Restart Kotlin Server".',
            };
          },
        },
      };
      const client = new LanguageClient('kodaStudio.kotlin', 'Kotlin Language Server', serverOptions, clientOptions);
      this.client = client;
      this._progress = undefined;
      client.onNotification('koda/status', (progress: ServerProgress) => {
        this._progress = progress;
        this.progressEmitter.fire(progress);
        if (progress.state === 'ready' && progress.restartRecommended && Date.now() - this.lastIndexRestart > 10 * 60_000) {
          this.lastIndexRestart = Date.now();
          log().info('Indexing of libraries finished; restarting the Kotlin server once so analysis uses the complete index.');
          void this.restart().catch(() => undefined);
        }
      });
      await client.start();
      this.setState('running');
    } catch (e) {
      this.setState('failed');
      log().error(`Kotlin server failed to start: ${e instanceof Error ? e.message : String(e)}`);
      throw e;
    }
  }

  private setState(state: ServerState): void {
    this._state = state;
    this.stateEmitter.fire(state);
  }

  dispose(): void {
    void this.stop();
    this.stateEmitter.dispose();
    this.progressEmitter.dispose();
    this.waitingEmitter.dispose();
    this.sourceWatcher.dispose();
    this.outputChannel.dispose();
  }
}

/** VSIX installs don't always keep the executable bit of the start script. */
function ensureExecutable(file: string): void {
  try {
    const mode = fs.statSync(file).mode;
    if ((mode & 0o111) === 0) fs.chmodSync(file, mode | 0o755);
  } catch (e) {
    log().warn(`Could not make ${file} executable: ${String(e)}`);
  }
}
