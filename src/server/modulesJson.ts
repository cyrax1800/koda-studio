// Converts the sync model (model.json) into the module graph our Kotlin language server reads (modules.json).
//
// The format is the server's `SerializedModule` list (lsp/app/src/main/kotlin/org/kotlinlsp/analysis/modules/
// Serialization.kt): source modules with their source folders, library modules with their jars, and one JDK module.
// Per Gradle project there is one source module per Android component (`<holder>.<variant>`, `…UnitTest`,
// `…AndroidTest`) or JVM source set (`<holder>.main`, `<holder>.test`). Every module also gets:
//   * the R jars of itself and of every project it depends on (nonTransitiveRClass),
//   * AAR jars that contain the AAR's R classes (android-classes-jar),
//   * all generated source folders AGP reports (BuildConfig, binding, KSP, AIDL, proto, ...),
//   * real module dependencies instead of jars of sibling projects that were never built.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import type {
  AndroidComponent,
  AndroidComponentKind,
  ExternalLibrary,
  ExtrasComponent,
  GradleProjectModel,
  KotlinOptions,
  ProjectModel,
  SourceProviderModel,
} from '../gradle/modelTypes.ts';

/** One entry of modules.json; mirrors `SerializedModule` in the server. */
export interface SerializedModule {
  id: string;
  dependencies: string[];
  /** Source folders of a source module, jars of a library, or the JDK home. */
  contentRoots: string[];
  /** `-sources.jar` files of a library. */
  sourceRoots?: string[] | null;
  /** JVM target, e.g. "17" or "1.8". */
  javaVersion: string;
  isSource: boolean;
  /** Kotlin language version of a source module, e.g. "2.2". */
  kotlinVersion?: string;
  /** Kotlin compiler arguments of a source module other than the language version: API version, -X flags, opt-ins. */
  compilerArguments?: string[];
  /** Dependencies whose `internal` declarations this module may use, as the compiler's friend paths: test → main. */
  friends?: string[];
  isJdk?: boolean;
}

export interface ConvertOptions {
  /** `cached`: attach `-sources.jar` files already in the Gradle cache. */
  librarySources: 'cached' | 'off';
  /** Attach `<sdk>/sources/android-<api>`. */
  androidSdkSources?: boolean;
  gradleUserHome?: string;
  /** File-system hooks, replaced in tests. */
  fs?: {
    exists(p: string): boolean;
    readdir(p: string): string[];
    readFile(p: string): string | undefined;
  };
}

export interface ConvertResult {
  modules: SerializedModule[];
  warnings: string[];
}

export const JDK_MODULE = 'jdk';

const COMPONENT_SUFFIX: Record<AndroidComponentKind, string> = {
  main: '',
  unitTest: 'UnitTest',
  androidTest: 'AndroidTest',
};

const DEFAULT_JVM_TARGET = '17';

const realFs = {
  exists: (p: string) => fs.existsSync(p),
  readdir: (p: string) => {
    try {
      return fs.readdirSync(p);
    } catch {
      return [];
    }
  },
  readFile: (p: string) => {
    try {
      return fs.readFileSync(p, 'utf8');
    } catch {
      return undefined;
    }
  },
};

export function toModulesJson(model: ProjectModel, options: ConvertOptions): ConvertResult {
  return new Converter(model, options).convert();
}

class Converter {
  private readonly fs: NonNullable<ConvertOptions['fs']>;
  private readonly gradleUserHome: string;
  private readonly projects: Map<string, GradleProjectModel>;
  private readonly sources: SerializedModule[] = [];
  private readonly libraries = new Map<string, SerializedModule>();
  private readonly warnings: string[] = [];
  private readonly jdk: SerializedModule;
  private readonly sourcesJarCache = new Map<string, string | undefined>();
  /** The android.jar library, which AAR libraries need to resolve their Android supertypes. */
  private androidSdk: string | undefined;

  private readonly model: ProjectModel;
  private readonly options: ConvertOptions;

  constructor(model: ProjectModel, options: ConvertOptions) {
    this.model = model;
    this.options = options;
    this.fs = options.fs ?? realFs;
    this.gradleUserHome = options.gradleUserHome ?? process.env.GRADLE_USER_HOME ?? path.join(os.homedir(), '.gradle');
    this.projects = new Map(model.projects.map((p) => [p.path, p]));
    this.jdk = this.jdkModule(model.javaHome);
  }

  convert(): ConvertResult {
    for (const project of this.model.projects) {
      const holder = this.holderName(project.path);
      if (project.android) this.addAndroid(project, holder);
      else if (project.extras?.kind === 'jvm') this.addJvm(project, holder);
    }
    const libraryDependencies = [JDK_MODULE, ...(this.androidSdk ? [this.androidSdk] : [])];
    const libraries = [...this.libraries.values()].map((library) =>
      library.id === this.androidSdk ? library : { ...library, dependencies: libraryDependencies },
    );
    return { modules: [this.jdk, ...libraries, ...this.sources], warnings: this.warnings };
  }

  // ---- Android ----

  private addAndroid(project: GradleProjectModel, holder: string): void {
    const android = project.android!;
    const variant = android.selectedVariant;
    const sdkLibrary = this.androidSdkLibrary(android.bootClasspath);
    const mainModule = this.androidModuleName(holder, variant, 'main');
    for (const kind of ['main', 'unitTest', 'androidTest'] as const) {
      const component = android.components[kind];
      if (!component) continue;
      const extras = project.extras?.components?.[kind];
      if (!extras) this.warnings.push(`${project.path}: no classpath for ${variant}${COMPONENT_SUFFIX[kind]}`);
      const id = this.androidModuleName(holder, variant, kind);

      const dependencies: string[] = [];
      if (sdkLibrary) dependencies.push(sdkLibrary);
      for (const jar of this.classJars(component)) {
        dependencies.push(this.fileLibrary(this.generatedJarName(project.path, id, jar), [jar]));
      }
      if (kind !== 'main') dependencies.push(mainModule);
      this.addProjectDependencies(dependencies, extras);
      this.addExternalLibraries(dependencies, extras?.libraries ?? []);
      dependencies.push(JDK_MODULE);

      this.sources.push(
        this.sourceModule(
          id,
          [...this.providerSourceDirs(component.sourceProviders), ...this.existing(component.generatedSourceFolders)],
          dependencies,
          extras?.kotlin,
          project.extras?.kotlinPluginVersion,
          kind === 'main' ? [] : [mainModule],
        ),
      );
    }
  }

  private androidModuleName(holder: string, variant: string, kind: AndroidComponentKind): string {
    return `${holder}.${variant}${COMPONENT_SUFFIX[kind]}`;
  }

  /** Java and Kotlin folders of every source provider (main, flavors, build type, variant). */
  private providerSourceDirs(providers: SourceProviderModel[]): string[] {
    return this.existing(providers.flatMap((p) => [...p.java, ...p.kotlin]));
  }

  /**
   * Jars AGP compiles the component against that come from the module itself: the R class jar and the BuildConfig
   * jar. classesFolders also lists the module's own javac/kotlinc output, which must stay out: those are stale
   * binary copies of the sources being edited.
   */
  private classJars(component: AndroidComponent): string[] {
    const jars = [...component.classesFolders, ...Object.values(component.generatedClassPaths)];
    return unique(jars.filter((f) => f.endsWith('.jar') && this.fs.exists(f)));
  }

  private generatedJarName(projectPath: string, moduleId: string, jar: string): string {
    const kind = path.basename(jar) === 'R.jar' ? 'r' : path.basename(jar, '.jar');
    return `Gradle: android:${kind}:${projectPath}:${moduleId.slice(moduleId.lastIndexOf('.') + 1)}`;
  }

  private androidSdkLibrary(bootClasspath: string[]): string | undefined {
    const jars = bootClasspath.filter((f) => this.fs.exists(f));
    const androidJar = jars.find((f) => path.basename(f) === 'android.jar');
    if (!androidJar) {
      this.warnings.push('android.jar not found on the boot classpath');
      return undefined;
    }
    const platform = path.basename(path.dirname(androidJar));
    let sources: string[] | null = null;
    if (this.options.androidSdkSources) {
      const dir = path.join(path.dirname(path.dirname(path.dirname(androidJar))), 'sources', platform);
      if (this.fs.exists(dir)) sources = [dir];
    }
    const id = this.putLibrary(`Gradle: android:android-sdk:${platform}`, jars, sources);
    this.androidSdk ??= id;
    return id;
  }

  // ---- JVM ----

  private addJvm(project: GradleProjectModel, holder: string): void {
    const components = project.extras?.components ?? {};
    const mainModule = `${holder}.main`;
    for (const kind of ['main', 'test'] as const) {
      const component = components[kind];
      if (!component) continue;
      const dependencies: string[] = [];
      if (kind === 'test') dependencies.push(mainModule);
      this.addProjectDependencies(dependencies, component);
      this.addExternalLibraries(dependencies, component.libraries ?? []);
      dependencies.push(JDK_MODULE);
      const sourceDirs = this.existing([...(component.javaDirs ?? []), ...(component.kotlinDirs ?? []), ...(component.generatedDirs ?? [])]);
      this.sources.push(
        this.sourceModule(`${holder}.${kind}`, sourceDirs, dependencies, component.kotlin, project.extras?.kotlinPluginVersion, kind === 'test' ? [mainModule] : []),
      );
    }
  }

  // ---- dependencies ----

  /** Module dependencies on sibling projects, plus their R jars so `other.module.R` resolves (nonTransitiveRClass). */
  private addProjectDependencies(dependencies: string[], component: ExtrasComponent | undefined): void {
    for (const dependency of component?.projectDependencies ?? []) {
      if (dependency.build !== ':') continue;
      const target = this.projects.get(dependency.path);
      if (!target) {
        this.warnings.push(`project dependency ${dependency.path} is not part of the model`);
        continue;
      }
      const targetHolder = this.holderName(target.path);
      if (target.android) {
        const moduleId = this.androidModuleName(targetHolder, target.android.selectedVariant, 'main');
        const main = target.android.components.main;
        dependencies.push(moduleId);
        if (main) {
          for (const jar of this.classJars(main)) dependencies.push(this.fileLibrary(this.generatedJarName(target.path, moduleId, jar), [jar]));
        }
      } else if (target.extras?.kind === 'jvm' && target.extras.components?.main) {
        dependencies.push(`${targetHolder}.main`);
      }
    }
  }

  private addExternalLibraries(dependencies: string[], libraries: ExternalLibrary[]): void {
    for (const library of libraries) {
      const files = library.files.filter((f) => this.fs.exists(f));
      if (files.length === 0) continue;
      const sources = this.sourcesJar(library);
      dependencies.push(this.putLibrary(`Gradle: ${library.id}`, files, sources ? [sources] : null));
    }
  }

  private fileLibrary(id: string, files: string[]): string {
    return this.putLibrary(id, files, null);
  }

  /** Registers a library and returns its id; a different library under a taken id gets a distinguishing suffix. */
  private putLibrary(id: string, jars: string[], sources: string[] | null): string {
    const library: SerializedModule = { id, dependencies: [], contentRoots: jars, sourceRoots: sources, javaVersion: DEFAULT_JVM_TARGET, isSource: false, isJdk: false };
    const sameJars = (other: SerializedModule) => JSON.stringify(other.contentRoots) === JSON.stringify(jars);
    for (let i = 1; ; i++) {
      const candidate = i === 1 ? id : `${id} (${i})`;
      const existing = this.libraries.get(candidate);
      if (!existing) {
        this.libraries.set(candidate, { ...library, id: candidate });
        return candidate;
      }
      if (sameJars(existing)) return candidate;
    }
  }

  private sourcesJar(library: ExternalLibrary): string | undefined {
    if (this.options.librarySources !== 'cached' || !library.group || !library.module || !library.version) return undefined;
    const key = library.id;
    if (this.sourcesJarCache.has(key)) return this.sourcesJarCache.get(key);
    const dir = path.join(this.gradleUserHome, 'caches', 'modules-2', 'files-2.1', library.group, library.module, library.version);
    const fileName = `${library.module}-${library.version}-sources.jar`;
    let found: string | undefined;
    for (const hash of this.fs.readdir(dir)) {
      const candidate = path.join(dir, hash, fileName);
      if (this.fs.exists(candidate)) {
        found = candidate;
        break;
      }
    }
    this.sourcesJarCache.set(key, found);
    return found;
  }

  // ---- modules ----

  private sourceModule(
    id: string,
    sourceDirs: string[],
    dependencies: string[],
    kotlin: KotlinOptions | null | undefined,
    kotlinPluginVersion: string | null | undefined,
    friends: string[],
  ): SerializedModule {
    return {
      id,
      dependencies: unique(dependencies),
      contentRoots: unique(sourceDirs),
      javaVersion: kotlin?.jvmTarget ?? DEFAULT_JVM_TARGET,
      isSource: true,
      kotlinVersion: kotlin?.languageVersion ?? majorMinor(kotlinPluginVersion) ?? '2.2',
      compilerArguments: [
        ...(kotlin?.apiVersion ? ['-api-version', kotlin.apiVersion] : []),
        ...(kotlin?.freeCompilerArgs ?? []),
        ...(kotlin?.optIn ?? []).map((o) => `-opt-in=${o}`),
        // Compiler plugins as Gradle passes them; the server swaps the jars for its own builds of the same plugins.
        ...(kotlin?.pluginClasspath ?? []).map((jar) => `-Xplugin=${jar}`),
        ...(kotlin?.pluginOptions ?? []).flatMap((option) => ['-P', option]),
      ],
      ...(friends.length > 0 ? { friends } : {}),
    };
  }

  private jdkModule(javaHome: string | undefined): SerializedModule {
    const release = javaHome ? this.fs.readFile(path.join(javaHome, 'release')) : undefined;
    const version = /JAVA_VERSION="(\d+)(?:\.(\d+))?/.exec(release ?? '');
    const major = version ? (version[1] === '1' ? `1.${version[2]}` : version[1]) : undefined;
    if (!javaHome) this.warnings.push('no JDK in the model; the JDK the server runs on is used');
    return { id: JDK_MODULE, dependencies: [], contentRoots: javaHome ? [javaHome] : [], sourceRoots: null, javaVersion: major ?? DEFAULT_JVM_TARGET, isSource: false, isJdk: true };
  }

  private existing(dirs: string[]): string[] {
    return unique(dirs).filter((d) => this.fs.exists(d));
  }

  private holderName(projectPath: string): string {
    return projectPath === ':' ? this.model.rootProjectName : `${this.model.rootProjectName}${projectPath.replace(/:/g, '.')}`;
  }
}

function unique<T>(values: T[]): T[] {
  return [...new Set(values)];
}

function majorMinor(version: string | null | undefined): string | undefined {
  const match = /^(\d+)\.(\d+)/.exec(version ?? '');
  return match ? `${match[1]}.${match[2]}` : undefined;
}
