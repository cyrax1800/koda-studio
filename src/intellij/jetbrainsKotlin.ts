// Talks to the "Kotlin by JetBrains" extension only through its documented settings and commands.
import * as vscode from 'vscode';

import { log } from '../log.ts';

export const KOTLIN_EXTENSION_ID = 'jetbrains.kotlin-server';
export const INTELLIJ_EXTENSION_ID = 'jetbrains.intellij-server';

interface ConfiguredProject {
  type: string;
  path: string;
  [key: string]: unknown;
}

/** workspace.json files written by this extension all live in `<gradle root>/.gradle/android-bridge/`. */
const OUR_PATH = /\/\.gradle\/android-bridge\/workspace\.json$/;

export function kotlinExtensionInstalled(): boolean {
  return vscode.extensions.getExtension(KOTLIN_EXTENSION_ID) !== undefined;
}

export async function offerKotlinExtensionInstall(): Promise<void> {
  const install = 'Install Kotlin by JetBrains';
  const choice = await vscode.window.showWarningMessage(
    'Android Bridge needs the "Kotlin by JetBrains" extension for completion, navigation and references.',
    install,
  );
  if (choice === install) {
    await vscode.commands.executeCommand('workbench.extensions.installExtension', KOTLIN_EXTENSION_ID);
  }
}

/**
 * "Java and Kotlin by IntelliJ IDEA" refuses to run next to "Kotlin by JetBrains" and asks to uninstall it.
 * VS Code has no API for one extension to disable another, so point the user at it.
 */
export async function warnAboutIntellijExtension(): Promise<void> {
  if (!vscode.extensions.getExtension(INTELLIJ_EXTENSION_ID)) return;
  const show = 'Show Extension';
  const choice = await vscode.window.showWarningMessage(
    '"Java and Kotlin by IntelliJ IDEA" is enabled. It conflicts with "Kotlin by JetBrains", which Android Bridge uses. ' +
      'Disable it for this workspace to avoid two language servers.',
    show,
  );
  if (choice === show) {
    await vscode.commands.executeCommand('workbench.extensions.search', `@id:${INTELLIJ_EXTENSION_ID}`);
  }
}

/**
 * Points `intellij.projects` at our workspace.json. A non-empty `intellij.projects` also stops the server's own
 * Gradle auto-import, which is what fails on Android projects. Returns true when the setting changed.
 */
export async function ensureProjectsSetting(workspaceJson: vscode.Uri): Promise<boolean> {
  const config = vscode.workspace.getConfiguration('intellij');
  const inspected = config.inspect<ConfiguredProject[]>('projects');
  const current = inspected?.workspaceValue ?? [];
  const path = workspaceJson.toString();
  if (current.some((p) => p.type === 'json' && p.path === path)) return false;
  const next = [...current.filter((p) => !(p.type === 'json' && OUR_PATH.test(p.path))), { type: 'json', path }];
  await config.update('projects', next, vscode.ConfigurationTarget.Workspace);
  log().info(`Set intellij.projects to ${JSON.stringify(next)}`);
  return true;
}

export function projectsSettingPointsTo(workspaceJson: vscode.Uri): boolean {
  const projects = vscode.workspace.getConfiguration('intellij').get<ConfiguredProject[]>('projects') ?? [];
  return projects.some((p) => p.type === 'json' && p.path === workspaceJson.toString());
}

/**
 * Asks the running server to re-import. The command awaits an information message (and shows an error when the
 * server isn't running yet), so it is not awaited here.
 */
export function reloadKotlinWorkspace(): void {
  if (!kotlinExtensionInstalled()) return;
  void Promise.resolve(vscode.commands.executeCommand('jetbrains.kotlin.reloadWorkspace')).then(
    () => undefined,
    (e: unknown) => log().warn(`jetbrains.kotlin.reloadWorkspace failed: ${String(e)}`),
  );
}

/** Restarts the server so it picks up `intellij.projects`; waits at most a few seconds for the command. */
export async function restartKotlinServer(): Promise<void> {
  if (!kotlinExtensionInstalled()) return;
  const restart = Promise.resolve(vscode.commands.executeCommand('jetbrains.kotlin.restartLsp')).then(
    () => undefined,
    (e: unknown) => log().warn(`jetbrains.kotlin.restartLsp failed: ${String(e)}`),
  );
  await Promise.race([restart, new Promise((resolve) => setTimeout(resolve, 5000))]);
}
