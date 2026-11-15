# bionic Node 构建（android-inproc 目录名保留，含义已是「bionic 子进程运行时」）

在 macOS（Apple Silicon）上把 Node.js 24.22.1 交叉编成 Android arm64（bionic，API 26）。主产物是 `node` 可执行文件，App 当子进程 exec；`FORM=shared` 可出 `libnode.so`（未构建，见下）。

## 怎么跑

```sh
source tooling/env-mac.sh                      # JDK 17、NDK 28.2.13676358
runtime/android-inproc/libnode/build-libnode.sh all
```

分步：`fetch | patch | configure | build | package`，每步可重跑。变量：`ANDROID_NDK_HOME`、`API`（默认 26）、`NODE_VER`/`NODE_SHA`、`WORK`（默认 `.cache`）、`OUT`（默认 `out/arm64-v8a`）、`JOBS`、`FORM`（exe / shared）。
源码包从 npmmirror 下载并校验 SHA256 `4f537979…b435231` 后解压到 `.cache/tree`。

## 实测

- 宿主：Mac mini M4，10 核 16 GiB，NDK 的 darwin 目录是 x86_64+arm64 通用二进制，arm64 原生运行，无需 Rosetta。
- 全量编译（含宿主工具 mksnapshot、torque、ICU 工具）`make -j10 node`：1665 秒（约 28 分钟）；最后一次只重链接 12 秒。
- 产物：`out/arm64-v8a/node`，strip 后 66,279,784 字节（63.2 MiB），sha256 见同目录 `node.sha256`，验收输出 `audit.txt`（23 项 PASS，0 FAIL）。
- 构建树 `.cache` 约 1.6 GB。

## 环境变量（运行 node 时）

- `UV_USE_IO_URING=0`：Node 24.22.1 的 libuv 在 `__ANDROID_API__` 下 `uv__use_io_uring()` 直接返回 0（`deps/uv/src/unix/linux.c:455-457`），默认就不会调 `io_uring_setup`；仍建议在 App 的固定环境里设 0，防止以后升级 libuv 后行为变化。
- `TMPDIR` 指向 App 私有目录；`NODE_OPTIONS` 不要设 `--jitless`（会丢 WebAssembly）。

## 宿主侧适配（macOS 交叉编 OS=android 的坑）

gyp 把 `OS=android` 也套给 host toolset，在 macOS 上要逐个纠正：`GYP_HOST_OS=mac`（补丁 0004）、`/usr/bin/clang` 作宿主编译器并设 `SDKROOT`、`host-ar.sh`（thin archive 换普通归档）、`host-link.sh`（去掉 `--start-group`，补 `-framework CoreFoundation`）。目标侧 `LDFLAGS=-static-libstdc++`，否则 NEEDED 里会有 `libc++_shared.so`。

## libnode.so

未构建（任务调整后降为可选）。`FORM=shared build-libnode.sh configure && … build` 会走 `--shared` 并构建 `libnode` 目标；configure 通过过，但从未走到链接，无产出、无验收。
