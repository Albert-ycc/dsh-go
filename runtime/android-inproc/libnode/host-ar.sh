#!/usr/bin/env bash
# macOS 宿主的 ar 包装：gyp 为 OS=android 生成 thin archive（crsT），Apple ld 读不了 thin archive，
# 换成普通归档 crs；归档工具用 NDK 的 llvm-ar（支持 @file 列表）。
set -e
: "${LLVM_AR:?LLVM_AR not set}"
args=()
for a in "$@"; do
  [ "$a" = crsT ] && a=crs
  args+=("$a")
done
exec "$LLVM_AR" "${args[@]}"
