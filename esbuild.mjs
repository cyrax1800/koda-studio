import * as esbuild from 'esbuild';

const production = process.argv.includes('--production');
const watch = process.argv.includes('--watch');

const context = await esbuild.context({
  entryPoints: ['src/extension.ts'],
  bundle: true,
  format: 'cjs',
  platform: 'node',
  target: 'node20',
  outfile: 'dist/extension.cjs',
  external: ['vscode'],
  sourcemap: !production,
  minify: production,
  logLevel: 'info',
});

// Starts the server for Zed (zed/), which can't run a VS Code extension.
const zedLauncher = await esbuild.context({
  entryPoints: ['src/zed/launcher.ts'],
  bundle: true,
  format: 'cjs',
  platform: 'node',
  target: 'node20',
  outfile: 'dist/zed-launcher.cjs',
  sourcemap: !production,
  minify: production,
  logLevel: 'info',
});

for (const ctx of [context, zedLauncher]) {
  if (watch) {
    await ctx.watch();
  } else {
    await ctx.rebuild();
    await ctx.dispose();
  }
}
