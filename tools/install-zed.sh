#!/bin/sh
# Builds the Zed launcher and installs ~/.koda-studio/bin/koda-lsp, which the Zed extension (zed/) runs.
# Then in Zed: "zed: install dev extension" and pick the zed/ folder of this repository.
set -e
repo=$(cd "$(dirname "$0")/.." && pwd)
node "$repo/esbuild.mjs"
[ -x "$repo/server/kotlin-lsp/bin/kotlin-lsp" ] || (cd "$repo" && npm run build:lsp)
node=$(command -v node)
mkdir -p "$HOME/.koda-studio/bin"
cat > "$HOME/.koda-studio/bin/koda-lsp" <<WRAPPER
#!/bin/sh
exec "$node" "$repo/dist/zed-launcher.cjs" "\$@"
WRAPPER
chmod +x "$HOME/.koda-studio/bin/koda-lsp"
echo "Installed $HOME/.koda-studio/bin/koda-lsp -> $repo/dist/zed-launcher.cjs"
