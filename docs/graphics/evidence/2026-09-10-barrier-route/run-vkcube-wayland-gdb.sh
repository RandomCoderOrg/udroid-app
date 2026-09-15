#!/bin/sh
set -eu

RUNTIME=${UDROID_WAYLAND_RUNTIME:-/root/udroid-gfxstream-wayland-format-20260912}
ICD=${UDROID_WAYLAND_ICD:-/root/gfxstream-wayland-format-icd.json}
FRAMES=${UDROID_VKCUBE_FRAMES:-1}

exec gdb -q -batch \
	-ex 'set pagination off' \
	-ex 'set environment VIRTGPU_KUMQUAT 1' \
	-ex "set environment VK_DRIVER_FILES $ICD" \
	-ex "set environment VK_ICD_FILENAMES $ICD" \
	-ex "set environment LD_LIBRARY_PATH $RUNTIME/lib:/opt/udroid/gfxstream/lib:/usr/lib/aarch64-linux-gnu" \
	-ex run \
	-ex 'thread apply all bt' \
	--args /usr/bin/vkcube \
		--wsi wayland \
		--present_mode 2 \
		--width 640 \
		--height 640 \
		--c "$FRAMES"
