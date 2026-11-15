#!/usr/bin/env bash
# 用 NDK 编 libdsh_sysprobe.so（bionic PIE 可执行文件）。需要先 `source tooling/env-mac.sh`。
#   runtime/android-inproc/sysprobe/build-sysprobe.sh [输出目录]   默认 runtime/android-inproc/.cache/sysprobe
# 输出 <目录>/libdsh_sysprobe.so，同输入两次构建字节相同。接口见 probe.c 文件头。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INPROC="$(cd "$HERE/.." && pwd)"
: "${ANDROID_NDK_HOME:?先 source tooling/env-mac.sh}"
OUT="${1:-$INPROC/.cache/sysprobe}"
API=26
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$TC/aarch64-linux-android${API}-clang"
[[ -x "$CC" ]] || { echo "missing: $CC" >&2; exit 1; }
mkdir -p "$OUT"
# 16 KiB 页对齐、无 build-id、路径前缀归一，保证可复现。
"$CC" -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -ffile-prefix-map="$HERE"=. \
  -Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now \
  -o "$OUT/libdsh_sysprobe.so" "$HERE/probe.c"
"$TC/llvm-strip" --strip-unneeded "$OUT/libdsh_sysprobe.so"
chmod 0755 "$OUT/libdsh_sysprobe.so"
echo "built $OUT/libdsh_sysprobe.so"
