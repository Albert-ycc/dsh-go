import fs from 'node:fs';
import { StartupError } from './errors.mjs';

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const strArr = (v) => Array.isArray(v) && v.every((x) => typeof x === 'string');
const int = (v) => Number.isInteger(v);

/** 读一个 JSON 配置文件：不存在返回 undefined；存在但解析失败直接启动失败，不静默回退。 */
export function readJsonFile(p) {
  let text;
  try {
    text = fs.readFileSync(p, 'utf8');
  } catch (e) {
    if (e.code === 'ENOENT') return undefined;
    throw new StartupError(`无法读取配置文件 ${p}（${e.code}）`);
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new StartupError(`配置文件不是合法 JSON：${p}`);
  }
}

const bad = (name, what) => new StartupError(`配置文件 ${name} 的格式不对：${what}`);

export function checkConfig(c) {
  if (!isObj(c)) throw bad('config.json', '顶层必须是对象');
  if (c.device !== undefined && c.device !== null) {
    if (!isObj(c.device)) throw bad('config.json', 'device 必须是对象');
    if (c.device.serial !== undefined && c.device.serial !== null && typeof c.device.serial !== 'string') throw bad('config.json', 'device.serial 必须是字符串');
    if (c.device.prefer !== undefined && !['auto', 'usb', 'tcp'].includes(c.device.prefer)) throw bad('config.json', 'device.prefer 只能是 auto、usb、tcp');
  }
  if (c.hostNames !== undefined && !strArr(c.hostNames)) throw bad('config.json', 'hostNames 必须是字符串数组');
  if (c.logText !== undefined && typeof c.logText !== 'boolean') throw bad('config.json', 'logText 必须是布尔值');
  if (c.display !== undefined && c.display !== null) {
    if (!isObj(c.display) || !int(c.display.width) || !int(c.display.height) || c.display.width <= 0 || c.display.height <= 0) throw bad('config.json', 'display 必须是 {width, height} 正整数');
  }
}

export function checkTypemap(t) {
  if (!isObj(t)) throw bad('typemap.json', '顶层必须是对象');
  for (const k of ['dropTypes', 'ignoreClickable', 'pureContainers', 'androidEditable', 'passwordWords', 'systemBundles', 'inputMethodBundles']) {
    if (!strArr(t[k])) throw bad('typemap.json', `${k} 必须是字符串数组`);
  }
  if (!isObj(t.types)) throw bad('typemap.json', 'types 必须是对象');
}

export function checkKeymap(k) {
  if (!isObj(k)) throw bad('keymap.json', '顶层必须是对象');
  for (const name of ['ENTER', 'DEL', 'CTRL_LEFT', 'A']) {
    if (!int(k[name])) throw bad('keymap.json', `${name} 必须是整数键码`);
  }
}

export function checkGestures(g) {
  if (!isObj(g)) throw bad('gestures.json', '顶层必须是对象');
  for (const name of ['recents', 'notifications']) {
    const e = g[name];
    const pt = (p) => Array.isArray(p) && p.length === 2 && p.every((x) => typeof x === 'number' && x >= 0 && x <= 1);
    if (!isObj(e) || !['swipe', 'fling'].includes(e.op) || !pt(e.from) || !pt(e.to) || !int(e.velocity)) {
      throw bad('gestures.json', `${name} 必须是 {op, from:[0-1,0-1], to:[0-1,0-1], velocity}`);
    }
  }
}

export function checkAliases(a) {
  if (!isObj(a) || !Object.values(a).every((v) => typeof v === 'string')) throw bad('aliases.json', '必须是 名称→包名 的字符串映射');
}

/** tokens.json 的形状：数组，元素带 64 位 hex 的 hash 与字符串 name。 */
export function tokensShapeOk(t) {
  return Array.isArray(t) && t.every((e) => isObj(e) && typeof e.hash === 'string' && /^[0-9a-f]{64}$/.test(e.hash) && (e.name === undefined || typeof e.name === 'string'));
}
