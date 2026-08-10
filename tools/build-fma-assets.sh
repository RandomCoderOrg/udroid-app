#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FMA_SOURCE="${FMA_SOURCE:-${ROOT}/../fake-media-accel}"
FMA_COMMIT="1da72812c411b67f76d8c20a093cb0ff54760251"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/Library/Android/sdk}}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT}/ndk/28.2.13676358}"
CMAKE_BIN="${CMAKE_BIN:-${ANDROID_SDK_ROOT}/cmake/3.22.1/bin/cmake}"
OUT="${ROOT}/app/src/main/assets/runtime"
WORK="${TMPDIR:-/tmp}/udroid-fma-assets"

test -x "${CMAKE_BIN}"
test -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
test "$(git -C "${FMA_SOURCE}" rev-parse HEAD)" = "${FMA_COMMIT}"
git -C "${FMA_SOURCE}" diff --quiet
git -C "${FMA_SOURCE}" diff --cached --quiet

rm -rf "${WORK}"
mkdir -p "${WORK}"

case "$(uname -s)" in
  Darwin) NDK_HOST=darwin-x86_64 ;;
  Linux) NDK_HOST=linux-x86_64 ;;
  *) echo "Unsupported NDK host" >&2; exit 1 ;;
esac
LLVM_STRIP="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST}/bin/llvm-strip"

build_android_daemon() {
  local abi="$1"
  local build="${WORK}/android-${abi}"
  "${CMAKE_BIN}" -S "${FMA_SOURCE}" -B "${build}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM=26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF
  "${CMAKE_BIN}" --build "${build}" --target fake-media-acceld --parallel
  "${LLVM_STRIP}" "${build}/fake-media-acceld"
  install -m 0755 "${build}/fake-media-acceld" "${OUT}/${abi}/fake-media-acceld"
}

build_va_driver() {
  local platform="$1"
  local abi="$2"
  local output="${WORK}/linux-${abi}"
  mkdir -p "${output}"
  docker run --rm --platform "${platform}" \
    -v "${FMA_SOURCE}:/src:ro" \
    -v "${output}:/out" \
    ubuntu:22.04 bash -euc '
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -qq
      apt-get install -y --no-install-recommends \
        build-essential ca-certificates cmake libva-dev pkg-config
      cmake -S /src -B /tmp/build \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF
      cmake --build /tmp/build --target fma-va-driver --parallel
      strip /tmp/build/fma_drv_video.so
      install -m 0644 /tmp/build/fma_drv_video.so /out/fma_drv_video.so
    '
  install -m 0644 "${output}/fma_drv_video.so" \
    "${OUT}/${abi}/fma_drv_video.so"
}

for abi in arm64-v8a armeabi-v7a x86_64; do
  build_android_daemon "${abi}"
done

build_va_driver linux/arm64 arm64-v8a
build_va_driver linux/arm/v7 armeabi-v7a
build_va_driver linux/amd64 x86_64

"${ROOT}/tools/verify-fma-assets.sh"
