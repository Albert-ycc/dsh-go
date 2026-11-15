# T1 bionic Node 构建

任务口径在中途调整：P0-B 实测卓易通对 App 主进程与 exec 出的 bionic 可执行文件一视同仁，主产物由 `libnode.so` 改为 bionic 的 `node` 可执行文件，`libnode.so` 降为可选。目录名 `runtime/android-inproc` 保留，含义现为「bionic 子进程运行时」。

## 结论

- 在这台 Mac 上直接编成功，不需要 Linux。产物 `runtime/android-inproc/out/arm64-v8a/node`，strip 后 66,279,784 字节（63.2 MiB），P0-A 估的 75–85 MB 偏高。〔实测〕
- sha256 `3b76a38328f26ce08ae231544eef3e4050a0720bf3ec10cffeca135cefe5d1ae`。〔实测〕
- 没有在真机上跑过。所有「能启动」「syscall 不被杀」的结论都停在静态审计，P2 要冒烟。
- 耗时：最后一次全量 `make -j10 node` 1665 秒；从下载到出产物墙钟约 45 分钟，其中 7 轮因 macOS 宿主问题失败后重来。〔实测〕

## 判定标准核对（`out/arm64-v8a/audit.txt`，脚本 `libnode/audit-libnode.sh`）

- `file`：ELF 64-bit LSB pie executable, ARM aarch64, interpreter /system/bin/linker64。〔实测〕
- `llvm-readelf -h`：Type DYN（PIE），Machine AArch64，OS/ABI UNIX - System V（与 NDK 同类产物一致）。`-A` 无输出（aarch64 无 ARM 属性节）。〔实测〕
- `-d`：NEEDED 只有 libc.so、libdl.so、libm.so、liblog.so；libc++ 静态。无 SONAME（可执行文件不需要）。〔实测〕
- `-l`：有 PT_INTERP=/system/bin/linker64；GNU_STACK 为 RW、不可执行；LOAD 段对齐 0x4000（16 KiB，兼容 16 KiB 页设备）。〔实测〕
- 无任何 GLIBC 符号版本。〔实测〕
- 动态导出表 38,171 个符号：`napi_module_register`、`napi_create_function`、`node::Start`、`node::InitializeOncePerProcess`、`node::LoadEnvironment`、`node::SpinEventLoop`、`node::Stop`、`node::SetProcessExitHandler`、`node::MultiIsolatePlatform::Create`，以及 P0-A 2.5 的六个私有符号（`v8::V8::GetVersion`、`Isolate::GetCurrent`、`Isolate::GetCurrentContext`、`Context::GetNumberOfEmbedderDataFields`、`Context::SlowGetAlignedPointerFromEmbedderData`、`PrincipalRealm::builtin_module_require`）全部可见。可执行文件带 `-rdynamic`，所以 node-addon-require-builtin 和其他 `.node` 能从 `RTLD_DEFAULT` 解析到。〔实测：符号存在；`.node` 能否真解析未测〕
- libc++ 符号命名空间是 `std::__ndk1`，`.node` 若用 C++ 要用同一套 libc++（NDK 默认如此）。

## 实际执行步骤

1. `lipo -archs $NDK/.../darwin-x86_64/bin/clang` → `x86_64 arm64`；`clang --version` 为 NDK 的 clang 19.0.1。node 宿主版本 24.13.1（够用）。〔实测〕
2. 下载 `node-v24.22.1.tar.xz`（npmmirror），sha256 `4f537979…b435231`，与任务书一致。〔实测〕
3. 打补丁（脚本里每个先 `--dry-run`；另在干净解压树上从头再验一遍，7 个全 OK）。
4. `configure.py --dest-os=android --dest-cpu=arm64 --cross-compiling --openssl-no-asm --with-intl=small-icu --with-icu-locales=en,zh --without-npm --without-corepack --without-inspector`（`FORM=exe` 不加 `--shared`）。环境：`CC/CXX` 为 NDK 的 `aarch64-linux-android26-clang(++)`，`CC_host/CXX_host` 为 `/usr/bin/clang(++)`，`GYP_DEFINES` 抄 `android_configure.py:57-74`。
5. `make -C out BUILDTYPE=Release -j10 node`。
6. `llvm-strip --strip-unneeded` → 产物；审计脚本出 `audit.txt`。

## 补丁（`libnode/patches/`）

P0-A 预定的三个：
- 0001 debug_utils：去掉 `__ANDROID__` 下 stderr 转 logcat 的分支，C++ 层 stderr 写 fd 2。〔读码+编译通过〕
- 0002 `v8_android_log_stdout%` 默认 1。〔同上〕
- 0003 `NODE_IMPLEMENTS_POSIX_CREDENTIALS` 对 Android 打开。〔同上〕

P0-A 没预见、在 macOS 宿主上必须新写的四个：
- 0004 `tools/gyp/pylib/gyp/input.py`：`GYP_HOST_OS` 设置后，host toolset 的 conditions 按该值求 `OS`。不设则行为不变。症状：libuv host 副本编 `unix/linux.c`，缺 `<netpacket/packet.h>`。
- 0005 `tools/icu/icu-generic.gyp`：去掉 `OS != "mac"` 排除，ICU 工具得到 `-frtti`（`xmlparser.cpp` 的 `dynamic_cast`）。
- 0006 `tools/v8_gypfiles/v8.gyp`：android 目标的 v8_libbase host 副本仅在 linux 宿主用 `platform-linux.cc`；同文件已有 `host_os=="mac"` 的 darwin 分支，上游预期过 mac 宿主。
- 0007 `deps/zlib/zlib.gyp`：Chromium 版 zlib 在 `ARMV8_OS_ANDROID` 下调 `android_getCpuFeatures()`，无人提供实现，`openssl-cli` 链接报 undefined。构建脚本把 NDK `sources/android/cpufeatures/cpu-features.c` 拷成 `deps/zlib/ndk_cpu_features.c` 并编进 `zlib_arm_crc32`。直接写 NDK 绝对路径不行（gyp 拼出的 `.o` 目标路径 make 找不到规则）。这一条在 Linux 宿主上同样需要。

未打：trap-handler（P0-A 已排除）、`avoid-ficlone-ioctl`、两处 `-latomic`、`getaddrinfo.c`、`common.gypi` cpufeatures include（NDK 里 `sources/android/cpufeatures` 存在，保留即可）。

## 宿主工具链问题（均在脚本里处理，非补丁）

按出现顺序，完整报错见 `.cache/build*.log`：
1. 直接调 Xcode 内的 clang：`fatal error: 'stdio.h' file not found`。改用 `/usr/bin/clang` 垫片并设 `SDKROOT`。
2. host 副本编 linux.c：见 0004。
3. `ar: @…/libuv.a.ar-file-list: No such file`：gyp 对 OS=android 生成 `ar crsT @list`，Apple `ar` 不认。`host-ar.sh` 调 NDK `llvm-ar`。
4. `ld: unknown options: --start-group --end-group`：`host-link.sh` 丢掉这两个参数。
5. `libuv.a` 只有 8 字节：thin archive，Apple ld 读不了。`host-ar.sh` 把 `crsT` 换成 `crs`。
6. ICU host 工具 `use of dynamic_cast requires -frtti`：0005。
7. v8_libbase host 缺 `<sys/prctl.h>`：0006。
8. torque 等 host 工具链接缺 `_CFRelease` 等：`host-link.sh` 补 `-framework CoreFoundation`。
9. `openssl-cli` 链接 `android_getCpuFeatures` 未定义：0007。
10. 第一版产物 NEEDED 含 `libc++_shared.so`：NDK clang++ 默认动态链 libc++；`LDFLAGS=-static-libstdc++` 后重链接（12 秒）解决。

## P0-A 假设的证实与推翻

- 「可能必须 Linux」：推翻。arm64 原生 NDK clang 加上述五处宿主适配，macOS 可编。官方 `android-configure` 的 darwin 分支路径本身可用，只是不带 `--shared`，这里直接手写 configure。
- 「NDK clang ≥ 16」：证实，clang 19.0.1 编过 ada、zlib crc32、V8，无需 pgo.h 补丁。
- 「small-icu + en,zh」：configure 与 ICU 裁剪（locales en,root,zh）通过；中文 `Intl` 运行时行为未测。
- 「cross-compiling 强制关 snapshot」：证实，`config.gypi` 里 `node_use_node_snapshot=false`。启动比官方二进制慢，未测量。
- 「体积 75–85 MB」：实测 63.2 MiB（可执行文件，不含 libnode 形态）。
- 「静态 libc++ 需要确认」：NDK clang++ 默认不静态，需显式 `-static-libstdc++`。
- 「`common.gypi` 依赖 NDK 的 cpufeatures」：该 include 保留，且 zlib 还需要它的实现源文件（0007），P0-A 未预见。

## 避开的系统调用（P0-B「给 P1 的结论」）

- `io_uring`：`deps/uv/src/unix/linux.c:455-457`，`__ANDROID_API__` 已定义时 `uv__use_io_uring()` 直接返回 0，不会调 `io_uring_setup`；其余 `io_uring_*` 的 `syscall()` 只在该函数放行后可达。`UV_USE_IO_URING` 在这个分支里根本不读，设 0 无害。〔读码〕
- `accept`：libuv 在 `__linux__`（Android 同）下 `#define uv__accept4 accept4`（`core.c:96-97`），走 accept4。产物导入表里有 `accept` 和 `accept4`；`accept` 的唯一引用方是 `obj.target/openssl/.../bio_sock2.o`（OpenSSL `BIO_accept`），Node 不调用。〔实测：导入表与 .o 逐个核；调用关系读码〕
- `openat2`：`deps/uv`、`src` 全文无引用；产物导入表也无 `openat2`。bionic 内部是否用，取决于设备上的 libc.so（动态链接，用的是设备自带的 bionic，不是我们编的）。〔读码+实测导入表〕
- `faccessat2`、`fchmodat2`：libuv 的 `uv_fs_access` 调 `access()`（`fs.c:1697`），导入表有 `access`、`chmod`、`fchmod`、`fchmodat`，无 `faccessat`。这两个 syscall 若被触发，来自设备上 bionic 的 `access()` / `fchmodat()` 实现，不在 libuv 或 Node 这一层，本机读不到设备 bionic 源码，〔推断〕。P2 用 `node -e` 在真机跑 `fs.accessSync`、`fs.chmodSync(path, mode)`、`fs.copyFileSync` 冒烟。
- 导入的其他相关函数：`fork`、`pthread_create`、`socket`、`epoll_create1`、`epoll_pwait`、`eventfd`、`syscall`（libuv 的 `copy_file_range`、`statx`、`getrandom`、`gettid`、`close` 走它，这些在 P0-B「可以放心用」里）。无 `set_robust_list`/`rseq` 的直接引用（它们由 libc 启动路径调用，bionic 版本不调，P0-B 已证）。

## 待拍板

1. `libnode.so` 要不要做：exec 形态已够用，进程内加载没有用户。若要，`FORM=shared` 需整体再编（预计同量级耗时，配置变了，复用对象的程度未验证）。
2. 16 KiB 页对齐已满足；要不要把 `node` 以 `lib*.so` 名放进 `nativeLibraryDir` 以绕开 filesDir 的执行限制，取决于 P0-B 对 filesDir exec 的实测，本任务不涉及。
3. 构建要不要迁到 GitHub Actions 做可复现构建：本机构建依赖 7 个补丁与 3 个包装脚本，Linux 上预计只需 0001-0003、0007。

## 主线决定（2026年11月02日）

1. `libnode.so` 不做，exec 形态即为本项目的引擎运行时。
2. 本期不迁 GitHub Actions；补丁与包装脚本已在 `runtime/android-inproc/libnode/`，可在本机重跑复现。
