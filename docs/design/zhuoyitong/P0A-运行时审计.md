# P0-A 运行时审计与 bionic libnode 配方

基线：dsh-go `main` 8fe1c1c；引擎 DSH 0.2.1-rc.4；Node 24.22.1。作者分支 `claude/harmonyos-ohos` 取 530f183（2026年11月02日 fetch）。日期：2026年11月02日。

证据标记：〔读码〕读了源文件或文档；〔实测〕本次在本机执行并看到输出；〔推断〕由读码结论推出，没有执行；NOT_RUN 没跑过。引用格式 `文件:行号`，作者分支文件写作 `ohos:路径`。本机 npm 树（`~/.local/share/dsh-cli/node_modules`）是 DSH 0.2.1-rc.2 的 darwin 版，rc.4 的树只有锁文件（`runtime/locks/dsh-0.2.1-rc.4/package-lock.json`，629 条目），引擎代码行为的读码以 rc.2 为准，rc.4 差异标「rc.2 读码」。

## 1. 引擎依赖清单

### 1.1 Node 版本与钉死原因

- 版本 24.22.1，`runtime/build-runtime.sh:41`。官方 linux-x64 / linux-arm64 tarball 的 sha256 钉在 `runtime/build-runtime.sh:73-74`，同时与镜像的 SHASUMS256.txt 交叉核对（`:130-140`）〔读码〕。
- 仓库内没有记录钉到 .22.1 的具体理由。该钉自 1.0.0 源码提交 a5fa673 起就在（`git log -S` 〔实测〕）。
- 能确认的硬约束：引擎启动时 `dsh-app-boot` 向 Node 的默认 ESM / CJS 解析器装拦截（`installRuntimeInterception` → `internalModules()`，rc.2 `@deepseek-ai/dsh-app-boot/lib/index.js:1779-1860`），读取 `internal/modules/esm/loader`、`internal/modules/cjs/loader` 等五个内部模块，并按 loader 形状（`getOrCreateModuleJob` 是否存在）分支；注释写明 24.12.0 起换了形状。`node-addon-require-builtin` 的原生探针按 Node 24.22.1 的 Realm 槽位（38 号）与 `builtin_module_require` getter 的机器码取内部 require（`ohos:runtime/ohos/native/loader/README.md`）。所以小版本升级需要重核这两处〔读码〕。
- DSH 包本身没有声明 `engines`（锁文件 `@deepseek-ai/dsh` 条目无 engines 字段）〔读码〕。

### 1.2 npm 树怎么产出

`runtime/build-runtime.sh`（x86_64 Linux 宿主，不执行外架构代码）〔读码〕：

1. `install_dsh`（`:244-271`）：有 `runtime/locks/dsh-0.2.1-rc.4/package-lock.json` 就 `npm ci`，否则首次 `npm install --save-exact @deepseek-ai/dsh@0.2.1-rc.4` 后把重复的精确依赖提升为直接依赖再装一次并落锁。flag 见 `:234-241`：`--cpu=<arm64|x64> --os=linux --libc=glibc --ignore-scripts --registry=npmmirror`。`--ignore-scripts` 意味着所有 install 脚本都不跑，原生件只取 npm 里现成的 prebuild / 平台包。
2. `patch-dsh`（`:390`，实现 `runtime/lib/rtbuild.py:328`）：按文件名排序逐个应用 `runtime/patches/*.diff`，`pins.json` 按 DSH 版本记录每个目标文件的 before / after sha256，不在 pins 里的版本直接失败。
3. `install_pnpm`（`:274`）：pnpm 11.28.5，integrity 钉死，装到 `/usr/local/lib/node_modules/pnpm`，供插件安装用。
4. `rtbuild.py assemble`：按 ABI 裁剪（foreign-arch ELF、musl ELF、`prebuilds/` 里非本架构目录、`.exe/.dll/.dylib`、`node-pty/third_party/conpty`、`.map` / `.d.ts`，`rtbuild.py:727-745`、`:1149-1180`），生成 `opt/dsh-go/direct/native-addons.json`（`.node` 清单，`:684`、`:1190`），再把 Ubuntu Base 24.04.5 + tzdata/ca-certificates/git + python-build-standalone 3.12.15 + Node + DSH 树 + pnpm + 桥 + overlay 打成一个 tar.xz rootfs。
5. 进程内路线用得到的只有第 1-3 步的 npm 树与补丁结果；第 4 步里的 Ubuntu / python / git / proot / talloc / android-shmem 整块不再需要。

### 1.3 补丁（`runtime/patches/`，15 个，全部 pin 到 rc.4）

| 补丁 | 作用 | 进程内是否保留 |
|---|---|---|
| fs-create-exclusive | Android 共享存储拒绝硬链接时保持 no-clobber 创建 | 保留（与 bionic / FUSE 相关） |
| file-upload-resolver-dispose | 上传 resolver 注册只释放一次 | 保留 |
| route-guard | 非桥 webserver 的路由守卫 | 保留 |
| install-log-redaction、plugin-manager-active-run-guard、plugin-manager-cancel-tree-confirmation | 插件安装日志脱敏、运行中守卫、取消确认 | 保留；若插件安装整块降级则不触发 |
| zz-direct-runtime | Direct 路径接缝：fs-local / working-directory / home-paths / app-boot / sandbox / skill-filesystem / api-session-controller 的 `toHost` 映射，以及 `subprocess-local` 里 node-pty 调用接缝 | 钩子全部通过 `globalThis[Symbol.for("dsh.go.direct")]` 守卫，没有 preload 就不生效；是否还要这套「guest 路径→物理路径」映射见 4.5 |
| zzz-config-editor-path、zzz-title-cache-refresh、zzzz-plugin-config-readability、zzzzz-plugin-config-bundles | rc4 配置编辑、标题缓存刷新、插件配置可读性与 HMR 锁 | 保留 |
| zzzzzz-session-deletion、zzzzzzz-input-recovery、zzzzzzzz-model-defaults、zzzzzzzz-schedule-authorization | 会话删除、输入恢复、模型默认值、定时任务授权 | 保留 |

补丁都是对 `node_modules/@deepseek-ai/*/lib/index.js` 的文本补丁，与 Node ABI 无关，换 libnode 不影响〔读码〕。

### 1.4 桥 `runtime/bridge/dsh-go-bridge`（0.4.0）

纯 JS 的 DSH 插件，由 `/etc/dsh-app/dsh-web.patch.yml` 按绝对路径 `/opt/dsh-go/dsh-go-bridge/lib/index.js` 以 `insert` 加载（`rtbuild.py:883-890`），在引擎的 webserver 上挂 `/dsh-go/v1/*`（inventory、occupancy、plugin-config、sessions 删除、native 协议 contributions / views / invoke / stream）。App 与引擎之间的业务面全走 HTTP（`127.0.0.1:{port}`），没有别的 IPC〔读码，`runtime/bridge/dsh-go-bridge/lib/index.js:1-10`〕。换进程内宿主后桥原样可用，只需把路径换成 App 私有目录。

同一份 overlay 还给 plugin-manager 配 `fallbackRegistries`（npmmirror → npmjs）。

### 1.5 启动契约

三种形态对照〔读码〕：

| 项 | PRoot（默认） | Direct（可选，glibc loader） | 进程内（目标） |
|---|---|---|---|
| 可执行 | `libproot.so ... /usr/bin/env -i ... /bin/sh -c "echo $$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web ..."`（模板 `rtbuild.py:1311-1356`） | `ld-linux-aarch64.so.1 --library-path ... node --import=preload.mjs .../dsh/lib/bin.js web ...`（`DirectLaunch.kt:320`） | `dsh_node_start(argc, argv)`，在 App 进程一条线程里跑 |
| `dsh` 垫片 | `/usr/local/bin/dsh`：`node --import=/opt/dsh-go/boot-cache.mjs .../bin.js "$@"`（`rtbuild.py:866-871`） | 同上，由 Kotlin 生成 `bin/node` 脚本 | 不需要垫片；boot-cache 是为 PRoot 路径查找慢写的，进程内不需要 |
| web 参数 | `web --patch /etc/dsh-app/dsh-web.patch.yml [--patch phone.patch.yml] [--patch session-search] [--patch app-auth] [--patch speech] --host 127.0.0.1 --port {PORT} --no-open`（`DshProcess.kt:116-139`；各 overlay 由 `PhoneGuest` / `SessionSearchGuest` / `AppAuthGuest` / `SpeechGuest` 写） | 同左，`--patch` 路径加 rootfs 前缀（`DirectLaunch.kt:241`） | 同左，路径换成 App 私有目录 |
| 端口 | `pickPort()`：3080..3100 逐个试，都占用则临时端口（`DshProcess.kt:426-435`）；绑 `127.0.0.1` | 同 | 同；作者用 `--port 0` 并从就绪行取端口（`ohos:app/ohos/entry/src/main/cpp/host_core.cpp:288-291`），本机现有逻辑需要事先知道端口，保持 `pickPort` 即可 |
| 工作目录 | `--cwd=/root`（guest 的 `/root`） | `rootfs/root`（`DirectLaunch.kt:321`） | `filesDir/runtime/rootfs/root`（沿用）或新目录 |
| 固定环境 | `HOME=/root USER LOGNAME SHELL=/bin/bash PATH LANG=C.UTF-8 TERM TMPDIR=/tmp DSH_HOME=/root/.dsh pnpm_config_registry=https://registry.npmmirror.com/ UV_USE_IO_URING=0 NARB_DISABLE_NATIVE_CACHE=1 NODE_OPTIONS=--dns-result-order=ipv4first`（`rtbuild.py:1313-1323`） | 同左，路径经 `hostPath()` 换成物理路径，另加 `DSH_GO_RUNTIME_ROOT`、`DSH_GO_DIRECT_ROOTFS`、`DSH_GO_DIRECT_MAIN=1`、`TZDIR`、`SHELL=$bin/bash`（`DirectLaunch.kt:299-319`） | `HOME`、`DSH_HOME`、`DSH_AGENTS_HOME`、`TMPDIR` 显式设；作者的做法见 1.5 末 |
| 应用叠加环境 | `TZ`、用户传入的 `DEEPSEEK_API_KEY` 等、`NODE_OPTIONS` 追加 `--max-old-space-size=N`、`UV_THREADPOOL_SIZE`（`RuntimeController.kt:325-328`，`PerformanceRules.kt:120-136`）；PRoot 再追加 `--import=/opt/dsh-go/direct/proot-dns.mjs`（`DirectLaunch.kt:72-74`） | 同左 | `NODE_OPTIONS` 的内容改为 libnode 的 exec args（作者 `kDisableNodeOptionsEnv`，非空 NODE_OPTIONS 一律拒绝，`ohos:runtime/ohos/host/node_runner.cpp:87-90`）；`UV_THREADPOOL_SIZE` 仍走环境变量 |
| 宿主 → 引擎 token | stdout 一行 `dsh web: http://127.0.0.1:PORT/?token=<43 字符>`，正则 `TOKEN_URL`（`DshProcess.kt:416`）；读到后每 250 ms 探 TCP，90 s 上限（`:323-335`） | 同 | 同；stdout 改为 fd 1/2 重定向到管道（作者 `host_core.cpp:122-134`） |
| 停止 | 读 `/tmp/dsh-web.pid` → SIGTERM（dsh 自行 dispose 插件退出）；5 s 后对 proot SIGQUIT；兜底 destroyForcibly（`DshProcess.kt:247-271`） | 同，无 proot | `dsh_node_stop()`（`node::Stop`，不是信号） |
| 重启 | 杀进程再起 | 同 | 同一进程内 Node 只能启动一次（作者 `embedded-exit.md`：`dsh_node_start` 第二次调用返回 64）。重启引擎等于重启承载进程 |

作者的进程内环境做法（`ohos:docs/design/harmonyos/native-host.md` 启动前提 1-7）：宿主在 `start` 前用 `setenv` 写 `HOME / DSH_HOME / DSH_AGENTS_HOME / TMPDIR / NARB_DISABLE_NATIVE_CACHE`，引擎退出后恢复；调用方不得覆盖 `NODE_*`、`UV_*`、`DSH_GO_*`、`NARB_*`。

### 1.6 引擎运行中会 spawn 的子进程

读 rc.2 的 `@deepseek-ai/*/lib` 得到下表。「进程内替代」指不 exec 外部可执行文件、在引擎同一 Node 里完成；「必须子进程」指该能力就是跑一个外部程序；「可降级」指可以关闭或换成较弱的实现。子进程在卓易通里能不能跑、能跑多少，取决于 P0-B，下表按「假设子进程受限」给默认处置，P0-B 出结果后改。

| 触发 | 谁在 spawn | 实际执行的东西 | 默认处置 |
|---|---|---|---|
| bash 工具（核心） | `dsh-bash-local` → `dsh-subprocess-local`：`argv=["bash","-c",cmd]`（rc.2 `dsh-bash-local/lib/index.js:140-145`），经 `resolveExecutable` 在 PATH 里找 | rootfs 里的 Ubuntu bash 与其下所有命令（git、python3、node、npm、pnpm、rg…） | 必须子进程；bionic 没有 bash。可降级为作者的虚拟 shell（just-bash 3.6.0 的私有构建，命令白名单，无系统进程；`ohos:docs/design/harmonyos/virtual-shell-command-budget.md`、`ohos:runtime/ohos/process/`）。这是本改造里最大的功能回退点，见 5-1 |
| bash 工具的进程管理 | `dsh-subprocess-local` 的 `spawnSubprocess`：`child_process.spawn(program, args, {detached:true})`（rc.2 `runner-launch-B2zsQ1Dz.js:1020-1050`） | 同上 | 平台判定 `process.platform`。Node 的 Android 构建报 `android`（`node.gyp:573` `NODE_PLATFORM="<(OS)"`，`OS=android`），不是 `linux`，于是 `selectContainmentMode` 跳过 Linux scope 分支落到 `fallback`（`dsh-subprocess-local/lib/index.js:1583-1600`），直接 spawn，不经过「以 `process.execPath` 起 runner + koffi execve」的链路。〔读码〕 |
| 终端（sidebar 终端、持久 bash） | `dsh-subprocess-local.spawnTerminal` → `node-pty`（`:1656`） | `shellPath` 默认 `/bin/bash`（`dsh-terminal-bash/lib/index.js:9`） | 属可选 bundle `dsh-experimental-terminal-bundle`，默认不开（`dsh-app-boot/lib/index.js:579-592` OPTIONAL_BUNDLES）。可降级：不启用 |
| 手机操控 MCP | `dsh-mcp-client` stdio：`command: !!js process.execPath`，args `relay.cjs bridge.json`（`PhoneGuest.kt:55-70`，`:62-63`） | 以 `process.execPath` 起一个 Node 子进程当 stdio⇄bridge 套接字的中继 | 进程内 `process.execPath` 不是 Node 可执行文件，这条链路会直接断。必须改：`dsh-mcp-client` 支持 `streamable-http` 传输（`headers` 可带 token，rc.2 `dsh-mcp-client/lib/index.js:781-797`），改成连 App 里的回环 HTTP 端点；Kotlin 侧 `McpServer.kt`（newline JSON-RPC，`PhoneBridge.kt` 的抽象套接字 / TCP）要补 HTTP 外壳 |
| 插件安装 / 卸载 / 查询 | `dsh-plugin-manager` 的 `execa("pnpm", ...)`（rc.2 `:409`、`:610`、`:743`、`:794`、`:833`）和 `execa("git", ...)`（`:1177`） | pnpm（`#!/usr/bin/env node`，需要 PATH 上有 `node`）、git | 必须子进程且依赖 `node` 可执行文件；进程内没有 `node`。可降级：作者的做法是明确拒绝并提示「连接电脑管理插件」（`ohos:docs/design/harmonyos/plugin-install-refusal.md`）。若要保留，需要一个 `node` 启动器可执行文件，其可用性取决于 P0-B |
| 文件变更面板 | `dsh-workspace-changes` 调 git（rc.2 grep 命中） | git | 可降级：作者用 isomorphic-git 纯 JS 实现 `ohos:runtime/ohos/process/git*.mjs` |
| worktree 工具 | `dsh-experimental-worktree`（可选 bundle，默认关）调 git | git | 可降级：不启用 |
| 搜索（grep / glob 工具） | `dsh-tool-fs-search` 用 `@vscode/ripgrep` 的 `rg`（rc.2 `:109-130`；Direct 注释称 pinned 为 static PIE，`DirectLaunch.kt:288-290`） | `@vscode/ripgrep-linux-arm64/bin/rg` | 必须子进程；可降级为作者的虚拟 rg（re2js，`ohos:runtime/ohos/process/rg.mjs`、`regex-src/`） |
| Office 转 PDF skill | `dsh-skill-office` 把 `process.execPath` + `libreoffice-kit/lib/cli.js` 写进给模型的提示，由模型经 bash 工具调用（rc.2 `dsh-skill-office/lib/index.js:43-52`） | node 子进程 + LibreOffice Kit | 可降级：关闭该 skill（作者 `composition.json` 整块排除 libreoffice-kit） |
| 可编程工具调用（ptc） | `dsh-ptc-runtime-node` 以 `process.execPath` 起 worker（rc.2 `:640-646`），`dsh-base` 默认装（`dsh-base/cordis.patch.yml:397-401`） | node 子进程 | 进程内可替代：改成 `worker_threads`，需要补丁；或可降级关闭 ptc / workflow-ptc。工作量待 P1 评估 |
| 本地语音 | `dsh-experimental-speech-to-text-sensevoice` 以 `process.execPath` 起 worker（rc.2 `:775`） | node 子进程 + sherpa-onnx | 可选 bundle，可降级（见 2.4） |
| 沙箱 | `dsh-sandbox-local` 的探针 `spawnSync("bwrap")`、landlock-run（rc.2 `:100-160`），`PLATFORM_CHAINS = {linux:[bwrap,landlock], darwin:[seatbelt], win32:...}`（`:173-176`） | bwrap / landlock-run | `android` 不在链里 → `unavailable` → 受限模式下 `confine()` 抛 `SandboxUnavailableError`（`:492-499`、`:511`）。现状（`linux` + PRoot 里 bwrap/landlock 不可用）的行为我没跑过，进程内是否改变权限预设的实际表现 NOT_RUN，见 4.5 |
| 浏览器打开 / 目录选择 / `open-in-app` / `native-command` | `spawn(process.execPath, ...)` 或 `xdg-open`、`gio` | — | 手机场景用不到；`--no-open` 已关；`android` 平台落到「不支持」分支。无需处理 |

`process.execPath` 是整张表的关键变量：libnode 嵌入后 `process.execPath` 由 `uv_exepath()` 读 `/proc/self/exe`，得到承载进程（`app_process64`），不是 Node〔推断〕。凡是 `spawn(process.execPath, …)`、`#!/usr/bin/env node` 的链路都不能直接用。要不要提供一个 `node` 启动器可执行文件（NDK 编的小 ELF，`dlopen` libnode 并调 `dsh_node_start`），以及它能不能在容器子进程里跑（要线程），由 P0-B 决定。

## 2. 原生扩展逐个结论

先写共同事实：

- 锁里这六类在 `linux-arm64` 的现成件都是 glibc 构建，bionic 不能直接用。`rtbuild.py:1166-1174` 会把外架构与 musl 的 ELF 丢掉，Android bionic 构建根本不在其识别范围（`:196` 注释）。
- libnode 报 `process.platform === 'android'`，而引擎和这些扩展的加载器大量按 `linux` / `darwin` / `win32` 分支（`@deepseek-ai/*/lib` 中 `platform === "linux"` 的命中十余处）。凡是原生包选择、flock 平台检查都要处理。
- 在 bionic 上加载自编 `.node` 时，`napi_*` 符号由 `libnode.so` 提供；`.node` 要 `-Wl,-z,defs` 链接 `libnode.so`，运行时靠 `NEEDED libnode.so` 解析（作者 `ohos:runtime/ohos/node/BUILD.md` 的 addon 须知，Android 没有 Ark 同名符号，不需要他们的 `napi-rename.h`）〔推断〕。

### 2.1 node-pty 1.2.0-beta.15

- 谁用：`dsh-subprocess-local.spawnTerminal`（`requireNodePty()`，rc.2 `:1442`、`:1656`），只服务终端 bundle（可选，默认关）。
- 现成件：`prebuilds/` 目录只有 darwin / linux（glibc）/ win32，没有 Android（本机树 `ls prebuilds` 〔实测〕：darwin-arm64、darwin-x64、linux-arm64、linux-x64、win32-arm64、win32-x64；`linux-arm64/pty.node` 经 `file` 〔实测〕为 ELF aarch64 动态链接，glibc 构建）。
- 能否 NDK 编：包里带 `binding.gyp` 与 `src/unix/pty.cc`、`spawn-helper.cc`〔读码〕；`binding.gyp` 对所有非 mac 非 solaris 平台无条件加 `-lutil`（本机树 `binding.gyp`〔读码〕），bionic 没有独立 libutil，需要去掉；`forkpty` / `openpty` 在 bionic 有（API 23 起）〔推断〕。NOT_RUN。
- 降级：不启用终端 bundle。bash 工具走普通 spawn，不依赖 pty。
- 作者鸿蒙分支：整块排除（`ohos:docs/design/harmonyos/runtime-composition.md` 组成规则表「koffi、Windows FFI、node-pty、外部 ripgrep」行），终端能力不提供。

### 2.2 koffi 3.1.1

- 谁用：Linux 的 runner 启动链（`runner-launch` 的 `loadLinuxExecve()` 经 koffi 调 libc `execve` / `fcntl`，rc.2 `runner-launch-B2zsQ1Dz.js:1055-1090`），以及 Windows 专用路径（`dsh-fs-local`、`dsh-win32-process`、`dsh-session-persistence-jsonl` 里全是 `kernel32.dll` / `advapi32.dll` / `user32.dll`，`libreoffice-kit` 的 Windows 管道）。
- 在 Android 上：`process.platform==='android'` 时 `selectContainmentMode` 不走 Linux scope，`loadLinuxExecve` 不会被调；其余 koffi 使用点全是 win32。所以不需要 koffi。〔读码〕
- Android 构建：锁里 koffi 平台包有 linux-arm64 / linux-x64 / win32 / darwin / freebsd / openbsd，没有 android。不编。
- 降级：不需要。排除该包可减体积。
- 作者鸿蒙分支：整块排除。

### 2.3 sharp 0.35.5

- 谁用：`dsh-attachment-local`（图片附件的元数据读取、缩放、重编码，`requireSharp()`，rc.2 `:118-265`）。核心路径：用户发图片给模型。
- 现成件：`dist/sharp.cjs` 的加载顺序是 `../src/build/Release/sharp-<platform>-<ver>.node` → `sharp-wasm32` 本地构建 → 按 `runtimePlatform` 选 `@img/sharp-<platform>`（列表里没有 android）→ 最后 `require("@img/sharp-wasm32/sharp.node")`（本机树 `sharp/dist/sharp.cjs:27-105` 〔读码〕）。`runtimePlatform` 在 libnode 上是 `android-arm64`，前几项都落空，会走 `@img/sharp-wasm32`（emnapi + wasm，本机树里已装，8.9 MB〔实测 `du`〕，依赖 `@emnapi/runtime`）。
- 结论：不需要 NDK 编 libvips，wasm32 回落可能直接可用。前提是 libnode 的 V8 带 WebAssembly：作者的 OHOS 构建固定 `--jitless`，其下没有 `WebAssembly`（`ohos:runtime/ohos/node/BUILD.md:189` 把「jitless 无 wasm」列为待验项，`virtual-shell-command-budget.md` 的 jitless 样本记录 WebAssembly 不存在），Android 构建不加 `--jitless` 即有。NOT_RUN：wasm32 版在 bionic libnode 上的实际可用性、大图的内存与耗时。
- 兜底：用 Android 的 `BitmapFactory` / `ImageDecoder` 在 Kotlin 侧重编码，经桥回填；需要改 `dsh-attachment-local`，工作量较大，只在 wasm32 不可用时才做。
- 作者鸿蒙分支：排除 sharp 与 `@img/*`，图片重编码返回 `OHOS_IMAGE_CODEC_UNAVAILABLE`（运行时组成表）。

### 2.4 sherpa-onnx 1.13.8（`sherpa-onnx-node` + 平台包）

- 谁用：本地语音识别 `dsh-experimental-speech-to-text-sensevoice` 的 worker（`createRequire(...)("sherpa-onnx-node")`，rc.2 `worker.js:75`），可选 bundle `voice-input`，默认关。平台白名单是 `linux-arm64` 等字面量（`index.js:204-207`），`android-arm64` 不在内，直接抛「Local speech is unavailable」。
- Android 构建：锁里 sherpa-onnx 平台包只有 darwin / linux / win，没有 android。sherpa-onnx 上游有 Android 的 JNI 构建，但这是 Java 绑定，不是 N-API；N-API 的 Android 版需要自己从源码编（含 onnxruntime），工作量大。
- 降级：关闭；语音输入已有 Android 侧识别入口（`SpeechGuest.kt`、`voiceModelLine` 方法通道），由 Kotlin 承担。
- 作者鸿蒙分支：排除，本地 SenseVoice 给出「系统输入法/电脑」提示。

### 2.5 node-addon-require-builtin 0.1.9

- 谁用：引擎启动路径上的硬依赖。`dsh-app-boot` 的 `internalModules()` 直接 `createRequire(...)("node-addon-require-builtin")`，没有 `--expose-internals` 回落（rc.2 `dsh-app-boot/lib/index.js:1779-1783`）；`cordis-plugin-loader`、`dsh-hmr` 有「`process.execArgv` 含 `--expose-internals` 就直接 require，否则走 addon」的两段式（`cordis-plugin-loader/lib/index.js:10-17`、`dsh-hmr/lib/index.js:172-180`）。不加载它，`dsh web` 起不来。
- 现成件：平台包只有 darwin / linux gnu+musl / win32，没有 android。加载器的平台后缀对非 darwin/linux/win32 平台直接用 `${process.platform}-${process.arch}`，即 `node-addon-require-builtin-android-arm64`，二进制路径 `prebuilt/android-arm64-napi-v9.node`（本机树 `node-addon-native-custom-loader/lib/index.js:288-298`、`:310-315`〔读码〕）。所以在 Android 上不改引擎代码，补一个符合这个名字与 `prebuilds.json` 的包就行。
- 能否 NDK 编：源码公开、MIT，仓库 `deepseek-ai/dsh-node-addon-require-builtin`，提交 a58d3599（0.1.9）；`packages/native/src/runtime_probe/linux_arm64.cc`、`runtime_context/linux_arm64.cc` 已有 arm64 Linux 分支（作者 `ohos:runtime/ohos/native/loader/source-pins.json` 的文件清单与 `README.md`）。它用 `dlsym(RTLD_DEFAULT)` 取 libnode 的六个符号（`v8::V8::GetVersion`、`Isolate::GetCurrent`、`Isolate::GetCurrentContext`、`Context::GetNumberOfEmbedderDataFields`、`Context::SlowGetAlignedPointerFromEmbedderData`、`node::PrincipalRealm::builtin_module_require`），再解码 getter 的机器码取 Realm 字段偏移。
  - 对 Android 的含义：libnode 必须保持这六个符号可见（不要用作者那种只导出 `dsh_*` 的 version script）；`System.loadLibrary` 加载的库默认不在全局符号作用域，`RTLD_DEFAULT` 能否看到 libnode 的符号要验证，作者在鸿蒙同样标 NOT_RUN。NDK clang 与官方 Node 用的 GCC 生成的 getter 指令形状是否一致没有验证。
- 低成本替代：`libnode` 本来就由我们启动，可以在 exec args 里固定加 `--expose-internals`，再给 `dsh-app-boot` 的 `internalModules()` 补一个小补丁，仿 `dsh-hmr` 的 `requireInternal`（先 `process.execArgv` 含 `--expose-internals` 就 `require(id)`）。这样不依赖机器码探针。代价是动一处 pinned 的 `dsh-app-boot/lib/index.js`；作者明确选了「编上游 addon、不改 profile resolution」（`ohos:docs/design/harmonyos/startup.md` 开头「不引入旧 O7c 的 `--expose-internals`」）。两条都 NOT_RUN，建议先试上游 addon，不通再用 `--expose-internals`。
- 加载位置：`.node` 若放 `filesDir`，要 `dlopen` App 数据目录里的库；放进 `jniLibs`（`lib*.so`，已有 `useLegacyPackaging=true`、`extractNativeLibs=true`，`app/android/app/build.gradle.kts:76`、`AndroidManifest.xml:55`）更稳。`prebuilt/android-arm64-napi-v9.node` 可做成指向 `nativeLibraryDir/libdsh_require_builtin.so` 的符号链接〔推断〕。`NARB_DISABLE_NATIVE_CACHE=1` 已在固定环境里，避免加载器把 `.node` 复制到临时缓存再加载。
- 作者鸿蒙分支：用上游源码编出 `libdsh_require_builtin.so`（clang 23 + 静态 libc++ + `dsh_napi_*` 改名），放进 HAP libs，用补丁 `ohos-hap-native-libraries.diff` + 自有包 `@dsh-go/ohos-native` 以 `process.dlopen` 按固定产品名加载（`ohos:docs/design/harmonyos/hap-native-libraries.md`）。

### 2.6 @deepseek-ai/node-addon-system 0.1.2

两部分：

- `flock`（N-API，`src/flock.c`）：`dsh-session-persistence-jsonl` 每个会话目录的写锁，`tryLockExclusive(fd)`（rc.2 `dsh-session-persistence-jsonl/lib/index.js:696`，锁文件 `session.lock`）。会话持久化的硬依赖：不能 flock，会话创建 / 恢复失败。`flock.js` 先检查 `platform !== 'linux' && platform !== 'darwin'` 就抛 `ERR_FLOCK_UNSUPPORTED_PLATFORM`（本机树 `@deepseek-ai/node-addon-system/lib/flock.js:10-15`〔读码〕）。所以 Android 上不打补丁一定失败，与原生件有没有无关。
  - 能否 NDK 编：`src/flock.c` 只用 `<sys/file.h>` 与 N-API，约 5.7 KB，随包发布；bionic 有 `flock(2)`。作者已用同一份源码编过 OHOS 版（`ohos:runtime/ohos/native/build-flock.py`）。
  - 需要：一个对 `platform === 'android'` 放行并从固定库名加载的补丁（照作者的 `ohos-hap-native-libraries.diff`，或把 `flock.js` 的平台判断与路径拼接改成读环境变量里的库路径）。
- `landlock-run`（Linux Landlock 启动器 ELF，`src/main.c`）：只被 `dsh-sandbox-local` 在 `linux` 链里调用。`android` 不在链里，不使用。作者也没有移植。
- 作者鸿蒙分支：只编 flock（`libdsh_flock.so`），不移植 landlock；补丁见上。

### 2.7 汇总

| 扩展 | 引擎功能 | Android bionic 来源 | 默认处置 |
|---|---|---|---|
| node-pty | 终端（可选 bundle） | 无现成件；NDK 可编，需改 `binding.gyp` | 不启用，后续再编 |
| koffi | 仅 Linux runner / Windows | 无；不需要 | 排除 |
| sharp | 图片附件 | 无 bionic；`@img/sharp-wasm32` 回落（读码） | 先验 wasm32；不行再 Kotlin 重编码 |
| sherpa-onnx | 本地语音（可选） | 无；N-API 版需自编 | 关闭，Kotlin 侧识别 |
| node-addon-require-builtin | 启动必需 | 上游源码公开 MIT；NDK 可编，探针未验 | 必编；备选 `--expose-internals` |
| node-addon-system（flock） | 会话写锁，必需 | `flock.c` 随包；NDK 可编 | 必编 + 补平台判断 |
| node-addon-system（landlock） | 沙箱，仅 linux 链 | 不需要 | 排除 |

## 3. bionic libnode 24.22.1 构建配方草案

### 3.1 先答问题

- **官方 `android-configure` 在 macOS arm64 宿主能不能用**：脚本本身放行 Darwin（`android_configure.py:13-15`、`:57-59`），但 NDK 工具链路径硬编码为 `toolchains/llvm/prebuilt/darwin-x86_64`（`:59`），Linux 为 `linux-x86_64`（`:63`）〔读码，v24.22.1〕。本机 `arch -x86_64 /usr/bin/true` 返回 `Bad CPU type in executable`〔实测〕，即没有可用的 Rosetta。NDK 的 darwin 工具链是否带 arm64 原生二进制我没有核实：下载 NDK 后 `lipo -archs $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang` 一条命令就能定；NOT_RUN。
- 另一层：官方说明 Android 不是受支持平台，CI 里没有 Android 测试（`BUILDING.md:796-799`），也没有看到任何人在 macOS 宿主上交叉编过 Android 版（Termux 在 Linux 容器里编，注释称单架构约两小时，`packages/nodejs/build.sh:8`；作者的 OHOS 版在 Arch Linux 上编，`ohos:BUILD.md:13`）。**结论：按「必须 Linux」规划**；GitHub Actions 的 `ubuntu` x86_64 runner 满足（NDK 的 Linux 工具链只有 `linux-x86_64`，不要用 arm64 runner）。本机路线只有在上面的 `lipo` 检查通过后才值得试，通过也仍是未验证路线。
- **NDK 版本**：作者实测 OHOS SDK 自带的 clang 15 编不了 Node 24（`deps/ada/ada.h` 需要 `std::ranges::find_if` 与 lambda 捕获结构化绑定；`deps/zlib/crc32_simd.c` 的 AArch64 CRC 内建函数选不出来，`ohos:BUILD.md:17-20`）。所以需要 clang ≥ 16，实际建议 NDK r27 及以上（其 clang 18 起；各 NDK 的 clang 版本号来自记忆，未联网核实，〔推断〕）。App 现在的 `ndkVersion` 取 Flutter 默认（`app/android/app/build.gradle.kts:31`），libnode 的 NDK 与它可以不同，只要 libnode 对外只暴露 C ABI（`extern "C"`），C++ 运行库静态链入 libnode（NEEDED 里不能有 `libc++_shared.so`）。
- **目标 API**：`android_configure.py:29` 要求 ≥ 24；App `minSdk=26`（`build.gradle.kts:41`）。用 26。clang 对 API < 29 默认走模拟 TLS（`__emutls_*`），作者在 OHOS 上也是模拟 TLS 并静态链入 compiler-rt builtins（`ohos:BUILD.md:65`）；NDK 自带 `libclang_rt.builtins`，不需要另编〔推断〕。API 26 构建出的 libnode 在更高 API 上也能跑；反过来不行。
- **`--shared` 与产物名**：`configure.py:1007-1012` 的 `--shared` 帮助里写明「not officially supported for regular applications」；`dest_os == 'android'` 时共享库后缀固定 `so`（`configure.py:1849`），产出就是 `libnode.so`，不带版本号（作者要为 OHOS 单独补的就是这一处，Android 已有）。gyp 目标名 `libnode`（`node.gyp:15` `node_lib_target_name%`）。
- **预计体积**：作者在 OHOS arm64、`small-icu`（root,en）、关 inspector、静态 libc++ 的同类配置下，`libnode.so` 未精简 107,838,320 字节，`--strip-unneeded` 后 81,662,168 字节（`ohos:BUILD.md:170`）。Android 构建的体积预计同一量级，约 75–85 MB，未实测〔推断〕。
- **预计耗时**：作者 `-j4`（内存上限 6 GB）4,147 秒，单个 cc1plus 编 V8 可达 1.8 GB，`-j6` 在 14 GB 机器上耗尽内存（`ohos:BUILD.md:56`、`:160-163`）；Termux 注释称约两小时 / 架构。GitHub 标准 runner 的核数与内存按公开仓库 4 vCPU / 16 GB 估，单次预计 1.5–2.5 小时，未实测〔推断〕。本机 M4：10 核 16 GiB 内存，可用磁盘 61 GiB〔实测〕。

### 3.2 Termux 补丁（`packages/nodejs`，版本 26.13.0）逐个判定

做法：用 `gh api` 取 Termux 仓库 `packages/nodejs` 下的 `build.sh` 与全部 `*.patch`，再取 Node `v24.22.1` 对应的 18 个原文件，在临时目录里用 macOS 自带 `patch -p1 --dry-run` 逐个试〔实测〕。除标注外都「干净应用」（无 fuzz、无 offset）。Termux 的 `build.sh` 构建的是可执行文件，没有 `--shared`，并且用系统的 ICU / openssl / sqlite / zlib / cares（`build.sh:177-192`）；所以它提供的是补丁清单，不是 libnode 配方。15 个 `test-*.patch` 只改 Node 自带测试，不适用。

| 补丁 | 干了什么 | 对 24.22.1 + 嵌入式 libnode | 判定 |
|---|---|---|---|
| `src-debug_utils.cc` | `__ANDROID__` 下 C++ 层 `FPrintF(stderr, …)` 不再转 logcat，保持写 fd 2 | 24.22.1 `src/debug_utils.cc:533-538` 同样把 stderr 发往 `__android_log_print`。致命异常、OOM 的 C++ 输出会进 logcat 而不进我们的 stderr 管道，`CrashClassifier` 读不到。守卫宏改成我们自己的（如 `DSH_GO_EMBEDDED_RUNTIME`）。干净应用 | **要打** |
| `tools-v8_gypfiles-toolchain.gypi` | `v8_android_log_stdout` 默认 1（V8 自己的打印走 stdout）；去掉 arm64 的 `-mbranch-protection=standard` | 第一处同样关系到 V8 打印的去向；第二处只在 `v8_control_flow_integrity==1` 时生效，默认 0。干净应用 | 第一处建议打，第二处不打 |
| `src-node_internals.h` | 把 `NODE_IMPLEMENTS_POSIX_CREDENTIALS` 对 Android 打开 | 24.22.1 `src/node_internals.h:329` 在 Android 不定义，`process.getuid/geteuid` 等不存在。引擎有守卫写法：`dsh-spill-local` 在 `process.geteuid === void 0` 时直接放行属主检查（rc.2 `:134`、`:156`），Android 上会少一道临时目录属主校验。干净应用 | 建议打 |
| `avoid-ficlone-ioctl` | Android 上不用 `FICLONE` ioctl | `deps/uv/src/unix/fs.c:1357-1372`；仅 `fs.copyFile` 带 FICLONE 标志时触发，容器 seccomp 对 ioctl 的限制未知。干净应用 | 低优先，可打 |
| `node.gyp`（`-latomic` 对 android；加 `src/getaddrinfo.c`） | `-latomic`：clang 链接原子操作库；`getaddrinfo.c`：Termux 绕 IPv6 `localhost` 的解析问题 | `-latomic` 在 arm64 通常不需要，链接报 `__atomic_*` 未定义再加；`getaddrinfo.c` 是 Termux 专用 hack。干净应用 | 两处都先不打 |
| `tools-v8_gypfiles-v8.gyp`（`-latomic`；android 源列表加 `platform-linux.h`） | 同上；补源文件 | 同上；`platform-linux.h` 若编译报缺才加。干净应用 | 先不打，按需 |
| `common.gypi` | 删掉 `OS=="android"` 里的 `-I<(android_ndk_path)/sources/android/cpufeatures` | 24.22.1 `common.gypi:314-316` 保留该 include，`android_configure.py:73` 会设置 `android_ndk_path`。需要 NDK 里真有 `sources/android/cpufeatures`（NOT_RUN，下载后 `ls` 一下）。干净应用 | 视 NDK 而定 |
| `tools-gyp-pylib-gyp-generator-ninja.py` | ninja 生成器对 host / target 的 `obj` 目录区分 | 只有 `--ninja` 才用 | 用 make 不打；用 ninja 要打 |
| `deps-v8-src-trap-handler-trap-handler.h` | 全局关闭 trap handler | **在 24.22.1 失败**（`1 out of 1 hunks failed`）〔实测〕。上游自带的 `android-patches/trap-handler.h.patch` 也同样失败〔实测〕。头文件现状（`deps/v8/src/trap-handler/trap-handler.h:17-26`）对原生 arm64 的 Android 已排除，仿真分支才会在 Android 上误开并触发 `#error`（`:55-60`），而本构建的目标代码走原生分支 | **不打** |
| `deps-uv-src-unix-process.c` | 删 `setgroups/setgid/setuid` | 只影响 `child_process.spawn` 的 `uid/gid` 选项；引擎不用 | 不打 |
| `fix_multiple_definitions` | 去掉 `sysinfo-memory.c` 里重复的 `uv_get_free_memory` | 24.22.1 的 `uv.gyp` android 源列表不含 `sysinfo-memory.c`（`deps/uv/uv.gyp:136-142`、全文 grep 无该名） | 不需要 |
| `deps-uv-uv.gyp`、`deps-uvwasi`、`node_report.cc` | 共享 libuv 的 include 顺序、WASI readdir、`cpus` 为空时的 JSON | Termux 专用或与引擎无关 | 不打 |
| `deps-uv-src-unix-core.c`、`deps-v8-…flag-definitions.h`、`lib-os.js`、`lib-child_process.js` | 把 `/tmp`、`/data/local/tmp`、`/bin/sh` 换成 Termux 前缀 | 我们由宿主设 `TMPDIR`；`lib/child_process.js:687` 在 Android 默认 `/system/bin/sh`，正是想要的值 | 不打 |

补充三条与补丁无关的 24.22.1 事实〔读码〕：

- libuv 在 Android 已无条件关闭 io_uring（`deps/uv/src/unix/linux.c:455-457` `__ANDROID_API__` 直接 `return 0`）。作者为 OHOS 改的 `uv__use_io_uring` 在 Android 上不需要。`UV_USE_IO_URING=0` 环境变量仍留着无害。
- `./configure` 在 `--cross-compiling` 下强制 `node_use_node_snapshot=false`、`node_use_node_code_cache=false`（`configure.py:1689`、`:1697`）。引擎启动会比官方二进制多花内置模块编译的时间，没有办法绕开；模块级编译缓存（`NODE_COMPILE_CACHE`）已在 `docs/design/runtime-cold-start.md:130` 评估过，结论是不留。
- 作者的 `node24-ohos.diff`（`ohos:runtime/ohos/node/node24-ohos.diff`）五处里：共享库后缀（Android 已是 `so`）、io_uring（Android 已关）两处不需要；`deps/v8/src/wasm/pgo.h` 补 `#include <atomic>` 只在 libc++ 不再间接带入时才报错（作者的 libc++ 是 LLVM 23，NDK 的版本更旧，按需）；`src/node.cc`、`src/node_main_instance.cc` 两处改的是 `node::Start` 的专用分支，`ohos:docs/design/harmonyos/embedded-exit.md` 称 runner 已改用公开嵌入 API，不再经过它们；但 `BUILD.md:88-92` 仍列着这两处，两份文档对不上，Android 走公开嵌入 API，不依赖它们。

### 3.3 配置与命令草案（Linux x86_64，NOT_RUN）

```sh
set -euo pipefail
NODE_VER=24.22.1
# 作者 ohos:runtime/ohos/node/BUILD.md:7 记录的 sha256；构建位另对照 nodejs.org 的 SHASUMS256.txt 复核
NODE_SHA=4f5379796280cdaeb0c2f358fd04b6a16cc4fb7dfa83b5a8c91a9d963b435231
NDK=$ANDROID_NDK_HOME            # r27 及以上
API=26                           # = app minSdk
curl -fLO "https://nodejs.org/dist/v$NODE_VER/node-v$NODE_VER.tar.xz"
echo "$NODE_SHA  node-v$NODE_VER.tar.xz" | sha256sum -c -
tar xf "node-v$NODE_VER.tar.xz" && cd "node-v$NODE_VER"

# 补丁（3.2 表中「要打 / 建议打」）：debug_utils stderr 守卫、v8_android_log_stdout、NODE_IMPLEMENTS_POSIX_CREDENTIALS
patch -p1 < ../patches/node24-android-embed.diff

# 不调用 ./android-configure：它只接受 <ndk> <api> <arch> 三个参数，
# 末行固定 `./configure --dest-cpu=… --dest-os=android --openssl-no-asm --cross-compiling`（android_configure.py:21-23、76-77），无法追加 --shared。
# 下面复制它的环境设置（:57-74）。
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export PATH=$PATH:$TC/bin
export CC=$TC/bin/aarch64-linux-android$API-clang
export CXX=$TC/bin/aarch64-linux-android$API-clang++
export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=linux OS=android android_ndk_path=$NDK"

./configure \
  --dest-cpu=arm64 --dest-os=android --cross-compiling \
  --shared \
  --openssl-no-asm \
  --with-intl=small-icu --with-icu-locales=en,zh \
  --without-npm --without-corepack --without-inspector

make -C out BUILDTYPE=Release -j"$(nproc)" libnode      # 或 configure 加 --ninja 后 ninja -C out/Release libnode
$TC/bin/llvm-strip --strip-unneeded out/Release/lib/libnode.so   # 输出位置 NOT_RUN，以实际为准
```

选项依据与取舍：

- `--shared`：嵌入用。configure 对 Android 固定 `so` 后缀（`configure.py:1849`）。
- `--openssl-no-asm`：`android_configure.py:77` 自带；arm64 汇编加速的 AES / SHA 在 NDK 上是否可用没验证，之后可单独试。
- `--with-intl=small-icu`：默认是 `full-icu`（`configure.py:823-828`），带全量 ICU 数据；`small-icu` 配 `--with-icu-locales`（`configure.py:847`）可只留 `en,zh`。引擎界面有中文，保留 `zh`。NOT_RUN：`small-icu` 构建在 `--cross-compiling` 下会走 ICU 数据裁剪工具的宿主构建。
- `--without-npm --without-corepack`：引擎用自带 pnpm，不需要 Node 内置 npm。
- `--without-inspector`：作者也关，引擎不开调试端口。
- 不加 `--jitless`：作者的 OHOS 构建固定 `--jitless`（文档未写明理由）；Android App 进程本身有 ART JIT，V8 的可执行页应可用〔推断〕。容器是否允许 `mmap(PROT_EXEC)` 是 P0-B 要探的项；如果不允许，运行时加 `--jitless` 即可回退（同时失去 WebAssembly，影响 2.3 的 sharp wasm32 回落）。
- 静态 libc++：NDK clang 链接 C++ 的默认选择没有核实。构建后用 `llvm-readelf -d out/Release/lib/libnode.so | grep NEEDED` 确认没有 `libc++_shared.so`，有就在 `LDFLAGS` 加 `-static-libstdc++`。
- 不裁剪导出符号：2.5 的 require-builtin 探针需要 `v8::` 和 `node::` 的私有符号可见，不要套作者的 `libnode-exports.map`。
- 16 KiB 页对齐：16 KiB 页的 Android 设备要求 `-Wl,-z,max-page-size=16384`〔推断〕；卓易通容器的内核页大小未知，P0-B 记录。

产物验收（构建位出库前跑，NOT_RUN）：`llvm-readelf -h/-d` 确认 AArch64、ET_DYN、无 `PT_INTERP`、SONAME `libnode.so`、NEEDED 只有 bionic 库；`llvm-nm -D --defined-only` 看到 `node::InitializeOncePerProcess`、`node::CommonEnvironmentSetup::Create`、`node::LoadEnvironment`、`node::SpinEventLoop`、`node::Stop`、`node::SetProcessExitHandler` 与 2.5 的六个符号。

### 3.4 作者分支里 libnode 怎么被宿主加载并启动 `dsh web`

链路〔读码，`ohos:docs/design/harmonyos/native-host.md`、`startup.md`、`embedded-exit.md`、`ohos:runtime/ohos/host/node_runner.cpp`、`ohos:app/ohos/entry/src/main/cpp/host_core.cpp`〕：

1. `libdsh_host.so`（Ark N-API 模块，`napi_host.cpp`）`NEEDED libnode.so`。宿主只经 C 函数 `dsh_node_start(int argc, char** argv)` / `dsh_node_stop()` 进入 Node。
2. `host_core.cpp` 的 `start`：校验目录与环境 → `std::thread` 建引擎线程 → 线程里 `setenv` 若干项 → 组 13 个参数的 argv：`dsh-node --jitless --import <runtime>/runtime/ohos/preload.mjs <runtime>/node_modules/@deepseek-ai/dsh/lib/bin.js web --patch <DSH_HOME>/ohos-host.patch.json --host 127.0.0.1 --port 0 --no-open` → 调 `dsh_node_start`。
3. stdout / stderr 捕获：启动前 `pipe()` 两对，`dup2` 把 fd 1 / 2 指向管道写端，两条 reader 线程轮询读，行上限 16384 字节，超长整行丢弃；队列最多约 512 项，满了丢最旧的 log；日志里的 URL token 与 env 中 KEY/TOKEN/SECRET/PASSWORD 值遮盖。就绪判定只认固定的一行 `dsh web: http://127.0.0.1:PORT/?token=TOKEN`。退出时先恢复 fd、排空并 join reader、恢复环境，再发终态事件。
4. `node_runner.cpp` 的 `dsh_node_start`：拒绝非空 `NODE_OPTIONS`；`InitializeOncePerProcess` 带 `kNoStdioInitialization | kNoDefaultSignalHandling | kNoInitializeV8 | kNoInitializeNodeV8Platform | kDisableNodeOptionsEnv | kNoParseGlobalDebugVariables | kNoAdjustResourceLimits | kNoUseLargePages`；`MultiIsolatePlatform::Create(4)`；`CommonEnvironmentSetup::Create(... kTrackUnmanagedFds | kNoGlobalSearchPaths | kNoCreateInspector | kNoStartDebugSignalHandler)`；`SetProcessExitHandler` 记录退出码并 `node::Stop`；`LoadEnvironment(env, StartExecutionCallback{})` 走 Node 默认 CLI 入口（`--import` 链生效）；`SpinEventLoop`。一进程一次，第二次调用返回 64。
5. 事件交给 JS 层：`getState()` / `drainEvents()` 轮询，宿主不持有 `napi_env`。

能直接搬到 Android 的：

| 作者件 | 处置 |
|---|---|
| `node_runner.cpp`（公开嵌入 API 的 runner） | 直接搬。去掉 `RegisterPublicationBinding()` 与 `publication_binding.h`（OHOS 原子发布 / deviceinfo 专用）；去掉 argv 里的 `--jitless` 强制与 preload 路径后缀校验，换成 Android 自己的参数契约 |
| `host_core.cpp/.h`（线程、管道、环境保存恢复、事件队列、日志遮盖、就绪行解析）、`file_access.h`（逐层 `openat` + `O_NOFOLLOW`） | 平台无关，直接搬；只需把 hilog 换成 logcat |
| `napi_host.cpp` | 不搬，换成 JNI 层 |
| `napi-rename.h`、`napi_isolation.py`、`libnode-exports.map` | 不需要。这套是为避开 Ark 同名 `napi_*` 符号；Android 无冲突。`libnode-exports.map` 还会隐藏 2.5 需要的私有符号 |
| `preload.mjs`（网络后端 undici + publication 账本） | 不需要。它们为 OHOS 的 musl + sharefs rename 限制写 |
| `ohos-hap-native-libraries.diff` + `@dsh-go/ohos-native` | 思路搬：flock 与 require-builtin 以固定库名从受信目录 `process.dlopen`。Android 目标按 2.5 / 2.6 另写，判断改为 `platform === 'android'` |
| `runtime/ohos/process/`（just-bash 虚拟 shell、虚拟 rg、isomorphic-git） | 子进程不可用时的降级方案，可整块借（AGPL，同仓库）；是否采用取决于 P0-B，见 5-1 |

Android 上需要补的、作者没遇到的：

- **线程栈**。`std::thread` 在 bionic 的默认栈是 1 MiB〔推断〕，V8 默认栈限制约 984 KiB，几乎没有余量。Node 的 Worker 线程栈固定 4 MiB 并按 `栈 - 192 KiB` 设 V8 限制（`src/node_worker.h:119`、`:121`，`src/node_worker.cc:743-756`）〔读码〕。引擎线程用 `pthread_attr_setstacksize` 建，至少 4 MiB，建议 8 MiB。作者的 `host_core.cpp` 没有设栈（grep 无 `stack`），OHOS 上 musl 的默认值另论。
- **stderr 去向**：见 3.2 第一行，不打补丁则 C++ 层 stderr 进 logcat。
- **载体进程**：见 4.6。

### 3.5 风险与未验证项

| 项 | 状态 |
|---|---|
| NDK 的 clang 能编 Node 24.22.1 的 V8（ada / zlib crc32 / `<atomic>`） | NOT_RUN |
| NDK darwin 工具链是否原生 arm64 | NOT_RUN（`lipo -archs`） |
| Linux x86_64 宿主交叉编 arm64 的 host toolset（mksnapshot 走模拟器）能否一次过 | NOT_RUN；Termux 与作者在同类宿主都编成，但不是同一版本与同一 NDK |
| libnode 在 `System.loadLibrary` 后 `RTLD_DEFAULT` 能否看到 libnode 私有符号 | NOT_RUN |
| 容器内 V8 可执行页（JIT）、`futex`/线程 | P0-B |
| `small-icu + zh` 下中文 `Intl` 行为 | NOT_RUN |

## 4. 给 P1 架构的输入

### 4.1 Kotlin → Dart 的 method channel 契约（现状）

`MethodChannel("dsh/runtime")`，`RuntimePlugin.kt:72-179` 共 28 个方法〔读码〕；Dart 端 `app/lib/runtime/runtime_bridge.dart`（`RuntimeBridge`，`:504-721`）逐一对应，状态机在 `runtime_state.dart`（`LocalRuntimeController`）。

| 类别 | 方法 | 进程内改造后 |
|---|---|---|
| 引擎生命周期 | `getStatus`、`install`、`start`（`env`、`phoneStorage`、`mode`、`visualReplies`）、`stop`、`reset`（`keep`）、`getLogs`、`getLogSnapshot` | 保留签名。`install` 语义由「解压 rootfs」变为「校验 / 摆放引擎 npm 树」；进度事件 `progress.step` 现有 `extract` / `verify` / `configure` |
| 安全模式 / 回退 | `lastKnownGood`、`restoreLastKnownGood`、`safePreflight`、`moveAside`、`clearFailure`、`setAutoSafe`、`failureLog` | 保留。它们读写 `rootfs/root/.dsh/profiles/web` 等文件（`LastKnownGood.kt:84`），只要 home 目录结构不变就不用动 |
| 只属于 PRoot / Direct | `setDirectEngine`；`getStatus` 里的 `directEngineEnabled/Available/Active/FallbackReason`、`currentExecutionMode`、`launchSelectedMode`、`launchSpawned`、`launchFailureReason`、`tidyingFiles` | 删。Dart 侧引用点：`ui/settings/direct_engine_settings.dart`、`diagnostics_page.dart`、`chat_screen.dart`、`empty_state.dart`、`engine_status.dart`、`engine_screen.dart`、`usage/usage_stats.dart`、`diagnostics/diagnostics_markdown.dart`〔`grep` 实测〕 |
| 与引擎无关的系统能力 | `batteryExempt`、`requestBatteryExempt`、`backgroundSettings`、`openBackgroundSettings`、`openVendorSettings`、`networkMetered`、`voiceModelLine`、`storageStatus`、`requestStorage`、`openAllFilesAccess` | 原样保留 |
| 技能 / 诊断 | `publishDiagnostics`、`installManagedSkill`、`cancelManagedSkill` | 保留；写入目标是 `$DSH_HOME` 下文件 |

`getStatus` 的 `RuntimeStatus` map 字段见 `runtime_bridge.dart:143-300`。`abi` / `bundledVersion` / `installedVersion` / `dshVersion` / `nodeVersion` 仍有意义；`baseUrl` / `token` 由就绪行产生。`EventChannel("dsh/runtime/events")` 事件：`status`、`progress`、`logs`（约 100 ms 一批，`seq` 单调、先订阅后取快照的去重协议，`docs/architecture.md`）、`notice`；日志事件协议不变，宿主从管道读取后喂给 `RuntimeLogs`。

### 4.2 Kotlin 运行时类处置

| 类 | 处置 |
|---|---|
| `RuntimeInstaller`、`ParallelXZInputStream`、`TarExtractor`、`AssetSeekableInputStream` | rootfs tar.xz 解压整块不要。新的安装器只摆放 npm 树（JS、JSON、静态资源），按文件 hash 增量落地（作者 P2 的做法，`ohos:native-host.md`「运行时与原生库的落点」） |
| `GuestSetup` | `writeResolvConf` / `pickDns` / `DnsConfigLog` 整块不要（bionic 的 `getaddrinfo` 走系统解析，`refreshDns` 网络回调也不需要）；`writeAgentsMd`、`writeDiagnostics` 保留，改根路径 |
| `L2sMigration` | 整块不要（PRoot 假硬链接迁移） |
| `DirectLaunch` | 整块不要（glibc loader 规划、wrapper 脚本、PRoot 绑定解析） |
| `DshProcess` | 重写：不再 `ProcessBuilder`，改为调 JNI。保留可复用逻辑：`pickPort`、token 行正则、`canConnect` 探测、日志脱敏。进程清理 / 归属（`RuntimeProcessOwnership`、`reapStrays`、`killStrays`）按 4.6 重新定义归属判据 |
| `DirectLaunch.PROOT_DNS` 的 `--import` | 不要 |
| `RuntimeController` | 保留骨架（phase 状态机、safe mode、LKG、自动重启、日志）；`startProcess` 里 PRoot 准备（resolv.conf、`PhoneStorage.syncShortcut`、各 `*Guest.writeFiles`）按需保留写文件部分 |
| `CrashClassifier` | 重写：现有分类依赖 proot / 加载器的 stderr 字样与 `128+SIGSYS`（`CrashClassifier.kt:94-109`）；进程内改为 `:engine` 进程死亡（Binder `linkToDeath`）+ tombstone / exit reason（`ApplicationExitInfo`，API 30+，需要核对 minSdk 26 的兜底）+ 日志尾部 |
| `RuntimeManifest` | 保留读 `assets/runtime/manifest.json` 的机制，字段改：去掉 `rootfs*`、`launch.argv/env`（proot 模板），新增 libnode 版本 / 原生库清单 |
| `DshService` | 保留前台服务与 wake lock；DNS 回调、网络阻断观测（`onBlockedStatusChanged`）里 DNS 部分删 |
| `LaunchStart`、`SafeMode`、`LastKnownGood`、`ManagedSkillInstaller`、`PhoneStorage` | 保留；`PhoneStorage.syncShortcut` 在 home 里放 `手机存储 → /sdcard` 符号链接（`PhoneStorage.kt:48-51`），进程内物理路径一致，仍成立 |
| `PhoneGuest` | `PATCH_YAML` 的 stdio 中继要换传输（1.6 手机操控行）；`relay.cjs` 不再用 |

### 4.3 Dart 侧

- `RuntimeBridge`：删 `setDirectEngine`、`RuntimeStatus` 里 Direct 相关字段与 UI（4.1 表）。
- 首启本机路径：`ui/onboarding/local_install.dart`、`runtime_setup_page.dart` 展示 rootfs 解压进度与「约 750MB」之类文案（该数值来自作者追加 8，本次没测）；安装体量变了，文案与进度步骤（`setup_progress.dart`）要对应改。
- `local_footprint.dart`、`usage/usage_stats.dart`、`diagnostics_page.dart` 含 rootfs / proot 字样，需逐处看。
- l10n：改界面文案走 `app/lib/l10n/parts/*.arb` 中英同步（`AGENTS.md`）。

### 4.4 引擎侧必改项（按「不改就起不来 / 起来了某核心能力坏」排序）

| # | 项 | 原因 | 办法 |
|---|---|---|---|
| 1 | `node-addon-require-builtin` | 引擎启动硬依赖（2.5） | NDK 编 `android-arm64` 平台包；备选 `--expose-internals` + `dsh-app-boot` 小补丁 |
| 2 | `flock` 平台判断 | 会话写锁，`platform==='android'` 直接抛错（2.6） | 补丁 + NDK 编 `system.node` |
| 3 | 手机操控 MCP 传输 | `process.execPath` 起中继，进程内不成立（1.6） | 改 `streamable-http`；Kotlin 侧补 HTTP 外壳（带 `Authorization` 头） |
| 4 | bash 工具的 shell | 无 `bash`（1.6） | 见 5-1 |
| 5 | 图片附件 | sharp（2.3） | 先验 wasm32 回落 |
| 6 | 搜索工具 | `rg` 子进程 | 取决于 P0-B；备选虚拟 rg |
| 7 | 插件安装 | pnpm 需要 `node` 可执行文件 | 取决于 P0-B；备选拒绝并提示 |
| 8 | 沙箱 | `android` 不在 `PLATFORM_CHAINS`（1.6） | 在 `workspace-write` / `read-only` 预设下 bash 是否被拒要实测，NOT_RUN |
| 9 | `process.getuid` 系列缺失 | Node 的 Android 构建不定义（3.2） | 编 libnode 时带补丁；或接受 `dsh-spill-local` 少一道校验 |

### 4.5 路径语义

现状 session 与配置里记录的是 guest 绝对路径（`/root/workspace/...`，`/root/.dsh`）。Direct 路线用 `runtime/direct/paths.mjs` 的 `toHost` / `toGuest` 在引擎的类型化边界（`zz-direct-runtime.diff`）映射，`globalThis[Symbol.for("dsh.go.direct")]` 缺席则全部是空操作〔读码〕。进程内有两条路：

- 继续用这套映射，`rootfs` 指向 `filesDir/runtime/rootfs`，现有会话与配置无需迁移；需要保留 `preload.mjs` 的路径部分（`createPaths`），去掉 `processes.mjs`（把 `spawn` 重写成走 glibc loader 的那套）与 `modules.mjs`（`/proc/self/fd` 别名的 `dlopen` 校验）、`dns.mjs`。
- 物理路径世界，不要映射：老会话里的 `/root/...` cwd 失效，需要一次性迁移。

作者的做法是第一条：`guestRoot = filesDir + '/runtime/rootfs'`，下面仍是 `root`、`root/.dsh`、`root/.agents`、`root/workspace`（`ohos:native-host.md`「调用面与所有权」表），目的是 Kotlin 侧所有按 rootfs 相对路径写文件的代码不用改。

### 4.6 进程模型

- 一个进程里 Node 只能启动一次，停止后不能原地再起（`ohos:embedded-exit.md`）。本 App 现状里引擎的 stop / start 频繁出现：安全模式切换、设置变更、自动重启（`RuntimeController` 的 `autoRestarts`）、reset。所以承载进程要能被整个重启：`android:process=":engine"` 的前台服务，主进程通过 Messenger / AIDL 下发 `start(config)` / `stop`、接收 `logs / ready / exit` 事件；引擎重启 = 杀 `:engine` 进程再拉。AndroidManifest 现有各 service 都没有 `android:process`（`AndroidManifest.xml:212-262`），属新增。
- 引擎进程崩溃（native abort、OOM）不会带走 UI 进程；`CrashClassifier` 改为基于进程死亡与退出原因。
- 手机操控桥（`PhoneBridge`，抽象套接字 + TCP 回环）在主进程，`:engine` 里的 MCP 客户端连回主进程；Linux 抽象命名空间 UNIX 套接字在同 UID 的两个 App 进程间可连，不需要 TCP 兜底，但 MCP 传输改 HTTP 后只剩 TCP 回环一条〔推断〕。
- 引擎 spawn 出来的 bash / rg 等子进程与引擎同属 `:engine` 的 UID，现有的「按 UID 扫 `/proc` 回收残留」（`RuntimeProcessOwnership`）可以保留，归属判据由「环境 / cwd 含 rootfs 路径」改成宿主注入的环境标记。
- 隐含的开销：`:engine` 是独立的 zygote 派生进程，会再加载一份 ART 与 Flutter 之外的最小框架；内存占用没有测。

## 5. 待拍板

1. **bash 工具在 App 内没有 bash / git / python / node 之后怎么办。** 这是整个改造最大的功能回退。三条路：(a) 带一套 bionic 用户态（bash + coreutils + git + python 的 NDK / Termux 构建，作为子进程跑）——能不能跑看 P0-B 的子进程系统调用表；(b) 采用作者的虚拟 shell（just-bash + 虚拟 rg + isomorphic-git，`ohos:runtime/ohos/process/`），命令白名单，无 python / node / git CLI，但不依赖子进程，整块可借；(c) 两者并存，子进程可用时走 (a)，否则落 (b)。P0-B 回来前不能定。
2. **进程内路线是替换现有 PRoot / Direct，还是只给卓易通变体用。** 影响「整块不要」的范围：替换则 4.2 表里的 `RuntimeInstaller` / `L2sMigration` / `DirectLaunch` / DNS 相关代码删除；并存则两套路线共享 `RuntimeController` 状态机，`getStatus` 里 Direct 字段保留。
3. **构建链放哪**（`00-改造方案.md` 第五节 1、2）。本审计新增的事实：官方 `android_configure.py` 的 darwin 分支写死 `darwin-x86_64`，本机没有 Rosetta〔实测〕，所以本机编 libnode 取决于 NDK 的 darwin 工具链有没有 arm64 原生二进制（`lipo -archs` 一条命令可定，NOT_RUN）；没有则只能走 Linux x86_64（GitHub Actions）。libnode 单次预计 1.5–2.5 小时、产物约 80 MB，宜做成按 Node 版本 / 补丁 / NDK 版本缓存的独立 workflow，不要放进每次 APK 构建。
