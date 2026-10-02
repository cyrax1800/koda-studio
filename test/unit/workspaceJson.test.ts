import { describe, expect, it } from 'vitest';

import { javaLanguageLevel, toWorkspaceJson } from '../../src/intellij/workspaceJson.ts';
import type { DependencyData, ModuleData, WorkspaceData } from '../../src/intellij/workspaceJsonTypes.ts';
import { demoModel, fakeFs, GRADLE_HOME, JDK, ROOT, SDK } from './fixtures.ts';

function convert(missing: string[] = [], options: { androidSdkSources?: boolean } = {}) {
  const model = demoModel();
  return toWorkspaceJson(model, { librarySources: 'cached', gradleUserHome: GRADLE_HOME, fs: fakeFs(model, missing), ...options });
}

function module(workspace: WorkspaceData, name: string): ModuleData {
  const found = workspace.modules.find((m) => m.name === name);
  if (!found) throw new Error(`no module ${name}; have ${workspace.modules.map((m) => m.name).join(', ')}`);
  return found;
}

const names = (deps: DependencyData[]) => deps.map((d) => ('name' in d ? `${d.type}:${d.name}` : d.type));

// Keys the kotlin-lsp v263.4702.0 importer accepts (model.kt at that tag). It rejects the whole file otherwise.
const ALLOWED: Record<string, string[]> = {
  workspace: ['modules', 'libraries', 'sdks', 'kotlinSettings', 'javaSettings', 'externalSystem'],
  module: ['name', 'type', 'coordinate', 'dependencies', 'contentRoots', 'facets', 'externalProjectPath', 'externalProjectId'],
  'dependency:module': ['type', 'name', 'scope', 'isExported', 'isTestJar'],
  'dependency:library': ['type', 'name', 'scope', 'isExported'],
  'dependency:sdk': ['type', 'name', 'kind'],
  'dependency:moduleSource': ['type'],
  'dependency:inheritedSdk': ['type'],
  contentRoot: ['path', 'excludedPatterns', 'excludedUrls', 'sourceRoots'],
  sourceRoot: ['path', 'type'],
  library: ['name', 'level', 'module', 'type', 'roots', 'excludedRoots', 'properties'],
  libraryRoot: ['path', 'type', 'inclusionOptions'],
  sdk: ['name', 'type', 'version', 'homePath', 'roots', 'additionalData'],
  kotlinSettings: [
    'name', 'sourceRoots', 'configFileItems', 'module', 'useProjectSettings', 'implementedModuleNames',
    'dependsOnModuleNames', 'additionalVisibleModuleNames', 'productionOutputPath', 'testOutputPath', 'sourceSetNames',
    'isTestModule', 'externalProjectId', 'isHmppEnabled', 'pureKotlinSourceFolders', 'kind', 'compilerArguments',
    'additionalArguments', 'scriptTemplates', 'scriptTemplatesClasspath', 'copyJsLibraryFiles',
    'outputDirectoryForJsLibraryFiles', 'targetPlatform', 'externalSystemRunTasks', 'version', 'flushNeeded',
  ],
  javaSettings: [
    'module', 'inheritedCompilerOutput', 'excludeOutput', 'compilerOutputs', 'compilerOutputsForTests', 'languageLevelId',
    'manifestAttributes', 'compilerArguments',
  ],
  compilerArguments: ['languageVersion', 'jvmTarget', 'pluginOptions', 'pluginClasspaths'],
};

function checkKeys(kind: string, value: object): void {
  const extra = Object.keys(value).filter((k) => !ALLOWED[kind]!.includes(k));
  expect(extra, `${kind} has keys the server does not know`).toEqual([]);
}

describe('toWorkspaceJson', () => {
  it('only uses keys the installed server understands', () => {
    const { workspace } = convert();
    checkKeys('workspace', workspace);
    for (const m of workspace.modules) {
      checkKeys('module', m);
      for (const d of m.dependencies) checkKeys(`dependency:${d.type}`, d);
      for (const r of m.contentRoots) {
        checkKeys('contentRoot', r);
        for (const s of r.sourceRoots ?? []) checkKeys('sourceRoot', s);
      }
    }
    for (const l of workspace.libraries) {
      checkKeys('library', l);
      expect(l).toHaveProperty('type');
      for (const r of l.roots) checkKeys('libraryRoot', r);
    }
    for (const s of workspace.sdks) checkKeys('sdk', s);
    for (const k of workspace.kotlinSettings) {
      checkKeys('kotlinSettings', k);
      expect(k.compilerArguments?.startsWith('J{')).toBe(true);
      checkKeys('compilerArguments', JSON.parse(k.compilerArguments!.slice(1)));
    }
    for (const j of workspace.javaSettings) checkKeys('javaSettings', j);
  });

  it('creates holder and per-component modules like the server’s own Gradle import', () => {
    const { workspace } = convert();
    expect(workspace.modules.map((m) => m.name)).toEqual([
      'demo',
      'demo.app',
      'demo.app.prodDebug',
      'demo.app.prodDebugUnitTest',
      'demo.feature.home',
      'demo.feature.home.prodDebug',
      'demo.core.model',
      'demo.core.model.main',
      'demo.core.model.test',
    ]);
    expect(module(workspace, 'demo.app')).toMatchObject({ externalProjectId: ':app', contentRoots: [{ path: `${ROOT}/app` }] });
  });

  it('orders Android dependencies: SDK, own sources, own R jar, modules with their R jars, libraries, JDK', () => {
    const { workspace } = convert();
    expect(names(module(workspace, 'demo.app.prodDebug').dependencies)).toEqual([
      'library:Gradle: android:android-sdk:android-36',
      'moduleSource',
      'library:Gradle: android:r::app:prodDebug',
      'module:demo.feature.home.prodDebug',
      'library:Gradle: android:r::feature:home:prodDebug',
      'module:demo.core.model.main',
      'library:Gradle: androidx.core:core:1.16.0',
      'library:Gradle: com.squareup.okhttp3:okhttp:4.12.0',
      'sdk:21',
    ]);
    for (const d of module(workspace, 'demo.app.prodDebug').dependencies) {
      if ('scope' in d) expect(d.scope).toBe('provided');
    }
  });

  it('never puts the module’s own javac/kotlinc output on its classpath', () => {
    const { workspace } = convert();
    const roots = workspace.libraries.flatMap((l) => l.roots.map((r) => r.path));
    expect(roots.some((r) => r.includes('/intermediates/javac/') || r.includes('/built_in_kotlinc/'))).toBe(false);
  });

  it('makes test modules depend on and see internals of the main module', () => {
    const { workspace } = convert();
    const test = module(workspace, 'demo.app.prodDebugUnitTest');
    expect(names(test.dependencies)).toContain('module:demo.app.prodDebug');
    expect(names(test.dependencies)).toContain('library:Gradle: android:r::app:prodDebugUnitTest');
    expect(test.contentRoots.flatMap((r) => r.sourceRoots ?? []).every((s) => s.type === 'java-test' || s.type === 'java-test-resource')).toBe(true);
    const kotlin = workspace.kotlinSettings.find((k) => k.module === 'demo.app.prodDebugUnitTest')!;
    expect(kotlin.isTestModule).toBe(true);
    expect(kotlin.additionalVisibleModuleNames).toEqual(['demo.app.prodDebug']);
  });

  it('adds one content root per source provider and per generated folder', () => {
    const { workspace } = convert();
    const roots = module(workspace, 'demo.feature.home.prodDebug').contentRoots;
    expect(roots.map((r) => r.path)).toEqual([
      `${ROOT}/feature/home/src/main`,
      `${ROOT}/feature/home/src/debug`,
      `${ROOT}/feature/home/build/generated/data_binding_base_class_source_out/prodDebug/out`,
      `${ROOT}/feature/home/build/generated/ksp/prodDebug/kotlin`,
    ]);
    expect(roots[0]!.sourceRoots).toEqual([
      { path: `${ROOT}/feature/home/src/main/java`, type: 'java-source' },
      { path: `${ROOT}/feature/home/src/main/kotlin`, type: 'java-source' },
      { path: `${ROOT}/feature/home/src/main/resources`, type: 'java-resource' },
    ]);
  });

  it('skips folders and jars that do not exist yet', () => {
    const missingR = `${ROOT}/feature/home/build/intermediates/compile_r_class_jar/prodDebug/generateProdDebugRFile/R.jar`;
    const missingKsp = `${ROOT}/feature/home/build/generated/ksp/prodDebug/kotlin`;
    const { workspace } = convert([missingR, missingKsp]);
    const home = module(workspace, 'demo.feature.home.prodDebug');
    expect(names(home.dependencies)).not.toContain('library:Gradle: android:r::feature:home:prodDebug');
    expect(home.contentRoots.map((r) => r.path)).not.toContain(missingKsp);
  });

  it('dedupes libraries shared by modules and attaches cached sources', () => {
    const { workspace } = convert();
    expect(workspace.libraries.filter((l) => l.name === 'Gradle: androidx.core:core:1.16.0')).toHaveLength(1);
    const okhttp = workspace.libraries.find((l) => l.name === 'Gradle: com.squareup.okhttp3:okhttp:4.12.0')!;
    expect(okhttp.roots).toEqual([
      { path: `${GRADLE_HOME}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0/h1/okhttp-4.12.0.jar` },
      { path: `${GRADLE_HOME}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0/h9/okhttp-4.12.0-sources.jar`, type: 'SOURCES' },
    ]);
  });

  it('attaches Android SDK sources only when asked', () => {
    const sdk = (w: WorkspaceData) => w.libraries.find((l) => l.name === 'Gradle: android:android-sdk:android-36')!;
    expect(sdk(convert().workspace).roots.some((r) => r.type === 'SOURCES')).toBe(false);
    expect(sdk(convert([], { androidSdkSources: true }).workspace).roots).toContainEqual({ path: `${SDK}/sources/android-36`, type: 'SOURCES' });
  });

  it('passes Kotlin compiler settings the way the server exports them', () => {
    const { workspace } = convert();
    const app = workspace.kotlinSettings.find((k) => k.module === 'demo.app.prodDebug')!;
    expect(JSON.parse(app.compilerArguments!.slice(1))).toEqual({
      languageVersion: '2.2',
      jvmTarget: '17',
      pluginOptions: ['plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true'],
      pluginClasspaths: [`${GRADLE_HOME}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.2.21/h2/compose.jar`],
    });
    expect(app.additionalArguments).toBe('-Xexplicit-backing-fields -opt-in=kotlin.RequiresOptIn');
    expect(app.sourceRoots).toContain(`${ROOT}/app/build/generated/source/buildConfig/prod/debug`);
  });

  it('lays out JVM modules with src/<set> content roots and generated folders separately', () => {
    const { workspace } = convert();
    const main = module(workspace, 'demo.core.model.main');
    expect(main.contentRoots).toEqual([
      {
        path: `${ROOT}/core/model/src/main`,
        sourceRoots: [
          { path: `${ROOT}/core/model/src/main/java`, type: 'java-source' },
          { path: `${ROOT}/core/model/src/main/kotlin`, type: 'java-source' },
          { path: `${ROOT}/core/model/src/main/resources`, type: 'java-resource' },
        ],
      },
      {
        path: `${ROOT}/core/model/build/generated/ksp/main/kotlin`,
        sourceRoots: [{ path: `${ROOT}/core/model/build/generated/ksp/main/kotlin`, type: 'java-source' }],
      },
    ]);
    expect(names(module(workspace, 'demo.core.model.test').dependencies)).toEqual([
      'moduleSource',
      'module:demo.core.model.main',
      'sdk:21',
    ]);
  });

  it('describes the JDK the daemon ran on', () => {
    const { workspace } = convert();
    expect(workspace.sdks).toEqual([{ name: '21', type: 'jdk', version: 'JDK_21', homePath: JDK, additionalData: '' }]);
  });
});

describe('javaLanguageLevel', () => {
  it.each([
    ['17', 'JDK_17'],
    ['1.8', 'JDK_1_8'],
    ['VERSION_11', 'JDK_11'],
    ['JavaVersion.VERSION_1_8', 'JDK_1_8'],
    ['21', 'JDK_21'],
  ])('%s → %s', (input, expected) => {
    expect(javaLanguageLevel(input)).toBe(expected);
  });

  it('returns null for unknown input', () => {
    expect(javaLanguageLevel(undefined)).toBeNull();
    expect(javaLanguageLevel('latest')).toBeNull();
  });
});
