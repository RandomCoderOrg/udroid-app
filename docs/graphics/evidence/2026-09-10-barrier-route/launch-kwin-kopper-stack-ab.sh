#!/bin/sh
set -eu

export LD_LIBRARY_PATH="/root/udroid-mesa-wayland-kopper-25.1.9/lib/aarch64-linux-gnu:/root/udroid-kwin-contract/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LIBGL_DRIVERS_PATH=/root/udroid-mesa-wayland-kopper-25.1.9/lib/aarch64-linux-gnu/dri

exec /root/udroid-gfxstream-fd-exec.py /tmp/kumquat-gpu-0 4 \
	/root/udroid-kwin-contract/bin/kwin_wayland \
	--wayland-display="${WAYLAND_DISPLAY:?missing parent Wayland display}" \
	--gbm-allocator-fd=4 \
	--socket=wayland-kwin-scheduler-ab \
	--width=720 \
	--height=720 \
	-- \
	/root/launch-plasma-shell-wayland-clean.sh
