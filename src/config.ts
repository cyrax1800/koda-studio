import * as vscode from 'vscode';

export type GenerateSources = 'none' | 'light' | 'full';

export interface BridgeConfig {
  javaHome: string | undefined;
  gradleArguments: string[];
  variant: string | undefined;
  generateSources: GenerateSources;
  onGradleFileChange: 'ask' | 'always' | 'never';
  librarySources: 'cached' | 'off';
  androidSdkSources: boolean;
  codeLens: {
    enabled: boolean;
    implementations: boolean;
    maxSymbolsPerFile: number;
  };
}

export function readConfig(scope?: vscode.Uri): BridgeConfig {
  const c = vscode.workspace.getConfiguration('androidBridge', scope);
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
    librarySources: c.get<'cached' | 'off'>('librarySources', 'cached'),
    androidSdkSources: c.get<boolean>('androidSdkSources', false),
    codeLens: {
      enabled: c.get<boolean>('codeLens.enabled', true),
      implementations: c.get<boolean>('codeLens.implementations', false),
      maxSymbolsPerFile: c.get<number>('codeLens.maxSymbolsPerFile', 200),
    },
  };
}
