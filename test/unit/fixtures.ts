// A small synthetic project: an app with flavors, an Android library (R + view binding + KSP output) and a
// plain Kotlin/JVM library. Paths are fake; tests decide which of them "exist".
import type { AndroidComponent, ExtrasComponent, GradleProjectModel, ProjectModel } from '../../src/gradle/modelTypes.ts';

export const ROOT = '/work/demo';
export const SDK = '/sdk';
export const GRADLE_HOME = '/home/.gradle';
export const JDK = '/jdk/21';

const kotlin = (task: string, extra: Partial<NonNullable<ExtrasComponent['kotlin']>> = {}) => ({
  task,
  jvmTarget: '17',
  languageVersion: null,
  apiVersion: null,
  freeCompilerArgs: ['-Xexplicit-backing-fields'],
  optIn: ['kotlin.RequiresOptIn'],
  pluginClasspath: [],
  pluginOptions: [],
  ...extra,
});

function provider(dir: string, name: string) {
  return {
    name,
    java: [`${dir}/src/${name}/java`],
    kotlin: [`${dir}/src/${name}/java`, `${dir}/src/${name}/kotlin`],
    resources: [`${dir}/src/${name}/resources`],
    aidl: [`${dir}/src/${name}/aidl`],
  };
}

function component(dir: string, providers: string[], variantDir: string, extra: Partial<AndroidComponent> = {}): AndroidComponent {
  return {
    sourceProviders: providers.map((p) => provider(dir, p)),
    generatedSourceFolders: [`${dir}/build/generated/ksp/${variantDir}/kotlin`, `${dir}/build/generated/ksp/${variantDir}/java`],
    classesFolders: [
      `${dir}/build/intermediates/javac/${variantDir}/compile${variantDir}JavaWithJavac/classes`,
      `${dir}/build/intermediates/built_in_kotlinc/${variantDir}/compileKotlin/classes`,
    ],
    generatedClassPaths: {},
    ideSetupTaskNames: [],
    ...extra,
  };
}

const app: GradleProjectModel = {
  path: ':app',
  name: 'app',
  dir: `${ROOT}/app`,
  android: {
    agpVersion: '9.4.1',
    projectType: 'APPLICATION',
    namespace: 'com.demo',
    selectedVariant: 'prodDebug',
    bootClasspath: [`${SDK}/platforms/android-36/android.jar`, `${SDK}/build-tools/36.0.0/core-lambda-stubs.jar`],
    javaCompileOptions: { sourceCompatibility: '17', targetCompatibility: '17' },
    variants: [
      { name: 'devDebug', buildType: 'debug', productFlavors: ['dev'] },
      { name: 'prodDebug', buildType: 'debug', productFlavors: ['prod'] },
    ],
    components: {
      main: component(`${ROOT}/app`, ['main', 'prod', 'debug', 'prodDebug'], 'prodDebug', {
        generatedSourceFolders: [`${ROOT}/app/build/generated/source/buildConfig/prod/debug`],
        classesFolders: [
          `${ROOT}/app/build/intermediates/javac/prodDebug/classes`,
          `${ROOT}/app/build/intermediates/compile_r_class_jar/prodDebug/generateProdDebugRFile/R.jar`,
        ],
      }),
      unitTest: component(`${ROOT}/app`, ['test', 'testProd'], 'prodDebugUnitTest', {
        classesFolders: [`${ROOT}/app/build/intermediates/compile_and_runtime_r_class_jar/prodDebugUnitTest/generateProdDebugUnitTestStubRFile/R.jar`],
      }),
    },
  },
  extras: {
    projectPath: ':app',
    kind: 'android',
    variant: 'prodDebug',
    kotlinPluginVersion: '2.2.21',
    problems: [],
    tasks: ['generateProdDebugBuildConfig', 'generateProdDebugRFile'],
    components: {
      main: {
        configuration: 'prodDebugCompileClasspath',
        libraries: [
          {
            id: 'androidx.core:core:1.16.0',
            group: 'androidx.core',
            module: 'core',
            version: '1.16.0',
            files: [`${GRADLE_HOME}/caches/transforms/aa/transformed/core-1.16.0-api.jar`],
            artifactType: 'android-classes-jar',
          },
          {
            id: 'com.squareup.okhttp3:okhttp:4.12.0',
            group: 'com.squareup.okhttp3',
            module: 'okhttp',
            version: '4.12.0',
            files: [`${GRADLE_HOME}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0/h1/okhttp-4.12.0.jar`],
            artifactType: 'android-classes-jar',
          },
        ],
        projectDependencies: [
          { path: ':feature:home', build: ':' },
          { path: ':core:model', build: ':' },
        ],
        kotlin: kotlin('compileProdDebugKotlin', {
          pluginClasspath: [`${GRADLE_HOME}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.2.21/h2/compose.jar`],
          pluginOptions: ['plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true'],
        }),
      },
      unitTest: {
        configuration: 'prodDebugUnitTestCompileClasspath',
        libraries: [
          {
            id: 'junit:junit:4.13.2',
            group: 'junit',
            module: 'junit',
            version: '4.13.2',
            files: [`${GRADLE_HOME}/caches/modules-2/files-2.1/junit/junit/4.13.2/h3/junit-4.13.2.jar`],
            artifactType: 'android-classes-jar',
          },
        ],
        projectDependencies: [{ path: ':feature:home', build: ':' }],
        kotlin: kotlin('compileProdDebugUnitTestKotlin'),
      },
    },
  },
};

const featureHome: GradleProjectModel = {
  path: ':feature:home',
  name: 'home',
  dir: `${ROOT}/feature/home`,
  android: {
    agpVersion: '9.4.1',
    projectType: 'LIBRARY',
    namespace: 'com.demo.home',
    selectedVariant: 'prodDebug',
    bootClasspath: [`${SDK}/platforms/android-36/android.jar`],
    variants: [{ name: 'prodDebug', buildType: 'debug', productFlavors: ['prod'] }],
    components: {
      main: component(`${ROOT}/feature/home`, ['main', 'debug'], 'prodDebug', {
        generatedSourceFolders: [
          `${ROOT}/feature/home/build/generated/data_binding_base_class_source_out/prodDebug/out`,
          `${ROOT}/feature/home/build/generated/ksp/prodDebug/kotlin`,
        ],
        classesFolders: [
          `${ROOT}/feature/home/build/intermediates/javac/prodDebug/classes`,
          `${ROOT}/feature/home/build/intermediates/compile_r_class_jar/prodDebug/generateProdDebugRFile/R.jar`,
        ],
      }),
    },
  },
  extras: {
    projectPath: ':feature:home',
    kind: 'android',
    variant: 'prodDebug',
    kotlinPluginVersion: '2.2.21',
    problems: [],
    components: {
      main: {
        libraries: [
          {
            id: 'androidx.core:core:1.16.0',
            group: 'androidx.core',
            module: 'core',
            version: '1.16.0',
            files: [`${GRADLE_HOME}/caches/transforms/aa/transformed/core-1.16.0-api.jar`],
            artifactType: 'android-classes-jar',
          },
        ],
        projectDependencies: [{ path: ':core:model', build: ':' }],
        kotlin: kotlin('compileProdDebugKotlin'),
      },
    },
  },
};

const coreModel: GradleProjectModel = {
  path: ':core:model',
  name: 'model',
  dir: `${ROOT}/core/model`,
  extras: {
    projectPath: ':core:model',
    kind: 'jvm',
    kotlinPluginVersion: '2.2.21',
    problems: [],
    components: {
      main: {
        javaDirs: [`${ROOT}/core/model/src/main/java`],
        kotlinDirs: [`${ROOT}/core/model/src/main/kotlin`, `${ROOT}/core/model/src/main/java`, `${ROOT}/core/model/build/generated/ksp/main/kotlin`],
        resourceDirs: [`${ROOT}/core/model/src/main/resources`],
        generatedDirs: [],
        libraries: [
          {
            id: 'org.jetbrains.kotlin:kotlin-stdlib:2.2.21',
            group: 'org.jetbrains.kotlin',
            module: 'kotlin-stdlib',
            version: '2.2.21',
            files: [`${GRADLE_HOME}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.21/h4/kotlin-stdlib-2.2.21.jar`],
            artifactType: 'jar',
          },
        ],
        projectDependencies: [],
        kotlin: kotlin('compileKotlin', { jvmTarget: '21', freeCompilerArgs: [], optIn: [] }),
        javaRelease: '21',
      },
      test: {
        javaDirs: [`${ROOT}/core/model/src/test/java`],
        kotlinDirs: [`${ROOT}/core/model/src/test/kotlin`],
        resourceDirs: [],
        generatedDirs: [],
        libraries: [],
        projectDependencies: [],
        kotlin: kotlin('compileTestKotlin', { jvmTarget: '21', freeCompilerArgs: [], optIn: [] }),
        javaRelease: '21',
      },
    },
  },
};

const rootProject: GradleProjectModel = {
  path: ':',
  name: 'demo',
  dir: ROOT,
  extras: { projectPath: ':', kind: 'other', problems: [] },
};

export function demoModel(): ProjectModel {
  return {
    schemaVersion: 1,
    rootDir: ROOT,
    rootProjectName: 'demo',
    gradleVersion: '9.6.0',
    javaHome: JDK,
    projects: [structuredClone(rootProject), structuredClone(app), structuredClone(featureHome), structuredClone(coreModel)],
    problems: [],
  };
}

/** Every path mentioned in the model exists, except the ones in `missing`. */
export function fakeFs(model: ProjectModel, missing: string[] = []) {
  const files = new Map<string, string>([[`${JDK}/release`, 'JAVA_VERSION="21.0.2"\n']]);
  const existing = new Set<string>();
  const add = (p: string) => existing.add(p);
  const walk = (value: unknown) => {
    if (typeof value === 'string' && value.startsWith('/')) add(value);
    else if (Array.isArray(value)) value.forEach(walk);
    else if (value && typeof value === 'object') Object.values(value).forEach(walk);
  };
  walk(model);
  add(`${GRADLE_HOME}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0/h9/okhttp-4.12.0-sources.jar`);
  add(`${SDK}/sources/android-36`);
  for (const m of missing) existing.delete(m);
  const dirs = new Map<string, string[]>([
    [`${GRADLE_HOME}/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/4.12.0`, ['h1', 'h9']],
  ]);
  return {
    exists: (p: string) => existing.has(p) || files.has(p),
    readdir: (p: string) => dirs.get(p) ?? [],
    readFile: (p: string) => files.get(p),
  };
}
