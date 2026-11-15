# contract 夹具说明（P2-0）

采集日期 2026年11月02日，目标机 Pura X（VDE-AL00），官方 hdc 3.1.0e（USB `<USB序列号>`）。证据标记：〔实测〕本次执行并看到输出；〔推断〕由实测推出、未直接执行；NOT_RUN 未做。

## 1. 夹具清单

每份 `fixtures/<名>.json` 是 `uitest dumpLayout -p` 的原样输出（未改一个字节），`fixtures/shots/<名>.jpeg` 是同一动作里紧邻的 `snapshot_display` 截屏。

| 夹具 | 截图 | 页面 | 说明 |
|---|---|---|---|
| `01-settings-home.json` | `shots/01-settings-home.jpeg` | 鸿蒙设置主页，滚动在最顶（账号卡、WLAN、星闪和蓝牙…） | 前台窗口 `com.huawei.hmos.settings/com.huawei.hmos.settings.MainAbility`，`pagePath=pages/home/SettingsHome`，215 节点 |
| `02-settings-bluetooth.json` | `shots/02-settings-bluetooth.jpeg` | 设置 → 星闪和蓝牙 | 含两个 `Toggle`（星闪、蓝牙），两个都是开启状态，没有改任何开关 |
| `03a-wifi-list.json` | `shots/03a-wifi-list.jpeg` | 设置 → WLAN 列表 | 已连接网络、已保存网络、新网络混排；一个 `Toggle`（WLAN 总开关） |
| `03-wifi-password-dialog.json` | `shots/03-wifi-password-dialog.jpeg` | 点未保存的加密网络「WiFi-03」弹出的密码面板 | 全程没有输入、没有连接；采完按 Back 关闭。截图是纯黑，见 2.1 |
| `04-container-probe-home.json` | `shots/04-container-probe-home.jpeg` | 容器内安卓应用：探针 App `probe.zyt` 首页 | 窗口 `probe.zyt/probe.zyt.MainActivity`，节点类型是安卓类名（`android.widget.TextView/Button/FrameLayout/LinearLayout/ViewGroup/View`） |
| `05-lockscreen.json` | `shots/05-lockscreen.jpeg` | 锁屏（手机自行熄屏锁定后采到，截屏纯黑） | 见 2.3 |

`fixtures/` 里另有 `probe-app-home.dump.json`、`synthetic-settings.dump.json` 两份，不是本次采集的，来自 P2-a 的离线开发，对拍时按文件名区分。

截屏里叠着红色布局边界框、`P: 1 / 1 dX dY Xv Yv Prs` 指针位置条和右上角的 `60` 帧率数字，是手机上已开启的 ArkUI 调试显示，不是本次动作造成的，也没有被改动。它只影响截屏，不影响 dump。

## 2. 三个未知量（方案第 9 节）

### 2.1 密码框在 dump 里怎么表现

〔实测〕`03-wifi-password-dialog.json`：

```
{"type":"TextInput","hint":"密码","text":"","originalText":"","description":"","id":"",
 "bounds":"[48,405][1272,525]","clickable":"true","focused":"true","scrollable":"true", ...}
```

- 没有任何 password / secure / inputType 字段。节点属性全集（215 节点那份的并集）只有 `abilityName accessibilityId backgroundColor backgroundImage blur bounds bundleName checkable checked clickable clip description displayId enabled focused hashcode hierarchy hint hitTestBehavior hostWindowId id key longClickable opacity origBounds originalText pagePath scrollable selected text type visible zIndex`。
- 掩码字符：NOT_RUN。派单规定不输入，没有输入就没有掩码文本可看。
- 能用的信号有三个，都是〔实测〕：
  1. `hint` 为「密码」，`type` 为 `TextInput`；中继的「hint/desc/text 命中密码类词」规则对这一例成立。
  2. 面板外层 `Navigation` 的 `id` 是 `Setting.SheetPage.wifi_password_config`，同窗口有 `Column` `id=PasswordHeadViewFloat`。id 是系统应用自己的命名，能做白名单加分项，不能当通用判据。
  3. 输入法弹出后，dump 里多出第三个窗口 `com.huawei.hmos.inputmethod`（`[357,1092][1305,2010]`），其中有一个文本为「安全键盘」的节点（`[789,1182][958,1231]`，出现在 `texts` 输出里）。输入法窗口里出现「安全键盘」可以当作「当前焦点是密码框」的运行时信号，比 hint 通用。
- 截屏：密码框获得焦点后 `snapshot_display` 返回的是 109,591 字节的纯黑 JPEG（`shots/03-wifi-password-dialog.jpeg`，只有叠加的调试框与状态栏，应用内容整屏为黑；锁屏熄屏那张是 98,166 字节）。〔推断〕系统对含安全输入的窗口做了截屏屏蔽。对中继的含义：带图的 observe 在密码页会得到黑图，Kotlin 侧不能把「全黑图」当成读屏失败而重试。

### 2.2 Toggle 的 `checked` 字段

〔实测〕`02-settings-bluetooth.json`、`03a-wifi-list.json`：Toggle 节点 `type=Toggle`，`checkable="true"`，状态在 **`checked`**（字符串 `"true"` / `"false"`）。`selected` 恒为 `"false"`，不是开关状态。

```
{"type":"Toggle","text":"","checkable":"true","checked":"true","clickable":"true",
 "id":"Setting.Fusion.BluetoothSwitchGroup.BluetoothSwitchItem.result","bounds":"[1128,711][1236,771]"}
```

- Toggle 自己没有文字。标签「蓝牙」在父节点 `Row`（`id=...BluetoothSwitchItem`，`text="蓝牙"`），该 `Row` 同样 `checkable=true`、`checked=true`、`clickable=true`。点 Row 中心和点 Toggle 都是开关点击，映射时标签要取父 Row 的 text。
- 手上只有「开」态（蓝牙、星闪、WLAN 三个开关都是开），没有「关」态样本，因为派单禁止改开关。`checked` 的关态值按同一字段为 `"false"` 处理〔推断〕，字段名已确定。

### 2.3 锁屏时 dump 的窗口特征

〔实测〕`05-lockscreen.json`（手机空闲后自行锁屏，屏幕熄灭，`aa start` 返回 10106102）：

- 顶层只有两个窗口，bundle 都是 `com.ohos.sceneboard`，`abilityName` 为空：`WindowScene id=session18 [0,117][1320,2120]` 和 `WindowScene id=session33 [0,0][1320,117]`（状态栏）。没有任何应用窗口。
- `session18` 下根组件 `id=ScreenLockRootComponent`，其下 `MainPageView_Screen_Lock_Home`、`ScreenLockWidgetView`；可见文字只有「免打扰」「来电和通知已静音」，状态栏有运营商名「中国广电」。
- 对比解锁状态：前台应用窗口（如设置）与 `session33` 并列，`session18` 不存在。
- 判据建议：顶层窗口里没有 bundle 不是 `com.ohos.sceneboard` 的窗口，且存在 `id` 以 `ScreenLock` 开头的节点，即锁屏。同时 `aa start` 的 10106102 是第二个独立信号。
- 屏幕亮着的锁屏（有时钟、可上滑解锁的那一屏）没有采到：`uitest uiInput keyEvent Power` 返回 No Error 但屏幕仍黑，派单禁止操作锁屏设置，该变体 NOT_RUN。

## 3. 键码与输入行为（〔实测〕，输入框是设置里的搜索框 `Search`，输完已清空）

| 动作 | 命令 | 结果 |
|---|---|---|
| 回车 | `keyEvent 2054` | 提交输入法里正在组词的内容（见下），搜索框触发搜索。`keymap.json` 的 ENTER=2054 成立 |
| 删除 | `keyEvent 2055` | 删除前一个字符：先提交 `ab`，再按一次，框里变 `a`。DEL=2055 成立 |
| 全选 | `keyEvent 2072 2017`（CTRL_LEFT + A，两个键码一条命令） | 全选后接 `2055` 把 `helloworldX` 清成空串。成功 2 次，1 次没清掉（那次没有先点输入框，焦点状态未知）。结论：全选删除要在点击输入框之后做，且读回比对不可省 |
| `Back` / `Home` | `keyEvent Back` | 回显 `No Error`，返回有效。刚按完 Back 立刻读 dump 有时仍是旧页，读屏前至少等 2 秒 |
| `inputText x y 文本` | `inputText 700 201 hello` | 先点 `(x,y)` 再输入。**追加，不替换**：框里已有 `hello` 时再 `inputText ... world`，回车后是 `helloworld` |
| `text 文本` | `text X` | 输入到当前焦点，同样追加 |
| 中文 | `inputText 700 201 你好` | 回车后追加成 `a你好`，中文可输入 |

关键发现：这台机的输入法是拼音输入法，`text`/`inputText` 打入的拉丁字母先进入输入法的组词条（dump 里是输入法窗口的 `Text`，如 `x'ya'b'c`），框里的 `text` 在按回车（2054）之前不更新；回车把组词内容原样提交。含义：`inputText` 不能替代方案 4.4 的 `replaceText` 宏里的「全选→删除→text」，而且 `text` 之后必须补一次 2054 提交，否则读回比对读到的是旧值。普通表单里回车可能触发提交或换行，是否合适 NOT_RUN（只在搜索框里试过）。纯中文输入是否也要回车提交：本次中文输入后紧跟回车，未单测。
中继现状：`replaceText` 宏默认只做点击、全选、删除、`text` 四步，不发回车；调用方回读发现框里没有新文字（组词条未提交）时，再发 `keyEvent 2054`，或在请求里带 `commit: true` 让宏最后追加这一步。样例：`samples/act-replaceText*.json`、`samples/act-replaceText-commit*.json`。

读回比对的数据：搜索框值在 `SearchField` 节点的 `text`（`id=__SearchField__searchComponent`），外层 `Search` 节点（`id=searchComponent`）的 `text` 恒为空。可编辑节点不一定自己带文本，读回要找子孙里的 `SearchField`。输入后 dump 的更新有 1 秒以上延迟，读回前至少等 3 秒。

## 4. `aa start` 拉起容器内安卓应用

〔实测〕`aa start -b probe.zyt -a probe.zyt.MainActivity` 回显 `start ability successfully.`，从鸿蒙设置在前台时执行，1 秒内 dump 的前台窗口变成 `probe.zyt/probe.zyt.MainActivity`，重复两次一致。

- bundleName / abilityName 的来源：`uitest dumpLayout` 顶层窗口的 `bundleName`、`abilityName`（探针 App 是 `probe.zyt` / `probe.zyt.MainActivity`；见 `04-container-probe-home.json` 第一个窗口）。
- `bm dump -n probe.zyt` 报 `error: failed to get information and the parameters may be wrong.`；`bm dump -a`（264 行）里没有 `probe.zyt`，只有宿主壳 `com.huawei.shell_assistant`。含义：方案 4.5 里「未给 ability 时用 `bm dump -n` 取 mainAbility」对容器应用不成立，`launch` 对容器应用必须由调用方给全 `bundle` + `ability`，中继的 `apps` 列表（来自 `bm dump -a`）不含容器应用。
- `aa dump -l` 里容器应用任务名形如 `#probe.zyt:entry:probe.zyt.MainActivity`，记录里的 bundle 是 `com.huawei.shell_assistant`，可用来列出正在运行的容器应用任务。
- 锁屏时 `aa start` 失败：`Error Code:10106102 ... The device screen is locked during the application launch, unlock screen failed. Error cause: The current mode is developer mode, and the screen cannot be unlocked automatically`（〔实测〕）。

## 5. typemap 修正意见（按夹具实际出现的 type，6 份夹具共 1,023 个节点）

出现频次：Row 210、Stack 176、Column 110、SymbolGlyph 104、Text 86、Image 68、ListItem 39、RelativeContainer 30、Divider 22、`__Common__` 16、Button 11、TextClock 10、Flex 9、ListItemGroup 8、WindowScene 7、`metaballNode` 6、root 5、Navigation 5、TitleBar 5、List 5、HdsTitleBar 4、HdsMenuNode 4、Toggle 4、NavDestination/NavDestinationContent/NavigationContent 各 3、Scroll 2、Canvas 2、NavBar/NavBarContent 各 2，各出现 1 次的有 MaskBlur、Mask、NavRouter、Search、SearchField、Circle、ScrollBar、LoadingProgress、SheetWrapper、SheetPage、TextInput、EffectComponent、DepthComponent，另有空串 type 6 个（根节点）。

对方案 4.3 的表逐项修正：

1. **可点击的容器才是控件，不是 Button**。设置页 20 个 clickable 节点里 Row 11、Column 2；三个设置页夹具里 `clickable=true` 且自身 `text` 为空、子树里有文字的节点占 13/20、6/16、3/19、8/26。原表只映射 `Button`，会把设置页的列表项全部丢成无标签的容器。规则：任何 `clickable=true` 的节点都映射为可点元素，`text` 取自身，空则取子树里第一个非空 `text`（`ScreenFormat` 要求的标签），类名按 `type` 映射不到时用 `android.view.View`。
2. **`Toggle` → `android.widget.Switch`/`switch`，已有实例**（2.2）。加 `check`，`checked` 字段为真时加 `checked`；标签取父 `Row` 的 `text`，同时父 `Row` 也是 `checkable`，要去重：只保留 Toggle 或只保留 Row 其中一个作为可点元素（建议保留 Row，因为它带标签且范围大，Toggle 作为其状态来源）。
3. **`TextInput` → `android.widget.EditText`**，加 `editable`；密码判定见 2.1。`Search` 外层与内层 `SearchField` 都 `clickable=true`，`SearchField` 才带文本，映射 `Search` 为容器、`SearchField` 为 `EditText`。
4. **`List` / `Scroll` → RecyclerView**：`scrollable="true"` 的类型在夹具里是 `List`、`TextInput`、`SearchField`、`ScrollBar`。不要对 `TextInput` 因 `scrollable` 置 `scroll`，会让文字化输出写「可滑动」。`ScrollBar` 丢弃。
5. **`Image` / `SymbolGlyph`**：设置页的图标全是这两类，`text` 为空。`SymbolGlyph` 原表没有，补 `android.widget.ImageView`；无 `description` 且不可点时丢弃以压体积。
6. **补丢弃名单**：`Divider`、`Mask`、`MaskBlur`、`Circle`、`Canvas`、`EffectComponent`、`DepthComponent`、`LoadingProgress`、`metaballNode`（灵动胶囊，`clickable=true` 但不是用户界面）、`TextClock`（它的子 `Text` 已带「02」「:」「34」，状态栏时钟拆成 4 个节点，状态栏窗口 `com.ohos.sceneboard` 整体按 `kind:"system"` 处理）。
7. **容器安卓节点**：`type` 已是安卓类名（`android.widget.*`、`android.view.*`），原样透传，`raw` 与 `cls` 相同；但同一窗口外面还有鸿蒙一侧的 `Stack/Row/SymbolGlyph/Image/RelativeContainer` 包装，容器窗口的鸿蒙包装层与安卓节点的区分靠 `type` 是否以 `android.` 开头。〔实测〕`04-container-probe-home.json` 里 `android.widget.Button` 8 个，文字在 `text`，`description` 为空。
8. **窗口分类**：`com.ohos.sceneboard`（状态栏、锁屏、下拉通知栏〔推断〕）、`com.huawei.hmos.inputmethod`（输入法）按系统窗口，`kind:"system"`；输入法窗口出现是「安全键盘」判据的来源。
9. **`visible` 恒为 `"true"`**（6 份夹具除根节点外无一例外），dump 已经只含可见节点，「丢弃 visible:false」一条在这台机上是空操作，保留无害。
10. **`opacity`**：容器窗口根节点 `opacity="0.000000"` 但其内容完整可见（`04-container-probe-home.json` 的 `android.widget.FrameLayout`），按「opacity 为 0 丢弃」会把整个容器 App 页面裁掉。**这一条必须改**：只对叶子节点用 opacity 丢弃，且不对 `android.*` 窗口根生效。

## 6. 采集过程里的运行事实

- 同一时刻另有 agent 用同一台手机的 hdc 时，出现间歇 `Connect server failed`、命令耗时从 1 秒变成几十秒、前台被切到计算器；协调方让对方停手后恢复。单机多 agent 共用一台手机时需要串行。
- 设置主页滚动在折叠大标题的状态下，dump 里「星闪和蓝牙」的 bounds 是 `[216,234][505,290]`，点它的中心 `(360,262)` 没有反应；把列表下拉到顶（`swipe 660 700 660 1500 800`）后同一行的 bounds 变成 `[216,1186][505,1242]`，点击生效。原因没有查（〔推断〕为折叠态下该行被标题栏盖住）。点击前后要读屏确认页面变化，不能只信 `No Error`。

## 7. 支付与银行入口（P4 M3，待核）

`config/aliases.json` 已去掉「钱包」。`SafetyRules.DEFAULT_DENY`（Kotlin，不在中继里）建议补的鸿蒙 bundle，来自真机 `bm dump -a` 已读到的名单：`com.huawei.hmos.wallet`、`com.huawei.hmos.walletservice`、`com.huawei.hmos.quickpay`、`com.alipay.mobile.client`。`com.huawei.hmos.passwordvault`（密码保险箱）不是支付入口但同属不碰名单的候选。

微信、云闪付、各银行与京东金融等应用的鸿蒙 bundle 名没有在已读到的片段里出现，待用 `bm dump -a` 全量核对后补，没核实的不写进名单。
