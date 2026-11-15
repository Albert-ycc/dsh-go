// bionic 引擎补丁（runtime/patches/bionic）的测试。
//   DSH_PREFIX=<android 过滤的 rc.4 树，已依次打完主目录与 bionic 补丁> node --test runtime/tests/bionic-patches.test.mjs
// 可选：
//   FLOCK_REAL_NODE=<darwin 版 system.node>  用它替身真实加载并抢锁（macOS 上没法 dlopen bionic 的 .node）
//   ANDROID_NDK_HOME                          设置后现编 system.node，检查符号与 NEEDED
import assert from 'node:assert/strict';
import {test} from 'node:test';
import {spawnSync} from 'node:child_process';
import {chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync} from 'node:fs';
import {constants, tmpdir} from 'node:os';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '..', '..');
const prefix = process.env.DSH_PREFIX ? resolve(process.env.DSH_PREFIX) : undefined;
const noPrefix = prefix ? false : 'DSH_PREFIX 未设置';
const V = '0.2.1-rc.4';
const bootFile = 'node_modules/@deepseek-ai/dsh-app-boot/lib/index.js';
const flockFile = 'node_modules/@deepseek-ai/node-addon-system/lib/flock.js';
const tmp = realpathSync(mkdtempSync(join(tmpdir(), 'bionic-patches-')));
process.on('exit', () => rmSync(tmp, {recursive: true, force: true}));
const sha = (p) => spawnSync('shasum', ['-a', '256', p], {encoding: 'utf8'}).stdout.split(' ')[0];
const run = (args, opts = {}) => spawnSync(process.execPath, args, {encoding: 'utf8', ...opts});

test('pins 链：bionic 的 before 等于主目录链条终点，补丁树文件等于 after', () => {
  const main = JSON.parse(readFileSync(join(repo, 'runtime/patches/pins.json'), 'utf8')).patches;
  const bionic = JSON.parse(readFileSync(join(repo, 'runtime/patches/bionic/pins.json'), 'utf8')).patches;
  const end = {};
  for (const name of Object.keys(main).sort()) for (const [rel, p] of Object.entries(main[name][V] ?? {})) end[rel] = p.after;
  const order = Object.keys(bionic).sort();
  assert.deepEqual(order, ['bionic-attachment-durable-boundary.diff', 'bionic-expose-internals.diff', 'bionic-flock-android.diff']);
  for (const name of order) for (const [rel, p] of Object.entries(bionic[name][V])) {
    // dsh-app-boot 被主目录改过，要接在链尾；flock.js 主目录不碰，before 是 rc.4 原件
    if (rel === bootFile) assert.equal(p.before, end[rel], 'dsh-app-boot 链条断开');
    else assert.equal(end[rel], undefined, `${rel} 已被主目录补丁修改，链条需要重排`);
    if (prefix) assert.equal(sha(join(prefix, rel)), p.after, `${rel} 与 pins 的 after 不符`);
  }
});

// 取补丁后文件里的 internalModules() 原文，放进临时 .mjs，在 --expose-internals 下真实执行。
function extractInternalModules() {
  const src = readFileSync(join(prefix, bootFile), 'utf8');
  const start = src.indexOf('function internalModules() {');
  assert.notEqual(start, -1);
  const end = src.indexOf('\n}\n', start);
  assert.notEqual(end, -1);
  const file = join(tmp, 'internal-modules.mjs');
  writeFileSync(file, `import { createRequire } from "node:module";\n${src.slice(start, end + 2)}\nexport { internalModules };\n`);
  return file;
}

test('E1：--expose-internals 下 internalModules() 返回 esm / cjs 形状', {skip: noPrefix}, () => {
  const file = extractInternalModules();
  const probe = join(tmp, 'e1-probe.mjs');
  writeFileSync(probe, `import {internalModules} from ${JSON.stringify(pathToFileURL(file).href)};
const m = internalModules();
const out = {
  keys: Object.keys(m).sort(),
  esmResolveSync: typeof m.esm.resolveSync,
  esmJob: typeof (m.modern ? m.esm.getOrCreateModuleJob : m.esm.getModuleJobForImport),
  modern: m.modern,
  defaultResolve: typeof m.esmDefaultResolve,
  esmConditions: [...m.esmConditions].includes('node'),
  cjsResolveFilename: typeof m.cjs._resolveFilename,
  cjsNodeModulePaths: typeof m.cjs._nodeModulePaths,
  cjsConditions: [...m.cjsConditions].includes('require'),
};
console.log(JSON.stringify(out));`);
  const r = run(['--expose-internals', probe]);
  assert.equal(r.status, 0, r.stderr);
  const got = JSON.parse(r.stdout.trim().split('\n').pop());
  assert.deepEqual(got.keys, ['cjs', 'cjsConditions', 'esm', 'esmConditions', 'esmDefaultResolve', 'modern']);
  assert.equal(got.esmResolveSync, 'function');
  assert.equal(got.esmJob, 'function');
  assert.equal(typeof got.modern, 'boolean');
  assert.equal(got.defaultResolve, 'function');
  assert.equal(got.esmConditions, true);
  assert.equal(got.cjsResolveFilename, 'function');
  assert.equal(got.cjsNodeModulePaths, 'function');
  assert.equal(got.cjsConditions, true);
});

test('E1：没有 --expose-internals 时仍走 node-addon-require-builtin（glibc 路径不变）', {skip: noPrefix}, () => {
  const file = extractInternalModules();
  const probe = join(tmp, 'e1-glibc-probe.mjs');
  writeFileSync(probe, `import {internalModules} from ${JSON.stringify(pathToFileURL(file).href)};
try { internalModules(); console.log('NO_THROW'); } catch (e) { console.log(e.code + '|' + /node-addon-require-builtin/.test(e.message)); }`);
  const r = run([probe]);
  assert.equal(r.status, 0, r.stderr);
  // 临时文件在 tmp 下，解析不到该包：MODULE_NOT_FOUND 且消息点名 node-addon-require-builtin，证明走的是原分支
  assert.equal(r.stdout.trim(), 'MODULE_NOT_FOUND|true');
});

// 在 process.platform 被替换成 android 的子进程里加载 flock.js。夹具树：flock.js 拷自补丁树，
// 平台包 @deepseek-ai/node-addon-system-android-arm64/bin/bionic/system.node 由调用方放入。
function flockFixture(name, putBinding) {
  const root = join(tmp, name);
  const pkgDir = join(root, 'node_modules/@deepseek-ai/node-addon-system');
  mkdirSync(join(pkgDir, 'lib'), {recursive: true});
  copyFileSync(join(prefix, flockFile), join(pkgDir, 'lib/flock.js'));
  writeFileSync(join(pkgDir, 'package.json'), '{"name":"@deepseek-ai/node-addon-system","version":"0.1.2","type":"module"}');
  const plat = join(root, 'node_modules/@deepseek-ai/node-addon-system-android-arm64');
  mkdirSync(join(plat, 'bin/bionic'), {recursive: true});
  writeFileSync(join(plat, 'package.json'), '{"name":"@deepseek-ai/node-addon-system-android-arm64","version":"0.1.2"}');
  putBinding(join(plat, 'bin/bionic/system.node'));
  return {flock: pathToFileURL(join(pkgDir, 'lib/flock.js')).href, binding: join(plat, 'bin/bionic/system.node')};
}

test('E2：android 平台下 flock.js 加载 bin/bionic/system.node（替身 .node，验路径解析与调用）', {skip: noPrefix}, () => {
  const fx = flockFixture('flock-stub', (p) => writeFileSync(p, 'not an elf'));
  const probe = join(tmp, 'e2-stub.mjs');
  writeFileSync(probe, `import Module from 'node:module';
Object.defineProperty(process, 'platform', {value: 'android'});
const loaded = [];
Module._extensions['.node'] = (module, filename) => { loaded.push(filename); module.exports = {tryLock(fd, cb) { cb(fd === 7 ? 0 : ${constants.errno.EAGAIN}); }}; };
const {tryLockExclusive} = await import(${JSON.stringify(fx.flock)});
await tryLockExclusive(7);
const err = await tryLockExclusive(8).then(() => null, (e) => e);
console.log(JSON.stringify({loaded, code: err?.code, errno: err?.errno, syscall: err?.syscall}));`);
  const r = run([probe]);
  assert.equal(r.status, 0, r.stderr);
  const got = JSON.parse(r.stdout.trim().split('\n').pop());
  assert.deepEqual(got.loaded, [fx.binding]);
  assert.deepEqual([got.code, got.errno, got.syscall], ['EAGAIN', constants.errno.EAGAIN, 'flock']);
});

test('E2：未打补丁的 flock.js 在 android 上仍拒绝（反向守卫）', () => {
  // 取主 rc.4 原件的哈希对照：补丁把 'android' 放行，原件里没有
  const diff = readFileSync(join(repo, 'runtime/patches/bionic/bionic-flock-android.diff'), 'utf8');
  assert.match(diff, /^-    if \(platform !== 'linux' && platform !== 'darwin'\) \{$/m);
  assert.match(diff, /^\+    if \(platform !== 'linux' && platform !== 'darwin' && platform !== 'android'\) \{$/m);
});

test('E2：真实 .node 加载并抢锁（FLOCK_REAL_NODE 替身，非 bionic 产物）', {skip: noPrefix || (process.env.FLOCK_REAL_NODE && existsSync(process.env.FLOCK_REAL_NODE) ? false : 'FLOCK_REAL_NODE 未设置')}, () => {
  const fx = flockFixture('flock-real', (p) => copyFileSync(process.env.FLOCK_REAL_NODE, p));
  const lockFile = join(tmp, 'lockfile');
  writeFileSync(lockFile, '');
  const probe = join(tmp, 'e2-real.mjs');
  writeFileSync(probe, `import {openSync} from 'node:fs';
Object.defineProperty(process, 'platform', {value: 'android'});
const {tryLockExclusive} = await import(${JSON.stringify(fx.flock)});
const a = openSync(${JSON.stringify(lockFile)}, 'r+');
const b = openSync(${JSON.stringify(lockFile)}, 'r+');
await tryLockExclusive(a);
const err = await tryLockExclusive(b).then(() => null, (e) => e);
console.log(JSON.stringify({code: err?.code, errno: err?.errno}));`);
  const r = run([probe]);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(JSON.parse(r.stdout.trim().split('\n').pop()).code, 'EAGAIN');
});

// E3：附件落盘时的目录 fsync 边界。夹具按手机上的形状摆：rootfs 在一个「穿得过、打不开」的目录下面
// （手机上是 /data/user/0，应用对它只有 x 权限），DSH_HOME 是 <rootfs>/root/.dsh。子进程里用真实的
// preload.mjs 装路径适配器，调引擎导出的 commitPreparedImageFile（带图提问落盘走的就是它），
// 并记下为 fsync 打开过的每个目录。
const attachmentFile = 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js';
const asRoot = process.getuid?.() === 0 ? 'root 不受目录权限约束，夹具封不住上级目录' : false;

function attachmentFixture(name, run) {
  const sealed = join(tmp, name, 'sealed');
  const rootfs = join(sealed, 'app/files/runtime-bionic/rootfs');
  mkdirSync(join(rootfs, 'opt/dsh-go/bionic'), {recursive: true});
  mkdirSync(join(rootfs, 'root/.dsh'), {recursive: true});
  mkdirSync(join(sealed, 'app/elsewhere/.dsh'), {recursive: true});
  for (const f of ['paths.mjs', 'preload.mjs']) copyFileSync(join(repo, 'runtime/bionic', f), join(rootfs, 'opt/dsh-go/bionic', f));
  const probe = join(tmp, name, 'probe.mjs');
  writeFileSync(probe, `import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {syncBuiltinESMExports} from 'node:module';
const opened = [];
const open = fs.promises.open;
fs.promises.open = async function tracedOpen(path, ...rest) {
  let dir = false;
  try { dir = fs.statSync(path).isDirectory(); } catch {}
  if (dir) opened.push(String(path));
  return open.call(fs.promises, path, ...rest);
};
syncBuiltinESMExports();
const {commitPreparedImageFile} = await import(${JSON.stringify(pathToFileURL(join(prefix ?? tmp, attachmentFile)).href)});
const data = new Uint8Array(Buffer.from('dsh-go e3 scanned page'));
const sha = createHash('sha256').update(data).digest('hex');
const root = process.argv[2] + '/attachments/v1';
let result;
try {
  const ref = await commitPreparedImageFile(root, {data, ref: {attachmentId: 'sha256:' + sha, mediaType: 'image/jpeg', width: 1, height: 1, bytes: data.byteLength}});
  const stored = fs.readFileSync(root + '/objects/' + sha.slice(0, 2) + '/' + sha);
  result = {ok: true, id: String(ref.attachmentId), intact: Buffer.compare(stored, data) === 0};
} catch (error) {
  result = {ok: false, code: error?.code ?? error?.cause?.code, message: String(error?.message)};
}
console.log(JSON.stringify({adapter: globalThis[Symbol.for('dsh.go.direct')]?.root, result, opened}));`);
  const preload = join(rootfs, 'opt/dsh-go/bionic/preload.mjs');
  const save = (home, {adapter}) => {
    const r = spawnSync(process.execPath, [...(adapter ? ['--import', preload] : []), probe, home], {encoding: 'utf8'});
    assert.equal(r.status, 0, r.stderr);
    return JSON.parse(r.stdout.trim().split('\n').pop());
  };
  chmodSync(sealed, 0o111);
  try {
    return run({sealed, rootfs, home: join(rootfs, 'root/.dsh'), elsewhere: join(sealed, 'app/elsewhere/.dsh'), save});
  } finally {
    chmodSync(sealed, 0o700);
  }
}

test('E3：rootfs 的上级目录打不开时，带图提问的附件照样落盘，目录 fsync 止于 rootfs', {skip: noPrefix || asRoot}, () => {
  attachmentFixture('attachment-boundary', ({sealed, rootfs, home, save}) => {
    const got = save(home, {adapter: true});
    assert.equal(got.adapter, rootfs);
    assert.deepEqual(got.result, {ok: true, id: got.result.id, intact: true}, `落盘被拒：${got.result.message}`);
    // rootfs 以内的每一级照旧 fsync（与 PRoot 里走到 guest 根是同一组目录），rootfs 以外一个都不碰
    for (const dir of [join(rootfs, 'root'), rootfs, home, join(home, 'attachments'), join(home, 'attachments/v1')]) {
      assert.ok(got.opened.includes(dir), `没有 fsync ${dir}`);
    }
    const outside = got.opened.filter((dir) => dir !== rootfs && !dir.startsWith(`${rootfs}/`));
    assert.deepEqual(outside, [], '目录 fsync 走出了 rootfs');
    assert.ok(!got.opened.includes(sealed));
  });
});

test('E3：没有路径适配器、或 DSH_HOME 不在 rootfs 里，边界仍是文件系统根（夹具确实复现 EACCES）', {skip: noPrefix || asRoot}, () => {
  attachmentFixture('attachment-boundary-guard', ({sealed, home, elsewhere, save}) => {
    for (const got of [save(home, {adapter: false}), save(elsewhere, {adapter: true})]) {
      assert.equal(got.result.ok, false);
      assert.equal(got.result.code, 'EACCES');
      assert.ok(got.result.message.includes(`open '${sealed}'`), got.result.message);
    }
  });
});

const ndk = process.env.ANDROID_NDK_HOME;
test('system.node：NDK 现编，无 NEEDED libnode，未定义符号只有 napi_* 与 libc，同输入两次逐字节相同',
  {skip: ndk && existsSync(ndk) ? false : 'ANDROID_NDK_HOME 未设置'}, () => {
    const tc = join(ndk, 'toolchains/llvm/prebuilt/darwin-x86_64/bin');
    const script = join(repo, 'runtime/android-inproc/addons/build-flock.sh');
    const build = (dir) => {
      const r = spawnSync('bash', [script, dir], {encoding: 'utf8'});
      assert.equal(r.status, 0, r.stderr);
      return join(dir, 'system.node');
    };
    const a = build(join(tmp, 'ndk-a'));
    const b = build(join(tmp, 'ndk-b'));
    assert.equal(sha(a), sha(b));
    const needed = spawnSync(join(tc, 'llvm-readelf'), ['-d', a], {encoding: 'utf8'}).stdout
      .split('\n').filter((l) => l.includes('(NEEDED)')).map((l) => l.match(/\[(.*)\]/)[1]);
    assert.ok(needed.every((n) => !/node/.test(n)), `NEEDED: ${needed}`);
    assert.deepEqual(needed.sort(), ['libc.so', 'libdl.so']);
    const undef = spawnSync(join(tc, 'llvm-nm'), ['-D', '-u', a], {encoding: 'utf8'}).stdout
      .split('\n').map((l) => l.trim()).filter(Boolean).map((l) => l.replace(/^U /, ''));
    const napi = undef.filter((s) => s.startsWith('napi_'));
    const rest = undef.filter((s) => !s.startsWith('napi_'));
    assert.ok(napi.length >= 15);
    assert.ok(rest.every((s) => s.endsWith('@LIBC')), `非 napi/libc 符号: ${rest}`);
    const defined = spawnSync(join(tc, 'llvm-nm'), ['-D', '--defined-only', a], {encoding: 'utf8'}).stdout
      .split('\n').map((l) => l.trim().split(/\s+/).pop()).filter(Boolean).sort();
    assert.deepEqual(defined, ['napi_register_module_v1', 'node_api_module_get_api_version_v1']);
    const e = spawnSync(join(tc, 'llvm-readelf'), ['-h', a], {encoding: 'utf8'}).stdout;
    assert.match(e, /Machine:\s+AArch64/);
  });
