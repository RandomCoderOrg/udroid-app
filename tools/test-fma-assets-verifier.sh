#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="${ROOT}/app/src/main/assets/runtime"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/udroid-fma-verifier.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT

cp -R "${SOURCE}/." "${WORK}/"
FMA_ASSETS_DIR="${WORK}" "${ROOT}/tools/verify-fma-assets.sh" >/dev/null

printf 'mixed-generation-fixture' >> "${WORK}/x86_64/fma_drv_video.so"
if FMA_ASSETS_DIR="${WORK}" "${ROOT}/tools/verify-fma-assets.sh" \
    >/dev/null 2>&1; then
  echo "Verifier accepted an asset whose manifest hash does not match" >&2
  exit 1
fi

cp "${SOURCE}/x86_64/fma_drv_video.so" "${WORK}/x86_64/fma_drv_video.so"
case "$(uname -s)" in
  Darwin) NDK_HOST=darwin-x86_64 ;;
  Linux) NDK_HOST=linux-x86_64 ;;
  *) echo "Unsupported NDK host" >&2; exit 1 ;;
esac
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/Library/Android/sdk}}"
OBJCOPY="${OBJCOPY:-${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT}/ndk/28.2.13676358}/toolchains/llvm/prebuilt/${NDK_HOST}/bin/llvm-objcopy}"
printf '%s\0' '0000000000000000000000000000000000000000' > "${WORK}/stale-revision"
"${OBJCOPY}" --update-section ".fma_source=${WORK}/stale-revision" \
  "${WORK}/x86_64/fma_drv_video.so"
if command -v sha256sum >/dev/null 2>&1; then
  driver_hash="$(sha256sum "${WORK}/x86_64/fma_drv_video.so" | awk '{print $1}')"
else
  driver_hash="$(shasum -a 256 "${WORK}/x86_64/fma_drv_video.so" | awk '{print $1}')"
fi
awk -v hash="${driver_hash}" '
  $2 == "x86_64/fma_drv_video.so" { $1 = hash }
  { print }
' "${WORK}/fma-assets.manifest" > "${WORK}/fma-assets.manifest.next"
mv "${WORK}/fma-assets.manifest.next" "${WORK}/fma-assets.manifest"
if FMA_ASSETS_DIR="${WORK}" "${ROOT}/tools/verify-fma-assets.sh" \
    >/dev/null 2>&1; then
  echo "Verifier accepted a manifest-consistent binary from another source revision" >&2
  exit 1
fi

echo "FMA asset verifier rejects mixed or modified payloads"
