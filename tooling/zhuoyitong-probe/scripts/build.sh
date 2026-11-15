#!/bin/bash
# 构建探针 APK；产物 app/build/outputs/apk/release/app-release.apk
set -e
cd "$(dirname "$0")/.."
source ../env-mac.sh
./gradlew --no-daemon :app:assembleRelease "$@"
ls -l app/build/outputs/apk/release/*.apk
