// Runs inside the VS Code extension host (see run.mjs). Fails by throwing.
const assert = require('node:assert');
const path = require('node:path');
const vscode = require('vscode');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (...args) => console.log('[e2e]', ...args);

async function poll(what, minutes, attempt) {
  const deadline = Date.now() + minutes * 60_000;
  let last;
  while (Date.now() < deadline) {
    last = await attempt();
    if (last.ok) return last;
    await sleep(5000);
  }
  throw new Error(`timed out waiting for ${what}: ${JSON.stringify(last?.detail ?? last).slice(0, 2000)}`);
}

exports.run = async function run() {
  const project = process.env.ANDROID_BRIDGE_E2E_PROJECT;
  const extension = vscode.extensions.getExtension('android-bridge.android-bridge');
  assert.ok(extension, 'Android Bridge is not loaded');
  assert.ok(vscode.extensions.getExtension('jetbrains.kotlin-server'), 'Kotlin by JetBrains is not loaded');
  const api = await extension.activate();
  assert.ok(api.syncService, 'no Android Gradle root detected');

  const started = Date.now();
  const ok = await vscode.commands.executeCommand('androidBridge.sync');
  assert.strictEqual(ok, true, `sync failed: ${JSON.stringify(api.syncService.state)}`);
  log(`sync ok in ${((Date.now() - started) / 1000).toFixed(0)}s: ${JSON.stringify(api.syncService.state)}`);

  const open = async (relative) => {
    const document = await vscode.workspace.openTextDocument(path.join(project, relative));
    await vscode.window.showTextDocument(document);
    return document;
  };
  const at = (document, text, offset) => {
    const index = document.getText().indexOf(text);
    assert.ok(index >= 0, `"${text}" not in ${document.uri.fsPath}`);
    return document.positionAt(index + offset);
  };

  // Completion: proves the server imported our workspace.json and finished indexing.
  const viewModel = await open('feature/home/src/main/kotlin/com/skydoves/pokedex/compose/feature/home/HomeViewModel.kt');
  const completionAt = at(viewModel, 'homeRepository.fetchPokemonList', 'homeRepository.'.length);
  await poll('completion', 10, async () => {
    const list = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', viewModel.uri, completionAt);
    const labels = (list?.items ?? []).map((i) => (typeof i.label === 'string' ? i.label : i.label.label));
    return { ok: labels.includes('fetchPokemonList'), detail: labels.slice(0, 20) };
  });
  log(`completion ok after ${((Date.now() - started) / 1000).toFixed(0)}s`);

  // Library-module R class (unresolved with the server's own Gradle import) and other generated code.
  for (const file of [
    'feature/home/src/main/kotlin/com/skydoves/pokedex/compose/feature/home/PokedexHome.kt',
    'core/network/src/main/kotlin/com/skydoves/pokedex/compose/core/network/di/NetworkModule.kt',
    'core/datastore/src/main/kotlin/com/skydoves/pokedex/compose/core/datastore/UserPreferencesSerializer.kt',
  ]) {
    const document = await open(file);
    const result = await poll(`diagnostics of ${file}`, 2, async () => {
      const diagnostics = vscode.languages.getDiagnostics(document.uri);
      const errors = diagnostics.filter((d) => d.severity === vscode.DiagnosticSeverity.Error).map((d) => `L${d.range.start.line + 1} ${d.message}`);
      return { ok: errors.length === 0, detail: errors };
    });
    log(`no errors in ${path.basename(file)}`, result.detail);
  }

  // Cross-module references.
  const pokemon = await open('core/model/src/main/kotlin/com/skydoves/pokedex/compose/core/model/Pokemon.kt');
  const declaration = at(pokemon, 'data class Pokemon(', 'data class '.length);
  const references = (await vscode.commands.executeCommand('vscode.executeReferenceProvider', pokemon.uri, declaration)) ?? [];
  const modules = new Set(references.map((r) => path.relative(project, r.uri.fsPath).split(path.sep).slice(0, 2).join('/')));
  log(`references: ${references.length} in ${[...modules].join(', ')}`);
  assert.ok(references.length > 10 && modules.has('feature/home'), 'cross-module references missing');

  // Usages CodeLens, resolved through the same reference search.
  const lenses = await poll('usages CodeLens', 3, async () => {
    const all = (await vscode.commands.executeCommand('vscode.executeCodeLensProvider', pokemon.uri, 100)) ?? [];
    const titles = all.map((l) => l.command?.title).filter(Boolean);
    return { ok: titles.some((t) => /^\d+ usages?$/.test(t)), detail: titles };
  });
  log(`codelens: ${JSON.stringify(lenses.detail)}`);
  log('ALL CHECKS PASSED');
};
