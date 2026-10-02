// The JetBrains Kotlin LSP's workspace.json format, mirrored from
// github.com/Kotlin/kotlin-lsp workspace-import/src/com/jetbrains/ls/imports/json/model.kt at tag kotlin-lsp/v263.4702.0
// (the build behind jetbrains.kotlin-server 0.0.12). The server rejects the whole file on an unknown key, so only
// fields that exist at that tag may appear here. Field order follows the server's own export.

export const SUPPORTED_SERVER_BUILD = '263.4702.0';

export interface WorkspaceData {
  modules: ModuleData[];
  libraries: LibraryData[];
  sdks: SdkData[];
  kotlinSettings: KotlinSettingsData[];
  javaSettings: JavaSettingsData[];
  externalSystem?: string | null;
}

export interface ModuleData {
  name: string;
  type?: string | null;
  dependencies: DependencyData[];
  contentRoots: ContentRootData[];
  externalProjectPath?: string | null;
  externalProjectId?: string | null;
}

export type DependencyScope = 'compile' | 'test' | 'runtime' | 'provided';

export type DependencyData =
  | { type: 'module'; name: string; scope: DependencyScope; isExported?: boolean; isTestJar?: boolean }
  | { type: 'library'; name: string; scope: DependencyScope; isExported?: boolean }
  | { type: 'inheritedSdk' }
  | { type: 'moduleSource' }
  | { type: 'sdk'; name: string; kind: string };

export interface ContentRootData {
  path: string;
  excludedPatterns?: string[];
  excludedUrls?: string[];
  sourceRoots?: SourceRootData[];
}

/** At v263.4702.0 a source root is only `{path, type}`; `generated`/`packagePrefix` came later. */
export interface SourceRootData {
  path: string;
  type: SourceRootType;
}

export type SourceRootType = 'java-source' | 'java-test' | 'java-resource' | 'java-test-resource';

export interface LibraryData {
  name: string;
  /** Required by the server even though it is nullable. The server's own Gradle import writes COMPILE/PROVIDED. */
  type: string | null;
  roots: LibraryRootData[];
}

export interface LibraryRootData {
  path: string;
  type?: 'CLASSES' | 'SOURCES';
}

export interface SdkData {
  name: string;
  type: string;
  version: string | null;
  homePath: string | null;
  additionalData: string;
}

export interface KotlinSettingsData {
  name: string;
  sourceRoots: string[];
  configFileItems: { id: string; url: string }[];
  module: string;
  useProjectSettings: boolean;
  implementedModuleNames: string[];
  dependsOnModuleNames: string[];
  additionalVisibleModuleNames: string[];
  productionOutputPath: string | null;
  testOutputPath: string | null;
  sourceSetNames: string[];
  isTestModule: boolean;
  externalProjectId: string;
  isHmppEnabled: boolean;
  pureKotlinSourceFolders: string[];
  kind: 'default' | 'source_set_holder' | 'compilation_and_source_set_holder';
  /** `"J" + JSON.stringify({languageVersion, jvmTarget, pluginOptions, pluginClasspaths})`, as the server exports it. */
  compilerArguments: string | null;
  additionalArguments: string | null;
  scriptTemplates: string | null;
  scriptTemplatesClasspath: string | null;
  outputDirectoryForJsLibraryFiles: string | null;
  targetPlatform: string | null;
  externalSystemRunTasks: string[];
  version: number;
  flushNeeded: boolean;
}

export interface JavaSettingsData {
  module: string;
  inheritedCompilerOutput: boolean;
  excludeOutput: boolean;
  languageLevelId: string | null;
  manifestAttributes: Record<string, string>;
}
