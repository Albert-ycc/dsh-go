# P2-a Mac 中继回执

日期 2026年11月02日。产物在 `tooling/dsh-relay/`，规格见 `P1-架构-手机操控中转.md` 第 4、5、7、8、9、12 节。标注：〔实测〕本次命令输出，〔读码〕读源码，〔推断〕未执行，NOT_RUN 未做。

## 1. 结论

离线部分完成：29 个测试全绿，observe 转换与夹具逐字一致，命令注入用例通过，launchd 作业已生成未安装，验收清单写在 `tooling/dsh-relay/README.md`。真机部分只做了一半：读屏在手机被其他测试占用时 36% 成功，延迟远高于 4.7 的表；按协调方通知停止了对手机的一切调用，launch、act 经中继的完整一轮、R10 真机转义、动作后截屏等项为 NOT_RUN，等手机空出来再补。真机上发现并已修掉一个会让 launch 对计算器失败的 bug（第 4 节）。

## 2. 命令与输出

```
$ node --test tooling/dsh-relay/tests/*.test.mjs
ℹ tests 29   ℹ pass 29   ℹ fail 0      （连续多轮，单轮约 20 秒）
$ plutil -lint tooling/dsh-relay/launchd/com.albert.dsh-relay.plist
com.albert.dsh-relay.plist: OK
（P5 起仓库里的生成版 plist 已删除，只留 `plist.template`，个人标签在安装时由 `--label` 或 `DSH_RELAY_LABEL` 给；要 lint 就 lint 安装后的 `~/Library/LaunchAgents/<标签>.plist`。）
$ curl -sk --tls-max 1.2 https://127.0.0.1:38731/v1/ping        （临时实例）
{"service":"dsh-relay","v":1}
$ curl -sk --tls-max 1.2 https://192.168.31.182:38731/v1/ping    （经局域网地址）
{"service":"dsh-relay","v":1}
```

`AGENTS.md` 命令块加了一行，写成 `node --test tooling/dsh-relay/tests/*.test.mjs`。派单原文的 `node --test tooling/dsh-relay/tests/`〔实测〕在 Node 24.13.1 报 `Could not find`，目录不会被展开；仓库里 `runtime/tests/*.test.mjs` 也是通配写法。

〔实测〕临时实例启动输出：`dsh-relay listening: https://127.0.0.1:38731 https://192.168.31.182:38731 http://127.0.0.1:38732`。只绑回环和 en1 的私网地址，没有 0.0.0.0，没有 198.18.x。已关闭（`lsof -iTCP:38731` 无监听）。

## 3. 判定标准逐条

1. 通过。七项各有用例（`tests/relay.test.mjs`）：队列串行（四个并发 click 的 start/end 严格交替，第五个并发得 429 busy）、取消杀子进程（stop 后假 hdc 进程 `kill -0` 失败，排队项没到设备，返回 409 cancelled）、超时（504 并杀进程，队列随后可用）、幂等重放（同 id 重放响应相同且设备只执行一次，在途重放得 202，没发到设备的失败不占 id）、限速（5 次 401 后 429，合法令牌同样 429）、来源拒绝（白名单外 403、Host 头不合法 403、缺 `X-Dsh-Relay` 403，另有 CIDR 纯函数表）、halt 不对称（令牌只能停；`/v1/resume`、`/ctl/resume` 经令牌或 HTTPS 一律 404；业务令牌打管理口 401；暂停期间 hdc 调用数不变）。另有配对、通道绑定、日志脱敏、launch、apps、目标选择、十二个错误码、参数校验等用例。
2. 通过。`tests/observe.test.mjs` 对 `contract/fixtures/*.dump.json` 两份夹具转换，输出与 `contract/expected/*.observe.json` 逐字比对。夹具是 `probe-app-home`（真机抓的探针应用首页，原始 58,877 字节，转换后窗口树 3,032 字节）和手写的 `synthetic-settings`（覆盖 Toggle、TextInput、List、密码提示词、隐藏与零面积节点、状态栏窗口）。方案 4.3 写的 Toggle、TextInput、List 在真机 dump 里仍没有实例，映射表仍是〔推断〕；P2-0 的真机夹具到位后放进同一目录，跑 `node testkit/gen-expected.mjs` 并审 diff。
3. 部分。见第 5 节：observe 真机通过但延迟不达标；`act key back` 经中继一次通过（349 ms）；launch 经中继 NOT_RUN（修 bug 前的一次失败见第 4 节）。
4. 部分。见第 6 节；真正的 launchd 验收清单在 `tooling/dsh-relay/README.md`。
5. 通过（假 hdc 层）。`命令注入` 用例让假 hdc 把中继发来的命令交给真实 `/bin/sh -c` 解释，设备端替身 `uitest` 记录收到的 argv：`a'b"c$(id);d\`e`、`x'; touch <canary>; echo '`、`$(touch <canary>)`、反引号、`&& | > <`、中文、纯单引号共 9 个载荷，argv 与原文逐字相同，canary 文件不存在；含 NUL、`\n`、`\t`、空串、501 字符、非字符串的文本在到达设备前 422；日志与响应里只有 `<text:N chars>`。真机 shell 上的同一验证 NOT_RUN（协调方叫停了 hdc 调用）；转义函数 `shq` 是同一份。

## 4. 真机上发现的问题

- launch 失败：方案 4.5 用 `bm dump -n` 的 `mainAbility` 作 `aa start -a` 的值。计算器的 `mainAbility` 是 `com.huawei.hmos.calculator.CalculatorAbility`，但 `abilityInfos[].name` 登记的是短名 `CalculatorAbility`，用全名报 `Error Code:10104001 The specified ability does not exist`〔实测，中继返回 424 command_failed〕；手敲 `aa start -b com.huawei.hmos.calculator -a CalculatorAbility -m phone` 三次均 `start ability successfully.`〔实测〕。设置的 `abilityInfos` 登记全名，`-a com.huawei.hmos.settings.MainAbility -m phone_settings` 成功〔实测，随即发了 `keyEvent Back`，这一步不在派单范围内，是我为验证方案原文的例子做的〕。已改为在 `abilityInfos` 里按名字解析（`lib/relay.mjs` 的 `bundleEntry`），有单测；修后经中继的真机 launch NOT_RUN。
- `uitest dumpLayout` 在手机被别的测试占用时延迟双峰：同一条命令直接手敲，〔实测〕1.4 s、18.4 s、1.2 s，另有两次约 36–38 s 后回显 `Wait for subscribe uitest.broadcast.command.reply timeout`；当时设备上能看到另一任务的 `uitest uiInput inputText` 进程。中继 5 秒超时杀掉 hdc 客户端后，设备端的 `uitest dumpLayout` 仍挂着（〔实测〕`ps` 看到 `l-9.json` 的进程残留），后续读屏排在它后面继续超时，形成连锁。已改为读屏超时或取消时按自己的输出路径 `pkill -f '[/]data/local/tmp/dsh-relay/l-N.json'`（方括号避免杀到执行 pkill 的 shell），有单测，真机 NOT_RUN。
- `hdc -t <usb> shell ...` 在与其他任务同时跑时约一半回 `Connect server failed`〔实测〕，`list targets` 同时正常。中继把这类回显归为 `device_offline` 并让下一次请求重新探测。

## 5. 实测延迟表（真机，临时实例 38731，USB 通道）

条件：手机同时被其他任务占用 uitest，不是安静环境，数字只能说明占用下的表现。

| 项 | 4.7 的表 | 本次〔实测〕 |
|---|---|---|
| observe 不带图 | 约 1.4 s | 25 次连续请求中 9 次成功，成功样本 1.5 / 2.4 / 2.8 / 2.8 / 4.1 / 8.8 / 9.5 s；16 次 504（5 s 预算内未取回）。另有 4.4 s、2.7 s 两次成功（早些时候）。均高于表 |
| 同条件下手敲 `uitest dumpLayout` | 1.23 s | 1.0–1.4 s（无冲突时），冲突时 18–38 s |
| observe 带图 | 约 2.0 s | NOT_RUN |
| act `key back` | 约 0.3 s | 349 ms（单次） |
| launch | 5 s 内 | NOT_RUN（修 bug 前一次 11 s 后失败） |
| `dumpLayout -b <bundle>` 是否更快 | 待测 | NOT_RUN |
| 响应体 | 节点 117 个、79 KB | 本次屏幕 70 个节点，转换后 5.4–10.2 KB |

结论：在手机独占的环境下 observe 应落在表内（手敲 1.0–1.4 s 加取回 0.03–0.13 s），这一点〔推断〕；占用环境下不达标的原因在设备端 uitest 争用，不在中继。动作前截了一张（当时屏幕是另一任务的搜索界面，带指针调试浮层，已按验证截图用完即删清除），动作后的截屏随叫停取消。

〔实测〕首个连接请求偶尔要 8 s：设备端 `echo ok; param get const.product.model` 首次 hdc 调用在 8 s 后才返回（第二、三次 0.08–0.10 s）。中继的连接探针预算是 3 s，因此首次请求常得到 `device_timeout`，重试后成功。

## 6. 本地网络权限与 launchd（判定标准 4）

在前台起的临时实例（终端会话下的 node，绑定 192.168.31.182:38731）上：
- Mac 经局域网地址访问自己的 38731 成功。
- Mac 的 hdc 连手机 `192.168.31.226:46225` 的 `shell echo ok` 成功〔实测，前台进程树〕。
- macOS 是否弹出本地网络权限窗口：未观察到。本次没有屏幕访问，`log show` 里没有包含 `local network` 的记录，只能说「没有证据显示弹出」，不能说没有弹。
- 手机连 Mac 的方向：手机 shell 没有 `nc`、`curl`，`toybox nc` 不存在，命令行无法验证；P0B 9.1 的容器到 Mac TCP 通信结果是旧证据。NOT_RUN。
- launchd 下两个方向：按规矩没有装。清单见 `tooling/dsh-relay/README.md`「launchd 验收清单」八步，含前台预检、装入、两个方向的判据、R8 的 bootout/bootstrap 验证、失败回退。

## 7. 与方案不一致的地方

- TLS 限定为 1.2。macOS 自带 curl（LibreSSL 3.3.6）对 Node 默认 TLS 1.3 握手报 `tlsv1 alert decode error`〔实测〕，R0 用的就是这条 curl；换成 1.2 后通过。Android 客户端不受影响。
- `/v1/ping` 不要求 `X-Dsh-Relay` 头：R0 的 `curl` 不带头，方案 4.1「所有请求必须带」与 R0 冲突，取 R0。其余端点缺头返回 403 `forbidden_source`。
- `/v1/halt` 在已暂停时仍返回 200（幂等），方案 4.2 的「除 ping 与 device 外返回 423」按字面会让重复 halt 得到 423；`/v1/stop` 暂停时返回 423。
- 限速返回 429，错误码用 `busy`（十二个码里只有它是 429），与队列满同码，`hint` 写明封禁 60 秒。
- 观察 4.3 的 `opacity` 为 0 的丢弃规则没有实现：容器内安卓节点在真机 dump 里一律是 `opacity:"0.000000"`，按字面丢会清空整个应用窗口〔实测〕。
- 额外裁剪：无文字、无描述、无提示、无标志且无子节点的叶子被丢弃（空装饰节点），同样让体积从 58,877 字节降到 3,032 字节；`viewId` 不参与这个判断。
- 节点树深度限制 28 层（P2-b 的 Kotlin 解析器嵌套上限 64，每层占 2 级），超出置 `truncated:true`。Node 字段里的空值全部省略，读取方按缺省值处理。
- 配对的编码按 P2-b 的约定：`nonce`、`proof`、`serverProof` 为小写 hex，`seenFp` 为小写 hex 的 SHA-256；固定向量在 `contract/samples/pair-vector.json`，`tests/vector.test.mjs` 验证。
- 观察取回用 `hdc file recv` 落到 `~/.dsh-relay/tmp`（0700）再立即读入内存并删除，方案 4.9 的「Mac 端不写文件」做不到；测试断言这个目录取完为空。
- 空闲期每 30 秒 `list targets` 刷新状态没有实现，状态在每次请求时刷新；活跃期 5 秒心跳已实现但未在真机长跑验证。
- 键码表〔推断〕：`keymap.json` 的回车、删除、全选键码、`recents`、`notifications` 手势在真机上未验证（P2-0 负责）。`inputText` 追加还是替换未测，沿用 `replaceText` 宏。

## 8. 交付清单

- `tooling/dsh-relay/`：`server.mjs`、`lib/`（relay、hdc、device、observe、acts、auth、tls、net、log、errors）、`bin/dsh-relay`、`config/`（typemap、keymap、gestures、aliases）、`launchd/`（`plist.template`；生成版 `com.albert.dsh-relay.plist` 已在 P5 删除）、`install.sh`、`contract/`、`testkit/`、`tests/`、`README.md`。
- `AGENTS.md`：命令块加一行。
- 十一个对外端点（ping、pair、device、observe、act、launch、apps、target、stop、halt，加 `/ctl/*`）和十二个错误码均已实现。管理口额外有 `/ctl/status`、`/ctl/halt`。
- `open /Users/albert/workspace/dsh-go/docs/design/zhuoyitong/P2-a-回执.md`

## 9. 待拍板

1. 读屏在手机被占用时的失败率：设备端 uitest 同一时刻只能服务一个调用方，P2-0/P2-d1/P2-5 与中继并行就会互相拖到 18–38 s。真机验收（P2-d2）需要手机独占，是否这样安排。
2. 手机空出来后我需要补跑的真机项：observe 带图与不带图各 10 次延迟、`dumpLayout -b` 对比、launch 计算器后 Back、动作前后截屏、R10 的真机转义检查。请通知我时间窗口。
3. 无。

## 10. 修订（按 P2-0 夹具与 `contract/NOTES.md` 第 3–5 节）

`node --test tooling/dsh-relay/tests/*.test.mjs`：32 个测试全绿，连续 3 轮稳定。上文第 3 节判定标准 2 的夹具现在是 P2-0 的六份真机夹具（`01`、`02`、`03`、`03a`、`04`、`05`）加手写的 `synthetic-settings`；原先的 `probe-app-home` 与 `04` 重复，已删。每份夹具各有 `contract/expected/<名>.observe.json`，`tests/observe.test.mjs` 逐字比对。手机一次没碰。

observe 转换（`lib/observe.mjs`、`config/typemap.json`）：
- `clickable=true` 的节点一律是可点元素；`text` 取自身，空则取子树第一个非空文字，类名映射不到时用 `android.view.View`。标签取自子树时，子树里那段重复文字节点被摘掉，Row 自带文字与子 `Text` 重复时同样处理。
- Toggle 摘掉，`check`/`checked` 并到可点的父 Row；Row 是可点元素，Toggle 是状态来源。`checked` 按字符串 `"true"`/`"false"` 读。
- `TextInput`、`SearchField` 映射为 `EditText`；`Search` 外层不再按可点处理。`scroll` 只给 `List/Grid/Scroll/WaterFlow` 和 `android.*` 节点，`TextInput`、`SearchField`、`ScrollBar` 不置。
- 丢弃名单补齐：`Divider Mask MaskBlur Circle Canvas EffectComponent DepthComponent LoadingProgress metaballNode TextClock ScrollBar`；`SymbolGlyph/Image` 无文字、无描述、不可点时仍按空叶子丢弃。`sceneboard` 与 `inputmethod` 窗口按 system。
- `opacity` 只对鸿蒙节点的叶子生效，`android.*` 节点整体豁免（NOTES 写的是窗口根，但容器内安卓叶子 `TextView` 同样是 `0.000000`，只豁免根会把页面文字清掉；夹具 04 有对应断言）。
- `focused` 只留给可点或可编辑的节点，焦点链上的祖先容器不再带，否则它们无法合并，体积增大。
- 锁屏判据入响应：只有 `com.ohos.sceneboard` 窗口且存在 `id` 以 `ScreenLock` 开头的节点时 `device.locked` 为 true（夹具 05 命中，01 为 false），其余为 false，不再是 null。`screenOn` 仍为 null。
- 体积：01 转换后 11.8 KB（原始 215 节点），03 密码面板 28 KB，05 锁屏 1 KB。
- 夹具 03 的密码框 `TextInput` 带 `editable`、`password`（靠 hint「密码」）；输入法「安全键盘」判据没有进响应，要用的话再加一个字段。

动作与配置：
- `replaceText` 宏现为 点击、全选（`2072 2017`）、删除（`2055`）、`text`、回车（`2054`）五步，`contract/samples/act-replaceText.response.json` 已同步。回车在非搜索框的表单里是否触发提交或换行，夹具说明只在搜索框里试过，NOT_RUN。
- `keymap.json` 的 `ENTER 2054`、`DEL 2055`、`CTRL_LEFT 2072`、`A 2017` 与 NOTES 一致，无需改。`inputText` 是追加，中继不使用。
- `launch` 允许调用方直接给 `bundle` + `ability`（不带 `module` 时命令不带 `-m`，不查 `bm dump -n`），`probe.zyt/probe.zyt.MainActivity` 的命令形态有单测。

NOTES 第 5 节十条里没有落代码的：第 9 条 `visible` 恒为 true，规则保留；第 7 条容器应用与鸿蒙包装层的区分按 `type` 是否以 `android.` 开头，已用于 `opacity`、`scroll`、类名处理。

真机补测项（observe 带图/不带图延迟、`dumpLayout -b` 对比、launch 经中继、R10 真机转义、动作前后截屏）继续 NOT_RUN，等通知。

追加：子树含 Toggle 且被保留为可点元素的 Row 现在带 `role:"switch"`（`checked` 照旧），`contract/expected/` 已重生成（02、03a 各有，01 无开关），`tests/observe.test.mjs` 断言手写夹具与夹具 02 的「蓝牙」Row 为 `role:"switch"`。

## 11. 修订 3（对应 P4 编号）

`node --test tooling/dsh-relay/tests/*.test.mjs`：41 个测试全绿。手机没碰。

- **B1 脱敏**：新增 `tooling/dsh-relay/contract/scrub.py`（幂等，`--check` 可做门禁）。夹具原地重写：设备名→「测试机」，账号名→「用户」，SSID→`WiFi-NN`，BSSID→`00:11:22:33:44:NN`，蓝牙名→`BT-NN`，设备 IPv6 链路本地地址→`fe80::1`，序列号→`<USB序列号>`。SSID 与蓝牙名从夹具结构识别（Wi-Fi 列表行、「已配对设备」之后的文字节点），不写死在脚本里；`id`、`key` 属性里也带 SSID 与 BSSID，所有字符串属性一并处理。`expected/` 已重新生成，`NOTES.md` 同步。`tooling/dsh-relay/.gitignore` 加 `contract/fixtures/shots/`，`tooling/zhuoyitong-probe/.gitignore` 加 `results/`。`tooling/dsh-relay` 下搜序列号、账号名、SSID、蓝牙名与 BSSID 的原串结果为零，局域网 IP 保留。**未清零的部分**：`tooling/zhuoyitong-probe/scripts/smoke.sh`、`ui-probe.sh`、`p3-engine.sh` 里还写着 USB 序列号，不在我的改动范围，需要它们的负责人改成读环境变量或占位。
- **H2 锁屏**：`device.locked` 已进 `/v1/observe` 响应（修订段起就有，P4 读到 null 是改前的状态）；本次让 `/v1/device` 也返回最近一次读屏得到的值，未读过时为 null。`com.ohos.systemui` 已在 system 窗口名单里（与 `sceneboard`、`inputmethod` 同列，待真机核实）。测试：锁屏夹具 05 → true，设置页夹具 01 → false。
- **H3 安全键盘**：observe 响应新增 `secureKeyboard`（布尔，常有）；输入法窗口里出现「安全键盘」文字时为 true，且把带焦点的可编辑节点置 `password`，hint 词判据保留。测试：夹具 03 为 true，夹具 02 为 false，去掉 hint 与 text 的输入框仍被判为密码框。「输入后掩码还是明文」仍无样本。
- **M3 别名与名单**：`aliases.json` 删掉「钱包」，测试断言别名里没有 wallet、pay、bank 类 bundle。`DEFAULT_DENY` 是 Kotlin 常量，不在我的范围；建议补的 bundle 与待核名单写在 `contract/NOTES.md` 第 7 节（`com.huawei.hmos.wallet`、`walletservice`、`quickpay`、`com.alipay.mobile.client`；微信、银行、云闪付等的 bundle 没有在已读名单里出现，待 `bm dump -a` 全量核对）。
- **M4**：`launch.uri` 删除，带 `uri` 返回 422，不再发 `aa start -U`，也不写日志。
- **M1**：`replaceText` 点击后先等 300 ms 读一次布局，要求点击点落在带焦点的可编辑节点内，否则返回 424 `command_failed`（「nothing was cleared or typed」）且不发全选与删除。代价是每次 `type` 多一次读屏（真机约 1.4 s），作业预算相应加 3 秒。测试断言失败时设备只收到那一次点击。
- **M7**：设备层失败只认回显开头的 `[Fail]`、`[E00xxxx]`、`Connect server failed`，不再子串匹配 `offline`。测试：应用输出含 `offlinemaps offline` 时 launch 成功且设备状态仍为 ok；开头为 `Connect server failed` 或 `[Fail]` 才判 `device_offline`。
- **M2 绑定设备**：新增 `~/.dsh-relay/binding.json`（序列号与无线地址）。首次使用只有唯一 USB 设备时自动绑定它，多台 USB 或多台无线设备时报 `device_offline` 并提示显式指定，不替用户猜。绑定后只连绑定的序列号或地址，别的设备在线也不回退，测试断言没有任何命令发到另一台。`POST /v1/target` 与 CLI `target set` 更新无线地址，`target auto` 清除绑定。副作用：只用无线地址绑定而没有绑 USB 序列号时，插上另一部 USB 手机不会被使用；要用 USB 需先让它成为首次绑定对象或在 `config.json` 写 `device.serial`。

没做：P4 的 M5、L1–L8、H1（Kotlin 侧）不在本批。
