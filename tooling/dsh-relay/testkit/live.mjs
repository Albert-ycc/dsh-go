// 真机验收辅助：对临时实例配对（令牌只落文件，不打印），再按路径调用并打印耗时。
// node testkit/live.mjs pair <code>
// node testkit/live.mjs call <METHOD> <path> [json-body]   （环境变量 LIVE_PORT、LIVE_TOKEN_FILE）
import fs from 'node:fs';
import { pairWith, request } from './client.mjs';
const port = Number(process.env.LIVE_PORT ?? 38731);
const tokenFile = process.env.LIVE_TOKEN_FILE ?? '/private/tmp/claude-501/relay-live/token';
const [cmd, a, b, c] = process.argv.slice(2);
if (cmd === 'pair') {
  const r = await pairWith({ port, code: a, name: 'live' });
  if (r.status !== 200) { console.log('pair failed', r.status, r.text); process.exit(1); }
  fs.writeFileSync(tokenFile, r.json.token, { mode: 0o600 });
  console.log('paired, token saved to file');
} else {
  const token = fs.readFileSync(tokenFile, 'utf8').trim();
  const t = Date.now();
  const r = await request({ port, method: a, path: b, body: c ? JSON.parse(c) : undefined, token, headers: { 'x-request-id': `live-${Date.now()}` } });
  const ms = Date.now() - t;
  let j = r.json;
  if (j?.image?.b64) j = { ...j, image: { ...j.image, b64: `<${j.image.b64.length} chars>` } };
  const short = JSON.stringify(j);
  console.log(`HTTP ${r.status} ${ms} ms bytes=${r.text.length}`);
  console.log(short.length > 1500 ? short.slice(0, 1500) + '…' : short);
}
