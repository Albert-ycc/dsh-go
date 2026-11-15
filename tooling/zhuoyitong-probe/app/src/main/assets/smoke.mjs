// P2-5 真机冒烟：S1–S11。驱动模式下每项在独立 node 子进程里跑，
// 这样某项被 SIGSYS 杀掉只记那一项（退出信号 → 128+n，SIGSYS = 159），后续项照跑。
// 用法：node smoke.mjs            跑全部并起 HTTP 返回 JSON 报告
//       node smoke.mjs --item S3  只跑一项（驱动内部使用，也可手动调试）
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import http from 'node:http';
import dgram from 'node:dgram';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { Worker } from 'node:worker_threads';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EXT = process.env.SMOKE_EXT || path.join(HERE, 'ext');
const OUT = process.env.SMOKE_OUT || HERE;
const now = () => Number(process.hrtime.bigint() / 1000000n);
const clip = (s, n = 2048) => (s.length > n ? s.slice(0, n) + `…[+${s.length - n}]` : s);

// ---------- 子进程封装：记录退出码 / 信号 ----------
function exitNumber(code, signal) {
  if (signal) return 128 + (os.constants.signals[signal] ?? 0);
  return code;
}
function run(cmd, args = [], opts = {}) {
  return new Promise((resolve) => {
    const t0 = now();
    let out = '';
    let p;
    try { p = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], ...opts }); } catch (e) { return resolve({ code: null, signal: null, exit: -1, ms: now() - t0, out: `spawn 同步抛错 ${e.code} ${e.message}` }); }
    const cap = opts.maxOut ?? 65536;
    const add = (d) => { if (out.length < cap) out += d; };
    p.stdout.on('data', add); p.stderr.on('data', add);
    const timer = setTimeout(() => p.kill('SIGKILL'), opts.timeoutMs ?? 60000);
    p.on('error', (e) => { clearTimeout(timer); resolve({ code: null, signal: null, exit: -1, ms: now() - t0, out: out + String(e) }); });
    p.on('close', (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal, exit: exitNumber(code, signal), ms: now() - t0, out });
    });
  });
}

// ---------- 单项内的步骤记录 ----------
const steps = [];
async function step(name, fn) {
  const t0 = now();
  try {
    const v = await fn();
    const s = typeof v === 'string' ? v : JSON.stringify(v);
    steps.push({ name, ok: true, ms: now() - t0, value: clip(s ?? '', 400) });
    console.log(`  ok   ${name}: ${clip(s ?? '', 200)}`);
    return v;
  } catch (e) {
    steps.push({ name, ok: false, ms: now() - t0, error: `${e?.code ?? ''} ${e?.message ?? e}`.trim() });
    console.log(`  FAIL ${name}: ${e?.code ?? ''} ${e?.message ?? e}`);
    return undefined;
  }
}
const need = (cond, msg) => { if (!cond) throw new Error(msg); };
function tmpRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'smoke-'));
}

// ---------- S1–S11 ----------
const ITEMS = [];
const item = (id, name, cmd, criteria, p0b, fn) => ITEMS.push({ id, name, cmd, criteria, p0b, fn });

item('S1', '启动与基本信息',
  'node -p "[version,platform,arch,execPath]"；node --expose-internals -e "require(\'internal/modules/esm/loader\')"',
  '输出 v24.22.1 android arm64 <路径>；第二条不抛错；进程正常退出', '99、293；E1 的 createRequire 取 internal',
  async () => {
    const info = await step('self', () => [process.version, process.platform, process.arch, process.execPath].join(' '));
    await step('uptime_ms_at_item_start', () => Math.round(process.uptime() * 1000));
    const r1 = await run(process.execPath, ['-p', '[process.version,process.platform,process.arch,process.execPath].join(" ")']);
    await step('child -p', () => { need(r1.exit === 0, `exit ${r1.exit} ${r1.out}`); return r1.out.trim(); });
    // 冷启动耗时：连跑 3 次 node -p 1（T1 关了 snapshot，启动会比官方慢）
    const times = [];
    for (let i = 0; i < 3; i++) times.push((await run(process.execPath, ['-p', '1'])).ms);
    await step('cold_start_ms x3', () => times.join(','));
    const r2 = await run(process.execPath, ['--expose-internals', '-e', "const m=require('internal/modules/esm/loader');console.log(Object.keys(m).slice(0,5).join(','))"]);
    await step('--expose-internals require internal/modules/esm/loader', () => { need(r2.exit === 0, `exit ${r2.exit} ${r2.out}`); return r2.out.trim(); });
    await step('createRequire + internal (E1 形态)', async () => {
      const r = await run(process.execPath, ['--expose-internals', '-e', "const {createRequire}=require('module');const r=createRequire(process.cwd()+'/x.js');console.log(typeof r('internal/modules/esm/loader'))"]);
      need(r.exit === 0, `exit ${r.exit} ${r.out}`); return r.out.trim();
    });
    await step('版本号为 v24.22.1', () => { need(process.version === 'v24.22.1', process.version); return process.version; });
    await step('platform=android arch=arm64', () => { need(process.platform === 'android' && process.arch === 'arm64', `${process.platform} ${process.arch}`); return 'ok'; });
    await step('versions', () => JSON.stringify({ v8: process.versions.v8, uv: process.versions.uv, openssl: process.versions.openssl, modules: process.versions.modules }));
    return info;
  });

item('S2', '事件循环与线程池',
  'setTimeout、fs.promises.readFile、crypto.randomBytes(cb)、zlib.gzipSync/gzip', '全部完成，无 SIGSYS', '425–427、278',
  async () => {
    await step('setTimeout 50ms', () => new Promise((res) => { const t = now(); setTimeout(() => res(`${now() - t}ms`), 50); }));
    await step('fs.promises.readFile', async () => (await fs.promises.readFile(process.execPath.length ? fileURLToPath(import.meta.url) : '')).length + ' bytes');
    await step('crypto.randomBytes(cb) 线程池', () => new Promise((res, rej) => crypto.randomBytes(32, (e, b) => (e ? rej(e) : res(b.length)))));
    await step('crypto.pbkdf2 线程池', () => new Promise((res, rej) => crypto.pbkdf2('a', 'b', 1000, 32, 'sha256', (e, b) => (e ? rej(e) : res(b.length)))));
    await step('zlib.gzipSync', () => zlib.gzipSync(Buffer.alloc(1 << 20, 1)).length);
    await step('zlib.gzip 异步', () => new Promise((res, rej) => zlib.gzip(Buffer.alloc(1 << 20, 2), (e, b) => (e ? rej(e) : res(b.length)))));
    await step('dns.lookup localhost 线程池', async () => JSON.stringify(await (await import('node:dns')).promises.lookup('localhost')));
  });

item('S3', '文件系统调用',
  'access R_OK|W_OK、chmod、copyFile(含 COPYFILE_EXCL)、realpath、rename、mkdtemp、promises.cp', '全部成功', '439 faccessat2、452 fchmodat2、437 openat2；71 / 285',
  async () => {
    const d = await step('os.tmpdir 下 mkdtemp', () => tmpRoot());
    need0(d);
    const f = path.join(d, 'a.txt');
    await step('writeFile', () => { fs.writeFileSync(f, 'hello'); return 'ok'; });
    await step('access R_OK|W_OK', () => { fs.accessSync(f, fs.constants.R_OK | fs.constants.W_OK); return 'ok'; });
    await step('fs.access cb', () => new Promise((res, rej) => fs.access(f, fs.constants.R_OK | fs.constants.W_OK, (e) => (e ? rej(e) : res('ok')))));
    await step('chmod 0755 + stat', () => { fs.chmodSync(f, 0o755); return (fs.statSync(f).mode & 0o777).toString(8); });
    await step('copyFile', () => { fs.copyFileSync(f, path.join(d, 'b.txt')); return 'ok'; });
    await step('copyFile COPYFILE_EXCL 目标已存在应抛 EEXIST', () => {
      try { fs.copyFileSync(f, path.join(d, 'b.txt'), fs.constants.COPYFILE_EXCL); } catch (e) { need(e.code === 'EEXIST', e.code); return e.code; }
      throw new Error('未抛错');
    });
    await step('copyFile EXCL 新目标', () => { fs.copyFileSync(f, path.join(d, 'c.txt'), fs.constants.COPYFILE_EXCL); return 'ok'; });
    await step('realpath', () => fs.realpathSync(d));
    await step('realpath.native', () => fs.realpathSync.native(d));
    await step('rename', () => { fs.renameSync(path.join(d, 'c.txt'), path.join(d, 'd.txt')); return 'ok'; });
    await step('mkdtemp', () => fs.mkdtempSync(path.join(d, 'x-')));
    await step('promises.cp 递归', async () => {
      fs.mkdirSync(path.join(d, 'src/sub'), { recursive: true }); fs.writeFileSync(path.join(d, 'src/sub/z'), 'z');
      await fs.promises.cp(path.join(d, 'src'), path.join(d, 'dst'), { recursive: true });
      need(fs.readFileSync(path.join(d, 'dst/sub/z'), 'utf8') === 'z', '内容不符'); return 'ok';
    });
    await step('大文件 copyFile 8MB', () => { const p = path.join(d, 'big'); fs.writeFileSync(p, Buffer.alloc(8 << 20, 7)); fs.copyFileSync(p, p + '2'); return fs.statSync(p + '2').size; });
    fs.rmSync(d, { recursive: true, force: true });
  });
function need0(v) { if (!v) throw new Error('前置步骤失败'); }

item('S4', '本地监听与回环',
  'net.createServer.listen(0,127.0.0.1) 自连收发；http.createServer + fetch；dgram 回环', '成功', '202 accept（libuv 用 242 accept4）',
  async () => {
    await step('net 自连收发', () => new Promise((res, rej) => {
      const s = net.createServer((c) => c.on('data', (b) => c.end('echo:' + b)));
      s.listen(0, '127.0.0.1', () => {
        const c = net.connect(s.address().port, '127.0.0.1', () => c.write('ping'));
        let got = ''; c.on('data', (b) => (got += b)); c.on('error', rej);
        c.on('close', () => { s.close(); got === 'echo:ping' ? res(got) : rej(new Error('got ' + got)); });
      });
    }));
    await step('http.createServer + fetch', async () => {
      const s = http.createServer((q, r) => r.end('hi ' + q.url));
      await new Promise((r) => s.listen(0, '127.0.0.1', r));
      const t = await (await fetch(`http://127.0.0.1:${s.address().port}/x`)).text();
      s.close(); need(t === 'hi /x', t); return t;
    });
    await step('dgram 回环', () => new Promise((res, rej) => {
      const a = dgram.createSocket('udp4'); const b = dgram.createSocket('udp4');
      const to = setTimeout(() => rej(new Error('超时')), 3000);
      a.on('message', (m) => { clearTimeout(to); a.close(); b.close(); res(m.toString()); });
      a.bind(0, '127.0.0.1', () => b.send('dg', a.address().port, '127.0.0.1'));
    }));
  });

item('S5', '外网与 TLS', "fetch('https://api.deepseek.com/')", 'HTTP 401，无超时', '解析走 bionic getaddrinfo；278 getrandom',
  async () => {
    const t = now();
    await step('fetch https://api.deepseek.com/', async () => {
      const r = await fetch('https://api.deepseek.com/', { signal: AbortSignal.timeout(20000) });
      need(r.status === 401, `HTTP ${r.status}`); return `HTTP ${r.status} ${now() - t}ms`;
    });
    await step('dns.lookup api.deepseek.com', async () => JSON.stringify(await (await import('node:dns')).promises.lookup('api.deepseek.com', { all: true })));
  });

item('S6', '线程', 'worker_threads 4 个 worker 计算 + postMessage；Atomics.wait + SharedArrayBuffer', '4 个都返回结果', '99、98',
  async () => {
    const code = `const {parentPort,workerData}=require('worker_threads');let s=0;for(let i=0;i<3e6;i++)s=(s+i*workerData.k)%1000003;parentPort.postMessage({k:workerData.k,s})`;
    await step('4 个 worker', async () => {
      const rs = await Promise.all([1, 2, 3, 4].map((k) => new Promise((res, rej) => {
        const w = new Worker(code, { eval: true, workerData: { k } });
        w.on('message', res); w.on('error', rej);
      })));
      need(rs.length === 4, 'n=' + rs.length); return JSON.stringify(rs);
    });
    await step('Atomics.wait + SAB（worker 唤醒主线程）', async () => {
      const ia = new Int32Array(new SharedArrayBuffer(8));
      const w = new Worker(`const {workerData}=require('worker_threads');const a=new Int32Array(workerData);setTimeout(()=>{Atomics.store(a,0,1);Atomics.notify(a,0)},100)`, { eval: true, workerData: ia.buffer });
      const r = Atomics.wait(ia, 0, 0, 5000); await new Promise((res) => w.on('exit', res));
      need(r === 'ok', r); return r;
    });
  });

item('S7', '子进程',
  "spawn(execPath,-e)、spawn(/system/bin/sh,-c 'echo hi; id; uname -a')、detached + kill(-pid)、execFileSync('tar',['--version'])", '都成功，进程组 kill 有效', '221 execve、220 clone、157 setsid；144–152、159 不应触发',
  async () => {
    await step('spawn(execPath, -e console.log(1))', async () => {
      const r = await run(process.execPath, ['-e', 'console.log(1)']); need(r.exit === 0 && r.out.trim() === '1', `exit ${r.exit} ${r.out}`); return `exit ${r.exit} out ${r.out.trim()}`;
    });
    await step('spawn /system/bin/sh -c echo/id/uname', async () => {
      const sh = fs.existsSync('/system/bin/sh') ? '/system/bin/sh' : '/bin/sh';
      const r = await run(sh, ['-c', 'echo hi; id; uname -a']); need(r.exit === 0 && r.out.startsWith('hi'), `exit ${r.exit} ${r.out}`); return `${sh} exit ${r.exit}: ${r.out.replace(/\n/g, ' | ')}`;
    });
    await step('detached + process.kill(-pid, SIGTERM)', () => new Promise((res, rej) => {
      const p = spawn('/bin/sh', ['-c', 'sleep 60 & sleep 60'], { detached: true, stdio: 'ignore' });
      p.on('error', rej);
      p.on('exit', (code, signal) => {
        setTimeout(() => {
          try { process.kill(-p.pid, 0); rej(new Error('进程组仍存活')); } catch (e) { e.code === 'ESRCH' ? res(`signal=${signal} 组已空 ESRCH`) : rej(e); }
        }, 300);
      });
      setTimeout(() => process.kill(-p.pid, 'SIGTERM'), 300);
    }));
    await step("execFileSync('tar', ['--version'])", () => execFileSync('tar', ['--version'], { encoding: 'utf8' }).split('\n')[0]);
    await step('PATH', () => process.env.PATH);
  });

item('S8', 'WebAssembly 与 JIT',
  'WebAssembly.validate + 实例化小模块；5 秒热循环；require(sharp) 缩放 PNG（wasm32）', '都成功，sharp.versions 可读', '无直接号；验证可执行页 mmap/mprotect PROT_EXEC',
  async () => {
    const bytes = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 7, 1, 96, 2, 127, 127, 1, 127, 3, 2, 1, 0, 7, 7, 1, 3, 97, 100, 100, 0, 0, 10, 9, 1, 7, 0, 32, 0, 32, 1, 106, 11]);
    await step('WebAssembly.validate', () => { need(WebAssembly.validate(bytes), 'invalid'); return 'true'; });
    await step('WebAssembly 实例化 add(2,3)', async () => { const { instance } = await WebAssembly.instantiate(bytes); const v = instance.exports.add(2, 3); need(v === 5, v); return v; });
    await step('5 秒热循环', () => {
      const end = now() + 5000; let n = 0, s = 0;
      while (now() < end) { for (let i = 0; i < 1e6; i++) s = (s + i * 31) | 0; n++; }
      return `${n} 轮 x 1e6, s=${s}`;
    });
    await step('process.execArgv / v8 flags', () => JSON.stringify({ execArgv: process.execArgv, jitless: process.config.variables.v8_jitless ?? null }));
    let sharp;
    await step('require(sharp)', () => {
      const req = createRequire(path.join(EXT, 'x.js')); sharp = req('sharp'); need(typeof sharp === 'function', typeof sharp); return typeof sharp;
    });
    if (sharp) {
      await step('sharp.versions', () => JSON.stringify(sharp.versions));
      await step('sharp 生成 PNG 并缩放', async () => {
        const png = await sharp({ create: { width: 64, height: 48, channels: 3, background: { r: 255, g: 0, b: 0 } } }).png().toBuffer();
        const small = await sharp(png).resize(16, 12).png().toBuffer(); const m = await sharp(small).metadata();
        need(m.width === 16 && m.height === 12, JSON.stringify(m)); return `png ${png.length}B -> ${small.length}B ${m.width}x${m.height}`;
      });
    }
  });

item('S9', '系统信息',
  'os.tmpdir 可写、homedir、cpus、networkInterfaces、userInfo、fs.watch、stat(tmp).uid === geteuid()', '都不抛错；cpus 非空', '26–28 inotify；netlink 受限时 networkInterfaces 的表现',
  async () => {
    const d = await step('os.tmpdir 可写', () => { const t = tmpRoot(); return t; });
    await step('os.homedir', () => os.homedir());
    await step('os.cpus().length', () => { const n = os.cpus().length; need(n > 0, 'cpus 为空'); return n; });
    await step('诊断 /proc/stat 与 /proc/cpuinfo 可读性', () => ['/proc/stat', '/proc/cpuinfo', '/sys/devices/system/cpu/present'].map((f) => { try { return `${f}=${fs.readFileSync(f, 'utf8').length}B`; } catch (e) { return `${f}=${e.code}`; } }).join(' '));
    await step('os.availableParallelism', () => os.availableParallelism());
    await step('os.networkInterfaces', () => JSON.stringify(Object.fromEntries(Object.entries(os.networkInterfaces()).map(([k, v]) => [k, v.map((a) => a.address)]))));
    await step('os.userInfo', () => JSON.stringify(os.userInfo()));
    await step('os.totalmem/freemem', () => `${os.totalmem()} / ${os.freemem()}`);
    if (d) {
      await step('fs.watch 收到事件', () => new Promise((res, rej) => {
        const w = fs.watch(d, (ev, fn) => { w.close(); res(`${ev} ${fn}`); });
        setTimeout(() => fs.writeFileSync(path.join(d, 'w.txt'), '1'), 100);
        setTimeout(() => { w.close(); rej(new Error('3s 内没有事件')); }, 3000);
      }));
      await step('stat(tmp).uid === geteuid()', () => {
        const u = fs.statSync(d).uid, e = process.geteuid?.(); need(typeof e === 'number', 'geteuid 不存在'); need(u === e, `uid ${u} euid ${e}`); return `${u}`;
      });
      fs.rmSync(d, { recursive: true, force: true });
    }
  });

item('S10', 'flock 原生件',
  'process.dlopen 加载 filesDir 下 bionic/system.node，对同一文件两个 fd 各 tryLock', '第一次成功，第二次 EAGAIN；dlopen 成功', '32 flock；dlopen 目录限制与 napi_* 符号可见性',
  async () => {
    const so = path.join(EXT, 'bionic', 'system.node');
    await step('system.node 存在', () => { need(fs.existsSync(so), so + ' 不存在'); return `${fs.statSync(so).size} B @ ${so}`; });
    const mod = await step('process.dlopen', () => { const m = { exports: {} }; process.dlopen(m, so); return m.exports; });
    if (!mod) return;
    await step('导出', () => Object.keys(mod).join(','));
    const d = tmpRoot(); const f = path.join(d, 'lock');
    fs.writeFileSync(f, '');
    const fd1 = fs.openSync(f, 'r+'), fd2 = fs.openSync(f, 'r+');
    const lock = (fd) => new Promise((res) => mod.tryLock(fd, res));
    await step('第一次 tryLock → 0', async () => { const e = await lock(fd1); need(e === 0, 'errno ' + e); return e; });
    await step('第二次 tryLock → EAGAIN(11)', async () => { const e = await lock(fd2); need(e === 11, 'errno ' + e); return e; });
    fs.closeSync(fd1); fs.closeSync(fd2); fs.rmSync(d, { recursive: true, force: true });
  });

item('S11', 'ripgrep',
  'rg --version；3000 个小文件目录跑 rg -j1 foo 与 rg -j4 foo；rg --files', '三条都成功', '99（set_robust_list）、144/146/159（Command::uid）',
  async () => {
    const rg = path.join(EXT, 'bin', 'rg');
    await step('rg 存在', () => { need(fs.existsSync(rg), rg + ' 不存在'); return `${fs.statSync(rg).size} B`; });
    const v = await run(rg, ['--version']);
    await step('rg --version', () => { need(v.exit === 0, `exit ${v.exit} ${v.out}`); return v.out.split('\n')[0]; });
    const d = tmpRoot();
    for (let i = 0; i < 3000; i++) { const sub = path.join(d, 'd' + (i % 30)); fs.mkdirSync(sub, { recursive: true }); fs.writeFileSync(path.join(sub, `f${i}.txt`), i % 10 === 0 ? `line\nfoo ${i}\n` : `bar ${i}\n`); }
    for (const j of ['-j1', '-j4']) {
      const r = await run(rg, [j, '-c', 'foo', d]);
      await step(`rg ${j} foo（期望 300 个文件命中）`, () => { need(r.exit === 0, `exit ${r.exit} ${clip(r.out, 300)}`); const n = r.out.trim().split('\n').length; need(n === 300, 'hits ' + n); return `exit 0, ${n} 个文件, ${r.ms}ms`; });
    }
    const f = await run(rg, ['--files', d], { maxOut: 1 << 20 });
    await step('rg --files（期望 3000）', () => { need(f.exit === 0, `exit ${f.exit} ${clip(f.out, 300)}`); const n = f.out.trim().split('\n').length; need(n === 3000, 'files ' + n); return `${n} 个, ${f.ms}ms`; });
    fs.rmSync(d, { recursive: true, force: true });
  });

// ---------- 单项模式 ----------
async function runOne(id) {
  const it = ITEMS.find((x) => x.id === id);
  let pass = false;
  try { await it.fn(); pass = steps.every((s) => s.ok); } catch (e) { console.log('ITEM THREW', e?.stack || e); steps.push({ name: 'item', ok: false, error: String(e) }); }
  fs.writeFileSync(process.env.SMOKE_RESULT_FILE, JSON.stringify({ pass, steps }));
  process.exit(0);
}

// ---------- 驱动模式 ----------
async function driver() {
  const t00 = now();
  const meta = {
    started: new Date().toISOString(), node: process.version, platform: process.platform, arch: process.arch, execPath: process.execPath,
    execArgv: process.execArgv, tmpdir: os.tmpdir(), ext: EXT, UV_USE_IO_URING: process.env.UV_USE_IO_URING ?? null,
    uid: process.getuid?.(), euid: process.geteuid?.(), cwd: process.cwd(),
  };
  const results = [];
  const only = process.env.SMOKE_ONLY ? process.env.SMOKE_ONLY.split(',') : null;
  for (const it of ITEMS) {
    if (only && !only.includes(it.id)) continue;
    console.log(`== ${it.id} ${it.name}`);
    const rf = path.join(OUT, `.result-${it.id}.json`);
    try { fs.unlinkSync(rf); } catch {}
    const r = await run(process.execPath, [fileURLToPath(import.meta.url), '--item', it.id], { env: { ...process.env, SMOKE_RESULT_FILE: rf }, timeoutMs: 180000 });
    let res = null;
    try { res = JSON.parse(fs.readFileSync(rf, 'utf8')); } catch {}
    let status;
    if (r.signal === 'SIGKILL' && r.ms >= 179000) status = 'TIMEOUT';
    else if (r.signal) status = 'CRASH';
    else if (!res) status = 'CRASH';
    else status = res.pass ? 'PASS' : 'FAIL';
    results.push({
      id: it.id, name: it.name, cmd: it.cmd, criteria: it.criteria, p0b_numbers: it.p0b,
      status, exit_code: r.code, signal: r.signal, exit: r.exit, ms: r.ms,
      steps: res?.steps ?? [], output: clip(r.out),
    });
    console.log(`-- ${it.id} ${status} exit=${r.exit}${r.signal ? ' signal=' + r.signal : ''} ${r.ms}ms`);
    fs.writeFileSync(path.join(OUT, 'smoke-partial.json'), JSON.stringify({ meta, items: results }));
  }
  const report = { meta: { ...meta, total_ms: now() - t00, finished: new Date().toISOString() }, summary: Object.fromEntries(results.map((x) => [x.id, x.status])), items: results };
  const body = JSON.stringify(report, null, 1);
  fs.writeFileSync(path.join(OUT, 'smoke-report.json'), body);
  const srv = http.createServer((q, r) => { r.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }); r.end(body); });
  srv.listen(0, '0.0.0.0', () => {
    console.log(`SMOKE_SUMMARY ${JSON.stringify(report.summary)}`);
    console.log(`SMOKE_PORT ${srv.address().port}`);
    console.log('SMOKE_DONE');
  });
  setTimeout(() => process.exit(0), (Number(process.env.SMOKE_HOLD_SEC) || 1800) * 1000);
}

const ai = process.argv.indexOf('--item');
if (ai > 0) await runOne(process.argv[ai + 1]); else await driver();
