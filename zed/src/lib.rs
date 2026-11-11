//! Koda Studio for Zed: runs Koda's Kotlin language server for Android Gradle projects.
//!
//! The server runs through `koda-lsp` (a wrapper around `dist/zed-launcher.cjs`, installed to
//! `~/.koda-studio/bin` by `npm run install:zed`), which syncs a never-synced project with Gradle and then starts
//! `server/kotlin-lsp`. Override it with `lsp.koda.binary` in Zed's settings.
use zed_extension_api::{self as zed, serde_json, settings::LspSettings, LanguageServerId, Result, Worktree};

const SERVER_ID: &str = "koda";

struct KodaExtension;

impl KodaExtension {
    /// The launcher: `lsp.koda.binary.path`, else `koda-lsp` on the PATH, else `~/.koda-studio/bin/koda-lsp`.
    fn launcher(worktree: &Worktree, env: &[(String, String)]) -> String {
        if let Some(path) = worktree.which("koda-lsp") {
            return path;
        }
        let home = env.iter().find(|(k, _)| k == "HOME").map(|(_, v)| v.as_str()).unwrap_or("~");
        format!("{home}/.koda-studio/bin/koda-lsp")
    }
}

impl zed::Extension for KodaExtension {
    fn new() -> Self {
        KodaExtension
    }

    fn language_server_command(&mut self, _id: &LanguageServerId, worktree: &Worktree) -> Result<zed::Command> {
        let mut env = worktree.shell_env();
        let binary = LspSettings::for_worktree(SERVER_ID, worktree).ok().and_then(|s| s.binary);
        if let Some(extra) = binary.as_ref().and_then(|b| b.env.clone()) {
            env.extend(extra);
        }
        let command = binary.as_ref().and_then(|b| b.path.clone()).unwrap_or_else(|| Self::launcher(worktree, &env));
        let mut args = binary.and_then(|b| b.arguments).unwrap_or_default();
        args.extend(["--root".to_string(), worktree.root_path()]);
        Ok(zed::Command { command, args, env })
    }

    fn language_server_initialization_options(
        &mut self,
        _id: &LanguageServerId,
        worktree: &Worktree,
    ) -> Result<Option<serde_json::Value>> {
        let data = format!("{}/.gradle/koda", worktree.root_path());
        let mut options = serde_json::json!({
            "modulesFile": format!("{data}/modules.json"),
            // Its own index: VS Code's lives in VS Code's storage and is locked while VS Code runs the server.
            "indexDir": format!("{data}/zed-index"),
            // Zed reports changed files only to watchers a server registers.
            "watchFiles": true,
        });
        let user = LspSettings::for_worktree(SERVER_ID, worktree).ok().and_then(|s| s.initialization_options);
        if let (Some(serde_json::Value::Object(user)), Some(target)) = (user, options.as_object_mut()) {
            target.extend(user);
        }
        Ok(Some(options))
    }
}

zed::register_extension!(KodaExtension);
