#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
	echo "usage: $0 7|16 run-label" >&2
	exit 2
fi

case "$1" in
	7|16)
		REPAINT_WINDOW=$1
		;;
	*)
		echo "usage: $0 7|16 run-label" >&2
		exit 2
		;;
esac

RUN_LABEL=$2
case "$RUN_LABEL" in
	''|*[!A-Za-z0-9._-]*|?????????????????????????????????????????????????*)
		echo "invalid run-label (use 1-48 characters from A-Za-z0-9._-)" >&2
		exit 2
		;;
esac
RUN_SUFFIX=-$RUN_LABEL

case ${UDROID_SCHEDULER_TIMING-0} in
	0)
		unset UDROID_WINSYS_TIMING
		;;
	1)
		export UDROID_WINSYS_TIMING=1
		;;
	*)
		echo "invalid UDROID_SCHEDULER_TIMING (expected 0 or 1)" >&2
		exit 2
		;;
esac

STAGE=/root/udroid-weston-socket-v1
OUTPUT=/root/udroid-gfxstream-output-20260901-rfence-v2
DIAG=/opt/udroid-diag/sync-export-20260906
OWNERSHIP_DIAG=/opt/udroid-diag/ownership-20260906
ICD_JSON=${UDROID_SCHEDULER_GFXSTREAM_ICD:-$DIAG/gfxstream/share/vulkan/icd.d/gfxstream-diag_icd.aarch64.json}
ICD_LIB=${UDROID_SCHEDULER_GFXSTREAM_LIB:-$DIAG/gfxstream/lib/libvulkan_gfxstream.so}
ICD_LIB_DIR=${ICD_LIB%/*}
ZINK_LIB=$DIAG/zink/lib/aarch64-linux-gnu
ZINK_DRI=$OWNERSHIP_DIAG/dri
WESTON_CONFIG=/root/weston-repaint${REPAINT_WINDOW}-20260910.ini
CHILD=${UDROID_SCHEDULER_CHILD:-/usr/bin/weston-simple-shm}

test -r "$ICD_JSON"
test -r "$ICD_LIB"
test -r "$ZINK_LIB/libEGL.so.1"
test -r "$ZINK_DRI/zink_dri.so"
test -r "$WESTON_CONFIG"
case "$CHILD" in
	/*) test -x "$CHILD" ;;
	*)
		echo "UDROID_SCHEDULER_CHILD must be an absolute executable path" >&2
		exit 2
		;;
esac

unset GFXSTREAM_GBM_DEBUG
unset UDROID_WINSYS_OWNERSHIP_TRACE
unset WAYLAND_DEBUG

export GBM_BACKEND=gfxstream
export GBM_BACKENDS_PATH=/root/gbm-transport-diag
export VIRTGPU_KUMQUAT=1
export VK_DRIVER_FILES="$ICD_JSON"
export VK_ICD_FILENAMES="$ICD_JSON"
export WESTON_GFXSTREAM_PRESENTER_ICD="$ICD_LIB"
export WESTON_GFXSTREAM_PRESENT_DIAGNOSTICS=1
export WESTON_GL_REPAINT_DIAGNOSTICS=1
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export LIBGL_DRIVERS_PATH="$ZINK_DRI"
export LIBGL_KOPPER_DRI2=true
export LD_LIBRARY_PATH="/root/udroid-gbm-contract/lib:$ZINK_LIB:$ZINK_DRI:$ICD_LIB_DIR:$DIAG/gfxstream/lib:$OUTPUT:$STAGE/frontend:$STAGE/libweston:/root/udroid-weston-contract/libweston:/usr/lib/aarch64-linux-gnu"
export WESTON_MODULE_MAP="headless-backend.so=$STAGE/libweston/backend-headless/headless-backend-clock-20260910.so;gl-renderer.so=/root/udroid-weston-contract/libweston/renderer-gl/gl-renderer.so;desktop-shell.so=/usr/lib/aarch64-linux-gnu/weston/desktop-shell.so;"
export WESTON_DATA_DIR=/usr/share/weston
export XDG_RUNTIME_DIR=/run/weston

mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

SOCKET_NAME=wayland-gfxstream-sched${REPAINT_WINDOW}-20260910${RUN_SUFFIX}
LOG_PATH=$STAGE/weston-sched${REPAINT_WINDOW}-20260910${RUN_SUFFIX}.log

PID_RECORD=$XDG_RUNTIME_DIR/weston-sched${REPAINT_WINDOW}-20260910${RUN_SUFFIX}.pid
PID_RECORD_TMP=$PID_RECORD.tmp.$$
PROC_STAT=$(cat /proc/$$/stat)
PROC_STAT=${PROC_STAT#*) }
set -- $PROC_STAT
PROC_STARTTIME=${20}
umask 077
printf 'pid=%s\nstarttime=%s\nlabel=%s\nsocket=%s\nlog=%s\n' \
	"$$" "$PROC_STARTTIME" "$RUN_LABEL" "$SOCKET_NAME" "$LOG_PATH" \
	>"$PID_RECORD_TMP"
mv "$PID_RECORD_TMP" "$PID_RECORD"

exec "$STAGE/frontend/weston" \
	--debug \
	--backend=headless \
	--renderer=gl \
	--shell=desktop \
	--config="$WESTON_CONFIG" \
	--gbm-allocator-socket=/tmp/kumquat-gpu-0 \
	--gfxstream-presenter-icd="$ICD_LIB" \
	--fake-seat \
	--socket="$SOCKET_NAME" \
	--width=720 \
	--height=720 \
	--idle-time=0 \
	--log="$LOG_PATH" \
	-- \
	"$CHILD"
