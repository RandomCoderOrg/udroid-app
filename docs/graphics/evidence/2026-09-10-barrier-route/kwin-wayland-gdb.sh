#!/bin/sh
set -eu

kwin=/root/udroid-kwin-contract/bin/kwin_wayland

if [ "${UDROID_KWIN_GDB_INFERIOR:-0}" = 1 ]; then
	if [ ! -e /proc/self/fd/4 ] && [ ! -L /proc/self/fd/4 ]; then
		echo "UDROID_KWIN_GDB_RESULT status=fd4-missing" >&2
		exit 1
	fi
	echo "UDROID_KWIN_GDB_FD4 target=$(readlink /proc/self/fd/4)" >&2
	printf '%s\n' "$$" >"${UDROID_KWIN_GDB_READY:?UDROID_KWIN_GDB_READY is required}.starting"
	export LD_LIBRARY_PATH="$UDROID_KWIN_GDB_INFERIOR_LD_LIBRARY_PATH"
	exec "$kwin" \
		"$@" \
		--wayland-display="${WAYLAND_DISPLAY:?missing parent Wayland display}" \
		--gbm-allocator-fd=4 \
		--width="${UDROID_WESTON_LOGICAL_WIDTH:-720}" \
		--height="${UDROID_WESTON_LOGICAL_HEIGHT:-720}"
fi

export UDROID_KWIN_GDB_INFERIOR=1
export UDROID_KWIN_GDB_INFERIOR_LD_LIBRARY_PATH="/root/udroid-kwin-contract/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
ready=${UDROID_KWIN_GDB_READY:?UDROID_KWIN_GDB_READY is required}
unset LD_LIBRARY_PATH

# GDB evaluates its $ variables; the shell must preserve them literally.
# shellcheck disable=SC2016
exec gdb -q -batch \
	-ex 'set pagination off' \
	-ex 'set confirm off' \
	-ex 'handle SIGSEGV stop print nopass' \
	-ex 'handle SIGABRT stop print nopass' \
	-ex 'catch exec' \
	-ex run \
	-ex "shell mv $ready.starting $ready" \
	-ex 'delete breakpoints' \
	-ex continue \
	-ex 'echo UDROID_KWIN_GDB_STOP\n' \
	-ex 'p $_siginfo.si_signo' \
	-ex 'info proc mappings' \
	-ex 'info sharedlibrary' \
	-ex 'info symbol $pc' \
	-ex 'x/16i $pc-32' \
	-ex 'x/32gx $sp' \
	-ex 'thread apply all info registers' \
	-ex 'thread apply all bt full' \
	-ex 'echo UDROID_KWIN_GDB_DONE\n' \
	--args /bin/sh "$0" "$@"
