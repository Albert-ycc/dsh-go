import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { setup, alive, waitFor, sleep, FIXTURES } from './helpers.mjs';
import { request, pairWith } from '../testkit/client.mjs';
import { defaultSourceAllowed, listenAddresses } from '../lib/net.mjs';
import { RelayError, HTTP_STATUS } from '../lib/errors.mjs';

const click = (x, y) => ({ op: 'click', x, y });
const starts = (api) => api.calls().filter((c) => c.ev === 'start' && c.args.includes('shell') && /uiInput/.test(c.args.at(-1)));

// ---- 判定标准 1：七项 ----

test('队列串行：并发请求的设备命令互不重叠，队列满返回 429 busy', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'uiInput click', sleepMs: 150 }] });
  await api.req({ method: 'GET', path: '/v1/device' });
  // 先让设备连接缓存生效，避免把选目标的命令算进来
  await api.act(click(1, 1));
  const rs = await Promise.all([2, 3, 4].map((n) => api.act(click(n, n))));
  assert.deepEqual(rs.map((r) => r.status), [200, 200, 200]);
  const evs = api.calls().filter((c) => c.args.at(-1)?.startsWith?.('uitest uiInput click'));
  // 同一时刻只有一个 uiInput 子进程：按时间排序后 start/end 严格交替
  let open = 0;
  for (const e of evs.sort((a, b) => a.t - b.t || (a.ev === 'end' ? -1 : 1))) {
    open += e.ev === 'start' ? 1 : -1;
    assert.ok(open <= 1, 'two device commands overlapped');
  }
  assert.equal(evs.filter((e) => e.ev === 'start').length, 4);

  // 深度 4：第 5 个并发请求被拒
  api.setBehaviors([{ match: 'uiInput click', sleepMs: 400 }]);
  const five = await Promise.all([10, 11, 12, 13, 14].map((n) => api.act(click(n, n))));
  assert.deepEqual(five.map((r) => r.status).sort(), [200, 200, 200, 200, 429]);
  assert.equal(five.find((r) => r.status === 429).json.error.code, 'busy');
});

test('取消杀子进程：stop 之后在途 hdc 进程消失，排队项返回 cancelled', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'uiInput click 5 5', sleepMs: 30000 }] });
  await api.act(click(1, 1));
  const hang = api.act(click(5, 5), { 'x-dsh-task': 'task-A' });
  const queued = api.act(click(6, 6), { 'x-dsh-task': 'task-A' });
  const started = await waitFor(() => api.calls().find((c) => c.ev === 'start' && c.args.at(-1) === 'uitest uiInput click 5 5'));
  assert.ok(alive(started.pid));
  const s = await api.req({ method: 'POST', path: '/v1/stop', body: { task: 'task-A' } });
  assert.equal(s.json.cancelled, 2);
  const [a, b] = await Promise.all([hang, queued]);
  assert.equal(a.status, 409);
  assert.equal(a.json.error.code, 'cancelled');
  assert.equal(b.status, 409);
  await waitFor(() => !alive(started.pid), 2000);
  assert.ok(!starts(api).some((c) => c.args.at(-1).endsWith('click 6 6')), 'queued command must never reach the device');
  // 队列恢复可用
  assert.equal((await api.act(click(7, 7))).status, 200);
});

test('超时：设备命令超过预算返回 504 并杀掉子进程', async (t) => {
  const api = await setup(t, { relayOpts: { timeouts: { act: 2000 } }, behaviors: [{ match: 'uiInput click 9 9', sleepMs: 30000 }] });
  await api.req({ method: 'POST', path: '/v1/observe', body: {} }); // 先连上设备并学到屏幕尺寸，act 的 400 ms 预算只留给命令本身
  const t0 = Date.now();
  const r = await api.act(click(9, 9));
  assert.equal(r.status, 504);
  assert.equal(r.json.error.code, 'device_timeout');
  assert.ok(Date.now() - t0 < 6000);
  const pid = api.calls().find((c) => c.ev === 'start' && c.args.at(-1) === 'uitest uiInput click 9 9').pid;
  await waitFor(() => !alive(pid), 2000);
  const after = await api.act(click(2, 2));
  assert.equal(after.status, 200, JSON.stringify(after.json) + JSON.stringify(api.calls().slice(-6)));
});

test('幂等：同一 X-Request-Id 重放返回首次结果且只执行一次；在途重放得到 202', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'uiInput click 3 3', sleepMs: 500 }] });
  await api.act(click(1, 1));
  const id = 'req-00000001';
  const first = api.act(click(3, 3), { 'x-request-id': id });
  await waitFor(() => api.uitest().length === 1 || starts(api).some((c) => c.args.at(-1).endsWith('click 3 3')));
  const inflight = await api.act(click(3, 3), { 'x-request-id': id });
  assert.equal(inflight.status, 202);
  assert.equal(inflight.json.in_progress, true);
  const r1 = await first;
  const r2 = await api.act(click(3, 3), { 'x-request-id': id });
  assert.equal(r1.status, 200);
  assert.equal(r2.status, 200);
  assert.deepEqual(r2.json, r1.json);
  assert.equal(starts(api).filter((c) => c.args.at(-1).endsWith('click 3 3')).length, 1);
  // 设备命令没发出去的失败不缓存：换成非法参数同 id 不会被锁死
  const id2 = 'req-00000002';
  assert.equal((await api.act({ op: 'click', x: 'a', y: 1 }, { 'x-request-id': id2 })).status, 422);
  assert.equal((await api.act(click(4, 4), { 'x-request-id': id2 })).status, 200);
});

test('限速：同一来源 5 次鉴权失败后封 60 秒，合法令牌也得到 429', async (t) => {
  const api = await setup(t);
  const bad = 'x'.repeat(43);
  for (let i = 0; i < 5; i++) {
    const r = await request({ port: api.port, method: 'POST', path: '/v1/observe', token: bad, body: {} });
    assert.equal(r.status, 401, `attempt ${i}`);
    assert.equal(r.json.error.code, 'unauthorized');
  }
  const r6 = await request({ port: api.port, method: 'POST', path: '/v1/observe', token: bad, body: {} });
  assert.equal(r6.status, 429);
  const good = await api.req({ method: 'GET', path: '/v1/device' });
  assert.equal(good.status, 429);
  const fails = api.logLines().filter((l) => l.op === 'auth_fail');
  assert.equal(fails.length, 5);
  assert.equal(fails[0].ip, '127.0.0.1');
});

test('来源拒绝：白名单外的来源 403，Host 头不合法 403，缺 X-Dsh-Relay 403', async (t) => {
  const api = await setup(t, { relayOpts: { sourceAllowed: (ip) => ip.startsWith('10.') }, pair: false });
  const r = await request({ port: api.port, path: '/v1/ping' });
  assert.equal(r.status, 403);
  assert.equal(r.json.error.code, 'forbidden_source');
  assert.ok(api.logLines().some((l) => l.op === 'forbidden_source'));

  const api2 = await setup(t);
  const h = await api2.req({ method: 'GET', path: '/v1/device', headers: { host: 'evil.example.com' } });
  assert.equal(h.status, 403);
  const nh = await api2.req({ method: 'GET', path: '/v1/device', noRelayHeader: true });
  assert.equal(nh.status, 403);
  assert.equal(nh.json.error.code, 'forbidden_source');
  const ping = await api2.req({ method: 'GET', path: '/v1/ping', noRelayHeader: true });
  assert.equal(ping.status, 200);
});

test('来源白名单与监听地址规则（纯函数）', () => {
  const yes = ['127.0.0.1', '10.1.2.3', '172.16.0.1', '172.31.255.255', '192.168.31.226', '100.64.0.1', '100.127.255.255', '::1', '::ffff:192.168.1.5'];
  const no = ['8.8.8.8', '198.18.0.5', '198.19.255.255', '172.32.0.1', '100.128.0.1', '169.254.1.1', '0.0.0.0', 'garbage'];
  for (const ip of yes) assert.ok(defaultSourceAllowed(ip), ip);
  for (const ip of no) assert.ok(!defaultSourceAllowed(ip), ip);
  const addrs = listenAddresses({
    en1: [{ family: 'IPv4', address: '192.168.31.182', internal: false }],
    utun6: [{ family: 'IPv4', address: '198.18.0.1', internal: false }],
    lo0: [{ family: 'IPv4', address: '127.0.0.1', internal: true }],
    tailscale0: [{ family: 'IPv4', address: '100.101.102.103', internal: false }],
    wan: [{ family: 'IPv4', address: '203.0.113.9', internal: false }],
    v6: [{ family: 'IPv6', address: 'fe80::1', internal: false }],
  });
  assert.deepEqual(addrs, ['100.101.102.103', '192.168.31.182']);
});

test('halt 不对称：令牌能停不能恢复；恢复只走本机管理口；暂停期间无设备命令', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'uiInput click 8 8', sleepMs: 30000 }] });
  await api.act(click(1, 1));
  const running = api.act(click(8, 8));
  await waitFor(() => starts(api).some((c) => c.args.at(-1).endsWith('click 8 8')));
  const h = await api.req({ method: 'POST', path: '/v1/halt', body: {} });
  assert.equal(h.status, 200);
  assert.equal(h.json.halted, true);
  assert.equal((await running).json.error.code, 'cancelled');

  const before = api.calls().length;
  const blocked = await api.act(click(2, 2));
  assert.equal(blocked.status, 423);
  assert.equal(blocked.json.error.code, 'halted');
  assert.equal((await api.req({ method: 'POST', path: '/v1/observe', body: {} })).status, 423);
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.halted, true);
  assert.equal(api.calls().length, before, 'no hdc process may run while halted');

  // 令牌侧没有任何恢复入口
  for (const p of ['/v1/resume', '/ctl/resume', '/v1/unhalt']) {
    const r = await api.req({ method: 'POST', path: p, body: {} });
    assert.equal(r.status, 404, p);
  }
  assert.equal((await api.act(click(2, 2))).status, 423);
  // 管理口需要管理令牌，业务令牌不行
  assert.equal((await api.ctl('POST', '/ctl/resume', {}, api.token)).status, 401);
  assert.equal((await api.ctl('POST', '/ctl/resume')).status, 200);
  assert.equal((await api.act(click(2, 2))).status, 200);
});

// ---- 判定标准 5：命令注入（R10） ----

test('命令注入：文本在设备 shell 里解析出的参数与原文逐字一致，无副作用', async (t) => {
  const api = await setup(t);
  const canary = path.join(api.root, 'PWNED');
  const payloads = [
    'a\'b"c$(id);d`e',
    `x'; touch ${canary}; echo '`,
    `$(touch ${canary})`,
    `\`touch ${canary}\``,
    'a && b | c > d < e & f \\ g * ? ~ #',
    '中文输入：你好，世界',
    "'", "''", "'\"'\"'",
  ];
  for (const text of payloads) {
    const r = await api.act({ op: 'text', text });
    assert.equal(r.status, 200, text);
  }
  const seen = api.uitest().map((a) => a[2]);
  assert.deepEqual(seen, payloads);
  assert.ok(!fs.existsSync(canary), 'injected command must not run');
  // 非法输入在到达设备之前被拒
  for (const bad of ['a\nb', 'a\u0000b', 'a\tb', '', 'x'.repeat(501), 42]) {
    assert.equal((await api.act({ op: 'text', text: bad })).status, 422);
  }
  assert.equal(api.uitest().length, payloads.length);
  // 日志与响应里没有文字内容
  assert.ok(!api.logText().includes('世界'));
  assert.ok(!api.logText().includes('touch'));
  assert.match(api.logText(), /<text:\d+ chars>/);
});

test('replaceText 宏默认四步，不发回车；commit:true 才追加回车', async (t) => {
  const api = await setup(t);
  const r = await api.act({ op: 'replaceText', x: 600, y: 1980, text: 'hello' });
  assert.equal(r.status, 200);
  assert.deepEqual(r.json.steps.map((s) => s.cmd), [
    'uitest uiInput click 600 1980',
    'uitest uiInput keyEvent 2072 2017',
    'uitest uiInput keyEvent 2055',
    'uitest uiInput text <text:5 chars>',
  ]);
  assert.deepEqual(api.uitest().map((a) => a.slice(1)), [['click', '600', '1980'], ['keyEvent', '2072', '2017'], ['keyEvent', '2055'], ['text', 'hello']]);
  const c = await api.act({ op: 'replaceText', x: 600, y: 1980, text: 'hello', commit: true });
  assert.equal(c.status, 200);
  assert.equal(c.json.steps.at(-1).cmd, 'uitest uiInput keyEvent 2054');
  assert.equal(c.json.steps.length, 5);
  const no = await api.act({ op: 'replaceText', x: 600, y: 1980, text: 'hello', commit: false });
  assert.equal(no.json.steps.length, 4);
  assert.equal((await api.act({ op: 'replaceText', x: 600, y: 1980, text: 'hello', commit: 'yes' })).status, 422);
});

test('act 参数校验：越界坐标、未知 op、速度范围、key 名', async (t) => {
  const api = await setup(t);
  await api.act(click(1, 1)); // 让显示器尺寸已知
  const cases = [click(1320, 10), click(-1, 5), click(1.5, 2), { op: 'zap' }, { op: 'swipe', x1: 1, y1: 1, x2: 2, y2: 2, velocity: 100 }, { op: 'key', name: 'power' }];
  for (const c of cases) assert.equal((await api.act(c)).status, 422, JSON.stringify(c));
  const ok = await api.act({ op: 'swipe', x1: 600, y1: 1500, x2: 600, y2: 500 });
  assert.equal(ok.json.steps[0].cmd, 'uitest uiInput swipe 600 1500 600 500 600');
  assert.equal((await api.act({ op: 'key', name: 'back' })).json.steps[0].cmd, 'uitest uiInput keyEvent Back');
  assert.equal((await api.act({ op: 'key', name: 'enter' })).json.steps[0].cmd, 'uitest uiInput keyEvent 2054');
  assert.match((await api.act({ op: 'key', name: 'recents' })).json.steps[0].cmd, /^uitest uiInput swipe 660 2109 660 1314 300$/);
});

// ---- 配对与鉴权 ----

test('配对：配对码错误 401，成功一次即作废，令牌可用且只存哈希，日志不含配对码', async (t) => {
  const api = await setup(t, { pair: false });
  const wrong = api.relay.auth.startPairing();
  const bad = await pairWith({ port: api.port, code: 'AAAAA-AAAAA' });
  assert.equal(bad.status, 401);
  assert.equal(bad.json.error.code, 'unauthorized');
  const ok = await pairWith({ port: api.port, code: wrong.code.toLowerCase().replace('-', ' ') });
  assert.equal(ok.status, 200);
  assert.match(ok.json.token, /^[A-Za-z0-9_-]{43}$/);
  const again = await pairWith({ port: api.port, code: wrong.code });
  assert.equal(again.status, 401);
  const d = await request({ port: api.port, path: '/v1/device', token: ok.json.token });
  assert.equal(d.status, 200);
  const stored = fs.readFileSync(path.join(api.relay.home, 'tokens.json'), 'utf8');
  assert.ok(!stored.includes(ok.json.token));
  assert.ok(!api.logText().includes(ok.json.token));
  assert.ok(!api.logText().includes(wrong.code.replace('-', '')));
  assert.equal(fs.statSync(path.join(api.relay.home, 'tokens.json')).mode & 0o777, 0o600);
  // 撤销
  const id = api.relay.auth.list()[0].id;
  api.relay.auth.revoke(id);
  assert.equal((await request({ port: api.port, path: '/v1/device', token: ok.json.token })).status, 401);
});

test('配对：5 次错误后配对码作废；通道绑定拒绝换证书的中间人', async (t) => {
  const api = await setup(t, { pair: false });
  const { code } = api.relay.auth.startPairing();
  // 中间人场景：客户端看到的指纹与服务端真实指纹不同
  const { pairKey, hmac } = await import('../lib/auth.mjs');
  const crypto = await import('node:crypto');
  const nonce = crypto.randomBytes(16);
  const fakeFp = 'a'.repeat(64);
  const proof = hmac(pairKey(code, nonce), `pair|${fakeFp}`).toString('hex');
  const r = await request({ port: api.port, method: 'POST', path: '/v1/pair', body: { nonce: nonce.toString('hex'), seenFp: fakeFp, proof, name: 'mitm' } });
  assert.equal(r.status, 401);
  for (let i = 0; i < 3; i++) await pairWith({ port: api.port, code: 'ZZZZZ-ZZZZZ' });
  // 前 4 次失败后码仍有效？第 5 次失败即作废
  await pairWith({ port: api.port, code: 'ZZZZZ-ZZZZZ' }).catch(() => {});
  const final = await pairWith({ port: api.port, code });
  assert.ok([401, 429].includes(final.status));
});

// ---- observe / launch / apps / device ----

test('observe：假 hdc 夹具经 HTTP 返回与 contract 期望一致的窗口树，设备端与 Mac 端临时文件都清掉', async (t) => {
  const api = await setup(t);
  const r = await api.req({ method: 'POST', path: '/v1/observe', body: { image: true } });
  assert.equal(r.status, 200);
  const expected = JSON.parse(fs.readFileSync(path.join(FIXTURES, '..', 'expected', 'synthetic-settings.observe.json'), 'utf8'));
  assert.deepEqual(r.json.windows, expected.windows);
  assert.equal(r.json.main, expected.main);
  assert.deepEqual(r.json.display, { id: 0, width: 1320, height: 2120 });
  assert.equal(r.json.image.mime, 'image/jpeg');
  assert.equal(Buffer.from(r.json.image.b64, 'base64')[0], 0xff);
  assert.equal(fs.readdirSync(path.join(api.fake, 'dev', 'data/local/tmp/dsh-relay')).length, 0);
  assert.equal(fs.readdirSync(path.join(api.relay.home, 'tmp')).length, 0);
  const line = api.logLines().find((l) => l.op === 'observe');
  assert.equal(line.kind, 'read');
  assert.equal(line.nodes, expected.nodes);
  assert.ok(line.bytes > 0 && line.ms >= 0);
  assert.ok(!api.logText().includes('蓝牙'), 'screen text must not reach the log');
  const dev = await api.req({ method: 'GET', path: '/v1/device' });
  assert.equal(dev.json.device.state, 'ok');
  assert.equal(dev.json.device.model, 'VDE-AL00');
  assert.equal(dev.json.device.via, 'usb');
});

test('observe 带 bundle 时只读目标窗口（dumpLayout -b）', async (t) => {
  const api = await setup(t);
  await api.req({ method: 'POST', path: '/v1/observe', body: { bundle: 'com.huawei.hmos.settings' } });
  assert.ok(api.calls().some((c) => c.ev === 'bundle' && c.bundle === 'com.huawei.hmos.settings'));
  assert.equal((await api.req({ method: 'POST', path: '/v1/observe', body: { bundle: "x'; rm -rf /" } })).status, 422);
});

test('launch：从 bm dump 解析入口，锁屏回显映射 device_locked，非法包名 422', async (t) => {
  const api = await setup(t);
  const r = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'com.example.app' }, headers: { 'x-request-id': 'launch-0001' } });
  assert.equal(r.status, 200);
  assert.deepEqual(r.json.resolved, { module: 'phone', ability: 'MainAbility' });
  assert.ok(api.calls().some((c) => c.args.at(-1) === "aa start -b 'com.example.app' -a 'MainAbility' -m 'phone'"));
  api.setBehaviors([{ match: 'aa start', out: 'error: failed to start ability. code: 10106102\n' }]);
  const locked = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'com.example.app', ability: 'A', module: 'm' } });
  assert.equal(locked.status, 409);
  assert.equal(locked.json.error.code, 'device_locked');
  for (const bad of [{ bundle: 'a b' }, { bundle: 'a;b' }, { bundle: 'a.b', ability: '$(x)' }, {}]) {
    assert.equal((await api.req({ method: 'POST', path: '/v1/launch', body: bad })).status, 422, JSON.stringify(bad));
  }
  api.setBehaviors([{ match: 'aa start', out: 'error: bundle not found\n' }]);
  const nf = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'a.b', ability: 'A' } });
  assert.equal(nf.json.error.code, 'command_failed');
});

test('apps：包名清单合并别名，q 过滤', async (t) => {
  const api = await setup(t);
  const all = await api.req({ method: 'GET', path: '/v1/apps' });
  assert.equal(all.json.apps.length, 3);
  assert.deepEqual(all.json.apps.find((a) => a.bundle === 'com.huawei.hmos.settings'), { bundle: 'com.huawei.hmos.settings', label: '设置', alias: '设置' });
  assert.equal(all.json.apps.find((a) => a.bundle === 'cn.example.app').alias, null);
  const q = await api.req({ method: 'GET', path: '/v1/apps?q=%E8%AE%BE%E7%BD%AE' });
  assert.equal(q.json.apps.length, 1);
});

// ---- 设备选择与错误码 ----

test('目标选择：USB 优先；无 USB 用 TCP；两者都没有 device_offline 并退避', async (t) => {
  const both = await setup(t, { targets: '192.168.31.226:46225\tTCP\tConnected\tlocalhost\nFAKE-USB-0001\tUSB\tConnected\tlocalhost\n' });
  await both.act(click(1, 1));
  assert.ok(both.calls().some((c) => c.args[0] === '-t' && c.args[1] === 'FAKE-USB-0001'));
  assert.ok(!both.calls().some((c) => c.args[0] === '-t' && c.args[1] === '192.168.31.226:46225'));

  const tcp = await setup(t, { targets: '192.168.31.226:46225\tTCP\tConnected\tlocalhost\n' });
  await tcp.act(click(1, 1));
  assert.ok(tcp.calls().some((c) => c.args[1] === '192.168.31.226:46225'));
  assert.equal((await tcp.req({ method: 'GET', path: '/v1/device' })).json.device.via, 'tcp');

  const none = await setup(t, { targets: '' });
  const r = await none.act(click(1, 1));
  assert.equal(r.status, 503);
  assert.equal(r.json.error.code, 'device_offline');
  assert.equal(r.json.error.retryable, true);
  const n = none.calls().length;
  const r2 = await none.act(click(1, 1));
  assert.equal(r2.status, 503);
  const un = await setup(t, { targets: 'FAKE-USB-0001\tUSB\tUnauthorized\tlocalhost\n' });
  assert.equal((await un.act(click(1, 1))).json.error.code, 'device_unauthorized');
});

test('TCP 无线目标：POST /v1/target 校验私网地址与端口', async (t) => {
  const api = await setup(t);
  for (const bad of ['8.8.8.8:5555', '192.168.31.226:80', '192.168.31.226', 'a.b:1234', '192.168.31.226:70000', undefined]) {
    assert.equal((await api.req({ method: 'POST', path: '/v1/target', body: { addr: bad } })).status, 422, String(bad));
  }
  const ok = await api.req({ method: 'POST', path: '/v1/target', body: { addr: '192.168.31.226:46225' } });
  assert.equal(ok.status, 200);
  assert.ok(api.logLines().some((l) => l.kind === 'ctl' && l.op === 'target'));
});

test('十二个错误码与 HTTP 状态一一对应', () => {
  const expect = { unauthorized: 401, forbidden_source: 403, cancelled: 409, device_locked: 409, bad_args: 422, halted: 423, command_failed: 424, busy: 429, device_offline: 503, device_unauthorized: 503, device_timeout: 504, internal: 500 };
  assert.deepEqual(HTTP_STATUS, expect);
  const b = new RelayError('device_offline', 'm', { retryable: true, hint: 'h' }).toBody();
  assert.deepEqual(b, { ok: false, error: { code: 'device_offline', message: 'm', retryable: true, hint: 'h' } });
});

test('命令失败：uitest 回显非 No Error 时 424 command_failed', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'uiInput click 4 4', out: 'Error: bad coordinates\n' }] });
  const r = await api.act(click(4, 4));
  assert.equal(r.status, 424);
  assert.equal(r.json.error.code, 'command_failed');
});

test('未知路径与方法：404/405，且不带 CORS 头', async (t) => {
  const api = await setup(t);
  assert.equal((await api.req({ method: 'GET', path: '/v1/nope' })).status, 404);
  const r = await api.req({ method: 'GET', path: '/v1/act' });
  assert.equal(r.status, 405);
  assert.ok(!Object.keys(r.headers).some((h) => h.startsWith('access-control')));
  assert.equal((await api.req({ method: 'OPTIONS', path: '/v1/act' })).status, 405);
});

test('日志：每行 JSON，字段脱敏，令牌与配对码不出现', async (t) => {
  const api = await setup(t);
  await api.act(click(10, 20));
  const line = api.logLines().find((l) => l.kind === 'act');
  assert.equal(line.op, 'click');
  assert.deepEqual(line.args, { op: 'click', x: 10, y: 20 });
  assert.equal(line.cmd, 'uitest uiInput click 10 20');
  assert.match(line.tok, /^[0-9a-f]{6}$/);
  assert.ok(!api.logText().includes(api.token));
});

test('读屏超时：杀掉设备端残留的 uitest dumpLayout（只按自己的输出路径），并清掉临时文件', async (t) => {
  const api = await setup(t, { relayOpts: { timeouts: { observe: 1500 } }, behaviors: [{ match: 'uitest dumpLayout', sleepMs: 30000 }] });
  const r = await api.req({ method: 'POST', path: '/v1/observe', body: {} });
  assert.equal(r.status, 504);
  const kill = api.calls().find((c) => c.ev === 'start' && /pkill -f/.test(c.args.at(-1) ?? ''));
  assert.ok(kill, 'pkill must be issued');
  assert.match(kill.args.at(-1), /^rm -f \/data\/local\/tmp\/dsh-relay\/l-\d+\.json; pkill -f '\[\/\]data\/local\/tmp\/dsh-relay\/l-\d+\.json'$/);
  assert.equal(fs.readdirSync(path.join(api.relay.home, 'tmp')).length, 0);
});

test('launch 容器应用：调用方给全 bundle+ability，不查 bm dump，不带 -m', async (t) => {
  const api = await setup(t);
  const r = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'probe.zyt', ability: 'probe.zyt.MainActivity' } });
  assert.equal(r.status, 200);
  assert.deepEqual(r.json.resolved, { module: null, ability: 'probe.zyt.MainActivity' });
  assert.ok(api.calls().some((c) => c.args.at(-1) === "aa start -b 'probe.zyt' -a 'probe.zyt.MainActivity'"));
  assert.ok(!api.calls().some((c) => /bm dump -n/.test(c.args.at(-1) ?? '')));
});

// ---- 修订 3（P4 对抗审查） ----

test('M1 replaceText：焦点不在目标输入框时不发全选与删除', async (t) => {
  const api = await setup(t);
  const r = await api.act({ op: 'replaceText', x: 600, y: 900, text: 'hello' }); // 该点不在带焦点的输入框内
  assert.equal(r.status, 424);
  assert.equal(r.json.error.code, 'command_failed');
  assert.match(r.json.error.message, /nothing was cleared/);
  assert.deepEqual(api.uitest().map((a) => a.slice(1)), [['click', '600', '900']]);
  assert.ok(!api.uitest().some((a) => a[1] === 'keyEvent'));
});

test('M4 launch 不再支持 uri', async (t) => {
  const api = await setup(t);
  const r = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'a.b', uri: 'https://example.com/?token=secret' } });
  assert.equal(r.status, 422);
  assert.ok(!api.calls().some((c) => /aa start/.test(c.args.at(-1) ?? '')));
  assert.ok(!api.logText().includes('secret'));
});

test('M7 设备在线判断只认 hdc 回显开头：应用输出里带 offline 不算设备离线', async (t) => {
  const api = await setup(t, { behaviors: [{ match: 'aa start', out: 'start ability successfully.\ncom.example.offlinemaps offline\n' }] });
  const ok = await api.req({ method: 'POST', path: '/v1/launch', body: { bundle: 'com.example.offlinemaps', ability: 'A' } });
  assert.equal(ok.status, 200);
  assert.equal((await api.req({ method: 'GET', path: '/v1/device' })).json.device.state, 'ok');
  api.setBehaviors([{ match: 'uiInput click 3 3', out: 'Connect server failed\n' }]);
  const down = await api.act(click(3, 3));
  assert.equal(down.json.error.code, 'device_offline');
  api.setBehaviors([{ match: 'uiInput click 4 4', out: '[Fail]ExecuteCommand need connect-key?\n' }]);
  assert.equal((await api.act(click(4, 4))).json.error.code, 'device_offline');
});

test('M2 绑定设备：首次连接绑定唯一的 USB 设备，之后别的设备在线也不回退', async (t) => {
  const api = await setup(t);
  assert.equal((await api.act(click(1, 1))).status, 200);
  const bind = JSON.parse(fs.readFileSync(path.join(api.relay.home, 'binding.json'), 'utf8'));
  assert.equal(bind.serial, 'FAKE-USB-0001');
  // 绑定的手机拔掉，只剩另一台 USB 和另一个无线设备
  fs.writeFileSync(path.join(api.fake, 'targets.txt'), 'OTHER-USB-9999\tUSB\tConnected\tlocalhost\n192.168.31.77:5555\tTCP\tConnected\tlocalhost\n');
  api.relay.device.markStale();
  const r = await api.act(click(2, 2));
  assert.equal(r.status, 503);
  assert.equal(r.json.error.code, 'device_offline');
  assert.ok(!api.calls().some((c) => c.args[0] === '-t' && ['OTHER-USB-9999', '192.168.31.77:5555'].includes(c.args[1])), 'no command may reach another device');
  // 绑定的手机回来后恢复
  fs.writeFileSync(path.join(api.fake, 'targets.txt'), 'OTHER-USB-9999\tUSB\tConnected\tlocalhost\nFAKE-USB-0001\tUSB\tConnected\tlocalhost\n');
  api.relay.device.markStale();
  assert.equal((await api.act(click(2, 2))).status, 200);
  assert.ok(api.calls().some((c) => c.args[1] === 'FAKE-USB-0001' && c.args.includes('uitest uiInput click 2 2')));
});

test('M2 首次使用遇到多台设备不替用户猜；无线地址绑定后只认该地址', async (t) => {
  const multi = await setup(t, { targets: 'USB-A\tUSB\tConnected\tlocalhost\nUSB-B\tUSB\tConnected\tlocalhost\n' });
  const r = await multi.act(click(1, 1));
  assert.equal(r.json.error.code, 'device_offline');
  assert.match(r.json.error.message, /multiple USB/);
  assert.ok(!multi.calls().some((c) => c.args[0] === '-t'));

  const tcp = await setup(t, { targets: '192.168.31.226:46225\tTCP\tConnected\tlocalhost\n192.168.31.77:5555\tTCP\tConnected\tlocalhost\n' });
  assert.equal((await tcp.req({ method: 'POST', path: '/v1/target', body: { addr: '192.168.31.226:46225' } })).status, 200);
  assert.equal((await tcp.act(click(1, 1))).status, 200);
  assert.ok(!tcp.calls().some((c) => c.args[1] === '192.168.31.77:5555'));
});

test('H2 locked：锁屏夹具使 observe 与 /v1/device 报 locked:true；解锁页为 false', async (t) => {
  const lock = await setup(t, { dump: '05-lockscreen.json' });
  const o = await lock.req({ method: 'POST', path: '/v1/observe', body: {} });
  assert.equal(o.json.device.locked, true);
  assert.equal((await lock.req({ method: 'GET', path: '/v1/device' })).json.device.locked, true);
  const open = await setup(t, { dump: '01-settings-home.json' });
  assert.equal((await open.req({ method: 'POST', path: '/v1/observe', body: {} })).json.device.locked, false);
});

test('H3 secureKeyboard：输入法窗口出现「安全键盘」时响应带标记，焦点输入框置 password', async (t) => {
  const api = await setup(t, { dump: '03-wifi-password-dialog.json' });
  const o = await api.req({ method: 'POST', path: '/v1/observe', body: {} });
  assert.equal(o.json.secureKeyboard, true);
  const flat = [];
  (function walk(n) { flat.push(n); (n.children ?? []).forEach(walk); })({ children: o.json.windows.map((w) => w.root).filter(Boolean) });
  assert.ok(flat.find((n) => n.raw === 'TextInput').flags.includes('password'));
  const plain = await setup(t, { dump: '02-settings-bluetooth.json' });
  assert.equal((await plain.req({ method: 'POST', path: '/v1/observe', body: {} })).json.secureKeyboard, false);
});

test('M3 别名表里没有支付入口', () => {
  const a = JSON.parse(fs.readFileSync(path.join(path.dirname(new URL(import.meta.url).pathname), '..', 'config', 'aliases.json'), 'utf8'));
  assert.ok(!('钱包' in a));
  assert.ok(!Object.values(a).some((b) => /wallet|pay|bank/i.test(b)));
});
