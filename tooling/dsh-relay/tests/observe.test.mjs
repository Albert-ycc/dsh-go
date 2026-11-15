import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { convertDump } from '../lib/observe.mjs';
import { formatGolden } from '../testkit/format.mjs';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const tm = JSON.parse(fs.readFileSync(path.join(ROOT, 'config', 'typemap.json'), 'utf8'));
const fixtures = fs.readdirSync(path.join(ROOT, 'contract', 'fixtures')).filter((n) => n.endsWith('.json'));

test('contract 夹具：observe 转换输出与 expected 逐字一致', () => {
  assert.ok(fixtures.length >= 2);
  for (const f of fixtures) {
    const raw = JSON.parse(fs.readFileSync(path.join(ROOT, 'contract', 'fixtures', f), 'utf8'));
    const want = fs.readFileSync(path.join(ROOT, 'contract', 'expected', f.replace('.json', '.observe.json')), 'utf8');
    assert.equal(formatGolden(convertDump(raw, tm)), want, f);
  }
});

const load = (n) => JSON.parse(fs.readFileSync(path.join(ROOT, 'contract', 'fixtures', n), 'utf8'));
const flatten = (out) => {
  const flat = [];
  (function walk(n) { flat.push(n); (n.children ?? []).forEach(walk); })({ children: out.windows.map((w) => w.root).filter(Boolean) });
  return flat.slice(1);
};

test('转换规则（手写夹具）：Toggle 并入父 Row、密码词、隐藏与零面积节点被裁、禁用态', () => {
  const out = convertDump(load('synthetic-settings.json'), tm);
  const flat = flatten(out);
  const bt = flat.find((n) => n.text === '蓝牙');
  assert.deepEqual(bt.flags, ['click', 'check', 'checked']);
  assert.equal(bt.cls, 'android.view.View');
  assert.equal(bt.role, 'switch');
  assert.ok(!flat.some((n) => n.raw === 'Toggle'), 'Toggle is absorbed into its Row');
  assert.equal(flat.filter((n) => n.text === '蓝牙').length, 1, 'label text is not repeated by the child Text');
  assert.ok(flat.find((n) => n.hint === '请输入Wi-Fi密码').flags.includes('password'));
  assert.ok(!flat.find((n) => n.hint === '备注').flags.includes('password'));
  assert.ok(flat.find((n) => n.raw === 'List').flags.includes('scroll'));
  assert.ok(!flat.some((n) => n.text === '隐藏项'));
  assert.equal(flat.filter((n) => n.raw === 'Stack').length, 0);
  assert.equal(flat.find((n) => n.text === '飞行模式').enabled ?? flat.find((n) => n.raw === 'Row' && n.enabled === false).enabled, false);
  assert.equal(out.windows[1].kind, 'system');
  assert.equal(out.main, 0);
});

test('真机夹具 01/02：「蓝牙」是带标签的可点元素', () => {
  for (const f of ['01-settings-home.json', '02-settings-bluetooth.json']) {
    const hit = flatten(convertDump(load(f), tm)).filter((n) => n.text?.includes('蓝牙') && n.flags?.includes('click'));
    assert.ok(hit.length >= 1, f);
  }
  const bt = flatten(convertDump(load('02-settings-bluetooth.json'), tm)).find((n) => n.text === '蓝牙' && n.flags?.includes('check'));
  assert.deepEqual(bt.flags.filter((x) => x !== 'focused'), ['click', 'check', 'checked']);
  assert.equal(bt.role, 'switch');
});

test('真机夹具规则：丢弃名单、SearchField 才是输入框、TextInput 不置 scroll、容器安卓节点保留、opacity 规则', () => {
  const dropped = new Set(tm.dropTypes);
  for (const f of fixtures) {
    const out = convertDump(load(f), tm);
    const flat = flatten(out);
    assert.ok(!flat.some((n) => dropped.has(n.raw)), f);
    assert.ok(!flat.some((n) => n.raw === 'Search' && n.flags?.includes('click')), f);
    assert.ok(!flat.some((n) => n.raw === 'TextInput' && n.flags?.includes('scroll')), f);
  }
  const home = flatten(convertDump(load('01-settings-home.json'), tm));
  assert.ok(home.find((n) => n.raw === 'SearchField').flags.includes('editable'));
  const pw = flatten(convertDump(load('03-wifi-password-dialog.json'), tm)).find((n) => n.raw === 'TextInput');
  assert.ok(pw.flags.includes('editable') && pw.flags.includes('password'));
  const probe = flatten(convertDump(load('04-container-probe-home.json'), tm));
  assert.ok(probe.some((n) => n.cls === 'android.widget.Button' && n.text === '上一页'), 'android nodes with opacity 0 survive');
  const lock = convertDump(load('05-lockscreen.json'), tm);
  assert.equal(lock.locked, true);
  assert.equal(convertDump(load('01-settings-home.json'), tm).locked, false);
});

test('节点树深度限制 28 层并置 truncated', () => {
  let node = { attributes: { type: 'Text', text: 'leaf', bounds: '[0,0][10,10]' }, children: [] };
  for (let i = 0; i < 40; i++) node = { attributes: { type: 'Column', text: `c${i}`, bounds: '[0,0][10,10]' }, children: [node] };
  const out = convertDump({ children: [{ attributes: { bundleName: 'x', bounds: '[0,0][10,10]', type: 'Stack', text: 'w' }, children: [node] }] }, tm);
  let d = 0;
  (function walk(n) { d++; (n.children ?? []).forEach(walk); })(out.windows[0].root);
  assert.ok(d <= 28, `depth ${d}`);
  assert.equal(out.truncated, true);
});

test('超过 600 KB 按面积丢叶子并置 truncated', () => {
  const kids = [];
  for (let i = 0; i < 6000; i++) kids.push({ attributes: { type: 'Text', text: `item ${i} ${'x'.repeat(100)}`, bounds: `[0,${i}][${10 + (i % 50)},${i + 20}]` }, children: [] });
  const out = convertDump({ children: [{ attributes: { bundleName: 'x', bounds: '[0,0][1320,2120]', type: 'Stack', text: 'w' }, children: kids }] }, tm);
  assert.equal(out.truncated, true);
  assert.ok(Buffer.byteLength(JSON.stringify(out)) <= 600 * 1024);
});

test('H3：dump 里没有 hint 也能靠「安全键盘」信号判出密码框；systemui 按系统窗口', () => {
  const raw = load('03-wifi-password-dialog.json');
  (function strip(n) { if (n.attributes?.type === 'TextInput') { n.attributes.hint = ''; n.attributes.text = ''; } (n.children ?? []).forEach(strip); })(raw);
  const out = convertDump(raw, tm);
  assert.equal(out.secureKeyboard, true);
  assert.ok(flatten(out).find((n) => n.raw === 'TextInput').flags.includes('password'));
  assert.ok(tm.systemBundles.includes('com.ohos.systemui'));
  assert.ok(tm.systemBundles.includes('com.ohos.sceneboard'));
});
