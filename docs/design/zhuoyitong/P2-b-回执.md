# P2-b 回执：Kotlin 驱动与配置

日期：2026年11月02日。基线：dsh-go `main` 8fe1c1c 加工作树里引擎线 P2-3b 已交付的改动。证据标记：〔实测〕本次执行并看到输出；〔读码〕读了源文件；〔推断〕未执行；NOT_RUN 没跑。

## 1. 改动清单

新增（`app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/relay/`）：

| 文件 | 内容 |
|---|---|
| `RelayTokenStore.kt` | `filesDir/phone-relay/relay.json`（addr、fp、token），先收紧权限再写入，原子替换；`RelayConfig.toString()` 不含令牌 |
| `RelayClient.kt` | `RelayApi` 接口、`RelayClient`（HTTPS、叶证书 SHA-256 钉扎、Bearer、`X-Dsh-Relay`/`X-Request-Id`/`X-Dsh-Task`、连接 3 s、读 10/8/11/5 s、无应答同 id 重发一次、202 间隔 300 ms 同 id 再问、`device` 缓存）、错误码映射（`RelayCodes`）、给模型的句子（`RelayMessages.forModel`） |
| `RelayPairing.kt` | 一次性配对码 → PBKDF2-HMAC-SHA256（20 万次）→ HMAC 通道绑定；先探叶证书指纹，再在钉扎该指纹的连接上发 `/v1/pair`，校验 `serverProof` 通过才落盘 |
| `RelayTreeMapper.kt` | 中继 observe JSON → `UiWindow/UiNode`（供现有 `ScreenFormat`）；小系统窗口丢弃，主窗口放最后，窗口号取 bundle/ability 哈希以便跨两次读屏稳定 |
| `MacRelayDriver.kt` | `PhoneDriver`（MAIN）。每个写动作先重读、按指纹重新找到元素、点它此刻的中心；输入用 `replaceText` 加回读比对；动作后等 600 ms 再读（未画完再读至多两次）；`refuseWrite` 与 `ProtectedPages` 同安卓；还有 `RelayStatus`（`PhoneStatus.relay.state` 的八个取值） |
| `RelayShot.kt` | 中继 JPEG 缩到长边 ≤1300、灰掉密码框、画元素编号（`ScreenShooter.draw` 的第二份拷贝，按三次法则不抽取） |
| `RelaySystemApi.kt` | 容器应用走原 `SystemApi`，鸿蒙应用经中继列出与拉起；alarm/timer/calendar 返回指路句 |
| `PhoneHand.kt` | `auto|a11y|mac`；`auto` 用 `EngineFamily.confinedDomain(SELinux 域)`，容器取 `mac`，其余取 `a11y` |

改动：

| 文件 | 改动 |
|---|---|
| `PhoneControl.kt` | 只做局部替换，保留引擎线加的 `prepareLaunch(..., paths: GuestPaths = GuestPaths.IDENTITY)`。mac 手下：`driver(MAIN)` 返回 `MacRelayDriver` 且不等 `A11yKeeper`；`system()` 返回 `RelaySystemApi`；`isLocked()` 并入中继最近一次读到的锁屏（只读缓存，不联网）；`a11yState()`、`notifyFix`（点开进 `dshgo://open/phone-relay`）、`testScreen()`、`status()`（新增 `hand`、`handSetting`、`relay`）；任务停止时异步通知 Mac 取消在途命令；`reopen()` 在 mac 手下 0.8 秒后若 App 仍不在前台，请 Mac `aa start` 拉前台；`keep()` 与每 2 秒 tick 在 mac 手下不再跑 `A11yKeeper`。a11y 手下的分支一行未动 |
| `PhonePlugin.kt` | 新增 method channel：`setHand`、`relayPair`、`relayTest`、`relaySetTarget`、`relayHalt`、`relayForget` |
| `SafetyGate.kt` | `SYSTEM_PACKAGES` 加六个鸿蒙包 |
| `a11y/ProtectedPages.kt` | `packages` 加三个，`settingsPackages` 加 `com.huawei.hmos.settings` |
| `res/values{,-zh}/phone_relay_strings.xml` | 新增，14 条中英文案 |

测试（`app/android/app/src/test/kotlin/.../phone/relay/`）：`FakeRelayServer.kt`、`FakeRelay.kt`（假中继）、`RelayClientTest`、`RelayPairingTest`、`RelayTreeMapperTest`、`MacRelayDriverTest`、`RelayMiscTest.kt`（`PhoneHandTest`、`HarmonySystemPackagesTest`、`RelaySystemApiTest`）。

与派单不同之处：假中继没有用 `com.sun.net.httpserver.HttpsServer`。单元测试的编译类路径是 android.jar，Kotlin 与 javac 都看不到 `jdk.httpserver`（〔实测〕编译报 `package com.sun.net.httpserver does not exist`）。改用 JDK 的 `SSLServerSocket` 手写一个最小 HTTPS/1.1 服务（一连接一请求），TLS 栈仍是 JDK 的，证书是测试时现生成的 P-256 自签证书。

## 2. 验证环境

工作树里引擎线当时的 `RuntimeController.kt` 编译不过（〔实测〕第一次运行报 `K_FAMILY_OVERRIDE` 等未解析），所以先在 `/private/tmp/claude-501/p2b-ws/`（`git archive HEAD` 加 `.dart_tool` 等被忽略文件）里跑基线，引擎线交付后再把整个 `app/android/app/src` 同步进去跑最终结果。契约样例读 `tooling/dsh-relay/contract/expected/`（P2-a 已交付），测试相对路径 `../../../tooling/dsh-relay/contract/expected`。

命令：

```
source tooling/env-mac.sh
cd app/android
./gradlew :app:testX64DebugUnitTest --offline --continue
```

〔实测〕结果：909 个测试，1 个失败，即已知的 `L2sMigrationTest > aCopyLeftByAKilledRunIsRemovedAndLookalikeUserFilesStay`。基线（HEAD，未加我的改动）四个指定类跑过：`BUILD SUCCESSFUL`。

| 类 | 测试数 | 失败 |
|---|---|---|
| PhoneToolSchemaTest | 5 | 0 |
| SafetyGateTest | 8 | 0 |
| SafetyGateEnglishTest | 2 | 0 |
| PhoneCorePromptOwnerTest | 11 | 0 |
| RelayClientTest | 13 | 0 |
| RelayPairingTest | 7 | 0 |
| RelayTreeMapperTest | 10 | 0 |
| MacRelayDriverTest | 17 | 0 |
| RelaySystemApiTest | 4 | 0 |
| PhoneHandTest | 2 | 0 |
| HarmonySystemPackagesTest | 2 | 0 |

## 3. 判定标准逐条

1. 钉扎、配对向量、错误码、幂等重发：通过〔实测〕。
   - 钉扎：指纹不符返回 `pin_mismatch`，假中继收到的请求数为 0（握手阶段就断）。
   - 配对向量：PBKDF2 密钥、`proof`、`serverProof` 的期望值用 node:crypto 单独算出，Kotlin 对拍一致；另有完整配对流程、错码、中继证明不对、中间人换证书四个用例，后三者都不落盘。
   - 错误码：十个中继码各自映射，另有无可读正文时按状态码兜底。
   - 幂等：应答丢失后同 `X-Request-Id`、同正文重发；202 后同 id 再问。注意 JDK `HttpURLConnection` 对断开的 POST 自己还会补发一次，所以线上最多出现 4 份同 id 请求（中继按 id 幂等，无害），测试断言 2 至 4 份且 id 只有一个。
2. 契约夹具：通过〔实测〕。`synthetic-settings.observe.json` 经 `RelayTreeMapper` 与 `ScreenFormat`，文字里有「蓝牙」，该元素 `clickable`，开关读出「开」，灰掉的行读成「不可用」，密码框读成「输入框(密码，不可代填)」，列表读成「可上下滑」；`probe-app-home.observe.json`（真实容器抓取）也能映射。P2-a 的 `01-settings-home.json` 等真机原始 dump 还没有对应的 `expected/*.observe.json`，设置页真机样例的对拍 NOT_RUN，等 P2-a 产出后补一行。
3. 四个指定测试类全绿，哈希未变：通过〔实测〕。没有碰 `PhoneToolCatalog`、`PhoneCore`。
4. a11y 手下行为不变：通过〔读码〕加〔实测〕。`PhoneHand.resolve` 在非容器 SELinux 域上取 `a11y`（`PhoneHandTest`）；既有测试没改，909 个里只有已知的那一个失败。
5. `SYSTEM_PACKAGES` 新增包有单测：通过〔实测〕（`HarmonySystemPackagesTest`：六个包都被 `SafetyGate` 按 `system-page` 拒绝；`ProtectedPages` 的三个新包与鸿蒙设置的标题规则）。

另有驱动用例〔实测〕：点按用重读后元素的中心（蓝牙行 → 660,360）；元素自快照后移动则不点；屏幕号过期不点；权限管理页只读不点，解除限制后放行；密码框不填、没有任何文字发往手机；输入先 `replaceText` 再回读，回读不一致不提交；含换行或超 500 字的文本在本端拒绝；四个全局键映射；向下滚动手指向上；`tap_xy` 按截图比例换算；中继错误变成给模型的句子；中继暂停或锁屏时不碰设备；`stop` 转给 Mac。

## 4. 给 Dart（P2-c）的 method channel 契约

通道仍是 `dsh/phone`。凡 `ok:false` 都带 `reason`（给人看的中文或英文，已按 App 语言本地化）与可选 `code`。令牌不出原生层。

| 方法 | 参数 | 返回 |
|---|---|---|
| `setHand` | `{hand: "auto"|"a11y"|"mac"}` | `bool`；随后 `status` 事件带新手 |
| `relayPair` | `{addr: "ip:port", code: String}` | `{ok:true}` 或 `{ok:false, code, reason}`；`code` 取 `bad_address`（地址格式不对）、`unauthorized`（配对码错或过期）、`pin_mismatch`（中继证明不对）、`relay_unreachable`。阻塞约 1 至 3 秒，页面要显示进行中 |
| `relayTest` | 无 | `{ok, state, reason, model, via, foreground}`；`state` 见下；`foreground` 是前台 bundle（读一次屏） |
| `relaySetTarget` | `{addr: "ip:port"}`（无线调试地址） | `{ok:true}` 或 `{ok:false, code?, reason}` |
| `relayHalt` | 无 | `{ok:true}` 或 `{ok:false, code, reason}`；只能停，恢复要在 Mac 上 `dsh-relay resume` |
| `relayForget` | 无 | `bool`；只删本机配对，页面要提示 Mac 端另需 `dsh-relay revoke` |

`status` 方法与事件新增三个键：

```
"hand": "mac",                 // 实际生效的手：a11y | mac
"handSetting": "auto",         // 用户的选择：auto | a11y | mac
"relay": {"configured": true, "addr": "192.168.31.182:8731",
          "state": "ok|unreachable|unauthorized|halted|device_offline|device_unauthorized|locked|unconfigured",
          "device": {"model": "VDE-AL00", "via": "usb|tcp", "foreground": "com.huawei.hmos.settings"},
          "lastOkAt": 1793580000000}
```

`device.foreground` 来自最近一次读屏的缓存，可能为空；`lastOkAt` 只在 `status` 被求值时更新。`relay.state` 在没有任务时每 30 秒才问一次 Mac，任务进行中每 5 秒一次；页面要新鲜数据用 `relayTest`。`status` 里原有的 `a11y`、`overlay` 键在 mac 手下仍在，但无意义，页面按 `hand` 隐藏。通知点开的链接是 `dshgo://open/phone-relay`，需 P2-c 注册。

## 5. 待拍板

1. 卓易通机型判据：`PhoneHand` 的 `auto` 用 `EngineFamily.confinedDomain(EngineFamily.readSeLinuxDomain())`，即只看 SELinux 域，没有用 `EngineFamily.decide` 的 family 结果（它要 `RuntimeController` 里的偏好与探针状态）。希望改成「引擎 family 为 bionic 即 mac」时，需要 `RuntimeController` 暴露一个布尔，我再换进 `PhoneControl.hand()`，一处改动。现状下用户在开发者选项里强制 bionic 的普通安卓机不会自动变成 mac，可在设置里手动选。
2. `com.ohos.sceneboard` 按方案列入 `SYSTEM_PACKAGES`。它同时是鸿蒙的桌面，因此「回到桌面后点图标」会被当系统页拒绝，只能走 `open` 工具拉应用。若要放行桌面，需要把 sceneboard 拆成「状态栏/通知/锁屏」与「桌面」两类，这是放宽硬规则，由你定。

## 6. 未验证与风险

- 真机端到端 NOT_RUN（本任务没有真机）：Mac 中继到手机的整条链、`aa start` 拉起 DSH Go、容器内 App 后台时 HTTPS 是否被限制，归 P2-d1 与 P2-d2。
- DSH Go 在宿主侧的 bundle 与 ability 名，`reopen()` 里按「包名 = bundle、`MainActivity` 类全名 = ability」〔推断〕，P2-d1 要核对。
- `isLocked()` 是容器自己的判断加中继锁屏的「或」。若容器的 `PowerManager.isInteractive` 在手机亮屏时也报 false，所有调用都会被当成锁屏拒绝，R13 与 R3 会暴露，改法是 mac 手下只信中继。
- Kotlin 的 `Json` 解析器嵌套上限 64 级，一层节点占 2 级，所以控件树超过约 30 层时整次 observe 会得到 `bad_response`（用例〔实测〕）。已请 P2-a 把中继响应限制在 28 层以内并置 `truncated`。
- 中继 `windows` 数组的顺序：我按「非主窗口顶层在前」处理，`main` 挪到最后；`device.state` 的在线取值我认 `ok/online/connected/ready`（P2-a 的 `device.mjs` 实际取值 `ok/offline/connecting/unauthorized`，一致）。
- 滚动方向：中继只给 `scroll` 标志，不给方向，列表一律按纵向列表读；横向列表靠子节点横排启发式识别，设置页真机未验。
- 第二份拷贝：`MacRelayDriver` 的快照记账（`publish/resolve/after`）与 `RelayShot` 的画图各是第二份实现，第三次出现时再抽。
- 已与 P2-a 对齐的线上格式（hex 编码、`pair|`/`srv|` 消息、配对码规范化、窗口顺序、层数上限）已发消息；P2-a 的 `lib/auth.mjs` 与本端读码一致〔读码〕，Node 对 Kotlin 的跨进程对拍用的是同一组向量，端到端 NOT_RUN。

## 7. 修订

按主线拍板：（1）自动切 mac 手只认卓易通判据（SELinux 域），强制 bionic 的普通安卓机维持手动选，代码不变；（2）`com.ohos.sceneboard` 从 `SYSTEM_PACKAGES` 移出，「回桌面点图标」可走。`SYSTEM_PACKAGES` 现在新增五个鸿蒙包：设置、权限管理、隐私中心、安全卫士、安全访问围栏。

改动只有 `SafetyGate.kt` 里那一行名单和 `HarmonySystemPackagesTest`（去掉 sceneboard，新增 `isSystemPackage("com.ohos.sceneboard")` 为 false 的断言）。第 1 节、第 3 节第 5 条、第 5 节第 2 条中提到 sceneboard 或六个包的地方以本节为准。

〔实测〕`HarmonySystemPackagesTest`、`SafetyGateTest`、`SafetyGateEnglishTest`、`PhoneToolSchemaTest`、`PhoneCorePromptOwnerTest` 与 relay 包全部测试：`BUILD SUCCESSFUL`，无失败。

## 8. 修订 2：对真机夹具 01/02 对拍

P2-a 按 `contract/NOTES.md` 第 5 节修订 observe 输出后，`contract/expected/` 里有了 `01-settings-home`、`02-settings-bluetooth`、`03-wifi-password-dialog`、`03a-wifi-list`、`04-container-probe-home`、`05-lockscreen` 与 `synthetic-settings` 的 `.observe.json`。`RelayTreeMapper` 的源码不用改：NOTES 第 5 节的十条里，可点以 `click` 标志为准、Row/Toggle 去重、`SearchField` 为 `EditText`、sceneboard 与输入法为 `system`、opacity 只管叶子，都是 Node 侧转换的规则，已体现在 `expected` 输出里；Kotlin 侧读到的就是 `flags`、`cls`、`text`，标签取自身 `text`，子树文字由 `ScreenFormat` 现有的收集逻辑处理。

只改了 `RelayTreeMapperTest`：

- 判定标准 2 换成对真夹具：`01-settings-home` 里「星闪和蓝牙」是可点元素；`02-settings-bluetooth` 里「蓝牙」是可点元素、`checkable`，输出行读作「勾选框 蓝牙 · ✓」，状态可读，且同一个开关只出现一次。
- 新增：`03-wifi-password-dialog` 的 `TextInput` 带 `password` 标志，读成「输入框(密码，不可代填)」，输入法窗口不进内容；`05-lockscreen` 没有应用窗口。
- 原来引用 `probe-app-home.observe.json` 的用例改为 `04-container-probe-home.observe.json`；`synthetic-settings` 对开关的断言放宽为「开关 … · 开」或「勾选框 … · ✓」，因为 P2-a 修订后合成样例里的蓝牙行也成了可勾选的 Row。

〔实测〕`./gradlew :app:testX64DebugUnitTest --tests '*phone.relay.*' --tests '*ScreenFormat*' --tests '*ScreenObservation*' --tests '*ScreenDigest*' --tests '*ScreenVerdict*' --offline`：`BUILD SUCCESSFUL`，`RelayTreeMapperTest` 14 个、`ScreenFormatTest` 4 个，0 失败 0 跳过。

遗留一点：Toggle 被去重后，蓝牙行读作「勾选框 … · ✓」而不是「开关 … · 开」，因为 Row 没有开关角色。要读成开关，Node 侧给这类去重后的 Row 加 `role:"switch"` 即可，`RelayTreeMapper` 已经把 `role` 交给 `ScreenFormat`，无需改 Kotlin。是否改由 P2-a 决定。

## 9. 修订 3：对 P4 对抗审查的必修项

编号对应 `P4-对抗审查.md`。〔实测〕全量 `./gradlew :app:testX64DebugUnitTest --offline --continue`：928 个测试，仅已知的 `L2sMigrationTest` 一个失败。改动都已落在工作树。

| P4 | 改动 | 测试 |
|---|---|---|
| H1 令牌对引擎可读 | `RelayTokenStore` 改为 `relay.enc`：AES-256-GCM，密钥在 Android Keystore（`KeystoreRelayCipher`，别名 `dsh-relay-token`，不可导出，`setUserAuthenticationRequired(false)`），文件 0600、原子替换；明文只在本进程内存（读一次后缓存）。旧的明文 `relay.json` 首次读取时加密保存并覆写后删除。加解密抽成 `RelayCipher` 接口，`PhoneControl` 的构造调用不用改（默认就是 Keystore 实现） | `RelayTokenStoreTest` 6 个：磁盘上读不到令牌与地址的明文；新实例同密钥可读回、换密钥读不出；文件 0600；旧明文迁移后删除且不再含明文；解密失败按「未配对」处理不崩溃；`clear` 删两个文件；缓存命中后不再解密 |
| H2 锁屏与系统界面 | `SYSTEM_PACKAGES` 加 `com.ohos.systemui`；`checkWrite` 的系统页检查移到 `SCROLL` 放行之前，`MacRelayDriver` 的两条滚动分支也先看受保护页；`PhoneControl.isLocked()` 在 mac 手下只信中继缓存的 `device.locked`，容器自己的 keyguard 与 `isInteractive` 只写进 `status.relay.containerLocked` 作诊断；`locked=true` 时 `MacRelayDriver.availability()` 本来就拒绝一切读写，`PhoneCore` 的 `isLocked` 也拒绝所有写动作，sceneboard 仍不在名单里 | `RelayMiscTest`：systemui 是系统包；系统页上滚动被拒、解除限制后放行；`MacRelayDriverTest`：权限管理页滚动两条路径都被拒、解除后放行；既有的锁屏/暂停用例仍过 |
| H3 密码框 | `RelayTreeMapper` 新增 `secureKeyboard`：observe 响应带 `secureKeyboard:true`，或输入法窗口里有文字「安全键盘」。`MacRelayDriver.target()` 在安全键盘亮着时把可编辑目标报成 `isSecretField`，`SafetyGate` 因此按 `secret-field` 拒绝，交还卡片用原有的「密码需要你来输入」文案；`type` 在驱动里另有第二道拒绝。标签在相邻节点的输入框：同一父节点下有短（≤10 字）、不可点的「密码/验证码」类文字时，兄弟可编辑节点置 `password` | `RelayTreeMapperTest`：真夹具 03 识别出安全键盘、02 没有、响应字段生效、别的窗口里的四个字不算；邻居标签「登录密码」使输入框成密码框；邻居是可点的「忘记密码」或无关标签时不变。`MacRelayDriverTest`：安全键盘亮着时目标是 secret、`type` 被拒且没有任何输入发出；平时不是 secret |
| M3 支付与银行 | `DEFAULT_DENY` 加 `com.huawei.hmos.wallet`、`com.alipay.mobile.client`；`DEFAULT_CONFIRM_EVERY_WRITE` 加 `com.tencent.wechat` | `RelayMiscTest` |

与要求不同或需要核对的地方：

1. `KEY` 仍然放行。系统页上 Back/Home 是离开系统页的唯一出路，且 `key notifications` 之后的点击已经被 `com.ohos.systemui` 在名单里拦住；锁屏态由 `isLocked` 对包括 `key` 在内的所有写动作一律拒绝。若要连系统页上的 `key` 也拒绝，改 `SafetyGate.checkWrite` 一处。
2. 为实现 H2 的 `isLocked()`，动了 `PhoneControl.kt` 的 `isLocked()` 与 `relayStatusMap()` 两处（不在「只动」清单里，但 `isLocked` 就在这个文件里）。
3. `com.alipay.mobile.client`、`com.tencent.wechat` 是按记忆写的鸿蒙原生 bundle，NOTES 与 `aliases.json` 里没有这两个名字，待核：真机 `hdc shell bm dump -a`。`com.huawei.hmos.wallet` 来自 P2-a 的别名表，同样没在 `bm dump -a` 输出里核过。别名表里的「钱包」要不要去掉归 P2-a（P4 M3 的另一半）。
4. `com.ohos.systemui` 是否真是下拉通知与控制中心的承载 bundle，P4 也列为未决，需要真机下拉后 dump 核对。
5. 已保存过策略的用户，`PhonePolicies` 存在偏好里的名单不会自动带上新增的默认项；新用户和重置后的用户有。
6. `secureKeyboard` 顶层字段目前 P2-a 的 observe 输出里还没有，Kotlin 靠输入法窗口里的「安全键盘」文字也能识别（真夹具 03 上〔实测〕通过）；两条路径任一成立即可。密码框「已输入内容」时 dump 里是掩码还是明文，仍是 P4 未决项，NOT_RUN。
7. H1 的剩余缝隙：Keystore 密钥绑定的是 App 的 uid，引擎的 Node 调不了 Keystore API，所以读到的是密文；同 uid 的原生代码若自己走 keystore2 的 binder 仍可能解密，这一层没有办法在应用内堵。`phone-adb/` 里的自连 ADB 私钥（`AdbKeyStore`）同样明文落盘，不在本轮范围，未处理。`AGENTS.md`「凭据与隐私」一节需要把「令牌以 Keystore 加密落盘」写进去，该文件不在我的改动范围，请主线更新。
8. Keystore 本体（`KeystoreRelayCipher`）JVM 里跑不了，NOT_RUN，真机验：引擎侧 `cat filesDir/phone-relay/relay.enc` 读到密文，`RelayClient` 仍能请求。
