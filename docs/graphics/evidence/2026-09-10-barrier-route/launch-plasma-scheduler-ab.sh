#!/bin/sh
set -eu

export UDROID_ZINK_ASYNC_NATIVE_FENCE=1
export XDG_SESSION_TYPE=wayland
export XDG_SESSION_DESKTOP=KDE
export XDG_CURRENT_DESKTOP=KDE
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=6
export QT_QPA_PLATFORM=wayland
export PATH="/root/udroid-kwin-scheduler-ab-bin:$PATH"

exec /root/udroid-gfxstream-fd-exec.py /tmp/kumquat-gpu-0 4 \
	/usr/lib/aarch64-linux-gnu/libexec/plasma-dbus-run-session-if-needed \
	/root/plasma-session-supervisor.sh
