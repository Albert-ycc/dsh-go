#!/usr/bin/env bash
# 把 Node.js 交叉编成 Android arm64（bionic）的 libnode.so。配方来源 docs/design/zhuoyitong/P0A-运行时审计.md 第 3 节。
# 用法：build-libnode.sh [fetch|patch|configure|build|package|all]   默认 all，每步可单独重跑。
# 环境变量（均有默认）：
#   ANDROID_NDK_HOME  NDK 根（默认 ~/dev/android-sdk/ndk/28.2.13676358）
#   API               目标 API（26 = App minSdk）
#   NODE_VER / NODE_SHA / NODE_MIRROR
#   WORK              源码与构建目录（默认 <android-inproc>/.cache）
#   OUT               产物目录（默认 <android-inproc>/out/arm64-v8a）
#   JOBS              make 并行度（默认核数）
#   PYTHON            configure 用的 Python 3
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="$(cd "$HERE/.." && pwd)"
NODE_VER="${NODE_VER:-24.22.1}"
NODE_SHA="${NODE_SHA:-4f5379796280cdaeb0c2f358fd04b6a16cc4fb7dfa83b5a8c91a9d963b435231}"
NODE_MIRROR="${NODE_MIRROR:-https://npmmirror.com/mirrors/node}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/dev/android-sdk/ndk/28.2.13676358}"
API="${API:-26}"
WORK="${WORK:-$BASE/.cache}"
OUT="${OUT:-$BASE/out/arm64-v8a}"
JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}"
PYTHON="${PYTHON:-python3}"
# 产物形态：exe = bionic 的 node 可执行文件（主产物，App 当子进程 exec）；shared = libnode.so（可选）
FORM="${FORM:-exe}"

TARBALL="$WORK/src/node-v$NODE_VER.tar.xz"
SRC="$WORK/tree/node-v$NODE_VER"

case "$(uname -s)" in
  Darwin) NDK_HOST=darwin-x86_64 ;;   # NDK 的 darwin 目录是 x86_64+arm64 通用二进制
  Linux)  NDK_HOST=linux-x86_64 ;;
  *) echo "unsupported host" >&2; exit 1 ;;
esac
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST"

fetch() {
  mkdir -p "$WORK/src" "$WORK/tree"
  if [ ! -f "$TARBALL" ]; then
    curl -fL -o "$TARBALL" "$NODE_MIRROR/v$NODE_VER/node-v$NODE_VER.tar.xz"
  fi
  echo "$NODE_SHA  $TARBALL" | shasum -a 256 -c -
  if [ ! -d "$SRC" ]; then
    tar xf "$TARBALL" -C "$WORK/tree"
  fi
}

patch_tree() {
  cd "$SRC"
  # 0007 要用：NDK 的 cpufeatures 实现（Apache-2.0）拷进 zlib 目录
  cp "$ANDROID_NDK_HOME/sources/android/cpufeatures/cpu-features.c" "$SRC/deps/zlib/ndk_cpu_features.c"
  for p in "$HERE"/patches/*.patch; do
    if patch -p1 -R --dry-run -s -f < "$p" >/dev/null 2>&1; then
      echo "already applied: $(basename "$p")"
    else
      patch -p1 --dry-run -s -f < "$p"
      patch -p1 -s -f < "$p"
      echo "applied: $(basename "$p")"
    fi
  done
}

setup_env() {
  # 目标：NDK clang；宿主（mksnapshot、torque 等 V8 生成工具）：Xcode / 系统 clang。二者必须分开传。
  export CC="$TC/bin/aarch64-linux-android${API}-clang"
  export CXX="$TC/bin/aarch64-linux-android${API}-clang++"
  export AR="$TC/bin/llvm-ar"
  # /usr/bin/clang 是 xcrun 的垫片，会自动带 SDK 路径；直接调 Xcode 里的 clang 找不到 stdio.h
  export CC_host="${CC_host:-$(command -v clang)}"
  export CXX_host="${CXX_host:-$(command -v clang++)}"
  if [ "$(uname -s)" = Darwin ]; then
    export SDKROOT="${SDKROOT:-$(xcrun --show-sdk-path)}"
    export GYP_HOST_OS=mac   # 见 patches/0004，让 host toolset 按 mac 求值
    export LINK_host="${LINK_host:-$HERE/host-link.sh}"   # 去掉 Apple ld 不认的 --start-group
  fi
  # gyp 对 OS=android 生成 `ar crsT @list`（thin archive）：macOS 自带 ar 不认 @list，Apple ld 读不了 thin。
  # 宿主侧用包装脚本：llvm-ar + 去掉 T
  export LLVM_AR="$TC/bin/llvm-ar"
  export AR_host="${AR_host:-$HERE/host-ar.sh}"
  export PATH="$PATH:$TC/bin"
  # NDK clang++ 默认链 libc++_shared.so；App 里没人带它，静态链进去
  export LDFLAGS="${LDFLAGS:--static-libstdc++}"
  # 复制 android_configure.py:57-74 的环境设置
  export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=$(uname -s | tr A-Z a-z) OS=android android_ndk_path=$ANDROID_NDK_HOME"
}

configure_tree() {
  setup_env
  cd "$SRC"
  local shared_flag=""
  [ "$FORM" = shared ] && shared_flag="--shared"
  "$PYTHON" ./configure.py \
    $shared_flag --dest-os=android --dest-cpu=arm64 --cross-compiling \
    --openssl-no-asm \
    --with-intl=small-icu --with-icu-locales=en,zh \
    --without-npm --without-corepack --without-inspector
}

build_tree() {
  setup_env
  cd "$SRC"
  local target=node
  [ "$FORM" = shared ] && target=libnode
  make -C out BUILDTYPE=Release V=0 -j"$JOBS" "$target"
}

package() {
  cd "$SRC"
  mkdir -p "$OUT"
  local name built
  if [ "$FORM" = shared ]; then name=libnode.so; built="$(find out/Release -name 'libnode*.so' -type f | head -1)"
  else name=node; built=out/Release/node; fi
  [ -f "$built" ] || { echo "$name not found ($built)" >&2; exit 1; }
  cp "$built" "$OUT/$name.unstripped"
  cp "$built" "$OUT/$name"
  "$TC/bin/llvm-strip" --strip-unneeded "$OUT/$name"
  (cd "$OUT" && shasum -a 256 "$name" > "$name.sha256")
  bash "$HERE/audit-libnode.sh" "$OUT/$name" "$FORM" > "$OUT/audit-$name.txt" 2>&1 || true
  ls -l "$OUT"
}

step="${1:-all}"
case "$step" in
  fetch) fetch ;;
  patch) fetch; patch_tree ;;
  configure) fetch; patch_tree; configure_tree ;;
  build) build_tree ;;
  package) package ;;
  all) fetch; patch_tree; configure_tree; build_tree; package ;;
  *) echo "usage: $0 [fetch|patch|configure|build|package|all]" >&2; exit 2 ;;
esac
