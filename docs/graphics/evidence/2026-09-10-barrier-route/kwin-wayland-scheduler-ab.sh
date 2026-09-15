#!/bin/sh
set -eu

export LD_LIBRARY_PATH="/root/udroid-kwin-contract/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

exec /root/udroid-kwin-contract/bin/kwin_wayland \
	"$@" \
	--wayland-display="${WAYLAND_DISPLAY:?missing parent Wayland display}" \
	--gbm-allocator-fd=4 \
	--width="${UDROID_WESTON_LOGICAL_WIDTH:-720}" \
	--height="${UDROID_WESTON_LOGICAL_HEIGHT:-720}"
