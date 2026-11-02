import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { JDK_MODULE, toModulesJson, type SerializedModule } from '../../src/server/modulesJson.ts';
import { demoModel, fakeFs, GRADLE_HOME, JDK, ROOT, SDK } from './fixtures.ts';

function convert(missing: string[] = [], options: { androidSdkSources?: boolean } = {}) {
  const model = demoModel();
  return toModulesJson(model, { librarySources: 'cached', gradleUserHome: GRADLE_HOME, fs: fakeFs(model, missing), ...options });
}

function module(modules: SerializedModule[], id: string): SerializedModule {
  const found = modules.find((m) => m.id === id);
  if (!found) throw new Error(`no module ${id}; have ${modules.map((m) => m.id).join(', ')}`);
  return found;
}

// Keys `SerializedModule` in lsp/app/src/main/kotlin/org/kotlinlsp/analysis/modules/Serialization.kt declares.
// The fields of the server's `SerializedModule`, read from its source so the two can't drift apart.
const SERVER_KEYS = [
  .../data class SerializedModule\(([\s\S]*?)\n\)/
    .exec(readFileSync(path.join(__dirname, '../../lsp/app/src/main/kotlin/org/kotlinlsp/analysis/modules/Serialization.kt'), 'utf8'))![1]!
    .matchAll(/val (\w+):/g),
].map((m) => m[1]!);

describe('toModulesJson', () => {
  it('only uses keys the server declares', () => {
    for (const m of convert().modules) expect(Object.keys(m).filter((k) => !SERVER_KEYS.includes(k))).toEqual([]);
  });

  it('creates one source module per Android component and JVM source set', () => {
    const sources = convert().modules.filter((m) => m.isSource).map((m) => m.id);
    expect(sources).toEqual(['demo.app.prodDebug', 'demo.app.prodDebugUnitTest', 'demo.feature.home.prodDebug', 'demo.core.model.main', 'demo.core.model.test']);
  });

  it('orders Android dependencies: SDK, own R jar, modules with their R jars, libraries, JDK', () => {
    expect(module(convert().modules, 'demo.app.prodDebug').dependencies).toEqual([
      'Gradle: android:android-sdk:android-36',
      'Gradle: android:r::app:prodDebug',
      'demo.feature.home.prodDebug',
      'Gradle: android:r::feature:home:prodDebug',
      'demo.core.model.main',
      'Gradle: androidx.core:core:1.16.0',
      'Gradle: com.squareup.okhttp3:okhttp:4.12.0',
      JDK_MODULE,
    ]);
  });

  it('only refers to modules that exist', () => {
    const { modules } = convert();
    const ids = new Set(modules.map((m) => m.id));
    for (const m of modules) for (const d of m.dependencies) expect(ids.has(d), `${m.id} -> ${d}`).toBe(true);
  });

  it('never puts the module’s own javac/kotlinc output on its classpath', () => {
    const roots = convert().modules.filter((m) => !m.isSource).flatMap((m) => m.contentRoots);
    expect(roots.some((r) => r.includes('/intermediates/javac/') || r.includes('/built_in_kotlinc/'))).toBe(false);
  });

  it('makes test modules depend on the main module', () => {
    const test = module(convert().modules, 'demo.app.prodDebugUnitTest');
    expect(test.dependencies).toContain('demo.app.prodDebug');
    expect(test.dependencies).toContain('Gradle: android:r::app:prodDebugUnitTest');
  });

  it('lets test modules see internal declarations of their main module', () => {
    const modules = convert().modules;
    expect(module(modules, 'demo.app.prodDebugUnitTest').friends).toEqual(['demo.app.prodDebug']);
    expect(module(modules, 'demo.core.model.test').friends).toEqual(['demo.core.model.main']);
    expect(module(modules, 'demo.app.prodDebug').friends).toBeUndefined();
    expect(module(modules, 'demo.core.model.main').friends).toBeUndefined();
  });

  it('lists source folders of every source provider and generated folders', () => {
    expect(module(convert().modules, 'demo.feature.home.prodDebug').contentRoots).toEqual([
      `${ROOT}/feature/home/src/main/java`,
      `${ROOT}/feature/home/src/main/kotlin`,
      `${ROOT}/feature/home/src/debug/java`,
      `${ROOT}/feature/home/src/debug/kotlin`,
      `${ROOT}/feature/home/build/generated/data_binding_base_class_source_out/prodDebug/out`,
      `${ROOT}/feature/home/build/generated/ksp/prodDebug/kotlin`,
    ]);
  });

  it('skips folders and jars that do not exist yet', () => {
    const missingR = `${ROOT}/feature/home/build/intermediates/compile_r_class_jar/prodDebug/generateProdDebugRFile/R.jar`;
    const missingKsp = `${ROOT}/feature/home/build/generated/ksp/prodDebug/kotlin`;
    const home = module(convert([missingR, missingKsp]).modules, 'demo.feature.home.prodDebug');
    expect(home.dependencies).not.toContain('Gradle: android:r::feature:home:prodDebug');
    expect(home.contentRoots).not.toContain(missingKsp);
  });

  it('counts libraries whose jars were deleted from the Gradle cache', () => {
    expect(convert().missingLibraries).toBe(0);
    // A Gradle upgrade deletes the old version's transformed AAR jars.
    const { modules, missingLibraries, warnings } = convert([`${GRADLE_HOME}/caches/transforms/aa/transformed/core-1.16.0-api.jar`]);
    expect(missingLibraries).toBe(1);
    expect(modules.map((m) => m.id)).not.toContain('Gradle: androidx.core:core:1.16.0');
    expect(warnings.join('\n')).toContain('androidx.core:core:1.16.0');
  });

  it('dedupes libraries shared by modules, attaches cached sources and resolves them against the JDK and android.jar', () => {
    const { modules } = convert();
    expect(modules.filter((m) => m.id === 'Gradle: androidx.core:core:1.16.0')).toHaveLength(1);
    expect(module(modules, 'Gradle: com.squareup.okhttp3:okhttp:4.12.0')).toMatchObject({
      contentRoots: [`${GRADLE_HOME}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0/h1/okhttp-4.12.0.jar`],
      sourceRoots: [`${GRADLE_HOME}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0/h9/okhttp-4.12.0-sources.jar`],
      dependencies: [JDK_MODULE, 'Gradle: android:android-sdk:android-36'],
      isSource: false,
    });
  });

  it('attaches Android SDK sources only when asked', () => {
    const sdk = (modules: SerializedModule[]) => module(modules, 'Gradle: android:android-sdk:android-36');
    expect(sdk(convert().modules).sourceRoots).toBeNull();
    expect(sdk(convert([], { androidSdkSources: true }).modules).sourceRoots).toEqual([`${SDK}/sources/android-36`]);
  });

  it('takes the Kotlin language version, JVM target and compiler arguments from the compiler options', () => {
    expect(module(convert().modules, 'demo.app.prodDebug')).toMatchObject({
      kotlinVersion: '2.2',
      javaVersion: '17',
      compilerArguments: [
        '-Xexplicit-backing-fields',
        '-opt-in=kotlin.RequiresOptIn',
        `-Xplugin=${GRADLE_HOME}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.2.21/h2/compose.jar`,
        '-P',
        'plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true',
      ],
    });
    expect(module(convert().modules, 'demo.app.prodDebug').contentRoots).toContain(`${ROOT}/app/build/generated/source/buildConfig/prod/debug`);
  });

  it('lays out JVM modules from their source sets', () => {
    const { modules } = convert();
    expect(module(modules, 'demo.core.model.main').contentRoots).toEqual([
      `${ROOT}/core/model/src/main/java`,
      `${ROOT}/core/model/src/main/kotlin`,
      `${ROOT}/core/model/build/generated/ksp/main/kotlin`,
    ]);
    expect(module(modules, 'demo.core.model.test').dependencies).toEqual(['demo.core.model.main', JDK_MODULE]);
  });

  it('describes the JDK the daemon ran on', () => {
    expect(module(convert().modules, JDK_MODULE)).toEqual({
      id: JDK_MODULE,
      dependencies: [],
      contentRoots: [JDK],
      sourceRoots: null,
      javaVersion: '25',
      isSource: false,
      isJdk: true,
    });
  });
});
