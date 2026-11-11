# Koda Studio for Zed

Runs Koda's Kotlin language server (`../lsp`) in Zed for Android Gradle projects. It brings the Kotlin language itself too (grammar, highlighting, outline, test tasks), copied from [zed-extensions/kotlin](https://github.com/zed-extensions/kotlin) under MIT (`LICENSE-zed-kotlin`). Don't install Zed's **Kotlin** extension alongside it: both would define Kotlin.

## Install (from this repository)

1. `npm run install:zed`: builds `dist/zed-launcher.cjs` (and the server if it is missing) and writes `~/.koda-studio/bin/koda-lsp`.
2. In Zed, run `zed: install dev extension` and pick this `zed/` folder.
3. Use only Koda for Kotlin, in Zed's settings:

   ```jsonc
   "languages": {
     "Kotlin": {
       "language_servers": ["koda", "!kotlin-language-server", "!kotlin-lsp"],
       "code_actions_on_format": { "source.organizeImports": true }
     }
   }
   ```

## How it runs

- Go to definition into a library opens its real source: each sync downloads the `-sources.jar` of every library that publishes one (decompiled code only for those that don't).
- The launcher syncs the project with Gradle only when `.gradle/koda/modules.json` is missing (a VS Code sync writes the same file). Force a new sync with `"lsp": { "koda": { "binary": { "arguments": ["--sync"] } } }`, or sync in VS Code.
- The index lives in `.gradle/koda/zed-index`, apart from VS Code's, so both editors can run at once.
- Environment (`lsp.koda.binary.env`): `KODA_JAVA_HOME`, `KODA_GRADLE_JAVA_HOME`, `KODA_JVM_ARGS`, `KODA_VARIANT`, `KODA_GENERATE`, `KODA_LIBRARY_SOURCES` (`cached` skips downloading library sources jars during sync).
- Server options go in `lsp.koda.initialization_options` (`composeStability`, `namedArguments`, `parameterNameHints`).

Not in Zed (VS Code extension only): the status bar, Ctrl+Shift+Space smart completion, the usages CodeLens, and the automatic restart after the first library indexing. After a first index, run `editor: restart language server` once.
