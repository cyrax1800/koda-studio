import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import * as vscode from 'vscode';

import { readConfig, type GenerateSources } from '../config.ts';
import { findJavaRuntime } from '../gradle/javaHome.ts';
import type { ProjectModel, SyncProblem } from '../gradle/modelTypes.ts';
import { runSync, SyncCancelled } from '../gradle/syncRunner.ts';
import {
  ensureProjectsSetting,
  kotlinExtensionInstalled,
  offerKotlinExtensionInstall,
  projectsSettingPointsTo,
  reloadKotlinWorkspace,
  restartKotlinServer,
  warnAboutIntellijExtension,
} from '../intellij/jetbrainsKotlin.ts';
import { toWorkspaceJson } from '../intellij/workspaceJson.ts';
import { log } from '../log.ts';

export type SyncState =
  | { kind: 'idle' }
  | { kind: 'syncing'; message: string }
  | { kind: 'synced'; variant: string | undefined; problems: number; at: number }
  | { kind: 'failed'; message: string };

const CONSENT_KEY = 'androidBridge.configuredKotlinServer';
const EMPTY_WORKSPACE = { modules: [], libraries: [], sdks: [], kotlinSettings: [], javaSettings: [] };

export class SyncService implements vscode.Disposable {
  readonly folder: vscode.WorkspaceFolder;
  private readonly context: vscode.ExtensionContext;
  private readonly stateEmitter = new vscode.EventEmitter<SyncState>();
  private readonly syncedEmitter = new vscode.EventEmitter<void>();
  private current: Promise<boolean> | undefined;
  private abort: AbortController | undefined;
  private lastWrittenHash: string | undefined;
  private model: ProjectModel | undefined;
  private _state: SyncState = { kind: 'idle' };

  /** Fires on every state change. */
  readonly onDidChangeState = this.stateEmitter.event;
  /** Fires after a new workspace.json was handed to the Kotlin server. */
  readonly onDidSync = this.syncedEmitter.event;

  constructor(context: vscode.ExtensionContext, folder: vscode.WorkspaceFolder) {
    this.context = context;
    this.folder = folder;
  }

  get state(): SyncState {
    return this._state;
  }

  get root(): string {
    return this.folder.uri.fsPath;
  }

  get dataDir(): string {
    return path.join(this.root, '.gradle', 'android-bridge');
  }

  get modelFile(): string {
    return path.join(this.dataDir, 'model.json');
  }

  get workspaceJsonFile(): string {
    return path.join(this.dataDir, 'workspace.json');
  }

  get running(): boolean {
    return this.current !== undefined;
  }

  get configured(): boolean {
    return this.context.workspaceState.get<boolean>(CONSENT_KEY) === true;
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

  /**
   * Connects the Kotlin server to our workspace.json, asking once per workspace. Before the first sync the file is an
   * empty stub, which keeps the server from starting its own Gradle import.
   */
  async ensureConfigured(): Promise<boolean> {
    if (!kotlinExtensionInstalled()) {
      void offerKotlinExtensionInstall();
      return false;
    }
    const uri = vscode.Uri.file(this.workspaceJsonFile);
    if (projectsSettingPointsTo(uri) && fs.existsSync(this.workspaceJsonFile)) return true;
    if (!this.configured && this.context.extensionMode !== vscode.ExtensionMode.Test) {
      const setUp = 'Set Up';
      const choice = await vscode.window.showInformationMessage(
        `Set up Android support for "${this.folder.name}"? Android Bridge syncs the project with Gradle and connects it to ` +
          'Kotlin by JetBrains. It adds an "intellij.projects" entry to .vscode/settings.json and keeps the project model ' +
          'in .gradle/android-bridge/.',
        setUp,
      );
      if (choice !== setUp) return false;
    }
    await this.context.workspaceState.update(CONSENT_KEY, true);
    void warnAboutIntellijExtension();
    if (!fs.existsSync(this.workspaceJsonFile)) {
      fs.mkdirSync(this.dataDir, { recursive: true });
      fs.writeFileSync(this.workspaceJsonFile, JSON.stringify(EMPTY_WORKSPACE));
    }
    const changed = await ensureProjectsSetting(uri);
    // A server that already started its own Gradle import keeps it until restarted with the new setting.
    if (changed) await restartKotlinServer();
    return true;
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
    if (this.writeWorkspaceJson(model)) {
      log().info(`Updated workspace.json (${reason})`);
      reloadKotlinWorkspace();
      this.syncedEmitter.fire();
    }
  }

  private async runSync(options: { generate?: GenerateSources; reason: string }): Promise<boolean> {
    if (!(await this.ensureConfigured())) return false;
    const config = readConfig(this.folder.uri);
    const generate = options.generate ?? config.generateSources;
    this.abort = new AbortController();
    const signal = this.abort.signal;
    const started = Date.now();
    log().info(`Sync started (${options.reason}), variant=${config.variant ?? '<default>'}, generate=${generate}`);

    return vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'Android: syncing', cancellable: true },
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
            },
            {
              progress: (message) => report(message),
              phase: (name, tasks) => {
                log().info(`Phase: ${name}${tasks.length ? ` (${tasks.length} tasks)` : ''}`);
                if (name === 'generate') report('generating sources…');
              },
              warning: (message) => log().warn(message),
              output: (line) => log().trace(line),
            },
            signal,
          );
          const model = JSON.parse(fs.readFileSync(modelPath, 'utf8')) as ProjectModel;
          this.model = model;
          this.writeWorkspaceJson(model);
          // Reload even when the JSON is byte-identical: generated files may have changed underneath it.
          reloadKotlinWorkspace();
          const problems = collectProblems(model);
          for (const problem of problems) log().warn(`${problem.project ?? ''} [${problem.stage}] ${problem.message}`);
          const variant = appVariant(model);
          this.setState({ kind: 'synced', variant, problems: problems.length, at: Date.now() });
          log().info(`Sync finished in ${((Date.now() - started) / 1000).toFixed(1)}s, ${problems.length} problems`);
          if (problems.length > 0) {
            void showProblems(`Android sync finished with ${problems.length} problem(s).`);
          }
          this.syncedEmitter.fire();
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
    );
  }

  /** Writes workspace.json when its content changed. Returns true if it did. */
  private writeWorkspaceJson(model: ProjectModel): boolean {
    const config = readConfig(this.folder.uri);
    const { workspace, warnings } = toWorkspaceJson(model, {
      librarySources: config.librarySources,
      androidSdkSources: config.androidSdkSources,
    });
    for (const warning of warnings) log().warn(warning);
    const text = JSON.stringify(workspace);
    const hash = createHash('sha256').update(text).digest('hex');
    if (this.lastWrittenHash === undefined && fs.existsSync(this.workspaceJsonFile)) {
      this.lastWrittenHash = createHash('sha256').update(fs.readFileSync(this.workspaceJsonFile, 'utf8')).digest('hex');
    }
    if (hash === this.lastWrittenHash) return false;
    fs.mkdirSync(this.dataDir, { recursive: true });
    const temp = `${this.workspaceJsonFile}.tmp`;
    fs.writeFileSync(temp, text);
    fs.renameSync(temp, this.workspaceJsonFile);
    this.lastWrittenHash = hash;
    return true;
  }

  private setState(state: SyncState): void {
    this._state = state;
    this.stateEmitter.fire(state);
  }

  dispose(): void {
    this.abort?.abort();
    this.stateEmitter.dispose();
    this.syncedEmitter.dispose();
  }
}

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
