// Runs inside the VS Code extension host (see run.mjs). Fails by throwing. What it checks and times comes from the
// project's profile (profiles/<project>.cjs); the timings go to the results file run.mjs names.
const assert = require('node:assert');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const vscode = require('vscode');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (...args) => console.log('[e2e]', ...args);

async function poll(what, minutes, attempt, intervalMs = 2000) {
  const deadline = Date.now() + minutes * 60_000;
  let last;
  while (Date.now() < deadline) {
    last = await attempt();
    if (last.ok) return last;
    await sleep(intervalMs);
  }
  throw new Error(`timed out waiting for ${what}: ${JSON.stringify(last?.detail ?? last).slice(0, 2000)}`);
}

/** Resident memory (MB) of our Kotlin server processes, the ones started from this extension's server/ folder. */
function serverRssMb(serverDir) {
  try {
    const out = execFileSync('ps', ['-axo', 'rss=,command='], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
    return out
      .split('\n')
      .filter((line) => line.includes(serverDir))
      .reduce((sum, line) => sum + Number(line.trim().split(/\s+/)[0] || 0), 0) / 1024;
  } catch {
    return 0;
  }
}

exports.run = async function run() {
  const project = process.env.KODA_E2E_PROJECT;
  const pass = process.env.KODA_E2E_PASS ?? 'clean';
  const profile = require(process.env.KODA_E2E_PROFILE);
  const results = { project: profile.name, pass, at: new Date().toISOString(), timings: [], failures: [] };
  const record = (name, ms, extra = {}) => {
    const slow = extra.budgetMs !== undefined && ms > extra.budgetMs;
    results.timings.push({ name, ms: Math.round(ms), ...extra, slow });
    log(`${slow ? 'SLOW ' : ''}${name}: ${Math.round(ms)} ms${extra.budgetMs !== undefined ? ` (budget ${extra.budgetMs})` : ''}${extra.note ? ` – ${extra.note}` : ''}`);
  };
  const check = (ok, message) => {
    if (!ok) {
      results.failures.push(message);
      log(`FAIL ${message}`);
    }
  };

  const extension = vscode.extensions.getExtension('koda-studio.koda-studio');
  assert.ok(extension, 'Koda Studio is not loaded');
  const api = await extension.activate();
  assert.ok(api.syncService, 'no Android Gradle root detected');
  const serverDir = path.join(extension.extensionPath, 'server', 'kotlin-lsp');
  let peakRss = 0;
  const sampler = setInterval(() => (peakRss = Math.max(peakRss, serverRssMb(serverDir))), 2000);

  try {
    const started = Date.now();
    // A Gradle sync when asked (`--sync`) or when the project was never synced; otherwise the last sync is used, as
    // on a normal start.
    if (process.env.KODA_E2E_SYNC === '1' || !api.syncService.lastModel()) {
      const ok = await vscode.commands.executeCommand('kodaStudio.sync');
      assert.strictEqual(ok, true, `sync failed: ${JSON.stringify(api.syncService.state)}`);
      record('Gradle sync', Date.now() - started);
    }

    await poll('Kotlin server', 5, async () => ({ ok: api.server.state === 'running', detail: api.server.state }));
    record('server started', Date.now() - started);
    // Ready, after the one restart that follows indexing libraries (restartRecommended).
    let ready = 0;
    await poll('index ready', profile.minutes.ready, async () => {
      const progress = api.server.progress;
      const done = api.server.state === 'running' && progress?.state === 'ready' && !progress.restartRecommended;
      ready = done ? ready + 1 : 0;
      return { ok: ready >= 3, detail: progress };
    });
    record('index ready', Date.now() - started, { note: JSON.stringify({ indexed: api.server.progress.indexed }) });

    const open = async (relative) => {
      const document = await vscode.workspace.openTextDocument(path.join(project, relative));
      await vscode.window.showTextDocument(document);
      return document;
    };
    const at = (document, text, offset = 0) => {
      const index = document.getText().indexOf(text);
      assert.ok(index >= 0, `"${text}" not in ${document.uri.fsPath}`);
      return document.positionAt(index + offset);
    };
    const errorsOf = (document) =>
      vscode.languages
        .getDiagnostics(document.uri)
        .filter((d) => d.severity === vscode.DiagnosticSeverity.Error)
        .map((d) => `L${d.range.start.line + 1} ${d.message}`);
    const labelsOf = (list) => (list?.items ?? []).map((i) => (typeof i.label === 'string' ? i.label : i.label.label));
    /** Undoes edits made for a check: the files are never saved. */
    const revert = async (document) => {
      await vscode.window.showTextDocument(document);
      await vscode.commands.executeCommand('workbench.action.files.revert');
    };

    // Errors: time until a file's first diagnostics arrive after opening it, then none may be errors.
    for (const entry of profile.clean) {
      const { file, budgetMs } = typeof entry === 'string' ? { file: entry } : entry;
      const opened = Date.now();
      const target = path.join(project, file);
      let published = 0;
      let sawAnalyzing = false;
      let analyzedAt = 0;
      const subscriptions = [
        vscode.languages.onDidChangeDiagnostics((e) => {
          if (e.uris.some((u) => u.fsPath === target)) published = Date.now();
        }),
        // A file without problems may publish an empty set, which VS Code doesn't report as a change: the status
        // bar's "analyzing <file>" ending says the check is done too.
        api.server.onDidChangeProgress((progress) => {
          if (progress.analyzing === path.basename(file)) sawAnalyzing = true;
          else if (sawAnalyzing && !analyzedAt) analyzedAt = Date.now();
        }),
      ];
      const document = await open(file);
      await poll(`diagnostics of ${file}`, 5, async () => ({ ok: published > 0 || analyzedAt > 0 }), 200);
      const firstAt = Math.min(published || Infinity, analyzedAt || Infinity);
      // Then until they settle (a later run replaces the first).
      await poll(`settled diagnostics of ${file}`, 5, async () => ({ ok: Date.now() - Math.max(published, analyzedAt) > 3000 }), 500);
      subscriptions.forEach((s) => s.dispose());
      record(`errors shown: ${path.basename(file)}`, firstAt - opened, { budgetMs });
      const errors = errorsOf(document);
      check(errors.length === 0, `errors in ${path.basename(file)}: ${errors.slice(0, 10).join(' | ')}`);
    }

    // Completion, typed one character at a time.
    for (const c of profile.completions) {
      const document = await open(c.file);
      const start = at(document, c.find, c.offset);
      let typed = c.insert ?? '';
      if (typed) await vscode.window.activeTextEditor.edit((b) => b.insert(start, typed));
      let position = document.positionAt(document.offsetAt(start) + typed.length);
      let list;
      let lastKey = 0;
      for (const ch of c.type ?? '') {
        await vscode.window.activeTextEditor.edit((b) => b.insert(position, ch));
        position = position.translate(0, 1);
        lastKey = Date.now();
        await sleep(100);
      }
      const asked = Date.now();
      list = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', document.uri, position);
      const labels = labelsOf(list);
      const missing = c.expect.filter((e) => !labels.includes(e));
      record(`completion: ${c.name}`, Date.now() - asked, { budgetMs: c.budgetMs, note: `${labels.length} items` });
      check(missing.length === 0, `completion ${c.name}: missing ${missing.join(', ')}; got ${labels.slice(0, 10).join(', ')}`);
      if (typed || c.type) await revert(document);
      void lastKey;
    }

    // Edits must not leave wrong errors behind: the files typed in are checked again once their errors settle.
    for (const file of [...new Set(profile.completions.filter((c) => c.insert || c.type).map((c) => c.file))]) {
      const document = await open(file);
      await sleep(5000);
      const errors = errorsOf(document);
      log(`errors after editing ${path.basename(file)}: ${errors.length}`);
      check(errors.length === 0, `errors after editing ${path.basename(file)}: ${errors.slice(0, 10).join(' | ')}`);
    }

    for (const d of profile.definitions) {
      const document = await open(d.file);
      const asked = Date.now();
      const targets = (await vscode.commands.executeCommand('vscode.executeDefinitionProvider', document.uri, at(document, d.find, d.offset))) ?? [];
      record(`go to declaration: ${d.name}`, Date.now() - asked, { budgetMs: d.budgetMs });
      const files = targets.map((t) => path.basename((t.targetUri ?? t.uri).fsPath));
      check(files.some((f) => f.includes(d.expectFile)), `go to declaration ${d.name}: ${files.join(', ') || 'nothing'}`);
    }

    for (const h of profile.hovers) {
      const document = await open(h.file);
      const asked = Date.now();
      const hovers = (await vscode.commands.executeCommand('vscode.executeHoverProvider', document.uri, at(document, h.find, h.offset))) ?? [];
      record(`hover: ${h.name}`, Date.now() - asked, { budgetMs: h.budgetMs });
      const text = hovers.flatMap((x) => x.contents.map((c) => (typeof c === 'string' ? c : c.value))).join('\n');
      check(text.includes(h.expect), `hover ${h.name}: ${text.slice(0, 200) || 'nothing'}`);
    }

    for (const r of profile.references) {
      const document = await open(r.file);
      const asked = Date.now();
      const references = (await vscode.commands.executeCommand('vscode.executeReferenceProvider', document.uri, at(document, r.find, r.offset))) ?? [];
      const modules = new Set(references.map((x) => path.relative(project, x.uri.fsPath).split(path.sep).slice(0, 2).join('/')));
      record(`references: ${r.name}`, Date.now() - asked, { budgetMs: r.budgetMs, note: `${references.length} in ${modules.size} modules` });
      check(references.length >= r.min, `references ${r.name}: ${references.length} < ${r.min}`);
      if (r.module) check(modules.has(r.module), `references ${r.name}: none in ${r.module}`);
    }

    for (const l of profile.codeLens) {
      const document = await open(l.file);
      const asked = Date.now();
      const lenses = (await vscode.commands.executeCommand('vscode.executeCodeLensProvider', document.uri, l.resolve)) ?? [];
      const titles = lenses.map((x) => x.command?.title).filter((t) => t && /usage/.test(t));
      record(`CodeLens: ${l.name}`, Date.now() - asked, { budgetMs: l.budgetMs, note: `${lenses.length} lenses, ${titles.length} counted` });
      check(titles.length > 0, `CodeLens ${l.name}: no usage counts`);
    }

    if (profile.formatting) {
      const document = await open(profile.formatting);
      const organize =
        (await vscode.commands.executeCommand('vscode.executeCodeActionProvider', document.uri, new vscode.Range(0, 0, 0, 0), 'source.organizeImports')) ?? [];
      check(organize.some((a) => a.kind?.value === 'source.organizeImports'), 'no Optimize Imports action');
      const end = document.lineAt(document.lineCount - 1).range.end;
      await vscode.window.activeTextEditor.edit((b) => b.insert(end, '\nval  answer=listOf( 1,2 )\n'));
      await sleep(1000);
      const formatting = (await vscode.commands.executeCommand('vscode.executeFormatDocumentProvider', document.uri, { tabSize: 2, insertSpaces: true })) ?? [];
      await revert(document);
      check(formatting.length > 0, 'Reformat Code changed nothing in badly spaced code');
      log(`optimize imports: ${organize.length} action(s), format: ${formatting.length} edit(s)`);
    }

    // Restarting on purpose must not look like a crash, and completion must come back.
    const restarted = Date.now();
    await vscode.commands.executeCommand('kodaStudio.restartServer');
    check(api.server.state === 'running', `after restart: ${api.server.state}`);
    const first = profile.completions[0];
    const document = await open(first.file);
    await poll('completion after restart', 5, async () => {
      if (first.insert || first.type) return { ok: api.server.progress?.state === 'ready' };
      const list = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', document.uri, at(document, first.find, first.offset));
      return { ok: labelsOf(list).includes(first.expect[0]) };
    });
    record('restart until usable', Date.now() - restarted);
  } finally {
    clearInterval(sampler);
    results.peakServerRssMb = Math.round(peakRss);
    log(`peak server memory: ${results.peakServerRssMb} MB`);
    if (process.env.KODA_E2E_RESULTS) fs.writeFileSync(process.env.KODA_E2E_RESULTS, JSON.stringify(results, null, 2));
  }
  const slow = results.timings.filter((t) => t.slow).map((t) => t.name);
  if (slow.length) log(`slower than budget: ${slow.join('; ')}`);
  assert.deepStrictEqual(results.failures, [], 'checks failed');
  log('ALL CHECKS PASSED');
};
