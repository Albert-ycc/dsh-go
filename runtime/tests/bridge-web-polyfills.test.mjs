// dsh-go-bridge 的网页 polyfill（runtime/bridge/dsh-go-bridge/lib/web-polyfills.js）。
//   DSH_PREFIX=/path/to/dsh-prefix node --test runtime/tests/bridge-web-polyfills.test.mjs
import assert from 'node:assert/strict';
import {test} from 'node:test';
import {existsSync} from 'node:fs';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import vm from 'node:vm';
import {WEB_POLYFILLS_SCRIPT} from '../bridge/dsh-go-bridge/lib/web-polyfills.js';
import {apply} from '../bridge/dsh-go-bridge/lib/index.js';
import {withSources} from './plugin-config-source-fixture.mjs';

// 一个缺了这批内置的「旧 WebView」：独立 realm，删掉要补的静态方法与原型方法。
function oldEngine() {
  class Signal {}
  const g = vm.createContext({AbortController, AbortSignal: Signal, DOMException, URL: class URL0 { constructor(u, b) { return new URL(u, b); } }, setTimeout});
  vm.runInContext(`
    delete Promise.withResolvers; delete Promise.try; delete Object.hasOwn; delete RegExp.escape;
    for (const k of ['at','findLast','findLastIndex','toSorted','toReversed','toSpliced','with']) delete Array.prototype[k];
    for (const k of ['at','replaceAll']) delete String.prototype[k];
    for (const k of ['union','intersection','difference','symmetricDifference','isSubsetOf','isSupersetOf','isDisjointFrom']) delete Set.prototype[k];
  `, g);
  vm.runInContext(WEB_POLYFILLS_SCRIPT, g);
  return g;
}

test('旧引擎上逐项补齐，行为与规范一致', async () => {
  const g = oldEngine();
  const r = (src) => vm.runInContext(src, g);
  const j = (src) => JSON.parse(vm.runInContext(`JSON.stringify(${src})`, g));
  assert.equal(r(`(() => { const d = Promise.withResolvers(); d.resolve(3); return typeof d.promise.then; })()`), 'function');
  assert.equal(await r(`Promise.try((a, b) => a + b, 1, 2)`), 3);
  assert.equal(r(`Object.hasOwn({a: 1}, 'a') && !Object.hasOwn({}, 'toString')`), true);
  assert.deepEqual(j(`[3, 1, 2].toSorted()`), [1, 2, 3]);
  assert.deepEqual(j(`(() => { const a = [1, 2, 3]; const b = a.toReversed(); return [a, b]; })()`), [[1, 2, 3], [3, 2, 1]]);
  assert.deepEqual(j(`[1, 2, 3, 4].toSpliced(1, 2)`), [1, 4]);
  assert.deepEqual(j(`[1, 2, 3].with(-1, 9)`), [1, 2, 9]);
  assert.equal(r(`[1, 2, 3, 4].findLast((v) => v % 2)`), 3);
  assert.equal(r(`[1, 2, 3, 4].findLastIndex((v) => v > 9)`), -1);
  assert.equal(r(`[1, 2, 3].at(-1) + 'abc'.at(-1)`), '3c');
  assert.equal(r(`'a.b.a'.replaceAll('a', '[$&]')`), '[a].b.[a]');
  assert.equal(r(`'a+b+c'.replaceAll('+', () => '-')`), 'a-b-c');
  assert.throws(() => r(`'aa'.replaceAll(/a/, 'b')`), {name: 'TypeError'});
  assert.equal(r(`new RegExp(RegExp.escape('a.b*c-1')).test('xa.b*c-1')`), true);
  assert.equal(r(`new RegExp(RegExp.escape('a.b')).test('axb')`), false);
  assert.deepEqual(j(`[...new Set([1, 2, 3]).intersection(new Set([2, 3, 4]))]`), [2, 3]);
  assert.deepEqual(j(`[...new Set([1, 2]).union(new Set([2, 5]))]`), [1, 2, 5]);
  assert.deepEqual(j(`[...new Set([1, 2]).difference(new Set([2]))]`), [1]);
  assert.deepEqual(j(`[...new Set([1, 2]).symmetricDifference(new Set([2, 3]))]`), [1, 3]);
  assert.equal(r(`new Set([1]).isSubsetOf(new Set([1, 2])) && new Set([1, 2]).isSupersetOf(new Set([2])) && new Set([1]).isDisjointFrom(new Set([2]))`), true);
  assert.equal(r(`URL.canParse('https://x.test/') && !URL.canParse('nope')`), true);
});

test('AbortSignal.any：任一来源中止就中止，原因透传；已中止的来源立即生效', () => {
  const g = oldEngine();
  const any = g.AbortSignal.any;
  const a = new AbortController();
  const b = new AbortController();
  const both = any([a.signal, b.signal]);
  assert.equal(both.aborted, false);
  b.abort('stop');
  assert.equal(both.aborted, true);
  assert.equal(both.reason, 'stop');
  const done = new AbortController();
  done.abort('early');
  assert.equal(any([done.signal]).reason, 'early');
  assert.equal(typeof g.AbortSignal.timeout, 'function');
});

test('原生已有的内置不被替换', () => {
  const g = vm.createContext({AbortController, AbortSignal, DOMException, URL, setTimeout});
  const before = vm.runInContext(`[Promise.withResolvers, Array.prototype.toSorted, String.prototype.replaceAll, Object.hasOwn]`, g);
  vm.runInContext(WEB_POLYFILLS_SCRIPT, g);
  const after = vm.runInContext(`[Promise.withResolvers, Array.prototype.toSorted, String.prototype.replaceAll, Object.hasOwn]`, g);
  before.forEach((fn, i) => assert.equal(after[i], fn));
  assert.equal(AbortSignal.any.name, 'any');
});

test('桥把 polyfill 放在注入表第一位', () => {
  const hooks = new Map();
  const ctx = {logger: {warn() {}}, get: () => undefined, provide() {}, effect() {}, inject(_d, fn) { fn(ctx); },
    on(n, fn) { const fns = hooks.get(n) ?? []; fns.push(fn); hooks.set(n, fns); },
    webServer: {register() { return () => {}; }, registerUpgrade() { return () => {}; }}};
  withSources(ctx, []);
  apply(ctx, {persist: false, guardRoutes: false});
  const table = [{kind: 'global', name: '__X__', value: 1}];
  for (const fn of hooks.get('webserver/index-inject') ?? []) fn(table);
  assert.deepEqual(table[0], {kind: 'script', placement: 'head', text: WEB_POLYFILLS_SCRIPT});
  assert.equal(table.length, 2);
});

const prefix = process.env.DSH_PREFIX;
const webserver = prefix && join(prefix, 'node_modules/@deepseek-ai/dsh-host-webserver/lib/index.js');
test('渲染后 polyfill 在 <head> 最前、先于启动就绪脚本', {skip: !(webserver && existsSync(webserver)) && 'DSH_PREFIX 未指向引擎树'}, async () => {
  const {renderIndexInjections} = await import(pathToFileURL(webserver).href);
  const html = renderIndexInjections('<html><head><title>x</title></head><body><div id="app"></div></body></html>',
    [{kind: 'script', placement: 'head', text: WEB_POLYFILLS_SCRIPT}, {kind: 'global', name: '__X__', value: 1}]);
  const polyfill = html.indexOf('installWebPolyfills');
  assert.ok(polyfill > html.indexOf('<head>') && polyfill < html.indexOf('<title>'));
  assert.ok(polyfill < html.indexOf('Promise.withResolvers()).resolve()'));
});
