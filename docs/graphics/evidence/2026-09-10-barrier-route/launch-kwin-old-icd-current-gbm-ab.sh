#!/bin/sh
set -eu

export GBM_BACKEND=gfxstream
export GBM_BACKENDS_PATH=/root/gbm-transport-diag
export VIRTGPU_KUMQUAT=1
export VK_DRIVER_FILES=/root/gfxstream-native-wayland-icd.json
export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export LIBGL_KOPPER_DRI2=true
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=KDE
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=6
export LIBGL_DRIVERS_PATH=/root/udroid-mesa-wayland-kopper-25.1.9/lib/aarch64-linux-gnu/dri
export LD_LIBRARY_PATH="/root/udroid-mesa-wayland-kopper-25.1.9/lib/aarch64-linux-gnu:/root/udroid-kwin-contract/lib:/root/udroid-gbm-contract/lib:/opt/udroid/gfxstream/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# The inherited async-fence toggle is intentional; this old DRI may ignore it.
exec /usr/bin/dbus-run-session -- \
	/root/udroid-gfxstream-fd-exec.py /tmp/kumquat-gpu-0 4 \
	/root/udroid-kwin-contract/bin/kwin_wayland \
	--wayland-display="${WAYLAND_DISPLAY:?missing parent Wayland display}" \
	--gbm-allocator-fd=4 \
	--socket=wayland-kwin-old-icd-current-gbm-ab \
	--width=720 \
	--height=720 \
	-- \
	/root/launch-plasma-shell-wayland-clean.sh
