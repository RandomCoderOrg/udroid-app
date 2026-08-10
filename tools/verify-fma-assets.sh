#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="${ROOT}/app/src/main/assets/runtime"
if command -v readelf >/dev/null 2>&1; then
  READELF="$(command -v readelf)"
else
  ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/Library/Android/sdk}}"
  case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;
    Linux) NDK_HOST=linux-x86_64 ;;
    *) echo "Unsupported NDK host" >&2; exit 1 ;;
  esac
  READELF="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT}/ndk/28.2.13676358}/toolchains/llvm/prebuilt/${NDK_HOST}/bin/llvm-readelf"
fi
test -x "${READELF}"

verify_pair() {
  local abi="$1"
  local machine_pattern="$2"
  local daemon="${ASSETS}/${abi}/fake-media-acceld"
  local driver="${ASSETS}/${abi}/fma_drv_video.so"

  test -x "${daemon}"
  test -s "${driver}"
  file "${daemon}" | grep -E "${machine_pattern}" >/dev/null
  file "${driver}" | grep -E "${machine_pattern}" >/dev/null
  file "${daemon}" | grep -F "interpreter /system/bin/linker" >/dev/null
  file "${driver}" | grep -F "shared object" >/dev/null
  if "${READELF}" -d "${driver}" | grep -E 'Shared library: \[(libva|libdrm|libEGL|libGLES)' >/dev/null; then
    echo "${abi} VA driver unexpectedly links a graphics stack" >&2
    exit 1
  fi
}

verify_pair arm64-v8a 'ARM aarch64'
verify_pair armeabi-v7a 'ARM, EABI5'
verify_pair x86_64 'x86-64'

echo "Verified GPU-independent FMA assets for arm64-v8a, armeabi-v7a and x86_64"
