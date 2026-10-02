// Converts the sync model (model.json) into the JetBrains Kotlin LSP's workspace.json.
//
// The layout mirrors what the server's own Gradle import produces: one holder module per Gradle project, plus one
// module per Android component (`<holder>.<variant>`, `…UnitTest`, `…AndroidTest`) or JVM source set
// (`<holder>.main`, `<holder>.test`). Unlike that import, every module also gets:
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
import type {
  ContentRootData,
  DependencyData,
  DependencyScope,
  JavaSettingsData,
  KotlinSettingsData,
  LibraryData,
  LibraryRootData,
  ModuleData,
  SdkData,
  SourceRootData,
  WorkspaceData,
} from './workspaceJsonTypes.ts';

export interface ConvertOptions {
  /** `cached`: attach `-sources.jar` files already in the Gradle cache. */
  librarySources: 'cached' | 'off';
  /** Attach `<sdk>/sources/android-<api>`. Makes the server's index noticeably larger. */
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
  workspace: WorkspaceData;
  warnings: string[];
}

const COMPONENT_SUFFIX: Record<AndroidComponentKind, string> = {
  main: '',
  unitTest: 'UnitTest',
  androidTest: 'AndroidTest',
};

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

export function toWorkspaceJson(model: ProjectModel, options: ConvertOptions): ConvertResult {
  return new Converter(model, options).convert();
}

class Converter {
  private readonly fs: NonNullable<ConvertOptions['fs']>;
  private readonly gradleUserHome: string;
  private readonly projects: Map<string, GradleProjectModel>;
  private readonly modules: ModuleData[] = [];
  private readonly libraries = new Map<string, LibraryData>();
  private readonly kotlinSettings: KotlinSettingsData[] = [];
  private readonly javaSettings: JavaSettingsData[] = [];
  private readonly warnings: string[] = [];
  private readonly sdk: SdkData;
  private readonly sourcesJarCache = new Map<string, string | undefined>();

  private readonly model: ProjectModel;
  private readonly options: ConvertOptions;

  constructor(model: ProjectModel, options: ConvertOptions) {
    this.model = model;
    this.options = options;
    this.fs = options.fs ?? realFs;
    this.gradleUserHome = options.gradleUserHome ?? process.env.GRADLE_USER_HOME ?? path.join(os.homedir(), '.gradle');
    this.projects = new Map(model.projects.map((p) => [p.path, p]));
    this.sdk = this.jdk(model.javaHome);
  }

  convert(): ConvertResult {
    for (const project of this.model.projects) {
      const holder = this.holderName(project.path);
      this.modules.push({
        name: holder,
        dependencies: [{ type: 'moduleSource' }, this.sdkDependency()],
        contentRoots: [{ path: project.dir }],
        externalProjectPath: project.dir,
        externalProjectId: project.path,
      });
      this.javaSettings.push(this.javaSettingsFor(holder, this.languageLevel(project)));
      if (project.android) this.addAndroid(project, holder);
      else if (project.extras?.kind === 'jvm') this.addJvm(project, holder);
    }
    return {
      workspace: {
        modules: this.modules,
        libraries: [...this.libraries.values()],
        sdks: [this.sdk],
        kotlinSettings: this.kotlinSettings,
        javaSettings: this.javaSettings,
        externalSystem: 'GRADLE',
      },
      warnings: this.warnings,
    };
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
      const name = this.androidModuleName(holder, variant, kind);
      const test = kind !== 'main';
      const contentRoots = [
        ...this.providerContentRoots(component.sourceProviders, test),
        ...this.generatedContentRoots(component.generatedSourceFolders, test),
      ];

      const dependencies: DependencyData[] = [];
      if (sdkLibrary) dependencies.push(this.libraryDependency(sdkLibrary, 'provided'));
      dependencies.push({ type: 'moduleSource' });
      for (const jar of this.classJars(component)) {
        dependencies.push(this.libraryDependency(this.fileLibrary(this.generatedJarName(project.path, name, jar), [jar]), 'provided'));
      }
      if (test) dependencies.push({ type: 'module', name: mainModule, scope: 'provided' });
      this.addProjectDependencies(dependencies, extras, 'provided');
      this.addExternalLibraries(dependencies, extras?.libraries ?? [], 'provided');
      dependencies.push(this.sdkDependency());

      this.modules.push({
        name,
        dependencies: dedupeDependencies(dependencies),
        contentRoots,
        externalProjectPath: project.dir,
        externalProjectId: project.path,
      });
      this.kotlinSettings.push(
        this.kotlinSettingsFor(name, contentRoots, extras?.kotlin, project.extras?.kotlinPluginVersion, test, test ? [mainModule] : []),
      );
      this.javaSettings.push(this.javaSettingsFor(name, this.languageLevel(project)));
    }
  }

  private androidModuleName(holder: string, variant: string, kind: AndroidComponentKind): string {
    return `${holder}.${variant}${COMPONENT_SUFFIX[kind]}`;
  }

  /** One content root per source provider (e.g. `src/main`), holding its Java/Kotlin and resource folders. */
  private providerContentRoots(providers: SourceProviderModel[], test: boolean): ContentRootData[] {
    const roots: ContentRootData[] = [];
    for (const provider of providers) {
      const sourceDirs = unique([...provider.java, ...provider.kotlin]);
      const base = commonParent([...sourceDirs, ...provider.resources, ...provider.aidl]);
      if (!base) continue;
      const sourceRoots: SourceRootData[] = [
        ...sourceDirs.filter((d) => this.fs.exists(d)).map((d) => ({ path: d, type: test ? 'java-test' : 'java-source' }) as const),
        ...provider.resources
          .filter((d) => this.fs.exists(d))
          .map((d) => ({ path: d, type: test ? 'java-test-resource' : 'java-resource' }) as const),
      ];
      roots.push(sourceRoots.length > 0 ? { path: base, sourceRoots } : { path: base });
    }
    return roots;
  }

  /** Generated folders live under build/, so each becomes its own content root. Missing ones are skipped. */
  private generatedContentRoots(folders: string[], test: boolean): ContentRootData[] {
    return unique(folders)
      .filter((f) => this.fs.exists(f))
      .map((f) => ({ path: f, sourceRoots: [{ path: f, type: test ? 'java-test' : 'java-source' }] }));
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

  private generatedJarName(projectPath: string, moduleName: string, jar: string): string {
    const kind = path.basename(jar) === 'R.jar' ? 'r' : path.basename(jar, '.jar');
    return `Gradle: android:${kind}:${projectPath}:${moduleName.slice(moduleName.lastIndexOf('.') + 1)}`;
  }

  private androidSdkLibrary(bootClasspath: string[]): LibraryData | undefined {
    const jars = bootClasspath.filter((f) => this.fs.exists(f));
    const androidJar = jars.find((f) => path.basename(f) === 'android.jar');
    if (!androidJar) {
      this.warnings.push('android.jar not found on the boot classpath');
      return undefined;
    }
    const platform = path.basename(path.dirname(androidJar));
    const roots: LibraryRootData[] = jars.map((f) => ({ path: f }));
    if (this.options.androidSdkSources) {
      const sources = path.join(path.dirname(path.dirname(path.dirname(androidJar))), 'sources', platform);
      if (this.fs.exists(sources)) roots.push({ path: sources, type: 'SOURCES' });
    }
    return this.putLibrary({ name: `Gradle: android:android-sdk:${platform}`, type: 'COMPILE', roots });
  }

  // ---- JVM ----

  private addJvm(project: GradleProjectModel, holder: string): void {
    const components = project.extras?.components ?? {};
    const mainModule = `${holder}.main`;
    for (const kind of ['main', 'test'] as const) {
      const component = components[kind];
      if (!component) continue;
      const name = `${holder}.${kind}`;
      const test = kind === 'test';
      const contentRoots = this.jvmContentRoots(project, kind, component, test);
      const dependencies: DependencyData[] = [{ type: 'moduleSource' }];
      if (test) dependencies.push({ type: 'module', name: mainModule, scope: 'compile' });
      this.addProjectDependencies(dependencies, component, 'compile');
      this.addExternalLibraries(dependencies, component.libraries ?? [], 'compile');
      dependencies.push(this.sdkDependency());
      this.modules.push({
        name,
        dependencies: dedupeDependencies(dependencies),
        contentRoots,
        externalProjectPath: project.dir,
        externalProjectId: project.path,
      });
      this.kotlinSettings.push(
        this.kotlinSettingsFor(name, contentRoots, component.kotlin, project.extras?.kotlinPluginVersion, test, test ? [mainModule] : []),
      );
      this.javaSettings.push(this.javaSettingsFor(name, javaLanguageLevel(component.javaRelease ?? undefined)));
    }
  }

  private jvmContentRoots(project: GradleProjectModel, kind: string, component: ExtrasComponent, test: boolean): ContentRootData[] {
    const base = path.join(project.dir, 'src', kind);
    const sourceType = test ? 'java-test' : 'java-source';
    const resourceType = test ? 'java-test-resource' : 'java-resource';
    const sourceDirs = unique([...(component.javaDirs ?? []), ...(component.kotlinDirs ?? []), ...(component.generatedDirs ?? [])]);
    const inBase: SourceRootData[] = [];
    const outside: ContentRootData[] = [];
    const place = (dir: string, type: SourceRootData['type']) => {
      if (!this.fs.exists(dir)) return;
      if (isInside(dir, base)) inBase.push({ path: dir, type });
      else outside.push({ path: dir, sourceRoots: [{ path: dir, type }] });
    };
    for (const dir of sourceDirs) place(dir, sourceType);
    for (const dir of unique(component.resourceDirs ?? [])) place(dir, resourceType);
    return [inBase.length > 0 ? { path: base, sourceRoots: inBase } : { path: base }, ...outside];
  }

  // ---- dependencies ----

  /** Module dependencies on sibling projects, plus their R jars so `other.module.R` resolves (nonTransitiveRClass). */
  private addProjectDependencies(dependencies: DependencyData[], component: ExtrasComponent | undefined, scope: DependencyScope): void {
    for (const dependency of component?.projectDependencies ?? []) {
      if (dependency.build !== ':') continue;
      const target = this.projects.get(dependency.path);
      if (!target) {
        this.warnings.push(`project dependency ${dependency.path} is not part of the model`);
        continue;
      }
      const targetHolder = this.holderName(target.path);
      if (target.android) {
        const main = target.android.components.main;
        dependencies.push({ type: 'module', name: this.androidModuleName(targetHolder, target.android.selectedVariant, 'main'), scope });
        if (main) {
          const moduleName = this.androidModuleName(targetHolder, target.android.selectedVariant, 'main');
          for (const jar of this.classJars(main)) {
            dependencies.push(this.libraryDependency(this.fileLibrary(this.generatedJarName(target.path, moduleName, jar), [jar]), scope));
          }
        }
      } else if (target.extras?.kind === 'jvm' && target.extras.components?.main) {
        dependencies.push({ type: 'module', name: `${targetHolder}.main`, scope });
      }
    }
  }

  private addExternalLibraries(dependencies: DependencyData[], libraries: ExternalLibrary[], scope: DependencyScope): void {
    for (const library of libraries) {
      const files = library.files.filter((f) => this.fs.exists(f));
      if (files.length === 0) continue;
      const roots: LibraryRootData[] = files.map((f) => ({ path: f }));
      const sources = this.sourcesJar(library);
      if (sources) roots.push({ path: sources, type: 'SOURCES' });
      const data = this.putLibrary({ name: `Gradle: ${library.id}`, type: 'COMPILE', roots });
      dependencies.push(this.libraryDependency(data, scope));
    }
  }

  private fileLibrary(name: string, files: string[]): LibraryData {
    return this.putLibrary({ name, type: 'COMPILE', roots: files.map((f) => ({ path: f })) });
  }

  /** Registers a library; a different library under a taken name gets a distinguishing suffix. */
  private putLibrary(library: LibraryData): LibraryData {
    const existing = this.libraries.get(library.name);
    if (!existing) {
      this.libraries.set(library.name, library);
      return library;
    }
    const sameClasses = (a: LibraryData, b: LibraryData) =>
      JSON.stringify(classRoots(a)) === JSON.stringify(classRoots(b));
    if (sameClasses(existing, library)) return existing;
    for (let i = 2; ; i++) {
      const name = `${library.name} (${i})`;
      const other = this.libraries.get(name);
      if (!other) {
        const renamed = { ...library, name };
        this.libraries.set(name, renamed);
        return renamed;
      }
      if (sameClasses(other, library)) return other;
    }
  }

  private libraryDependency(library: LibraryData, scope: DependencyScope): DependencyData {
    return { type: 'library', name: library.name, scope };
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

  // ---- settings ----

  private kotlinSettingsFor(
    module: string,
    contentRoots: ContentRootData[],
    kotlin: KotlinOptions | null | undefined,
    kotlinPluginVersion: string | null | undefined,
    test: boolean,
    friends: string[],
  ): KotlinSettingsData {
    const sourceRoots = contentRoots.flatMap((r) => (r.sourceRoots ?? []).filter((s) => s.type === 'java-source' || s.type === 'java-test').map((s) => s.path));
    const languageVersion = kotlin?.languageVersion ?? majorMinor(kotlinPluginVersion);
    const compilerArguments = {
      ...(languageVersion ? { languageVersion } : {}),
      ...(kotlin?.jvmTarget ? { jvmTarget: kotlin.jvmTarget } : {}),
      pluginOptions: kotlin?.pluginOptions ?? [],
      pluginClasspaths: (kotlin?.pluginClasspath ?? []).filter((f) => this.fs.exists(f)),
    };
    // Only the keys the server itself exports go into compilerArguments; everything else travels as CLI arguments.
    const additional = [
      ...(kotlin?.apiVersion ? ['-api-version', kotlin.apiVersion] : []),
      ...(kotlin?.freeCompilerArgs ?? []),
      ...(kotlin?.optIn ?? []).map((o) => `-opt-in=${o}`),
    ];
    return {
      name: 'Kotlin',
      sourceRoots,
      configFileItems: [],
      module,
      useProjectSettings: false,
      implementedModuleNames: [],
      dependsOnModuleNames: [],
      additionalVisibleModuleNames: friends,
      productionOutputPath: null,
      testOutputPath: null,
      sourceSetNames: [],
      isTestModule: test,
      externalProjectId: module,
      isHmppEnabled: true,
      pureKotlinSourceFolders: [],
      kind: 'default',
      compilerArguments: `J${JSON.stringify(compilerArguments)}`,
      additionalArguments: additional.length > 0 ? additional.join(' ') : null,
      scriptTemplates: null,
      scriptTemplatesClasspath: null,
      outputDirectoryForJsLibraryFiles: null,
      targetPlatform: null,
      externalSystemRunTasks: [],
      version: 5,
      flushNeeded: false,
    };
  }

  private javaSettingsFor(module: string, languageLevelId: string | null): JavaSettingsData {
    return { module, inheritedCompilerOutput: false, excludeOutput: false, languageLevelId, manifestAttributes: {} };
  }

  private languageLevel(project: GradleProjectModel): string | null {
    return javaLanguageLevel(project.android?.javaCompileOptions?.sourceCompatibility ?? project.extras?.components?.main?.javaRelease ?? undefined);
  }

  private jdk(javaHome: string | undefined): SdkData {
    const release = javaHome ? this.fs.readFile(path.join(javaHome, 'release')) : undefined;
    const version = /JAVA_VERSION="(\d+)(?:\.(\d+))?/.exec(release ?? '');
    const major = version ? (version[1] === '1' ? version[2] : version[1]) : undefined;
    const name = major ?? '17';
    return { name, type: 'jdk', version: `JDK_${name}`, homePath: javaHome ?? null, additionalData: '' };
  }

  private sdkDependency(): DependencyData {
    return { type: 'sdk', name: this.sdk.name, kind: this.sdk.type };
  }

  private holderName(projectPath: string): string {
    return projectPath === ':' ? this.model.rootProjectName : `${this.model.rootProjectName}${projectPath.replace(/:/g, '.')}`;
  }
}

// ---- helpers ----

function unique<T>(values: T[]): T[] {
  return [...new Set(values)];
}

function classRoots(library: LibraryData): string[] {
  return library.roots.filter((r) => (r.type ?? 'CLASSES') === 'CLASSES').map((r) => r.path);
}

/** Keeps the first occurrence of each module/library dependency; the order decides class resolution priority. */
function dedupeDependencies(dependencies: DependencyData[]): DependencyData[] {
  const seen = new Set<string>();
  return dependencies.filter((d) => {
    const key = 'name' in d ? `${d.type}:${d.name}` : d.type;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function isInside(child: string, parent: string): boolean {
  const relative = path.relative(parent, child);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

/** Directory that contains all the given folders, e.g. `src/main` for `src/main/java` and `src/main/kotlin`. */
function commonParent(dirs: string[]): string | undefined {
  const parents = unique(dirs.map((d) => path.dirname(d)));
  if (parents.length === 0) return undefined;
  let common = parents[0]!;
  for (const parent of parents.slice(1)) {
    while (!isInside(parent, common)) {
      const up = path.dirname(common);
      if (up === common) return undefined;
      common = up;
    }
  }
  return common;
}

function majorMinor(version: string | null | undefined): string | undefined {
  const match = /^(\d+)\.(\d+)/.exec(version ?? '');
  return match ? `${match[1]}.${match[2]}` : undefined;
}

/** "17", "1.8", "VERSION_17" or "JavaVersion.VERSION_1_8" → "JDK_17" / "JDK_1_8". */
export function javaLanguageLevel(value: string | undefined): string | null {
  if (!value) return null;
  const match = /(\d+)(?:[._](\d+))?$/.exec(value.trim());
  if (!match) return null;
  const [, major, minor] = match;
  if (major === '1' && minor) return Number(minor) >= 9 ? `JDK_${minor}` : `JDK_1_${minor}`;
  return `JDK_${major}`;
}
