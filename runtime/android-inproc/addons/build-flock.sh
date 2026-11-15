#!/usr/bin/env bash
# 用 NDK 编 bionic 版 system.node（只含 flock）。需要先 `source tooling/env-mac.sh`。
#   runtime/android-inproc/addons/build-flock.sh [输出目录]    默认 runtime/android-inproc/.cache/addons（out/ 归 T1，这里不写）
# 头文件取自 T1 缓存的 node-v24.22.1/src（NODE_INCLUDE 可覆盖）；不链接 libnode，napi_* 由宿主 node 导出。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INPROC="$(cd "$HERE/.." && pwd)"
: "${ANDROID_NDK_HOME:?先 source tooling/env-mac.sh}"
NODE_INCLUDE="${NODE_INCLUDE:-$INPROC/.cache/tree/node-v24.22.1/src}"
OUT="${1:-$INPROC/.cache/addons}"
API=26
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$TC/aarch64-linux-android${API}-clang"
for f in "$CC" "$NODE_INCLUDE/node_api.h" "$NODE_INCLUDE/js_native_api.h"; do
  [[ -e "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done
mkdir -p "$OUT"
# -Wl,--build-id=none 与 -ffile-prefix-map 让同输入两次构建字节相同；
# -z undefs（默认）保留 napi_* 未定义；--no-undefined 不能加。
"$CC" -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared -fvisibility=hidden \
  -DNAPI_VERSION=8 -DBUILDING_NODE_EXTENSION \
  -ffile-prefix-map="$HERE"=. -I"$NODE_INCLUDE" \
  -Wl,--build-id=none -Wl,-soname,system.node -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now \
  -o "$OUT/system.node" "$HERE/flock-android.c"
"$TC/llvm-strip" --strip-unneeded "$OUT/system.node"
echo "built $OUT/system.node"
