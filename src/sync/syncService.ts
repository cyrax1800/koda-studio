import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import * as vscode from 'vscode';

import { readConfig, type GenerateSources } from '../config.ts';
import { findJavaRuntime } from '../gradle/javaHome.ts';
import type { ProjectModel, SyncProblem } from '../gradle/modelTypes.ts';
import { runSync, SyncCancelled } from '../gradle/syncRunner.ts';
import { log } from '../log.ts';
import { toModulesJson } from '../server/modulesJson.ts';
import { runningDaemons, stopDaemons } from './daemons.ts';

export type SyncState =
  | { kind: 'idle' }
  | { kind: 'syncing'; message: string }
  | { kind: 'synced'; variant: string | undefined; problems: number; at: number }
  | { kind: 'failed'; message: string };

export class SyncService implements vscode.Disposable {
  readonly folder: vscode.WorkspaceFolder;
  private readonly context: vscode.ExtensionContext;
  private readonly stateEmitter = new vscode.EventEmitter<SyncState>();
  private readonly syncedEmitter = new vscode.EventEmitter<{ modulesChanged: boolean }>();
  private current: Promise<boolean> | undefined;
  private abort: AbortController | undefined;
  private lastWrittenHash: string | undefined;
  private _missingLibraries = 0;
  private model: ProjectModel | undefined;
  private _state: SyncState = { kind: 'idle' };
  /** Gradle and Kotlin daemons our syncs started, stopped when the window closes. Kept across reloads. */
  private readonly startedDaemons: Set<number>;

  /** Fires on every state change. */
  readonly onDidChangeState = this.stateEmitter.event;
  /** Fires after a sync or refresh, with whether modules.json changed. */
  readonly onDidSync = this.syncedEmitter.event;

  constructor(context: vscode.ExtensionContext, folder: vscode.WorkspaceFolder) {
    this.context = context;
    this.folder = folder;
    this.startedDaemons = new Set(context.workspaceState.get<number[]>(DAEMONS_KEY, []));
  }

  get state(): SyncState {
    return this._state;
  }

  get root(): string {
    return this.folder.uri.fsPath;
  }

  get dataDir(): string {
    const dir = path.join(this.root, '.gradle', 'koda');
    // Synced before the rename to Koda Studio: keep that sync instead of asking for a new one.
    const legacy = path.join(this.root, '.gradle', 'android-bridge');
    if (!fs.existsSync(dir) && fs.existsSync(legacy)) {
      try {
        fs.renameSync(legacy, dir);
      } catch {
        // Another window may have moved it already.
      }
    }
    return dir;
  }

  get modelFile(): string {
    return path.join(this.dataDir, 'model.json');
  }

  /** The module graph our Kotlin server reads. */
  get modulesFile(): string {
    return path.join(this.dataDir, 'modules.json');
  }

  /** Libraries of the last converted model whose jars are gone from the Gradle cache: the model needs a sync. */
  get missingLibraries(): number {
    return this._missingLibraries;
  }

  get running(): boolean {
    return this.current !== undefined;
  }

  /** The last synced model, read from disk after a window reload. */
  lastModel(): ProjectModel | undefined {
    if (!this.model && fs.existsSync(this.modelFile)) {
      try {
        this.model = JSON.parse(fs.readFileSync(this.modelFile, 'utf8')) as ProjectModel;
      } catch (e) {
        log().warn(`Ignoring unreadable ${this.modelFile}: ${String(e)}`);
      }
    }
    return this.model;
  }

  /** Runs a Gradle sync unless one is already running. Resolves to whether it succeeded. */
  sync(options: { generate?: GenerateSources; reason: string }): Promise<boolean> {
    if (this.current) return this.current;
    const run = this.runSync(options).finally(() => {
      this.current = undefined;
      this.abort = undefined;
    });
    this.current = run;
    return run;
  }

  cancel(): void {
    this.abort?.abort();
  }

  /** Re-converts the last model without Gradle, e.g. after a build created new generated folders. */
  refreshFromModel(reason: string): void {
    if (this.running) return;
    const model = this.lastModel();
    if (!model) return;
    if (this.writeModulesJson(model)) {
      log().info(`Updated modules.json (${reason})`);
      this.syncedEmitter.fire({ modulesChanged: true });
    }
  }

  private async runSync(options: { generate?: GenerateSources; reason: string }): Promise<boolean> {
    const config = readConfig(this.folder.uri);
    const generate = options.generate ?? config.generateSources;
    // The first sync is followed by indexing everything, which needs the memory the daemon holds.
    const firstSync = !this.lastModel();
    const stopDaemon = config.stopGradleDaemon === 'always' || (config.stopGradleDaemon === 'afterFirstSync' && firstSync);
    this.abort = new AbortController();
    const signal = this.abort.signal;
    const started = Date.now();
    log().info(`Sync started (${options.reason}), variant=${config.variant ?? '<default>'}, generate=${generate}`);
    const daemonsBefore = runningDaemons();
    const rememberDaemons = () => {
      for (const pid of runningDaemons()) if (!daemonsBefore.has(pid)) this.startedDaemons.add(pid);
      void this.context.workspaceState.update(DAEMONS_KEY, [...this.startedDaemons]);
    };

    return vscode.window.withProgress<boolean>(
      { location: vscode.ProgressLocation.Notification, title: 'Koda: syncing', cancellable: true },
      async (progress, token) => {
        token.onCancellationRequested(() => this.abort?.abort());
        const report = (message: string) => {
          progress.report({ message });
          this.setState({ kind: 'syncing', message });
        };
        report('starting Gradle…');
        try {
          const java = await findJavaRuntime(config.javaHome, this.root);
          log().info(`Using Java ${java.version} from ${java.source}: ${java.home}`);
          const modelPath = await runSync(
            {
              extensionPath: this.context.extensionPath,
              projectDir: this.root,
              outFile: this.modelFile,
              variant: config.variant,
              generate,
              gradleArguments: config.gradleArguments,
              java,
              stopDaemon,
              downloadSources: config.librarySources === 'download',
            },
            {
              progress: (message) => report(message),
              phase: (name, tasks) => {
                log().info(`Phase: ${name}${tasks.length ? ` (${tasks.length} tasks)` : ''}`);
                if (name === 'generate') report('generating sources…');
                if (name === 'stop-daemon') report('stopping the Gradle daemon to free memory for indexing…');
              },
              warning: (message) => log().warn(message),
              output: (line) => log().trace(line),
            },
            signal,
          );
          const model = JSON.parse(fs.readFileSync(modelPath, 'utf8')) as ProjectModel;
          this.model = model;
          const modulesChanged = this.writeModulesJson(model);
          const problems = collectProblems(model);
          for (const problem of problems) log().warn(`${problem.project ?? ''} [${problem.stage}] ${problem.message}`);
          const variant = appVariant(model);
          this.setState({ kind: 'synced', variant, problems: problems.length, at: Date.now() });
          log().info(`Sync finished in ${((Date.now() - started) / 1000).toFixed(1)}s, ${problems.length} problems`);
          if (problems.length > 0) {
            void showProblems(`Android sync finished with ${problems.length} problem(s).`);
          }
          this.syncedEmitter.fire({ modulesChanged });
          return true;
        } catch (e) {
          if (e instanceof SyncCancelled) {
            log().info('Sync cancelled');
            this.setState(this.model ? { kind: 'synced', variant: appVariant(this.model), problems: 0, at: Date.now() } : { kind: 'idle' });
            return false;
          }
          const message = e instanceof Error ? e.message : String(e);
          log().error(`Sync failed: ${message}`);
          this.setState({ kind: 'failed', message });
          void showProblems(`Android sync failed: ${firstLine(message)}`);
          return false;
        }
      },
    ).then((ok) => {
      rememberDaemons();
      return ok;
    });
  }

  /** Stops the daemons our syncs started (window closing, project closed). */
  stopStartedDaemons(): void {
    if (this.startedDaemons.size === 0) return;
    const stopped = stopDaemons(this.startedDaemons);
    if (stopped.length > 0) log().info(`Stopped ${stopped.length} JVM daemon(s) started by Koda syncs: ${stopped.join(', ')}`);
    this.startedDaemons.clear();
    void this.context.workspaceState.update(DAEMONS_KEY, []);
  }

  /** Deletes the synced model, so the next sync is a first one (Clear Caches). */
  clearData(): void {
    this.model = undefined;
    this.lastWrittenHash = undefined;
    fs.rmSync(this.dataDir, { recursive: true, force: true });
    this.setState({ kind: 'idle' });
  }

  /** Writes modules.json when its content changed. Returns true if it did. */
  private writeModulesJson(model: ProjectModel): boolean {
    const config = readConfig(this.folder.uri);
    const { modules, warnings, missingLibraries } = toModulesJson(model, {
      librarySources: config.librarySources,
      androidSdkSources: config.androidSdkSources,
    });
    for (const warning of warnings) log().warn(warning);
    this._missingLibraries = missingLibraries;
    const text = JSON.stringify(modules);
    const hash = createHash('sha256').update(text).digest('hex');
    if (this.lastWrittenHash === undefined && fs.existsSync(this.modulesFile)) {
      this.lastWrittenHash = createHash('sha256').update(fs.readFileSync(this.modulesFile, 'utf8')).digest('hex');
    }
    if (hash === this.lastWrittenHash) return false;
    fs.mkdirSync(this.dataDir, { recursive: true });
    const temp = `${this.modulesFile}.tmp`;
    fs.writeFileSync(temp, text);
    fs.renameSync(temp, this.modulesFile);
    this.lastWrittenHash = hash;
    return true;
  }

  private setState(state: SyncState): void {
    this._state = state;
    this.stateEmitter.fire(state);
  }

  dispose(): void {
    this.abort?.abort();
    if (readConfig(this.folder.uri).stopDaemonsOnClose) this.stopStartedDaemons();
    this.stateEmitter.dispose();
    this.syncedEmitter.dispose();
  }
}

const DAEMONS_KEY = 'koda.startedDaemons';

function collectProblems(model: ProjectModel): SyncProblem[] {
  return [...model.problems, ...model.projects.flatMap((p) => p.extras?.problems ?? [])];
}

/** The variant of the first application module, shown in the status bar. */
export function appVariant(model: ProjectModel): string | undefined {
  const apps = model.projects.filter((p) => p.android?.projectType === 'APPLICATION');
  return (apps[0] ?? model.projects.find((p) => p.android))?.android?.selectedVariant;
}

async function showProblems(message: string): Promise<void> {
  const show = 'Show Log';
  if ((await vscode.window.showWarningMessage(message, show)) === show) log().show();
}

function firstLine(text: string): string {
  const line = text.split('\n')[0] ?? text;
  return line.length > 300 ? `${line.slice(0, 300)}…` : line;
}
