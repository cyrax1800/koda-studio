# Koda Studio

Code Android projects in VS Code with Koda Studio's own Kotlin language server: completion, go to
definition, hover, find references, errors as you type, and a "N usages" CodeLens, across modules
and the generated code Gradle reports. You don't need any JetBrains extension.

> **Status: phase 2, milestone 5 (in progress).** The server, in `lsp/`, runs on the Kotlin 2.4.20 compiler
> front end (Analysis API) with the IntelliJ 251 platform.
>
> What works now:
> - errors as you type, including Kotlin 2.3 features such as explicit backing fields, using each module's
>   compiler flags;
> - compiler plugins: Compose checks, kotlinx-serialization (`serializer()`), Parcelize. The server uses its own
>   builds of these plugins, matched to the ones your build applies;
> - generated code: R classes, `BuildConfig`, protobuf, view/data binding, KSP/kapt output (Hilt, Room), and Java
>   sources in your modules;
> - parameter info while typing a call (`(` and `,` open it; **Cmd+Shift+Space** on macOS, Ctrl+Shift+Space elsewhere):
>   every overload with types and defaults, the current parameter highlighted;
> - parameter names before literal arguments, `Text(text: "Hi")` (`kodaStudio.inlayHints.parameterNames`);
> - Compose stability: `stable` / `runtime` / `unstable` hints after
>   composable parameters, a warning on parameters that stop a composable from skipping, and the reasons on hover.
>   Worked out live as you type (`kodaStudio.composeStability.enabled`);
> - test source sets see `internal` declarations of their main source set;
> - files changed outside the editor (a `git checkout`, new generated code) are picked up without a restart;
> - go to definition, hover, document symbols;
> - find references across modules, including Java files (e.g. Hilt's generated factories) and KDoc links, and the
>   usages CodeLens. Searches run in the background, so they never hold up completion or typing;
> - completion: names in scope, members after `x.` (nested classes after a class name), labels after `this@` and
>   `return@`, extension functions and
>   properties (also on `this`, e.g. `lifecycleScope`), classes or functions that aren't imported yet (the import
>   is added), keywords, named arguments (`modifier =`) and overrides. Calls complete with their required arguments
>   only, passed by name (`Text(text = …)`, `kodaStudio.completion.namedArguments`), a trailing lambda as a
>   block (`launch { }`, `Row { }`) and type arguments that can't be inferred typed out (`entry<…> { }`). Suggestions
>   that fit the expected type come first: enum entries, sealed subclasses, `Alignment.CenterVertically` for an
>   `Alignment.Vertical`, `true`/`false`. **Ctrl+Shift+Space** lists only those (IntelliJ's smart completion).
>
> - **Optimize Imports** (Ctrl+Option+O, also on save): removes unused imports and sorts the rest as Android Studio
>   does. **Reformat Code** (Cmd+Option+L on macOS, Ctrl+Alt+L elsewhere): fixes indentation, spacing and blank lines
>   and keeps your line breaks and trailing commas. A project whose `.editorconfig` configures ktlint gets its full
>   ktlint setup;
> - **Implement members** (Cmd+. on a class or `object : LifecycleEventObserver { }` that misses abstract members):
>   adds them with their full signatures and `TODO()` bodies. Typing `override fun onSa…` (or `fun onSa…`) in a class
>   completes the whole override;
> - Java library parameters keep their names (`observe(owner) { }`, `post { }`, `onCreate(savedInstanceState: Bundle?)`)
>   from the class files or the library's sources, and the Android SDK's sources when installed; a Java functional
>   interface parameter last in the call is completed as a trailing lambda;
> - in a type (`val x: Frag…`, `object : FragmentManager.Fragm…`) completion offers only types;
> - go to definition into libraries opens their sources (`-sources.jar`, the SDK's `sources/android-NN`), else the class
>   decompiled to Java with method bodies (Vineflower, as Android Studio uses Fernflower). Inside an opened Java
>   source (`AppCompatActivity.java`) Cmd+click keeps navigating: to members of the same class, other library classes
>   and the Android SDK;
> - a status bar item shows what the Kotlin server is doing: loading the project, indexing (files done of found),
>   checking a file for errors, or ready.
>
> Known gaps:
> - compiler plugins other than Compose, serialization and Parcelize are skipped;
> - Java usages of Kotlin properties through their getters/setters (`getName()`) aren't counted;
> - Android SDK methods name their parameters `p0`, `p1` unless the SDK's sources are installed (SDK Manager → Sources
>   for Android NN): `android.jar` has no parameter names.

## How it works

1. **Sync.** `server/koda-sync.jar` runs your project's Gradle through the Tooling API and
   reads Android Studio's own IDE models (AGP "v2" models). A small init script
   (`gradle/koda.init.gradle`) adds what those models leave out:
   - the compile classpath as the compiler sees it, where AAR jars contain their R classes;
   - Kotlin compiler options and plugins;
   - plain JVM modules.
2. **Generate.** It runs the cheap source-generation tasks for the selected variant: R jars,
   BuildConfig, view/data binding and AIDL. It never runs KSP or compilation unless you ask.
3. **Modules.** The model becomes `.gradle/koda/modules.json`, the server's module graph.
   It has one module per Android component (`app.debug`, `app.debugUnitTest`, …) or JVM source set,
   with their sources, generated folders, jars and module dependencies.
4. **Server.** `server/kotlin-lsp` runs on the Kotlin Analysis API (the compiler's K2 front end)
   with its own on-disk index. It reads `modules.json` and restarts after each sync. Its index lives
   in VS Code's workspace storage, not in your project.
5. **CodeLens.** "N usages" (and optionally "N implementations") above Kotlin declarations, computed
   through the server's find references. Searches wait until you stop typing.

## Requirements

- VS Code 1.90+.
- A JDK 17+ for Gradle and the server. By default Koda Studio uses the same JDK as `./gradlew` in
  a terminal (`org.gradle.java.home`, then `JAVA_HOME`, then the newest installed JDK), so the sync
  reuses your Gradle daemon.
- An Android project built with AGP 8.x or 9.x. Variant discovery uses `ProjectGraph` on AGP 8.10+;
  older AGP versions match variants by build type and flavors.
- Disable **Kotlin by JetBrains** and **Java and Kotlin by IntelliJ IDEA** in the workspace, if they
  are installed. Otherwise two servers analyze the same files. Koda Studio warns when either is
  enabled.

## Usage

Open the project folder (the one with `settings.gradle(.kts)`). On first open Koda Studio syncs
and starts the server. Status bar: `✓ Koda: prodDebug`. Click it for actions.

| Command | What it does |
|---|---|
| **Koda: Sync Project with Gradle Files** | Re-reads the project model, rewrites `modules.json` and restarts the server. Prompted automatically when build files change. |
| **Koda: Select Build Variant** | Picks the app variant (e.g. `prodDebug`). Library modules follow what the app consumes. |
| **Koda: Generate Sources (including KSP/kapt)** | Also runs `generate<Variant>Sources` and KSP/kapt. Slower: it compiles upstream modules. |
| **Koda: Restart Kotlin Server** | Restarts the language server, e.g. after changing its JVM settings. |
| **Koda: Rebuild Kotlin Index** | Deletes the server's index and indexes everything again. |
| **Koda: Clear Caches and Restart…** | Android Studio's Invalidate Caches: clears the Kotlin index, or the index and the Gradle sync, and starts again as on a first open. |
| **Koda: Show Sync Log** | Gradle output and sync problems. |
| **Koda: Show Kotlin Server Log** | The server's own log. Also opened by clicking the `Kotlin` status bar item. |

| Shortcut (macOS / Windows, Linux) | Android Studio's | |
|---|---|---|
| Cmd+Option+L / Ctrl+Alt+L | Reformat Code | VS Code's Format Document, in Kotlin files. |
| Ctrl+Option+O / Ctrl+Alt+O | Optimize Imports | Also runs on every save (`editor.codeActionsOnSave`). |
| Cmd+Shift+Space / Ctrl+Shift+Space | Parameter Info (Cmd+P) | VS Code's Trigger Parameter Hints. Cmd+P stays Quick Open. |
| Ctrl+Shift+Space | Smart completion | Only suggestions of the expected type. On Windows and Linux this replaces Trigger Parameter Hints in Kotlin files. |

Koda Studio sets two defaults for Kotlin files: imports are optimized on save, and Koda Studio is the formatter.
Change them under `"[kotlin]"` in your settings, e.g. `"editor.codeActionsOnSave": {}` to stop optimizing on save,
or `"editor.formatOnSave": true` to also reformat on save.

The `Kotlin` status bar item next to the sync status shows the server: `starting…`, `loading project…`,
`indexing 12,345/43,329`, `analyzing HomeViewModel.kt`, then `✓ Kotlin`. When completion, go to declaration or hover
takes more than a moment it says so (`completing…`, `finding the declaration…`), with what the server is busy with,
e.g. `completing… (indexing 812/5,003)`. Until indexing finishes, classes and
functions that aren't imported yet may be missing from completion. After indexing library jars (the first open, or a
sync with new dependencies) the server restarts once by itself, so nothing analysed before the index was complete
stays wrong; normal starts don't.

KSP/kapt output (Hilt `Hilt_*`, Room `*_Impl`, Dagger components) appears after a build. A normal
`./gradlew assembleProdDebug` is enough: Koda Studio watches `build/generated` and picks up new
folders without another sync.

### Settings

| Setting | Default | |
|---|---|---|
| `kodaStudio.variant` | app's first debug variant | Build variant of application modules. |
| `kodaStudio.sync.generateSources` | `light` | `none`, `light` (R, BuildConfig, binding, AIDL) or `full` (also KSP/kapt). |
| `kodaStudio.sync.onGradleFileChange` | `ask` | `ask`, `always` or `never` re-sync when build files change. |
| `kodaStudio.sync.stopDaemonsOnClose` | `true` | When the window closes, stop the Gradle and Kotlin daemons Koda's syncs started (not those of your own builds). |
| `kodaStudio.sync.stopGradleDaemon` | `afterFirstSync` | Stop the Gradle daemon a sync started when it's done, freeing its memory for indexing: `afterFirstSync` (also after Clear Caches), `always` or `never`. |
| `kodaStudio.gradle.javaHome` | | Force a JDK for Gradle. |
| `kodaStudio.gradle.arguments` | `[]` | Extra Gradle arguments. |
| `kodaStudio.server.javaHome` | | Force a JDK for the Kotlin server. |
| `kodaStudio.server.jvmArgs` | `["-Xmx3g", …]` | JVM arguments for the Kotlin server. The default keeps it at about 3.5–4 GB on a large project; a larger `-Xmx` makes big reference searches faster. |
| `kodaStudio.librarySources` | `download` | Library `-sources.jar` files for go to definition: `download` them during sync, use only those already `cached`, or `off`. |
| `kodaStudio.androidSdkSources` | `false` | Attach Android SDK sources. Larger index. |
| `kodaStudio.completion.namedArguments` | `true` | Completed Kotlin calls pass required arguments by name. |
| `kodaStudio.inlayHints.parameterNames` | `literals` | Parameter names before `literals`, `all` arguments, or `none`. |
| `kodaStudio.composeStability.enabled` | `true` | Compose stability hints, warnings and hover. |
| `kodaStudio.codeLens.enabled` | `true` | "N usages" lens. |
| `kodaStudio.codeLens.implementations` | `false` | "N implementations" lens. |
| `kodaStudio.codeLens.maxSymbolsPerFile` | `0` | Skip the lens in files with more declarations than this; `0` is no limit. Only declarations on screen are searched. |

## What changes in your project

- `.gradle/koda/` holds `model.json` and `modules.json`. `.gradle` is normally gitignored. (Before the rename to
  Koda Studio it was `.gradle/android-bridge/`; it is moved on the next start. Settings named `androidBridge.*` are
  still read.)
- Nothing else. Settings and the index stay in VS Code.

## Limitations

- **Kotlin only.** `.java` files are analyzed so Kotlin code sees them, but there is no completion
  inside them. Java *libraries* work fully.
- See the status note at the top for the gaps of the current milestone.
- Not imported yet: Kotlin Multiplatform Android modules (`com.android.kotlin.multiplatform.library`),
  included builds such as `build-logic`/`buildSrc`, and multi-root workspaces (the first Android
  root is used).
- The CodeLens doesn't count usages in XML layouts, unlike Android Studio.
- `builder-model` must be at least the project's AGP version. A sync error mentioning
  `ModelBuilderParameter` "Unsupported method" means the AGP is newer than Koda Studio supports.

## Development

```bash
npm install
npm run build:sidecar   # Gradle sync tool → server/koda-sync.jar
npm run build:lsp       # Kotlin language server (lsp/) → server/kotlin-lsp/
npm run build
npm test
npm run test:e2e        # real VS Code on a sample Android project
npm run package         # koda-studio-<version>.vsix
```

The build steps need a JDK 17+.

The end-to-end test opens a real VS Code with a throwaway profile. What it checks and times for a project is in
`test/e2e/profiles/<folder name>.cjs` (errors per file, completion while typing, go to declaration, hover,
references, CodeLens, restart), each timing with a budget it reports as `SLOW` when exceeded:

```bash
node test/e2e/run.mjs <project> --pass both                        # clean, then warm on the same index
node test/e2e/run.mjs <project> --pass clean --keep                 # keep the index for later --pass warm runs
node test/e2e/run.mjs <project> --sync                              # also time a Gradle sync
```

Results go to `.e2e-results/<project>-<pass>-<time>.json`, with a table of both passes and the server's peak memory.

Tools for working without the VS Code UI:

```bash
# Export a project's model and convert it.
java -jar server/koda-sync.jar --project-dir <project> \
  --init-script gradle/koda.init.gradle --out <project>/.gradle/koda/model.json
node tools/convert-model.ts <project>/.gradle/koda/model.json <project>/.gradle/koda/modules.json

# Start the server headless on it and check diagnostics, completion, references and latency.
node tools/lsp-probe.mjs --root <project> --server-cmd "$PWD/server/kotlin-lsp/bin/kotlin-lsp" \
  --init-options '{"modulesFile":"<project>/.gradle/koda/modules.json","indexDir":"/tmp/kotlin-index"}' \
  --probes <probes>.json
```

Third-party code and licenses are listed in `THIRD_PARTY_NOTICES.md`.
