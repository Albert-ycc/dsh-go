import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import https from 'node:https';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { RelayError, StartupError } from './errors.mjs';
import { readJsonFile, checkConfig, checkTypemap, checkKeymap, checkGestures, checkAliases } from './validate.mjs';
import { createLogger } from './log.mjs';
import { createAuth, sha256hex } from './auth.mjs';
import { Hdc } from './hdc.mjs';
import { Device, validTcpAddr } from './device.mjs';
import { convertDump } from './observe.mjs';
import { validateAct, planAct, needsDisplay, checkBundle, shq } from './acts.mjs';
import { ensureCert } from './tls.mjs';
import { defaultSourceAllowed, isLoopback, listenAddresses, normalizeRemote, parseIPv4 } from './net.mjs';

export const VERSION = '0.1.0';
const CONFIG_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'config');
const DEVICE_DIR = '/data/local/tmp/dsh-relay';
const MAX_BODY = 64 * 1024;
const DRAIN_MAX_BODY = 8 * 1024 * 1024;
const IDEM_TTL_MS = 60_000;
const REQ_ID_RE = /^[A-Za-z0-9._-]{8,64}$/;
const TASK_RE = /^[A-Za-z0-9._:-]{1,64}$/;
/** observe 从开始到结束（含重连重试，不含最后的设备端清理）的总上限；手机端读超时 18 秒，要留出清理与网络余量 */
const OBSERVE_CAP_MS = 12_000;
const DUMP_MAX_BYTES = 16 * 1024 * 1024;
const KICK_MIN_INTERVAL_MS = 10_000;

/** 先看 home 下的覆盖文件，没有再用仓库默认；文件存在但损坏或形状不对直接启动失败，不静默回退。 */
function loadJson(home, name, check, fallback) {
  for (const p of [path.join(home, name), path.join(CONFIG_DIR, name)]) {
    const v = readJsonFile(p);
    if (v !== undefined) {
      check?.(v);
      return v;
    }
  }
  return fallback;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const firstLine = (s) => String(s).trim().split('\n')[0].slice(0, 300);

export function createRelay(opts = {}) {
  const home = opts.home ?? process.env.DSH_RELAY_HOME ?? path.join(os.homedir(), '.dsh-relay');
  const now = opts.now ?? (() => Date.now());
  const tmpDir = path.join(home, 'tmp');
  try {
    fs.mkdirSync(home, { recursive: true, mode: 0o700 });
    fs.rmSync(tmpDir, { recursive: true, force: true });
    fs.mkdirSync(tmpDir, { recursive: true, mode: 0o700 });
  } catch (e) {
    throw new StartupError(`状态目录不可用 ${home}（${e.code ?? e.message}），检查路径与权限`);
  }

  const config = { device: {}, logText: false, ...loadJson(home, 'config.json', checkConfig, {}), ...(opts.config ?? {}) };
  if (config.device === null) config.device = {};
  const typemap = loadJson(home, 'typemap.json', checkTypemap);
  const keymap = loadJson(home, 'keymap.json', checkKeymap);
  const gestures = loadJson(home, 'gestures.json', checkGestures);
  const aliases = { ...loadJson(CONFIG_DIR, 'aliases.json', checkAliases, {}), ...loadJson(home, 'aliases.json', checkAliases, {}) };
  const timeouts = { observe: 8000, act: 5000, launch: 8000, apps: 5000, ...(opts.timeouts ?? {}) };

  let log;
  try {
    log = createLogger({
      dir: opts.logDir ?? process.env.DSH_RELAY_LOG_DIR ?? path.join(os.homedir(), 'Library', 'Logs', 'dsh-relay'),
      now,
    });
  } catch (e) {
    throw new StartupError(`日志目录不可用（${e.code ?? e.message}），检查 DSH_RELAY_LOG_DIR 与权限`);
  }
  const auth = createAuth({
    home,
    now,
    warn: (m) => {
      console.error(`dsh-relay: ${m}`);
      log.write({ kind: 'ctl', op: 'warn', msg: m.slice(0, 300), ok: false });
    },
  });
  const tls = ensureCert(home);
  if (tls.regenerated) log.write({ kind: 'ctl', op: 'cert_regenerated', fp: tls.fingerprint.slice(0, 12), ok: true });
  if (tls.keyModeFixed) log.write({ kind: 'ctl', op: 'key_mode_fixed', ok: true });
  const hdc = new Hdc({ bin: opts.hdcBin ?? process.env.HDC_BIN ?? 'hdc', now });
  const device = new Device({
    home,
    config,
    now,
    warn: (m) => {
      console.error(`dsh-relay: ${m}`);
      log.write({ kind: 'ctl', op: 'warn', msg: m.slice(0, 300), ok: false });
    },
  });
  const sourceAllowed = opts.sourceAllowed ?? defaultSourceAllowed;
  const hostNames = new Set(['localhost', ...(config.hostNames ?? [])]);
  const haltFile = path.join(home, 'HALT');
  const halted = () => fs.existsSync(haltFile);

  let seq = 0;
  const bundleCache = new Map();
  let appsCache = null;

  // ---------- 设备操作 ----------

  async function dumpLayout(ctx, target, { bundle } = {}) {
    const n = ++seq;
    const remote = `${DEVICE_DIR}/l-${n}.json`;
    const local = path.join(tmpDir, `l-${n}.json`);
    let failed = false;
    try {
      let t0 = now();
      const cmd =
        `mkdir -p ${DEVICE_DIR} && uitest dumpLayout${bundle ? ` -b ${shq(bundle)}` : ''} -p ${remote}`;
      const r = await device.shell(ctx, target, cmd, { timeoutMs: 5000 });
      if (!/DumpLayout saved to/.test(r.text)) throw new RelayError('command_failed', firstLine(r.text) || 'dumpLayout failed', { executed: true });
      const layoutMs = now() - t0;
      t0 = now();
      await recv(ctx, target, remote, local, 3000);
      const bytes = fs.statSync(local).size;
      if (bytes > DUMP_MAX_BYTES) throw new RelayError('command_failed', `dumpLayout output too large (${bytes} bytes)`, { executed: true });
      let raw;
      try {
        raw = JSON.parse(fs.readFileSync(local, 'utf8'));
      } catch {
        raw = null;
      }
      // 半截 JSON、空文件、null、数组：设备吐了坏数据，按设备命令失败返回，不算中继内部错误
      if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
        throw new RelayError('command_failed', 'dumpLayout output is not a valid layout JSON', { executed: true, hint: 'read the screen again' });
      }
      return { raw, bytes, layoutMs, recvMs: now() - t0 };
    } catch (e) {
      failed = true;
      throw e;
    } finally {
      fs.rmSync(local, { force: true });
      // 设备端布局 JSON 含屏幕文字，取完立刻删；即使任务被取消也要清。
      // 超时或取消时设备上的 uitest 还挂着（实测会挂 18–36 秒并堵住后续读屏），按自己的输出路径杀掉；
      // 方括号让 pkill -f 不匹配执行它的这条 shell 本身
      const kill = failed ? `; pkill -f '[/]${remote.slice(1)}'` : '';
      await device.shell(ctx, target, `rm -f ${remote}${kill}`, { timeoutMs: 2000, allowCancelled: true }).catch(() => {});
    }
  }

  async function recv(ctx, target, remote, local, timeoutMs) {
    const r = await ctx.run(['-t', target, 'file', 'recv', remote, local], { timeoutMs });
    if (r.code !== 0 || !fs.existsSync(local)) {
      throw new RelayError('command_failed', firstLine(r.text || r.stderr) || 'file recv failed', { executed: true });
    }
  }

  async function snapshot(ctx, target) {
    const n = ++seq;
    const remote = `${DEVICE_DIR}/s-${n}.jpeg`;
    const local = path.join(tmpDir, `s-${n}.jpeg`);
    try {
      const t0 = now();
      const r = await device.shell(ctx, target, `mkdir -p ${DEVICE_DIR} && snapshot_display -f ${remote}`, { timeoutMs: 3000 });
      const m = /width: (\d+), height: (\d+)/.exec(r.text);
      if (!/success/i.test(r.text) || !m) throw new RelayError('command_failed', firstLine(r.text) || 'snapshot failed', { executed: true });
      await recv(ctx, target, remote, local, 3000);
      const b64 = fs.readFileSync(local).toString('base64');
      return { mime: 'image/jpeg', width: Number(m[1]), height: Number(m[2]), b64, ms: now() - t0 };
    } finally {
      fs.rmSync(local, { force: true });
      await device.shell(ctx, target, `rm -f ${remote}`, { timeoutMs: 2000, allowCancelled: true }).catch(() => {});
    }
  }

  async function observeOnce(ctx, body) {
    const target = await device.acquire(ctx);
    const d = await dumpLayout(ctx, target, { bundle: body.bundle });
    const conv = convertDump(d.raw, typemap);
    device.setLocked(conv.locked);
    // display：整屏读屏按维度取已见过的最大值（键盘、半屏窗口会让窗口外接框变小）；限定 bundle 的读屏不参与合并
    if (conv.display && !body.bundle) device.mergeDisplay(conv.display);
    if (device.display) conv.display = { id: 0, width: device.display.width, height: device.display.height };
    let image = null;
    let imageMs = 0;
    if (body.image) {
      image = await snapshot(ctx, target);
      imageMs = image.ms;
      delete image.ms;
      // 有截图时以截图尺寸为准并更新缓存
      device.mergeDisplay({ width: image.width, height: image.height }, true);
      conv.display = { id: 0, width: image.width, height: image.height };
    }
    const out = {
      ok: true,
      seq,
      tookMs: { layout: d.layoutMs, recv: d.recvMs, image: imageMs },
      display: conv.display,
      device: { locked: conv.locked, screenOn: null },
      secureKeyboard: conv.secureKeyboard,
      main: conv.main,
      windows: conv.windows,
    };
    if (image) out.image = image;
    if (conv.truncated) out.truncated = true;
    return { out, nodes: conv.nodes, bytes: d.bytes };
  }

  async function observe(ctx, body) {
    const startedAt = now();
    try {
      return await observeOnce(ctx, body);
    } catch (e) {
      if (!(e instanceof RelayError) || ctx.cancelled || !['device_timeout', 'device_offline'].includes(e.code)) throw e;
      // 读操作：设备超时后重连一次再试（方案 4.7）。重试预算压在总上限内：
      // 手机端读超时 18 秒，首轮超时+清理+重试+清理之和必须在它之内
      const left = Math.min(timeouts.observe, startedAt + OBSERVE_CAP_MS - now());
      if (left < 1500) throw e;
      device.markStale();
      ctx.extendDeadline(left);
      return await observeOnce(ctx, body);
    }
  }

  function focusOnEditable(conv, x, y) {
    let hit = false;
    const walk = (n) => {
      const [l, t, r, b] = n.bounds;
      if (n.flags?.includes('editable') && n.flags.includes('focused') && x >= l && x < r && y >= t && y < b) hit = true;
      (n.children ?? []).forEach(walk);
    };
    for (const w of conv.windows) if (w.root) walk(w.root);
    return hit;
  }

  async function act(ctx, body) {
    const target = await device.acquire(ctx);
    if (needsDisplay(body) && !device.display) {
      const d = await dumpLayout(ctx, target);
      const c = convertDump(d.raw, typemap);
      if (c.display) device.mergeDisplay(c.display);
    }
    const steps = planAct(body, { display: device.display, keymap, gestures });
    const done = [];
    const t0 = now();
    for (const [i, s] of steps.entries()) {
      if (s.pauseMs) {
        await sleep(s.pauseMs);
        ctx.assertLive();
      }
      if (body.op === 'replaceText' && i === 1) {
        // 点击之后先确认焦点真在目标输入框，再发全选和删除；否则这两步会落在别的界面上
        await sleep(300);
        ctx.extendDeadline(timeouts.act + 3000);
        const d = await dumpLayout(ctx, target, {});
        if (!focusOnEditable(convertDump(d.raw, typemap), body.x, body.y)) {
          throw new RelayError('command_failed', 'focus is not on an input field at the target point; nothing was cleared or typed', { executed: true, hint: 'screen changed, read the screen again' });
        }
      }
      const s0 = now();
      const r = await device.shell(ctx, target, s.cmd, { timeoutMs: timeouts.act });
      done.push({ cmd: s.log, ms: now() - s0, out: r.text.trim().slice(0, 200) });
      if (!/No Error/.test(r.text)) {
        throw new RelayError('command_failed', firstLine(r.text) || 'uitest failed', { executed: true });
      }
    }
    return { out: { ok: true, op: body.op, steps: done, tookMs: now() - t0 }, cmd: done.map((s) => s.cmd).join(' && ') };
  }

  async function bundleEntry(ctx, target, bundle) {
    if (bundleCache.has(bundle)) return bundleCache.get(bundle);
    const r = await device.shell(ctx, target, `bm dump -n ${shq(bundle)}`, { timeoutMs: 4000 });
    const i = r.text.indexOf('{');
    let info;
    try {
      info = JSON.parse(r.text.slice(i));
    } catch {
      throw new RelayError('command_failed', `bundle not found: ${bundle}`, { executed: true });
    }
    const mods = info.hapModuleInfos ?? [];
    const entryName = info.mainEntry || info.entryModuleName;
    const mod = mods.find((m) => m.moduleName === entryName && m.mainAbility) ?? mods.find((m) => m.mainAbility);
    if (!mod) throw new RelayError('command_failed', `no main ability for ${bundle}`, { executed: true });
    // aa start -a 要的是 abilityInfos 里登记的 name：计算器登记短名 CalculatorAbility，
    // 设置登记全名；mainAbility 字段两者都写全名，直接用会报 10104001
    const names = (mod.abilityInfos ?? []).map((a) => a.name);
    const ability = names.includes(mod.mainAbility) ? mod.mainAbility : names.find((n) => mod.mainAbility.endsWith(`.${n}`)) ?? mod.mainAbility;
    const entry = { module: mod.moduleName, ability };
    bundleCache.set(bundle, entry);
    return entry;
  }

  async function launch(ctx, body) {
    const target = await device.acquire(ctx);
    let { ability, module } = body;
    if (!ability) ({ ability, module } = await bundleEntry(ctx, target, body.bundle));
    const resolved = { module: module ?? null, ability };
    const cmd = `aa start -b ${shq(body.bundle)} -a ${shq(ability)}${module ? ` -m ${shq(module)}` : ''}`;
    const r = await device.shell(ctx, target, cmd, { timeoutMs: timeouts.launch });
    if (/10106102/.test(r.text)) {
      device.setLocked(true);
      throw new RelayError('device_locked', 'device is locked', { executed: true });
    }
    if (!/start ability successfully/i.test(r.text)) {
      throw new RelayError('command_failed', firstLine(r.text) || 'aa start failed', { executed: true });
    }
    return { out: { ok: true, resolved, out: r.text.trim().slice(0, 300) }, cmd };
  }

  async function listApps(ctx) {
    if (appsCache && now() - appsCache.at < 60_000) return appsCache.list;
    const target = await device.acquire(ctx);
    const r = await device.shell(ctx, target, 'bm dump -a', { timeoutMs: timeouts.apps });
    const set = new Set();
    for (const line of r.text.split('\n')) {
      const t = line.trim();
      if (/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(t) && t.includes('.')) set.add(t);
    }
    const byBundle = new Map(Object.entries(aliases).map(([name, b]) => [b, name]));
    const list = [...set].sort().map((bundle) => {
      const a = byBundle.get(bundle) ?? null;
      return { bundle, label: a, alias: a };
    });
    appsCache = { at: now(), list };
    return list;
  }

  // ---------- HTTP ----------

  const idem = new Map();
  function sweepIdem() {
    for (const [k, v] of idem) if (v.at && now() - v.at > IDEM_TTL_MS) idem.delete(k);
  }

  /** 写动作按请求 id 幂等：60 秒内重放返回首次结果，在途返回 202。设备命令没发出去的结果不缓存。 */
  async function idempotent(reqId, sig, fn) {
    if (!reqId) return fn();
    sweepIdem();
    const hit = idem.get(reqId);
    // 同一个请求 id 只能重放同一个请求（方法+路径+body 摘要）；别的请求借用它会静默拿到不相干的结果
    if (hit && hit.sig !== sig) throw new RelayError('bad_args', 'X-Request-Id was already used for a different request');
    if (hit?.state === 'done') return { replay: true, ...hit.result };
    if (hit?.state === 'inflight') return { status: 202, body: { ok: true, in_progress: true } };
    idem.set(reqId, { state: 'inflight', sig });
    let result;
    try {
      result = await fn();
    } catch (e) {
      if (e instanceof RelayError && e.executed) {
        idem.set(reqId, { state: 'done', sig, at: now(), result: { status: e.status, body: e.toBody() } });
      } else {
        idem.delete(reqId);
      }
      throw e;
    }
    idem.set(reqId, { state: 'done', sig, at: now(), result });
    return result;
  }
  const requestSig = (method, p, body) => crypto.createHash('sha256').update(`${method} ${p} ${JSON.stringify(body)}`).digest('hex');

  function send(res, status, body) {
    const s = JSON.stringify(body);
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(s),
      'cache-control': 'no-store',
    });
    res.end(s);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let n = 0;
      let over = false;
      const tooLarge = () => reject(new RelayError('bad_args', 'body too large'));
      // 超限也读完（丢弃，不缓冲）再回 422：客户端还在上传时服务端就断连，它只会看到 EPIPE/ECONNRESET，读不到错误码。
      // 声明长度超过 DRAIN_MAX 的不陪读，立刻回 422；上传不完的连接由 requestTimeout（30 秒）收掉
      if (Number(req.headers['content-length']) > DRAIN_MAX_BODY) return tooLarge();
      req.on('data', (c) => {
        n += c.length;
        if (over) return;
        if (n > MAX_BODY) {
          over = true;
          chunks.length = 0;
        } else chunks.push(c);
      });
      req.on('end', () => {
        if (over) return tooLarge();
        if (n === 0) return resolve({});
        try {
          const v = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error('not object');
          resolve(v);
        } catch {
          reject(new RelayError('bad_args', 'invalid JSON body'));
        }
      });
      req.on('error', reject);
    });
  }

  function hostOk(host) {
    if (!host) return false;
    const h = host.startsWith('[') ? host.slice(1, host.indexOf(']')) : host.replace(/:\d+$/, '');
    return parseIPv4(h) !== null || h === '::1' || hostNames.has(h);
  }

  /**
   * 设备不在线时在后台连一次，不阻塞调用方：/v1/device 与服务启动时调用。
   * 走 soft 作业（不计队列深度，仍与真实作业串行，不会和读屏同时抢 hdc），10 秒最多一次。
   */
  let lastKickAt = 0;
  let lastKickCode = null;
  function kickAcquire({ force = false } = {}) {
    if (halted() || (!force && device.state === 'ok')) return null;
    const t = now();
    if (!force && lastKickAt && t - lastKickAt < KICK_MIN_INTERVAL_MS) return null;
    lastKickAt = t;
    const job = hdc.submit({ kind: 'kick', soft: true, timeoutMs: 8000 }, (ctx) => device.acquire(ctx, { passive: true }));
    // 失败只在错误码变化时记一行，离线期间每 10 秒一次不刷日志
    return job.then(
      () => {
        lastKickCode = null;
        log.write({ kind: 'ctl', op: 'kick', dev: device.target ? `${device.via}:${device.target}` : null, ok: true });
      },
      (e) => {
        const code = e.code ?? 'internal';
        if (code !== lastKickCode) log.write({ kind: 'ctl', op: 'kick', ok: false, code });
        lastKickCode = code;
      },
    );
  }

  function submit(kind, req, ctxFn, timeoutMs) {
    return hdc.submit({ task: req.headers['x-dsh-task'], kind, timeoutMs }, ctxFn);
  }

  async function handleV1(req, res, ctxInfo) {
    const { ip, url, method } = ctxInfo;
    const p = url.pathname;
    const routes = {
      '/v1/ping': 'GET', '/v1/pair': 'POST', '/v1/device': 'GET', '/v1/observe': 'POST', '/v1/act': 'POST',
      '/v1/launch': 'POST', '/v1/apps': 'GET', '/v1/target': 'POST', '/v1/stop': 'POST', '/v1/halt': 'POST',
    };
    if (!(p in routes)) return send(res, 404, { ok: false, error: { code: 'bad_args', message: 'unknown path', retryable: false } });
    if (routes[p] !== method) return send(res, 405, { ok: false, error: { code: 'bad_args', message: 'method not allowed', retryable: false } });
    if (p === '/v1/ping') return send(res, 200, { service: 'dsh-relay', v: 1 });

    if (req.headers['x-dsh-relay'] !== '1') {
      throw new RelayError('forbidden_source', 'missing X-Dsh-Relay header');
    }
    if (auth.blocked(ip)) {
      throw new RelayError('busy', 'too many failed authentications', { retryable: true, hint: 'blocked for 60 seconds' });
    }

    const body = method === 'POST' ? await readBody(req) : {};
    const reqId = req.headers['x-request-id'];
    if (reqId !== undefined && !REQ_ID_RE.test(String(reqId))) throw new RelayError('bad_args', 'invalid X-Request-Id');
    const taskHeader = req.headers['x-dsh-task'];
    if (taskHeader !== undefined && !TASK_RE.test(String(taskHeader))) throw new RelayError('bad_args', 'invalid X-Dsh-Task');

    if (p === '/v1/pair') {
      const okShape =
        typeof body.nonce === 'string' && /^[0-9a-f]{32}$/.test(body.nonce) &&
        typeof body.seenFp === 'string' && /^[0-9a-f]{64}$/.test(body.seenFp) &&
        typeof body.proof === 'string' && /^[0-9a-f]{64}$/.test(body.proof);
      const r = okShape ? auth.completePairing(body, tls.fingerprint, ip) : { ok: false, reason: 'bad_shape' };
      if (!r.ok && r.reason === 'save_failed') throw new RelayError('internal', 'could not save the new token; pairing not completed', { retryable: true });
      if (!r.ok) {
        auth.fail(ip);
        log.write({ kind: 'auth', op: 'pair', ip, ok: false, reason: r.reason });
        throw new RelayError('unauthorized', 'pairing failed');
      }
      log.write({ kind: 'auth', op: 'pair', ip, tok: r.id, ok: true });
      return send(res, 200, { ok: true, token: r.token, serverProof: r.serverProof });
    }

    const who = auth.verifyBearer(req.headers.authorization, ip);
    if (!who) {
      auth.fail(ip);
      log.write({ kind: 'auth', op: 'auth_fail', ip, path: p, ok: false });
      throw new RelayError('unauthorized', 'invalid token');
    }
    const task = req.headers['x-dsh-task'];
    const entry = { req: reqId, task, ip, tok: who.id };
    const dev = () => (device.target ? `${device.via}:${device.target}` : null);

    if (p === '/v1/device') {
      kickAcquire();
      return send(res, 200, {
        ok: true,
        halted: halted(),
        device: device.status(),
        queue: { depth: hdc.depth() },
        version: VERSION,
      });
    }
    if (p === '/v1/halt') {
      doHalt();
      log.write({ ...entry, kind: 'ctl', op: 'halt', ok: true });
      return send(res, 200, { ok: true, halted: true });
    }
    if (halted()) throw new RelayError('halted', 'relay is halted on the Mac', { hint: 'run dsh-relay resume on the Mac' });

    if (p === '/v1/stop') {
      if (typeof body.task !== 'string' || !body.task) throw new RelayError('bad_args', 'task required');
      const n = hdc.cancelTask(body.task);
      log.write({ ...entry, kind: 'ctl', op: 'stop', cancelled: n, ok: true });
      return send(res, 200, { ok: true, cancelled: n });
    }
    if (p === '/v1/target') {
      if (!validTcpAddr(body.addr)) throw new RelayError('bad_args', 'addr must be private IPv4:port (1024-65535)');
      device.setTcpAddr(body.addr);
      log.write({ ...entry, kind: 'ctl', op: 'target', ok: true });
      return send(res, 200, { ok: true, state: device.state });
    }

    const t0 = now();
    const finish = (kind, op, extra, ok = true, code) =>
      log.write({ ...entry, kind, op, ...extra, dev: dev(), ms: now() - t0, ok, ...(code ? { code } : {}) });
    try {
      if (p === '/v1/observe') {
        if (body.bundle !== undefined) checkBundle(body.bundle);
        const budget = Number.isInteger(body.timeoutMs) ? Math.min(15000, Math.max(2000, body.timeoutMs)) : timeouts.observe;
        const r = await submit('read', req, (ctx) => observe(ctx, { image: body.image === true, bundle: body.bundle }), budget);
        finish('read', 'observe', { nodes: r.nodes, bytes: r.bytes });
        return send(res, 200, r.out);
      }
      if (p === '/v1/act') {
        validateAct(body);
        const r = await idempotent(reqId, requestSig(method, p, body), async () => {
          const v = await submit('act', req, (ctx) => act(ctx, body), body.op === 'replaceText' ? timeouts.act + 3000 : timeouts.act);
          return { status: 200, body: v.out, cmd: v.cmd };
        });
        if (!r.replay) {
          finish('act', body.op, { args: actArgs(body), cmd: r.cmd, ...(r.status === 202 ? { inflight: true } : {}) });
        }
        return send(res, r.status, r.body);
      }
      if (p === '/v1/launch') {
        checkBundle(body.bundle);
        if (body.ability !== undefined) checkBundle(body.ability, 'ability');
        if (body.module !== undefined) checkBundle(body.module, 'module');
        if (body.uri !== undefined) throw new RelayError('bad_args', 'uri is not supported');
        const r = await idempotent(reqId, requestSig(method, p, body), async () => {
          const v = await submit('launch', req, (ctx) => launch(ctx, body), timeouts.launch);
          return { status: 200, body: v.out, cmd: v.cmd };
        });
        if (!r.replay) finish('launch', 'launch', { args: { bundle: body.bundle }, cmd: r.cmd });
        return send(res, r.status, r.body);
      }
      if (p === '/v1/apps') {
        const list = await submit('read', req, (ctx) => listApps(ctx), timeouts.apps);
        const q = (url.searchParams.get('q') ?? '').toLowerCase();
        const apps = q ? list.filter((a) => a.bundle.toLowerCase().includes(q) || (a.alias ?? '').toLowerCase().includes(q)) : list;
        finish('read', 'apps', { count: apps.length });
        return send(res, 200, { ok: true, apps });
      }
    } catch (e) {
      if (e instanceof RelayError) finish(p === '/v1/launch' ? 'launch' : p === '/v1/apps' ? 'read' : p === '/v1/observe' ? 'read' : 'act', p.slice(4), {}, false, e.code);
      throw e;
    }
  }

  function actArgs(b) {
    // 文本内容永不入日志，除非显式开 logText
    if (b.op === 'text' || b.op === 'replaceText') {
      const { text, ...rest } = b;
      return { ...rest, ...(config.logText ? { text } : { textChars: [...text].length }) };
    }
    return b;
  }

  function doHalt() {
    fs.writeFileSync(haltFile, String(now()), { mode: 0o600 });
    return hdc.cancelAll();
  }

  async function handleCtl(req, res, { url, method }) {
    if (!auth.verifyControl(req.headers.authorization)) throw new RelayError('unauthorized', 'invalid control token');
    const p = url.pathname;
    const body = method === 'POST' ? await readBody(req) : {};
    const addrs = () => listeners.map((l) => `${l.address}:${l.port}`);
    if (p === '/ctl/status' && method === 'GET') {
      return send(res, 200, {
        ok: true, version: VERSION, halted: halted(), device: device.status(), queue: { depth: hdc.depth() },
        tokens: auth.list(), errors: log.recentErrors, listeners: addrs(), fingerprint: tls.fingerprint,
      });
    }
    if (p === '/ctl/pair' && method === 'POST') {
      const r = auth.startPairing();
      log.write({ kind: 'ctl', op: 'pair_start', ok: true });
      return send(res, 200, { ok: true, code: r.code, expiresAt: r.expiresAt, addresses: addrs().filter((a) => !a.startsWith('127.')), fingerprint: tls.fingerprint });
    }
    if (p === '/ctl/revoke' && method === 'POST') {
      if (typeof body.id !== 'string') throw new RelayError('bad_args', 'id required');
      const n = auth.revoke(body.id);
      if (n === null) throw new RelayError('bad_args', "id must be 'all' or exactly 6 hex characters (see dsh-relay status)");
      log.write({ kind: 'ctl', op: 'revoke', n, ok: true });
      return send(res, 200, { ok: true, revoked: n });
    }
    if (p === '/ctl/probe' && method === 'POST') {
      // doctor 用：同步连一次设备（最多 8 秒），返回连接结果
      let error = null;
      if (!halted()) {
        try {
          device.markStale();
          lastKickAt = 0;
          await kickAcquire({ force: true });
        } catch (e) {
          error = e.code ?? 'internal';
        }
      }
      return send(res, 200, { ok: true, halted: halted(), device: device.status(), error });
    }
    if (p === '/ctl/halt' && method === 'POST') {
      const n = doHalt();
      log.write({ kind: 'ctl', op: 'halt', ok: true, source: 'cli' });
      return send(res, 200, { ok: true, halted: true, cancelled: n });
    }
    if (p === '/ctl/resume' && method === 'POST') {
      fs.rmSync(haltFile, { force: true });
      log.write({ kind: 'ctl', op: 'resume', ok: true });
      return send(res, 200, { ok: true, halted: false });
    }
    if (p === '/ctl/target' && method === 'POST') {
      if (body.addr === null || body.addr === 'auto') device.setTcpAddr(null);
      else if (validTcpAddr(body.addr)) device.setTcpAddr(body.addr);
      else throw new RelayError('bad_args', 'addr must be private IPv4:port');
      log.write({ kind: 'ctl', op: 'target', ok: true, source: 'cli' });
      return send(res, 200, { ok: true, addr: device.tcpAddr });
    }
    return send(res, 404, { ok: false, error: { code: 'bad_args', message: 'unknown ctl path', retryable: false } });
  }

  function makeHandler(plain) {
    return async (req, res) => {
      const ip = normalizeRemote(req.socket.remoteAddress);
      try {
        if (!sourceAllowed(ip)) {
          log.write({ kind: 'auth', op: 'forbidden_source', ip, ok: false });
          throw new RelayError('forbidden_source', 'source address not allowed');
        }
        if (!hostOk(req.headers.host)) throw new RelayError('forbidden_source', 'host header not allowed');
        const url = new URL(req.url, 'http://x');
        const method = req.method;
        if (url.pathname.startsWith('/ctl/')) {
          // 管理接口只在回环明文口、来源为回环时可见
          if (!plain || !(isLoopback(ip) || ip === '::1')) {
            return send(res, 404, { ok: false, error: { code: 'bad_args', message: 'unknown path', retryable: false } });
          }
          return await handleCtl(req, res, { url, method });
        }
        return await handleV1(req, res, { ip, url, method });
      } catch (e) {
        const err = e instanceof RelayError ? e : new RelayError('internal', 'internal error', { hint: String(req.headers['x-request-id'] ?? '') });
        if (!(e instanceof RelayError)) log.write({ kind: 'ctl', op: 'internal_error', ok: false, msg: String(e?.message).slice(0, 200) });
        if (!res.headersSent) send(res, err.status, err.toBody());
      }
    };
  }

  // ---------- 监听 ----------

  const listeners = []; // {address, port, server, plain}
  let rebindTimer = null;
  let keepaliveTimer = null;
  const interfaces = opts.interfaces ?? (() => os.networkInterfaces());
  const port = opts.port ?? Number(process.env.DSH_RELAY_PORT ?? 8731);
  const ctlPort = opts.ctlPort ?? (process.env.DSH_RELAY_CTL_PORT ? Number(process.env.DSH_RELAY_CTL_PORT) : port === 0 ? 0 : port + 1);
  for (const [name, v] of [['DSH_RELAY_PORT', port], ['管理口（DSH_RELAY_CTL_PORT 或端口+1）', ctlPort]]) {
    if (!Number.isInteger(v) || v < 0 || v > 65535) throw new StartupError(`${name} 无效：${v}，需要 0–65535 的整数（管理口默认是服务端口+1，所以服务端口最大 65534）`);
  }
  const bindErrors = new Map(); // `${address}:${port}` → 最近一次错误码，同一错误只记一次日志

  function bindOne(address, p, plain) {
    return new Promise((resolve) => {
      const server = plain
        ? http.createServer(makeHandler(true))
        : https.createServer({ key: tls.key, cert: tls.cert, minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2' }, makeHandler(false));
      server.requestTimeout = 30_000;
      server.headersTimeout = 10_000;
      server.once('error', (e) => {
        const k = `${address}:${p}`;
        if (bindErrors.get(k) !== e.code) {
          bindErrors.set(k, e.code);
          log.write({ kind: 'ctl', op: 'bind_failed', address, port: p, plain, code: e.code, ok: false });
          console.error(`dsh-relay: 绑定 ${plain ? 'http' : 'https'}://${address}:${p} 失败（${e.code}）${e.code === 'EADDRINUSE' ? '，端口被别的进程占用' : e.code === 'EACCES' ? '，没有权限' : ''}`);
        }
        resolve(null);
      });
      server.listen(p, address, () => {
        bindErrors.delete(`${address}:${p}`);
        const l = { address, port: server.address().port, server, plain };
        listeners.push(l);
        resolve(l);
      });
    });
  }

  async function syncListeners() {
    // 回环永远有；私网与 Tailscale 网卡地址随网卡变化增减，不绑 0.0.0.0，不绑 198.18/15
    const want = new Set(['127.0.0.1', ...(opts.loopbackOnly ? [] : listenAddresses(interfaces()))]);
    for (const l of [...listeners]) {
      if (!l.plain && !want.has(l.address)) {
        l.server.close();
        l.server.closeAllConnections?.();
        listeners.splice(listeners.indexOf(l), 1);
        log.write({ kind: 'ctl', op: 'unbind', address: l.address, ok: true });
      }
    }
    for (const a of want) {
      if (!listeners.some((l) => !l.plain && l.address === a)) await bindOne(a, port, false);
    }
    // 管理口也纳入重绑：被别的进程占着时每轮重试，不再永久缺失
    if (!listeners.some((l) => l.plain)) await bindOne('127.0.0.1', ctlPort, true);
  }

  async function start() {
    await syncListeners();
    if (!listeners.some((l) => !l.plain)) {
      const codes = [...bindErrors.entries()].map(([k, c]) => `${k} ${c}`).join('；');
      await stop();
      throw new StartupError(`HTTPS 端口一个都没绑上（${codes || '无可用地址'}），服务无法使用，退出`, 1);
    }
    if (!listeners.some((l) => l.plain)) {
      console.error(`dsh-relay: 管理口 127.0.0.1:${ctlPort} 没绑上，dsh-relay 命令暂时不可用，每 15 秒重试`);
    }
    if (opts.rebindMs !== 0) {
      rebindTimer = setInterval(() => syncListeners().catch(() => {}), opts.rebindMs ?? 15_000);
      rebindTimer.unref();
    }
    if (opts.keepalive !== false) {
      keepaliveTimer = setInterval(() => {
        if (halted() || hdc.depth() > 0 || !device.target || now() - device.lastActiveAt > 120_000) return;
        hdc
          .submit({ kind: 'keepalive', timeoutMs: 3000 }, async (ctx) => {
            await device.shell(ctx, device.target, 'echo ok', { timeoutMs: 3000 });
            device.verifiedAt = now();
          })
          .catch(() => device.markStale());
      }, 5000);
      keepaliveTimer.unref();
    }
    log.write({ kind: 'ctl', op: 'start', listeners: listeners.map((l) => `${l.address}:${l.port}`), ok: true });
    if (opts.probeHdc !== false) probeHdc();
    // 启动后有绑定目标就主动连一次，手机端第一次「测试连接」时不必再等
    if (opts.warmStart !== false && (device.bound.serial || device.bound.addr)) kickAcquire({ force: true });
    return listeners.map((l) => ({ address: l.address, port: l.port, plain: l.plain }));
  }

  /** 启动时探测 hdc 一次并留一行日志：路径错了或不可执行，第一时间能在 relay.log 里看到 */
  function probeHdc() {
    execFile(hdc.bin, ['-v'], { timeout: 3000, encoding: 'utf8' }, (err, stdout) => {
      if (err) {
        log.write({ kind: 'ctl', op: 'hdc_probe', bin: hdc.bin, code: err.code ?? (err.killed ? 'TIMEOUT' : 'EXIT'), ok: false });
        console.error(`dsh-relay: hdc 不可用（${hdc.bin}，${err.code ?? 'exit'}），读屏和操作都会失败；检查 launchd 作业里的 HDC_BIN 与 PATH`);
      } else {
        log.write({ kind: 'ctl', op: 'hdc_probe', bin: hdc.bin, ver: String(stdout).trim().slice(0, 60), ok: true });
      }
    });
  }

  async function stop() {
    clearInterval(rebindTimer);
    clearInterval(keepaliveTimer);
    hdc.cancelAll();
    await Promise.all(
      listeners.splice(0).map(
        (l) => new Promise((r) => {
          l.server.close(() => r());
          l.server.closeAllConnections?.();
        }),
      ),
    );
  }

  return {
    start, stop, auth, device, hdc, log, tls, home, config,
    listeners, halted, doHalt, syncListeners,
    https: () => listeners.find((l) => !l.plain && l.address === '127.0.0.1'),
    ctl: () => listeners.find((l) => l.plain),
  };
}

export { sha256hex };
