import fs from 'node:fs';
import path from 'node:path';
import * as vscode from 'vscode';

const SETTINGS_FILES = ['settings.gradle', 'settings.gradle.kts'];
const ANDROID_MARKER = /com\.android\.(application|library|dynamic-feature|test|tools\.build)|android[.-](application|library)|\bagp\s*=/;

/** The first workspace folder that is the root of an Android Gradle build. */
export function findAndroidRoot(): vscode.WorkspaceFolder | undefined {
  for (const folder of vscode.workspace.workspaceFolders ?? []) {
    if (folder.uri.scheme === 'file' && isAndroidGradleRoot(folder.uri.fsPath)) return folder;
  }
  return undefined;
}

export function isAndroidGradleRoot(dir: string): boolean {
  if (!SETTINGS_FILES.some((f) => fs.existsSync(path.join(dir, f)))) return false;
  const candidates = [
    ...SETTINGS_FILES,
    'build.gradle',
    'build.gradle.kts',
    'gradle/libs.versions.toml',
    'app/build.gradle',
    'app/build.gradle.kts',
  ];
  return candidates.some((f) => {
    try {
      return ANDROID_MARKER.test(fs.readFileSync(path.join(dir, f), 'utf8'));
    } catch {
      return false;
    }
  });
}
