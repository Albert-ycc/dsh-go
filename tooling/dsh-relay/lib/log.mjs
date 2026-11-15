import fs from 'node:fs';
import path from 'node:path';

function localIso(d) {
  const pad = (n, w = 2) => String(n).padStart(w, '0');
  const off = -d.getTimezoneOffset();
  const sign = off >= 0 ? '+' : '-';
  const a = Math.abs(off);
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}` +
    `${sign}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
  );
}

/** 每行一个 JSON，10 MB 滚动保留 5 份（方案 4.9）。调用方负责脱敏，这里不过滤字段。 */
export function createLogger({ dir, maxBytes = 10 * 1024 * 1024, keep = 5, now = () => Date.now() }) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const file = path.join(dir, 'relay.log');
  let size = 0;
  try {
    size = fs.statSync(file).size;
  } catch {}
  const recentErrors = [];

  function rotate() {
    for (let i = keep - 1; i >= 1; i--) {
      const from = `${file}.${i}`;
      if (fs.existsSync(from)) fs.renameSync(from, `${file}.${i + 1}`);
    }
    fs.renameSync(file, `${file}.1`);
    // 超出保留份数的最老一份
    try {
      fs.rmSync(`${file}.${keep + 1}`, { force: true });
    } catch {}
    size = 0;
  }

  let lastStderrAt = 0;
  /** 日志写不进去（盘满、权限、只读）不能拖垮请求：降级为 stderr 一行，同一类失败 60 秒内只报一次。 */
  function write(entry) {
    let line;
    try {
      line = JSON.stringify({ ts: localIso(new Date(now())), ...entry }) + '\n';
    } catch {
      return;
    }
    try {
      if (size + line.length > maxBytes && size > 0) rotate();
      fs.appendFileSync(file, line, { mode: 0o600 });
      size += Buffer.byteLength(line);
    } catch (e) {
      const t = Date.now();
      if (t - lastStderrAt > 60_000) {
        lastStderrAt = t;
        console.error(`dsh-relay: 写日志失败 ${file}（${e.code ?? e.message}），请求照常处理，日志丢失`);
      }
    }
    if (entry.ok === false || entry.op === 'auth_fail') {
      recentErrors.push(JSON.parse(line));
      if (recentErrors.length > 20) recentErrors.shift();
    }
  }
  return { write, file, recentErrors };
}
