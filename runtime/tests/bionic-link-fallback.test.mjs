// bionic 硬链接回退（runtime/bionic/link-fallback.mjs）的测试。
//   node --test runtime/tests/bionic-link-fallback.test.mjs
import assert from 'node:assert/strict';
import {test} from 'node:test';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {installLinkFallback} from '../bionic/link-fallback.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..', '..');
const moduleFile = join(repo, 'runtime/bionic/link-fallback.mjs');

// 真实 fs 上的一份拷贝，只把三种 link 换成「容器里的样子」：按给定错误码拒绝。
function refusingFs(code) {
  const deny = () => Object.assign(new Error(`${code}: link refused`), {code});
  const target = {...fs, promises: {...fs.promises}};
  target.linkSync = () => { throw deny(); };
  target.promises.link = async () => { throw deny(); };
  target.link = (_a, _b, cb) => queueMicrotask(() => cb(deny()));
  installLinkFallback(target);
  return target;
}

function scratch() {
  const dir = fs.mkdtempSync(join(tmpdir(), 'dsh-link-'));
  const staged = join(dir, 'session.tmp');
  fs.writeFileSync(staged, 'generation-1');
  return {dir, staged, current: join(dir, 'session.jsonl.zstd')};
}

for (const code of ['EACCES', 'EPERM']) {
  test(`${code}：三种 link 都退回到独占复制，暂存文件保留`, async () => {
    const t = refusingFs(code);
    const {dir, staged, current} = scratch();
    try {
      await t.promises.link(staged, current);
      assert.equal(fs.readFileSync(current, 'utf8'), 'generation-1');
      assert.ok(fs.existsSync(staged));
      fs.rmSync(current);
      t.linkSync(staged, current);
      assert.equal(fs.readFileSync(current, 'utf8'), 'generation-1');
      fs.rmSync(current);
      await new Promise((ok, fail) => t.link(staged, current, (e) => (e ? fail(e) : ok())));
      assert.equal(fs.readFileSync(current, 'utf8'), 'generation-1');
    } finally {
      fs.rmSync(dir, {recursive: true, force: true});
    }
  });
}

test('目标已存在：回退后照样报 EEXIST，已有内容不被覆盖', async () => {
  const t = refusingFs('EACCES');
  const {dir, staged, current} = scratch();
  try {
    fs.writeFileSync(current, 'winner');
    await assert.rejects(t.promises.link(staged, current), {code: 'EEXIST'});
    assert.throws(() => t.linkSync(staged, current), {code: 'EEXIST'});
    await assert.rejects(new Promise((ok, fail) => t.link(staged, current, (e) => (e ? fail(e) : ok()))), {code: 'EEXIST'});
    assert.equal(fs.readFileSync(current, 'utf8'), 'winner');
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('其他错误原样抛出，不走复制', async () => {
  const t = refusingFs('ENOSPC');
  const {dir, staged, current} = scratch();
  try {
    await assert.rejects(t.promises.link(staged, current), {code: 'ENOSPC'});
    assert.throws(() => t.linkSync(staged, current), {code: 'ENOSPC'});
    assert.equal(fs.existsSync(current), false);
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});

test('重复安装不叠加包装', () => {
  const t = refusingFs('EACCES');
  const wrapped = t.promises.link;
  installLinkFallback(t);
  assert.equal(t.promises.link, wrapped);
});

test('经 NODE_OPTIONS 加载：execArgv 为空的 worker 与 ESM 具名导入都换成回退版本', () => {
  const dir = fs.mkdtempSync(join(tmpdir(), 'dsh-link-wiring-'));
  try {
    const worker = join(dir, 'worker.cjs');
    fs.writeFileSync(worker, `
      const p = require('node:fs/promises');
      require('node:worker_threads').parentPort.postMessage(p.link.name);
    `);
    const main = join(dir, 'main.mjs');
    fs.writeFileSync(main, `
      import { link } from 'node:fs/promises';
      import { Worker } from 'node:worker_threads';
      const w = new Worker(${JSON.stringify(worker)}, { execArgv: [] });
      w.on('message', (name) => { console.log(JSON.stringify({ main: link.name, worker: name })); });
    `);
    const run = spawnSync(process.execPath, [main], {
      encoding: 'utf8',
      env: {...process.env, NODE_OPTIONS: `--dns-result-order=ipv4first --import ${moduleFile}`},
    });
    assert.equal(run.status, 0, run.stderr);
    assert.deepEqual(JSON.parse(run.stdout.trim()), {main: 'linkWithFallback', worker: 'linkWithFallback'});
  } finally {
    fs.rmSync(dir, {recursive: true, force: true});
  }
});
