import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRelay } from '../lib/relay.mjs';
import { pairWith, request } from '../testkit/client.mjs';

const KIT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'testkit');
export const FIXTURES = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'contract', 'fixtures');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 起一个指向假 hdc 的中继实例，只绑回环，端口随机，所有状态在临时目录。 */
export async function setup(t, { relayOpts = {}, targets, behaviors = [], dump = 'synthetic-settings.json', pair = true, prep } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-relay-test-'));
  const fake = path.join(root, 'fake');
  fs.mkdirSync(path.join(fake, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(fake, 'dev'));
  fs.copyFileSync(path.join(KIT, 'fake-uitest.mjs'), path.join(fake, 'bin', 'uitest'));
  fs.chmodSync(path.join(fake, 'bin', 'uitest'), 0o755);
  fs.writeFileSync(path.join(fake, 'targets.txt'), targets ?? 'FAKE-USB-0001\t\tUSB\tConnected\tlocalhost\n'.replace('\t\t', '\t'));
  fs.copyFileSync(path.join(FIXTURES, dump), path.join(fake, 'dump.json'));
  fs.writeFileSync(path.join(fake, 'behaviors.json'), JSON.stringify(behaviors));
  fs.writeFileSync(path.join(fake, 'bm-n.json'), JSON.stringify({ mainEntry: 'phone', entryModuleName: 'phone', hapModuleInfos: [{ moduleName: 'phone', mainAbility: 'com.example.MainAbility', abilityInfos: [{ name: 'MainAbility' }] }] }));
  process.env.FAKE_HDC_DIR = fake;
  prep?.({ root, fake, home: path.join(root, 'home') });
  const relay = createRelay({
    home: path.join(root, 'home'),
    logDir: path.join(root, 'log'),
    hdcBin: path.join(KIT, 'fake-hdc.mjs'),
    port: 0,
    ctlPort: 0,
    probeHdc: false,
    loopbackOnly: true,
    keepalive: false,
    rebindMs: 0,
    ...relayOpts,
  });
  await relay.start();
  t.after(async () => {
    await relay.stop();
    fs.rmSync(root, { recursive: true, force: true });
  });
  const port = relay.https().port;
  const api = {
    root, fake, relay, port,
    ctlPort: relay.ctl().port,
    setBehaviors: (b) => fs.writeFileSync(path.join(fake, 'behaviors.json'), JSON.stringify(b)),
    calls: () => (fs.existsSync(path.join(fake, 'calls.log')) ? fs.readFileSync(path.join(fake, 'calls.log'), 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []),
    uitest: () => (fs.existsSync(path.join(fake, 'uitest.log')) ? fs.readFileSync(path.join(fake, 'uitest.log'), 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []),
    logLines: () => fs.readFileSync(relay.log.file, 'utf8').trim().split('\n').map((l) => JSON.parse(l)),
    logText: () => fs.readFileSync(relay.log.file, 'utf8'),
    req: (o) => request({ port, token: api.token, ...o }),
    ctl: (method, p, body, token) =>
      new Promise((resolve, reject) => {
        import('node:http').then(({ default: http }) => {
          const data = body ? JSON.stringify(body) : null;
          const r = http.request({ host: '127.0.0.1', port: api.ctlPort, path: p, method, headers: { authorization: `Bearer ${token ?? relay.auth.controlToken}`, 'content-type': 'application/json', ...(data ? { 'content-length': Buffer.byteLength(data) } : {}) } }, (res) => {
            const c = [];
            res.on('data', (d) => c.push(d));
            res.on('end', () => { const text = Buffer.concat(c).toString(); resolve({ status: res.statusCode, json: JSON.parse(text) }); });
          });
          r.on('error', reject);
          if (data) r.write(data);
          r.end();
        });
      }),
    act: (body, headers = {}) => api.req({ method: 'POST', path: '/v1/act', body, headers }),
  };
  if (pair) {
    const code = relay.auth.startPairing().code;
    const r = await pairWith({ port, code });
    api.token = r.json.token;
  }
  return api;
}

export function alive(pid) {
  try { process.kill(pid, 0); return true; } catch { return false; }
}

export async function waitFor(fn, ms = 3000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = fn();
    if (v) return v;
    await sleep(20);
  }
  throw new Error('waitFor timeout');
}
