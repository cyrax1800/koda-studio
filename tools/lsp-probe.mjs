#!/usr/bin/env node
// Headless driver for the JetBrains Kotlin language server, used to test imports without the VS Code UI.
//
// node tools/lsp-probe.mjs --root <project> [--workspace-json <file>] [--probes <probes.json>]
//                          [--idle 20] [--timeout 1800] [--system-path <dir>] [--log <file>]
//                          [--server-cmd "<command> <args>"] [--files-root <dir>] [--init-options <json>]
//                          [--wait-ready]
//
// --server-cmd drives any other language server over stdio instead of JetBrains'. --files-root resolves probe
// files somewhere other than --root, for servers whose project file must live outside the project.
//
// Probe file format: [{ "file": "app/src/main/.../X.kt", "find": "text", "offset": 0,
//                       "kind": "diagnostics" | "completion" | "references" | "definition" | "hover" }]
// A completion probe may also have "insert": text typed at the position first (and undone afterwards), and
// "repeat": n. { "kind": "parallel", "probes": [...] } runs its probes at the same time.
import { execFileSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const args = parseArgs(process.argv.slice(2));
const root = path.resolve(required(args, 'root'));
const idleSeconds = Number(args.idle ?? 20);
const timeoutSeconds = Number(args.timeout ?? 1800);
const serverCommand = args['server-cmd'] ? String(args['server-cmd']).split(' ').filter(Boolean) : undefined;
const serverHome = serverCommand ? undefined : args.server ?? findServerHome();
const filesRoot = path.resolve(args['files-root'] ?? root);
const systemPath = args['system-path'] ?? path.join(os.tmpdir(), 'koda-probe', path.basename(root));
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
const [launcher, ...launchArgs] = serverCommand ?? [path.join(serverHome, 'bin', 'intellij-server'), '--stdio', '--system-path', systemPath];
log(`server: ${launcher} ${launchArgs.join(' ')}`);
log(`root  : ${root}`);
log(`system: ${systemPath}`);
log(`projects: ${JSON.stringify(projects)}`);

const server = spawn(launcher, launchArgs, { cwd: root, env, stdio: ['pipe', 'pipe', 'pipe'] });

// Peak resident memory of the server process tree, sampled every second.
let peakRssKb = 0;
const rssTimer = setInterval(() => {
  try {
    const out = execFileSync('ps', ['-A', '-o', 'pid=,ppid=,rss=']).toString();
    const rows = out.trim().split('\n').map((l) => l.trim().split(/\s+/).map(Number));
    const tree = new Set([server.pid]);
    for (let changed = true; changed; ) {
      changed = false;
      for (const [pid, ppid] of rows) if (tree.has(ppid) && !tree.has(pid)) (tree.add(pid), (changed = true));
    }
    const rss = rows.filter(([pid]) => tree.has(pid)).reduce((sum, [, , kb]) => sum + kb, 0);
    peakRssKb = Math.max(peakRssKb, rss);
  } catch {
    // ps can fail while the process exits
  }
}, 1000);
rssTimer.unref();
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
function request(method, params, timeoutMs = Number(args['request-timeout'] ?? 120) * 1000) {
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

// Servers without pull diagnostics publish them instead; the latest set per document is kept here.
const pushedDiagnostics = new Map();

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

// `--wait-ready`: start the probes once our server's `koda/status` says indexing finished.
let markReady;
const serverReady = new Promise((resolve) => (markReady = resolve));

function onNotification(msg) {
  const p = msg.params ?? {};
  if (msg.method === 'koda/status' && p.state === 'ready') markReady();
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
      pushedDiagnostics.set(p.uri, { at: Date.now(), items: p.diagnostics ?? [] });
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
/** Sends one ranged edit, which every server accepts (some only support incremental sync). */
function changeDocument(uri, range, text) {
  const version = versions.get(uri) + 1;
  versions.set(uri, version);
  notify('textDocument/didChange', { textDocument: { uri, version }, contentChanges: [{ range, text }] });
}
function offsetAt(text, position) {
  let offset = 0;
  for (let line = 0; line < position.line; line++) offset = text.indexOf('\n', offset) + 1;
  return offset + position.character;
}

function applyEdits(text, edits) {
  const sorted = [...edits].sort((a, b) => offsetAt(text, b.range.start) - offsetAt(text, a.range.start));
  for (const e of sorted) text = text.slice(0, offsetAt(text, e.range.start)) + e.newText + text.slice(offsetAt(text, e.range.end));
  return text;
}

function positionAt(text, index) {
  const lines = text.slice(0, index).split('\n');
  return { line: lines.length - 1, character: lines[lines.length - 1].length };
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
  if (probe.kind === 'wait') {
    if (probe.ready) await serverReady;
    else await sleep((probe.seconds ?? 10) * 1000);
    log(`wait: done`);
    return { label: 'wait', ok: true };
  }
  const file = path.resolve(filesRoot, probe.file);
  const uri = openDocument(file);
  let text = fs.readFileSync(file, 'utf8');
  const label = `${probe.kind} ${probe.file}${probe.find ? ` @ "${probe.find}"+${probe.offset ?? 0}` : ''}`;
  const position = probe.find ? positionOf(text, probe.find, probe.offset ?? 0) : undefined;
  const t0 = Date.now();
  try {
    switch (probe.kind) {
      case 'diagnostics': {
        let items;
        // `"insert"` (at `"find"` + `"offset"`) checks the diagnostics of an edited file, without touching the disk;
        // the edit is undone afterwards.
        let insertedRange;
        if (probe.insert) {
          const index = text.indexOf(probe.find) + (probe.offset ?? 0);
          const start = positionAt(text, index);
          insertedRange = { start, end: positionAt(text.slice(0, index) + probe.insert, index + probe.insert.length) };
          const before = pushedDiagnostics.get(uri)?.at ?? 0;
          changeDocument(uri, { start, end: start }, probe.insert);
          const deadline = Date.now() + 120_000;
          while (Date.now() < deadline && (pushedDiagnostics.get(uri)?.at ?? 0) <= before) await sleep(200);
          // Then quiet for 3 s, so diagnostics of the edit have arrived.
          while (Date.now() < deadline && Date.now() - pushedDiagnostics.get(uri).at < 3000) await sleep(200);
          items = pushedDiagnostics.get(uri)?.items ?? [];
          changeDocument(uri, insertedRange, '');
        } else try {
          const report = await request('textDocument/diagnostic', { textDocument: { uri } });
          items = report?.items ?? [];
        } catch {
          // Wait until the server has published diagnostics for this document and then gone quiet for 3 s.
          const deadline = Date.now() + 120_000;
          while (Date.now() < deadline) {
            const pushed = pushedDiagnostics.get(uri);
            if (pushed && Date.now() - pushed.at > 3000) break;
            await sleep(500);
          }
          items = pushedDiagnostics.get(uri)?.items ?? [];
        }
        const errors = items.filter((d) => d.severity === 1);
        log(`${label}: ${errors.length} errors, ${items.length} total (${Date.now() - t0} ms)`);
        if (probe.expectError) {
          const found = errors.some((d) => d.message.includes(probe.expectError));
          log(`    expected error ${JSON.stringify(probe.expectError)}: ${found ? 'found' : 'MISSING'}`);
          for (const d of errors.slice(0, probe.max ?? 15)) log(`    L${d.range.start.line + 1}: ${d.message}`);
          return { label, errors: errors.length, ok: found };
        }
        for (const d of errors.slice(0, probe.max ?? 15)) log(`    L${d.range.start.line + 1}: ${d.message}`);
        return { label, errors: errors.length };
      }
      case 'completion': {
        let requestPosition = position;
        let insertedRange;
        if (probe.insert) {
          const index = text.indexOf(probe.find) + (probe.offset ?? 0);
          const edited = text.slice(0, index) + probe.insert + text.slice(index);
          const start = positionAt(text, index);
          requestPosition = positionAt(edited, index + probe.insert.length);
          insertedRange = { start, end: requestPosition };
          changeDocument(uri, { start, end: start }, probe.insert);
        }
        // `"smart": true` asks for smart completion (Ctrl+Shift+Space) first.
        if (probe.smart) notify('koda/smartCompletion', {});
        const result = await request('textDocument/completion', { textDocument: { uri }, position: requestPosition });
        if (insertedRange) changeDocument(uri, insertedRange, '');
        const items = Array.isArray(result) ? result : result?.items ?? [];
        const labels = items.map((i) => i.label);
        const expected = probe.expect ?? [];
        const missing = expected.filter((e) => !labels.some((l) => l === e || l.startsWith(e + '(') || l.startsWith(e + ' ')));
        log(`${label}: ${items.length} items (${Date.now() - t0} ms)${expected.length ? `, missing: ${JSON.stringify(missing)}` : ''}`);
        log(`    first: ${labels.slice(0, 12).join(', ')}`);
        // `"showInsert": true` prints what the expected items insert, e.g. `launch { $0 }`, and their import edits.
        if (probe.showInsert) {
          for (const item of items.filter((i) => expected.some((e) => i.label === e || i.label.startsWith(e + '(')))) {
            const imports = (item.additionalTextEdits ?? []).map((e) => e.newText.trim()).join('; ');
            const from = item.labelDetails?.description ? ` (${item.labelDetails.description})` : '';
            log(`    insert ${item.label}${item.labelDetails?.detail ?? ""}${from}: ${item.insertText ?? item.textEdit?.newText ?? item.label}${imports ? `  [${imports}]` : ''}`);
          }
        }
        return { label, ok: missing.length === 0 };
      }
      // Typing as in VS Code: `"type"` is typed one character at a time (`"interval"` ms apart, default 120) at
      // `"find"` + `"offset"` after `"insert"` (e.g. a new line). Each keystroke sends what the editor sends: the change,
      // then completion (the previous one cancelled), semantic tokens, inlay hints and code actions. Reports when the
      // last completion arrived after the last keystroke, and whether it had `"expect"`.
      case 'typing': {
        // `"after"`: seconds between opening the file and the first keystroke (people look before they type).
        if (probe.after) await sleep(probe.after * 1000);
        const index = text.indexOf(probe.find) + (probe.offset ?? 0);
        const start = positionAt(text, index);
        let typed = probe.insert ?? '';
        if (typed) changeDocument(uri, { start, end: start }, typed);
        const timings = { completion: [], semanticTokens: [], inlayHint: [], codeAction: [] };
        const timed = (name, method, params) => {
          const sentAt = Date.now();
          return request(method, params).then(
            (result) => (timings[name].push(Date.now() - sentAt), result),
            () => undefined,
          );
        };
        // As VS Code's suggest widget: one request at a time; typing while it is pending sends nothing, and the answer
        // is asked again for the current word only when the list was incomplete.
        const currentWord = () => /[A-Za-z0-9_]*$/.exec(typed)[0];
        let pending;
        let shown;
        let lastKeyAt = 0;
        const ask = () => {
          const word = currentWord();
          const cursor = positionAt(text.slice(0, index) + typed, index + typed.length);
          pending = timed('completion', 'textDocument/completion', { textDocument: { uri }, position: cursor }).then((result) => {
            pending = undefined;
            shown = { result, word, at: Date.now() };
            if (result?.isIncomplete && currentWord() !== word) ask();
          });
        };
        for (const ch of probe.type) {
          const at = positionAt(text.slice(0, index) + typed, index + typed.length);
          changeDocument(uri, { start: at, end: at }, ch);
          typed += ch;
          lastKeyAt = Date.now();
          const cursor = positionAt(text.slice(0, index) + typed, index + typed.length);
          // A new word (after `.` or a space) starts a new list.
          if (!pending && (!shown || shown.result?.isIncomplete || !/[A-Za-z0-9_]/.test(ch) || currentWord().length === 1)) ask();
          void timed('semanticTokens', 'textDocument/semanticTokens/full', { textDocument: { uri } });
          void timed('inlayHint', 'textDocument/inlayHint', { textDocument: { uri }, range: { start: { line: Math.max(0, cursor.line - 40), character: 0 }, end: { line: cursor.line + 40, character: 0 } } });
          void timed('codeAction', 'textDocument/codeAction', { textDocument: { uri }, range: { start: cursor, end: cursor }, context: { diagnostics: [] } });
          await sleep(probe.interval ?? 120);
        }
        while (pending) await pending;
        const result = shown?.result;
        const shownAfter = Math.max(0, (shown?.at ?? Date.now()) - lastKeyAt);
        const word = currentWord().toLowerCase();
        const items = (Array.isArray(result) ? result : result?.items ?? []).filter((i) => (i.filterText ?? i.label).toLowerCase().startsWith(word));
        const labels = items.map((i) => i.label);
        const expected = probe.expect ?? [];
        const missing = expected.filter((e) => !labels.some((l) => l === e || l.startsWith(e + '(') || l.startsWith(e + ' ')));
        // Then, with the text unchanged, what highlighting and hints take.
        await Promise.all([
          timed('semanticTokens', 'textDocument/semanticTokens/full', { textDocument: { uri } }),
          timed('inlayHint', 'textDocument/inlayHint', { textDocument: { uri }, range: { start: { line: 0, character: 0 }, end: { line: 200, character: 0 } } }),
        ]);
        const stats = Object.entries(timings).map(([k, v]) => `${k} ${v.length ? `${Math.min(...v)}–${Math.max(...v)}` : '-'} ms`).join(', ');
        log(`${label} typing "${probe.type}": list ${shownAfter} ms after the last key, ${timings.completion.length} requests, ${items.length} items${expected.length ? `, missing: ${JSON.stringify(missing)}` : ''}`);
        log(`    first: ${labels.slice(0, 8).join(', ')}`);
        log(`    ${stats}`);
        const end = positionAt(text.slice(0, index) + typed, index + typed.length);
        changeDocument(uri, { start, end }, '');
        await sleep(1000);
        return { label, ms: shownAfter, ok: missing.length === 0 };
      }
      // Optimize Imports as on save: the import lines it removes, and whether the order changed.
      // Formatting: how many lines Reformat Code changes, and the first few.
      // `{ "kind": "wait", "seconds": 60 }` or `{ "kind": "wait", "ready": true }`: let the server work (index) first.
      case 'wait': {
        if (probe.ready) await serverReady;
        else await new Promise((r) => setTimeout(r, (probe.seconds ?? 10) * 1000));
        log(`${label}: waited`);
        return { label, ok: true };
      }
      // Code actions at `"find"` + `"offset"` (after `"insert"`): their titles, and the text after applying the first
      // one whose title starts with `"apply"`.
      case 'codeAction': {
        let original = text;
        let insertedRange;
        if (probe.insert) {
          const index = text.indexOf(probe.find);
          const start = positionAt(text, index);
          text = text.slice(0, index) + probe.insert + text.slice(index);
          insertedRange = { start, end: positionAt(text, index + probe.insert.length) };
          changeDocument(uri, { start, end: start }, probe.insert);
        }
        const at = positionAt(text, text.indexOf(probe.at ?? probe.find) + (probe.offset ?? 0));
        const actions = (await request('textDocument/codeAction', { textDocument: { uri }, range: { start: at, end: at }, context: { diagnostics: [] } })) ?? [];
        log(`${label}: ${actions.map((a) => a.title).join(' | ')} (${Date.now() - t0} ms)`);
        const chosen = actions.find((a) => probe.apply && a.title.startsWith(probe.apply));
        if (chosen) {
          const after = applyEdits(text, chosen.edit?.changes?.[uri] ?? []);
          const lines = after.split('\n');
          const anchor = (probe.at ?? probe.find).split('\n')[0];
          const from = Math.max(0, after.slice(0, after.indexOf(anchor)).split('\n').length - 2);
          for (const line of lines.slice(from, from + (probe.max ?? 14))) log(`    | ${line}`);
          const imports = after.split('\n').filter((l) => l.startsWith('import ') && !original.includes(l));
          if (imports.length) log(`    new imports: ${imports.join('; ')}`);
        }
        if (insertedRange) changeDocument(uri, insertedRange, '');
        text = original;
        return { label, ok: !probe.apply || !!chosen };
      }
      // Highlighting: how many semantic tokens of each type the file gets, and the first few.
      case 'semanticTokens': {
        const types = serverCapabilities?.semanticTokensProvider?.legend?.tokenTypes ?? [];
        const data = (await request('textDocument/semanticTokens/full', { textDocument: { uri } }))?.data ?? [];
        const lines = text.split('\n');
        const tokens = [];
        for (let i = 0, line = 0, char = 0; i + 4 < data.length; i += 5) {
          line += data[i];
          char = data[i] === 0 ? char + data[i + 1] : data[i + 1];
          tokens.push({ type: types[data[i + 3]] ?? data[i + 3], text: lines[line]?.slice(char, char + data[i + 2]) });
        }
        const counts = {};
        for (const t of tokens) counts[t.type] = (counts[t.type] ?? 0) + 1;
        log(`${label}: ${tokens.length} tokens ${JSON.stringify(counts)} (${Date.now() - t0} ms)`);
        log(`    ${tokens.slice(0, probe.max ?? 20).map((t) => `${t.text}:${t.type}`).join(' ')}`);
        return { label, ok: tokens.length > 0 };
      }
      // What the usages CodeLens does: references for every declaration in the file.
      case 'codeLens': {
        const symbols = (await request('textDocument/documentSymbol', { textDocument: { uri } })) ?? [];
        const flat = [];
        const walk = (list) => list.forEach((s) => { flat.push(s); walk(s.children ?? []); });
        walk(symbols);
        let total = 0;
        for (const symbol of flat.slice(0, probe.max ?? 200)) {
          const position = (symbol.selectionRange ?? symbol.location?.range).start;
          const refs = await request('textDocument/references', { textDocument: { uri }, position, context: { includeDeclaration: false } });
          total += refs?.length ?? 0;
        }
        log(`${label}: ${flat.length} declarations, ${total} references (${Date.now() - t0} ms)`);
        return { label, ok: true };
      }
      case 'organizeImports':
      case 'format': {
        // `"insert"` at `"find"` + `"offset"` first, e.g. an unused import or badly indented code (undone afterwards).
        let insertedRange;
        let original = text;
        if (probe.insert) {
          const index = text.indexOf(probe.find) + (probe.offset ?? 0);
          const start = positionAt(text, index);
          text = text.slice(0, index) + probe.insert + text.slice(index);
          insertedRange = { start, end: positionAt(text, index + probe.insert.length) };
          changeDocument(uri, { start, end: start }, probe.insert);
        }
        let edits;
        if (probe.kind === 'organizeImports') {
          const actions = await request('textDocument/codeAction', {
            textDocument: { uri },
            range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
            context: { diagnostics: [], only: ['source.organizeImports'] },
          });
          edits = actions?.[0]?.edit?.changes?.[uri] ?? [];
        } else {
          edits = (await request('textDocument/formatting', { textDocument: { uri }, options: { tabSize: 4, insertSpaces: true } })) ?? [];
        }
        if (insertedRange) changeDocument(uri, insertedRange, '');
        const after = applyEdits(text, edits);
        const before = text.split('\n');
        const now = after.split('\n');
        const removed = before.filter((l) => !now.includes(l));
        const added = now.filter((l) => !before.includes(l));
        log(`${label}: ${edits.length} edit(s), ${removed.length} line(s) removed, ${added.length} added (${Date.now() - t0} ms)`);
        if (probe.showEdits) for (const e of edits) log(`    edit ${JSON.stringify(e)}`);
        for (const l of removed.slice(0, probe.max ?? 10)) log(`    - ${l}`);
        for (const l of added.slice(0, probe.max ?? 10)) log(`    + ${l}`);
        const expected = probe.expectRemoved ?? [];
        const missing = expected.filter((e) => !removed.some((l) => l.includes(e)));
        const wrong = (probe.expectKept ?? []).filter((e) => !now.some((l) => l.includes(e)));
        if (expected.length || wrong.length) log(`    missing: ${JSON.stringify(missing)} wrongly removed: ${JSON.stringify(wrong)}`);
        text = original;
        return { label, ok: missing.length === 0 && wrong.length === 0 };
      }
      case 'signatureHelp': {
        // Cursor at the end of `"insert"` (at `"find"` + `"offset"`), like a completion probe.
        const index = text.indexOf(probe.find) + (probe.offset ?? 0);
        const start = positionAt(text, index);
        const end = positionAt(text.slice(0, index) + probe.insert, index + probe.insert.length);
        changeDocument(uri, { start, end: start }, probe.insert);
        const help = await request('textDocument/signatureHelp', { textDocument: { uri }, position: end });
        changeDocument(uri, { start, end }, '');
        const signatures = help?.signatures ?? [];
        log(`${label}: ${signatures.length} signatures, active ${help?.activeSignature}, parameter ${help?.activeParameter} (${Date.now() - t0} ms)`);
        signatures.forEach((s, i) => {
          const p = s.parameters?.[help.activeParameter]?.label;
          const current = Array.isArray(p) ? s.label.slice(p[0], p[1]) : p;
          log(`    ${i === help.activeSignature ? '*' : ' '} ${s.label.slice(0, 220)}${i === help.activeSignature ? `   ← ${current}` : ''}`);
        });
        return { label, ok: signatures.length > 0 };
      }
      case 'inlayHints': {
        // Every hint of the file, printed as `line: <word before the hint> <hint>`.
        const lines = text.split('\n');
        const end = { line: lines.length, character: 0 };
        const hints = (await request('textDocument/inlayHint', { textDocument: { uri }, range: { start: { line: 0, character: 0 }, end } })) ?? [];
        log(`${label}: ${hints.length} hints (${Date.now() - t0} ms)`);
        for (const h of hints) {
          const line = lines[h.position.line] ?? '';
          const before = [...line.slice(0, h.position.character).matchAll(/(\w+)\s*:/g)].pop()?.[1] ?? line.slice(0, h.position.character).trim().split(/\s+/).pop();
          const tip = typeof h.tooltip === 'string' ? h.tooltip : h.tooltip?.value ?? '';
          log(`    hint L${h.position.line + 1} ${before} -> ${typeof h.label === 'string' ? h.label : h.label.map((p) => p.value).join('')} | ${tip}`);
        }
        return { label, hints: hints.length };
      }
      case 'references': {
        const result = (await request('textDocument/references', { textDocument: { uri }, position, context: { includeDeclaration: false } })) ?? [];
        const byModule = {};
        for (const loc of result) {
          const rel = path.relative(filesRoot, fileURLToPath(loc.uri));
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
        // The line each location points at, to check it lands on the declaration.
        for (const l of locations) {
          try {
            const target = fs.readFileSync(fileURLToPath(l.uri ?? l.targetUri), 'utf8').split('\n');
            const line = (l.range ?? l.targetSelectionRange).start.line;
            log(`    L${line + 1}: ${target[line]?.trim().slice(0, 120)}`);
          } catch {}
        }
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
let serverCapabilities;
(async () => {
  const rootUri = pathToFileURL(root).toString();
  const init = await request('initialize', {
    processId: process.pid,
    clientInfo: { name: 'koda-probe' },
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
    initializationOptions: {
      defaultSdk: args['default-sdk'],
      buildTools: {},
      projects,
      disableRocksDBWriteAheadLog: false,
      ...(args['init-options'] ? JSON.parse(args['init-options']) : {}),
    },
  });
  serverCapabilities = init?.capabilities;
  log(`initialized: ${init?.serverInfo?.name ?? '?'} ${init?.serverInfo?.version ?? ''}`);
  notify('initialized', {});
  if (args['wait-ready']) await serverReady;
  else await waitForIdle();
  log(`import/indexing idle after ${((Date.now() - started) / 1000).toFixed(0)}s`);

  if (args.export) {
    await request('workspace/executeCommand', { command: 'exportWorkspace', arguments: [root] }).catch((e) => log(`export failed: ${e.message}`));
    log(`exported workspace.json to ${root}`);
  }

  const results = [];
  for (const probe of probes) results.push(await runProbe(probe));
  log(`peak RSS ${(peakRssKb / 1024).toFixed(0)} MB`);
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
