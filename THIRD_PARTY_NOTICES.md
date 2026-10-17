# Third-party notices

## Kotlin language server (`lsp/`, shipped as `server/kotlin-lsp/`)

Forked from [H4-MM-3R/kotlin-lsp](https://github.com/H4-MM-3R/kotlin-lsp), Copyright 2025 Hemram,
licensed under the Apache License 2.0. That project is itself a fork of
[amgdev9/kotlin-lsp](https://github.com/amgdev9/kotlin-lsp), licensed under the MIT License. Its
license text is in `lsp/licenses/amgdev9-kotlin-lsp_LICENSE.txt`.

Koda Studio modified the server. Changed files say "Modified 2026 by Koda Studio contributors"
in their header, and `git log -- lsp` lists every change.

The server bundles the Kotlin compiler and Analysis API, IntelliJ Platform modules, LSP4J, RocksDB
and other libraries. Their licenses and notices are in `lsp/NOTICE` and `lsp/licenses/`, and they
ship in `server/kotlin-lsp/license/`.

`server/kotlin-lsp/plugins/` holds the IDE builds of the Kotlin compiler plugins for Compose,
kotlinx-serialization and Parcelize (`compose-compiler-plugin-for-ide`,
`kotlinx-serialization-compiler-plugin-for-ide`, `parcelize-compiler-plugin-for-ide`), version 2.4.20,
Copyright JetBrains s.r.o. and Kotlin Programming Language contributors, licensed under the Apache
License 2.0. They are used unmodified.

`server/kotlin-lsp/formatter/` holds the formatter: [ktlint](https://github.com/pinterest/ktlint) 1.8.0
(rule engine and standard rule set, Copyright Pinterest, Inc. and ktlint contributors, MIT License) with
the libraries it needs, unmodified: kotlin-compiler-embeddable, kotlin-stdlib and kotlin-reflect (Copyright JetBrains
s.r.o. and Kotlin Programming Language contributors, Apache License 2.0), kotlinx-coroutines (Apache License 2.0),
ec4j-core (Apache License 2.0), kotlin-logging (Apache License 2.0), poko-annotations (Apache License 2.0),
annotations (JetBrains, Apache License 2.0) and SLF4J (MIT License). It runs in a class loader of its own.

The server also bundles [Vineflower](https://github.com/Vineflower/vineflower) 1.12.0 (Copyright the Vineflower
Development Team and JetBrains s.r.o., a fork of IntelliJ's Fernflower decompiler, Apache License 2.0), unmodified, in
`server/kotlin-lsp/lib/`. It shows library classes without sources as Java with method bodies.

## Kotlin grammar and language configuration

`syntaxes/kotlin.tmLanguage.json` and `language-configuration.json` come from the same project's
VS Code extension (Apache License 2.0).
