import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { tokensShapeOk } from './validate.mjs';

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

export const sha256hex = (s) => crypto.createHash('sha256').update(s).digest('hex');

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(a).digest();
  const hb = crypto.createHash('sha256').update(b).digest();
  return crypto.timingSafeEqual(ha, hb);
}

/** 配对码归一化：大小写不敏感，去分隔符，Crockford 易混字符 O→0 I/L→1。客户端按同一规则处理。 */
export function normalizeCode(code) {
  return String(code).toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
}

export function pairKey(code, nonce) {
  return crypto.pbkdf2Sync(normalizeCode(code), nonce, 200000, 32, 'sha256');
}

export const hmac = (key, msg) => crypto.createHmac('sha256', key).update(msg).digest();

export function createAuth({ home, now = () => Date.now(), warn = (m) => console.error(`dsh-relay: ${m}`) }) {
  fs.mkdirSync(home, { recursive: true, mode: 0o700 });
  const tokensPath = path.join(home, 'tokens.json');
  const controlPath = path.join(home, 'control.token');

  let tokens = [];
  let raw = null;
  try {
    raw = fs.readFileSync(tokensPath, 'utf8');
  } catch (e) {
    if (e.code !== 'ENOENT') warn(`读不了 ${tokensPath}（${e.code}），令牌表按空处理`);
  }
  if (raw !== null) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {}
    if (tokensShapeOk(parsed)) tokens = parsed;
    else {
      // 损坏的令牌表不能被下一次保存静默覆盖：先备份，再告警，所有手机需要重新配对
      let bad = `${tokensPath}.bad`;
      if (fs.existsSync(bad)) bad = `${bad}.${now()}`;
      try {
        fs.renameSync(tokensPath, bad);
        warn(`tokens.json 损坏，已备份为 ${bad}，所有手机需要重新配对`);
      } catch (e) {
        warn(`tokens.json 损坏且备份失败（${e.code}），所有手机需要重新配对`);
      }
    }
  }
  let dirtyAt = 0;
  const save = () => {
    const tmp = `${tokensPath}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(tokens, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, tokensPath);
  };

  if (!fs.existsSync(controlPath)) {
    fs.writeFileSync(controlPath, crypto.randomBytes(32).toString('base64url'), { mode: 0o600 });
  }
  const controlToken = fs.readFileSync(controlPath, 'utf8').trim();

  // 失败限速：同一来源 60 秒内 5 次失败后封 60 秒
  const fails = new Map();
  const FAIL_LIMIT = 5;
  const WINDOW_MS = 60_000;
  function blocked(ip) {
    const e = fails.get(ip);
    return !!e && e.blockedUntil > now();
  }
  function fail(ip) {
    const t = now();
    let e = fails.get(ip);
    if (!e || t - e.start > WINDOW_MS) e = { start: t, count: 0, blockedUntil: 0 };
    e.count += 1;
    if (e.count >= FAIL_LIMIT) {
      e.blockedUntil = t + WINDOW_MS;
      e.count = 0;
      e.start = t;
    }
    fails.set(ip, e);
  }

  function verifyBearer(header, ip) {
    const m = /^Bearer ([A-Za-z0-9_-]{20,128})$/.exec(header ?? '');
    if (!m) return null;
    const hash = sha256hex(m[1]);
    const entry = tokens.find((t) => safeEqual(t.hash, hash));
    if (!entry) return null;
    entry.lastIp = ip;
    entry.lastAt = now();
    if (now() - dirtyAt > 30_000) {
      dirtyAt = now();
      try {
        save();
      } catch (e) {
        warn(`保存 tokens.json 失败（${e.code ?? e.message}），最近来源时间没落盘`);
      }
    }
    return { id: entry.hash.slice(0, 6), name: entry.name };
  }

  function verifyControl(header) {
    const m = /^Bearer (.+)$/.exec(header ?? '');
    return !!m && safeEqual(m[1], controlToken);
  }

  // 配对码：10 位 Crockford base32，5 分钟，最多验证 5 次，成功即作废。只在内存。
  let pairing = null;
  function startPairing() {
    const bytes = crypto.randomBytes(10);
    let code = '';
    for (const b of bytes) code += CROCKFORD[b % 32];
    pairing = { code, expiresAt: now() + 5 * 60_000, attempts: 0 };
    return { code: `${code.slice(0, 5)}-${code.slice(5)}`, expiresAt: pairing.expiresAt };
  }

  /** 返回 {ok:true, token, serverProof} 或 {ok:false, reason}。 */
  function completePairing({ nonce, seenFp, proof, name }, realFp, ip) {
    if (!pairing || pairing.expiresAt < now()) {
      pairing = null;
      return { ok: false, reason: 'no_active_code' };
    }
    pairing.attempts += 1;
    const attempts = pairing.attempts;
    const nonceBuf = Buffer.from(String(nonce), 'hex');
    const key = pairKey(pairing.code, nonceBuf);
    const expected = hmac(key, `pair|${seenFp}`).toString('hex');
    const good = seenFp === realFp && safeEqual(String(proof), expected);
    if (!good) {
      if (attempts >= 5) pairing = null;
      return { ok: false, reason: 'bad_proof' };
    }
    pairing = null;
    const token = crypto.randomBytes(32).toString('base64url');
    tokens.push({
      hash: sha256hex(token),
      name: String(name ?? 'unnamed').slice(0, 64),
      createdAt: now(),
      lastIp: ip,
      lastAt: now(),
    });
    try {
      save();
    } catch (e) {
      // 令牌没落盘就不能发给手机：重启后它会变成无效令牌
      tokens.pop();
      warn(`保存 tokens.json 失败（${e.code ?? e.message}），配对未完成`);
      return { ok: false, reason: 'save_failed' };
    }
    return {
      ok: true,
      token,
      id: sha256hex(token).slice(0, 6),
      serverProof: hmac(key, `srv|${realFp}`).toString('hex'),
    };
  }

  /** which 只接受 'all' 或恰好 6 位 hex（/v1 之外 list 里给出的 id）；其它返回 null，由调用方回 422。 */
  function revoke(which) {
    if (which !== 'all' && !/^[0-9a-f]{6}$/.test(which)) return null;
    const before = tokens.length;
    tokens = which === 'all' ? [] : tokens.filter((t) => !t.hash.startsWith(which));
    save();
    return before - tokens.length;
  }

  const list = () =>
    tokens.map((t) => ({ id: t.hash.slice(0, 6), name: t.name, createdAt: t.createdAt, lastIp: t.lastIp, lastAt: t.lastAt }));

  return { verifyBearer, verifyControl, blocked, fail, startPairing, completePairing, revoke, list, controlToken };
}
