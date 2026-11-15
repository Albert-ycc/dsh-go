#!/bin/bash
# P2-d1：容器 UI 探针（S-OVL 悬浮窗、S-NTF 通知、S-FGS 拉前台）。前提：探针 APK（versionCode 19+）已装。
# 用法：HDC_TARGET=<序列号> scripts/ui-probe.sh [ovl|ntf|fgs|all]   结果与截图落在 results/ui-probe-<时间>/
# 手机锁屏时 aa start 报 10106102：每 20 秒重试，最多 30 分钟。S-FGS 的中继路径用 hdc aa start 代替 /v1/launch。
set -uo pipefail
cd "$(dirname "$0")"
HDC=~/.local/bin/hdc
USB="${HDC_USB_SERIAL:?请设置 HDC_USB_SERIAL（hdc list targets 里的 USB 序列号）}"; TCP="${HDC_TCP_TARGET:-}"
if [ -z "${HDC_TARGET:-}" ]; then
  if $HDC list targets 2>/dev/null | grep -q "$USB"; then HDC_TARGET=$USB
  elif [ -n "$TCP" ] && $HDC list targets 2>/dev/null | grep -q "$TCP"; then HDC_TARGET=$TCP
  else echo "USB 与 TCP 目标都不在线" >&2; exit 2; fi
fi
export HDC_TARGET
WHAT=${1:-all}
OUT=../results/ui-probe-$(date +%y%m%d-%H%M%S); mkdir -p "$OUT"
DEADLINE=$(( $(date +%s) + 1800 ))
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/run.log"; }
H() { python3 ui_probe_helper.py "$@"; }
dev() { $HDC -t "$HDC_TARGET" shell "$@" 2>&1; }
retry_aa() { # $1 bundle $2 ability
  while :; do
    o=$(dev aa start -b "$1" -a "$2")
    grep -q successfully <<<"$o" && return 0
    log "aa start $1: $(tr '\n' ' ' <<<"$o" | cut -c1-120) ，20 秒后重试"
    [ "$(date +%s)" -ge $DEADLINE ] && { log "等满 30 分钟"; return 1; }
    sleep 20
  done
}
PROBE=(probe.zyt probe.zyt.MainActivity)
SETTINGS=(com.huawei.hmos.settings com.huawei.hmos.settings.MainAbility)
report() { # 从屏幕上的 HTTP 地址取报告，打印 ui_probe 字段
  txt=$(python3 uitest_tap.py texts 2>/dev/null)
  port=$(grep -o 'wlan0:[0-9.]* 端口 [0-9]*' <<<"$txt" | head -1); ip=$(sed 's/wlan0:\([0-9.]*\) .*/\1/' <<<"$port"); p=$(awk '{print $3}' <<<"$port")
  [ -n "$p" ] || { echo "(取不到报告地址)"; return 1; }
  curl -sS --max-time 20 "http://$ip:$p/report" | python3 -c "import json,sys; print(json.load(sys.stdin).get('ui_probe',''))"
}
fresh_probe() { retry_aa "${PROBE[@]}" || exit 4; sleep 3; }

ovl() {
  log "== S-OVL"; fresh_probe
  H tap '^S-OVL$' | tee -a "$OUT/run.log"; sleep 2
  H cap "$OUT/ovl-1-probe-front" | tee -a "$OUT/run.log"
  report | tee "$OUT/ovl-report-1.txt"
  H tap 'S-OVL权限页' | tee -a "$OUT/run.log"; sleep 3      # 只看页面，不改开关
  H cap "$OUT/ovl-2-permission-page" | tee -a "$OUT/run.log"
  retry_aa "${PROBE[@]}"; sleep 2
  log "切到鸿蒙设置，看浮层是否还在"
  retry_aa "${SETTINGS[@]}"; sleep 4
  H cap "$OUT/ovl-3-settings-front" | tee -a "$OUT/run.log"
  H grep 'ZYT-OVL' | tee -a "$OUT/run.log"
}
ntf() {
  log "== S-NTF"; fresh_probe
  H tap '^S-NTF$' | tee -a "$OUT/run.log"; sleep 2
  report | tee "$OUT/ntf-report-1.txt"
  log "切到鸿蒙设置后下拉通知栏"
  retry_aa "${SETTINGS[@]}"; sleep 3
  dev uitest uiInput swipe 300 3 300 1400 600 | tee -a "$OUT/run.log"; sleep 2
  H cap "$OUT/ntf-1-shade" | tee -a "$OUT/run.log"
  H grep 'ZYT-NTF-PROBE|ZYT-ACTION|容器通知探针' | tee "$OUT/ntf-grep.txt"
  if grep -q '^HIT.*ZYT-ACTION' "$OUT/ntf-grep.txt"; then
    H tap 'ZYT-ACTION' | tee -a "$OUT/run.log"; sleep 3
    H cap "$OUT/ntf-2-after-action" | tee -a "$OUT/run.log"
  else log "通知栏里没有 ZYT-ACTION 按钮文字"; fi
  retry_aa "${PROBE[@]}"; sleep 3
  report | tee "$OUT/ntf-report-2.txt"
}
fgs() {
  log "== S-FGS"; fresh_probe
  H tap '^S-FGS$' | tee -a "$OUT/run.log"; T0=$(date +%s)
  retry_aa "${SETTINGS[@]}"; sleep 2
  log "App 在后台，等 Service 20 秒后 startActivity"
  for i in 1 2 3 4 5 6 7 8; do sleep 5; echo "t+$(( $(date +%s)-T0 ))s $(H fg)" | tee -a "$OUT/fgs-a-poll.txt"; done
  H cap "$OUT/fgs-a-after" | tee -a "$OUT/run.log"
  retry_aa "${PROBE[@]}"; sleep 3; report | tee "$OUT/fgs-report-a.txt"
  log "路径 B：切到设置后用 hdc aa start 拉探针 MainActivity"
  retry_aa "${SETTINGS[@]}"; sleep 3; echo "before: $(H fg)" | tee -a "$OUT/fgs-b.txt"
  dev aa start -b probe.zyt -a probe.zyt.MainActivity | tee -a "$OUT/fgs-b.txt"
  for i in 1 2 3; do sleep 1; echo "t+${i}s $(H fg)" | tee -a "$OUT/fgs-b.txt"; done
  H cap "$OUT/fgs-b-after" | tee -a "$OUT/run.log"
}
case $WHAT in ovl) ovl;; ntf) ntf;; fgs) fgs;; all) ovl; ntf; fgs;; esac
log "完成，结果目录 $OUT"
