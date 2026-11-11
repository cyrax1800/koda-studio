import * as vscode from 'vscode';

import type { KotlinServer, ServerProgress, ServerState } from '../server/languageClient.ts';
import type { SyncService, SyncState } from '../sync/syncService.ts';

export class StatusBar implements vscode.Disposable {
  private readonly item = vscode.window.createStatusBarItem('kodaStudio.status', vscode.StatusBarAlignment.Left, 10);

  constructor(service: SyncService) {
    this.item.name = 'Koda Studio';
    this.item.command = 'kodaStudio.statusMenu';
    service.onDidChangeState((state) => this.render(state));
    this.render(service.state, service);
    this.item.show();
  }

  private render(state: SyncState, service?: SyncService): void {
    switch (state.kind) {
      case 'idle': {
        const variant = service?.lastModel() ? 'not synced this session' : 'not synced';
        this.item.text = '$(sync) Android';
        this.item.tooltip = `Koda Studio: ${variant}. Click to sync.`;
        this.item.backgroundColor = undefined;
        break;
      }
      case 'syncing':
        this.item.text = '$(sync~spin) Koda: syncing…';
        this.item.tooltip = state.message;
        this.item.backgroundColor = undefined;
        break;
      case 'synced':
        this.item.text = `${state.problems > 0 ? '$(warning)' : '$(check)'} Koda: ${state.variant ?? 'synced'}`;
        this.item.tooltip =
          `Synced at ${new Date(state.at).toLocaleTimeString()}` +
          (state.problems > 0 ? ` with ${state.problems} problem(s), see the log` : '') +
          '. Click for actions.';
        this.item.backgroundColor = undefined;
        break;
      case 'failed':
        this.item.text = '$(error) Koda: sync failed';
        this.item.tooltip = state.message;
        this.item.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
        break;
    }
  }

  dispose(): void {
    this.item.dispose();
  }
}

/**
 * The Kotlin server's state next to the sync item: starting, loading the project, indexing (files done of those found
 * so far), analysing a file, or ready. Until indexing finishes, classes that aren't imported yet may be missing from
 * completion. Click for the server's log.
 */
export class ServerStatusBar implements vscode.Disposable {
  private readonly item = vscode.window.createStatusBarItem('kodaStudio.server', vscode.StatusBarAlignment.Left, 9);
  private readonly subscriptions: vscode.Disposable[] = [];
  private readonly server: KotlinServer;

  constructor(server: KotlinServer) {
    this.server = server;
    this.item.name = 'Kotlin Server';
    this.item.command = 'kodaStudio.showServerLog';
    this.subscriptions.push(
      server.onDidChangeState(() => this.render()),
      server.onDidChangeProgress(() => this.render()),
      server.onDidChangeWaiting(() => this.render()),
    );
    this.render();
  }

  private render(): void {
    const text = describe(this.server.state, this.server.progress, this.server.waitingFor);
    if (!text) {
      this.item.hide();
      return;
    }
    this.item.text = text.text;
    this.item.tooltip = text.tooltip;
    const failed = this.server.state === 'failed' || this.server.progress?.state === 'failed';
    this.item.backgroundColor = failed ? new vscode.ThemeColor('statusBarItem.errorBackground') : undefined;
    this.item.show();
  }

  dispose(): void {
    this.subscriptions.forEach((s) => s.dispose());
    this.item.dispose();
  }
}

export function describe(
  state: ServerState,
  progress: ServerProgress | undefined,
  waitingFor?: string,
): { text: string; tooltip: string } | undefined {
  const status = describeServer(state, progress);
  if (!status || !waitingFor || state !== 'running') return status;
  // What you're waiting for, and why it may take long: the project is still loading or being indexed.
  const busy = progress && (progress.state !== 'ready' || progress.preparing) ? ` (${status.text.replace(/^\$\([^)]*\) Kotlin: /, '')})` : '';
  return { text: `$(loading~spin) Kotlin: ${waitingFor}${busy}`, tooltip: `${status.tooltip}` };
}

function describeServer(state: ServerState, progress: ServerProgress | undefined): { text: string; tooltip: string } | undefined {
  switch (state) {
    case 'stopped':
      return undefined;
    case 'failed':
      return { text: '$(error) Kotlin: stopped', tooltip: 'The Kotlin server stopped. Click for its log.' };
    case 'starting':
      return { text: '$(loading~spin) Kotlin: starting…', tooltip: 'Starting the Kotlin server.' };
    case 'running':
      break;
  }
  if (progress?.state === 'failed') {
    return { text: '$(error) Kotlin: failed', tooltip: `${progress.message ?? 'The Kotlin server could not load the project.'} Click for its log.` };
  }
  if (!progress || progress.state === 'loading') {
    return { text: '$(loading~spin) Kotlin: loading project…', tooltip: 'Reading the modules and libraries of the project.' };
  }
  if (progress.state === 'indexing') {
    const counts = progress.toIndex > 0 ? ` ${progress.indexed.toLocaleString()}/${progress.toIndex.toLocaleString()}` : '…';
    return {
      text: `$(loading~spin) Kotlin: indexing${counts}`,
      tooltip: 'Indexing the project and its libraries. Until it finishes, classes and functions that are not imported yet may be missing from completion.',
    };
  }
  if (progress.preparing) {
    const name = moduleName(progress.preparing);
    const count = progress.modulesPrepared ? ` (${progress.modulesPrepared.toLocaleString()} modules)` : '';
    return {
      text: `$(loading~spin) Kotlin: preparing ${name}${count}`,
      tooltip:
        `Setting up ${progress.preparing} for analysis, with its compiler plugins. The first analysis after a start sets ` +
        'up the file\'s module and every module it depends on; later ones reuse them.',
    };
  }
  if (progress.analyzing) {
    return { text: `$(loading~spin) Kotlin: analyzing ${progress.analyzing}`, tooltip: `Checking ${progress.analyzing} for errors.` };
  }
  return { text: '$(check) Kotlin', tooltip: 'The Kotlin server is ready. Click for its log.' };
}

/** `myapp.features.feature_home.prodDebug` → `feature_home`; `app.main` → `app`. */
export function moduleName(id: string): string {
  const parts = id.split('.');
  return parts.length >= 3 ? parts[parts.length - 2]! : parts[0]!;
}
