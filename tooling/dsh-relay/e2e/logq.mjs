#!/usr/bin/env node
// 查 relay.log：node logq.mjs <log> <fromLine> [key=value | has:key | no:key]...
// 打印 fromLine 之后（不含）满足全部条件的行，原样输出（每行一个 JSON）。
import fs from 'node:fs';
const [file, from, ...conds] = process.argv.slice(2);
let text = '';
try { text = fs.readFileSync(file, 'utf8'); } catch { process.exit(0); }
const lines = text.split('\n').filter(Boolean).slice(Number(from) || 0);
for (const l of lines) {
  let j;
  try { j = JSON.parse(l); } catch { continue; }
  const ok = conds.every((c) => {
    if (c.startsWith('has:')) return j[c.slice(4)] !== undefined;
    if (c.startsWith('no:')) return j[c.slice(3)] === undefined;
    const i = c.indexOf('=');
    return String(j[c.slice(0, i)]) === c.slice(i + 1);
  });
  if (ok) console.log(l);
}
