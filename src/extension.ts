import fs from 'node:fs';
import path from 'node:path';
import * as vscode from 'vscode';

import { UsagesCodeLensProvider } from './codelens/usagesCodeLens.ts';
import { log } from './log.ts';
import { KotlinServer } from './server/languageClient.ts';
import { findAndroidRoot } from './sync/androidRoot.ts';
import { appVariant, SyncService } from './sync/syncService.ts';
import { watchBuildOutputs, watchGradleFiles } from './sync/watchers.ts';
import { ServerStatusBar, StatusBar } from './ui/statusBar.ts';

/** Returned from activate() for the end-to-end tests. */
export interface KodaApi {
  syncService: SyncService | undefined;
  server: KotlinServer | undefined;
  codeLens: UsagesCodeLensProvider;
}

export async function activate(context: vscode.ExtensionContext): Promise<KodaApi> {
  const lenses = new UsagesCodeLensProvider();
  context.subscriptions.push(lenses, vscode.languages.registerCodeLensProvider({ language: 'kotlin', scheme: 'file' }, lenses));

  const folder = findAndroidRoot();
  const service = folder ? new SyncService(context, folder) : undefined;
  const server = folder && service ? new KotlinServer(context, folder, service.modulesFile) : undefined;
  const withService = (action: (s: SyncService) => unknown) => () => {
    if (!service) {
      void vscode.window.showInformationMessage('No Android Gradle project found in this workspace.');
      return;
    }
    return action(service);
  };

  context.subscriptions.push(
    vscode.commands.registerCommand('kodaStudio.sync', withService((s) => s.sync({ reason: 'command' }))),
    vscode.commands.registerCommand('kodaStudio.generateSources', withService((s) => s.sync({ generate: 'full', reason: 'generate sources' }))),
    vscode.commands.registerCommand('kodaStudio.selectVariant', withService((s) => selectVariant(s))),
    vscode.commands.registerCommand('kodaStudio.showLog', () => log().show()),
    vscode.commands.registerCommand('kodaStudio.restartServer', withService(() => server?.restart())),
    vscode.commands.registerCommand('kodaStudio.rebuildIndex', withService(() => server?.rebuildIndex())),
    // IntelliJ's smart completion: only suggestions of the expected type.
    vscode.commands.registerCommand('kodaStudio.smartCompletion', async () => {
      await server?.requestSmartCompletion();
      await vscode.commands.executeCommand('editor.action.triggerSuggest');
    }),
    vscode.commands.registerCommand('kodaStudio.statusMenu', withService((s) => statusMenu(s))),
    vscode.commands.registerCommand('kodaStudio.showServerLog', () => server?.showOutput()),
    vscode.commands.registerCommand('kodaStudio.clearCaches', withService((s) => server && clearCaches(s, server))),
  );
  const api: KodaApi = { syncService: service, server, codeLens: lenses };
  if (!service || !server) return api;

  log().info(`Android Gradle project: ${service.root}`);
  void warnAboutOtherKotlinServers();
  context.subscriptions.push(
    service,
    new StatusBar(service),
    new ServerStatusBar(server),
    watchGradleFiles(service),
    watchBuildOutputs(service),
    server,
    service.onDidSync(() => {
      lenses.onSynced();
      // The server reads modules.json and indexes generated folders only at startup.
      void (server.state === 'stopped' ? server.start() : server.restart()).catch(() => undefined);
    }),
  );

  if (!service.lastModel()) {
    // First open: Android Studio syncs right away too. The setup prompt in ensureConfigured asks first.
    void service.sync({ reason: 'first open' });
    return api;
  }
  // Rewrites modules.json if the converter changed since the last sync; that event starts the server.
  service.refreshFromModel('startup');
  void server.start().catch(() => undefined);
  if (gradleFilesNewerThan(service.root, service.modelFile)) {
    const syncNow = 'Sync Now';
    void vscode.window.showInformationMessage('Gradle files have changed since the last Android sync.', syncNow).then((choice) => {
      if (choice === syncNow) void service.sync({ reason: 'Gradle files changed while closed' });
    });
  }
  return api;
}

export function deactivate(): void {}

/** JetBrains' Kotlin extensions start their own server for the same files, so diagnostics and completions double. */
async function warnAboutOtherKotlinServers(): Promise<void> {
  const others = ['jetbrains.kotlin-server', 'jetbrains.intellij-server'].filter((id) => vscode.extensions.getExtension(id));
  if (others.length === 0) return;
  const show = 'Show Extensions';
  const choice = await vscode.window.showWarningMessage(
    'Koda Studio runs its own Kotlin language server. Disable "Kotlin by JetBrains" and "Java and Kotlin by IntelliJ IDEA" ' +
      'for this workspace so two servers don\'t analyze the same files.',
    show,
  );
  if (choice === show) await vscode.commands.executeCommand('workbench.extensions.search', others.map((id) => `@id:${id}`).join(' '));
}

async function selectVariant(service: SyncService): Promise<void> {
  const model = service.lastModel();
  if (!model) {
    void vscode.window.showInformationMessage('Sync the project once to see its build variants.');
    return;
  }
  const apps = model.projects.filter((p) => p.android?.projectType === 'APPLICATION');
  const source = apps[0] ?? model.projects.find((p) => p.android);
  const variants = source?.android?.variants ?? [];
  const current = appVariant(model);
  const picked = await vscode.window.showQuickPick(
    variants.map((v) => ({
      label: v.name,
      description: [v.productFlavors.join(' + '), v.buildType].filter(Boolean).join(' · '),
      detail: v.name === current ? 'current' : undefined,
    })),
    { title: `Build variant${source ? ` of ${source.path}` : ''}`, placeHolder: current },
  );
  if (!picked || picked.label === current) return;
  await vscode.workspace.getConfiguration('kodaStudio', service.folder.uri).update('variant', picked.label, vscode.ConfigurationTarget.Workspace);
  await service.sync({ reason: `variant ${picked.label}` });
}

async function statusMenu(service: SyncService): Promise<void> {
  const items: (vscode.QuickPickItem & { command: string })[] = [
    { label: '$(sync) Sync Project with Gradle Files', command: 'kodaStudio.sync' },
    { label: '$(list-selection) Select Build Variant…', command: 'kodaStudio.selectVariant' },
    { label: '$(gear) Generate Sources (including KSP/kapt)', command: 'kodaStudio.generateSources' },
    { label: '$(output) Show Sync Log', command: 'kodaStudio.showLog' },
    { label: '$(debug-restart) Restart Kotlin Server', command: 'kodaStudio.restartServer' },
    { label: '$(trash) Rebuild Kotlin Index', command: 'kodaStudio.rebuildIndex' },
    { label: '$(clear-all) Clear Caches and Restart…', command: 'kodaStudio.clearCaches' },
  ];
  if (service.running) items.unshift({ label: '$(stop) Cancel Sync', command: 'kodaStudio.cancelSync' });
  const picked = await vscode.window.showQuickPick(items, { title: 'Koda Studio' });
  if (!picked) return;
  if (picked.command === 'kodaStudio.cancelSync') service.cancel();
  else await vscode.commands.executeCommand(picked.command);
}

/**
 * Android Studio's Invalidate Caches: start again from a clean state, to test or to get out of a bad one. Either the
 * Kotlin index only, or also the synced Gradle model (a first sync again, then indexing everything).
 */
async function clearCaches(service: SyncService, server: KotlinServer): Promise<void> {
  const index = { label: '$(database) Kotlin index', description: 'Re-index the project and libraries; keeps the Gradle sync', detail: server.indexDir };
  const all = { label: '$(clear-all) Kotlin index and Gradle sync', description: 'As a first open: sync with Gradle, then index everything', detail: service.dataDir };
  const picked = await vscode.window.showQuickPick([index, all], { title: 'Koda: Clear Caches and Restart', placeHolder: 'What to clear' });
  if (!picked) return;
  await server.stop();
  fs.rmSync(server.indexDir, { recursive: true, force: true });
  log().info(`Cleared the Kotlin index at ${server.indexDir}`);
  if (service.running) {
    service.cancel();
    await service.sync({ reason: 'cancelled' }).catch(() => undefined);
  }
  if (picked === all) {
    service.clearData();
    log().info(`Cleared the Gradle sync data at ${service.dataDir}`);
    // The sync's end starts the server (onDidSync).
    await service.sync({ reason: 'caches cleared' });
    return;
  }
  await server.start();
}

/** Whether a build script or the version catalog changed after the model was written. */
function gradleFilesNewerThan(root: string, modelFile: string): boolean {
  let modelTime: number;
  try {
    modelTime = fs.statSync(modelFile).mtimeMs;
  } catch {
    return true;
  }
  const names = new Set(['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties']);
  const stack = [root];
  let visited = 0;
  while (stack.length > 0 && visited < 5000) {
    const dir = stack.pop()!;
    visited++;
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!entry.name.startsWith('.') && entry.name !== 'build' && entry.name !== 'node_modules' && entry.name !== 'src') stack.push(full);
      } else if (names.has(entry.name) || entry.name.endsWith('.versions.toml')) {
        try {
          if (fs.statSync(full).mtimeMs > modelTime) return true;
        } catch {
          // ignore files that vanished
        }
      }
    }
  }
  return false;
}
