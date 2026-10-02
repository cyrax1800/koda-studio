#!/usr/bin/env node
// Headless driver for the JetBrains Kotlin language server, used to test imports without the VS Code UI.
//
// node tools/lsp-probe.mjs --root <project> [--workspace-json <file>] [--probes <probes.json>]
//                          [--idle 20] [--timeout 1800] [--system-path <dir>] [--log <file>]
//
// Probe file format: [{ "file": "app/src/main/.../X.kt", "find": "text", "offset": 0,
//                       "kind": "diagnostics" | "completion" | "references" | "definition" | "hover" }]
// A completion probe may also have "insert": text typed at the position first (and undone afterwards), and
// "repeat": n. { "kind": "parallel", "probes": [...] } runs its probes at the same time.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const args = parseArgs(process.argv.slice(2));
const root = path.resolve(required(args, 'root'));
const idleSeconds = Number(args.idle ?? 20);
const timeoutSeconds = Number(args.timeout ?? 1800);
const serverHome = args.server ?? findServerHome();
const systemPath = args['system-path'] ?? path.join(os.tmpdir(), 'android-bridge-probe', path.basename(root));
const logFile = args.log;
const probes = args.probes ? JSON.parse(fs.readFileSync(args.probes, 'utf8')) : [];

const logStream = logFile ? fs.createWriteStream(logFile) : undefined;
const started = Date.now();
function log(line) {
  const stamped = `[${((Date.now() - started) / 1000).toFixed(1).padStart(7)}s] ${line}`;
  console.log(stamped);
  logStream?.write(stamped + '\n');
}

const projects = [];
if (args['workspace-json']) {
  projects.push({ type: 'json', path: pathToFileURL(path.resolve(args['workspace-json'])).toString() });
}

const env = { ...process.env };
delete env.IJ_LAUNCHER_DEBUG;
if (args.region) env.INTELLIJ_REGION = args.region;
if (args['java-home']) env.JAVA_HOME = args['java-home'];
if (args['jvm-options']) env.IJ_JAVA_OPTIONS = args['jvm-options'];

fs.mkdirSync(systemPath, { recursive: true });
const launcher = path.join(serverHome, 'bin', 'intellij-server');
log(`server: ${launcher}`);
log(`root  : ${root}`);
log(`system: ${systemPath}`);
log(`projects: ${JSON.stringify(projects)}`);

const server = spawn(launcher, ['--stdio', '--system-path', systemPath], { env, stdio: ['pipe', 'pipe', 'pipe'] });
server.stderr.setEncoding('utf8');
let stderrTail = '';
server.stderr.on('data', (chunk) => {
  stderrTail = (stderrTail + chunk).slice(-20000);
  for (const line of chunk.split('\n')) if (line.trim()) logStream?.write(`[stderr] ${line}\n`);
});
server.on('exit', (code, signal) => {
  log(`server exited code=${code} signal=${signal}`);
  if (code !== 0 && code !== null) console.log(stderrTail.split('\n').slice(-40).join('\n'));
  process.exit(code ?? 1);
});

// ---- JSON-RPC over stdio ----
let nextId = 1;
const pending = new Map();
let buffer = Buffer.alloc(0);
server.stdout.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf('\r\n\r\n');
    if (headerEnd < 0) return;
    const header = buffer.subarray(0, headerEnd).toString('ascii');
    const match = /Content-Length: *(\d+)/i.exec(header);
    if (!match) throw new Error(`bad header: ${header}`);
    const length = Number(match[1]);
    if (buffer.length < headerEnd + 4 + length) return;
    const body = buffer.subarray(headerEnd + 4, headerEnd + 4 + length).toString('utf8');
    buffer = buffer.subarray(headerEnd + 4 + length);
    onMessage(JSON.parse(body));
  }
});

function send(message) {
  const body = Buffer.from(JSON.stringify({ jsonrpc: '2.0', ...message }), 'utf8');
  server.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
  server.stdin.write(body);
}
function request(method, params, timeoutMs = 600_000) {
  const id = nextId++;
  send({ id, method, params });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out`));
    }, timeoutMs);
    pending.set(id, { resolve, reject, timer, method });
  });
}
function notify(method, params) {
  send({ method, params });
}

// ---- progress tracking ----
const activeProgress = new Map();
let lastActivity = Date.now();
let sawProgress = false;

function onMessage(msg) {
  if (msg.id !== undefined && msg.method === undefined) {
    const entry = pending.get(msg.id);
    if (!entry) return;
    pending.delete(msg.id);
    clearTimeout(entry.timer);
    if (msg.error) entry.reject(new Error(`${entry.method}: ${msg.error.message}`));
    else entry.resolve(msg.result);
    return;
  }
  if (msg.id !== undefined) return onServerRequest(msg);
  onNotification(msg);
}

function onServerRequest(msg) {
  let result = null;
  switch (msg.method) {
    case 'workspace/configuration':
      result = (msg.params?.items ?? []).map(() => null);
      break;
    case 'workspace/workspaceFolders':
      result = [{ uri: pathToFileURL(root).toString(), name: path.basename(root) }];
      break;
    case 'window/workDoneProgress/create':
    case 'client/registerCapability':
    case 'client/unregisterCapability':
    case 'window/showMessageRequest':
    case 'window/showDocument':
      break;
    default:
      log(`<- server request ${msg.method} ${JSON.stringify(msg.params ?? {}).slice(0, 300)}`);
  }
  send({ id: msg.id, result });
}

function onNotification(msg) {
  const p = msg.params ?? {};
  switch (msg.method) {
    case '$/progress': {
      const value = p.value ?? {};
      lastActivity = Date.now();
      sawProgress = true;
      if (value.kind === 'begin') {
        activeProgress.set(p.token, value.title);
        log(`progress begin [${value.title}] ${value.message ?? ''}`);
      } else if (value.kind === 'report') {
        if (value.message) logStream?.write(`progress report [${activeProgress.get(p.token)}] ${value.message} ${value.percentage ?? ''}\n`);
      } else if (value.kind === 'end') {
        log(`progress end   [${activeProgress.get(p.token)}] ${value.message ?? ''}`);
        activeProgress.delete(p.token);
      }
      break;
    }
    case 'window/logMessage':
      logStream?.write(`log[${p.type}] ${p.message}\n`);
      if (p.type === 1) log(`server error: ${String(p.message).slice(0, 2000)}`);
      break;
    case 'window/showMessage':
      log(`showMessage[${p.type}] ${p.message}`);
      break;
    case 'textDocument/publishDiagnostics':
      break;
    case 'intellij/importLog':
      logStream?.write(`import: ${String(p.message ?? '').trimEnd()}\n`);
      if (p.failed) log(`import FAILED: ${String(p.message ?? '').slice(0, 2000)}`);
      lastActivity = Date.now();
      break;
    default:
      log(`<- notification ${msg.method} ${JSON.stringify(p).slice(0, 500)}`);
  }
}

async function waitForIdle() {
  const deadline = Date.now() + timeoutSeconds * 1000;
  for (;;) {
    await sleep(1000);
    const quietFor = (Date.now() - lastActivity) / 1000;
    if (activeProgress.size === 0 && quietFor >= idleSeconds && (sawProgress || quietFor >= idleSeconds * 3)) return;
    if (Date.now() > deadline) throw new Error(`timed out waiting for import/indexing; active: ${[...activeProgress.values()]}`);
  }
}

// ---- probes ----
const versions = new Map();
function openDocument(file) {
  const uri = pathToFileURL(file).toString();
  if (!versions.has(uri)) {
    notify('textDocument/didOpen', {
      textDocument: { uri, languageId: file.endsWith('.java') ? 'java' : 'kotlin', version: 1, text: fs.readFileSync(file, 'utf8') },
    });
    versions.set(uri, 1);
  }
  return uri;
}
function changeDocument(uri, text) {
  const version = versions.get(uri) + 1;
  versions.set(uri, version);
  notify('textDocument/didChange', { textDocument: { uri, version }, contentChanges: [{ text }] });
}
function positionOf(text, find, offset = 0) {
  const index = text.indexOf(find);
  if (index < 0) throw new Error(`"${find}" not found`);
  const before = text.slice(0, index + offset);
  const lines = before.split('\n');
  return { line: lines.length - 1, character: lines[lines.length - 1].length };
}

async function runProbe(probe) {
  if (probe.kind === 'parallel') {
    const t0 = Date.now();
    const results = await Promise.all(probe.probes.map(runProbe));
    log(`parallel group done (${Date.now() - t0} ms)`);
    return { label: 'parallel', results };
  }
  if (probe.repeat > 1) {
    const results = [];
    for (let i = 0; i < probe.repeat; i++) results.push(await runProbe({ ...probe, repeat: 1 }));
    return { label: `${probe.kind} x${probe.repeat}`, results };
  }
  const file = path.resolve(root, probe.file);
  const uri = openDocument(file);
  const text = fs.readFileSync(file, 'utf8');
  const label = `${probe.kind} ${probe.file}${probe.find ? ` @ "${probe.find}"+${probe.offset ?? 0}` : ''}`;
  const position = probe.find ? positionOf(text, probe.find, probe.offset ?? 0) : undefined;
  const t0 = Date.now();
  try {
    switch (probe.kind) {
      case 'diagnostics': {
        const report = await request('textDocument/diagnostic', { textDocument: { uri } });
        const items = report?.items ?? [];
        const errors = items.filter((d) => d.severity === 1);
        log(`${label}: ${errors.length} errors, ${items.length} total (${Date.now() - t0} ms)`);
        for (const d of errors.slice(0, probe.max ?? 15)) log(`    L${d.range.start.line + 1}: ${d.message}`);
        return { label, errors: errors.length };
      }
      case 'completion': {
        let requestPosition = position;
        if (probe.insert) {
          const index = text.indexOf(probe.find) + (probe.offset ?? 0);
          const edited = text.slice(0, index) + probe.insert + text.slice(index);
          changeDocument(uri, edited);
          requestPosition = positionOf(edited, probe.find.slice(0, probe.offset ?? 0) + probe.insert, (probe.offset ?? 0) + probe.insert.length);
        }
        const result = await request('textDocument/completion', { textDocument: { uri }, position: requestPosition });
        if (probe.insert) changeDocument(uri, text);
        const items = Array.isArray(result) ? result : result?.items ?? [];
        const labels = items.map((i) => i.label);
        const expected = probe.expect ?? [];
        const missing = expected.filter((e) => !labels.some((l) => l === e || l.startsWith(e + '(') || l.startsWith(e + ' ')));
        log(`${label}: ${items.length} items (${Date.now() - t0} ms)${expected.length ? `, missing: ${JSON.stringify(missing)}` : ''}`);
        log(`    first: ${labels.slice(0, 12).join(', ')}`);
        return { label, ok: missing.length === 0 };
      }
      case 'references': {
        const result = (await request('textDocument/references', { textDocument: { uri }, position, context: { includeDeclaration: false } })) ?? [];
        const byModule = {};
        for (const loc of result) {
          const rel = path.relative(root, fileURLToPath(loc.uri));
          const key = rel.split(path.sep).slice(0, rel.startsWith('core') || rel.startsWith('feature') ? 2 : 1).join('/');
          byModule[key] = (byModule[key] ?? 0) + 1;
        }
        log(`${label}: ${result.length} references (${Date.now() - t0} ms) ${JSON.stringify(byModule)}`);
        return { label, count: result.length };
      }
      case 'definition': {
        const result = await request('textDocument/definition', { textDocument: { uri }, position });
        const locations = Array.isArray(result) ? result : result ? [result] : [];
        log(`${label}: ${locations.map((l) => (l.uri ?? l.targetUri)).join(', ') || 'none'} (${Date.now() - t0} ms)`);
        return { label, count: locations.length };
      }
      case 'hover': {
        const result = await request('textDocument/hover', { textDocument: { uri }, position });
        const contents = result?.contents;
        const value = typeof contents === 'string' ? contents : contents?.value ?? JSON.stringify(contents);
        log(`${label}: ${String(value).replace(/\s+/g, ' ').slice(0, 300)}`);
        return { label };
      }
      default:
        throw new Error(`unknown probe kind ${probe.kind}`);
    }
  } catch (e) {
    log(`${label}: FAILED ${e.message}`);
    return { label, failed: true };
  }
}

// ---- main ----
(async () => {
  const rootUri = pathToFileURL(root).toString();
  const init = await request('initialize', {
    processId: process.pid,
    clientInfo: { name: 'android-bridge-probe' },
    rootUri,
    workspaceFolders: [{ uri: rootUri, name: path.basename(root) }],
    capabilities: {
      window: { workDoneProgress: true, showMessage: {} },
      workspace: { configuration: true, workspaceFolders: true, didChangeWatchedFiles: { dynamicRegistration: true } },
      textDocument: {
        synchronization: { didSave: true },
        completion: { completionItem: { snippetSupport: true, labelDetailsSupport: true } },
        hover: { contentFormat: ['markdown', 'plaintext'] },
        references: {},
        definition: { linkSupport: true },
        documentSymbol: { hierarchicalDocumentSymbolSupport: true },
        diagnostic: { dynamicRegistration: true },
        codeLens: {},
      },
    },
    initializationOptions: { defaultSdk: args['default-sdk'], buildTools: {}, projects, disableRocksDBWriteAheadLog: false },
  });
  log(`initialized: ${init?.serverInfo?.name ?? '?'} ${init?.serverInfo?.version ?? ''}`);
  notify('initialized', {});
  await waitForIdle();
  log(`import/indexing idle after ${((Date.now() - started) / 1000).toFixed(0)}s`);

  if (args.export) {
    await request('workspace/executeCommand', { command: 'exportWorkspace', arguments: [root] }).catch((e) => log(`export failed: ${e.message}`));
    log(`exported workspace.json to ${root}`);
  }

  const results = [];
  for (const probe of probes) results.push(await runProbe(probe));
  log(`SUMMARY ${JSON.stringify(results)}`);

  await request('shutdown', null, 30_000).catch(() => {});
  notify('exit');
  setTimeout(() => server.kill(), 5000).unref();
})().catch((e) => {
  log(`fatal: ${e.stack ?? e}`);
  server.kill();
  process.exit(1);
});

// ---- helpers ----
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) out[key] = true;
    else out[key] = argv[++i];
  }
  return out;
}
function required(obj, key) {
  if (!obj[key]) throw new Error(`--${key} is required`);
  return obj[key];
}
function findServerHome() {
  const extDir = path.join(os.homedir(), '.vscode', 'extensions');
  const candidates = fs.readdirSync(extDir).filter((d) => d.startsWith('jetbrains.kotlin-server-')).sort();
  if (candidates.length === 0) throw new Error('jetbrains.kotlin-server extension not found');
  return path.join(extDir, candidates[candidates.length - 1], 'server');
}
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
