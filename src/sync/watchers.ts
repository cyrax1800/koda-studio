import * as vscode from 'vscode';

import { readConfig } from '../config.ts';
import type { SyncService } from './syncService.ts';

const GRADLE_FILES = '**/{build.gradle,build.gradle.kts,settings.gradle,settings.gradle.kts,gradle.properties,*.versions.toml}';

/** Like Android Studio's "Gradle files have changed since last project sync" banner. */
export function watchGradleFiles(service: SyncService): vscode.Disposable {
  const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(service.folder, GRADLE_FILES));
  let timer: NodeJS.Timeout | undefined;
  let prompting = false;
  const onChange = (uri: vscode.Uri) => {
    if (/[/\\](build|\.gradle)[/\\]/.test(uri.fsPath.slice(service.root.length))) return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void handle(), 1500);
  };
  const handle = async () => {
    const mode = readConfig(service.folder.uri).onGradleFileChange;
    if (mode === 'never' || service.running || prompting) return;
    if (mode === 'always') {
      void service.sync({ reason: 'Gradle files changed' });
      return;
    }
    prompting = true;
    try {
      const syncNow = 'Sync Now';
      const always = 'Always Sync';
      const choice = await vscode.window.showInformationMessage('Gradle files have changed since the last Android sync.', syncNow, always);
      if (choice === always) {
        await vscode.workspace
          .getConfiguration('androidBridge', service.folder.uri)
          .update('sync.onGradleFileChange', 'always', vscode.ConfigurationTarget.Workspace);
      }
      if (choice === syncNow || choice === always) void service.sync({ reason: 'Gradle files changed' });
    } finally {
      prompting = false;
    }
  };
  watcher.onDidChange(onChange);
  watcher.onDidCreate(onChange);
  watcher.onDidDelete(onChange);
  return new vscode.Disposable(() => {
    if (timer) clearTimeout(timer);
    watcher.dispose();
  });
}

/**
 * Builds outside the extension (`./gradlew assembleDebug`) create generated source folders and R jars. Re-converting
 * the last model is cheap and adds them without a Gradle sync; it only reloads the server if workspace.json changed.
 */
export function watchBuildOutputs(service: SyncService): vscode.Disposable {
  const patterns = ['**/build/generated/*/*', '**/build/generated/*/*/*', '**/build/intermediates/*r_class_jar/**/R.jar'];
  const watchers = patterns.map((p) => vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(service.folder, p), false, true, true));
  let timer: NodeJS.Timeout | undefined;
  const schedule = () => {
    if (service.running) return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => service.refreshFromModel('new build outputs'), 5000);
  };
  for (const watcher of watchers) watcher.onDidCreate(schedule);
  return new vscode.Disposable(() => {
    if (timer) clearTimeout(timer);
    for (const watcher of watchers) watcher.dispose();
  });
}
