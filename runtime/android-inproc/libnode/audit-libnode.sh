#!/usr/bin/env bash
# 产物验收：对应 P0A 3.3「产物验收」与 T1 任务书的判定标准。用法：audit-libnode.sh <libnode.so>
set -u
SO="${1:?usage: audit-libnode.sh <file> [exe|shared]}"
FORM="${2:-shared}"
NDK="${ANDROID_NDK_HOME:-$HOME/dev/android-sdk/ndk/28.2.13676358}"
case "$(uname -s)" in Darwin) H=darwin-x86_64 ;; *) H=linux-x86_64 ;; esac
B="$NDK/toolchains/llvm/prebuilt/$H/bin"
fail=0
chk() { if [ "$2" = ok ]; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi; }

echo "== file"; file "$SO"
file "$SO" | grep -q 'ELF 64-bit LSB \(shared object\|pie executable\), ARM aarch64' && chk "ELF aarch64 (shared object / PIE exe)" ok || chk "ELF aarch64" no
echo "== size"; ls -l "$SO"
echo "== readelf -h"; "$B/llvm-readelf" -h "$SO"
echo "== readelf -d"; "$B/llvm-readelf" -d "$SO"
if [ "$FORM" = shared ]; then "$B/llvm-readelf" -d "$SO" | grep -q 'SONAME' && chk "has SONAME" ok || chk "has SONAME" no; fi
needed="$("$B/llvm-readelf" -d "$SO" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort)"
echo "NEEDED: $(echo $needed)"
bad="$(echo "$needed" | grep -vE '^(libc\.so|libm\.so|libdl\.so|liblog\.so)$' || true)"
[ -z "$bad" ] && chk "NEEDED only bionic libc/libm/libdl/liblog" ok || { chk "NEEDED only bionic libs (extra: $bad)" no; }
echo "== program headers"; "$B/llvm-readelf" -l "$SO" | sed -n '/Program Headers/,/Section to Segment/p'
"$B/llvm-readelf" -l "$SO" | grep 'GNU_STACK' | grep -qE 'RW ' && ! "$B/llvm-readelf" -l "$SO" | grep 'GNU_STACK' | grep -q 'E ' && chk "GNU_STACK present, not executable" ok || chk "GNU_STACK present, not executable" no
if [ "$FORM" = exe ]; then
  "$B/llvm-readelf" -l "$SO" | grep -q 'Requesting program interpreter: /system/bin/linker64' && chk "PT_INTERP=/system/bin/linker64" ok || chk "PT_INTERP=/system/bin/linker64" no
  "$B/llvm-readelf" -h "$SO" | grep -qE 'Type:.*(EXEC|DYN)' && chk "Type EXEC/DYN" ok || chk "Type EXEC/DYN" no
else
  "$B/llvm-readelf" -l "$SO" | grep -q INTERP && chk "no PT_INTERP" no || chk "no PT_INTERP" ok
fi
echo "== LOAD alignment"; "$B/llvm-readelf" -l "$SO" | grep LOAD
echo "== attributes (-A)"; "$B/llvm-readelf" -A "$SO"
echo "== glibc version refs"
if "$B/llvm-readelf" -V "$SO" 2>/dev/null | grep -q GLIBC || "$B/llvm-readelf" --dyn-syms "$SO" | grep -q GLIBC; then chk "no GLIBC symbol versions" no; else chk "no GLIBC symbol versions" ok; fi
echo "== exported symbols"
"$B/llvm-nm" -D --defined-only "$SO" > /tmp/.libnode-exports.$$ 2>/dev/null
echo "total defined dynamic symbols: $(wc -l < /tmp/.libnode-exports.$$)"
for s in napi_module_register napi_create_function \
         _ZN4node24InitializeOncePerProcessEiPPcNS_ProcessInitializationFlags5FlagsE \
         _ZN4node4StartEiPPc \
         _ZN4node21CommonEnvironmentSetup6CreateEPNS_20MultiIsolatePlatformEPNSt3__16vectorINS3_12basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEENS7_ISA_EEEERKNS_21EmbedderSnapshotDataENS_17EnvironmentFlags5FlagsEmNS3_8functionIFvPNS_11EnvironmentEEEE; do :; done
want_demangled=(
 "napi_module_register" "napi_create_function"
 "node::Start(int, char**)"
 "node::InitializeOncePerProcess("
 "node::CommonEnvironmentSetup::CommonEnvironmentSetup("
 "node::LoadEnvironment("
 "node::SpinEventLoop("
 "node::Stop("
 "node::SetProcessExitHandler("
 "node::MultiIsolatePlatform::Create("
 "node::PrincipalRealm::builtin_module_require"
 "v8::V8::GetVersion"
 "v8::Isolate::GetCurrent()"
 "v8::Isolate::GetCurrentContext"
 "v8::Context::GetNumberOfEmbedderDataFields"
 "v8::Context::SlowGetAlignedPointerFromEmbedderData"
)
"$B/llvm-nm" -D --defined-only -C "$SO" > /tmp/.libnode-exports-dem.$$ 2>/dev/null
for w in "${want_demangled[@]}"; do
  n=$(grep -cF -- "$w" /tmp/.libnode-exports-dem.$$)
  if [ "$n" -gt 0 ]; then chk "export: $w ($n)" ok; else chk "export: $w" no; fi
done
rm -f /tmp/.libnode-exports.$$ /tmp/.libnode-exports-dem.$$
echo "== result"; [ "$fail" = 0 ] && echo "ALL PASS" || echo "SOME FAILED"
exit $fail
