#!/bin/sh
set -eu

RUNTIME=${UDROID_WAYLAND_RUNTIME:-/root/udroid-gfxstream-wayland-format-20260912}
ICD=${UDROID_WAYLAND_ICD:-/root/gfxstream-wayland-format-icd.json}
FRAMES=${UDROID_VKCUBE_FRAMES:-1}

# GDB evaluates its $ variables; the shell must preserve them literally.
# shellcheck disable=SC2016
exec gdb -q -batch \
	-ex 'set pagination off' \
	-ex 'set confirm off' \
	-ex 'set breakpoint pending on' \
	-ex 'set can-use-hw-watchpoints 1' \
	-ex 'set environment VIRTGPU_KUMQUAT 1' \
	-ex "set environment VK_DRIVER_FILES $ICD" \
	-ex "set environment VK_ICD_FILENAMES $ICD" \
	-ex "set environment LD_LIBRARY_PATH $RUNTIME/lib:/opt/udroid/gfxstream/lib:/usr/lib/aarch64-linux-gnu" \
	-ex 'break wsi_common_queue_present' \
	-ex run \
	-ex 'set $pPresentInfo = (void *)$x2' \
	-ex 'set $head = *(void **)($x2 + 8)' \
	-ex 'set $head_pnext_slot = (void **)((char *)$head + 8)' \
	-ex 'set $head_pnext = *$head_pnext_slot' \
	-ex 'set $head_memory = (char *)$head - 32' \
	-ex 'printf "pPresentInfo=%p\n", $pPresentInfo' \
	-ex 'printf "head=%p\n", $head' \
	-ex 'printf "head->pNext=%p\n", $head_pnext' \
	-ex 'watch -l *$head_pnext_slot' \
	-ex continue \
	-ex 'printf "head->pNext old=%p new=%p\n", $head_pnext, *$head_pnext_slot' \
	-ex 'info registers x0 x1 x2 x3 x4 x5 x23 x25 sp pc' \
	-ex 'x/16gx $head_memory' \
	-ex 'thread apply all bt' \
	--args /usr/bin/vkcube \
		--wsi wayland \
		--present_mode 2 \
		--width 640 \
		--height 640 \
		--c "$FRAMES" \
		--display_timing
