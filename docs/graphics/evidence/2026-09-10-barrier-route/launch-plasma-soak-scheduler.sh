#!/bin/sh
set -eu

export XDG_SESSION_TYPE=wayland
export XDG_SESSION_DESKTOP=KDE
export XDG_CURRENT_DESKTOP=KDE
export KDE_FULL_SESSION=true
export KDE_SESSION_VERSION=6
export QT_QPA_PLATFORM=wayland

case ${UDROID_KWIN_GDB-0} in
	0) export PATH="/root/udroid-kwin-scheduler-ab-bin:$PATH" ;;
	1) export PATH="/root/udroid-kwin-gdb-bin:$PATH" ;;
	*) echo "UDROID_PLASMA_SOAK_RESULT status=invalid-gdb" >&2; exit 2 ;;
esac

exec /root/udroid-gfxstream-fd-exec.py /tmp/kumquat-gpu-0 4 \
	/usr/lib/aarch64-linux-gnu/libexec/plasma-dbus-run-session-if-needed \
	/root/run-plasma-wayland-soak.sh
