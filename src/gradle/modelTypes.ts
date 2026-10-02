// Shape of model.json written by android-bridge-sync (sidecar/src/main/kotlin/androidbridge/sync/SyncAction.kt)
// and the init script (gradle/android-bridge.init.gradle).

export interface ProjectModel {
  schemaVersion: 1;
  rootDir: string;
  rootProjectName: string;
  gradleVersion?: string;
  /** JDK the Gradle daemon ran on. */
  javaHome?: string;
  requestedVariant?: string;
  projects: GradleProjectModel[];
  problems: SyncProblem[];
  selectedVariants?: Record<string, string>;
}

export interface SyncProblem {
  project?: string;
  stage: string;
  message: string;
}

export interface GradleProjectModel {
  path: string;
  name: string;
  dir: string;
  android?: AndroidModel;
  extras?: ProjectExtras;
}

export interface AndroidModel {
  agpVersion: string;
  projectType: 'APPLICATION' | 'LIBRARY' | 'DYNAMIC_FEATURE' | 'TEST' | 'FUSED_LIBRARY' | string;
  namespace?: string;
  buildFolder?: string;
  selectedVariant: string;
  bootClasspath: string[];
  javaCompileOptions?: { sourceCompatibility?: string; targetCompatibility?: string };
  variants: { name: string; buildType?: string; productFlavors: string[] }[];
  components: Partial<Record<AndroidComponentKind, AndroidComponent>>;
}

export type AndroidComponentKind = 'main' | 'unitTest' | 'androidTest';

export interface AndroidComponent {
  sourceProviders: SourceProviderModel[];
  generatedSourceFolders: string[];
  classesFolders: string[];
  generatedClassPaths: Record<string, string>;
  compileTaskName?: string;
  ideSetupTaskNames: string[];
  sourceGenTaskName?: string;
}

export interface SourceProviderModel {
  name: string;
  java: string[];
  kotlin: string[];
  resources: string[];
  aidl: string[];
}

export interface ProjectExtras {
  projectPath: string;
  kind: 'android' | 'jvm' | 'other';
  variant?: string;
  /** Version of the Kotlin Gradle plugin (`kotlin.coreLibrariesVersion`), if Kotlin is applied. */
  kotlinPluginVersion?: string | null;
  problems: SyncProblem[];
  /** Android: keys of AndroidComponentKind. JVM: "main" | "test". */
  components?: Record<string, ExtrasComponent>;
  tasks?: string[];
}

export interface ExtrasComponent {
  configuration?: string;
  libraries?: ExternalLibrary[];
  projectDependencies?: { path: string; build: string }[];
  kotlin?: KotlinOptions | null;
  // JVM projects only
  javaDirs?: string[];
  kotlinDirs?: string[];
  resourceDirs?: string[];
  generatedDirs?: string[];
  outputDirs?: string[];
  javaRelease?: string | null;
}

export interface ExternalLibrary {
  /** `group:module:version` for module components, otherwise the component's display name. */
  id: string;
  group?: string;
  module?: string;
  version?: string;
  files: string[];
  artifactType: string;
}

export interface KotlinOptions {
  task: string;
  jvmTarget?: string | null;
  languageVersion?: string | null;
  apiVersion?: string | null;
  freeCompilerArgs: string[];
  optIn: string[];
  pluginClasspath: string[];
  pluginOptions: string[];
}
