import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { StartupError } from './errors.mjs';

/** 叶证书 SHA-256（小写十六进制，无冒号）。客户端钉扎的就是这个值。 */
export function fingerprintOfPem(pem) {
  return new crypto.X509Certificate(pem).fingerprint256.replaceAll(':', '').toLowerCase();
}

export function fingerprintOfDer(der) {
  return crypto.createHash('sha256').update(der).digest('hex');
}

/** 首次启动生成 ECDSA P-256 自签证书，用系统自带 openssl，不引入依赖。 */
export function ensureCert(home) {
  const dir = path.join(home, 'tls');
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const keyPath = path.join(dir, 'key.pem');
  const certPath = path.join(dir, 'cert.pem');
  let regenerated = false;
  let keyModeFixed = false;
  if (!fs.existsSync(keyPath) || !fs.existsSync(certPath)) {
    const openssl = process.env.OPENSSL_BIN || '/usr/bin/openssl';
    try {
      execFileSync(
        openssl,
        [
          'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
          '-days', '3650', '-subj', '/CN=dsh-relay', '-keyout', keyPath, '-out', certPath,
        ],
        { stdio: 'pipe' },
      );
    } catch (e) {
      throw new StartupError(`生成证书失败（${openssl}：${e.code ?? 'exit ' + e.status}），检查 openssl 是否可用`);
    }
    regenerated = true;
  }
  // 私钥权限收紧到 0600；证书是公开的，同样 0600 保持一致
  for (const [p, isKey] of [[keyPath, true], [certPath, false]]) {
    if ((fs.statSync(p).mode & 0o777) !== 0o600) {
      fs.chmodSync(p, 0o600);
      if (isKey) keyModeFixed = true;
    }
  }
  let key, cert, fingerprint, validTo;
  try {
    key = fs.readFileSync(keyPath);
    cert = fs.readFileSync(certPath);
    fingerprint = fingerprintOfPem(cert);
    validTo = new crypto.X509Certificate(cert).validTo;
    crypto.createPrivateKey(key);
  } catch {
    throw new StartupError(`证书或私钥损坏：${dir}；删除该目录后重启会重新生成，所有手机需要重新配对`);
  }
  return { key, cert, fingerprint, validTo, regenerated, keyModeFixed };
}
