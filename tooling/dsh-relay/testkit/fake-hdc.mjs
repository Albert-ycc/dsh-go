#!/usr/bin/env node
// 假 hdc：按 FAKE_HDC_DIR 里的状态文件应答，覆盖中继用到的全部子命令。
// shell 里的 `uitest uiInput ...` 交给真实的 /bin/sh 解释，由 bin/uitest 记录 argv，
// 因此命令注入用例验证的是「中继转义后的字符串被 shell 解析出的真实参数」。
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const dir = process.env.FAKE_HDC_DIR;
const args = process.argv.slice(2);
const rec = (o) => fs.appendFileSync(path.join(dir, 'calls.log'), JSON.stringify({ t: Date.now(), pid: process.pid, ...o }) + '\n');
const dev = (p) => path.join(dir, 'dev', p);
const readJson = (n, d) => { try { return JSON.parse(fs.readFileSync(path.join(dir, n), 'utf8')); } catch { return d; } };
const done = (out = '', code = 0) => { rec({ ev: 'end', args }); process.stdout.write(out); process.exit(code); };

rec({ ev: 'start', args });
let a = args;
let target = null;
if (a[0] === '-t') { target = a[1]; a = a.slice(2); }

if (a[0] === 'list') done(fs.readFileSync(path.join(dir, 'targets.txt'), 'utf8'));
if (a[0] === 'tconn') done('Connect OK\n');
if (a[0] === '-v') done('Ver: 3.1.0e\n');

if (a[0] === 'file' && a[1] === 'recv') {
  const src = dev(a[2]);
  if (!fs.existsSync(src)) done('[Fail]no such file\n', 1);
  fs.copyFileSync(src, a[3]);
  done(`FileTransfer finish, Size:${fs.statSync(src).size}, File count = 1\n`);
}

if (a[0] === 'shell') {
  const cmd = a[1];
  const behaviors = readJson('behaviors.json', []);
  const b = behaviors.find((x) => cmd.includes(x.match) && !(x.times !== undefined && x.used >= x.times));
  const finish = (fn) => {
    if (b?.sleepMs) setTimeout(fn, b.sleepMs); else fn();
  };
  if (b?.times !== undefined) { b.used = (b.used ?? 0) + 1; fs.writeFileSync(path.join(dir, 'behaviors.json'), JSON.stringify(behaviors)); }
  finish(() => {
    if (b?.hang) return setInterval(() => {}, 1e6); // 永不返回，等中继超时杀掉
    if (b?.bigMB) { // 输出 bigMB 兆字节垃圾，验证中继的输出上限
      const chunk = 'x'.repeat(1024 * 1024);
      let i = 0;
      const w = () => { while (i < b.bigMB) { i++; if (!process.stdout.write(chunk)) return process.stdout.once('drain', w); } process.exit(b.exit ?? 0); };
      return w();
    }
    if (b?.out !== undefined) return done(b.out, b.exit ?? 0);
    if (cmd.startsWith('uitest uiInput')) {
      const bin = path.join(dir, 'bin');
      const child = spawn('/bin/sh', ['-c', cmd], { env: { ...process.env, PATH: `${bin}:${process.env.PATH}` }, stdio: ['ignore', 'pipe', 'pipe'] });
      let out = '';
      child.stdout.on('data', (d) => (out += d));
      child.stderr.on('data', (d) => (out += d));
      return child.on('close', () => done(out));
    }
    let m;
    if (/^echo ok; param get const\.product\.model/.test(cmd)) return done('ok\nVDE-AL00\n');
    if (cmd.startsWith('rm ')) { fs.rmSync(dev(cmd.split(' ').pop()), { recursive: true, force: true }); return done(''); }
    if ((m = /uitest dumpLayout(?: -b '([^']*)')? -p (\S+)$/.exec(cmd))) {
      if (m[1]) rec({ ev: 'bundle', bundle: m[1] });
      fs.mkdirSync(path.dirname(dev(m[2])), { recursive: true });
      fs.copyFileSync(path.join(dir, 'dump.json'), dev(m[2]));
      return done(`DumpLayout saved to:${m[2]}\n`);
    }
    if ((m = /snapshot_display -f (\S+)$/.exec(cmd))) {
      fs.mkdirSync(path.dirname(dev(m[1])), { recursive: true });
      fs.writeFileSync(dev(m[1]), Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]));
      return done(`process: display 0\nsuccess: snapshot display 0 , write to ${m[1]} as jpeg, width: 1320, height: 2120\n`);
    }
    if (cmd.startsWith('bm dump -n')) return done(`${cmd.split("'")[1]}:\n` + fs.readFileSync(path.join(dir, 'bm-n.json'), 'utf8'));
    if (cmd === 'bm dump -a') return done('ID: 100:\n\tcom.huawei.hmos.settings\n\tcom.huawei.hmos.calculator\n\tcn.example.app\n');
    if (cmd.startsWith('aa start')) return done('start ability successfully.\n');
    if (cmd.startsWith('param get persist.hdc.port')) return done('Get parameter "persist.hdc.port" fail! errNum is:1002!\nwlan0 inet addr:192.168.31.226\n');
    return done(`fake-hdc: unhandled ${cmd}\n`, 0);
  });
} else if (!['list', 'tconn', 'file', '-v'].includes(a[0])) {
  done('[Fail]unknown\n', 1);
}
