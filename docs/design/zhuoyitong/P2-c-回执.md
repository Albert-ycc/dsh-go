# P2-c 回执：Dart 页面、文案、指南

日期：2026年11月02日。基线：dsh-go `main` 8fe1c1c 加工作树里 P2-3b、P2-4、P2-a、P2-b 已交付的改动。证据标记：〔实测〕本次执行并看到输出；〔读码〕读了源文件；〔推断〕未执行；NOT_RUN 没跑。开工前确认了 `~/dev/apk-p3.build.done` 已存在，之后才写文件。

## 1. 改动清单

新增：

| 文件 | 内容 |
|---|---|
| `app/lib/ui/phone/phone_relay_page.dart` | Mac 中继页：状态提示（八种各一条标题加说明加下一步）、配对表单、当前设备（Mac 地址、机型、USB 或无线调试、前台应用、最近一次成功）、管理（测试连接、更新无线调试地址、停止中继、重新配对、忘记这台 Mac）；`phoneRelayErrorText` 把错误码换成句子 |
| `app/lib/l10n/parts/phone_relay_{zh,en}.arb` | 前缀 `phoneRelay`（含 `phoneRelayError<Code>`）、`searchKwPhoneRelay`、`pluginsInstallGitRefusedBionic{,Hint}`，两侧键与占位逐个对应 |
| `app/test/phone/phone_relay_test.dart` | 34 例 |
| `app/test/plugins/plugin_git_refusal_test.dart` | 4 例 |

改动：

| 文件 | 改动 |
|---|---|
| `app/lib/phone/phone_channel.dart` | `PhoneHand`、`PhoneRelayState`（八个取值）、`PhoneRelayDevice`、`PhoneRelay`、`PhoneRelayResult`；`PhoneStatus` 增 `hand`、`handSetting`、`relay`（没有这三个键时是 a11y 手、未配对）；通道方法 `setHand`、`relayPair`、`relayTest`、`relaySetTarget`、`relayHalt`、`relayForget`，缺插件或抛错时返回带原因的失败 |
| `app/lib/state/phone_state.dart` | 六个对应方法，成功与否都随后 `refreshStatus` |
| `app/lib/ui/phone/phone_control_screen.dart` | `hand == mac`：准备卡第一步换成「Mac 中继」（已连接显示机型，否则显示状态，按钮「去配对」或「去处理」），隐藏「自动恢复无障碍」行、受限设置行、悬浮条卡、不打扰模式行；概览状态行出中继状态；「读一下当前屏幕」在中继 `ok` 时可点。`进阶` 卡里加「Mac 中继」入口行（两种手都显示，a11y 手下副标题是一句说明）。a11y 手下其余分支一行未动 |
| `app/lib/ui/settings/index/spots.dart`、`settings_pages.dart` | 新页 `SettingsPageId.phoneRelay`，父页手机操控，链接 `dshgo://open/phone-relay`，条件「手机操控已开」；`page_links.dart` 经设置索引自动认这个链接，未改 |
| `app/lib/state/plugins_state.dart` | 「Git 来源插件拒绝」，见第 3 节 |
| `docs/guide/{zh,en}/04-phone-control.md`（及 `app/assets/skills/dsh-go-guide/` 下由 `guide_sync.py` 生成的两份） | 「开启」第 2 步后加一句卓易通分叉；「停止」加 `dsh-relay halt`；新增「鸿蒙卓易通：经 Mac 中转」一节：八步装中继（摘自 `tooling/dsh-relay/README.md`「launchd 验收清单」）、配对、能做什么、八状态表、更新无线调试地址、Mac 离线时、停止与撤销 |
| `app/test/phone/support.dart` | `phoneStatus` 增 `hand`、`relay` 参数；`relayBlock()`；`FakePhoneNative` 处理六个新方法 |

生成物：`app/lib/l10n/app_{zh,en}.arb`、`l10n/gen/*` 由 `l10n_merge.py` 重生成（其中也含 P2-4 已有的改动，没动它们的源）。

## 2. 配对码的处理

〔读码〕配对码输入框 `obscureText`、关联想与自动纠正、关输入法个性化学习、关交互选择（所以不能复制粘贴）、只放行字母数字与连字符空格。点配对后，调用发出之前先清空输入框，成功失败都一样，页面上没有任何位置回显它。Dart 没有 `print`/日志。令牌不出原生层，页面从未见过。〔实测〕测试覆盖：字段属性、成功后调用参数里有码而界面无码且输入框空、四种失败码后输入框空且界面无码。故意把 `obscureText` 改成 false、注释掉清空，相关 5 例变红，改回后全绿。

## 3. Git 来源插件拒绝

落点：`PluginInstallsController.install`（`plugins_state.dart`）。市场安装、分享码导入、插件详情页的安装三条路都经过它，所以只改这一处。条件：目标主机是本机、`RuntimeStatus.isBionic`、`spec` 是 Git 来源（`git+`、`git://`、`git@`、`github:` 等前缀、`owner/repo`、`.git` 的 http 地址，函数 `isGitPluginSpec`）。命中时不发起安装，任务直接置为失败，`failure.title` 为「这台手机的运行环境里没有 git，不能从 Git 来源安装插件」，现有的失败提示（`pluginsInstallFailureNamed`）会把它显示出来；`message` 给出替代做法。〔实测〕`plugin_git_refusal_test.dart`：Git 规格被拒且 `pluginManager/installBundle` 零次调用；bionic 下 npm 规格照常安装；glibc 下 Git 规格不拦。

没有做的：插件详情页「安装」按钮之外的、专门展示 `failure.message` 的界面不属于本任务；`message` 在现有失败详情里是否展开显示未逐页核对〔推断〕。

## 4. 命令与输出

环境：`source tooling/env-mac.sh`；`DSH_TEST_FONT_DIR` 指向用 NotoSansSC 的 ttf 复制成 `NotoSansCJK-{Light,Regular,Bold}.ttc` 的临时目录（与 P2-4 同法）。

| 命令 | 结果 |
|---|---|
| `flutter analyze` | `No issues found!`〔实测〕 |
| `python3 app/tool/l10n_merge.py --check` | `✓ 48 parts, 5583 keys`〔实测〕 |
| `python3 app/tool/guide_sync.py --check` | `guide: 26 files checked`〔实测〕 |
| `flutter test test/phone/phone_relay_test.dart test/plugins/plugin_git_refusal_test.dart test/settings/index` | 全过〔实测〕 |
| `flutter test test/phone test/plugins test/settings test/links test/state test/l10n test/search`，本树与 HEAD 干净 worktree 各跑一次，同字体 | 本树 2437 个、234 败；HEAD 2379 个、233 败。差集只有 1 条：`test/plugins/native/native_capabilities_test.dart` 的「owner the clip ends: listening and the caption stop with it」，单独连跑两次均通过，是两棵树并行时的时序抖动〔实测〕。除此之外，败例名单与 HEAD 逐条一致，均为字体度量不同导致的 golden 与几何测试。新增 38 例全过 |

未跑：全仓 `flutter test`（只跑了与本次相关的七个目录及对照）；真字体环境下的全绿，原因是本机缺 Noto Sans CJK；arm64 真机项、Kotlin 侧联调，不在本任务。截图与真机上看页面 NOT_RUN，页面只经 widget 测试验证。

## 5. 判定标准逐条

1. `flutter analyze`、`l10n_merge --check`、`guide_sync --check` 全绿：通过〔实测〕。`flutter test`：相关目录与 HEAD 相比无新增稳定失败，名单见上〔实测〕。
2. fake channel 下八种中继状态各有 widget 测试：通过，`the page in each state` 八例逐一断言标题、说明、配对表单是否出现、管理项是否出现；另有解析层八个取值与未知值的断言〔实测〕。
3. 配对页测试：字段属性、按钮可用条件、成功、四种失败码、未知码取原生文案〔实测〕。
4. 中英文案键一一对应：`l10n_merge` 校验通过〔实测〕；指南中的粗体全部是该语言的界面标签，`guide_sync` 校验通过〔实测〕。

## 6. 待拍板

1. 卓易通的「进阶」卡里「后台运行」「后台确认与返回」两行仍显示。它们讲的是安卓的省电白名单与后台弹出权限，在鸿蒙容器里是否有意义没有证据，P2-d1 的 S-NTF、S-FGS 出结果后再决定隐藏还是改文案。
2. 概览卡「关闭状态」下的三行能力介绍（`_WhatItDoes`）仍写「读屏需要打开无障碍」，卓易通开关未开时用户会看到这句。改它要按 `hand` 分叉并新增文案，本期未动。
3. 无。
