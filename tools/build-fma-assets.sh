#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FMA_SOURCE="${FMA_SOURCE:-${ROOT}/../fake-media-accel}"
FMA_COMMIT="$(tr -d '\r\n' < "${ROOT}/tools/fma-source.lock")"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/Library/Android/sdk}}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT}/ndk/28.2.13676358}"
CMAKE_BIN="${CMAKE_BIN:-${ANDROID_SDK_ROOT}/cmake/3.22.1/bin/cmake}"
OUT="${ROOT}/app/src/main/assets/runtime"
WORK="${TMPDIR:-/tmp}/udroid-fma-assets"
FMA_VA_BUILD_MODE="${FMA_VA_BUILD_MODE:-docker}"

test -x "${CMAKE_BIN}"
test -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
printf '%s\n' "${FMA_COMMIT}" | grep -Eq '^[0-9a-f]{40}$'
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
LLVM_OBJCOPY="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${NDK_HOST}/bin/llvm-objcopy"
PROVENANCE_FILE="${WORK}/fma-source-revision"
printf '%s\0' "${FMA_COMMIT}" > "${PROVENANCE_FILE}"

stamp_source_revision() {
  local binary="$1"
  "${LLVM_OBJCOPY}" \
    --add-section ".fma_source=${PROVENANCE_FILE}" \
    --set-section-flags .fma_source=readonly \
    "${binary}"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

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
  stamp_source_revision "${build}/fake-media-acceld"
  install -m 0755 "${build}/fake-media-acceld" "${OUT}/${abi}/fake-media-acceld"
}

build_va_driver_docker() {
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
  stamp_source_revision "${output}/fma_drv_video.so"
  install -m 0644 "${output}/fma_drv_video.so" \
    "${OUT}/${abi}/fma_drv_video.so"
}

build_va_driver_zig() {
  local target="$1"
  local abi="$2"
  local output="${WORK}/linux-${abi}/fma_drv_video.so"
  local zig_bin="${ZIG_BIN:-$(command -v zig || true)}"
  local include_root="${FMA_LIBVA_INCLUDE:-}"
  local sources=(
    src/va/fma_drv_video.c
    src/va/h264_annexb.c
    src/va/h264_timing.c
    src/client/client.c
    src/common/av1_obu.c
    src/common/h264_stream.c
    src/common/ivf.c
    src/common/protocol.c
    src/common/transport.c
  )

  test -n "${zig_bin}"
  test -x "${zig_bin}"
  test -f "${include_root}/va/va.h"
  mkdir -p "$(dirname "${output}")"
  (
    cd "${FMA_SOURCE}"
    "${zig_bin}" cc -target "${target}" -shared -fPIC -O2 -std=gnu11 \
      -Wall -Wextra -Wpedantic -Wno-c23-extensions -pthread \
      -Iinclude -Isrc/va -I"${include_root}" \
      "${sources[@]}" -Wl,-soname,fma_drv_video.so -o "${output}"
  )
  "${LLVM_STRIP}" "${output}"
  stamp_source_revision "${output}"
  install -m 0644 "${output}" "${OUT}/${abi}/fma_drv_video.so"
}

write_manifest() {
  local manifest="${OUT}/fma-assets.manifest"
  local asset
  {
    printf 'format=1\n'
    printf 'source_revision=%s\n' "${FMA_COMMIT}"
    for asset in \
      arm64-v8a/fake-media-acceld \
      arm64-v8a/fma_drv_video.so \
      armeabi-v7a/fake-media-acceld \
      armeabi-v7a/fma_drv_video.so \
      x86_64/fake-media-acceld \
      x86_64/fma_drv_video.so; do
      printf '%s  %s\n' "$(sha256_file "${OUT}/${asset}")" "${asset}"
    done
  } > "${manifest}"
}

for abi in arm64-v8a armeabi-v7a x86_64; do
  build_android_daemon "${abi}"
done

case "${FMA_VA_BUILD_MODE}" in
  docker)
    build_va_driver_docker linux/arm64 arm64-v8a
    build_va_driver_docker linux/arm/v7 armeabi-v7a
    build_va_driver_docker linux/amd64 x86_64
    ;;
  zig)
    build_va_driver_zig aarch64-linux-gnu.2.35 arm64-v8a
    build_va_driver_zig arm-linux-gnueabihf.2.35 armeabi-v7a
    build_va_driver_zig x86_64-linux-gnu.2.35 x86_64
    ;;
  *)
    echo "Unsupported FMA_VA_BUILD_MODE: ${FMA_VA_BUILD_MODE}" >&2
    exit 1
    ;;
esac

write_manifest
"${ROOT}/tools/verify-fma-assets.sh"
