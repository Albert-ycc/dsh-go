# dsh-relay

Mac 上的手机操控中继：HTTPS 服务，只做设备原语（读屏、注入输入、拉起应用），经官方 hdc 客户端操作鸿蒙手机。接口、错误码、安全分层见 `docs/design/zhuoyitong/P1-架构-手机操控中转.md` 第 4、5、7 节。Node 24，零依赖。

## 目录

```
server.mjs            入口
lib/                  relay（路由与设备操作）、hdc（串行队列）、device（目标选择）、observe（树转换）、
                      acts（动作与转义）、auth（令牌、配对、限速）、tls、net、log
bin/dsh-relay         CLI：status pair revoke halt resume target logs doctor
config/               typemap / keymap / gestures / aliases 默认值，可被 ~/.dsh-relay/ 下同名文件覆盖
launchd/              plist.template（install.sh 渲染，node 与 hdc 路径在安装时填入）
install.sh            渲染并装入 launchd，标签取 `--label`，其次环境变量 `DSH_RELAY_LABEL`，默认 `local.dsh-relay`（与 bin/dsh-relay、doctor 同一个默认）
contract/             接口样例、observe 夹具与期望输出，Kotlin 测试读同一份
testkit/              假 hdc、假 uitest、测试客户端、真机辅助脚本
tests/                node --test
```

## 命令

```bash
node --test tooling/dsh-relay/tests/*.test.mjs     # 全部离线测试（Node 24 的 --test 不展开目录，要写通配）
node tooling/dsh-relay/testkit/gen-expected.mjs     # 夹具换了以后重生成 contract/expected
```

环境变量：`HDC_BIN`（默认 `hdc`）、`DSH_RELAY_HOME`（默认 `~/.dsh-relay`）、`DSH_RELAY_PORT`（默认 8731，管理口为端口 +1，仅回环明文）、`DSH_RELAY_LOG_DIR`（默认 `~/Library/Logs/dsh-relay`）。临时实例用 38731 与独立的 HOME 和日志目录，不碰真实实例。

## launchd 验收清单（由用户在自己的 Mac 上执行）

装入前先在前台确认服务能起：

1. `cd ~/workspace/dsh-go/tooling/dsh-relay && HDC_BIN=$HOME/.local/bin/hdc node server.mjs`，看到 `dsh-relay listening: https://127.0.0.1:8731 https://192.168.x.x:8731 http://127.0.0.1:8732`。另开终端 `curl -sk --tls-max 1.2 https://127.0.0.1:8731/v1/ping` 返回 `{"service":"dsh-relay","v":1}`，Ctrl-C 退出。
2. 前台起服务时留意 macOS 是否弹出「想要查找并连接到本地网络上的设备」。有则记下弹窗的申请方（终端或 node）并点允许。

装入与验收：

3. `./install.sh --label com.albert.dsh-relay`（会写 `~/Library/LaunchAgents/com.albert.dsh-relay.plist` 并 `launchctl bootstrap`；node 取 `command -v node`，hdc 取 `HDC_BIN`，默认 `~/.local/bin/hdc`）。不带 `--label` 时标签是 `local.dsh-relay`；换标签就同时设 `DSH_RELAY_LABEL`，`doctor` 会检查 `DSH_RELAY_LABEL`、`local.dsh-relay`、`com.albert.dsh-relay` 三个。
4. `launchctl list com.albert.dsh-relay` 有 PID；`tail ~/Library/Logs/dsh-relay/launchd.log` 无报错；`bin/dsh-relay doctor` 全部 ok（launchd 一项变绿）。
5. 方向一，Mac 的 hdc 经 launchd 进程连手机：手机开着无线调试，`bin/dsh-relay target set <手机IP>:<端口>`，拔掉 USB，配对后调用 `/v1/observe`（或 `dsh-relay status` 看 `device.state` 为 `ok`、`via` 为 `tcp`）。若返回 `device_offline` 而终端里手敲 `hdc -t <ip:port> shell echo ok` 正常，说明 launchd 下的 node/hdc 没有本地网络权限。
6. 方向二，手机连 Mac：在手机上的 DSH Go 里配对并「测试连接」，或手机浏览器打开 `https://<Mac局域网IP>:8731/v1/ping`（证书告警点继续）。Mac 日志 `~/Library/Logs/dsh-relay/relay.log` 出现来源为手机 IP 的请求行即通。
7. 重启验证：`launchctl bootout gui/$(id -u)/com.albert.dsh-relay` 后 5 秒内手机侧得到「连不上 Mac 中继」，`launchctl bootstrap ...` 后下一次调用成功（对应验收 R8）。
8. 任一方向失败：系统设置 → 隐私与安全性 → 本地网络，勾选对应的 node / hdc；仍失败则回退方案是把服务改成登录项里的 Terminal 外壳启动，并把现象记入回执。

## 接口语义补充（手机端依赖）

- `POST /v1/act` 的 `replaceText`：点击、全选、删除、`text` 四步，**默认不发回车**；请求体带 `commit: true`（布尔）才在最后追加一次回车（拼音输入法组词条需要回车才进框的场景，由调用方回读不一致后再用）。样例见 `contract/samples/act-replaceText*.json` 与 `act-replaceText-commit*.json`。
- `GET /v1/device`：`device.state` 不是 `ok` 且未暂停时，服务在后台尝试连一次设备（不阻塞响应、不占队列深度、10 秒最多一次）；服务启动时若有绑定目标也会连一次。`device.locked` 只在最近 15 秒内读过屏时给 `true/false`，否则是 `null`。
- `POST /v1/observe` 的 `display`：按维度取已见过的最大值，不随键盘、半屏窗口跳动；带 `image` 时以截图尺寸为准并更新缓存。限定 `bundle` 的读屏不参与合并。
- 请求头：`X-Request-Id` 为 8–64 位 `[A-Za-z0-9._-]`，同一个 id 60 秒内只能重放同一个请求（方法、路径、body 一致），否则 422；`X-Dsh-Task` 为 1–64 位 `[A-Za-z0-9._:-]`。
- body 超过 64 KB 返回 422 `bad_args`（`body too large`）：8 MB 以内读完丢弃再回，声明长度超过 8 MB 的立即回。
- hdc 单条命令回显累计超过 8 MB 即终止并返回 424 `command_failed`；`dumpLayout` 文件超过 16 MB 或不是合法布局 JSON 同样 424。

## 启动失败的退出码

配置文件（`config.json`、`typemap.json`、`keymap.json`、`gestures.json`、`aliases.json`，先看 `DSH_RELAY_HOME`、再看仓库 `config/`）损坏或形状不对、证书损坏、状态目录不可写、端口值非法：打印一行中文原因，退出码 78，不打栈。HTTPS 端口一个都绑不上：退出码 1。`tokens.json` 损坏不阻止启动：备份成 `tokens.json.bad`（已存在则加时间戳后缀），日志记 `warn`，所有手机需要重新配对。证书被重新生成时日志有 `cert_regenerated` 行（指纹变了，手机要重新配对）。
