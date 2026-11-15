#!/bin/bash
# P2-5 一条命令：备外部文件 → 构建 → 推送 → 经文件管理器安装 → 打开 App → 点「Node 冒烟」
#                → 轮询屏幕拿 URL → curl 取回 JSON 到 results/smoke-<时间>.json
# 手机锁屏时 aa start 报 10106102：每 20 秒重试，总共最多等 30 分钟，不报错退出。
# 环境变量：HDC_USB_SERIAL（USB 序列号，必填）、HDC_TCP_TARGET（可选，USB 不在线时用的无线调试 ip:port）、HDC_TARGET（可选，直接指定）
#          SKIP_BUILD=1 跳过 stage+构建；SKIP_INSTALL=1 只重新点按钮取报告；
#          NODE_SRC 覆盖 T1 产物路径
set -uo pipefail
cd "$(dirname "$0")"
HDC=~/.local/bin/hdc
USB="${HDC_USB_SERIAL:?请设置 HDC_USB_SERIAL（hdc list targets 里的 USB 序列号）}"
TCP="${HDC_TCP_TARGET:-}"
if [ -z "${HDC_TARGET:-}" ]; then
  if $HDC list targets 2>/dev/null | grep -q "$USB"; then HDC_TARGET=$USB
  elif [ -n "$TCP" ] && $HDC list targets 2>/dev/null | grep -q "$TCP"; then HDC_TARGET=$TCP
  else echo "USB 与 TCP 目标都不在线" >&2; exit 2; fi
fi
export HDC_TARGET
echo "hdc 目标: $HDC_TARGET"
DEADLINE=$(( $(date +%s) + 1800 ))
log() { echo "[$(date +%H:%M:%S)] $*"; }

# 反复执行命令直到成功；输出含 10106102（锁屏）或命令失败都等 20 秒重试，超 30 分钟才放弃
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
aa() { $HDC -t "$HDC_TARGET" shell aa start "$@"; }
tap() { python3 uitest_tap.py tap "$@"; }

if [ -z "${SKIP_BUILD:-}" ]; then
  log "备外部文件"; ./stage-ext.sh || exit $?
  log "构建"; ./build.sh >/tmp/zyt-smoke-build.log 2>&1 || { tail -20 /tmp/zyt-smoke-build.log; exit 3; }
  ls -l ../app/build/outputs/apk/release/app-release.apk
fi

if [ -z "${SKIP_INSTALL:-}" ]; then
  # 先等手机解锁：拉起文件管理器本身是无害的探测
  retry "解锁检测" aa -b com.huawei.hmos.filemanager -a MainAbility >/dev/null || exit 4
  log "推送并安装"
  retry "安装" ./install.sh || exit 5
  sleep 8
  log "安装完成页点「打开」"
  retry "点打开" tap '^打开$' || { log "没有「打开」按钮，改用 aa start"; retry "aa start" aa -b probe.zyt -a probe.zyt.MainActivity || exit 6; }
else
  retry "拉起 App" aa -b probe.zyt -a probe.zyt.MainActivity || exit 6
fi
sleep 3
log "点「Node 冒烟」"
retry "点按钮" tap '(?i)^node 冒烟$' || exit 7

log "轮询屏幕拿 URL（最多 20 分钟）"
URL=""
END=$(( $(date +%s) + 1200 ))
while [ "$(date +%s)" -lt $END ]; do
  txt=$(python3 uitest_tap.py texts 2>/dev/null)
  URL=$(grep -o 'SMOKE_URL http://[0-9.]*:[0-9]*/report' <<<"$txt" | head -1 | cut -d' ' -f2)
  [ -n "$URL" ] && break
  grep -q 'EXIT code=' <<<"$txt" && { log "node 进程已退出但没有 URL，屏幕尾部:"; tail -15 <<<"$txt"; exit 8; }
  sleep 10
done
[ -n "$URL" ] || { log "20 分钟内没有 URL"; exit 8; }
log "URL $URL"
mkdir -p ../results
OUT=../results/smoke-$(date +%y%m%d-%H%M%S).json
curl -sS --max-time 30 "$URL" -o "$OUT" || { log "curl 失败（Mac 与手机不在同一局域网？）"; exit 9; }
log "已保存 $OUT"
python3 - "$OUT" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
for i in r['items']:
    print(f"{i['id']:4} {i['status']:7} exit={i['exit']!s:4} {i['ms']:>6}ms  {i['name']}")
PY
