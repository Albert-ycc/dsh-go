#!/bin/sh
# 渲染 launchd 作业并装入。标签优先级：--label > 环境变量 DSH_RELAY_LABEL > local.dsh-relay（与 bin/dsh-relay 同一个默认）。
set -eu
LABEL=${DSH_RELAY_LABEL:-local.dsh-relay}
[ "${1:-}" = "--label" ] && LABEL="${2:?--label needs a value}"
ROOT=$(cd "$(dirname "$0")" && pwd)
NODE=${NODE:-$(command -v node || true)}
[ -n "$NODE" ] && [ -x "$NODE" ] || { echo "install.sh: 找不到 node，设置 NODE=/path/to/node 后重试" >&2; exit 1; }
HDC=${HDC_BIN:-$HOME/.local/bin/hdc}
[ -x "$HDC" ] || echo "install.sh: 警告 $HDC 不可执行，服务会起来但读屏会失败；用 HDC_BIN=/path/to/hdc 重新安装" >&2
OUT="$HOME/Library/LaunchAgents/$LABEL.plist"
mkdir -p "$HOME/Library/Logs/dsh-relay" "$HOME/Library/LaunchAgents"
sed -e "s#{{LABEL}}#$LABEL#g; s#{{NODE}}#$NODE#g; s#{{ROOT}}#$ROOT#g; s#{{HDC}}#$HDC#g; s#{{HDC_DIR}}#$(dirname "$HDC")#g; s#{{HOME}}#$HOME#g" "$ROOT/launchd/plist.template" > "$OUT"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$OUT"
echo "installed $OUT (label $LABEL, node $NODE)"
