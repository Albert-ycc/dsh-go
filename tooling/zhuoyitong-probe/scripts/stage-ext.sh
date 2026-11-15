#!/bin/bash
# 备齐冒烟需要的外部文件，放进 App 的 assets/jniLibs（都在 .gitignore 里，不进库）：
#   jniLibs/arm64-v8a/libnode.so      T1 产物（node 可执行文件，PIE）
#   assets/ext/node_modules/...       sharp + @img/sharp-wasm32 + 依赖（取自 ~/.local/share/dsh-cli）
#   assets/ext/bin/rg                 官方 linux-arm64 静态 rg（npm 包 @vscode/ripgrep-linux-arm64@1.18.0，校验 sha256）
#   assets/ext/bionic/system.node     NDK 编的 flock 原生件（node-addon-system/src/flock.c）
# 外部文件走 assets 而不是 hdc file send：App 启动时由 Kotlin 拷到 filesDir/smoke/ext，
# 不依赖手机上哪个共享目录 App 读得到。
set -euo pipefail
cd "$(dirname "$0")/.."
source ../env-mac.sh
ROOT=$(cd ../.. && pwd)
NODE_SRC=${NODE_SRC:-$ROOT/runtime/android-inproc/out/arm64-v8a/node}
NODE_SHA=3b76a38328f26ce08ae231544eef3e4050a0720bf3ec10cffeca135cefe5d1ae
RG_TGZ_SHA1=81ffe7207ead1ea223e0cba91db0b8494545627e
RG_SHA256=e152ea689d6e8420357e592f0d8253b96476c164118ca3e6e13074fa1705ddda
DSH_NM=$HOME/.local/share/dsh-cli/node_modules
MAIN=app/src/main
EXT=$MAIN/assets/ext
WORK=${TMPDIR:-/tmp}/zyt-stage.$$
mkdir -p "$WORK"; trap 'rm -rf "$WORK"' EXIT

[ -f "$NODE_SRC" ] || { echo "T1 产物不存在: $NODE_SRC" >&2; exit 2; }
got=$(shasum -a 256 "$NODE_SRC" | cut -d' ' -f1)
[ "$got" = "$NODE_SHA" ] || echo "注意: node sha256 $got 与派单记录的 $NODE_SHA 不同（T1 重编过则正常）" >&2
mkdir -p $MAIN/jniLibs/arm64-v8a
cp "$NODE_SRC" $MAIN/jniLibs/arm64-v8a/libnode.so
echo "libnode.so  $got"

rm -rf "$EXT"; mkdir -p $EXT/node_modules/@img $EXT/node_modules/@emnapi $EXT/bin $EXT/bionic
for p in sharp detect-libc semver; do cp -R "$DSH_NM/$p" $EXT/node_modules/$p; done
rm -rf $EXT/node_modules/sharp/src
cp -R "$DSH_NM/@img/colour" "$DSH_NM/@img/sharp-wasm32" $EXT/node_modules/@img/
cp -R "$DSH_NM/@emnapi/runtime" $EXT/node_modules/@emnapi/

( cd "$WORK" && npm pack @vscode/ripgrep-linux-arm64@1.18.0 >/dev/null 2>&1 && \
  [ "$(shasum -a 1 vscode-ripgrep-linux-arm64-1.18.0.tgz | cut -d' ' -f1)" = "$RG_TGZ_SHA1" ] && \
  tar xzf vscode-ripgrep-linux-arm64-1.18.0.tgz )
cp "$WORK/package/bin/rg" $EXT/bin/rg
[ "$(shasum -a 256 $EXT/bin/rg | cut -d' ' -f1)" = "$RG_SHA256" ] || { echo "rg sha256 不符" >&2; exit 3; }
echo "rg          $RG_SHA256"

CLANG=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang
INC=$ROOT/runtime/android-inproc/.cache/tree/node-v24.22.1/src
[ -f "$INC/node_api.h" ] || { echo "缺 node_api.h: $INC" >&2; exit 4; }
"$CLANG" -shared -fPIC -O2 -I"$INC" "$DSH_NM/@deepseek-ai/node-addon-system/src/flock.c" -o $EXT/bionic/system.node
echo "system.node $(shasum -a 256 $EXT/bionic/system.node | cut -d' ' -f1)"
du -sh $EXT $MAIN/jniLibs
