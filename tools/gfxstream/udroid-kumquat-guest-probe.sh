#!/bin/sh
set -eu

archive=/tmp/udroid-gfxstream-guest-probe.tar.gz
work=/tmp/udroid-gfxstream-probe
socket=/tmp/kumquat-gpu-0

test -S "$socket" || {
    echo "[fail] Kumquat guest socket is not bound at $socket" >&2
    exit 2
}
test -r "$archive" || {
    echo "[fail] Probe archive is not readable at $archive" >&2
    exit 2
}

rm -rf "$work"
mkdir -p "$work"
tar -xzf "$archive" -C "$work"

echo "[probe] guest=$(uname -m) socket=$socket"
echo "[probe] icd=$work/libvulkan_gfxstream.so"
MESA_DEBUG=1 \
MESA_LOG_LEVEL=debug \
VIRTGPU_KUMQUAT=1 \
LD_LIBRARY_PATH="$work" \
    "$work/udroid_kumquat_present_probe" \
    "$work/libvulkan_gfxstream.so"
