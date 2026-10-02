# Android Bridge for Kotlin

Code Android projects in VS Code with the free **Kotlin by JetBrains** language server:
completion, go to definition, find references and a "N usages" CodeLens that work across
modules, including R classes, BuildConfig, view/data binding, KSP/Hilt/Room and protobuf
generated code.

Android Bridge does not ship its own language server. It replaces the step that fails for Android
projects, the project import: it reads the project the way Android Studio's Gradle sync does and
hands the result to the Kotlin server as a `workspace.json`.

## How it works

1. **Sync.** `server/android-bridge-sync.jar` runs your project's Gradle through the Tooling API and
   reads Android Studio's own IDE models (AGP "v2" models). A small init script
   (`gradle/android-bridge.init.gradle`) adds what those models leave out:
   - the compile classpath as the compiler sees it, where AAR jars contain their R classes;
   - Kotlin compiler options and compiler plugins (Compose, Parcelize, serialization);
   - plain JVM modules.
2. **Generate.** It runs the cheap source-generation tasks for the selected variant: R jars,
   BuildConfig, view/data binding and AIDL. It never runs KSP or compilation unless you ask.
3. **Import.** It converts the model to `.gradle/android-bridge/workspace.json`. It then points the
   `intellij.projects` setting at that file and reloads the Kotlin server.
4. **CodeLens.** "N usages" (and optionally "N implementations") above Kotlin declarations, computed
   through the server's find-references.

## Requirements

- VS Code 1.90+ and the **Kotlin by JetBrains** extension (`jetbrains.kotlin-server`).
- Disable **Java and Kotlin by IntelliJ IDEA** (`jetbrains.intellij-server`) in the workspace. The
  two run conflicting language servers.
- A JDK 17+ for Gradle. By default Android Bridge uses the same JDK as `./gradlew` in a terminal
  (`org.gradle.java.home`, then `JAVA_HOME`, then the newest installed JDK), so it reuses your Gradle
  daemon.
- An Android project built with AGP 8.x or 9.x. Variant discovery uses `ProjectGraph` on AGP 8.10+;
  older AGP versions match variants by build type and flavors.

## Usage

Open the project folder (the one with `settings.gradle(.kts)`). On first open Android Bridge asks to
set the project up, then syncs. Status bar: `✓ Android: prodDebug`. Click it for actions.

| Command | What it does |
|---|---|
| **Android: Sync Project with Gradle Files** | Re-reads the project model and regenerates `workspace.json`. Prompted automatically when build files change. |
| **Android: Select Build Variant** | Picks the app variant (e.g. `prodDebug`); library modules follow what the app consumes. |
| **Android: Generate Sources (including KSP/kapt)** | Also runs `generate<Variant>Sources` and KSP/kapt. Slower: it compiles upstream modules. |
| **Android: Show Sync Log** | Gradle output and sync problems. |

KSP/kapt output (Hilt `Hilt_*`, Room `*_Impl`, Dagger components) appears after a build. A normal
`./gradlew assembleProdDebug` is enough: Android Bridge watches `build/generated` and picks up new
folders without another sync.

### Settings

| Setting | Default | |
|---|---|---|
| `androidBridge.variant` | app's first debug variant | Build variant of application modules. |
| `androidBridge.sync.generateSources` | `light` | `none`, `light` (R, BuildConfig, binding, AIDL) or `full` (also KSP/kapt). |
| `androidBridge.sync.onGradleFileChange` | `ask` | `ask`, `always` or `never` re-sync when build files change. |
| `androidBridge.gradle.javaHome` | | Force a JDK for Gradle. |
| `androidBridge.gradle.arguments` | `[]` | Extra Gradle arguments. |
| `androidBridge.librarySources` | `cached` | Attach `-sources.jar` files already in the Gradle cache. |
| `androidBridge.androidSdkSources` | `false` | Attach Android SDK sources. Larger server index. |
| `androidBridge.codeLens.enabled` | `true` | "N usages" lens. |
| `androidBridge.codeLens.implementations` | `false` | "N implementations" lens. |

### Performance

Completion, references and diagnostics are computed by the Kotlin server, not by Android Bridge. On
pokedex-compose, warm completion takes about 0.4 s for names in scope and about 1 s after `x.`. The
first completion after the server starts takes about 3 s while the JVM warms up.

The usages CodeLens uses the same server, so it waits until you stop typing for 1.5 s before it
searches. After a save it keeps showing the old counts and updates them in the background. If
completion still feels slow, set `androidBridge.codeLens.enabled` to `false`.

The server has 2 GB of heap by default. Large projects need more, e.g.
`"intellij.additionalJvmArgs": ["-Xmx4g"]`.

## What changes in your project

- `.vscode/settings.json` gets one `intellij.projects` entry, with an absolute path to the file in
  the next bullet. Don't commit it.
- `.gradle/android-bridge/` holds `model.json` and `workspace.json`. `.gradle` is normally gitignored.

## Limitations

- **Kotlin only.** `.java` files are analyzed so Kotlin code sees them, but there is no completion
  inside them. Java *libraries* work fully.
- **Compose:** occasional false errors come from the Kotlin server itself.
- Not imported yet: Kotlin Multiplatform Android modules (`com.android.kotlin.multiplatform.library`),
  included builds such as `build-logic`/`buildSrc`, and multi-root workspaces (the first Android root
  is used).
- The CodeLens doesn't count usages in XML layouts, unlike Android Studio.
- `workspace.json` follows the format of Kotlin LSP build 263.4702.0 (`jetbrains.kotlin-server`
  0.0.12). The server rejects unknown keys, so a new server build may need an update here.
- `builder-model` must be at least the project's AGP version. A sync error mentioning
  `ModelBuilderParameter` "Unsupported method" means the AGP is newer than Android Bridge supports.

## Development

```bash
npm install
npm run build:sidecar
npm run build
npm test
npm run package
```

`npm run build:sidecar` needs a JDK 17+, and `npm run package` produces `android-bridge-0.1.0.vsix`.

Tools for working without the VS Code UI:

```bash
# Export a project's model and convert it.
java -jar server/android-bridge-sync.jar --project-dir <project> \
  --init-script gradle/android-bridge.init.gradle --out <project>/.gradle/android-bridge/model.json
node tools/convert-model.ts <project>/.gradle/android-bridge/model.json <project>/.gradle/android-bridge/workspace.json

# Start the Kotlin server headless on it and check diagnostics, completion and references.
node tools/lsp-probe.mjs --root <project> --workspace-json <project>/.gradle/android-bridge/workspace.json \
  --probes tools/probes/pokedex-compose.json --region <your intellij.region> --java-home <jdk>
```
