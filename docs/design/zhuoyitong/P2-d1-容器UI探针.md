# P2-d1 容器 UI 探针

日期 2026年11月02日。目标机 Pura X（VDE-AL00，卓易通容器）。标记：〔实测〕〔读码〕〔推断〕NOT_RUN。

## 1. 结论

三项真机探针全部 NOT_RUN。原因：探针 App 安装与拉起都要屏幕解锁，手机在 P2-0 采集后自行熄屏锁定（约 2026年11月02日 11:00 起），`aa start` 持续返回 10106102（`The device screen is locked ... The current mode is developer mode, and the screen cannot be unlocked automatically`），按派单每 20 秒重试、等满 30 分钟（11:02–11:32）仍未解锁，随后停止。三项的代码、脚本已就绪并通过编译，解锁后一条命令可跑完。

| 项 | 状态 | 原因 |
|---|---|---|
| S-OVL 普通悬浮窗 | NOT_RUN | 锁屏，APK 装不上、App 拉不起 |
| S-NTF 通知 | NOT_RUN | 同上 |
| S-FGS 拉前台（App 内 `startActivity` 与 hdc `aa start` 两条路） | NOT_RUN | 同上。hdc `aa start` 一路在 P2-0 有〔实测〕旁证（见 4），App 在后台时自己 `startActivity` 一路没有数据 |

## 2. 已交付的代码（〔读码〕，已 `./scripts/build.sh` 构建成功，APK 29,411,098 字节，versionCode 19）

全部在 `tooling/zhuoyitong-probe/` 内：

- `app/src/main/java/probe/zyt/UiProbe.kt`（新）：`UiProbe` 对象与 `NtfReceiver`、`FgsService`、`FgsTargetActivity`。每个动作往 `filesDir/ui-probe.log` 追加一行，由 `ReportServer` 的 `ui_probe` 字段读出。
- `MainActivity.kt`：新增一行四个按钮：`S-OVL`、`S-NTF`、`S-FGS`、`S-OVL权限页`；`mergedJson` 加 `ui_probe` 字段。
- `AndroidManifest.xml`：`SYSTEM_ALERT_WINDOW` 权限、`FgsTargetActivity`、`NtfReceiver`（action `probe.zyt.NTF_ACTION`）、`FgsService`。
- `app/build.gradle.kts`：versionCode 18 → 19。
- `scripts/ui-probe.sh`（新）、`scripts/ui_probe_helper.py`（新，读屏小工具）。

三项的判定口径：

- S-OVL：点 `S-OVL`，记录 `Settings.canDrawOverlays`、系统是否有权限页可解析、`WindowManager.addView(TYPE_APPLICATION_OVERLAY)` 成功还是抛异常；成功则浮层是 420×200 的红底「ZYT-OVL」。再用 `aa start` 把前台切到鸿蒙设置，dump 与截屏里找「ZYT-OVL」文字及 `probe.zyt` 窗口。权限页只用 `S-OVL权限页` 打开并截屏，不拨开关。
- S-NTF：点 `S-NTF`，记录 `areNotificationsEnabled`，发一条标题 `ZYT-NTF-PROBE`、带按钮 `ZYT-ACTION`（`PendingIntent.getBroadcast`）的通知；切到设置后用 `uitest uiInput swipe 300 3 300 1400 600` 下拉通知栏，dump 里找标题与按钮；找到就点按钮，再回探针读 `ui_probe` 里有没有 `NTF broadcast received`。
- S-FGS：路径 A 点 `S-FGS` 起后台 `FgsService`，20 秒后它 `startActivity(FgsTargetActivity)`，脚本在这 20 秒里用 `aa start` 把前台切到设置，每 5 秒读前台窗口 40 秒，看 `probe.zyt/probe.zyt.FgsTargetActivity` 是否出现，并读 `ui_probe` 里的 `FGS service startActivity` 行（成功或异常类名）。路径 B 先切到设置，再 `hdc aa start -b probe.zyt -a probe.zyt.MainActivity`，每秒读前台窗口三次。中继的 `/v1/launch` 未就绪，用官方 hdc 代替。

## 3. 复现命令

手机解锁后，在 Mac 上：

```
cd tooling/zhuoyitong-probe/scripts
HDC_TARGET=<USB序列号> ./install.sh        # 经文件管理器装 versionCode 19；失败时手动装
HDC_TARGET=<USB序列号> ./ui-probe.sh all   # 或 ovl / ntf / fgs，结果在 results/ui-probe-<时间>/
```

`ui-probe.sh` 对 `aa start` 内置 10106102 重试（每 20 秒、最多 30 分钟）。`install.sh` 在锁屏时失败过一次（文件管理器页面没有「Download」目录入口，屏幕上只剩锁屏的「免打扰」文字），解锁后文件管理器流程是否仍与脚本一致，未验证。该脚本是 P2-5 留下的，本次未改。

## 4. 与本项相关、已有的实测

- 〔实测，P2-0〕`aa start -b probe.zyt -a probe.zyt.MainActivity` 对容器应用有效，`start ability successfully.`，1 秒内前台窗口变为 `probe.zyt/probe.zyt.MainActivity`，从设置前台切换，重复两次（`tooling/dsh-relay/contract/NOTES.md` 第 4 节）。`bm dump -n probe.zyt` 查不到容器应用，所以 `launch` 必须带 ability。
- 〔实测，P0B 9.2〕`ACTION_APPLICATION_DETAILS_SETTINGS` 从前台 Activity 启动成功，后台启动未测。

## 5. 待办（解锁后）

1. 用户解锁手机，保持亮屏。
2. 跑 3 的两条命令。
3. 把 `results/ui-probe-<时间>/` 里的 `run.log`、`ovl-report-*.txt`、`ntf-report-*.txt`、`fgs-*.txt` 与截图填回本文第 1 节表，每项写通过、失败、原因、原始输出。
