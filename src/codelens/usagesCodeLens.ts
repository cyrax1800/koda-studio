// "N usages" / "N implementations" lenses, computed through VS Code's reference and implementation providers,
// which the Kotlin LSP serves. Lenses are resolved lazily, so only visible declarations cost a search.
// Searches share the server with completion, so none starts while the user is typing.
import fs from 'node:fs';
import * as vscode from 'vscode';

import { readConfig } from '../config.ts';
import { log } from '../log.ts';
import { implementationsTitle, isImportLine, lensTargets, usagesTitle, type LensTarget } from './symbolFilter.ts';

type Mode = 'usages' | 'implementations';

class UsageLens extends vscode.CodeLens {
  readonly uri: vscode.Uri;
  readonly target: LensTarget;
  readonly mode: Mode;

  constructor(uri: vscode.Uri, target: LensTarget, mode: Mode) {
    const position = new vscode.Position(target.position.line, target.position.character);
    super(new vscode.Range(position, position));
    this.uri = uri;
    this.target = target;
    this.mode = mode;
  }

  get key(): string {
    return `${this.uri.toString()}|${this.target.container}|${this.target.name}|${this.target.kind}|${this.mode}`;
  }
}

/** Results found shortly after a sync may come from an index that is still being built. */
const INDEXING_GRACE_MS = 3 * 60_000;
/** How long the user must stop typing before a reference search starts. */
const SEARCH_QUIET_MS = 1500;
/** How long the user must stop typing before the document symbols are requested. */
const SYMBOLS_QUIET_MS = 500;

interface CacheEntry {
  command: vscode.Command;
  generation: number;
}

export class UsagesCodeLensProvider implements vscode.CodeLensProvider<UsageLens>, vscode.Disposable {
  private readonly changed = new vscode.EventEmitter<void>();
  private readonly cache = new Map<string, CacheEntry>();
  private readonly revalidating = new Set<string>();
  private readonly limiter = new Limiter(2);
  private readonly typing = new TypingTracker();
  private readonly disposables: vscode.Disposable[] = [this.typing];
  private syncedAt = Date.now();
  /** Bumped on save: cached counts stay visible but are recomputed in the background. */
  private generation = 0;

  readonly onDidChangeCodeLenses = this.changed.event;

  constructor() {
    this.disposables.push(
      vscode.workspace.onDidSaveTextDocument((document) => {
        if (document.languageId === 'kotlin' || document.languageId === 'java') this.generation++;
      }),
      vscode.workspace.onDidChangeConfiguration((e) => {
        if (e.affectsConfiguration('kodaStudio.codeLens')) this.refresh();
      }),
    );
  }

  /** Called after a sync: the server re-imports, so earlier counts may be wrong. */
  onSynced(): void {
    this.syncedAt = Date.now();
    this.refresh();
  }

  refresh(): void {
    this.cache.clear();
    this.changed.fire();
  }

  async provideCodeLenses(document: vscode.TextDocument, token: vscode.CancellationToken): Promise<UsageLens[]> {
    const config = readConfig(document.uri).codeLens;
    if (!config.enabled || document.uri.scheme !== 'file') return [];
    // Project files only: a library's source opened by go to definition (kept in the index folder) gets none.
    if (!vscode.workspace.getWorkspaceFolder(document.uri)) return [];
    // VS Code asks again after every edit and cancels the previous request, so this waits out a typing burst.
    if (!(await this.typing.quiet(SYMBOLS_QUIET_MS, token))) return [];
    const symbols = await vscode.commands.executeCommand<(vscode.DocumentSymbol | vscode.SymbolInformation)[] | undefined>(
      'vscode.executeDocumentSymbolProvider',
      document.uri,
    );
    if (token.isCancellationRequested || !symbols || symbols.length === 0 || !('selectionRange' in symbols[0]!)) return [];
    const targets = lensTargets(symbols as vscode.DocumentSymbol[], (line) =>
      line < document.lineCount ? document.lineAt(line).text : '',
    );
    // Only the lenses on screen are resolved, so even a generated file of 5,000 constants is fine.
    if (config.maxSymbolsPerFile > 0 && targets.length > config.maxSymbolsPerFile) return [];
    const lenses: UsageLens[] = [];
    for (const target of targets) {
      lenses.push(new UsageLens(document.uri, target, 'usages'));
      if (config.implementations && target.implementable) lenses.push(new UsageLens(document.uri, target, 'implementations'));
    }
    const stale: UsageLens[] = [];
    for (const lens of lenses) {
      const cached = this.cache.get(lens.key);
      if (!cached) continue;
      lens.command = cached.command;
      if (cached.generation !== this.generation) stale.push(lens);
    }
    if (stale.length > 0) void this.revalidate(stale);
    return lenses;
  }

  /** Recomputes counts cached before the last save while they stay on screen, then redraws if any changed. */
  private async revalidate(lenses: UsageLens[]): Promise<void> {
    const mine = lenses.filter((lens) => !this.revalidating.has(lens.key));
    for (const lens of mine) this.revalidating.add(lens.key);
    let changed = false;
    try {
      await Promise.all(
        mine.map((lens) =>
          this.limiter.run(async () => {
            if (!(await this.typing.quiet(SEARCH_QUIET_MS))) return;
            const visible = vscode.window.visibleTextEditors.some((e) => e.document.uri.toString() === lens.uri.toString());
            if (!visible) return;
            const before = this.cache.get(lens.key)?.command.title;
            const command = await this.compute(lens).catch(() => undefined);
            if (command && command.title !== before) changed = true;
          }),
        ),
      );
    } finally {
      for (const lens of mine) this.revalidating.delete(lens.key);
    }
    if (changed) this.changed.fire();
  }

  async resolveCodeLens(lens: UsageLens, token: vscode.CancellationToken): Promise<UsageLens> {
    const cached = this.cache.get(lens.key);
    if (cached) {
      lens.command = cached.command;
      return lens;
    }
    try {
      const command = await this.limiter.run(async () =>
        (await this.typing.quiet(SEARCH_QUIET_MS, token)) ? this.compute(lens) : undefined,
      );
      lens.command = command ?? { title: '…', command: '' };
    } catch (e) {
      log().debug(`CodeLens for ${lens.target.name} failed: ${String(e)}`);
      lens.command = { title: '…', command: '' };
    }
    return lens;
  }

  private async compute(lens: UsageLens): Promise<vscode.Command | undefined> {
    const generation = this.generation;
    const position = lens.range.start;
    const providerCommand = lens.mode === 'usages' ? 'vscode.executeReferenceProvider' : 'vscode.executeImplementationProvider';
    const results =
      (await vscode.commands.executeCommand<(vscode.Location | vscode.LocationLink)[] | undefined>(providerCommand, lens.uri, position)) ??
      [];
    const files = new Map<string, string[]>();
    const locations = results.map(toLocation).filter((location) => {
      if (location.uri.toString() === lens.uri.toString() && location.range.contains(position)) return false;
      return lens.mode === 'implementations' || !isImportLine(lineText(location, files));
    });
    const command: vscode.Command = {
      title: lens.mode === 'usages' ? usagesTitle(locations.length) : implementationsTitle(locations.length),
      command: locations.length > 0 ? 'editor.action.showReferences' : '',
      arguments: locations.length > 0 ? [lens.uri, position, locations] : undefined,
    };
    const maybeStillIndexing = locations.length === 0 && Date.now() - this.syncedAt < INDEXING_GRACE_MS;
    if (!maybeStillIndexing) this.cache.set(lens.key, { command, generation });
    return command;
  }

  dispose(): void {
    this.changed.dispose();
    for (const d of this.disposables) d.dispose();
  }
}

function toLocation(value: vscode.Location | vscode.LocationLink): vscode.Location {
  return 'targetUri' in value ? new vscode.Location(value.targetUri, value.targetSelectionRange ?? value.targetRange) : value;
}

function lineText(location: vscode.Location, files: Map<string, string[]>): string {
  const line = location.range.start.line;
  const key = location.uri.toString();
  const open = vscode.workspace.textDocuments.find((d) => d.uri.toString() === key);
  if (open) return line < open.lineCount ? open.lineAt(line).text : '';
  if (location.uri.scheme !== 'file') return '';
  let lines = files.get(key);
  if (!lines) {
    try {
      lines = fs.readFileSync(location.uri.fsPath, 'utf8').split('\n');
    } catch {
      lines = [];
    }
    files.set(key, lines);
  }
  return lines[line] ?? '';
}

/** Remembers when a Kotlin or Java document was last edited. */
class TypingTracker implements vscode.Disposable {
  private lastEdit = 0;
  private readonly subscription = vscode.workspace.onDidChangeTextDocument((e) => {
    if (e.contentChanges.length > 0 && (e.document.languageId === 'kotlin' || e.document.languageId === 'java')) {
      this.lastEdit = Date.now();
    }
  });

  /** Resolves to true once no edit happened for `ms`, or to false when cancelled first. */
  async quiet(ms: number, token?: vscode.CancellationToken): Promise<boolean> {
    for (;;) {
      if (token?.isCancellationRequested) return false;
      const wait = this.lastEdit + ms - Date.now();
      if (wait <= 0) return true;
      await new Promise((resolve) => setTimeout(resolve, wait));
    }
  }

  dispose(): void {
    this.subscription.dispose();
  }
}

/** Runs at most `size` tasks at a time. */
class Limiter {
  private active = 0;
  private readonly queue: (() => void)[] = [];
  private readonly size: number;

  constructor(size: number) {
    this.size = size;
  }

  async run<T>(task: () => Promise<T> | T): Promise<T> {
    if (this.active >= this.size) await new Promise<void>((resolve) => this.queue.push(resolve));
    this.active++;
    try {
      return await task();
    } finally {
      this.active--;
      this.queue.shift()?.();
    }
  }
}
