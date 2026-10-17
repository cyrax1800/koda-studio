// Dev helper: node tools/convert-model.ts <model.json> <out/modules.json>
import fs from 'node:fs';
import path from 'node:path';

import type { ProjectModel } from '../src/gradle/modelTypes.ts';
import { toModulesJson } from '../src/server/modulesJson.ts';

const [modelPath, outPath] = process.argv.slice(2);
if (!modelPath || !outPath) {
  console.error('usage: node tools/convert-model.ts <model.json> <out/modules.json>');
  process.exit(2);
}
const model = JSON.parse(fs.readFileSync(modelPath, 'utf8')) as ProjectModel;
const { modules, warnings } = toModulesJson(model, { librarySources: 'cached' });
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, JSON.stringify(modules, null, 2));
for (const warning of warnings) console.warn(`warning: ${warning}`);
console.log(`modules=${modules.length} (${modules.filter((m) => m.isSource).length} source) -> ${outPath}`);
