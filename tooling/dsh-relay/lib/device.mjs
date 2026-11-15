import fs from 'node:fs';
import path from 'node:path';
import { RelayError } from './errors.mjs';
import { isPrivate, parseIPv4 } from './net.mjs';

const BACKOFF_S = [2, 4, 8, 15, 30];
const TRUST_MS = 20_000;
const BINDING_HINT = '绑定文件损坏，请用 dsh-relay target 重新绑定';
/** locked 只在最近这么久内读过屏才对外给 true/false，过期给 null（锁屏状态会变，旧值比没有更糟） */
export const LOCKED_FRESH_MS = 15_000;

/** hdc list targets -v：`<key>\t<USB|TCP|UART>\t<Connected|Offline|Unauthorized>\t<host>` */
export function parseTargets(text) {
  const out = [];
  for (const line of text.split('\n')) {
    const p = line.trim().split(/\s+/);
    if (p.length < 3) continue;
    const [key, kind, status] = p;
    if (!/^(USB|TCP|UART|BLE)$/i.test(kind)) continue;
    out.push({ key, kind: kind.toLowerCase(), status: status.toLowerCase() });
  }
  return out;
}

export function validTcpAddr(addr) {
  const m = /^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$/.exec(String(addr));
  if (!m || parseIPv4(m[1]) === null || !isPrivate(m[1])) return false;
  const port = Number(m[2]);
  return port >= 1024 && port <= 65535;
}

/**
 * hdc 自身报的连接类失败（命令没到设备）。只认回显开头：设备命令的输出里可能带 offline 之类的字样
 * （比如应用名 com.example.offlinemaps），在设备在线与否的判断上不能用子串匹配。
 */
function hdcLevelFailure(text) {
  const t = text.trimStart();
  if (/^\[E000002\]|^\[Fail\].*unauthori[sz]ed/i.test(t)) return 'device_unauthorized';
  if (/^(\[Fail\]|\[E00\d+\]|Connect server failed)/.test(t)) return 'device_offline';
  return null;
}

export class Device {
  constructor({ home, config, now = () => Date.now(), warn = () => {} }) {
    this.home = home;
    this.config = config;
    this.now = now;
    this.target = null; // 当前选中的 hdc connect key
    this.via = null;
    this.model = null;
    this.state = 'offline';
    this.locked = null;
    this.lockedAt = 0;
    this.since = now();
    this.verifiedAt = 0;
    this.failCount = 0;
    this.retryAt = 0;
    this.display = config.display ?? null; // {width,height}，读屏时更新
    // 与配对的那台手机绑定：只认记下的 USB 序列号和无线地址，不回退到别的设备
    this.bound = { serial: config.device?.serial ?? null, addr: null };
    this.bindFile = path.join(home, 'binding.json');
    // 绑定文件损坏不能等于解绑：解绑后下一次连接会把唯一在线的设备（可能是别的手机）自动绑上，绕开 M2。
    // 损坏就改名 .bad 并告警；存在 .bad 且没有新绑定文件时，不自动绑定任何候选，等用户显式 target
    this.bindingCorrupt = false;
    let text = null;
    try {
      text = fs.readFileSync(this.bindFile, 'utf8');
    } catch (e) {
      if (e.code !== 'ENOENT') this.bindingCorrupt = true;
    }
    if (text !== null) {
      let b = null;
      try {
        b = JSON.parse(text);
      } catch {}
      const okShape = b && typeof b === 'object' && !Array.isArray(b) && [b.serial, b.addr].every((v) => v === undefined || v === null || typeof v === 'string');
      if (okShape) this.bound = { serial: this.bound.serial ?? b.serial ?? null, addr: b.addr ?? null };
      else {
        let bad = `${this.bindFile}.bad`;
        if (fs.existsSync(bad)) bad = `${bad}.${now()}`;
        try {
          fs.renameSync(this.bindFile, bad);
          warn(`binding.json 损坏，已备份为 ${bad}；在 dsh-relay target 重新绑定之前不会自动绑定任何设备`);
        } catch (e) {
          warn(`binding.json 损坏且备份失败（${e.code}）；在 dsh-relay target 重新绑定之前不会自动绑定任何设备`);
        }
        this.bindingCorrupt = true;
      }
    } else {
      try {
        if (fs.readdirSync(home).some((f) => f.startsWith('binding.json.bad'))) this.bindingCorrupt = true;
      } catch {}
    }
    this.lastActiveAt = 0;
  }

  setState(state) {
    if (state !== this.state) {
      this.state = state;
      this.since = this.now();
    }
  }

  get tcpAddr() {
    return this.bound.addr;
  }

  #saveBinding() {
    fs.writeFileSync(this.bindFile, JSON.stringify({ serial: this.bound.serial, addr: this.bound.addr }), { mode: 0o600 });
  }

  /** addr 为 null 表示忘掉绑定，下次连接重新首次绑定 */
  setTcpAddr(addr) {
    this.bindingCorrupt = false; // 显式 target（含 auto）就是用户重新做了选择
    if (addr) this.bound.addr = addr;
    else this.bound = { serial: this.config.device?.serial ?? null, addr: null };
    this.#saveBinding();
    this.markStale();
    this.failCount = 0;
    this.retryAt = 0;
  }

  markStale() {
    this.verifiedAt = 0;
  }

  setLocked(v) {
    this.locked = v;
    this.lockedAt = this.now();
  }

  /** 对外的 locked：15 秒内读过屏才有值，否则 null */
  lockedFresh() {
    return this.locked !== null && this.now() - this.lockedAt <= LOCKED_FRESH_MS ? this.locked : null;
  }

  /**
   * 合并显示器尺寸：按维度取已见过的最大值（键盘、半屏窗口会让窗口外接框变小，不能让 display 跳）。
   * authoritative 为截图尺寸时直接覆盖（折叠屏换形态后以截图为准）。
   */
  mergeDisplay(size, authoritative = false) {
    if (!size) return this.display;
    this.display = authoritative || !this.display
      ? { width: size.width, height: size.height }
      : { width: Math.max(this.display.width, size.width), height: Math.max(this.display.height, size.height) };
    return this.display;
  }

  status() {
    return {
      state: this.state,
      via: this.via,
      target: this.target,
      model: this.model,
      since: this.since,
      locked: this.lockedFresh(),
      screenOn: null,
      ...(this.bindingCorrupt && !this.bound.serial && !this.bound.addr ? { hint: BINDING_HINT } : {}),
    };
  }

  /** 执行 `hdc -t X shell cmd`，hdc 层失败翻成错误码，设备命令的回显原样返回。 */
  async shell(ctx, target, cmd, opts = {}) {
    const res = await ctx.run(['-t', target, 'shell', cmd], opts);
    const bad = hdcLevelFailure(res.text) ?? (res.code !== 0 ? hdcLevelFailure(res.stderr) ?? 'command_failed' : null);
    if (bad === 'device_offline' || bad === 'device_unauthorized') {
      this.markStale();
      this.setState(bad === 'device_unauthorized' ? 'unauthorized' : 'offline');
      throw new RelayError(bad, bad === 'device_unauthorized' ? 'device has not authorized this Mac' : 'device unreachable', {
        retryable: bad === 'device_offline',
        executed: true,
      });
    }
    return res;
  }

  /** 选定并验证目标；缓存期内直接返回。 */
  async acquire(ctx, { passive = false } = {}) {
    if (!passive) this.lastActiveAt = this.now();
    if (this.target && this.now() - this.verifiedAt < TRUST_MS) return this.target;
    return this.connect(ctx);
  }

  async connect(ctx) {
    const prefer = this.config.device?.prefer ?? 'auto';
    if (this.now() < this.retryAt) {
      throw new RelayError('device_offline', 'device reconnecting', { retryable: true, hint: 'wireless link is retrying with backoff' });
    }
    if (this.bindingCorrupt && !this.bound.serial && !this.bound.addr) {
      this.target = null;
      this.setState('offline');
      throw new RelayError('device_offline', 'binding file was damaged; no device is bound', { hint: BINDING_HINT });
    }
    let list = parseTargets((await ctx.run(['list', 'targets', '-v'], { timeoutMs: 3000 })).text);
    const usable = (t) => t.status === 'connected';
    const connected = list.filter(usable);
    const b = this.bound;
    let pick = null;
    if (b.serial || b.addr) {
      // 已绑定：只认绑定的那台，别的设备在线也不回退
      const usbOk = prefer !== 'tcp' ? connected.find((t) => t.kind === 'usb' && b.serial && t.key === b.serial) : null;
      pick = usbOk ?? null;
      if (!pick && prefer !== 'usb' && b.addr) {
        pick = connected.find((t) => t.kind === 'tcp' && t.key === b.addr) ?? null;
        if (!pick) {
          // 无线目标不在线：先 tconn 再复查（Wi-Fi 省电休眠后常见）
          await ctx.run(['tconn', b.addr], { timeoutMs: 8000 });
          list = parseTargets((await ctx.run(['list', 'targets', '-v'], { timeoutMs: 3000 })).text);
          pick = list.find((t) => t.key === b.addr && usable(t)) ?? null;
        }
      }
    } else {
      // 首次使用：只有唯一的候选才自动绑定，多台时必须显式指定，不替用户猜
      const usbs = connected.filter((t) => t.kind === 'usb');
      const tcps = connected.filter((t) => t.kind === 'tcp');
      if (prefer !== 'tcp' && usbs.length > 1) {
        throw new RelayError('device_offline', 'multiple USB devices, set device.serial in config.json', { hint: 'device.serial' });
      }
      if (prefer !== 'tcp' && usbs.length === 1) pick = usbs[0];
      else if (prefer !== 'usb' && tcps.length > 1) {
        throw new RelayError('device_offline', 'multiple wireless devices, run dsh-relay target set <ip:port>', { hint: 'target set' });
      } else if (prefer !== 'usb' && tcps.length === 1) pick = tcps[0];
    }
    if (!pick) {
      const unauth = list.find((t) => t.status === 'unauthorized');
      if (unauth) {
        this.setState('unauthorized');
        throw new RelayError('device_unauthorized', 'device has not authorized this Mac', { hint: 'tap Allow on the phone' });
      }
      return this.#fail(prefer !== 'usb' && !!this.bound.addr);
    }

    this.target = pick.key;
    this.via = pick.kind;
    // 回显 ok 且顺带取机型；3 秒内应答才算在线
    let probe;
    try {
      probe = await this.shell(ctx, pick.key, 'echo ok; param get const.product.model', { timeoutMs: 3000 });
    } catch (e) {
      this.target = null;
      if (e.code === 'device_timeout' || e.code === 'device_offline') return this.#fail(pick.kind === 'tcp', e);
      throw e;
    }
    if (!/^ok/m.test(probe.text)) {
      this.target = null;
      return this.#fail(pick.kind === 'tcp');
    }
    // 首次成功连接即绑定这台手机
    if (pick.kind === 'usb' && !this.bound.serial) {
      this.bound.serial = pick.key;
      this.#saveBinding();
    } else if (pick.kind === 'tcp' && !this.bound.addr) {
      this.bound.addr = pick.key;
      this.#saveBinding();
    }
    this.model = probe.text.split('\n')[1]?.trim() || this.model;
    this.failCount = 0;
    this.retryAt = 0;
    this.verifiedAt = this.now();
    this.setState('ok');
    // 清掉上次异常退出残留的设备端临时文件
    await this.shell(ctx, pick.key, 'rm -rf /data/local/tmp/dsh-relay', { timeoutMs: 2000 }).catch(() => {});
    if (pick.kind === 'usb' && !this.bound.addr) await this.#learnTcp(ctx, pick.key);
    return pick.key;
  }

  /** USB 在位时自学无线调试地址：端口取 persist.hdc.port，地址取 wlan0（读命令，无副作用）。 */
  async #learnTcp(ctx, target) {
    try {
      const r = await this.shell(ctx, target, 'param get persist.hdc.port; ifconfig wlan0', { timeoutMs: 2000 });
      const port = /^(\d{4,5})\s*$/m.exec(r.text)?.[1];
      const ip = /inet (?:addr:)?(\d+\.\d+\.\d+\.\d+)/.exec(r.text)?.[1];
      if (port && ip && validTcpAddr(`${ip}:${port}`)) {
        this.bound.addr = `${ip}:${port}`;
        this.#saveBinding();
      }
    } catch {}
  }

  #fail(tcpInvolved, cause) {
    this.target = null;
    if (tcpInvolved) {
      const wait = BACKOFF_S[Math.min(this.failCount, BACKOFF_S.length - 1)];
      this.failCount += 1;
      this.retryAt = this.now() + wait * 1000;
      this.setState('connecting');
    } else {
      this.setState('offline');
    }
    throw new RelayError('device_offline', 'no reachable device', { retryable: true, hint: cause?.message });
  }
}
