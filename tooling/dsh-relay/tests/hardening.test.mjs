// 手机端审查位与 QA 对抗式审查（R1–R15）对应的回归用例。
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setup, waitFor, sleep, FIXTURES } from './helpers.mjs';
import { createRelay } from '../lib/relay.mjs';
import { StartupError } from '../lib/errors.mjs';
import { parseIPv4 } from '../lib/net.mjs';
import { Device } from '../lib/device.mjs';

const KIT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'testkit');
const OBS = { method: 'POST', path: '/v1/observe', body: {} };
const click = (x, y) => ({ op: 'click', x, y });
const listCalls = (api) => api.calls().filter((c) => c.ev === 'start' && c.args[0] === 'list');

/** 可前进的时钟：真实时间加偏移，既让超时照常走，又能跳过 10/15 秒 */
function skewClock() {
  const c = { skew: 0, now: () => Date.now() + c.skew };
  return c;
}

/** 只建中继（不起监听、不用假设备），用来测启动期校验。 */
function build(t, prep, opts = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-relay-boot-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const home = path.join(root, 'home');
  fs.mkdirSync(home, { recursive: true });
  prep?.(home, root);
  return {
    root,
    home,
    make: () => createRelay({ home, logDir: path.join(root, 'log'), hdcBin: path.join(KIT, 'fake-hdc.mjs'), port: 0, ctlPort: 0, loopbackOnly: true, keepalive: false, rebindMs: 0, probeHdc: false, ...opts }),
  };
}

// ---- 手机端语义：/v1/device 自愈、locked 时效、display 稳定 ----

test('F1 冷启动后 /v1/device 在非 ok 时后台连设备，几秒内自愈，且不入队列深度', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'echo ok', sleepMs: 500 }] });
  const first = await api.req({ method: 'GET', path: '/v1/device' });
  assert.equal(first.json.device.state, 'offline'); // 响应不等连接
  await sleep(150);
  const mid = await api.req({ method: 'GET', path: '/v1/device' });
  assert.equal(mid.json.queue.depth, 0, '后台探活不占队列深度');
  assert.ok(api.relay.hdc.running?.meta.soft, '探活是 soft 作业');
  await waitFor(() => api.relay.device.state === 'ok', 5000);
  const later = await api.req({ method: 'GET', path: '/v1/device' });
  assert.equal(later.json.device.state, 'ok');
  assert.equal(later.json.device.via, 'usb');
});

test('F1 探活限频 10 秒一次；暂停时不探；observe 的真实请求不受 soft 作业影响', async (t) => {
  const clock = skewClock();
  const api = await setup(t, { targets: '', relayOpts: { now: clock.now } });
  for (let i = 0; i < 5; i++) await api.req({ method: 'GET', path: '/v1/device' });
  await sleep(500);
  assert.equal(listCalls(api).length, 1, '10 秒内只连一次');
  clock.skew += 11_000;
  await api.req({ method: 'GET', path: '/v1/device' });
  await sleep(500);
  assert.equal(listCalls(api).length, 2, '过了 10 秒可以再连');
  await api.req({ method: 'POST', path: '/v1/halt', body: {} });
  clock.skew += 11_000;
  await api.req({ method: 'GET', path: '/v1/device' });
  await sleep(300);
  assert.equal(listCalls(api).length, 2, '暂停期间不碰设备');
});

test('F1 服务启动时有绑定目标就主动连一次；没有绑定则不碰设备', async (t) => {
  const bound = await setup(t, { relayOpts: { config: { device: { serial: 'FAKE-USB-0001' } } } });
  await waitFor(() => bound.relay.device.state === 'ok' && !bound.relay.hdc.running, 5000);
  const unbound = await setup(t);
  await sleep(400);
  assert.equal(unbound.calls().length, 0);
  assert.equal(unbound.relay.device.state, 'offline');
});

test('F2 locked 只在最近 15 秒内读过屏时返回 true/false，否则 null', async (t) => {
  const clock = skewClock();
  const api = await setup(t, { dump: '05-lockscreen.json', relayOpts: { now: clock.now } });
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.locked, null, '没读过屏是 null');
  assert.equal((await api.req(OBS)).json.device.locked, true);
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.locked, true);
  clock.skew += 14_000;
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.locked, true);
  clock.skew += 2_000;
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.locked, null, '16 秒后过期');
  fs.copyFileSync(path.join(FIXTURES, '01-settings-home.json'), path.join(api.fake, 'dump.json'));
  assert.equal((await api.req(OBS)).json.device.locked, false);
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.locked, false);
});

test('F2 launch 回显锁屏 10106102 也刷新 locked', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'aa start', out: 'error: 10106102\n' }] });
  const r = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'a.b', ability: 'A' } });
  assert.equal(r.status, 409);
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.locked, true);
});

test('F4 display：键盘/半屏窗口不让它跳，按维度取最大，截图为准并更新缓存', async (t) => {
  const api = await setup(t);
  const full = JSON.parse(fs.readFileSync(path.join(FIXTURES, 'synthetic-settings.json'), 'utf8'));
  const withWindowBounds = (b) => {
    const d = structuredClone(full);
    d.children = [{ ...d.children[0], attributes: { ...d.children[0].attributes, bounds: b } }];
    return d;
  };
  const dump = (d) => fs.writeFileSync(path.join(api.fake, 'dump.json'), JSON.stringify(d));
  const disp = async (body = {}) => (await api.req({ ...OBS, body })).json.display;
  assert.deepEqual(await disp(), { id: 0, width: 1320, height: 2120 });
  dump(withWindowBounds('[0,0][1320,1200]')); // 半屏窗口
  assert.deepEqual(await disp(), { id: 0, width: 1320, height: 2120 });
  dump(withWindowBounds('[0,0][1500,1000]')); // 一个维度更大
  assert.deepEqual(await disp(), { id: 0, width: 1500, height: 2120 });
  assert.deepEqual(await disp({ bundle: 'com.example.app' }), { id: 0, width: 1500, height: 2120 }, 'bundle 读屏用缓存，不参与合并');
  dump(full);
  assert.deepEqual(await disp({ image: true }), { id: 0, width: 1320, height: 2120 }, '截图尺寸为准');
  dump(withWindowBounds('[0,0][600,500]'));
  assert.deepEqual(await disp(), { id: 0, width: 1320, height: 2120 });
});

// ---- R1 日志与 rejection ----

test('R1 日志写不进去：请求照常完成，stderr 只报一次，服务不退出', async (t) => {
  const api = await setup(t);
  await api.req(OBS);
  fs.chmodSync(api.relay.log.file, 0o444);
  const seen = [];
  const orig = console.error;
  console.error = (...a) => seen.push(a.join(' '));
  try {
    assert.equal((await api.req(OBS)).status, 200);
    assert.equal((await api.act(click(5, 5))).status, 200);
    assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).status, 200);
  } finally {
    console.error = orig;
    fs.chmodSync(api.relay.log.file, 0o644);
  }
  assert.equal(seen.filter((l) => l.includes('写日志失败')).length, 1);
});

// ---- R2 超限 body ----

test('R2 body 超限返回 422 bad_args 再断连；恰好等于限额照常处理', async (t) => {
  const api = await setup(t);
  const over = await api.req({ method: 'POST', path: '/v1/stop', body: { a: 'x'.repeat(65529) } });
  assert.equal(over.status, 422);
  assert.match(over.json.error.message, /too large/);
  const big = await api.req({ method: 'POST', path: '/v1/stop', body: { a: 'x'.repeat(5 * 1024 * 1024) } });
  assert.equal(big.status, 422);
  const exact = await api.req({ method: 'POST', path: '/v1/stop', body: { a: 'x'.repeat(65528) } });
  assert.equal(exact.status, 422);
  assert.match(exact.json.error.message, /task required/, '限额内进入业务校验');
  assert.equal((await api.act(click(1, 1))).status, 200, '之后的请求正常');
});

// ---- R3 observe 重试预算 ----

test('R3 dumpLayout 挂起：504 且总耗时压在 14 秒内（手机端读超时 18 秒），队列随后可用', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'uitest dumpLayout', hang: true }] });
  const t0 = Date.now();
  const r = await api.req(OBS);
  const took = Date.now() - t0;
  assert.equal(r.status, 504);
  assert.equal(r.json.error.code, 'device_timeout');
  assert.ok(took < 14_000, `observe took ${took}ms`);
  api.setBehaviors([]);
  assert.equal((await api.req(OBS)).status, 200);
});

// ---- R6 绑定失败 ----

test('R6 HTTPS 端口一个都绑不上：start 抛 StartupError(exit 1)', async (t) => {
  const hog = net.createServer();
  await new Promise((r) => hog.listen(0, '127.0.0.1', r));
  t.after(() => hog.close());
  const b = build(t, null, { port: hog.address().port });
  const relay = b.make();
  const orig = console.error;
  console.error = () => {};
  try {
    await assert.rejects(relay.start(), (e) => e instanceof StartupError && e.exitCode === 1 && /EADDRINUSE/.test(e.message));
  } finally {
    console.error = orig;
  }
});

test('R6 管理口被占：服务照常起，释放后下一轮重绑自动补上', async (t) => {
  const hog = net.createServer();
  await new Promise((r) => hog.listen(0, '127.0.0.1', r));
  const b = build(t, null, { ctlPort: hog.address().port, rebindMs: 100 });
  const relay = b.make();
  const orig = console.error;
  console.error = () => {};
  try {
    await relay.start();
    t.after(() => relay.stop());
    assert.equal(relay.ctl(), undefined);
    await new Promise((r) => hog.close(r));
    await waitFor(() => relay.ctl(), 3000);
  } finally {
    console.error = orig;
  }
});

// ---- R7 启动期校验 ----

test('R7 配置文件损坏或形状不对：StartupError(78) 带文件名；不静默回退', async (t) => {
  const cases = [
    ['config.json', '{not json'], ['config.json', '{"hostNames":5}'], ['config.json', '[]'], ['config.json', '{"device":"x"}'],
    ['typemap.json', '{}'], ['typemap.json', '{oops'], ['keymap.json', '{}'], ['gestures.json', '{"recents":1}'], ['aliases.json', '[]'], ['aliases.json', '{"a":5}'],
  ];
  for (const [file, text] of cases) {
    const b = build(t, (home) => fs.writeFileSync(path.join(home, file), text));
    assert.throws(() => b.make(), (e) => e instanceof StartupError && e.exitCode === 78 && e.message.includes(file), `${file} ${text}`);
  }
});

test('R7 tokens.json 损坏：备份成 .bad、告警、不静默清空；再次损坏不覆盖旧备份', async (t) => {
  const warns = [];
  const orig = console.error;
  console.error = (...a) => warns.push(a.join(' '));
  try {
    const b = build(t, (home) => fs.writeFileSync(path.join(home, 'tokens.json'), '{{{'));
    const relay = b.make();
    assert.equal(relay.auth.list().length, 0);
    assert.equal(fs.readFileSync(path.join(b.home, 'tokens.json.bad'), 'utf8'), '{{{');
    assert.ok(warns.some((w) => w.includes('tokens.json 损坏')));
    assert.ok(fs.readFileSync(relay.log.file, 'utf8').includes('"op":"warn"'));
    fs.writeFileSync(path.join(b.home, 'tokens.json'), '{"not":"array"}');
    b.make();
    assert.equal(fs.readFileSync(path.join(b.home, 'tokens.json.bad'), 'utf8'), '{{{', '旧备份不被覆盖');
    assert.equal(fs.readdirSync(b.home).filter((f) => f.startsWith('tokens.json.bad')).length, 2);
  } finally {
    console.error = orig;
  }
});

test('R7 端口值非法：StartupError', async (t) => {
  for (const port of [-1, 70000, 1.5, NaN]) {
    const b = build(t, null, { port });
    assert.throws(() => b.make(), (e) => e instanceof StartupError, String(port));
  }
  const b = build(t, null, { port: 65535, ctlPort: undefined });
  assert.throws(() => b.make(), (e) => e instanceof StartupError && /65534/.test(e.message));
});

// ---- R8 证书 ----

test('R8 首次生成证书写 cert_regenerated；私钥权限被收紧；证书损坏启动失败', async (t) => {
  const b = build(t);
  const r1 = b.make();
  const lines = () => fs.readFileSync(r1.log.file, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
  assert.ok(lines().some((l) => l.op === 'cert_regenerated'));
  const key = path.join(b.home, 'tls', 'key.pem');
  fs.chmodSync(key, 0o644);
  const r2 = b.make();
  assert.equal(fs.statSync(key).mode & 0o777, 0o600);
  assert.ok(lines().some((l) => l.op === 'key_mode_fixed'));
  assert.equal(lines().filter((l) => l.op === 'cert_regenerated').length, 1, '证书没变就不再记');
  assert.equal(r1.tls.fingerprint, r2.tls.fingerprint);
  fs.writeFileSync(path.join(b.home, 'tls', 'cert.pem'), 'garbage');
  assert.throws(() => b.make(), (e) => e instanceof StartupError && e.exitCode === 78);
});

// ---- R9 / R10 / R11 设备输出 ----

test('R9 hdc 输出超 8 MB：杀掉并返回 424 command_failed，服务照常', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'uitest dumpLayout', bigMB: 9 }] });
  const r = await api.req(OBS);
  assert.equal(r.status, 424);
  assert.equal(r.json.error.code, 'command_failed');
  assert.match(r.json.error.message, /8 MB/);
  api.setBehaviors([]);
  assert.equal((await api.req(OBS)).status, 200);
});

test('R10 dump 文件半截、空、null、数组：424 command_failed，不是 500', async (t) => {
  const api = await setup(t);
  for (const text of ['{"attributes":{"bounds":"[0,0][1,1]"},"children":[{"attri', '', 'null', '[]', '"str"', '42']) {
    fs.writeFileSync(path.join(api.fake, 'dump.json'), text);
    const r = await api.req(OBS);
    assert.equal(r.status, 424, JSON.stringify(text));
    assert.equal(r.json.error.code, 'command_failed');
  }
  fs.copyFileSync(path.join(FIXTURES, 'synthetic-settings.json'), path.join(api.fake, 'dump.json'));
  assert.equal((await api.req(OBS)).status, 200);
  assert.ok(!api.logLines().some((l) => l.op === 'internal_error'));
});

test('R11 hdc 不存在：专用 hint 指向 HDC_BIN；启动探测写 hdc_probe 日志', async (t) => {
  const api = await setup(t, { relayOpts: { hdcBin: '/nonexistent/hdc', probeHdc: true } });
  const r = await api.req(OBS);
  assert.equal(r.status, 500);
  assert.equal(r.json.error.code, 'internal');
  assert.match(r.json.error.hint, /HDC_BIN/);
  await waitFor(() => api.logLines().find((l) => l.op === 'hdc_probe'));
  assert.equal(api.logLines().find((l) => l.op === 'hdc_probe').ok, false);
  const good = await setup(t, { relayOpts: { probeHdc: true } });
  const line = await waitFor(() => good.logLines().find((l) => l.op === 'hdc_probe'));
  assert.equal(line.ok, true);
  assert.match(line.ver, /3\.1/);
});

// ---- R12–R15 ----

test('R12 revoke 的 id 必须恰为 6 位 hex 或 all', async (t) => {
  const api = await setup(t);
  for (const id of ['', 'a', 'abcde', 'abcdef0', 'ABCDEF', 'zzzzzz']) {
    const r = await api.ctl('POST', '/ctl/revoke', { id });
    assert.equal(r.status, 422, JSON.stringify(id));
  }
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).status, 200, '令牌没被误撤');
  assert.equal((await api.ctl('POST', '/ctl/revoke', { id: 'abcdef' })).json.revoked, 0);
  const own = api.relay.auth.list()[0].id;
  assert.equal((await api.ctl('POST', '/ctl/revoke', { id: own })).json.revoked, 1);
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).status, 401);
});

test('R13 X-Request-Id 只能重放同一个请求：别的 body/路径借用得 422', async (t) => {
  const api = await setup(t);
  const h = { 'x-request-id': 'idem-aaaaaaaa-1' };
  const a = await api.act(click(5, 5), h);
  assert.equal(a.status, 200);
  const same = await api.act(click(5, 5), h);
  assert.deepEqual(same.json, a.json);
  assert.equal(api.uitest().length, 1, '同一请求重放不重做');
  const other = await api.act(click(9, 9), h);
  assert.equal(other.status, 422);
  assert.match(other.json.error.message, /different request/);
  const cross = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'a.b', ability: 'A' }, headers: h });
  assert.equal(cross.status, 422);
  assert.equal(api.uitest().length, 1);
});

test('R14 IPv4 禁前导零；target 拒绝 010.0.0.1', async (t) => {
  assert.equal(parseIPv4('010.0.0.1'), null);
  assert.equal(parseIPv4('192.168.01.5'), null);
  assert.equal(parseIPv4('192.168.1.5'), (192 << 24 | 168 << 16 | 1 << 8 | 5) >>> 0);
  assert.equal(parseIPv4('0.0.0.0'), 0);
  const api = await setup(t);
  assert.equal((await api.req({ method: 'POST', path: '/v1/target', body: { addr: '010.0.0.1:5555' } })).status, 422);
  assert.equal((await api.req({ method: 'POST', path: '/v1/target', body: { addr: '192.168.1.5:5555' } })).status, 200);
});

test('R15 X-Dsh-Task 头长度与字符校验', async (t) => {
  const api = await setup(t);
  for (const v of ['x'.repeat(65), 'bad task!', 'a/b']) {
    const r = await api.act({ op: 'key', name: 'back' }, { 'x-dsh-task': v });
    assert.equal(r.status, 422, v);
  }
  assert.equal((await api.act({ op: 'key', name: 'back' }, { 'x-dsh-task': '12345' })).status, 200);
  assert.equal((await api.act({ op: 'key', name: 'back' }, { 'x-dsh-task': 'task-1:a.b_c' })).status, 200);
});

// ---- binding.json 损坏不等于解绑（M2） ----

test('M2 binding.json 损坏：改名 .bad 并告警；显式 target 之前不自动绑任何候选，/v1/device 带 hint；target auto 后恢复', async (t) => {
  const warns = [];
  const orig = console.error;
  console.error = (...a) => warns.push(a.join(' '));
  let api;
  try {
    api = await setup(t, { prep: ({ home }) => { fs.mkdirSync(home, { recursive: true }); fs.writeFileSync(path.join(home, 'binding.json'), 'zzz{'); } });
  } finally {
    console.error = orig;
  }
  const home = api.relay.home;
  assert.equal(fs.readFileSync(path.join(home, 'binding.json.bad'), 'utf8'), 'zzz{');
  assert.ok(!fs.existsSync(path.join(home, 'binding.json')));
  assert.ok(warns.some((w) => w.includes('binding.json 损坏')));
  assert.ok(api.logLines().some((l) => l.op === 'warn' && l.msg.includes('binding.json')));
  // USB 手机就在线，也不能自动绑
  const o = await api.req(OBS);
  assert.equal(o.status, 503);
  assert.equal(o.json.error.code, 'device_offline');
  assert.match(o.json.error.hint, /dsh-relay target/);
  assert.equal(api.calls().filter((c) => c.args.includes('shell')).length, 0, '没有对任何设备发命令');
  const d = (await api.req({ method: 'GET', path: '/v1/device' })).json.device;
  assert.equal(d.state, 'offline');
  assert.match(d.hint, /绑定文件损坏.*dsh-relay target/);
  assert.ok(!fs.existsSync(path.join(home, 'binding.json')), '没有被自动写出新绑定');
  // 用户显式 target（auto）后清掉状态，恢复首次绑定行为
  assert.equal((await api.ctl('POST', '/ctl/target', { addr: null })).status, 200);
  assert.equal((await api.req(OBS)).status, 200);
  assert.equal(JSON.parse(fs.readFileSync(path.join(home, 'binding.json'), 'utf8')).serial, 'FAKE-USB-0001');
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.hint, undefined);
});

test('M2 重启后只有 .bad、没有 binding.json 仍不自动绑；显式 target set 也清掉；形状错的 JSON 同样按损坏处理', async (t) => {
  const mk = (files) => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-bind-'));
    t.after(() => fs.rmSync(home, { recursive: true, force: true }));
    for (const [f, text] of Object.entries(files)) fs.writeFileSync(path.join(home, f), text);
    return home;
  };
  const quiet = (fn) => { const o = console.error; console.error = () => {}; try { return fn(); } finally { console.error = o; } };
  const homeA = mk({ 'binding.json.bad': 'old' });
  const a = quiet(() => new Device({ home: homeA, config: {} }));
  assert.equal(a.bindingCorrupt, true);
  assert.equal(a.status().hint, '绑定文件损坏，请用 dsh-relay target 重新绑定');
  a.setTcpAddr('192.168.1.5:5555');
  assert.equal(a.bindingCorrupt, false);
  assert.equal(a.status().hint, undefined);
  const again = new Device({ home: homeA, config: {} }); // 有了新的 binding.json，.bad 不再阻塞
  assert.equal(again.bindingCorrupt, false);
  assert.equal(again.bound.addr, '192.168.1.5:5555');
  const homeB = mk({ 'binding.json': '[1,2]' });
  const warns = [];
  const b = new Device({ home: homeB, config: {}, warn: (m) => warns.push(m) });
  assert.equal(b.bindingCorrupt, true);
  assert.equal(warns.length, 1);
  assert.ok(fs.existsSync(path.join(homeB, 'binding.json.bad')));
  const homeC = mk({ 'binding.json': '{"serial":5}' });
  assert.equal(new Device({ home: homeC, config: {}, warn() {} }).bindingCorrupt, true);
  // 配置里写死了 serial 等于有绑定，不受影响
  const homeD = mk({ 'binding.json.bad': 'old' });
  assert.equal(new Device({ home: homeD, config: { device: { serial: 'X' } } }).status().hint, undefined);
});
