# P0-B 容器边界探针

日期：2026年11月02日。目标机 HUAWEI Pura X（VDE-AL00），卓易通 `com.droi.tong` 1.0.10.93。
探针工程：`tooling/zhuoyitong-probe/`（Kotlin + NDK/CMake，targetSdk 28，applicationId `probe.zyt`）。
证据标注：实测＝在真机上跑出的结果；读码＝来自源码；推断＝由实测推出、未直接验证。

## 一、结论

对 G0 的 B 项：**成立**。

容器的系统调用过滤器是一张加在整个 App 进程树上的 seccomp 白名单，不区分「主进程」和「exec 出来的子进程」。依据：

1. 主进程 `/proc/self/status` 为 `Seccomp: 2`、`Seccomp_filters: 2`，`:engine` 进程相同，`/system/bin/sh` 子进程相同（实测，第二节）。
2. 在主进程里 fork（不 exec，继承 App 自己的过滤器）逐号重跑 0–460，与 exec 静态 ELF 逐号扫描的分类差异为 0 项（实测，第四节）。
3. 主进程、`:engine` 进程、exec 出的 bionic 动态可执行文件，三处 18 项系统调用的结果逐行一致（实测，第三节）。

被杀的不是 `socket`、线程、`ptrace`。本机实测这三类全部放行：4 个 pthread 正常、`socket`/`bind`/`listen`/`connect`/`accept` 正常、`api.deepseek.com:443` 的 TLS 握手成功。被 SIGSYS 杀的有 `set_robust_list`(99)、`get_robust_list`(100)、`rseq`(293)、`io_uring_setup/enter/register`(425–427)、`openat2`(437)、`faccessat2`(439) 等共 222 个调用号（清单见第四节）。`set_robust_list` 与 `rseq` 是 glibc 进程启动时必调的两个，glibc 版 Node 在这张表下启动即死，bionic 版没有这个问题（bionic 进程里线程、fork、socket 全部跑通，实测）。

「把 libnode 嵌进 App 进程」这条路在系统调用层面成立。需要避开的调用见第八节。

与任务描述的出入：任务描述转述 issue #24 的结论是 `clone3`、`socket`、`ptrace` 直接 SIGSYS。本机实测 `socket`(198)、`ptrace`(117) 放行，`clone3`(435) 返回 EPERM 而非被杀。issue #24 原帖没有读，出入的原因（设备、容器版本、探针方法）未查清。

一个影响 P0-C 的发现：容器内 App 连不上手机自己的 hdcd（第五节、第九节）。安卓无障碍这条路在本机上启用不了服务（第九节）。

## 二、环境事实（实测）

- `Build.VERSION.SDK_INT` = 36（容器里是 Android 16 用户态），`Build.MODEL` = VDE-AL00。
- `Build.FINGERPRINT` = `HUAWEI/VDE-AL00/HWVDE:16/HUAWEIVDE-AL00/104.5.5.105DHSP16:user/release-keys`。
- `/proc/version`：`Linux version 6.6.98 #1 SMP Sun Sep 20 14:09:17 UTC 2026`，aarch64，`uname -a` 主机名 `localhost`，shell 是 Toybox。
- `/proc/self/status`：`NoNewPrivs: 0`、`Seccomp: 2`、`Seccomp_filters: 2`，主进程、`:engine`、sh 子进程三处相同。
- `/proc/cpuinfo`：AArch64，Features 含 sve、bti、paca/pacg。
- 进程身份：uid 10093（`u0_a93`），附加组含 `inet(3003)`，SELinux 上下文 `u:r:aoco_untrusted_app_27:s0:c93,c256,c512,c768`。
- 命名空间：容器内进程 `net:[4026531983]`、`pid:[4026531985]`、`mnt:[4026532372]`；`hdc shell` 里的进程 `net:[4026540029]`。网络命名空间不同。
- `applicationInfo.nativeLibraryDir` = `/data/app/~~<随机>/probe.zyt-<随机>/lib/arm64`，`dataDir` = `/data/user/0/probe.zyt`，`targetSdk` = 28。
- `/system/bin` 权限 `drwxr-x--x root shell`，对 App 不可列目录（`File.list()` 返回空，`ls` 报 Permission denied），只能按名字探测。存在的名字：`sh toybox toolbox ls cat id uname grep sed awk tar gzip gunzip ping env sleep cp mv rm mkdir chmod find ps top kill getprop setprop am pm cmd settings input screencap uiautomator run-as logcat ip ifconfig netstat ss dd tr head tail wc sort xargs which app_process app_process64 linker64 dumpsys service`。探测过但不存在：`busybox curl wget nc ssh git python python3 node npm perl sqlite3 tcpdump strace`。`toybox` 的 applet 清单里有 `tar gzip find grep sed xargs timeout nohup nsenter unshare taskset` 等，没有 `curl`、`wget`、`nc`。
- 非 root 下 `ip addr` 报 `Cannot bind netlink socket: Permission denied`，`/proc/net/route`、`/proc/net/tcp` 读不了。

## 三、系统调用表一：主进程、`:engine`、exec 动态子进程（实测）

做法：每项在 fork 出的子进程里跑一次原始 `syscall()`，不 exec，继承父进程过滤器；父进程 `waitpid` 区分正常返回与被信号杀。`exec` 列是把同一套代码编成 `libprobeexec.so`（bionic 动态可执行文件，放在 nativeLibraryDir），由 `execve` 拉起后在它自己的进程里重跑。三列逐行一致，合并为一张。

| 调用（aarch64 号） | 主进程 | `:engine` | exec 动态子进程 |
|---|---|---|---|
| clone3 (435)，空参数 | ret=-1 EPERM | 同 | 同 |
| set_robust_list (99) | **SIGSYS** | 同 | 同 |
| rseq (293) | **SIGSYS** | 同 | 同 |
| socket (198) AF_INET | ret=fd | 同 | 同 |
| socketpair (199) | ret=0 | 同 | 同 |
| accept4 (242)，fd=-1 | ret=-1 EBADF | 同 | 同 |
| ptrace (117) TRACEME | ret=0 | 同 | 同 |
| io_uring_setup (425) | **SIGSYS** | 同 | 同 |
| openat2 (437) | **SIGSYS** | 同 | 同 |
| faccessat2 (439) | **SIGSYS** | 同 | 同 |
| pipe2 (59) | ret=0 | 同 | 同 |
| eventfd2 (19) | ret=fd | 同 | 同 |
| epoll_create1 (20) | ret=fd | 同 | 同 |
| memfd_create (279) | ret=fd | 同 | 同 |
| getrandom (278) | ret=8 | 同 | 同 |
| execve (221) `/system/bin/sh -c "exit 7"` | 子进程退出码 7（exec 成功） | 同 | 同 |
| pthread_create（fork 出的子进程里） | ret=0 | 同 | 同 |
| fork（fork 出的子进程里再 fork） | ret=0 | 同 | 同 |

任务清单里写的 `ptrace` 号 116 在 aarch64 是 `syslog`，`ptrace` 是 117，表中按 117 测。116 在扫描里作为危险项被跳过。

线程与网络（同样三处结果一致，实测）：

| 项 | 主进程 | `:engine` | exec 动态子进程 |
|---|---|---|---|
| 4 个 pthread 各跑 2000 万次循环 | 创建 4/4，32 ms，结果一致 | 4/4，290 ms | 4/4，31 ms |
| 127.0.0.1 随机端口 bind+listen，自连 connect+accept，收发 4 字节 | 成功 | 成功 | 成功 |
| UDP 回环收发 | 成功 | 成功 | 成功 |
| 裸 socket 连 api.deepseek.com:443（解析到 101.71.73.135），发 TLS 1.3 ClientHello | 收到 ServerHello，47 ms | 42 ms | 25 ms |
| Java `HttpsURLConnection` 访问 `https://api.deepseek.com/` | HTTP 401，TLS_AES_256_GCM_SHA384，190 ms，证书 CN=api.deepseek.com | HTTP 401，325 ms | 未测 |
| Java `ServerSocket` 绑 0.0.0.0 随机端口 | 成功 | 未测 | 未测 |

裸 socket 一行只证明「原生代码里 socket、connect、send、recv 全通、对端回 ServerHello」，没有做密钥交换（NDK 没有 TLS 库，没有引入第三方库）。完整握手由 Java `HttpsURLConnection` 一行承担，返回 401 是因为没带 API Key。

## 四、系统调用表二：exec 静态 ELF 逐号扫描（实测）

做法：运行时在 filesDir 写一个 aarch64 静态 ELF（64 字节头 + 56 字节程序头 + 15 条指令：x0–x5 清零，`x8=号`，`svc #0`，`exit`），chmod 700 后 execve，号 0–460 逐个跑；子进程把 core 上限设 0（不设会让 SIGSYS 的核心转储拖过超时被误判为阻塞），每号 2.5 秒超时。

汇总：461 个号，放行 213，SIGSYS 222，SIGSEGV 1（139 `rt_sigreturn`，伪造栈帧必崩，说明已进内核，按放行计），阻塞 2（72 `pselect6`、73 `ppoll`，无超时必阻塞，按放行计），跳过 23。

fork 不 exec 在主进程里重跑同一范围，分类差异 0 项。

**被 SIGSYS 杀的（222 个）**

- 线程簿记：99 `set_robust_list`、100 `get_robust_list`、293 `rseq`。
- 凭据：143 `setregid`、144 `setgid`、145 `setreuid`、146 `setuid`、149 `setresgid`、151 `setfsuid`、152 `setfsgid`、159 `setgroups`（`setresuid` 147 放行）。
- System V IPC 与 POSIX 消息队列：180–185 `mq_*`、186–197 `msg*`/`sem*`/`shm*`。
- 其它：18 `lookup_dcookie`、42 `nfsservctl`、202 `accept`（`accept4` 242 放行）、217–219 `add_key`/`request_key`/`keyctl`、234 `remap_file_pages`、235–239 `mbind`/`get_mempolicy`/`set_mempolicy`/`migrate_pages`/`move_pages`、262–265 `fanotify_init`/`fanotify_mark`/`name_to_handle_at`/`open_by_handle_at`、272 `kcmp`、288–290 `pkey_*`、292 `io_pgetevents`。
- 未分配号：244–259、295–423 全段（含 32 位时间类 403–423）。
- 新调用：425–427 `io_uring_*`、428–433 新挂载 API（`open_tree` 到 `fspick`）、437 `openat2`、439 `faccessat2`、442 `mount_setattr`、443 `quotactl_fd`、447 `memfd_secret`、448 `process_mrelease`、449 `futex_waitv`、450 `set_mempolicy_home_node`、451 `cachestat`、452 `fchmodat2`、453 `map_shadow_stack`、454–456 `futex_wake`/`futex_wait`/`futex_requeue`、457–460 `statmount`/`listmount`/`lsm_*`。

**放行的（含错误返回），与 Node/libuv 相关的**

`futex`(98)、`clone`(220)、`clone3`(435，返回 EPERM)、`execve`(221)、`execveat`(281)、`socket`/`socketpair`/`bind`/`listen`/`connect`/`accept4`/`sendto`/`recvfrom`/`sendmsg`/`recvmsg`/`getsockopt`/`setsockopt`(198–212、242、243、269)、`epoll_create1`/`epoll_ctl`/`epoll_pwait`/`epoll_pwait2`(20–22、441)、`eventfd2`(19)、`timerfd_*`(85–87)、`signalfd4`(74)、`inotify_*`(26–28)、`pipe2`(59)、`dup3`(24)、`statx`(291)、`copy_file_range`(285)、`preadv2`/`pwritev2`(286–287)、`getrandom`(278)、`memfd_create`(279)、`close_range`(436)、`pidfd_open`/`pidfd_send_signal`/`pidfd_getfd`(434、424、438)、`prlimit64`(261)、`sched_setaffinity`/`sched_getaffinity`(122–123)、`membarrier`(283)、`mlock2`(284)、`userfaultfd`(282，返回 ENOSYS)、`ptrace`(117)、`process_vm_readv`/`process_vm_writev`(270–271)、`sendfile`(71)、`splice`(76)、`setns`(268)、`unshare`(97)、`mknodat`(33)、`landlock_*`(444–446)。

**跳过未测的 23 个**：39 `umount2`、40 `mount`、41 `pivot_root`、51 `chroot`、58 `vhangup`、89 `acct`、104 `kexec_load`、105 `init_module`、106 `delete_module`、112 `clock_settime`、116 `syslog`、142 `reboot`、161 `sethostname`、162 `setdomainname`、170 `settimeofday`、171 `adjtimex`、224 `swapon`、225 `swapoff`、266 `clock_adjtime`、273 `finit_module`、277 `seccomp`、280 `bpf`、294 `kexec_file_load`。原因：这些会改系统状态，任务规矩不动系统设置。

完整 461 行结果在 `tooling/zhuoyitong-probe/results/report-wifi-final.json` 的 `sweep`（exec 静态 ELF）与 `sweepFork`（fork 不 exec）两个字段。

## 五、网络与环境（实测）

| 项 | 结果 |
|---|---|
| 原生 `connect` 192.168.31.226:46225（手机自己的无线调试端口） | 失败，ECONNREFUSED，1 ms |
| 原生 `connect` 127.0.0.1:46225 | 失败，ECONNREFUSED，1 ms |
| Java `Socket.connect` 同两个地址 | 失败，ConnectException，7 ms / 3 ms |
| 原生 `connect` 192.168.31.226 上宿主侧另外 4 个监听端口（40251、33917、62110、39347，由 `hdc shell netstat` 取得） | 4 个全部 ECONNREFUSED |
| Java `Socket.connect` App 自己的 HTTP 服务（192.168.31.226:\<端口\> 与 127.0.0.1:\<端口\>） | 都成功，7 ms / 3 ms |
| UDP 广播 `OHOS HDC` 到 255.255.255.255:8710 与 192.168.31.255:8710 | 发送成功（sent=8），2 秒内无应答；结论见下 |
| `getifaddrs` | 看得到 `wlan0=192.168.31.226` 与 `lo=127.0.0.1`；Java `NetworkInterface` 同 |
| 容器内 App 拿到的 Wi-Fi 地址 | 192.168.31.226，与宿主相同 |

对照（实测）：同一时刻 `hdc shell netstat` 显示宿主侧 `0.0.0.0:46225 LISTEN`，Mac 上 `nc -z 192.168.31.226 46225` 成功。

读法：容器是独立的网络命名空间（inode 与 hdc shell 不同），虽然地址显示为同一个 192.168.31.226，App 访问不到宿主命名空间里的监听口，包括 hdcd 的 46225。宿主命名空间的 `/proc/net/tcp` 里也找不到 App 的 HTTP 监听口，但 Mac 能经 Wi-Fi 访问它（见下一节），两边在 Wi-Fi 上共用同一个 IP 的实现机制未查清（推断：宿主对容器做了端口转发或桥接）。

UDP 8710 一行不能作为「容器内收不到发现应答」的证据：同一个 `OHOS HDC` 载荷从 Mac 单播和广播发给手机，同样 2 秒无应答，而宿主侧 `netstat -lun` 显示 8710 在监听，说明载荷格式不对。发现报文的真实格式读码未做，NOT_RUN。

「授权框在 App 前台时能否弹出并被点击」（P0C 第七节第四项）：NOT_RUN，卡在探针 App 没有自连 hdcd 的能力，连接本身被拒。

## 六、HTTP 对外可见性（实测）

App 内 `ServerSocket` 绑 0.0.0.0 随机端口（五次运行分别是 42231、35225、45927、46333、38429，每次启动不同），屏幕首页显示 `HTTP wlan0:192.168.31.226 端口 <port> /report`。Mac（192.168.31.182）上 `curl http://192.168.31.226:<port>/report` 返回 200，约 23 KB JSON。容器端口对 Wi-Fi 局域网可见。

## 七、exec 子进程能干什么（实测）

- `fork`+`execve` `/system/bin/sh -c "echo hi; id; uname -a"`：退出码 0，输出 `hi`、`uid=10093(u0_a93) ... context=u:r:aoco_untrusted_app_27...`、`Linux localhost 6.6.98 ... aarch64 Toybox`。Java `ProcessBuilder` 同样成功。
- nativeLibraryDir 下的 `libprobeexec.so`（动态链接的 bionic 可执行文件）execve 成功。
- 把它拷到 filesDir、chmod 后 execve 成功；运行时生成的静态 ELF 从 filesDir execve 成功。targetSdk 28 下 data 目录 exec 不受限，与 dsh-go 现状一致。
- exec 出的 bionic 子进程里线程、socket、loopback、TLS 全部可用（第三节第三列）。
- `/system/bin` 里没有 `curl`、`wget`、`git`、`nc`、`ssh`、`python`、`node`。

## 八、给 P1 的结论

进程内引擎（bionic `libnode.so`）要避开：

1. 任何 glibc 代码。glibc 启动必调 `set_robust_list` 与 `rseq`，两个都是 SIGSYS。这也意味着 dsh-go 现有的 glibc Node 在这张表下启动阶段就会死，与 issue #24 把原因归到 `socket`/`ptrace` 的说法不同（读码依据缺，推断）。
2. `io_uring`。libuv 在 Linux 上可能在启动或首次文件操作时调 `io_uring_setup`，调到即 SIGSYS。Node 24 自带的 libuv 版本里 io_uring 的默认开关未读码确认（NOT_RUN，归 P0-A）；稳妥做法是进程启动前 `setenv("UV_USE_IO_URING","0")`，并在 P2 冒烟里确认。
3. `accept`(202)，用 `accept4`(242)；libuv 用的是 `accept4`（推断，未核对源码）。
4. `openat2`、`faccessat2`、`fchmodat2`、新挂载 API、`futex_waitv`/`futex2` 系（449、454–456）、`cachestat`。Node 路径上的 `fs.access`、`fs.chmod` 是否经过 bionic 里的 `faccessat2`/`fchmodat2` 未测，需在 P2 用 `node -e` 跑 `fs.access`、`fs.chmod(path, mode)`、`fs.copyFile` 确认（NOT_RUN）。
5. `setuid`/`setgid` 家族（`setresuid` 除外）、SysV IPC、POSIX mq、`mbind` 系、`keyctl`、`fanotify`、`kcmp`。引擎里的 npm 包若用到会被杀。

可以放心用：线程（pthread、`clone`）、`socket` 全套、`epoll`、`eventfd2`、`timerfd`、`inotify`、`pipe2`、`statx`、`copy_file_range`、`getrandom`、`memfd_create`、`pidfd_*`、`ptrace`、`futex`、`fork`、`execve`。

exec 子进程仍可用：`/system/bin/sh` 加 toybox（`tar`、`gzip`、`find`、`grep`、`sed`、`awk`、`xargs`、`timeout`、`ps`、`kill`、`getprop` 等），nativeLibraryDir 与 filesDir 里的 bionic 可执行文件。静态 ELF 与 bionic 动态程序走同一张表，没有额外限制。

shell 工具能用 `/system/bin/sh`；工具集里没有 `curl`、`wget`、`git`、`nc`、`ssh`，需要联网下载或 git 的功能要自带 bionic 版二进制（放 nativeLibraryDir）或改走引擎内 HTTP。pnpm/npm 安装插件靠 Node 自身网络，不依赖外部 curl。

PRoot 本身未测：`ptrace`(117)、`process_vm_readv/writev`(270–271)、`wait4`、`execve` 放行，但 PRoot 的完整工作流（`PTRACE_SEIZE`、`PTRACE_SYSCALL` 等请求）没跑，NOT_RUN。

## 九、第二轮：容器到宿主可达性与无障碍

日期：2026年11月02日。数据文件：`tooling/zhuoyitong-probe/results/round2-reach.json`（可达性与无障碍全表）、`results/fg-poll-round2.txt`、`results/fg-poll-round2b.txt`（每 0.5 秒记一次前台窗口 bundle/ability）。

### 9.1 容器里有没有路径到宿主 hdcd

**结论：本轮没有找到。** 试过的每个候选都不通，但其中一个候选的失败方式（172.17.1.1 静默超时，而不是被拒）说明宿主侧存在一个容器可路由到的桥地址，被防火墙之类的东西丢包，不能排除该路径。

宿主侧拓扑（`hdc shell ifconfig` 与 `/proc/net/arp`，实测，宿主视角）：

| 接口 | 地址 | 备注 |
|---|---|---|
| wlan0 | 192.168.31.226/24，MAC 32:e9:78:48:06:e0 | 真实 Wi-Fi；Mac 的 ARP 表里 .226 也是这个 MAC |
| ancowlan0 | 172.17.1.1/24，MAC be:d0:bd:93:5f:09 | 容器桥，宿主一侧 |
| lo、rmnet_ims00、p2p0、chba0 | 无可用 IPv4 | |
| 宿主 ARP 表 | 172.17.1.2 在 ancowlan0 上，MAC 6a:9b:ee:a5:d6:60 | 这个 MAC 与容器内 wlan0 的 fe80 地址 `fe80::689b:eeff:fea5:d660` 吻合（EUI-64），即 172.17.1.2 是容器 |

所以容器实际接在 ancowlan0 桥上，宿主一侧是 172.17.1.1。容器内看到的却是 `wlan0 = 192.168.31.226/24`、默认网关 192.168.31.1（`ConnectivityManager.LinkProperties` 实测），和宿主 Wi-Fi 地址相同。两个地址的对应关系（NAT、虚拟化出来的假地址或其它）没有查清。

容器内非 root 读不到任何网络表：`ip addr/route/neigh/rule` 报 `Cannot bind netlink socket: Permission denied`，`/proc/net/{route,arp,fib_trie,if_inet6,tcp}` 全部 Permission denied，`getprop` 过滤不到任何网络项，`/etc/hosts` 只有 localhost。容器内能拿到网络信息的途径是 Java `NetworkInterface`（wlan0 与 lo，wlan0 有 `fe80::689b:eeff:fea5:d660%wlan0/64` 与 192.168.31.226/24）和 `LinkProperties`（路由 192.168.31.0/24 直连、默认路由经 192.168.31.1；DNS 为 192.168.31.1、`fe80::d6da:21ff:fe86:fad1`、`2400:3200::1`）。

逐个候选 `connect`（Java Socket，2.5 秒超时，实测）：

| 目的地址:端口 | 结果 | 含义 |
|---|---|---|
| 192.168.31.226:46225（宿主 Wi-Fi 地址） | ECONNREFUSED，3 ms | 在容器本机内被拒，包没有出容器 |
| 127.0.0.1:46225 | ECONNREFUSED | 同上 |
| ::1:46225 | ECONNREFUSED | 同上 |
| fe80::689b:eeff:fea5:d660%wlan0:46225（容器自己的 link-local） | ECONNREFUSED | 同上 |
| 172.17.1.2:46225（容器自己的桥侧地址） | ECONNREFUSED，3 ms | 同上 |
| 192.168.31.1:46225（默认网关） | ECONNREFUSED，5 ms | 路由器自己回的 RST |
| fe80::d6da:21ff:fe86:fad1:46225（网关 link-local） | ECONNREFUSED，1 ms | 同上 |
| 2400:3200::1:46225（DNS，公网） | ECONNREFUSED，1 ms | 同上 |
| 192.168.1.1:46225 | ECONNREFUSED | 同上 |
| 172.17.1.1:46225（宿主桥地址，宿主 hdcd 监听 0.0.0.0） | TIMEOUT，2.5 s | 没有 RST 也没有 SYN-ACK，包被丢 |
| 172.17.1.1:40251 / 33917 / 62110 / 39347（宿主其它监听口） | TIMEOUT，2.5 s | 同上 |
| 10.0.2.2、10.0.3.1、172.17.0.1、100.64.0.1:46225 | TIMEOUT | 随手猜的地址，只能说明默认路由出去后无应答 |
| 192.168.31.182:22（Mac，对照） | CONNECTED，12 ms，首包 `SSH-2.0-OpenSSH_10.3` | 容器对 Wi-Fi 上其它主机的 TCP 完全通 |

读法：

1. 目的地址是容器自己（.226、127.0.0.1、自己的 fe80、172.17.1.2）时，包在容器本机就被拒，到不了宿主。这与上一轮判断一致。
2. 容器对局域网其它主机（Mac、路由器）出站正常。
3. 172.17.1.1 是宿主 hdcd 在容器桥上真实存在的地址，connect 只超时。是否真有路由到它、是桥上防火墙丢了，还是包走默认网关出了 Wi-Fi 然后被路由器丢掉，当前数据区分不了（读不到容器路由表，宿主侧没有 iptables/ip/tcpdump 可用）。
4. UDP 8710 发现这轮没有做。

对 P0-C 的含义：TCP 自连 hdcd 在目前所有已知地址上都不通；172.17.1.1 超时不能当作「不通」的定论，可以追加的下一步是 (a) 在宿主侧用能看包的手段确认 172.17.1.1 的 SYN 是否到达 ancowlan0，(b) 在容器内用 `LinkProperties` 之外的办法读路由表。

### 9.2 无障碍能不能穿透到鸿蒙界面

**结论：不成立，且没走到「穿透」那一步，卡在无法启用服务。** 容器的 Android 框架里无障碍服务列表是空的。

实测依据：

- 探针 App 声明了 `ProbeA11yService`（`canRetrieveWindowContent`、`flagDefault|flagRetrieveInteractiveWindows`、`typeAllMask`）。`AccessibilityManager.getInstalledAccessibilityServiceList()` 返回 0 项，包括它自己；`getEnabledAccessibilityServiceList()` 为空；`isEnabled=false`；`Settings.Secure.accessibility_enabled=0`、`enabled_accessibility_services=null`。服务从未进入 `onServiceConnected`，没有产生 a11y.json。
- 启用路径全部走不通。`startActivity(ACTION_ACCESSIBILITY_SETTINGS)`（有无 NEW_TASK 标志、有无 `setPackage`）和显式组件 `Settings$AccessibilitySettingsActivity` 返回成功，`resolveActivity` 命中 `com.android.settings/...AccessibilitySettingsActivity`，但前台窗口始终是 `probe.zyt.MainActivity`，我们 Activity 的生命周期只有 onPause 与 onResume 相隔 20–80 ms，页面一闪而过未显示。`ACCESSIBILITY_DETAILS_SETTINGS`（带本服务组件名）与用 `SubSettings` 加 `:settings:show_fragment` 直开无障碍页同样前台无变化（`fg-poll-round2b.txt`）。
- 同一个 `com.android.settings` 的其它页能打开：`ACTION_APPLICATION_DETAILS_SETTINGS`（`package:probe.zyt`）打开了容器的 `com.android.settings/...InstalledAppDetails`，窗口里有「应用信息」「ZytProbe」「卸载」「强行停止」「通知管理」「后台弹窗」「查看所有权限」「存储」。说明容器里有可用的 Android 设置，无障碍页被挡掉，不是设置整体不可用。
- `ACTION_SETTINGS` 拉起的是鸿蒙原生设置：前台窗口为 `com.huawei.hmos.settings/com.huawei.hmos.settings.MainAbility`，页面文字是「显示和亮度、护眼模式、电子书模式、字体大小和界面缩放、休眠」等。也就是说容器会把部分系统设置意图重定向到宿主鸿蒙设置。
- `am start` 在容器 shell 里报 `SecurityException: package=com.android.shell does not belong to uid=10093`，不能用它绕过。
- 不能改系统设置，所以没有尝试 `WRITE_SECURE_SETTINGS`、`settings put` 或 `pm grant`。

没有测到的：服务若能启用，它能否在鸿蒙原生窗口在前台时看到该窗口的节点。NOT_RUN，原因是服务启用不了，这一项没有数据。

对「手机操控第三条路」：安卓无障碍这条路在本机上的前置条件（让用户在安卓设置页打开服务）无法满足，已知的所有入口都被挡。结论是这条路目前走不通。

一个读 `dumpLayout` 的发现：鸿蒙 `uitest` 的窗口树里，容器里的安卓 Activity 以 `bundleName=<包名>`、`abilityName=<Activity 类名>` 的形式出现在顶层窗口（`probe.zyt/probe.zyt.MainActivity`、`com.android.settings/...InstalledAppDetails`），所以从鸿蒙侧看容器 App 是一级窗口，`uitest dumpLayout` 能读到它们的控件文字。这支持 P0-C 的方案本身：手机操控靠 hdc 的 uitest，不依赖安卓无障碍。

### 9.3 本轮留下的东西

- 探针新增：`可达性` 按钮（只跑第九节第一项加无障碍信息）、`设置尝试` 按钮（顺序尝试几种设置入口并写生命周期日志）、`无障碍设置` 与 `无障碍结果` 按钮、`scripts/fgpoll.py`（每 0.5 秒记前台窗口）。
- 过程插曲：重装后卓易通桌面的图标页不在原位，我误点了桌面一次「搜应用」并逐个点了几个文件夹找探针图标，没有改任何设置。后来用文件管理器安装流程里的「打开」按钮回到 App。

## 十、待拍板

1. P0C 的自连 hdcd 前提在 TCP 层被实测否定（第五节）：容器内连不上宿主 hdcd，也连不上宿主任何监听口。需要决定是否追加一轮「容器到宿主可达性」探针（候选：试连 hdcd 的 unix socket 路径、读 `/proc/net/arp`、找容器网关与宿主侧桥地址、看卓易通是否暴露宿主桥接端口）。不追加则手机操控通道要另找，验收二无法达成。
2. issue #24 的结论与本机实测不符（`socket`、`ptrace` 放行，`clone3` 为 EPERM）。是否值得给 Planetpai 回帖附本机表格，由用户定。
3. 无。

## 十一、复现

- 构建：`tooling/zhuoyitong-probe/scripts/build.sh`，产物 `app/build/outputs/apk/release/app-release.apk`（715 KB，arm64-v8a）。
- 安装：`HDC_TARGET=<usb序列号> tooling/zhuoyitong-probe/scripts/install.sh`，走文件管理器与卓易通安装器；`uitest_tap.py` 现在读 `HDC_TARGET` 环境变量选目标。
- 屏幕上分页显示全部结果（每页 24 行、每行 ≤58 字符，`上一页/下一页/重跑` 按钮），`uitest dumpLayout` 可读出全文；JSON 写在 `filesDir/report.json` 与 `getExternalFilesDir/report.json`，hdc 读不到，取回靠 HTTP。
- 备注：安装中途手机一度锁屏，`aa start` 报 10106102，用户解锁后继续；探针运行期间有一次被切到系统设置，回到桌面点图标后进程状态保持，结果完整。
