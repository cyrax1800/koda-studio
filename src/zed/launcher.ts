// Starts the Kotlin server for Zed (or any LSP client that just runs a command): syncs the Android project with
// Gradle when it has never been synced, then runs server/kotlin-lsp on this process's stdin/stdout.
//
//   node dist/zed-launcher.cjs [--root <project>] [--sync]
//
// stdout belongs to the language server protocol, so everything here logs to stderr (Zed's LSP log).
// Environment: KODA_JAVA_HOME (JDK for the server), KODA_GRADLE_JAVA_HOME (JDK for the sync), KODA_JVM_ARGS,
// KODA_VARIANT, KODA_GENERATE (none | light | full), KODA_LIBRARY_SOURCES (download | cached: skip downloading
// library sources jars).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

import type { GenerateSources } from '../config.ts';
import { findJavaRuntime } from '../gradle/javaHome.ts';
import type { ProjectModel } from '../gradle/modelTypes.ts';
import { runSync } from '../gradle/syncRunner.ts';
import { DEFAULT_JVM_ARGS } from '../server/jvmArgs.ts';
import { toModulesJson } from '../server/modulesJson.ts';

/** The repository (or installed extension) folder holding server/, gradle/ and dist/. */
const home = path.resolve(__dirname, '..');

function log(message: string): void {
  process.stderr.write(`[koda] ${message}\n`);
}

function option(name: string): string | undefined {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

async function sync(root: string, dataDir: string, modulesFile: string): Promise<void> {
  const java = await findJavaRuntime(process.env.KODA_GRADLE_JAVA_HOME || undefined, root, 'KODA_GRADLE_JAVA_HOME');
  const generate = (process.env.KODA_GENERATE as GenerateSources | undefined) ?? 'light';
  log(`Syncing ${root} with Gradle (Java ${java.version} from ${java.source}, generate=${generate}); this can take minutes.`);
  const started = Date.now();
  const modelPath = await runSync(
    {
      extensionPath: home,
      projectDir: root,
      outFile: path.join(dataDir, 'model.json'),
      variant: process.env.KODA_VARIANT || undefined,
      generate,
      gradleArguments: [],
      java,
      // Indexing follows a first sync and needs the memory the daemon holds.
      stopDaemon: true,
      downloadSources: process.env.KODA_LIBRARY_SOURCES !== 'cached',
    },
    {
      progress: (message) => log(message),
      phase: (name) => log(`phase: ${name}`),
      warning: (message) => log(`warning: ${message}`),
      output: () => undefined,
    },
    new AbortController().signal,
  );
  const model = JSON.parse(fs.readFileSync(modelPath, 'utf8')) as ProjectModel;
  const { modules, warnings, missingLibraries } = toModulesJson(model, { librarySources: 'cached' });
  for (const warning of warnings) log(`warning: ${warning}`);
  if (missingLibraries > 0) log(`${missingLibraries} library jars are missing from the Gradle cache.`);
  const temp = `${modulesFile}.tmp`;
  fs.writeFileSync(temp, JSON.stringify(modules));
  fs.renameSync(temp, modulesFile);
  log(`Sync finished in ${((Date.now() - started) / 1000).toFixed(1)}s: ${modules.length} modules.`);
}

/**
 * Whether the last synced model names library jars Gradle has since deleted (a newer Gradle version cleaned up the
 * old transforms): every AAR type would be unresolved, so it needs a new sync, as the VS Code extension does.
 */
function staleModel(modelFile: string): boolean {
  if (!fs.existsSync(modelFile)) return false;
  try {
    const model = JSON.parse(fs.readFileSync(modelFile, 'utf8')) as ProjectModel;
    const { missingLibraries } = toModulesJson(model, { librarySources: 'cached' });
    if (missingLibraries > 0) log(`${missingLibraries} library jars of the last sync are gone from the Gradle cache; syncing again.`);
    return missingLibraries > 0;
  } catch (e) {
    log(`Ignoring unreadable ${modelFile}: ${String(e)}`);
    return false;
  }
}

async function main(): Promise<void> {
  const root = path.resolve(option('--root') ?? process.cwd());
  const dataDir = path.join(root, '.gradle', 'koda');
  const legacy = path.join(root, '.gradle', 'android-bridge');
  if (!fs.existsSync(dataDir) && fs.existsSync(legacy)) fs.renameSync(legacy, dataDir);
  fs.mkdirSync(dataDir, { recursive: true });
  const modulesFile = path.join(dataDir, 'modules.json');

  if (process.argv.includes('--sync') || !fs.existsSync(modulesFile) || staleModel(path.join(dataDir, 'model.json'))) {
    await sync(root, dataDir, modulesFile);
  }

  const launcher = path.join(home, 'server', 'kotlin-lsp', 'bin', process.platform === 'win32' ? 'kotlin-lsp.bat' : 'kotlin-lsp');
  if (!fs.existsSync(launcher)) throw new Error(`Kotlin server not found at ${launcher}. Run "npm run build:lsp".`);
  const java = await findJavaRuntime(process.env.KODA_JAVA_HOME || undefined, root, 'KODA_JAVA_HOME');
  const jvmArgs = process.env.KODA_JVM_ARGS ?? DEFAULT_JVM_ARGS.join(' ');
  log(`Starting the Kotlin server for ${root} with Java ${java.version} (${java.source})`);

  const server = spawn(launcher, [], {
    cwd: root,
    env: { ...process.env, JAVA_HOME: java.home, JAVA_OPTS: jvmArgs },
    stdio: 'inherit',
  });
  for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP'] as const) process.on(signal, () => server.kill(signal));
  server.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
}

main().catch((e: unknown) => {
  log(`Couldn't start: ${e instanceof Error ? e.message : String(e)}`);
  process.exit(1);
});
