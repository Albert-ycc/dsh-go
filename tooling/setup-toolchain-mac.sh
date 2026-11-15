#!/usr/bin/env bash
# macOS（Apple Silicon）版构建链安装：Temurin JDK 17、Flutter stable、Android SDK
# （cmdline-tools、platform、build-tools、NDK、cmake）。与 setup-toolchain.sh 同一套版本，
# 只是把 Linux x64 的下载地址换成 macOS arm64，并且不装模拟器与系统镜像。
# 幂等：已存在的组件跳过；下载缓存在 $DEV_ROOT/.downloads。
set -euo pipefail

DEV_ROOT="${DEV_ROOT:-$HOME/dev}"
FLUTTER_VERSION="${FLUTTER_VERSION:-3.47.7}"
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-13114758}"

JDK_DIR="$DEV_ROOT/jdk-17"
FLUTTER_DIR="$DEV_ROOT/flutter"
SDK_DIR="$DEV_ROOT/android-sdk"
DL="$DEV_ROOT/.downloads"
mkdir -p "$DEV_ROOT" "$DL"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] || { log "只支持 macOS arm64"; exit 1; }

install_jdk() {
  if [ -x "$JDK_DIR/Contents/Home/bin/java" ]; then log "JDK 已存在"; return; fi
  local base="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/mac/"
  local name
  name=$(curl -fsSL "$base" | grep -oE 'OpenJDK17U-jdk_aarch64_mac_hotspot_[0-9._]+\.tar\.gz' | sort -uV | tail -1)
  [ -n "$name" ] || { log "找不到 JDK 压缩包"; return 1; }
  log "JDK: $name"
  [ -f "$DL/$name" ] || curl -fL --retry 3 -o "$DL/$name" "$base$name"
  rm -rf "$JDK_DIR"; mkdir -p "$JDK_DIR"
  tar -xzf "$DL/$name" -C "$JDK_DIR" --strip-components=1
  "$JDK_DIR/Contents/Home/bin/java" -version
}

install_flutter() {
  if [ -x "$FLUTTER_DIR/bin/flutter" ]; then log "Flutter 已存在"; return; fi
  local name="flutter_macos_arm64_${FLUTTER_VERSION}-stable.zip"
  local base="https://storage.flutter-io.cn/flutter_infra_release/releases"
  local sha
  sha=$(curl -fsSL "$base/releases_macos.json" | python3 -c '
import json,sys
v=sys.argv[1]
for r in json.load(sys.stdin)["releases"]:
    if r.get("version")==v and r.get("channel")=="stable" and r.get("dart_sdk_arch")=="arm64":
        print(r["sha256"]); break' "$FLUTTER_VERSION")
  [ -n "$sha" ] || { log "releases_macos.json 里找不到 $FLUTTER_VERSION arm64"; return 1; }
  log "Flutter: $name"
  [ -f "$DL/$name" ] || curl -fL --retry 3 -o "$DL/$name" "$base/stable/macos/$name"
  echo "$sha  $DL/$name" | shasum -a 256 -c -
  rm -rf "$FLUTTER_DIR"
  (cd "$DEV_ROOT" && unzip -q "$DL/$name")
  [ -x "$FLUTTER_DIR/bin/flutter" ]
}

install_cmdline_tools() {
  if [ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then log "cmdline-tools 已存在"; return; fi
  local name="commandlinetools-mac-${CMDLINE_TOOLS_BUILD}_latest.zip"
  log "cmdline-tools: $name"
  [ -f "$DL/$name" ] || curl -fL --retry 3 -o "$DL/$name" "https://dl.google.com/android/repository/$name"
  rm -rf "$DL/cmdline-tools" && mkdir -p "$SDK_DIR/cmdline-tools"
  (cd "$DL" && unzip -q "$name")
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$DL/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
}

install_jdk & p1=$!
install_flutter & p2=$!
install_cmdline_tools & p3=$!
wait $p1; wait $p2; wait $p3

export JAVA_HOME="$JDK_DIR/Contents/Home"
export ANDROID_HOME="$SDK_DIR" ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$JAVA_HOME/bin:$FLUTTER_DIR/bin:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"
export PUB_HOSTED_URL="https://pub.flutter-io.cn" FLUTTER_STORAGE_BASE_URL="https://storage.flutter-io.cn"

# Flutter 的 Gradle 插件默认的 compileSdk / NDK 版本（FlutterExtension.kt）。
# 管道里不用 head，pipefail 下 head 提前关管道会让 grep 以非零退出。
GRADLE_EXT="$FLUTTER_DIR/packages/flutter_tools/gradle/src/main/kotlin/FlutterExtension.kt"
NDK_VERSION=$(grep -oE 'ndkVersion[^"]*"[0-9.]+"' "$GRADLE_EXT" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | tail -1)
COMPILE_SDK=$(grep -oE 'compileSdkVersion[^0-9]*[0-9]+' "$GRADLE_EXT" | grep -oE '[0-9]+$' | tail -1)
[ -n "$NDK_VERSION" ] && [ -n "$COMPILE_SDK" ] || { log "没从 $GRADLE_EXT 读到 ndk/compileSdk 版本"; exit 1; }
log "Flutter 默认: compileSdk=$COMPILE_SDK ndk=$NDK_VERSION"

yes | sdkmanager --sdk_root="$SDK_DIR" --licenses >/dev/null || true
sdkmanager --sdk_root="$SDK_DIR" \
  "platform-tools" "platforms;android-${COMPILE_SDK:-36}" "build-tools;${COMPILE_SDK:-36}.0.0" \
  "ndk;${NDK_VERSION}" "cmake;3.22.1"

flutter config --no-analytics --android-sdk "$SDK_DIR" --jdk-dir "$JAVA_HOME" >/dev/null
flutter precache --android
yes | flutter doctor --android-licenses >/dev/null || true
flutter doctor -v || true

cat > "$(dirname "$0")/env-mac.sh" <<EOF
# source 这个文件把 dsh-go 的 macOS 构建链放进 PATH。
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_NDK_HOME="$SDK_DIR/ndk/$NDK_VERSION"
export PUB_HOSTED_URL="https://pub.flutter-io.cn"
export FLUTTER_STORAGE_BASE_URL="https://storage.flutter-io.cn"
export PATH="$JAVA_HOME/bin:$FLUTTER_DIR/bin:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:\$PATH"
EOF
log "完成；source tooling/env-mac.sh"
