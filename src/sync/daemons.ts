// JVM daemons that Koda Studio's syncs start (Gradle, and the Kotlin compile daemon when sources are generated), so
// they can be stopped when the window closes instead of holding gigabytes until they time out after hours. Only
// daemons that appeared during one of our syncs are stopped; one the user's own builds started stays.
import { execFileSync } from 'node:child_process';

const DAEMON = /GradleDaemon|gradle-daemon-main|KotlinCompileDaemon|kotlin-daemon/;

/** Process ids of the Gradle and Kotlin daemons running now. Empty on Windows (not supported there yet). */
export function runningDaemons(): Set<number> {
  if (process.platform === 'win32') return new Set();
  try {
    const out = execFileSync('ps', ['-axo', 'pid=,command='], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
    return new Set(daemonPids(out));
  } catch {
    return new Set();
  }
}

/** Parses `ps -axo pid=,command=` output. */
export function daemonPids(psOutput: string): number[] {
  const pids: number[] = [];
  for (const line of psOutput.split('\n')) {
    const match = /^\s*(\d+)\s+(.*)$/.exec(line);
    if (match && DAEMON.test(match[2]!) && /\bjava\b/.test(match[2]!)) pids.push(Number(match[1]));
  }
  return pids;
}

/** Stops the given daemons that are still running (a pid reused by another process is left alone). Returns those stopped. */
export function stopDaemons(pids: Iterable<number>): number[] {
  const running = runningDaemons();
  const stopped: number[] = [];
  for (const pid of pids) {
    if (!running.has(pid)) continue;
    try {
      process.kill(pid, 'SIGTERM');
      stopped.push(pid);
    } catch {
      // already gone
    }
  }
  return stopped;
}
