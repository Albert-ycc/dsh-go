#!/bin/bash
# 用 testkit 的假 hdc 起临时中继实例，用来不碰真机地演练 run-e2e.sh。
#   fake-instance.sh start [root]   起实例，打印 env.sh 路径；之后 `source <root>/env.sh && bash run-e2e.sh`
#   fake-instance.sh stop <root>    停实例并删目录
# 端口默认 38731（FAKE_PORT 可改），HOME 与日志目录都在 root 下，不碰 ~/.dsh-relay。
HERE=$(cd "$(dirname "$0")" && pwd)
KIT="$HERE/../testkit"
FIX="$HERE/../contract/fixtures"
case "${1:-}" in
stop)
  ROOT=${2:?root}
  [ -f "$ROOT/relay.pid" ] && kill "$(cat "$ROOT/relay.pid")" 2>/dev/null
  rm -rf "$ROOT"
  echo "stopped, removed $ROOT"
  ;;
start)
  ROOT=${2:-$(mktemp -d "${TMPDIR:-/tmp}/dsh-relay-e2e.XXXXXX")}
  PORT=${FAKE_PORT:-38731}
  FAKE="$ROOT/fake"
  mkdir -p "$FAKE/bin" "$FAKE/dev" "$ROOT/home" "$ROOT/log"
  cp "$KIT/fake-uitest.mjs" "$FAKE/bin/uitest" && chmod 755 "$FAKE/bin/uitest"
  printf 'FAKE-USB-0001\tUSB\tConnected\tlocalhost\n' >"$FAKE/targets.txt"
  cp "$FIX/synthetic-settings.json" "$FAKE/dump.json"
  echo '[]' >"$FAKE/behaviors.json"
  echo '{"mainEntry":"phone","entryModuleName":"phone","hapModuleInfos":[{"moduleName":"phone","mainAbility":"com.example.MainAbility","abilityInfos":[{"name":"MainAbility"}]}]}' >"$FAKE/bm-n.json"
  cat >"$ROOT/env.sh" <<ENV
export HDC_BIN="$KIT/fake-hdc.mjs"
export FAKE_HDC_DIR="$FAKE"
export DSH_RELAY_HOME="$ROOT/home"
export DSH_RELAY_PORT=$PORT
export DSH_RELAY_LOG_DIR="$ROOT/log"
export E2E_FAKE_DIR="$FAKE"
export E2E_RELAY_PID_FILE="$ROOT/relay.pid"
export E2E_RELAY_START_CMD="bash $HERE/start-relay.sh $ROOT/relay.pid $ROOT/relay.stdout"
ENV
  # shellcheck disable=SC1091
  . "$ROOT/env.sh"
  bash "$HERE/start-relay.sh" "$ROOT/relay.pid" "$ROOT/relay.stdout"
  for _ in $(seq 1 50); do
    curl -sk --tls-max 1.2 "https://127.0.0.1:$PORT/v1/ping" >/dev/null 2>&1 && break
    sleep 0.2
  done
  echo "$ROOT/env.sh"
  ;;
*)
  echo "usage: fake-instance.sh start [root] | stop <root>" >&2
  exit 2
  ;;
esac
