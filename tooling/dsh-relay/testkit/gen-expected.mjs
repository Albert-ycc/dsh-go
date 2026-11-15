// 重新生成 contract/expected/*.observe.json（夹具换了以后跑一次，人工审 diff 后提交）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { convertDump } from '../lib/observe.mjs';
import { formatGolden } from './format.mjs';
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const tm = JSON.parse(fs.readFileSync(path.join(root, 'config', 'typemap.json'), 'utf8'));
for (const f of fs.readdirSync(path.join(root, 'contract', 'fixtures')).filter((n) => n.endsWith('.json'))) {
  const out = convertDump(JSON.parse(fs.readFileSync(path.join(root, 'contract', 'fixtures', f), 'utf8')), tm);
  fs.writeFileSync(path.join(root, 'contract', 'expected', f.replace('.json', '.observe.json')), formatGolden(out));
  console.log(f, 'nodes', out.nodes);
}
