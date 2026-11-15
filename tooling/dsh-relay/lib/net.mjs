import os from 'node:os';

export function parseIPv4(s) {
  // 八位组不许前导零：hdc 可能按八进制解析 010，与 list targets 的 key 对不上
  const m = /^(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})$/.exec(s);
  if (!m) return null;
  const p = m.slice(1).map(Number);
  if (p.some((n) => n > 255)) return null;
  return ((p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]) >>> 0;
}

function inCidr(ip, base, bits) {
  const a = parseIPv4(ip);
  const b = parseIPv4(base);
  if (a === null || b === null) return false;
  const mask = bits === 0 ? 0 : (~0 << (32 - bits)) >>> 0;
  return (a & mask) >>> 0 === (b & mask) >>> 0;
}

export const isLoopback = (ip) => inCidr(ip, '127.0.0.0', 8);
export const isPrivate = (ip) =>
  inCidr(ip, '10.0.0.0', 8) || inCidr(ip, '172.16.0.0', 12) || inCidr(ip, '192.168.0.0', 16);
export const isTailscale = (ip) => inCidr(ip, '100.64.0.0', 10);
/** 本机代理 TUN 网段，既不监听也不接受来自它的连接。 */
export const isProxyNet = (ip) => inCidr(ip, '198.18.0.0', 15);

export function normalizeRemote(addr) {
  if (!addr) return '';
  return addr.startsWith('::ffff:') ? addr.slice(7) : addr;
}

/** 来源白名单：回环、RFC1918、Tailscale（方案 4.1）。IPv6 回环也放行。 */
export function defaultSourceAllowed(ip) {
  ip = normalizeRemote(ip);
  if (ip === '::1') return true;
  if (isProxyNet(ip)) return false;
  return isLoopback(ip) || isPrivate(ip) || isTailscale(ip);
}

/** 可监听的网卡地址：私网 IPv4 + Tailscale，不含回环（回环单独处理），不含 198.18/15。 */
export function listenAddresses(ifaces = os.networkInterfaces()) {
  const out = new Set();
  for (const list of Object.values(ifaces)) {
    for (const i of list ?? []) {
      if (i.family !== 'IPv4' && i.family !== 4) continue;
      if (i.internal) continue;
      if (isProxyNet(i.address)) continue;
      if (isPrivate(i.address) || isTailscale(i.address)) out.add(i.address);
    }
  }
  return [...out].sort();
}
