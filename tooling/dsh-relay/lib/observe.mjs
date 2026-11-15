// 鸿蒙 uitest dumpLayout JSON → 方案 4.3 的 Node 树。只做裁剪与类型映射，不做文字化。

const MAX_BYTES = 600 * 1024;

export function parseBounds(s) {
  const m = /^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/.exec(String(s ?? ''));
  return m ? [Number(m[1]), Number(m[2]), Number(m[3]), Number(m[4])] : null;
}

const T = (v) => v === 'true' || v === true;

function containsPasswordWord(s, words) {
  const low = s.toLowerCase();
  return words.some((w) => low.includes(w));
}

function firstText(n) {
  for (const c of n.children ?? []) {
    const a = c.attributes ?? {};
    const t = a.text || a.originalText;
    if (t) return t;
    const d = firstText(c);
    if (d) return d;
  }
  return '';
}

/** 删掉子树里第一个文字等于 label 的非可点节点（可点容器已经带上这个标签，不再重复一遍） */
function removeLabelText(children, label) {
  for (let i = 0; i < children.length; i++) {
    const c = children[i];
    if (c.text === label && !c.flags?.includes('click') && !c.children) {
      children.splice(i, 1);
      return true;
    }
    if (c.children && !c.flags?.includes('click') && removeLabelText(c.children, label)) {
      if (c.children.length === 0) delete c.children;
      return true;
    }
  }
  return false;
}

/** 可点的开关节点（Toggle）作为父 Row 的状态来源：摘掉它，把勾选状态并到父节点 */
function absorbToggle(children, flagsOut) {
  for (let i = 0; i < children.length; i++) {
    const c = children[i];
    if (c.raw === 'Toggle') {
      for (const f of ['check', 'checked']) if (c.flags?.includes(f)) flagsOut.add(f);
      children.splice(i, 1);
      return true;
    }
    if (c.children && !c.flags?.includes('click') && absorbToggle(c.children, flagsOut)) {
      if (c.children.length === 0) delete c.children;
      return true;
    }
  }
  return false;
}

const MAX_DEPTH = 28; // Kotlin 解析器嵌套上限 64，每层节点占 2 级
let depthCut = false;

function convNode(n, tm, passwordRe, depth = 1) {
  const a = n.attributes ?? {};
  const raw = a.type ?? '';
  if (tm.dropTypes.includes(raw)) return null;
  if (a.visible === 'false') return null;
  const bounds = parseBounds(a.bounds);
  if (!bounds || bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) return null;
  const isAndroid = raw.startsWith('android.');
  // opacity 只对鸿蒙一侧的叶子生效；容器内安卓节点在 dump 里一律是 0.000000 却完整可见
  if (!isAndroid && !(n.children ?? []).length && a.opacity !== undefined && a.opacity !== '' && Number(a.opacity) === 0) return null;

  const children = [];
  if (depth >= MAX_DEPTH && (n.children ?? []).length) depthCut = true;
  for (const c of depth >= MAX_DEPTH ? [] : n.children ?? []) {
    const cc = convNode(c, tm, passwordRe, depth + 1);
    if (cc) children.push(cc);
  }

  const map = tm.types[raw];
  const ownText = a.text || a.originalText || '';
  const desc = a.description ?? '';
  const hint = a.hint ?? '';
  const clickable = T(a.clickable) && !tm.ignoreClickable.includes(raw);
  // 可点节点没有自己的文字时，标签取子树里第一个非空文字
  const text = ownText || (clickable ? firstText(n) : '');
  const flags = new Set();
  if (clickable) flags.add('click');
  if (T(a.longClickable)) flags.add('long');
  if (map?.scroll || (isAndroid && T(a.scrollable))) flags.add('scroll');
  if (T(a.checkable)) flags.add('check');
  if (T(a.checked)) flags.add('checked');
  if (T(a.selected)) flags.add('selected');

  if (clickable && text) removeLabelText(children, text);
  const hasToggle = clickable && absorbToggle(children, flags);

  const editable = !!map?.editable || tm.androidEditable.includes(raw);
  if (editable) {
    flags.add('editable');
    const label = `${text} ${desc} ${hint}`;
    if (passwordRe.test(label) || (text && /^[•*●·]+$/.test(text))) flags.add('password');
  }
  // 焦点链上的祖先容器也带 focused，只留给可点或可编辑的节点，别让容器因此无法合并
  if (T(a.focused) && (clickable || editable)) flags.add('focused');
  const order = ['click', 'long', 'scroll', 'check', 'checked', 'selected', 'focused', 'editable', 'password'];
  const flagList = order.filter((f) => flags.has(f));

  const bare = !text && !desc && !hint && flagList.length === 0;
  if (bare && children.length === 0) return null;
  if (bare && children.length === 1 && a.enabled !== 'false' && tm.pureContainers.includes(raw)) return children[0];

  const node = { cls: map?.cls ?? (clickable && !isAndroid ? 'android.view.View' : raw), raw };
  const role = map?.role ?? (hasToggle ? 'switch' : undefined);
  if (role) node.role = role;
  if (text) node.text = text;
  if (desc) node.desc = desc;
  if (hint) node.hint = hint;
  if (a.id) node.viewId = a.id;
  node.bounds = bounds;
  if (flagList.length) node.flags = flagList;
  if (a.enabled === 'false') node.enabled = false;
  if (children.length) node.children = children;
  return node;
}

function countNodes(n) {
  let c = 1;
  for (const k of n.children ?? []) c += countNodes(k);
  return c;
}

function truncate(result) {
  // 按面积从小到大丢叶子，直到序列化后不超过上限
  let size = Buffer.byteLength(JSON.stringify(result));
  if (size <= MAX_BYTES) return false;
  while (size > MAX_BYTES) {
    const leaves = [];
    const walk = (n, parent) => {
      if (!n.children?.length) {
        if (parent) leaves.push({ n, parent, area: (n.bounds[2] - n.bounds[0]) * (n.bounds[3] - n.bounds[1]) });
        return;
      }
      for (const c of n.children) walk(c, n);
    };
    for (const w of result.windows) if (w.root) walk(w.root, null);
    if (leaves.length === 0) break;
    leaves.sort((x, y) => x.area - y.area);
    const drop = new Set(leaves.slice(0, Math.max(1, Math.ceil(leaves.length / 10))).map((l) => l.n));
    for (const l of leaves) {
      if (drop.has(l.n)) {
        l.parent.children = l.parent.children.filter((c) => c !== l.n);
        if (l.parent.children.length === 0) delete l.parent.children;
      }
    }
    size = Buffer.byteLength(JSON.stringify(result));
  }
  return true;
}

/** @returns {{windows: object[], main: number|null, display: {id:number,width:number,height:number}|null, nodes: number, truncated: boolean}} */
export function convertDump(raw, tm) {
  const passwordRe = new RegExp(tm.passwordWords.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'i');
  depthCut = false;
  const windows = [];
  let maxR = 0;
  let maxB = 0;
  for (const w of raw.children ?? []) {
    const a = w.attributes ?? {};
    const bounds = parseBounds(a.bounds);
    if (!bounds) continue;
    maxR = Math.max(maxR, bounds[2]);
    maxB = Math.max(maxB, bounds[3]);
    const bundle = a.bundleName ?? '';
    windows.push({
      bundle,
      ability: a.abilityName ?? '',
      page: a.pagePath ?? '',
      kind: tm.systemBundles.includes(bundle) ? 'system' : 'app',
      bounds,
      root: convNode(w, tm, passwordRe),
    });
  }
  const hasLockRoot = (n) => (n.attributes?.id ?? '').startsWith('ScreenLock') || (n.children ?? []).some(hasLockRoot);
  // 锁屏判据（夹具 05）：只有 sceneboard 窗口且存在 ScreenLock* 节点
  const locked = windows.length > 0 && windows.every((w) => w.bundle === 'com.ohos.sceneboard') && (raw.children ?? []).some(hasLockRoot);
  // 输入法窗口里出现「安全键盘」说明当前焦点是密码类输入框（夹具 03 实测）：dump 里没有密码标志，这是唯一的运行时信号
  const hasSecureText = (n) => [n.attributes?.text, n.attributes?.originalText, n.attributes?.description].some((v) => v?.includes('安全键盘')) || (n.children ?? []).some(hasSecureText);
  const secureKeyboard = (raw.children ?? []).some((w) => tm.inputMethodBundles.includes(w.attributes?.bundleName) && hasSecureText(w));
  if (secureKeyboard) {
    for (const w of windows) {
      if (w.kind !== 'app' || !w.root) continue;
      (function mark(n) {
        if (n.flags?.includes('editable') && n.flags.includes('focused') && !n.flags.includes('password')) n.flags.push('password');
        (n.children ?? []).forEach(mark);
      })(w.root);
    }
  }
  const mainIdx = windows.findIndex((w) => w.kind === 'app');
  const result = {
    display: maxR && maxB ? { id: 0, width: maxR, height: maxB } : null,
    locked,
    secureKeyboard,
    main: windows.length === 0 ? null : mainIdx >= 0 ? mainIdx : 0,
    windows,
  };
  const truncated = truncate(result) || depthCut;
  const nodes = windows.reduce((s, w) => s + (w.root ? countNodes(w.root) : 0), 0);
  return { ...result, nodes, truncated };
}
