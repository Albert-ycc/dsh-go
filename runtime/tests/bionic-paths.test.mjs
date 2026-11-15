// bionic 路径边界适配器（runtime/bionic）的测试。
//   node --test runtime/tests/bionic-paths.test.mjs
import assert from 'node:assert/strict';
import {test} from 'node:test';
import {spawnSync} from 'node:child_process';
import {copyFileSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {GUEST_PREFIXES, createBionicPaths} from '../bionic/paths.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..', '..');
const R = '/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime-bionic/rootfs';
const p = createBionicPaths(R + '/');

test('前缀表与 Kotlin GuestPaths.MAPPED 逐字一致', () => {
  const kt = readFileSync(join(repo, 'app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/GuestPaths.kt'), 'utf8');
  const block = kt.slice(kt.indexOf('private val MAPPED = listOf('), kt.indexOf(')', kt.indexOf('private val MAPPED = listOf(')));
  const mapped = [...block.matchAll(/"(\/[^"]+)"/gu)].map((m) => m[1]);
  assert.deepEqual([...GUEST_PREFIXES], mapped);
});

test('toHost：guest 前缀映射到 rootfs 下，其余绝对路径与相对路径原样', () => {
  assert.equal(p.root, R);
  assert.equal(p.toHost('/root/workspace'), `${R}/root/workspace`);
  assert.equal(p.toHost('/root'), `${R}/root`);
  assert.equal(p.toHost('/root/.dsh/config.json'), `${R}/root/.dsh/config.json`);
  assert.equal(p.toHost('/tmp/x'), `${R}/tmp/x`);
  assert.equal(p.toHost('/opt/dsh-app/x'), `${R}/opt/dsh-app/x`);
  assert.equal(p.toHost('/opt/dsh/node_modules'), `${R}/opt/dsh/node_modules`);
  assert.equal(p.toHost('/etc/dsh-app/dsh-web.patch.yml'), `${R}/etc/dsh-app/dsh-web.patch.yml`);
  for (const same of ['/sdcard/Download/a.txt', '/storage/emulated/0', '/system/bin/sh', '/data/user/0/x', '/rootfs', '/tmpx', '/opt/dshx', '/', 'relative/path', './x', '']) {
    assert.equal(p.toHost(same), same, same);
  }
  assert.equal(p.toHost(`${R}/root/workspace`), `${R}/root/workspace`, '已是物理路径不再二次改写');
  assert.equal(p.toHost(undefined), undefined);
  assert.equal(p.toHost(42), 42);
});

test('toGuest 是 toHost 在 guest 前缀上的逆；非 guest 的物理路径原样', () => {
  assert.equal(p.toGuest(`${R}/root/workspace`), '/root/workspace');
  assert.equal(p.toGuest(`${R}/tmp`), '/tmp');
  assert.equal(p.toGuest(`${R}/opt/dsh-go/bionic/preload.mjs`), '/opt/dsh-go/bionic/preload.mjs');
  assert.equal(p.toGuest(`${R}/bin/node`), `${R}/bin/node`, 'rootfs 下非 guest 前缀的目录没有 guest 名字');
  assert.equal(p.toGuest('/sdcard/x'), '/sdcard/x');
  for (const g of ['/root/workspace', '/root/.dsh', '/tmp/a', '/opt/dsh-app/x', '/var/lib/dsh-app/y']) assert.equal(p.toGuest(p.toHost(g)), g);
});

test('executable / cwd / prepareDirectory / 恒定项', async () => {
  assert.equal(p.executable('/root/.dsh/bin/../bin/tool'), `${R}/root/.dsh/bin/tool`);
  assert.equal(p.executable('/system/bin/sh'), '/system/bin/sh');
  assert.equal(p.executable('node'), 'node');
  assert.equal(p.cwd('/root/workspace'), `${R}/root/workspace`);
  assert.equal(p.cwd(new URL('file:///root/workspace')), `${R}/root/workspace`);
  assert.equal(p.cwd(undefined), undefined);
  assert.equal(await p.prepareDirectory('/root/workspace'), `${R}/root/workspace`);
  assert.equal(await p.prepareDirectory('/sdcard/Documents'), '/sdcard/Documents');
  assert.equal(await p.prepareDirectory('sub'), join(process.cwd(), 'sub'));
  await assert.rejects(() => p.prepareDirectory(''), TypeError);
  assert.equal(p.requiresProot(`${R}/root/.l2s/x`), false);
  assert.equal(p.containsExecutionPath('/anything'), true);
  assert.equal(p.command('/system/bin/sh', ['-c', 'id'], {}), undefined, 'dsh-subprocess-local 走原分支');
  assert.ok(Object.isFrozen(p));
});

test('createBionicPaths 拒绝非绝对或根目录的 rootfs', () => {
  for (const bad of ['', '/', 'rel', undefined, null, 3]) assert.throws(() => createBionicPaths(bad), /rootfs must be a non-root absolute directory/u);
});

// 真实用 --import 加载：按归档布局把两个文件放进临时 rootfs，检查 Symbol 装上、主脚本能看到映射。
test('preload.mjs 经 --import 装上 Symbol.for("dsh.go.direct")；DSH_GO_RUNTIME_ROOT 不一致则拒绝启动', () => {
  const tmp = realpathSync(mkdtempSync(join(tmpdir(), 'bionic-preload-')));
  try {
    const rootfs = join(tmp, 'rootfs');
    mkdirSync(join(rootfs, 'opt/dsh-go/bionic'), {recursive: true});
    mkdirSync(join(rootfs, 'root/workspace'), {recursive: true});
    for (const f of ['paths.mjs', 'preload.mjs']) copyFileSync(join(repo, 'runtime/bionic', f), join(rootfs, 'opt/dsh-go/bionic', f));
    const main = join(tmp, 'main.mjs');
    const probe = 'const a = globalThis[Symbol.for("dsh.go.direct")]; console.log(JSON.stringify({root: a?.root, ws: a?.toHost("/root/workspace"), sd: a?.toHost("/sdcard/x"), cmd: typeof a?.command}));';
    const {writeFileSync} = require_fs();
    writeFileSync(main, probe);
    const run = (env) => spawnSync(process.execPath, ['--import', join(rootfs, 'opt/dsh-go/bionic/preload.mjs'), main], {encoding: 'utf8', env: {...process.env, ...env}});
    const ok = run({DSH_GO_RUNTIME_ROOT: rootfs + '/'});
    assert.equal(ok.status, 0, ok.stderr);
    assert.deepEqual(JSON.parse(ok.stdout.trim()), {root: rootfs, ws: `${rootfs}/root/workspace`, sd: '/sdcard/x', cmd: 'function'});
    const noEnv = run({DSH_GO_RUNTIME_ROOT: undefined});
    assert.equal(noEnv.status, 0, noEnv.stderr);
    const other = join(tmp, 'other'); mkdirSync(other);
    const bad = run({DSH_GO_RUNTIME_ROOT: other});
    assert.notEqual(bad.status, 0);
    assert.match(bad.stderr, /\[dsh-app-bionic\] launch failed: DSH_GO_RUNTIME_ROOT does not match/u);
    const outside = spawnSync(process.execPath, ['--import', join(repo, 'runtime/bionic/preload.mjs'), main], {encoding: 'utf8'});
    assert.notEqual(outside.status, 0);
    assert.match(outside.stderr, /preload is outside its runtime path/u);
  } finally {
    rmSync(tmp, {recursive: true, force: true});
  }
});

function require_fs() { return {writeFileSync: (f, s) => { const fs = globalThis.process.getBuiltinModule('node:fs'); fs.writeFileSync(f, s); }}; }
