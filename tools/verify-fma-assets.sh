#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="${FMA_ASSETS_DIR:-${ROOT}/app/src/main/assets/runtime}"
MANIFEST="${ASSETS}/fma-assets.manifest"
SOURCE_LOCK="${FMA_SOURCE_LOCK:-${ROOT}/tools/fma-source.lock}"
EXPECTED_REVISION="$(tr -d '\r\n' < "${SOURCE_LOCK}")"
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
printf '%s\n' "${EXPECTED_REVISION}" | grep -Eq '^[0-9a-f]{40}$'

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

test -f "${MANIFEST}"
test "$(wc -l < "${MANIFEST}" | tr -d ' ')" -eq 8
test "$(grep -c '^format=1$' "${MANIFEST}")" -eq 1
test "$(grep -c '^source_revision=' "${MANIFEST}")" -eq 1
test "$(sed -n 's/^source_revision=//p' "${MANIFEST}")" = "${EXPECTED_REVISION}"

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
  "${READELF}" -Ws "${driver}" | grep -F '__vaDriverInit_1_14' >/dev/null
  if "${READELF}" -d "${driver}" | grep -E 'Shared library: \[(libva|libdrm|libEGL|libGLES)' >/dev/null; then
    echo "${abi} VA driver unexpectedly links a graphics stack" >&2
    exit 1
  fi

  verify_revision "${abi}/fake-media-acceld"
  verify_revision "${abi}/fma_drv_video.so"

  verify_hash "${abi}/fake-media-acceld"
  verify_hash "${abi}/fma_drv_video.so"
}

verify_revision() {
  local relative="$1"
  local embedded
  embedded="$("${READELF}" -p .fma_source "${ASSETS}/${relative}" 2>/dev/null |
    awk '/^[[]/ { print $3 }')"
  if test "${embedded}" != "${EXPECTED_REVISION}"; then
    echo "${relative} was not built from FMA ${EXPECTED_REVISION}" >&2
    exit 1
  fi
}

verify_hash() {
  local relative="$1"
  local expected
  local count
  count="$(awk -v path="${relative}" '$2 == path { count++ } END { print count + 0 }' "${MANIFEST}")"
  test "${count}" -eq 1
  expected="$(awk -v path="${relative}" '$2 == path { print $1 }' "${MANIFEST}")"
  printf '%s\n' "${expected}" | grep -Eq '^[0-9a-f]{64}$'
  test "$(sha256_file "${ASSETS}/${relative}")" = "${expected}"
}

verify_pair arm64-v8a 'ARM aarch64'
verify_pair armeabi-v7a 'ARM, EABI5'
verify_pair x86_64 'x86-64'
test "$(awk 'NF == 2 && $1 ~ /^[0-9a-f]{64}$/ { count++ } END { print count + 0 }' "${MANIFEST}")" -eq 6

echo "Verified FMA ${EXPECTED_REVISION} assets for arm64-v8a, armeabi-v7a and x86_64"
