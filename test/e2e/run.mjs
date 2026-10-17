// End-to-end test in a real VS Code with our Kotlin server (server/kotlin-lsp, built by `npm run build:lsp`).
//
//   node test/e2e/run.mjs [project] [--pass clean|warm|both] [--sync] [--keep]
//
// project   defaults to ~/Projects/Android/pokedex-compose; its folder name picks profiles/<name>.cjs (what is
//           checked and timed). ~/Projects/Android/tiketandroid is the large one, for worst-case timings.
// --pass    clean: an empty index, as on a first open (the default). warm: VS Code opened again on that index, as
//           every day. both: clean, then warm.
// --sync    run a Gradle sync first even if the project was synced (the first open of a project always syncs).
//
// Uses the installed VS Code with a throwaway profile and no other extensions. Timings go to
// .e2e-results/<project>-<pass>-<time>.json. Restores the project's .vscode/settings.json afterwards.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runTests } from '@vscode/test-electron';

const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback;
};
const positional = args.filter((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1] === '--pass'));

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const project = path.resolve(positional[0] ?? path.join(os.homedir(), 'Projects', 'Android', 'pokedex-compose'));
const profile = path.join(repo, 'test', 'e2e', 'profiles', `${path.basename(project)}.cjs`);
if (!fs.existsSync(profile)) throw new Error(`No e2e profile for ${path.basename(project)}: add ${profile}`);
const passes = { clean: ['clean'], warm: ['warm'], both: ['clean', 'warm'] }[option('pass', 'clean')];
if (!passes) throw new Error('--pass must be clean, warm or both');
const vscodeExecutablePath = process.env.VSCODE_EXECUTABLE ?? '/Applications/Visual Studio Code.app/Contents/MacOS/Code';
if (!fs.existsSync(path.join(repo, 'server', 'kotlin-lsp', 'bin'))) throw new Error('server/kotlin-lsp is missing; run npm run build:lsp');

// Short path: VS Code's IPC socket lives in the user-data dir and macOS limits socket paths to 103 characters. The
// index lives in the user data (workspace storage), so a new one means a clean index and reusing it a warm one.
const base = process.env.E2E_TMP ?? (process.platform === 'win32' ? os.tmpdir() : '/tmp');
const keptDir = path.join(base, `koda-e2e-${path.basename(project)}`);
const tmp = passes[0] === 'warm' ? keptDir : fs.mkdtempSync(path.join(base, 'abe2e-'));
if (passes[0] === 'warm' && !fs.existsSync(keptDir)) throw new Error(`No index from a clean pass at ${keptDir}; run --pass clean --keep (or --pass both) first`);
const userData = path.join(tmp, 'user-data');
const extensions = path.join(tmp, 'extensions');
fs.mkdirSync(path.join(userData, 'User'), { recursive: true });
fs.mkdirSync(extensions, { recursive: true });
fs.writeFileSync(
  path.join(userData, 'User', 'settings.json'),
  JSON.stringify(
    {
      'security.workspace.trust.enabled': false,
      'extensions.autoUpdate': false,
      'extensions.autoCheckUpdates': false,
      'kodaStudio.sync.onGradleFileChange': 'never',
      'workbench.startupEditor': 'none',
    },
    null,
    2,
  ),
);

const resultsDir = path.join(repo, '.e2e-results');
fs.mkdirSync(resultsDir, { recursive: true });
const settingsFile = path.join(project, '.vscode', 'settings.json');
const settingsBefore = fs.existsSync(settingsFile) ? fs.readFileSync(settingsFile, 'utf8') : undefined;
const vscodeDirExisted = fs.existsSync(path.dirname(settingsFile));
let failed = false;
const written = [];
try {
  for (const pass of passes) {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const results = path.join(resultsDir, `${path.basename(project)}-${pass}-${stamp}.json`);
    console.log(`\n=== ${path.basename(project)}: ${pass} pass ===`);
    try {
      await runTests({
        vscodeExecutablePath,
        extensionDevelopmentPath: repo,
        extensionTestsPath: path.join(repo, 'test', 'e2e', 'suite.cjs'),
        launchArgs: [project, '--user-data-dir', userData, '--extensions-dir', extensions, '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes'],
        extensionTestsEnv: {
          KODA_E2E_PROJECT: project,
          KODA_E2E_PROFILE: profile,
          KODA_E2E_PASS: pass,
          KODA_E2E_RESULTS: results,
          KODA_E2E_SYNC: flag('sync') && pass === 'clean' ? '1' : '0',
        },
      });
    } catch (e) {
      failed = true;
      console.error(`e2e ${pass} pass failed: ${e?.message ?? e}`);
    }
    if (fs.existsSync(results)) written.push(results);
    // The extension's and the server's logs of this pass, for looking into a failure or a slow step.
    const logsDir = results.replace(/\.json$/, '-logs');
    for (const file of findFiles(path.join(userData, 'logs'), (name) => name === 'Kotlin Language Server.log' || name === 'Koda Studio.log')) {
      fs.mkdirSync(logsDir, { recursive: true });
      fs.copyFileSync(file, path.join(logsDir, path.basename(file)));
    }
    fs.rmSync(path.join(userData, 'logs'), { recursive: true, force: true });
  }
} finally {
  if (settingsBefore !== undefined) fs.writeFileSync(settingsFile, settingsBefore);
  else fs.rmSync(settingsFile, { force: true });
  if (!vscodeDirExisted) fs.rmSync(path.dirname(settingsFile), { recursive: true, force: true });
  const keep = flag('keep') || process.env.E2E_KEEP;
  if (keep && tmp !== keptDir) {
    fs.rmSync(keptDir, { recursive: true, force: true });
    fs.renameSync(tmp, keptDir);
    console.log(`Kept the index for --pass warm at ${keptDir}`);
  } else if (!keep && tmp !== keptDir) {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// Side by side when both passes ran.
if (written.length > 0) {
  const runs = written.map((f) => JSON.parse(fs.readFileSync(f, 'utf8')));
  const names = [...new Set(runs.flatMap((r) => r.timings.map((t) => t.name)))];
  console.log(`\n${'timing'.padEnd(52)}${runs.map((r) => r.pass.padStart(12)).join('')}`);
  for (const name of names) {
    const cells = runs.map((r) => {
      const t = r.timings.find((x) => x.name === name);
      return (t ? `${(t.ms / 1000).toFixed(2)} s${t.slow ? '!' : ''}` : '-').padStart(12);
    });
    console.log(`${name.slice(0, 50).padEnd(52)}${cells.join('')}`);
  }
  console.log(`${'peak server memory'.padEnd(52)}${runs.map((r) => `${r.peakServerRssMb} MB`.padStart(12)).join('')}`);
  console.log(`(! = slower than the profile's budget)  Results: ${written.map((f) => path.relative(repo, f)).join(', ')}`);
}
process.exit(failed ? 1 : 0);

function findFiles(dir, accept) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return findFiles(full, accept);
    return accept(entry.name) ? [full] : [];
  });
}
