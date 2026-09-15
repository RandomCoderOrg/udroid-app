#!/bin/sh
set -eu

STAGE=/root/udroid-weston-socket-v1
OUTPUT=/root/udroid-gfxstream-output-20260901-rfence-v2
DIAG=/opt/udroid-diag/sync-export-20260906
OWNERSHIP_DIAG=/opt/udroid-diag/ownership-20260906
ICD_JSON=$DIAG/gfxstream/share/vulkan/icd.d/gfxstream-diag_icd.aarch64.json
ICD_LIB=$DIAG/gfxstream/lib/libvulkan_gfxstream.so
ZINK_LIB=$DIAG/zink/lib/aarch64-linux-gnu
ZINK_DRI=$OWNERSHIP_DIAG/dri

test -r "$ICD_JSON"
test -r "$ICD_LIB"
test -r "$ZINK_LIB/libEGL.so.1"
test -r "$ZINK_DRI/zink_dri.so"

export GBM_BACKEND=gfxstream
export GBM_BACKENDS_PATH=/root/gbm-transport-diag
export GFXSTREAM_GBM_DEBUG=1
export VIRTGPU_KUMQUAT=1
export VK_DRIVER_FILES="$ICD_JSON"
export VK_ICD_FILENAMES="$ICD_JSON"
export WESTON_GFXSTREAM_PRESENTER_ICD="$ICD_LIB"
export WESTON_GFXSTREAM_PRESENT_DIAGNOSTICS=1
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export LIBGL_DRIVERS_PATH="$ZINK_DRI"
export LIBGL_KOPPER_DRI2=true
export UDROID_WINSYS_TIMING=1
export UDROID_WINSYS_OWNERSHIP_TRACE=1
export LD_LIBRARY_PATH="/root/udroid-gbm-contract/lib:$ZINK_LIB:$ZINK_DRI:$DIAG/gfxstream/lib:$OUTPUT:$STAGE/frontend:$STAGE/libweston:/root/udroid-weston-contract/libweston:/usr/lib/aarch64-linux-gnu"
export WESTON_MODULE_MAP="headless-backend.so=$STAGE/libweston/backend-headless/headless-backend.so;gl-renderer.so=/root/udroid-weston-contract/libweston/renderer-gl/gl-renderer.so;desktop-shell.so=/usr/lib/aarch64-linux-gnu/weston/desktop-shell.so;"
export WESTON_DATA_DIR=/usr/share/weston
export XDG_RUNTIME_DIR=/run/weston

mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

exec "$STAGE/frontend/weston" \
    --backend=headless \
    --renderer=gl \
    --shell=desktop \
    --no-config \
    --gbm-allocator-socket=/tmp/kumquat-gpu-0 \
    --gfxstream-presenter-icd="$ICD_LIB" \
    --socket=wayland-gfxstream-ownership \
    --width=720 \
    --height=720 \
    --idle-time=0 \
    --log=/root/udroid-weston-socket-v1/weston-ownership.log \
    -- \
    /usr/bin/weston-simple-shm
