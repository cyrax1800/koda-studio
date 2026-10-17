import { describe, expect, it } from 'vitest';

import { daemonPids } from '../../src/sync/daemons.ts';

describe('daemonPids', () => {
  it('finds Gradle and Kotlin daemons, nothing else', () => {
    const ps = [
      '  5628 /jdk/bin/java -Xmx2g -cp /gradle/lib/gradle-daemon-main-9.1.0.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.1.0',
      '91118 /jdk/bin/java -cp gradle-launcher-8.14.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14',
      '91121 /jdk/bin/java -cp kotlin-daemon-embeddable-2.3.10.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon',
      '58105 /jdk/bin/java -Xmx3g -classpath app-2.0.jar org.kotlinlsp.MainKt',
      '  700 /usr/bin/grep GradleDaemon',
    ].join('\n');
    expect(daemonPids(ps)).toEqual([5628, 91118, 91121]);
  });
});
