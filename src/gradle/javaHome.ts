// Picks the JDK that runs the sync. By default it is the JDK `./gradlew` would use in a terminal, so the sync
// reuses the user's Gradle daemon instead of starting another one (a daemon is per Java home).
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface JavaRuntime {
  home: string;
  java: string;
  version: number;
  /** Set by the user. Only then is it forced onto the Gradle daemon as well. */
  explicit: boolean;
  source: string;
}

const MIN_VERSION = 17;

export async function findJavaRuntime(
  configured: string | undefined,
  gradleRoot: string,
  setting = 'kodaStudio.gradle.javaHome',
): Promise<JavaRuntime> {
  const tried: string[] = [];
  const accept = (home: string | undefined, source: string, explicit = false): JavaRuntime | undefined => {
    if (!home) return undefined;
    const runtime = inspect(home, source, explicit);
    if (runtime && runtime.version >= MIN_VERSION) return runtime;
    tried.push(`${source}: ${home}${runtime ? ` (Java ${runtime.version})` : ' (not a JDK)'}`);
    return undefined;
  };

  if (configured) {
    const runtime = accept(expandHome(configured), setting, true);
    if (runtime) return runtime;
    throw new Error(`${setting} must point to a JDK ${MIN_VERSION}+ (tried ${tried.join('; ')})`);
  }
  const candidates: [string | undefined, string][] = [
    [gradleProperty(path.join(gradleRoot, 'gradle.properties'), 'org.gradle.java.home'), 'org.gradle.java.home'],
    [gradleProperty(path.join(gradleUserHome(), 'gradle.properties'), 'org.gradle.java.home'), '~/.gradle/gradle.properties'],
    [process.env.JAVA_HOME, 'JAVA_HOME'],
    [await macJavaHome(), '/usr/libexec/java_home'],
  ];
  for (const [home, source] of candidates) {
    const runtime = accept(home, source);
    if (runtime) return runtime;
  }
  const discovered = discoverInstalledJdks()
    .map((home) => inspect(home, 'installed JDK', false))
    .filter((r): r is JavaRuntime => r !== undefined && r.version >= MIN_VERSION)
    .sort((a, b) => b.version - a.version);
  if (discovered[0]) return discovered[0];
  throw new Error(
    `No JDK ${MIN_VERSION}+ found. Set ${setting} or JAVA_HOME.` + (tried.length ? ` Tried: ${tried.join('; ')}` : ''),
  );
}

function inspect(home: string, source: string, explicit: boolean): JavaRuntime | undefined {
  const java = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  if (!fs.existsSync(java)) return undefined;
  const version = javaVersion(home);
  if (version === undefined) return undefined;
  return { home, java, version, explicit, source };
}

/** Major version from `<home>/release` (`JAVA_VERSION="21.0.2"`, or `"1.8.0_392"` for Java 8). */
export function javaVersion(home: string): number | undefined {
  let release: string;
  try {
    release = fs.readFileSync(path.join(home, 'release'), 'utf8');
  } catch {
    return undefined;
  }
  const match = /^JAVA_VERSION="(\d+)(?:\.(\d+))?/m.exec(release);
  if (!match) return undefined;
  return match[1] === '1' ? Number(match[2]) : Number(match[1]);
}

function gradleProperty(file: string, key: string): string | undefined {
  let text: string;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return undefined;
  }
  for (const line of text.split(/\r?\n/)) {
    const match = /^\s*([^#!=:\s]+)\s*[=:]\s*(.*?)\s*$/.exec(line);
    if (match && match[1] === key) return match[2]!.replace(/\\(.)/g, '$1');
  }
  return undefined;
}

function macJavaHome(): Promise<string | undefined> {
  if (process.platform !== 'darwin') return Promise.resolve(undefined);
  return new Promise((resolve) => {
    execFile('/usr/libexec/java_home', ['-v', `${MIN_VERSION}+`], { timeout: 5000 }, (error, stdout) => {
      resolve(error ? undefined : stdout.trim() || undefined);
    });
  });
}

function discoverInstalledJdks(): string[] {
  const roots: string[] = [];
  const home = os.homedir();
  if (process.platform === 'darwin') {
    roots.push('/Library/Java/JavaVirtualMachines', path.join(home, 'Library/Java/JavaVirtualMachines'));
  } else if (process.platform === 'win32') {
    const programFiles = process.env.ProgramFiles ?? 'C:\\Program Files';
    roots.push(...['Java', 'Eclipse Adoptium', 'Microsoft', 'Zulu', 'Amazon Corretto'].map((d) => path.join(programFiles, d)));
  } else {
    roots.push('/usr/lib/jvm', path.join(home, '.sdkman/candidates/java'));
  }
  roots.push(path.join(gradleUserHome(), 'jdks'));
  const homes: string[] = [];
  for (const root of roots) {
    let entries: string[];
    try {
      entries = fs.readdirSync(root);
    } catch {
      continue;
    }
    for (const entry of entries) {
      const base = path.join(root, entry);
      homes.push(fs.existsSync(path.join(base, 'Contents', 'Home')) ? path.join(base, 'Contents', 'Home') : base);
    }
  }
  // Android Studio ships a full JDK (with jlink, unlike the Kotlin LSP's runtime).
  if (process.platform === 'darwin') homes.push('/Applications/Android Studio.app/Contents/jbr/Contents/Home');
  return homes;
}

function gradleUserHome(): string {
  return process.env.GRADLE_USER_HOME ?? path.join(os.homedir(), '.gradle');
}

function expandHome(p: string): string {
  return p.startsWith('~') ? path.join(os.homedir(), p.slice(1)) : p;
}
