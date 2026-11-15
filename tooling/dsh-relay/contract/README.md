# contract 目录

- `samples/`：各端点请求与响应样例；`pair-vector.json` 是配对的固定向量，Kotlin 测试按它对拍。
- `fixtures/*.json`：`uitest dumpLayout` 原始输出。`01`–`05`（含 `03a`）是 P2-0 在真机采的原样输出，说明见 NOTES.md，`synthetic-settings` 是手写的设置页（Toggle、TextInput、List、状态栏窗口，覆盖映射表）。真机夹具到位后放进同一目录。
- `expected/*.observe.json`：对应夹具经 `lib/observe.mjs` 转换后的 observe 响应主体（`display/main/windows/nodes/truncated`），`tests/observe.test.mjs` 逐字比对。夹具更换后运行 `node testkit/gen-expected.mjs` 重新生成并人工审 diff。

## 线上约定（Node 与 Kotlin 共同遵守）

- 配对：`code` 去空格与连字符、转大写、O→0、I/L→1；`nonce` 16 字节随机数的小写 hex；`fp` 为叶证书 DER 的 SHA-256 小写 hex；`proof`、`serverProof` 为小写 hex；令牌为 base64url。
- `device.locked` 在锁屏判据成立时为 true（只有 sceneboard 窗口且存在 ScreenLock* 节点），否则 false。
- Node 树里空字符串、空数组、null 的字段一律省略（`text/desc/hint/viewId/role/flags/enabled/children`），读取方按缺省值处理；`enabled` 仅在 false 时出现。
- 嵌套深度不超过 28 层，超过的子树被截断并置 `truncated:true`。
- 在途重放返回 HTTP 202，`{"ok":true,"in_progress":true}`。
- TLS 固定在 1.2（macOS 自带 curl 的 LibreSSL 与 Node 的 1.3 握手不兼容）。
