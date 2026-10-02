import fs from 'node:fs';
import path from 'node:path';
import * as vscode from 'vscode';

import { UsagesCodeLensProvider } from './codelens/usagesCodeLens.ts';
import { log } from './log.ts';
import { findAndroidRoot } from './sync/androidRoot.ts';
import { appVariant, SyncService } from './sync/syncService.ts';
import { watchBuildOutputs, watchGradleFiles } from './sync/watchers.ts';
import { StatusBar } from './ui/statusBar.ts';

/** Returned from activate() for the end-to-end tests. */
export interface AndroidBridgeApi {
  syncService: SyncService | undefined;
  codeLens: UsagesCodeLensProvider;
}

export async function activate(context: vscode.ExtensionContext): Promise<AndroidBridgeApi> {
  const lenses = new UsagesCodeLensProvider();
  context.subscriptions.push(lenses, vscode.languages.registerCodeLensProvider({ language: 'kotlin', scheme: 'file' }, lenses));

  const folder = findAndroidRoot();
  const service = folder ? new SyncService(context, folder) : undefined;
  const withService = (action: (s: SyncService) => unknown) => () => {
    if (!service) {
      void vscode.window.showInformationMessage('No Android Gradle project found in this workspace.');
      return;
    }
    return action(service);
  };

  context.subscriptions.push(
    vscode.commands.registerCommand('androidBridge.sync', withService((s) => s.sync({ reason: 'command' }))),
    vscode.commands.registerCommand('androidBridge.generateSources', withService((s) => s.sync({ generate: 'full', reason: 'generate sources' }))),
    vscode.commands.registerCommand('androidBridge.selectVariant', withService((s) => selectVariant(s))),
    vscode.commands.registerCommand('androidBridge.showLog', () => log().show()),
    vscode.commands.registerCommand('androidBridge.openWorkspaceJson', withService(async (s) => {
      if (!fs.existsSync(s.workspaceJsonFile)) {
        void vscode.window.showInformationMessage('No workspace.json yet. Sync the project first.');
        return;
      }
      await vscode.window.showTextDocument(vscode.Uri.file(s.workspaceJsonFile));
    })),
    vscode.commands.registerCommand('androidBridge.statusMenu', withService((s) => statusMenu(s))),
  );
  const api: AndroidBridgeApi = { syncService: service, codeLens: lenses };
  if (!service) return api;

  log().info(`Android Gradle project: ${service.root}`);
  context.subscriptions.push(
    service,
    new StatusBar(service),
    watchGradleFiles(service),
    watchBuildOutputs(service),
    service.onDidSync(() => lenses.onSynced()),
  );

  if (!service.lastModel()) {
    // First open: Android Studio syncs right away too. The setup prompt in ensureConfigured asks first.
    void service.sync({ reason: 'first open' });
    return api;
  }
  if (await service.ensureConfigured()) {
    service.refreshFromModel('startup');
    if (gradleFilesNewerThan(service.root, service.modelFile)) {
      const syncNow = 'Sync Now';
      void vscode.window.showInformationMessage('Gradle files have changed since the last Android sync.', syncNow).then((choice) => {
        if (choice === syncNow) void service.sync({ reason: 'Gradle files changed while closed' });
      });
    }
  }
  return api;
}

export function deactivate(): void {}

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
  await vscode.workspace.getConfiguration('androidBridge', service.folder.uri).update('variant', picked.label, vscode.ConfigurationTarget.Workspace);
  await service.sync({ reason: `variant ${picked.label}` });
}

async function statusMenu(service: SyncService): Promise<void> {
  const items: (vscode.QuickPickItem & { command: string })[] = [
    { label: '$(sync) Sync Project with Gradle Files', command: 'androidBridge.sync' },
    { label: '$(list-selection) Select Build Variant…', command: 'androidBridge.selectVariant' },
    { label: '$(gear) Generate Sources (including KSP/kapt)', command: 'androidBridge.generateSources' },
    { label: '$(output) Show Sync Log', command: 'androidBridge.showLog' },
    { label: '$(json) Open Generated workspace.json', command: 'androidBridge.openWorkspaceJson' },
  ];
  if (service.running) items.unshift({ label: '$(stop) Cancel Sync', command: 'androidBridge.cancelSync' });
  const picked = await vscode.window.showQuickPick(items, { title: 'Android Bridge' });
  if (!picked) return;
  if (picked.command === 'androidBridge.cancelSync') service.cancel();
  else await vscode.commands.executeCommand(picked.command);
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
