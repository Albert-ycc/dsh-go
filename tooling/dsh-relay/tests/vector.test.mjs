import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pairKey, hmac, normalizeCode } from '../lib/auth.mjs';

const v = JSON.parse(fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'contract', 'samples', 'pair-vector.json'), 'utf8'));

test('配对固定向量：Kotlin 侧对拍用的 key/proof/serverProof 可复现', () => {
  assert.equal(normalizeCode('7k3qm 9xd2a'), v.normalizedCode);
  assert.equal(normalizeCode('O0-IL'), '00' + '11');
  const key = pairKey(v.code, Buffer.from(v.nonceHex, 'hex'));
  assert.equal(key.toString('hex'), v.keyHex);
  assert.equal(hmac(key, `pair|${v.seenFp}`).toString('hex'), v.proofHex);
  assert.equal(hmac(key, `srv|${v.seenFp}`).toString('hex'), v.serverProofHex);
});
