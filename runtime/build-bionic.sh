#!/usr/bin/env bash
# 在 macOS 上构建 bionic 引擎包（卓易通：App 把 bionic 版 Node 当子进程 exec，跑打过补丁的 DSH npm 树）。
#
#   source tooling/env-mac.sh
#   runtime/build-bionic.sh
#
# 输出（app/android/app/build.gradle.kts 的 arm64 flavor 读取）：
#   runtime/out-bionic/arm64-v8a/assets/runtime/bionic/engine-arm64-v8a.tar.xz
#   runtime/out-bionic/arm64-v8a/assets/runtime/bionic/manifest.json
#   runtime/out-bionic/arm64-v8a/jniLibs/arm64-v8a/libdsh_node.so      (runtime/android-inproc/out/arm64-v8a/node 原样改名)
#   runtime/out-bionic/arm64-v8a/jniLibs/arm64-v8a/libdsh_sysprobe.so
#   runtime/out-bionic/arm64-v8a/build-report.json
# 输出目录与 glibc 的 runtime/out 分开：build-runtime.sh 每次先 rm -rf 自己的输出目录。
#
# 环境变量：
#   BIONIC_WORK   工作目录（npm 树、下载缓存、中间产物），默认 runtime/.work/bionic
#   BIONIC_OUT    输出根目录，默认 runtime/out-bionic
#   NODE_BIN      T1 产出的 bionic node，默认 runtime/android-inproc/out/arm64-v8a/node
#   BIONIC_EXTRA_PLUGIN  可选，一个本地 dsh 插件目录的绝对路径（package.json + cordis.patch.yml + lib/ ...）。
#                 整包进归档 opt/dsh-go/plugins/<包名>/，其 cordis.patch.yml 经改写入口后作为额外 --patch 登记；不给则产物与未加此变量时逐字节相同
#
# 同输入两次构建，归档 sha256 相同（成员排序、uid/gid 0、固定 mtime、固定 xz 预设与线程数）。
# bash 3.2 兼容（macOS 自带）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="${BIONIC_WORK:-$HERE/.work/bionic}"
OUT="${BIONIC_OUT:-$HERE/out-bionic}/arm64-v8a"
CACHE="$WORK/cache"
LOCKS="$HERE/locks"
INPROC="$HERE/android-inproc"
NODE_BIN="${NODE_BIN:-$INPROC/out/arm64-v8a/node}"
RB=(python3 -I "$HERE/lib/rtbuild.py")
RBI=(python3 -I "$HERE/lib/rtbionic.py")

REVISION="${BIONIC_REVISION:-r1}"
DSH_VERSION="${DSH_VERSION:-0.2.1-rc.4}"
NODE_VERSION="${NODE_VERSION:-24.22.1}"
PNPM_VERSION="${PNPM_VERSION:-11.28.5}"
# 与 build-runtime.sh 同一个钉死值
PNPM_INTEGRITY="${PNPM_INTEGRITY:-sha512-SEVj0wtMKtJ8tIqpT/KzAtvOvI/2vyiBbCWJiG4UbOf3bzVPO8GkOOb16XaMMCqgpvet2CMpA81M+BQmy8FNng==}"
NPM_REGISTRY="https://registry.npmmirror.com"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1767225600}"
XZ_PRESET="${XZ_PRESET:-6}"
XZ_THREADS="${XZ_THREADS:-4}"

log() { printf '\033[1;34m[bionic %s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { printf '\033[1;31m[bionic] error:\033[0m %s\n' "$*" >&2; exit 1; }
sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

for tool in node npm curl xz python3 shasum; do
  command -v "$tool" >/dev/null || die "缺少宿主工具: $tool"
done
[[ -n "${ANDROID_NDK_HOME:-}" ]] || die "先 source tooling/env-mac.sh（需要 ANDROID_NDK_HOME）"
[[ -f "$NODE_BIN" ]] || die "没有 bionic node: $NODE_BIN（T1，见 runtime/android-inproc/libnode）"
if [[ -f "$(dirname "$NODE_BIN")/node.sha256" && "$NODE_BIN" == "$INPROC/out/arm64-v8a/node" ]]; then
  want="$(cut -d' ' -f1 "$(dirname "$NODE_BIN")/node.sha256")"
  [[ "$(sha256 "$NODE_BIN")" == "$want" ]] || die "node 的 sha256 与 node.sha256 不符"
fi
NODE_SHA="$(sha256 "$NODE_BIN")"

mkdir -p "$WORK" "$CACHE"
: > "$WORK/empty-user-npmrc"; : > "$WORK/empty-global-npmrc"

# 与 build-runtime.sh 的 npm_prefix_flags 一致，只把 --os=linux --libc=glibc 换成 --os=android
npm_flags() {  # $1 = prefix
  printf '%s\n' --prefix "$1" --cpu=arm64 --os=android \
    --registry="$NPM_REGISTRY" --cache "$CACHE/npm" \
    --userconfig "$WORK/empty-user-npmrc" --globalconfig "$WORK/empty-global-npmrc" \
    --ignore-scripts --no-audit --no-fund --update-notifier=false --loglevel=warn
}

npm_ci() {  # $1 = lockdir, $2 = prefix
  local flags=() line
  while IFS= read -r line; do flags+=("$line"); done < <(npm_flags "$2")
  rm -rf "$2"; mkdir -p "$2"
  cp "$1/package.json" "$1/package-lock.json" "$2/"
  npm ci "${flags[@]}"
}

# ---- DSH 树 ----
LOCK_DSH="$LOCKS/dsh-$DSH_VERSION"
[[ -f "$LOCK_DSH/package-lock.json" ]] || die "缺锁文件 $LOCK_DSH"
DSH_PREFIX="$WORK/dsh-prefix"
log "npm ci @deepseek-ai/dsh@$DSH_VERSION (--os=android --cpu=arm64, 锁文件钉死)"
npm_ci "$LOCK_DSH" "$DSH_PREFIX"
got="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$DSH_PREFIX/node_modules/@deepseek-ai/dsh/package.json")"
[[ "$got" == "$DSH_VERSION" ]] || die "装到的 dsh $got != $DSH_VERSION"

# ---- pnpm 树（锁与完整性同 build-runtime.sh） ----
LOCK_PNPM="$LOCKS/pnpm-$PNPM_VERSION"
[[ -f "$LOCK_PNPM/package-lock.json" ]] || die "缺锁文件 $LOCK_PNPM"
PNPM_PREFIX="$WORK/pnpm-prefix"
log "npm ci pnpm@$PNPM_VERSION"
npm_ci "$LOCK_PNPM" "$PNPM_PREFIX"
python3 -I - "$PNPM_PREFIX" "$PNPM_VERSION" "$PNPM_INTEGRITY" <<'PYEOF' || die "pnpm 锁或安装与钉死值不符"
import json, os, sys
prefix, version, integrity = sys.argv[1:4]
lock = json.load(open(os.path.join(prefix, "package-lock.json")))
pkgs = lock["packages"]
entry = pkgs.get("node_modules/pnpm", {})
others = sorted(k for k in pkgs if k not in ("", "node_modules/pnpm"))
got = json.load(open(os.path.join(prefix, "node_modules/pnpm/package.json")))["version"]
ok = entry.get("version") == version and entry.get("integrity") == integrity and got == version and not others
print("pnpm lock: version=%s installed=%s extra=%s -> %s" % (entry.get("version"), got, others, "ok" if ok else "MISMATCH"), file=sys.stderr)
sys.exit(0 if ok else 1)
PYEOF

# ---- 补丁：先主目录再 bionic（pins 链接在主目录终点上，顺序不能反） ----
log "patch-dsh: runtime/patches (主目录)"
"${RB[@]}" patch-dsh --prefix "$DSH_PREFIX" --dsh-version "$DSH_VERSION" --patches "$HERE/patches" >&2
log "patch-dsh: runtime/patches/bionic"
"${RB[@]}" patch-dsh --prefix "$DSH_PREFIX" --dsh-version "$DSH_VERSION" --patches "$HERE/patches/bionic" >&2

# ---- 补丁测试（P2-1），失败即构建失败 ----
log "node --test runtime/tests/bionic-patches.test.mjs"
(cd "$ROOT" && DSH_PREFIX="$DSH_PREFIX" node --test runtime/tests/bionic-patches.test.mjs) >&2 \
  || die "bionic-patches.test.mjs 未通过"

# ---- 原生件：flock、sysprobe ----
log "build-flock.sh"
"$INPROC/addons/build-flock.sh" "$WORK/addons" >&2
log "build-sysprobe.sh"
"$INPROC/sysprobe/build-sysprobe.sh" "$WORK/sysprobe" >&2

# ---- ripgrep：官方 linux-arm64 静态 rg（@vscode/ripgrep-linux-arm64），地址与 sha512 取自 DSH 锁文件 ----
rg_json="$("${RBI[@]}" rg-info --lock "$LOCK_DSH/package-lock.json")"
rg_url="$(python3 -I -c 'import json,sys; print(json.loads(sys.argv[1])["resolved"])' "$rg_json")"
rg_integrity="$(python3 -I -c 'import json,sys; print(json.loads(sys.argv[1])["integrity"])' "$rg_json")"
rg_tgz="$CACHE/rg/$(basename "$rg_url")"
if [[ ! -f "$rg_tgz" ]]; then
  log "下载 $rg_url"
  mkdir -p "$CACHE/rg"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -sS -o "$rg_tgz.part" "$rg_url"
  mv "$rg_tgz.part" "$rg_tgz"
fi

# ---- 组装 ----
BRIDGE="$HERE/bridge/dsh-go-bridge"
bridge_version="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$BRIDGE/package.json")"
EXTRA_ARGS=()
if [[ -n "${BIONIC_EXTRA_PLUGIN:-}" ]]; then
  [[ "$BIONIC_EXTRA_PLUGIN" == /* ]] || die "BIONIC_EXTRA_PLUGIN 要绝对路径: $BIONIC_EXTRA_PLUGIN"
  for f in package.json cordis.patch.yml; do
    [[ -f "$BIONIC_EXTRA_PLUGIN/$f" ]] || die "额外插件缺 $f: $BIONIC_EXTRA_PLUGIN"
  done
  EXTRA_ARGS=(--extra-plugin "$BIONIC_EXTRA_PLUGIN")
  log "额外插件: $BIONIC_EXTRA_PLUGIN"
fi
rm -rf "$OUT"
log "组装归档与 manifest"
"${RBI[@]}" assemble \
  --dsh-prefix "$DSH_PREFIX" --pnpm-prefix "$PNPM_PREFIX" --bridge "$BRIDGE" --bionic-preload "$HERE/bionic" \
  --flock-node "$WORK/addons/system.node" --rg-tgz "$rg_tgz" --rg-integrity "$rg_integrity" \
  --node "$NODE_BIN" --sysprobe "$WORK/sysprobe/libdsh_sysprobe.so" --out-dir "$OUT" \
  --patches-main "$HERE/patches" --patches-bionic "$HERE/patches/bionic" \
  --dsh-version "$DSH_VERSION" --node-version "$NODE_VERSION" --pnpm-version "$PNPM_VERSION" \
  --bridge-version "$bridge_version" --dsh-lock-sha256 "$(sha256 "$LOCK_DSH/package-lock.json")" \
  --pnpm-integrity "$PNPM_INTEGRITY" --revision "$REVISION" \
  --xz xz --xz-version "$(xz --version | head -1 | awk '{print $NF}')" \
  --epoch "$SOURCE_DATE_EPOCH" --xz-preset "$XZ_PRESET" --threads "$XZ_THREADS" \
  ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} >&2

# ---- 校验：jniLibs 是 AArch64 ELF，且 node 与输入逐字节相同 ----
for f in "$OUT"/jniLibs/arm64-v8a/*.so; do
  file -b "$f" | grep -q 'ARM aarch64' || die "ABI 不符: $f: $(file -b "$f")"
done
[[ "$(sha256 "$OUT/jniLibs/arm64-v8a/libdsh_node.so")" == "$NODE_SHA" ]] || die "libdsh_node.so 与 node 输入不一致"

log "完成: $OUT"
echo "$OUT/assets/runtime/bionic/engine-arm64-v8a.tar.xz"
