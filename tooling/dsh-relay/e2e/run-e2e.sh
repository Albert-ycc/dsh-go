#!/bin/bash
# dsh-relay 端到端验收脚本：P1 方案第 8 节 R0、R2、R6–R11 里能脚本化的部分。
# 每项输出 PASS / FAIL / SKIP 与关键 relay.log 行；任一 FAIL 则退出码 1。
# 需要人在手机上操作的部分（R1 配对填写、R3、R4、R5、R12、R13，以及 R6 的通知按钮、R8 的手机通知）不在此脚本内。
#
# 环境变量（默认值指向正式实例；序列号、IP、SSID 一律不写进仓库，由环境变量传入）：
#   DSH_RELAY_HOME      中继状态目录                  默认 ~/.dsh-relay
#   DSH_RELAY_PORT      HTTPS 端口，管理口为 +1       默认 8731
#   DSH_RELAY_LOG_DIR   relay.log 所在目录            默认 ~/Library/Logs/dsh-relay
#   HDC_BIN             doctor 用的 hdc               默认 ~/.local/bin/hdc
#   E2E_HOST            脚本访问中继用的地址          默认 127.0.0.1
#   E2E_LAN_HOST        R11 用的 Mac 局域网地址       默认同 E2E_HOST
#   E2E_USB_SERIAL      手机 USB 序列号（只用于核对绑定，不会被打印）  默认空
#   E2E_TOKEN_FILE      已有令牌文件；不设则脚本自己配对一个「e2e-script」令牌，结束时撤销
#   E2E_PAIR_CODE       手机配对用过的配对码（R1 日志检查：码不得出现在日志里），可选
#   E2E_FAKE_DIR        假 hdc 的状态目录；设了才跑需要改假设备状态的项（R6 延迟注入、R7 零设备调用断言、R9 掉线、R10 注入）
#   E2E_RELAY_PID_FILE / E2E_RELAY_START_CMD   R8 用：杀进程与重起命令；不设则 R8 SKIP
#   E2E_ALLOW_TYPE=1    在真机上也允许 R10 往当前焦点输入框打字（默认只在假设备上跑）
#   E2E_ONLY            只跑列出的项，如 "R0 R2"      默认全部
#   E2E_FAST=1          R11 不等 61 秒验证解封
#   E2E_ALLOW_LAUNCHD=0 doctor 的 launchd 一项也算必过（默认不算：验收阶段不装 launchd 作业）

HERE=$(cd "$(dirname "$0")" && pwd)
RELAY_DIR=$(cd "$HERE/.." && pwd)
CLI="$RELAY_DIR/bin/dsh-relay"

export DSH_RELAY_HOME="${DSH_RELAY_HOME:-$HOME/.dsh-relay}"
export DSH_RELAY_PORT="${DSH_RELAY_PORT:-8731}"
export DSH_RELAY_LOG_DIR="${DSH_RELAY_LOG_DIR:-$HOME/Library/Logs/dsh-relay}"
export HDC_BIN="${HDC_BIN:-$HOME/.local/bin/hdc}"
E2E_HOST="${E2E_HOST:-127.0.0.1}"
E2E_LAN_HOST="${E2E_LAN_HOST:-$E2E_HOST}"
BASE="https://$E2E_HOST:$DSH_RELAY_PORT"
LOG="$DSH_RELAY_LOG_DIR/relay.log"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dsh-e2e.XXXXXX")
BODY="$WORK/body.json"
HDR_FILE="$WORK/auth.hdr"
TOKEN_FILE="${E2E_TOKEN_FILE:-}"
SELF_PAIRED=0
SELF_TOKEN_ID=""
STATUS=""
NPASS=0 NFAIL=0 NSKIP=0
RESULTS=""

# ---------- 输出 ----------
say() { printf '%s\n' "$*"; }
pass() { NPASS=$((NPASS + 1)); RESULTS="$RESULTS$1 PASS\n"; say "PASS  $1  $2"; }
fail() { NFAIL=$((NFAIL + 1)); RESULTS="$RESULTS$1 FAIL\n"; say "FAIL  $1  $2"; }
skip() { NSKIP=$((NSKIP + 1)); RESULTS="$RESULTS$1 SKIP\n"; say "SKIP  $1  $2"; }
sel() { [ -z "${E2E_ONLY:-}" ] && return 0; case " $E2E_ONLY " in *" $1 "*) return 0 ;; esac; return 1; }
head_(){ say ""; say "=== $1 $2"; }
showlog() { # 打印 stdin 里的日志行（最多 4 行，截 300 字）
  local n=0 l
  while IFS= read -r l; do
    n=$((n + 1)); [ "$n" -le 4 ] && say "      relay.log> ${l:0:300}"
  done
}

# ---------- 工具 ----------
jv() { # jv <path.in.body>  取 $BODY 里的字段，没有则空串
  node -e 'let j;try{j=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))}catch{process.exit(0)};const v=process.argv[2].split(".").reduce((o,k)=>o==null?o:o[k],j);console.log(v===undefined||v===null?"":typeof v==="object"?JSON.stringify(v):v)' "$BODY" "$1"
}
loglines() { [ -f "$LOG" ] && wc -l <"$LOG" | tr -d ' ' || echo 0; }
logq() { node "$HERE/logq.mjs" "$LOG" "$@"; }

AUTH=0
API_H=()
api() { # api METHOD PATH [JSON]  → $STATUS，响应体在 $BODY
  local args=(-sk --tls-max 1.2 --max-time "${E2E_CURL_TIMEOUT:-30}" -o "$BODY" -w '%{http_code}' -X "$1" -H 'x-dsh-relay: 1')
  [ "$AUTH" = 1 ] && args+=(-H "@$HDR_FILE")
  [ "${#API_H[@]}" -gt 0 ] && args+=("${API_H[@]}")
  [ -n "${3:-}" ] && args+=(-H 'content-type: application/json' --data "$3")
  STATUS=$(curl "${args[@]}" "$BASE$2" 2>/dev/null) || STATUS="curl-$?"
}
fake() { [ -n "${E2E_FAKE_DIR:-}" ]; }
fake_behaviors() { fake && printf '%s' "$1" >"$E2E_FAKE_DIR/behaviors.json"; }
fake_calls() { [ -f "$E2E_FAKE_DIR/calls.log" ] && wc -l <"$E2E_FAKE_DIR/calls.log" | tr -d ' ' || echo 0; }

ping_ok() { [ "$(curl -sk --tls-max 1.2 --max-time 3 "$BASE/v1/ping" 2>/dev/null)" = '{"service":"dsh-relay","v":1}' ]; }

# ---------- 令牌 ----------
setup_token() {
  if [ -z "$TOKEN_FILE" ]; then
    local out code before
    before=$(loglines)
    out=$("$CLI" pair 2>&1) || { say "cannot start pairing: $out"; return 1; }
    code=$(printf '%s\n' "$out" | sed -n 's/^pairing code: \([0-9A-Z-]*\).*/\1/p')
    [ -n "$code" ] || { say "cannot parse pairing code"; return 1; }
    TOKEN_FILE="$WORK/token"
    (cd "$RELAY_DIR" && LIVE_PORT="$DSH_RELAY_PORT" LIVE_TOKEN_FILE="$TOKEN_FILE" node testkit/live.mjs pair "$code" >/dev/null 2>&1) \
      || { say "self-pairing failed"; return 1; }
    SELF_PAIRED=1
    SELF_TOKEN_ID=$(logq "$before" kind=auth op=pair ok=true | tail -1 | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{console.log(JSON.parse(s).tok)}catch{}})')
  fi
  [ -s "$TOKEN_FILE" ] || return 1
  (umask 077; printf 'authorization: Bearer %s\n' "$(tr -d '\n' <"$TOKEN_FILE")" >"$HDR_FILE")
  AUTH=1
}

cleanup() {
  if [ "$SELF_PAIRED" = 1 ] && [ -n "$SELF_TOKEN_ID" ]; then "$CLI" revoke "$SELF_TOKEN_ID" >/dev/null 2>&1; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# 观测一次，要求 200 且 ok:true
observe_ok() { api POST /v1/observe "{}"; [ "$STATUS" = 200 ] && [ "$(jv ok)" = true ]; }

# =====================================================================
say "dsh-relay e2e  $(date '+%Y-%m-%dT%H:%M:%S%z')"
say "target: $BASE  home=$DSH_RELAY_HOME  log=$LOG  fake=$([ -n "${E2E_FAKE_DIR:-}" ] && echo yes || echo no)"

# ---------- R0 ----------
if sel R0; then
  head_ R0 "doctor 与 ping"
  out=$("$CLI" doctor 2>&1); rc=$?
  say "$out" | sed 's/^/      /'
  hard_fail=$(printf '%s\n' "$out" | grep '^FAIL' | { if [ "${E2E_ALLOW_LAUNCHD:-1}" = 1 ]; then grep -v 'launchd job'; else cat; fi; })
  ping_body=$(curl -sk --tls-max 1.2 --max-time 5 "$BASE/v1/ping" 2>/dev/null)
  if [ -n "$hard_fail" ]; then fail R0 "doctor 有未通过项: $(printf '%s' "$hard_fail" | head -3 | tr '\n' ';')"
  elif [ "$ping_body" != '{"service":"dsh-relay","v":1}' ]; then fail R0 "ping 返回 $ping_body"
  else
    note=""
    [ "$rc" != 0 ] && note="（doctor 退出码 $rc，仅 launchd 一项未装：验收不装 launchd 作业，见文档偏差说明）"
    pass R0 "doctor 全部通过，ping 正确 $note"
  fi
  if [ -n "${E2E_USB_SERIAL:-}" ]; then
    if "$HDC_BIN" list targets 2>/dev/null | grep -qF "$E2E_USB_SERIAL"; then say "      hdc list targets 含 E2E_USB_SERIAL 指定的设备（序列号不打印）"
    else say "      注意: hdc list targets 里没有 E2E_USB_SERIAL 指定的设备"; fi
  fi
fi

# ---------- 令牌（R2 起都要） ----------
NEED_TOKEN=0
for r in R1 R2 R6 R7 R8 R9 R10 R11; do sel "$r" && NEED_TOKEN=1; done
if [ "$NEED_TOKEN" = 1 ]; then
  if ! ping_ok; then say "中继 ping 不通，R1 起的项无法执行"; fail SETUP "ping $BASE 失败"; NEED_TOKEN=0
  elif ! setup_token; then fail SETUP "拿不到令牌（E2E_TOKEN_FILE 或 dsh-relay pair 失败）"; NEED_TOKEN=0
  fi
fi

# ---------- R1（日志侧） ----------
if sel R1 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R1 "配对日志（手机侧填写与「测试连接」为人工）"
  lines=$(grep -c '"kind":"auth","op":"pair","ip":"[^"]*","tok":"[^"]*","ok":true' "$LOG" 2>/dev/null)
  if [ "${lines:-0}" -ge 1 ]; then
    grep '"kind":"auth","op":"pair"[^}]*"ok":true' "$LOG" | tail -2 | showlog
    leak=0
    [ -n "${E2E_PAIR_CODE:-}" ] && grep -qiF "$(printf '%s' "$E2E_PAIR_CODE" | tr -d '-')" "$LOG" && leak=1
    perm=$(stat -f '%Lp' "$DSH_RELAY_HOME/tokens.json" 2>/dev/null)
    if [ "$leak" = 1 ]; then fail R1 "配对码出现在 relay.log"
    elif [ "$perm" != 600 ]; then fail R1 "tokens.json 权限 $perm，应为 600"
    else pass R1 "有配对成功行；tokens.json 权限 600；配对码不在日志"; fi
  else skip R1 "relay.log 里还没有配对成功行（先在手机上配对）"; fi
fi

# ---------- R2 ----------
if sel R2 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R2 "读屏与日志脱敏"
  before=$(loglines); rid="e2e-$(date +%s)-r2"
  API_H=(-H "x-request-id: $rid"); observe_ok; ok=$?; API_H=()
  line=$(logq "$before" kind=read op=observe "req=$rid" | tail -1)
  [ -n "$line" ] && printf '%s\n' "$line" | showlog
  if [ "$ok" != 0 ]; then fail R2 "observe HTTP $STATUS code=$(jv error.code)"
  elif [ -z "$line" ]; then fail R2 "relay.log 没有 kind:read,op:observe 行"
  else
    res=$(printf '%s' "$line" | node -e '
      let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
        const j=JSON.parse(s);const allowed=new Set(["ts","req","task","ip","tok","kind","op","nodes","bytes","dev","ms","ok"]);
        const extra=Object.keys(j).filter(k=>!allowed.has(k));
        const miss=["nodes","bytes","ms"].filter(k=>!Number.isFinite(j[k]));
        console.log(extra.length?"extra:"+extra.join(","):miss.length?"missing:"+miss.join(","):"ok");
      })')
    if [ "$res" != ok ]; then fail R2 "日志行字段不符 ($res)"
    else pass R2 "observe 成功；日志含 nodes/bytes/ms，字段全在白名单内（无文字内容）"; fi
  fi
fi

# ---------- R6 ----------
if sel R6 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R6 "停止：/v1/stop 取消在途命令（通知按钮点击本身为人工）"
  before=$(loglines); task="e2e-task-r6-$$"
  fake_behaviors '[{"match":"uitest dumpLayout","sleepMs":4000}]'
  ( curl -sk --tls-max 1.2 --max-time 40 -o "$WORK/r6-inflight.json" -w '%{http_code}' -X POST \
      -H 'x-dsh-relay: 1' -H "@$HDR_FILE" -H "x-dsh-task: $task" -H 'content-type: application/json' --data '{"timeoutMs":15000}' \
      "$BASE/v1/observe" >"$WORK/r6-inflight.status" 2>/dev/null ) &
  inflight=$!
  sleep 1
  api POST /v1/stop "{\"task\":\"$task\"}" ; stop_status=$STATUS; cancelled=$(jv cancelled)
  wait "$inflight"
  fake_behaviors '[]'
  ist=$(cat "$WORK/r6-inflight.status"); icode=$(node -e 'try{console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).error.code)}catch{}' "$WORK/r6-inflight.json")
  line=$(logq "$before" kind=ctl op=stop | tail -1); [ -n "$line" ] && printf '%s\n' "$line" | showlog
  mark=$(loglines); sleep 2
  later_act=$(logq "$mark" kind=act | wc -l | tr -d ' ')
  if [ "$stop_status" != 200 ] || [ "${cancelled:-0}" -lt 1 ]; then fail R6 "stop HTTP $stop_status cancelled=${cancelled:-?}（在途命令没被取消）"
  elif [ -z "$line" ]; then fail R6 "没有 kind:ctl,op:stop 日志行"
  elif [ "$ist" != 409 ] || [ "$icode" != cancelled ]; then fail R6 "在途请求应得 409 cancelled，实际 $ist $icode"
  elif [ "$later_act" != 0 ]; then fail R6 "stop 之后 2 秒内出现 $later_act 条 act"
  else pass R6 "stop 取消 $cancelled 个在途命令，在途请求得 409 cancelled，之后 2 秒无 act"; fi
  observe_ok || say "      注意: stop 之后的 observe 未恢复 (HTTP $STATUS)"
fi

# ---------- R7 ----------
if sel R7 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R7 "halt / resume 不对称，无需重新配对"
  before=$(loglines); calls0=$(fake && fake_calls)
  "$CLI" halt >/dev/null 2>&1
  api GET /v1/device; dev_halted=$(jv halted)
  api POST /v1/observe '{}'; hs=$STATUS; hc=$(jv error.code)
  api POST /v1/act '{"op":"key","name":"back"}'; as=$STATUS; ac=$(jv error.code)
  calls1=$(fake && fake_calls)
  new_dev=$(logq "$before" kind=read op=observe | wc -l | tr -d ' ')
  new_act=$(logq "$before" kind=act | wc -l | tr -d ' ')
  logq "$before" op=halt | showlog
  "$CLI" resume >/dev/null 2>&1
  observe_ok; resumed=$?
  if [ "$hs" != 423 ] || [ "$hc" != halted ] || [ "$as" != 423 ] || [ "$ac" != halted ]; then fail R7 "halt 期间 observe=$hs/$hc act=$as/$ac，应都是 423 halted"
  elif [ "$dev_halted" != true ]; then fail R7 "/v1/device 的 halted 不是 true"
  elif [ "$new_dev" != 0 ] || [ "$new_act" != 0 ]; then fail R7 "halt 期间日志出现设备命令 (read=$new_dev act=$new_act)"
  elif fake && [ "$calls0" != "$calls1" ]; then fail R7 "halt 期间假 hdc 收到 $((calls1 - calls0)) 次调用"
  elif [ "$resumed" != 0 ]; then fail R7 "resume 后 observe 未恢复 (HTTP $STATUS)，旧令牌被拒或服务异常"
  else pass R7 "halt 期间 423 halted 且无设备命令$(fake && echo '（假 hdc 零调用）')；resume 后同一令牌恢复"; fi
fi

# ---------- R8 ----------
if sel R8 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R8 "Mac 离线（用停/起前台进程代替 launchctl bootout/bootstrap；手机通知与红色状态为人工）"
  if [ -z "${E2E_RELAY_PID_FILE:-}" ] || [ -z "${E2E_RELAY_START_CMD:-}" ]; then
    skip R8 "未设 E2E_RELAY_PID_FILE / E2E_RELAY_START_CMD，不能代为停起中继"
  else
    pid=$(cat "$E2E_RELAY_PID_FILE" 2>/dev/null)
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then fail R8 "pid 文件里的进程不存在"
    else
      kill "$pid"; t0=$(date +%s); down=0
      for _ in $(seq 1 25); do ping_ok || { down=1; break; }; sleep 0.2; done
      t_down=$(( $(date +%s) - t0 ))
      eval "$E2E_RELAY_START_CMD" ; up=0
      for _ in $(seq 1 75); do ping_ok && { up=1; break; }; sleep 0.2; done
      observe_ok; again=$?
      logq 0 kind=ctl op=start | tail -2 | showlog
      if [ "$down" != 1 ]; then fail R8 "停掉进程 5 秒内 ping 仍通"
      elif [ "$up" != 1 ]; then fail R8 "重起后 15 秒内 ping 不通"
      elif [ "$again" != 0 ]; then fail R8 "重起后同一令牌 observe 失败 (HTTP $STATUS)，令牌或证书没跟着落盘"
      else pass R8 "停进程后 ${t_down}s 内连不上；重起后原令牌与证书继续有效，无需重新配对"; fi
    fi
  fi
fi

# ---------- R9 ----------
if sel R9 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R9 "手机离线与无线调试地址更新"
  tgt=$("$CLI" target show 2>&1); say "      当前通道: $(printf '%s' "$tgt" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);console.log(JSON.stringify({state:j.state,via:j.via,tcpAddrSet:!!j.tcpAddr}))}catch{console.log("unparsed")}})')"
  if [ -n "${E2E_USB_SERIAL:-}" ]; then
    if [ -f "$DSH_RELAY_HOME/binding.json" ] && grep -qF "$E2E_USB_SERIAL" "$DSH_RELAY_HOME/binding.json"; then say "      binding.json 绑定的是 E2E_USB_SERIAL 指定的设备"
    else say "      注意: binding.json 没有绑定 E2E_USB_SERIAL 指定的设备"; fi
  fi
  if ! fake; then
    skip R9 "真机上只记录通道；拔 USB / 关无线调试是改手机系统设置，由用户协调，脚本不做（NOT_RUN）"
  else
    before=$(loglines)
    fake_behaviors '[{"match":"uitest dumpLayout","out":"Connect server failed\n"}]'
    api POST /v1/observe '{}'; off_s=$STATUS; off_c=$(jv error.code)
    off_state=$("$CLI" target show 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{console.log(JSON.parse(s).state)}catch{}})')
    fake_behaviors '[]'
    "$CLI" target set "${E2E_DUMMY_TCP:-10.255.255.254:5555}" >/dev/null 2>&1
    api POST /v1/observe '{}'; mid_s=$STATUS
    "$CLI" target auto >/dev/null 2>&1
    sleep 1
    observe_ok; back=$?
    logq "$before" kind=ctl op=target | showlog
    logq "$before" kind=read ok=false | showlog
    tl=$(logq "$before" kind=ctl op=target | wc -l | tr -d ' ')
    if [ "$off_s" != 503 ] || [ "$off_c" != device_offline ]; then fail R9 "hdc 回 Connect server failed 时 observe 应 503 device_offline，实际 $off_s $off_c"
    elif [ "$off_state" != offline ]; then fail R9 "掉线后 target show 的 state 应为 offline，实际 $off_state"
    elif [ "$tl" -lt 2 ]; then fail R9 "日志里 ctl,op:target 行只有 $tl 条（set 与 auto 各一条）"
    elif [ "$back" != 0 ]; then fail R9 "恢复后 observe 未成功 (HTTP $STATUS $(jv error.code))"
    else pass R9 "离线 503 device_offline；target set/auto 各有 ctl,op:target 行；恢复后读屏成功（假设备，通道切换为模拟）"; fi
    say "      中间态（设了不可达的无线地址）observe HTTP $mid_s"
  fi
fi

# ---------- R10 ----------
if sel R10 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R10 "命令注入（设备显示原文为人工；此处断言设备端 argv 与日志脱敏）"
  if ! fake && [ "${E2E_ALLOW_TYPE:-0}" != 1 ]; then
    skip R10 "真机上会把文字打进当前焦点输入框，默认不跑；先在备忘录里点进输入框，再设 E2E_ALLOW_TYPE=1"
  else
    before=$(loglines)
    payload=$(node "$HERE/payload.mjs" inject)
    canary_text=$(node "$HERE/payload.mjs" canary "$WORK")
    fake && rm -f "$E2E_FAKE_DIR/uitest.log"
    api POST /v1/act "$payload"; s1=$STATUS
    fake && api POST /v1/act "$canary_text"
    api POST /v1/act '{"op":"text","text":"line1\nline2"}'; s_ctrl=$STATUS
    line=$(logq "$before" kind=act op=text | head -1); [ -n "$line" ] && printf '%s\n' "$line" | showlog
    verdict=ok
    [ "$s1" = 200 ] || verdict="act HTTP $s1 $(jv error.code)"
    [ "$s_ctrl" = 422 ] || verdict="控制字符文本应 422，实际 $s_ctrl"
    printf '%s' "$line" | grep -q '<text:' || verdict="日志没有 <text:N chars> 占位"
    printf '%s' "$line" | grep -qF '$(id)' && verdict="日志里出现了原始文本"
    if fake && [ "$verdict" = ok ]; then
      want=$(node "$HERE/payload.mjs" argv)
      first=$(head -1 "$E2E_FAKE_DIR/uitest.log" 2>/dev/null)
      [ "$first" = "$want" ] || verdict="设备端 argv 与原文不一致: $first"
      { [ -e "$WORK/canary" ] || [ -e "$WORK/canary2" ] || [ -e "$WORK/canary3" ]; } && verdict="注入的 touch 被执行了"
    fi
    if [ "$verdict" = ok ]; then pass R10 "含引号与 \$() 的文本以单个原样 argv 到达设备端；日志只有字数；控制字符被 422 拒绝；注入命令未执行"
    else fail R10 "$verdict"; fi
  fi
fi

# ---------- R11（必须最后跑：会把来源地址封 60 秒） ----------
if sel R11 && [ "$NEED_TOKEN" = 1 ]; then
  head_ R11 "鉴权与限速（来源地址 $E2E_LAN_HOST；真正的「另一台设备」请再从手机浏览器或第二台电脑补一次）"
  before=$(loglines)
  codes=""
  for i in 1 2 3 4 5 6; do
    c=$(curl -sk --tls-max 1.2 --max-time 10 -o "$WORK/r11-$i.json" -w '%{http_code}' -X POST -H 'x-dsh-relay: 1' "https://$E2E_LAN_HOST:$DSH_RELAY_PORT/v1/observe" 2>/dev/null)
    codes="$codes $c"
  done
  nohdr=$(curl -sk --tls-max 1.2 --max-time 10 -o /dev/null -w '%{http_code}' -X POST "https://$E2E_LAN_HOST:$DSH_RELAY_PORT/v1/observe" 2>/dev/null)
  logq "$before" op=auth_fail | head -2 | showlog
  nfail=$(logq "$before" kind=auth op=auth_fail | wc -l | tr -d ' ')
  with_ip=$(logq "$before" kind=auth op=auth_fail has:ip | wc -l | tr -d ' ')
  src=$(logq "$before" op=auth_fail | head -1 | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{console.log(JSON.parse(s).ip)}catch{}})')
  say "      六次状态码:$codes ；不带 X-Dsh-Relay 头: $nohdr ；日志记录的来源: ${src:-?}"
  want_codes=" 401 401 401 401 401 429"
  if [ "$codes" != "$want_codes" ]; then fail R11 "状态码序列应为$want_codes，实际$codes"
  elif [ "$nfail" != 5 ] || [ "$with_ip" != 5 ]; then fail R11 "auth_fail 行应为 5 条且都带 ip，实际 $nfail / $with_ip"
  elif [ "$nohdr" != 403 ] && [ "$nohdr" != 429 ]; then fail R11 "不带 X-Dsh-Relay 头应 403 forbidden_source（被封时 429），实际 $nohdr"
  else
    if [ "${E2E_FAST:-0}" = 1 ]; then pass R11 "未带令牌 401×5 后第 6 次 429；auth_fail 带来源 IP（未验证 60 秒后解封，E2E_FAST=1）"
    else
      say "      等 61 秒验证解封…"; sleep 61
      if observe_ok; then pass R11 "未带令牌 401×5 后第 6 次 429；auth_fail 带来源 IP；61 秒后解封，合法令牌恢复"
      else fail R11 "61 秒后仍被封或读屏失败 (HTTP $STATUS)"; fi
    fi
  fi
fi

# ---------- 令牌泄露扫描 ----------
if [ "$NEED_TOKEN" = 1 ] && [ -s "$TOKEN_FILE" ]; then
  head_ LEAK "令牌不得出现在 relay.log"
  if grep -qF "$(tr -d '\n' <"$TOKEN_FILE")" "$LOG" 2>/dev/null; then fail LEAK "令牌明文出现在 relay.log"
  else pass LEAK "relay.log 中无令牌明文"; fi
fi

say ""
say "=== 汇总: PASS=$NPASS FAIL=$NFAIL SKIP=$NSKIP"
printf '%b' "$RESULTS" | sed 's/^/    /'
[ "$NFAIL" = 0 ]
