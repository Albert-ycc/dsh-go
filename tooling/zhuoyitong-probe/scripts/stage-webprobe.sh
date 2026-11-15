#!/bin/bash
# 备齐「Web 探针」的页面资产到 app/src/main/assets/webprobe（在 .gitignore 里，不进库）：
#   index.html            webprobe/index.html（测试页）
#   pdfjs410/             伴读在用的 pdf.js 4.10.38 现代版 + worker-poly.mjs（先装补丁再进 worker）
#   pdfjs56l/             pdf.js 5.6.205 的 legacy 构建（取自本机 codex-security 的依赖）
#   poly/                 dsh-go 桥的 web-polyfills.js + install.mjs（另补几项）
#   samples/text.pdf      文字版样本（SRC_TEXT 整份，15 页，内嵌方正字体，带权限加密）
#   samples/scan.pdf      扫描版样本（SRC_SCAN 的第 30–32 页）
#   samples/scan-big.pdf  大图扫描样本（SRC_SCAN_BIG 的第 30 页）
#   mac-url.txt           电脑局域网调试页地址（MAC_URL，可空）
# 需要 poppler 的 pdfseparate / pdfunite。
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/webprobe
PDFJS410=${PDFJS410:-$HOME/workspace/reading-companion-dev/web/vendor/pdfjs}
PDFJS56=${PDFJS56:-/usr/local/lib/node_modules/@openai/codex-security/node_modules/pdfjs-dist}
POLY=../../runtime/bridge/dsh-go-bridge/lib/web-polyfills.js
# 三份样本 PDF 由调用方给路径（本机书库，不进仓库）
SRC_TEXT=${SRC_TEXT:?设 SRC_TEXT 为文字版样本 PDF（约 15 页，带权限加密更好）}
SRC_SCAN=${SRC_SCAN:?设 SRC_SCAN 为扫描版样本 PDF（取第 30–32 页）}
SRC_SCAN_BIG=${SRC_SCAN_BIG:?设 SRC_SCAN_BIG 为大图扫描样本 PDF（取第 30 页）}

rm -rf "$OUT"; mkdir -p "$OUT"/{pdfjs410,pdfjs56l,poly,samples}
cp webprobe/index.html "$OUT/"
printf '%s' "${MAC_URL:-}" > "$OUT/mac-url.txt"

cp -R "$PDFJS410"/pdf.min.mjs "$PDFJS410"/pdf.worker.min.mjs "$PDFJS410"/cmaps "$PDFJS410"/standard_fonts "$OUT/pdfjs410/"
printf 'import "../poly/install.mjs";\nimport "./pdf.worker.min.mjs";\n' > "$OUT/pdfjs410/worker-poly.mjs"

cp "$PDFJS56"/legacy/build/pdf.min.mjs "$PDFJS56"/legacy/build/pdf.worker.min.mjs "$OUT/pdfjs56l/"
cp -R "$PDFJS56"/cmaps "$PDFJS56"/standard_fonts "$PDFJS56"/wasm "$PDFJS56"/iccs "$OUT/pdfjs56l/"

cp "$POLY" "$OUT/poly/web-polyfills.js"
cat > "$OUT/poly/install.mjs" <<'JS'
// 页面与 worker 共用：先装 dsh-go 桥现成的那批，再补 pdf.js 另外用到的几项
import { installWebPolyfills } from "./web-polyfills.js";
installWebPolyfills(globalThis);
const def = (t, k, v) => { if (t && !(k in t)) Object.defineProperty(t, k, { value: v, writable: true, configurable: true }); };
function at(i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return i < 0 || i >= this.length ? undefined : this[i]; }
def(Array.prototype, "at", at); def(String.prototype, "at", at);
def(Object.getPrototypeOf(Uint8Array.prototype), "at", at);
def(URL, "parse", (u, b) => { try { return new URL(u, b); } catch { return null; } });
def(Array, "fromAsync", async (it, fn) => { const out = []; let i = 0; for await (const v of it) out.push(fn ? await fn(v, i++) : v); return out; });
def(Object, "groupBy", (it, fn) => { const o = Object.create(null); let i = 0; for (const v of it) (o[fn(v, i++)] ||= []).push(v); return o; });
JS

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cut_pages() { # 源 起页 止页 目标
  pdfseparate -f "$2" -l "$3" "$1" "$W/p-%d.pdf"
  if [ "$2" = "$3" ]; then cp "$W/p-$2.pdf" "$4"; else pdfunite $(seq "$2" "$3" | sed "s|^|$W/p-|;s|$|.pdf|") "$4"; fi
  rm -f "$W"/p-*.pdf
}
cp "$SRC_TEXT" "$OUT/samples/text.pdf"   # 整份带上：这份期刊稿带 RC4 权限加密，poppler 拼不了页
cut_pages "$SRC_SCAN" 30 32 "$OUT/samples/scan.pdf"
cut_pages "$SRC_SCAN_BIG" 30 30 "$OUT/samples/scan-big.pdf"

du -sh "$OUT" "$OUT"/*
