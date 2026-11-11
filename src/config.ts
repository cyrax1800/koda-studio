import * as vscode from 'vscode';

import { DEFAULT_JVM_ARGS } from './server/jvmArgs.ts';

export type GenerateSources = 'none' | 'light' | 'full';

export interface KodaConfig {
  javaHome: string | undefined;
  gradleArguments: string[];
  variant: string | undefined;
  generateSources: GenerateSources;
  onGradleFileChange: 'ask' | 'always' | 'never';
  /** When to stop the Gradle daemon after a sync. */
  stopGradleDaemon: 'afterFirstSync' | 'always' | 'never';
  /** Stop the Gradle and Kotlin daemons our syncs started when the window closes. */
  stopDaemonsOnClose: boolean;
  librarySources: 'download' | 'cached' | 'off';
  androidSdkSources: boolean;
  server: {
    javaHome: string | undefined;
    jvmArgs: string[];
  };
  /** Compose stability hints, warnings and hover, as skydoves' Compose Stability Analyzer reports it. */
  composeStability: boolean;
  /** Completed Kotlin calls pass their required arguments by name: `Text(text = …)`. */
  namedArguments: boolean;
  /** Parameter names before call arguments: `literals`, `all` or `none`. */
  parameterNameHints: 'literals' | 'all' | 'none';
  codeLens: {
    enabled: boolean;
    implementations: boolean;
    maxSymbolsPerFile: number;
  };
}

/** Settings were called `androidBridge.*` before the rename; values set there are still read for now. */
const LEGACY_SECTION = 'androidBridge';

function isSet(config: vscode.WorkspaceConfiguration, key: string): boolean {
  const inspected = config.inspect(key);
  return [inspected?.globalValue, inspected?.workspaceValue, inspected?.workspaceFolderValue].some((v) => v !== undefined);
}

export function readConfig(scope?: vscode.Uri): KodaConfig {
  const current = vscode.workspace.getConfiguration('kodaStudio', scope);
  const legacy = vscode.workspace.getConfiguration(LEGACY_SECTION, scope);
  const c = {
    get<T>(key: string, fallback?: T): T {
      if (!isSet(current, key) && isSet(legacy, key)) return legacy.get<T>(key) as T;
      return (fallback === undefined ? current.get<T>(key) : current.get<T>(key, fallback)) as T;
    },
  };
  const text = (key: string) => {
    const value = c.get<string | null>(key);
    return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
  };
  return {
    javaHome: text('gradle.javaHome'),
    gradleArguments: c.get<string[]>('gradle.arguments', []).filter((a) => typeof a === 'string' && a !== ''),
    variant: text('variant'),
    generateSources: c.get<GenerateSources>('sync.generateSources', 'light'),
    onGradleFileChange: c.get<'ask' | 'always' | 'never'>('sync.onGradleFileChange', 'ask'),
    stopGradleDaemon: c.get<'afterFirstSync' | 'always' | 'never'>('sync.stopGradleDaemon', 'afterFirstSync'),
    stopDaemonsOnClose: c.get<boolean>('sync.stopDaemonsOnClose', true),
    librarySources: c.get<'download' | 'cached' | 'off'>('librarySources', 'download'),
    androidSdkSources: c.get<boolean>('androidSdkSources', false),
    server: {
      javaHome: text('server.javaHome'),
      jvmArgs: c.get<string[]>('server.jvmArgs', DEFAULT_JVM_ARGS).filter((a) => typeof a === 'string' && a !== ''),
    },
    composeStability: c.get<boolean>('composeStability.enabled', true),
    namedArguments: c.get<boolean>('completion.namedArguments', true),
    parameterNameHints: c.get<'literals' | 'all' | 'none'>('inlayHints.parameterNames', 'literals'),
    codeLens: {
      enabled: c.get<boolean>('codeLens.enabled', true),
      implementations: c.get<boolean>('codeLens.implementations', false),
      maxSymbolsPerFile: c.get<number>('codeLens.maxSymbolsPerFile', 0),
    },
  };
}
