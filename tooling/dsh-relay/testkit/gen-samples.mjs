// 生成 contract/samples/pair-vector.json（固定向量，Kotlin 测试对拍用）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pairKey, hmac } from '../lib/auth.mjs';
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'contract', 'samples');
const code = '7K3QM-9XD2A';
const nonceHex = '000102030405060708090a0b0c0d0e0f';
const fp = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
const key = pairKey(code, Buffer.from(nonceHex, 'hex'));
fs.writeFileSync(path.join(root, 'pair-vector.json'), JSON.stringify({
  note: 'code 归一化后为大写 Crockford base32 无分隔符；password=UTF-8(code)，salt=nonce 原始字节，PBKDF2-HMAC-SHA256 200000 次 32 字节；全部输出小写 hex。',
  code, normalizedCode: '7K3QM9XD2A', nonceHex, seenFp: fp,
  keyHex: key.toString('hex'),
  proofHex: hmac(key, `pair|${fp}`).toString('hex'),
  serverProofHex: hmac(key, `srv|${fp}`).toString('hex'),
}, null, 2) + '\n');
