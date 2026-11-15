#!/bin/bash
# 经文件管理器 + 卓易通安装器装探针 APK。用法：HDC_TARGET=<usb序列号> scripts/install.sh
set -e
cd "$(dirname "$0")"
T=${HDC_TARGET:?设置 HDC_TARGET}
HDC=~/.local/bin/hdc
APK=../app/build/outputs/apk/release/app-release.apk
NAME=zytprobe.apk
$HDC -t $T file send $APK /storage/media/100/local/files/Docs/Download/$NAME
$HDC -t $T shell aa start -b com.huawei.hmos.filemanager -a MainAbility
sleep 3
python3 uitest_tap.py tap '^我的手机$' '^(Download|下载)$' "^$NAME$" '^继续安装$'
