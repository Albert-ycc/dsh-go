#!/bin/bash
# 在后台起一个前台形态的中继进程（不碰 launchd），pid 写入 $1。
# 读环境变量：HDC_BIN DSH_RELAY_HOME DSH_RELAY_PORT DSH_RELAY_LOG_DIR（以及假 hdc 用的 FAKE_HDC_DIR）。
# 用法：start-relay.sh <pidfile> <stdout-log>
PIDFILE=${1:?pidfile}
OUT=${2:?stdout log}
HERE=$(cd "$(dirname "$0")" && pwd)
nohup node "$HERE/../server.mjs" >>"$OUT" 2>&1 &
echo $! >"$PIDFILE"
