#!/bin/bash
# 可靠截屏：hdc 偶发 "Connect server failed" 时旧图会被误收，所以先删远端旧文件、成功才收。用法 snap.sh <名>（存 results/p3/<名>.jpeg）
T=${HDC_TARGET:-${HDC_USB_SERIAL:?请设置 HDC_TARGET 或 HDC_USB_SERIAL}}; H="$HOME/.local/bin/hdc -t $T"
R=/data/local/tmp/snap-$$.jpeg
for i in 1 2 3 4 5; do
  out=$($H shell "rm -f $R; snapshot_display -f $R" 2>&1)
  if grep -q 'success' <<<"$out" && $H file recv $R "$(dirname "$0")/../results/p3/$1.jpeg" 2>&1 | grep -q finish; then echo "$(dirname "$0")/../results/p3/$1.jpeg"; exit 0; fi
  sleep 1
done
echo "截屏失败" >&2; exit 1
