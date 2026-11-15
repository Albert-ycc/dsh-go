# P0-C 自连 HDC 通道 PoC

日期：2026年11月02日。源码基线：OpenHarmony `developtools_hdc` master `fb114f4`（2025-09-13，gitee.com/openharmony/developtools_hdc，只取 src/common、src/host、src/daemon）。真机 hdcd 是 3.2.0e（`param get const.hdc.version`），源码比它旧，差异见第六节。标注含义：【读码】源码阅读，【实测】本机命令输出，【推断】由前两者推出。

## 一、结论

1. 协议层可行【读码】：hdcd TCP 模式是「11 字节封包头 + protobuf 风格头 + 载荷」，认证是 RSA-3072/SHA512 的 PSS 签名挑战，全部可用 JDK 自带 `java.security` 实现，不需要官方二进制。
2. 自写客户端已对真机 hdcd（192.168.31.226:46225）完成握手、RSA 认证，并跑通 `snapshot_display`、`uitest dumpLayout`、`uitest uiInput keyEvent Back`，截图经 `recv` 拉回后 md5 与官方 `hdc file recv` 一致【实测】。G0 中「PoC 对真机完成认证并截到屏」满足。
   daemon 没有要求 TLS-PSK：握手全程 authType 为 3、2、4，从未出现 6；AUTH_OK 里 `supportfeatures=heartbeat,encrypt_tcp` 只是 daemon 声明自己支持，客户端不声明就走明文通道【实测】。
3. 用 USB 通路跑通了目标命令的官方对照输出（第五节）。
4. 容器内 App 能否连到手机自己的无线调试端口：未测，列入 P0-B 探针（第七节）。

## 二、传输与认证流程

### 2.1 封包（session.cpp: `HdcSessionBase::Send` / `OnRead` / `DecryptPayload`）【读码】

```
PayloadHead (packed, 11B)  flag="HW"(2) | reserve(2)=0 | protocolVer=1(1) | headSize(u16 BE) | dataSize(u32 BE)
PayloadProtect (protobuf)  1:channelId(varint) 2:commandFlag(varint) 3:checkSum(varint,0) 4:vCode(varint)=0x09
载荷                       dataSize 字节
```

字段号来自 serial_struct.h `Descriptor<PayloadProtect>`；vCode 常量 `payloadProtectStaticVcode=0x09`（session.h）；校验和 `ENABLE_IO_CHECKSUM=false`（define.h）。线格式是标准 protobuf（serial_struct_define.h 的 `WriteVarint` / `MakeTagWireType`：tag<<3|wiretype）。

### 2.2 SessionHandShake（serial_struct.h `Descriptor<SessionHandShake>`，session.h 结构体）【读码】

| 字段号 | 名 | 类型 | 用法 |
|---|---|---|---|
| 1 | banner | string | 固定 `OHOS HDC`（define.h `HANDSHAKE_MESSAGE`） |
| 2 | authType | uint8 | 0 NONE，2 SIGNATURE，3 PUBLICKEY，4 OK，6 SSL_TLS_PSK（session.h `AuthType`） |
| 3 | sessionId | uint32 | 首包随机数，daemon 用它登记会话 |
| 4 | connectKey | string | `ip:port` |
| 5 | buf | string | 随阶段变化，见下；内含 TLV 时用 16 字节 tag + 16 字节十进制长度 + 值（base.cpp `TlvAppend`） |
| 6 | version | string | `Ver: 3.1.0e`+构建哈希；daemon 只要求 ≥ `Ver: 3.0.0b`，不一致仅记日志（daemon.cpp `HandDaemonAuth`、`DaemonSessionHandshake`） |

### 2.3 握手时序（host/server 侧 session.cpp `WorkThreadStartSession`、server.cpp `HandServerAuth`；daemon.cpp `HandDaemonAuth*`）【读码】

1. TCP 连上后 **host 先发**：`authType=NONE`，`buf`=TLV(`authtype`=`1`)，声明支持 RSA_3072_SHA512（`WorkThreadInitSession`）。不带 `supportfeatures` 里的 `encrypt_tcp`，则不触发 TLS-PSK 分支（daemon.cpp `SendAuthMsg`：仅当 `supportEncrypt` 才走 `SendAuthEncryptPsk`）。
2. daemon 回 `authType=PUBLICKEY`，`buf`=TLV(authtype=1)（`HandDaemonAuthInit`）。
3. host 回 `authType=PUBLICKEY`，`buf` = `hostname` + `\f`(0x0C) + 公钥 PEM（`HdcAuth::GetPublicKeyinfo`，auth.cpp:766）。公钥取 `~/.harmony/hdckey.pub`，SubjectPublicKeyInfo PEM，625 字节。
4. 公钥首次登记与弹窗（daemon.cpp `HandDaemonAuthPubkey`、`AlreadyInKnownHosts`、`PostUIConfirm`、`UpdateKnownHosts`）：daemon 把 PEM 与 `/data/service/el1/public/hdc/hdc_keys` 比对（要求长度恰为 625 且为子串）。已在内则不弹窗；不在则先给 host 发一条 `AUTH_OK` 形态的消息，TLV `emgmsg`=`[E000002]:The device unauthorized...`、`daemonauthstatus`=unauthorized，同时拉起授权框（`ShowPermitDialog`，结果经系统参数 `persist.hdc.daemon.auth_result` 回传：1 本次允许，2 始终允许，其余拒绝）。「始终允许」写入 hdc_keys，之后同一公钥免弹窗。**弹窗时机是第 3 步收到公钥之后、第 5 步之前，host 此时阻塞等待。** 拒绝则回 `[E000003]`。
5. 允许后 daemon 回 `authType=SIGNATURE`，`buf`=随机 token（`SendAuthSignMsg`，token 为 20 字节随机串 `GetSecureRandomString(SHA_DIGEST_LENGTH)`）。
6. host 签名：`SHA512(token)` 后 RSA-PSS，MGF1-SHA512，盐长=摘要长度 64（auth.cpp `RsaSign`，`RSA_PSS_SALTLEN_DIGEST`），结果标准 base64（`EVP_EncodeBlock`），以 `authType=SIGNATURE` 回送。daemon 侧验签盐长用 AUTO（`AuthVerifyRsaSign`），故 64 可过。声明不支持 3072 的旧路径是 `RSA_private_encrypt` PKCS1（`RsaEncrypt`），不需要。
7. daemon 验过后发 `authType=OK`，`buf`=TLV(`devname`、`daemonauthstatus`、`supportfeatures`…)，再发一个 `CMD_KERNEL_CHANNEL_CLOSE`（`SendAuthOkMsg`）。此后会话可发命令。密钥为 RSA-3072（auth.cpp `GenerateNewKey`；本机 hdckey 是 PKCS#8 无口令 PEM）。

### 2.4 命令（translate.cpp、server_for_client.cpp `DoCommandRemote`、daemon_unity.cpp `CommandDispatch`、task.cpp `TaskFinish`）【读码】

`hdc shell <cmd>`：client 直接发 `commandFlag=CMD_UNITY_EXECUTE(1001)`，载荷是命令字符串（无 NUL），channelId 任选非 0；daemon 把输出以若干数据帧回传，结束时发 `CMD_KERNEL_CHANNEL_CLOSE(2)`，载荷 1 字节。PoC 即按此收集。命令号表见 define_enum.h `HdcCommand`。官方 `file recv` 走 `CMD_FILE_INIT(3000)` 任务协议（common/transfer.cpp、file.cpp 的 TransferConfig / 压缩分块），PoC 未实现，`recv` 用 `shell "base64 < 文件"` 兜底（设备上 /bin/base64 存在【实测】）。

## 三、无线调试与 tconn【读码 + 实测】

- daemon 的 TCP 监听由系统参数 `persist.hdc.port` 决定，0/未设则由系统分配随机端口并写回该参数；绑定 `0.0.0.0`；同时在 UDP 8710 回应广播发现，应答串是 `OHOS HDC-<tcp端口>`（daemon_tcp.cpp `SetTCPListen` / `TransmitConfig`）。
- 官方流程：USB 下 `hdc tmode port <端口>` 打开 TCP 监听（hdcd 会重启），`hdc tconn <ip>:<端口>` 连接；host 侧 `HdcHostTCP::ConnectDaemon` 即普通 TCP connect 后走 2.3 的握手。设置页「无线调试」开关与 `tmode port` 的关系本次未验证【推断：同一机制】。
- 本机实测：用户在手机开发者选项打开无线调试前，`param get persist.hdc.port`、`persist.hdc.mode` 均返回 1002（未设置），hdcd 无 TCP 监听；打开后监听端口为 46225（本次端口，每次开关可能变）【实测】。

## 四、PoC 实测记录

环境：Mac 侧 hdc 3.1.0e，设备 hdcd 3.2.0e；USB 连接。

| 步骤 | 命令 | 结果 |
|---|---|---|
| 设备在线 | `hdc list targets -v` | `<USB序列号> USB Connected localhost` |
| 设备网络 | `hdc shell netstat -ltn` | 192.168.31.226 上监听 40251、33917、62110、39347 四个端口，进程名不可见 |
| 探测四个端口 | `/usr/bin/python3 hdc_client.py --host 192.168.31.226 --port <p> --debug --auth-timeout 6 shell "echo hi"` | 40251、62110：6 秒无应答超时；33917、39347：发出握手包后连接被对端复位。没有任何一个返回 hdc 握手包 |
| 本地自测 | `/usr/bin/python3 selftest_mock.py` | `mock selftest OK`，签名被 Mac 公钥验过 |
| 无线调试开启（用户手动） | 端口 46225，官方 `hdc tconn 192.168.31.226:46225` | Connect OK【协调方转述】；`hdc list targets -v` 同时列出 `192.168.31.226:46225 TCP Connected` 与 USB 目标【实测】 |
| 握手+认证+截屏 | `hdc_client.py --host 192.168.31.226 --port 46225 --debug shell "snapshot_display -f /data/local/tmp/poc.jpeg"` | 日志依次为 `authType=3 buf=33B`、`authType=2 buf=20B`、`authType=4 buf=203B`，`AUTH_OK devname='localhost' status='SUCCESS'`；未弹授权框（公钥已在 hdc_keys）；输出 `success: snapshot display 0 , write to /data/local/tmp/poc.jpeg as jpeg, width: 1320, height: 2120` |
| AUTH_OK 的 TLV | 同上，打印 tlv | `devname=localhost`、`daemonauthstatus=SUCCESS`、`1200=enable`（支持 CMD_UNITY_EXECUTE_EX）、`supportfeatures=heartbeat,encrypt_tcp` |
| 控件树 | `shell "uitest dumpLayout -p /data/local/tmp/poc-layout.json"` | `DumpLayout saved to:/data/local/tmp/poc-layout.json`，设备端 56337 字节 |
| 无副作用按键 | `shell "uitest uiInput keyEvent Back"` | `No Error` |
| 拉回截图 | `hdc_client.py ... recv /data/local/tmp/poc.jpeg /tmp/x.jpeg` | 98166 字节，JPEG 1320x2120，md5 `401d1d2c6121ae6ffab1bbb8e35ce5b3`，与设备端 `md5sum` 及官方 `hdc -t 192.168.31.226:46225 file recv` 拉回的文件三者一致 |
| 点击 | `uitest uiInput click` | 未执行；Back 已验证 uiInput 通路，坐标点击留给 P2 带界面的联调 |

观察：同一轮里有三次 `connect()` 超时（10 秒，且当时 `ping` 返回 Host is down），隔几十秒后 `nc -z` 通、客户端恢复。判断为手机 Wi-Fi 省电休眠，不是协议问题【推断】；App 内需要连接重试与超时重连。PoC 的 `recv` 因经 base64 管道，98KB 文件用时正常，大文件吞吐未测。

本次未修改手机的 tmode，也未触碰 hdc_keys；无线调试由用户在手机开发者选项里开启。

### 官方 hdc 对照输出（USB 通路，已留存）【实测】

```
hdc shell "snapshot_display -f /data/local/tmp/ref.jpeg"
  process: display 0, file type: jpeg, width: 1320, height: 2120
  success: snapshot display 0 , write to /data/local/tmp/ref.jpeg as jpeg, width: 1320, height: 2120
  文件 98883 字节，md5 89db8f9477ec857b08683d61cddf0e0e（设备端与 hdc file recv 拉回后一致）
hdc shell "uitest dumpLayout -p /data/local/tmp/ref_layout.json"
  DumpLayout saved to:/data/local/tmp/ref_layout.json   （56335 字节）
hdc shell uitest uiInput        # 用法：click/doubleClick/longClick <x> <y>、swipe/drag、dircFling、inputText、keyEvent
```

`uitest uiInput click` 未在真机执行，避免在用户手机上产生误触，留给 TCP 通路就绪后用 (x,y) 落在空白区的点击验证。

## 五、移植到 App 内（Kotlin）【推断】

工作量：约 3 人天。封包、protobuf（只用 varint 与 len-delimited，手写 60 行）、TLV、握手状态机都是纯字节操作；RSA 签名用 `Signature.getInstance("RSASSA-PSS")` 配 `PSSParameterSpec("SHA-512","MGF1",MGF1ParameterSpec.SHA512,64,1)`，私钥以 PKCS#8 载入，公钥导出 SubjectPublicKeyInfo PEM 并保证 625 字节。额外项：密钥对在 App 内用 `KeyPairGenerator("RSA",3072)` 自建，首次连接触发手机授权框，之后「始终允许」免弹；shell 输出流式读取；命令帧超时与断线重连；`file recv` 若要实现需另加约 2 人天，建议截图改走 `snapshot_display` 后 `base64` 兜底或直接实现 FILE 协议二选一，P1 定。

风险：
1. 设备 hdcd 为 3.2.0e，本次实测未强制 TLS-PSK（见第一节），该项风险仅在以后固件升级强制加密时出现（`AUTH_SSL_TLS_PSK`、`FEATURE_ENCRYPT_TCP`，session.cpp `SetFeature`、daemon.cpp `DaemonSSLHandshake`）。PoC 不声明该特性；若 daemon 拒绝，需实现 TLS 1.2/1.3 PSK 套件 `TLS_AES_128_GCM_SHA256`（host_ssl.cpp、daemon_ssl.cpp、auth.cpp `RsaPubkeyEncryptPsk`），Android 的 JSSE 对 PSK 套件无标准支持，要引入 Conscrypt/BouncyCastle，工作量增加约 3 到 4 人天。
2. 无线调试的端口每次开关可能变化（`persist.hdc.port`=0 时随机分配），App 需要让用户输入端口，或走 UDP 8710 广播发现（`OHOS HDC-<port>` 应答），后者在容器网络里是否可用未知。
3. 每次重启或关闭无线调试后，是否保留 hdc_keys 授权未验证。
4. 空闲心跳：daemon 声明支持 `heartbeat` 特性时，长期无数据可能被断开（session.cpp `FetchIOBuf`、heartbeat.cpp），长连接需要实现 `CMD_HEARTBEAT_MSG(5000)`；PoC 未声明该特性。

## 六、读码与真机版本差异

源码 `HDC_VERSION_NUMBER=0x30200100`（3.2.0a）而真机报 3.2.0e，client 3.1.0e。认证三步和 `PayloadHead` 在新版沿用的可能性高，但没有真机抓包证实。对照方式：待设备开 TCP 后，用一个 Python 转发代理夹在官方 `hdc tconn 127.0.0.1:<代理端口>` 与手机之间，抓握手阶段明文字节与 PoC 比对（TLS-PSK 建立之后的字节是密文，只能比对到 authType 切换处）。

## 七、P0-B 探针要补的项

- 容器内 App 进程对 `192.168.31.226:<hdcd端口>`（本机 Wi-Fi 地址）与 `127.0.0.1:<hdcd端口>` 分别 `connect()`：是否放行、耗时、错误码。
- 容器内能否 `getifaddrs` / 读到本机 Wi-Fi IP；UDP 8710 广播能否发出与收到。
- 无线调试端口是否在容器网络命名空间内可达（hdcd 绑定 0.0.0.0，理论上回环也可达，未测）。
- 授权框在 App 前台时能否正常弹出并被点击。

## 八、待拍板

1. 无。TLS-PSK 未被要求，Kotlin 移植按第五节约 3 人天估算，不含 TLS。
2. 授权弹窗路径（公钥未登记时手机弹框、`persist.hdc.daemon.auth_result`）本次因公钥已登记而没有触发，只有读码依据。App 内用新生成的密钥首次连接时需要在真机上走一遍，建议并入 P2 联调。

## 文件

- `/Users/albert/workspace/dsh-go/tooling/hdc-poc/hdc_client.py`（运行用 `/usr/bin/python3`，本机 `python3` 是不带 cryptography 的 venv）
- `/Users/albert/workspace/dsh-go/tooling/hdc-poc/selftest_mock.py`
