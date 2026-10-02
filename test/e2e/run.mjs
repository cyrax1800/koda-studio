// End-to-end smoke test in a real VS Code with the real Kotlin server.
//   node test/e2e/run.mjs [project]          (default: ~/Projects/Android/pokedex-compose)
// Uses the installed VS Code and jetbrains.kotlin-server, with a throwaway profile. Restores the project's
// .vscode/settings.json afterwards.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runTests } from '@vscode/test-electron';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const project = path.resolve(process.argv[2] ?? path.join(os.homedir(), 'Projects', 'Android', 'pokedex-compose'));
const vscodeExecutablePath = process.env.VSCODE_EXECUTABLE ?? '/Applications/Visual Studio Code.app/Contents/MacOS/Code';
const extensionsRoot = path.join(os.homedir(), '.vscode', 'extensions');
const kotlinServer = fs.readdirSync(extensionsRoot).filter((d) => d.startsWith('jetbrains.kotlin-server-')).sort().pop();
if (!kotlinServer) throw new Error('jetbrains.kotlin-server is not installed');

// Short path: VS Code's IPC socket lives in the user-data dir and macOS limits socket paths to 103 characters.
const tmp = fs.mkdtempSync(path.join(process.env.E2E_TMP ?? (process.platform === 'win32' ? os.tmpdir() : '/tmp'), 'abe2e-'));
const userData = path.join(tmp, 'user-data');
const extensions = path.join(tmp, 'extensions');
fs.mkdirSync(path.join(userData, 'User'), { recursive: true });
fs.writeFileSync(
  path.join(userData, 'User', 'settings.json'),
  JSON.stringify(
    {
      'intellij.region': process.env.INTELLIJ_REGION ?? 'apac',
      'intellij.dataSharing': 'none',
      'security.workspace.trust.enabled': false,
      'extensions.autoUpdate': false,
      'extensions.autoCheckUpdates': false,
      'androidBridge.sync.onGradleFileChange': 'never',
    },
    null,
    2,
  ),
);
fs.mkdirSync(extensions);
fs.symlinkSync(path.join(extensionsRoot, kotlinServer), path.join(extensions, kotlinServer));

const settingsFile = path.join(project, '.vscode', 'settings.json');
const settingsBefore = fs.existsSync(settingsFile) ? fs.readFileSync(settingsFile, 'utf8') : undefined;
const vscodeDirExisted = fs.existsSync(path.dirname(settingsFile));
let failed = false;
try {
  await runTests({
    vscodeExecutablePath,
    extensionDevelopmentPath: repo,
    extensionTestsPath: path.join(repo, 'test', 'e2e', 'suite.cjs'),
    launchArgs: [project, '--user-data-dir', userData, '--extensions-dir', extensions, '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes'],
    extensionTestsEnv: { ANDROID_BRIDGE_E2E_PROJECT: project },
  });
} catch (e) {
  failed = true;
  console.error(`e2e failed: ${e?.message ?? e}`);
} finally {
  if (settingsBefore !== undefined) fs.writeFileSync(settingsFile, settingsBefore);
  else fs.rmSync(settingsFile, { force: true });
  if (!vscodeDirExisted) fs.rmSync(path.dirname(settingsFile), { recursive: true, force: true });
  if (!process.env.E2E_KEEP) fs.rmSync(tmp, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
