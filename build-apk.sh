#!/usr/bin/env bash
# SmartTodo 一键打包脚本
# 用法：
#   ./build-apk.sh              # 打 debug 包（默认，无需签名）
#   ./build-apk.sh release      # 打 release 包（有 keystore 用正式签名，否则用 debug 签名）
#   ./build-apk.sh test         # 只跑单元测试，不打 APK
#   ./build-apk.sh all          # 跑测试 + 同时打 debug / release
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# 1. 定位 Android SDK：优先 ANDROID_HOME / ANDROID_SDK_ROOT，其次常见路径
SDK=""
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
  SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
  SDK="$ANDROID_SDK_ROOT"
elif [ -d "$HOME/Android/Sdk" ]; then
  SDK="$HOME/Android/Sdk"
elif [ -d "/opt/android-sdk" ]; then
  SDK="/opt/android-sdk"
fi

if [ -z "$SDK" ]; then
  echo "✗ 未找到 Android SDK。请安装后设置 ANDROID_HOME，或创建 local.properties 写入 sdk.dir=/你的/sdk/路径"
  exit 1
fi
echo "✓ Android SDK: $SDK"
echo "sdk.dir=$SDK" > local.properties

MODE="${1:-debug}"

run_tests() {
  echo "▶ 运行单元测试..."
  ./gradlew testDebugUnitTest --no-daemon
  echo "✓ 测试通过"
}

build_apk() {
  local variant="$1"
  echo "▶ 打包 $variant 版..."
  if [ "$variant" = "release" ]; then
    ./gradlew assembleRelease --no-daemon
  else
    ./gradlew assembleDebug --no-daemon
  fi
  echo "✓ 产物："
  find app/build/outputs/apk -name "*.apk" -type f -printf '  %p  (%k KB)\n' 2>/dev/null \
    || find app/build/outputs/apk -name "*.apk" -type f -exec ls -lh {} \;
}

case "$MODE" in
  test) run_tests ;;
  debug) build_apk debug ;;
  release) build_apk release ;;
  all) run_tests; build_apk debug; build_apk release ;;
  *) echo "用法: $0 [debug|release|test|all]"; exit 1 ;;
esac
