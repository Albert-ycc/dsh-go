#!/bin/bash
# P3 引擎线真机验收：推送 APK -> (卸载旧版) -> 安装 -> 拉起；其余交互按文字点。
# 用法：
#   p3-engine.sh push        推 APK 到手机 Download
#   p3-engine.sh uninstall   在卓易通里卸载旧版 DSH Go（长按桌面图标，按文字点；截屏留证）
#   p3-engine.sh install     经文件管理器 + 卓易通安装器装
#   p3-engine.sh launch      拉起 DSH Go（点安装完成页「打开」，或在卓易通桌面点图标）
#   p3-engine.sh reinstall   push -> uninstall -> install -> launch（卸载重装，/root、API key、设置全丢；升级只跑 push、install、launch）
#   p3-engine.sh tap <正则>...   按文字依次点
#   p3-engine.sh texts       打印当前屏全部文字（带坐标）
#   p3-engine.sh shot <名>   截屏到 results/p3/<名>.jpeg
#   p3-engine.sh waitfor <正则> [秒]   轮询屏幕文字，打印命中耗时
#   p3-engine.sh fgdump <名> aa dump -l 前台记录存 results/p3/<名>.txt
#   p3-engine.sh swipe x1 y1 x2 y2 [ms]
#   p3-engine.sh home        回桌面
# 环境变量：HDC_USB_SERIAL（USB 序列号，必填）、HDC_TCP_TARGET（可选，USB 不在线时用的无线调试 ip:port）、
#          HDC_TARGET（可选，直接指定）、APK（默认 app-arm64-release.apk）
# 锁屏（10106102）每 20 秒重试，最多 30 分钟，不报错退出。
set -uo pipefail
case "${1:-help}" in help|-h|--help) sed -n 2,22p "$0"; exit 0 ;; esac
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
HDC=~/.local/bin/hdc
USB="${HDC_USB_SERIAL:?请设置 HDC_USB_SERIAL（hdc list targets 里的 USB 序列号）}"
TCP="${HDC_TCP_TARGET:-}"
APK=${APK:-$HERE/../../../app/build/app/outputs/flutter-apk/app-arm64-release.apk}
APKNAME=dshgo-p3.apk
RES="$HERE/../results/p3"; mkdir -p "$RES"
if [ -z "${HDC_TARGET:-}" ]; then
  if $HDC list targets 2>/dev/null | grep -q "$USB"; then HDC_TARGET=$USB
  elif [ -n "$TCP" ] && $HDC list targets 2>/dev/null | grep -q "$TCP"; then HDC_TARGET=$TCP
  else echo "USB 与 TCP 目标都不在线" >&2; exit 2; fi
fi
export HDC_TARGET
H() { $HDC -t "$HDC_TARGET" "$@"; }
log() { echo "[$(date +%H:%M:%S)] $*"; }
DEADLINE=$(( $(date +%s) + 1800 ))

retry() {
  local what=$1; shift
  while :; do
    local out; out=$("$@" 2>&1); local rc=$?
    if [ $rc -eq 0 ] && ! grep -q 10106102 <<<"$out"; then echo "$out"; return 0; fi
    if grep -q 10106102 <<<"$out"; then log "$what: 手机锁屏(10106102)，20 秒后重试"; else log "$what: 失败 rc=$rc: $(tail -1 <<<"$out")，20 秒后重试"; fi
    [ "$(date +%s)" -ge $DEADLINE ] && { log "$what: 等满 30 分钟仍未成功"; return 1; }
    sleep 20
  done
}
aa() { H shell aa start "$@"; }
tap() { python3 "$HERE/uitest_tap.py" tap "$@"; }
texts() { python3 "$HERE/uitest_tap.py" texts; }
shot() { ( cd "$RES" && python3 -c "import sys; sys.path.insert(0,'$HERE'); import uitest_tap as u; u.shot('$1'); print('$RES/$1.jpeg')" ); }

unlock_probe() { retry "解锁检测" aa -b com.huawei.hmos.filemanager -a MainAbility >/dev/null; }

do_push() {
  [ -f "$APK" ] || { echo "没有 APK: $APK" >&2; exit 3; }
  log "sha256 $(shasum -a 256 "$APK" | cut -c1-16)  $(stat -f %z "$APK") B"
  retry "推送" H file send "$APK" /storage/media/100/local/files/Docs/Download/$APKNAME
}

do_uninstall() {
  # 卓易通里卸载：桌面图标长按 -> 菜单里的「卸载/删除」 -> 确认。控件文字以真机为准，找不到就截屏停下。
  unlock_probe || exit 4
  H shell aa start -b com.droi.tong -a "$(H shell bm dump -n com.droi.tong 2>/dev/null | tr -d '\r' | grep -o '"mainAbility": *"[^"]*"' | head -1 | sed 's/.*: *"\(.*\)"/\1/')" >/dev/null 2>&1
  sleep 3; shot uninstall-0-container
  texts > "$RES/uninstall-0-texts.txt"
  log "卓易通首屏文字已存 uninstall-0-texts.txt；用 'tap' / 长按 手工推进，必要时按文字调整"
  python3 - <<'PY'
import sys, time
sys.path.insert(0, '.')
import uitest_tap as u
r = u.find(r'^DSH ?Go$', attempts=3)
if not r:
    print('桌面没有 DSH Go 图标，视为未安装或需手工翻页'); sys.exit(0)
print('图标', r)
u.sh('shell', 'uitest', 'uiInput', 'longClick', str(r[1]), str(r[2]))
time.sleep(2)
PY
  shot uninstall-1-menu; texts > "$RES/uninstall-1-texts.txt"
  tap '^(卸载|删除|移除)' && sleep 2 && shot uninstall-2-confirm
  tap '^(卸载|删除|确定|确认)$' && sleep 3 && shot uninstall-3-done
}

do_install() {
  unlock_probe || exit 4
  log "文件管理器装 $APKNAME"
  retry "打开文件管理器" aa -b com.huawei.hmos.filemanager -a MainAbility >/dev/null
  sleep 3
  # 首装按钮是「继续安装」；覆盖安装时安装器可能写「继续更新」「更新」「覆盖安装」，以真机文字为准
  retry "点选安装包" tap '^我的手机$' '^(Download|下载)$' "^$APKNAME$" '^(继续安装|继续更新|更新|覆盖安装)$' || exit 5
  sleep 10; shot install-done; texts > "$RES/install-done-texts.txt"
}

do_launch() {
  retry "点打开" tap '^打开$' || { log "没有「打开」按钮，请在卓易通桌面点图标"; exit 6; }
  date +%s > "$RES/launch-epoch.txt"
  sleep 4; shot launch-first
}

# 无参数只打印用法：默认跑卸载重装会把 /root、API key 和设置一起清掉。
case "${1:-help}" in
  push) do_push ;;
  uninstall) do_uninstall ;;
  install) do_install ;;
  launch) do_launch ;;
  reinstall) do_push && do_uninstall && do_install && do_launch ;;
  help|-h|--help) sed -n 2,22p "$0"; exit 0 ;;
  tap) shift; retry "点按" tap "$@" ;;
  texts) texts ;;
  shot) shift; shot "$1" ;;
  waitfor)
    pat=$2; lim=${3:-120}; t0=$(date +%s)
    while :; do
      if texts 2>/dev/null | grep -Eq "$pat"; then echo "命中 '$pat' 耗时 $(( $(date +%s) - t0 )) s"; exit 0; fi
      [ $(( $(date +%s) - t0 )) -ge "$lim" ] && { echo "超时 ${lim}s 未见 '$pat'"; exit 1; }
      sleep 3
    done ;;
  fgdump) H shell aa dump -l > "$RES/$2.txt"; echo "$RES/$2.txt" ;;
  swipe) shift; H shell uitest uiInput swipe "$1" "$2" "$3" "$4" ${5:-} ;;
  home) H shell uitest uiInput keyEvent Home ;;
  *) sed -n 2,22p "$0"; exit 1 ;;
esac
