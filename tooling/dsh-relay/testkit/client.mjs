// 测试与真机验收共用的最小客户端：配对（与 Kotlin 同一套编码）、带头请求。
import https from 'node:https';
import crypto from 'node:crypto';
import { pairKey, hmac } from '../lib/auth.mjs';
import { fingerprintOfDer } from '../lib/tls.mjs';

export function request({ host = '127.0.0.1', port, method = 'GET', path, headers = {}, body, token, noRelayHeader = false, timeout = 30000 }) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? null : JSON.stringify(body);
    const h = { ...headers };
    if (!noRelayHeader) h['x-dsh-relay'] = '1';
    if (token) h.authorization = `Bearer ${token}`;
    if (data) {
      h['content-type'] = 'application/json';
      h['content-length'] = Buffer.byteLength(data);
    }
    const req = https.request({ host, port, method, path, headers: h, rejectUnauthorized: false, timeout, maxVersion: 'TLSv1.2', agent: false }, (res) => {
      const c = [];
      const fp = fingerprintOfDer(res.socket.getPeerCertificate().raw);
      res.on('data', (d) => c.push(d));
      res.on('end', () => {
        const text = Buffer.concat(c).toString('utf8');
        let json = null;
        try { json = JSON.parse(text); } catch {}
        resolve({ status: res.statusCode, json, text, headers: res.headers, fp });
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('client timeout')));
    if (data) req.write(data);
    req.end();
  });
}

/** 与方案 5.5 / P2-b 约定一致：nonce、proof 小写 hex。 */
export async function pairWith({ host, port, code, name = 'test' }) {
  const probe = await request({ host, port, path: '/v1/ping' });
  const seenFp = probe.fp;
  const nonce = crypto.randomBytes(16);
  const key = pairKey(code, nonce);
  const proof = hmac(key, `pair|${seenFp}`).toString('hex');
  const r = await request({ host, port, method: 'POST', path: '/v1/pair', body: { nonce: nonce.toString('hex'), seenFp, proof, name } });
  if (r.status === 200) {
    const expect = hmac(key, `srv|${seenFp}`).toString('hex');
    if (r.json.serverProof !== expect) throw new Error('serverProof mismatch');
  }
  return r;
}
