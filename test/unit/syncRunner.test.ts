import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { javaVersion } from '../../src/gradle/javaHome.ts';
import { parseEvent, syncArguments } from '../../src/gradle/syncRunner.ts';

const java = { home: '/jdk', java: '/jdk/bin/java', version: 21, explicit: false, source: 'JAVA_HOME' };

describe('syncArguments', () => {
  it('passes the project, model file and init script to the sidecar', () => {
    const args = syncArguments({
      extensionPath: '/ext',
      projectDir: '/work/app',
      outFile: '/work/app/.gradle/android-bridge/model.json',
      variant: 'prodDebug',
      generate: 'light',
      gradleArguments: ['-Pfoo=bar', '--offline'],
      java,
    });
    expect(args).toEqual([
      '-jar', path.join('/ext', 'server', 'android-bridge-sync.jar'),
      '--project-dir', '/work/app',
      '--init-script', path.join('/ext', 'gradle', 'android-bridge.init.gradle'),
      '--out', '/work/app/.gradle/android-bridge/model.json',
      '--generate', 'light',
      '--variant', 'prodDebug',
      '--gradle-arg', '-Pfoo=bar',
      '--gradle-arg', '--offline',
    ]);
  });

  it('forces the JDK onto Gradle only when the user chose it', () => {
    const base = { extensionPath: '/e', projectDir: '/p', outFile: '/o', variant: undefined, generate: 'none' as const, gradleArguments: [] };
    expect(syncArguments({ ...base, java })).not.toContain('--java-home');
    expect(syncArguments({ ...base, java: { ...java, explicit: true } })).toEqual(expect.arrayContaining(['--java-home', '/jdk']));
  });
});

describe('parseEvent', () => {
  it('parses sidecar event lines', () => {
    expect(parseEvent('{"event":"phase","name":"generate","tasks":[":app:generateDebugRFile"]}')).toEqual({
      event: 'phase',
      name: 'generate',
      tasks: [':app:generateDebugRFile'],
    });
  });

  it('ignores anything else', () => {
    expect(parseEvent('> Task :app:compileDebugKotlin')).toBeUndefined();
    expect(parseEvent('{not json')).toBeUndefined();
    expect(parseEvent('{"no":"event"}')).toBeUndefined();
  });
});

describe('javaVersion', () => {
  const jdk = (release: string) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'jdk-'));
    fs.writeFileSync(path.join(dir, 'release'), release);
    return dir;
  };

  it('reads the major version from the release file', () => {
    expect(javaVersion(jdk('JAVA_VERSION="21.0.12.1"\n'))).toBe(21);
    expect(javaVersion(jdk('IMPLEMENTOR="x"\nJAVA_VERSION="17"\n'))).toBe(17);
    expect(javaVersion(jdk('JAVA_VERSION="1.8.0_392"\n'))).toBe(8);
  });

  it('returns undefined without a release file', () => {
    expect(javaVersion(fs.mkdtempSync(path.join(os.tmpdir(), 'nojdk-')))).toBeUndefined();
  });
});
