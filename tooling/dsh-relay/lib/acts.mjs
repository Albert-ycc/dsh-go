import { RelayError } from './errors.mjs';

const bad = (msg) => new RelayError('bad_args', msg);

/** 单引号转义：设备端 shell 解释命令，文本参数必须在拼命令这一层转义（方案 4.4）。 */
export function shq(s) {
  return `'${String(s).replaceAll("'", `'\\''`)}'`;
}

export function checkText(text) {
  if (typeof text !== 'string' || text.length === 0) throw bad('text must be a non-empty string');
  // 含 NUL、\n、\t 在内的全部控制字符一律拒绝；回车用 key enter
  if (/[\u0000-\u001f\u007f]/.test(text)) throw bad('text contains control characters');
  if ([...text].length > 500) throw bad('text longer than 500 characters');
  return text;
}

function int(v, name, lo, hi) {
  if (!Number.isInteger(v)) throw bad(`${name} must be an integer`);
  if (v < lo || v > hi) throw bad(`${name} out of range`);
  return v;
}

function point(body, kx, ky, display) {
  const w = display?.width ?? 20000;
  const h = display?.height ?? 20000;
  return [int(body[kx], kx, 0, w - 1), int(body[ky], ky, 0, h - 1)];
}

export const OPS = ['click', 'doubleClick', 'longClick', 'swipe', 'fling', 'key', 'text', 'replaceText'];
export const KEYS = ['back', 'home', 'enter', 'recents', 'notifications'];

/** 不碰设备的参数校验。坐标范围依赖显示器尺寸，display 未知时只做整数和粗上限检查，设备侧再查。 */
export function validateAct(body) {
  if (!body || typeof body !== 'object') throw bad('body required');
  if (!OPS.includes(body.op)) throw bad('unknown op');
  switch (body.op) {
    case 'text':
      checkText(body.text);
      break;
    case 'replaceText':
      checkText(body.text);
      int(body.x, 'x', 0, 20000);
      int(body.y, 'y', 0, 20000);
      if (body.commit !== undefined && typeof body.commit !== 'boolean') throw bad('commit must be a boolean');
      break;
    case 'key':
      if (!KEYS.includes(body.name)) throw bad('unknown key name');
      break;
    case 'swipe':
    case 'fling':
      for (const k of ['x1', 'y1', 'x2', 'y2']) int(body[k], k, 0, 20000);
      if (body.velocity !== undefined) int(body.velocity, 'velocity', 200, 40000);
      if (body.op === 'fling' && body.stepLength !== undefined) int(body.stepLength, 'stepLength', 1, 20000);
      break;
    default:
      int(body.x, 'x', 0, 20000);
      int(body.y, 'y', 0, 20000);
  }
}

/**
 * 把校验过的动作展开成设备命令步骤。cmd 发往设备；log 是脱敏后的形态（文本位只留字数）。
 * @returns {{cmd: string, log: string, pauseMs?: number}[]}
 */
export function planAct(body, { display, keymap, gestures }) {
  const key = (name) => {
    const code = keymap[name];
    if (!Number.isInteger(code)) throw new RelayError('internal', `keymap missing ${name}`);
    return code;
  };
  const textStep = (text) => ({
    cmd: `uitest uiInput text ${shq(text)}`,
    log: `uitest uiInput text <text:${[...text].length} chars>`,
  });
  const plain = (cmd) => ({ cmd, log: cmd });
  switch (body.op) {
    case 'click':
    case 'doubleClick':
    case 'longClick': {
      const [x, y] = point(body, 'x', 'y', display);
      return [plain(`uitest uiInput ${body.op} ${x} ${y}`)];
    }
    case 'swipe':
    case 'fling': {
      const [x1, y1] = point(body, 'x1', 'y1', display);
      const [x2, y2] = point(body, 'x2', 'y2', display);
      const v = body.velocity ?? 600;
      const extra = body.op === 'fling' && body.stepLength !== undefined ? ` ${body.stepLength}` : '';
      return [plain(`uitest uiInput ${body.op} ${x1} ${y1} ${x2} ${y2} ${v}${extra}`)];
    }
    case 'key': {
      if (body.name === 'back') return [plain('uitest uiInput keyEvent Back')];
      if (body.name === 'home') return [plain('uitest uiInput keyEvent Home')];
      if (body.name === 'enter') return [plain(`uitest uiInput keyEvent ${key('ENTER')}`)];
      const g = gestures[body.name];
      if (!g || !display) throw new RelayError('internal', 'gesture needs display size');
      const px = (f, size) => Math.min(size - 1, Math.max(0, Math.round(f * size)));
      return [
        plain(
          `uitest uiInput ${g.op} ${px(g.from[0], display.width)} ${px(g.from[1], display.height)} ` +
            `${px(g.to[0], display.width)} ${px(g.to[1], display.height)} ${g.velocity}`,
        ),
      ];
    }
    case 'text':
      return [textStep(body.text)];
    case 'replaceText': {
      const [x, y] = point(body, 'x', 'y', display);
      const steps = [
        plain(`uitest uiInput click ${x} ${y}`),
        { ...plain(`uitest uiInput keyEvent ${key('CTRL_LEFT')} ${key('A')}`), pauseMs: 150 },
        plain(`uitest uiInput keyEvent ${key('DEL')}`),
        textStep(body.text),
      ];
      // 默认不发回车：回车在表单里可能提交或换行。拼音输入法把拉丁字母先放进组词条、回车才进框的场景，
      // 由调用方回读不一致后带 commit:true 补一次（夹具 NOTES 第 3 节）
      if (body.commit === true) steps.push(plain(`uitest uiInput keyEvent ${key('ENTER')}`));
      return steps;
    }
  }
  throw bad('unknown op');
}

export const needsDisplay = (body) =>
  (body.op === 'key' && (body.name === 'recents' || body.name === 'notifications')) ||
  ['click', 'doubleClick', 'longClick', 'swipe', 'fling', 'replaceText'].includes(body.op);

const BUNDLE_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/;
export function checkBundle(v, name = 'bundle') {
  if (typeof v !== 'string' || !BUNDLE_RE.test(v)) throw bad(`${name} invalid`);
  return v;
}
