#!/bin/sh
set -eu

RUNTIME=${UDROID_WAYLAND_RUNTIME:-/root/udroid-gfxstream-wayland-async-20260911}
ICD=${UDROID_WAYLAND_ICD:-$RUNTIME/gfxstream-wayland-async-icd.json}

test -r "$ICD"
test -r "$RUNTIME/lib/libvulkan_gfxstream.so"

FRAMES=${UDROID_VKCUBE_FRAMES:-600}
if [ "${UDROID_WAYLAND_DEBUG:-0}" = 1 ]; then
	export WAYLAND_DEBUG=client
fi

export VIRTGPU_KUMQUAT=1
export VK_DRIVER_FILES="$ICD"
export VK_ICD_FILENAMES="$ICD"
export LD_LIBRARY_PATH="$RUNTIME/lib:/opt/udroid/gfxstream/lib:/usr/lib/aarch64-linux-gnu"

START_NS=$(date +%s%N)
set +e
/usr/bin/vkcube \
	--wsi wayland \
	--present_mode 2 \
	--width 640 \
	--height 640 \
	--c "$FRAMES" \
	--display_timing
STATUS=$?
set -e
END_NS=$(date +%s%N)
ELAPSED_NS=$((END_NS - START_NS))

printf 'UDROID_VKCUBE_RESULT status=%s frames=%s elapsed_ns=%s\n' \
	"$STATUS" "$FRAMES" "$ELAPSED_NS"
exit "$STATUS"
