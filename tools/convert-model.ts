// Dev helper: node tools/convert-model.ts <model.json> <out/workspace.json>
import fs from 'node:fs';
import path from 'node:path';

import type { ProjectModel } from '../src/gradle/modelTypes.ts';
import { toWorkspaceJson } from '../src/intellij/workspaceJson.ts';

const [modelPath, outPath] = process.argv.slice(2);
if (!modelPath || !outPath) {
  console.error('usage: node tools/convert-model.ts <model.json> <out/workspace.json>');
  process.exit(2);
}
const model = JSON.parse(fs.readFileSync(modelPath, 'utf8')) as ProjectModel;
const { workspace, warnings } = toWorkspaceJson(model, { librarySources: 'cached' });
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(workspace, null, 2));
for (const warning of warnings) console.warn(`warning: ${warning}`);
console.log(`modules=${workspace.modules.length} libraries=${workspace.libraries.length} -> ${outPath}`);
