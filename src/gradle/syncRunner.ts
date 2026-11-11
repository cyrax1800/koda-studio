// Runs koda-sync (sidecar/) and turns its JSON event lines into callbacks.
import { spawn } from 'node:child_process';
import path from 'node:path';
import readline from 'node:readline';

import type { GenerateSources } from '../config.ts';
import type { JavaRuntime } from './javaHome.ts';

export interface SyncRequest {
  extensionPath: string;
  projectDir: string;
  outFile: string;
  variant: string | undefined;
  generate: GenerateSources;
  gradleArguments: string[];
  java: JavaRuntime;
  /** Stop the Gradle daemon the sync started when it's done, freeing its memory for indexing. */
  stopDaemon?: boolean;
  /** Download the `-sources.jar` of every library into the Gradle cache, for go to definition. */
  downloadSources?: boolean;
}

export interface SyncListener {
  progress(message: string): void;
  phase(name: string, tasks: string[]): void;
  warning(message: string): void;
  output(line: string): void;
}

export interface SyncEvent {
  event: 'phase' | 'progress' | 'model' | 'warning' | 'error' | 'done';
  name?: string;
  message?: string;
  stage?: string;
  path?: string;
  tasks?: string[];
}

export class SyncCancelled extends Error {
  constructor() {
    super('Sync cancelled');
  }
}

export function syncArguments(request: SyncRequest): string[] {
  return [
    '-jar',
    path.join(request.extensionPath, 'server', 'koda-sync.jar'),
    '--project-dir',
    request.projectDir,
    '--init-script',
    path.join(request.extensionPath, 'gradle', 'koda.init.gradle'),
    '--out',
    request.outFile,
    '--generate',
    request.generate,
    ...(request.variant ? ['--variant', request.variant] : []),
    ...(request.java.explicit ? ['--java-home', request.java.home] : []),
    ...(request.stopDaemon ? ['--stop-daemon'] : []),
    ...(request.downloadSources ? ['--download-sources'] : []),
    ...request.gradleArguments.flatMap((a) => ['--gradle-arg', a]),
  ];
}

/** Parses one stdout line. Anything that isn't an event (shouldn't happen) is reported as output. */
export function parseEvent(line: string): SyncEvent | undefined {
  if (!line.startsWith('{')) return undefined;
  try {
    const value = JSON.parse(line) as SyncEvent;
    return typeof value.event === 'string' ? value : undefined;
  } catch {
    return undefined;
  }
}

export function runSync(request: SyncRequest, listener: SyncListener, signal: AbortSignal): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(request.java.java, syncArguments(request), {
      cwd: request.projectDir,
      env: { ...process.env, JAVA_HOME: request.java.home },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let modelPath: string | undefined;
    let error: string | undefined;
    let cancelled = false;
    const onAbort = () => {
      cancelled = true;
      // SIGTERM runs the sidecar's shutdown hook, which cancels the Gradle build.
      child.kill('SIGTERM');
      setTimeout(() => child.kill('SIGKILL'), 15_000).unref();
    };
    signal.addEventListener('abort', onAbort, { once: true });

    readline.createInterface({ input: child.stdout }).on('line', (line) => {
      const event = parseEvent(line);
      if (!event) {
        listener.output(line);
        return;
      }
      switch (event.event) {
        case 'progress':
          if (event.message) listener.progress(event.message);
          break;
        case 'phase':
          listener.phase(event.name ?? '', event.tasks ?? []);
          break;
        case 'model':
        case 'done':
          modelPath = event.path ?? modelPath;
          break;
        case 'warning':
          listener.warning(event.message ?? '');
          break;
        case 'error':
          error = `${event.stage ? `${event.stage}: ` : ''}${event.message ?? 'unknown error'}`;
          break;
      }
    });
    readline.createInterface({ input: child.stderr }).on('line', (line) => listener.output(line));

    child.on('error', (e) => reject(new Error(`Could not start ${request.java.java}: ${e.message}`)));
    child.on('close', (code) => {
      signal.removeEventListener('abort', onAbort);
      if (cancelled) reject(new SyncCancelled());
      else if (code === 0 && modelPath) resolve(modelPath);
      else reject(new Error(error ?? `Sync exited with code ${code}`));
    });
  });
}
