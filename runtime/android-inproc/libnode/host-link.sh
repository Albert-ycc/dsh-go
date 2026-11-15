#!/usr/bin/env bash
# macOS 宿主链接器包装：gyp 的 make 生成器按 GNU ld 写 -Wl,--start-group / --end-group，
# Apple ld 不认。macOS 的静态库本来就不依赖顺序，直接丢掉这两个参数。
args=()
for a in "$@"; do
  case "$a" in
    -Wl,--start-group|-Wl,--end-group) ;;
    *) args+=("$a") ;;
  esac
done
# V8 的 abseil 在 macOS 上用 CoreFoundation 取时区
exec /usr/bin/clang++ "${args[@]}" -framework CoreFoundation
