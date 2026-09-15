#!/bin/sh
set -eu

RUNTIME=${UDROID_WAYLAND_RUNTIME:-/root/udroid-gfxstream-wayland-async-20260911}
ICD=${UDROID_WAYLAND_ICD:-$RUNTIME/gfxstream-wayland-async-icd.json}

export VIRTGPU_KUMQUAT=1
export VK_DRIVER_FILES="$ICD"
export VK_ICD_FILENAMES="$ICD"
export LD_LIBRARY_PATH="$RUNTIME/lib:/opt/udroid/gfxstream/lib:/usr/lib/aarch64-linux-gnu"

/usr/bin/vulkaninfo 2>&1 | awk '
	/^Presentable Surfaces:/ { print_surface = 1 }
	/^Device Groups:/ { print_surface = 0 }
	print_surface
'
