#!/bin/sh
set -eu

duration=${UDROID_PLASMA_SOAK_SECONDS:-180}
runtime=${XDG_RUNTIME_DIR:?XDG_RUNTIME_DIR is required}
inner=wayland-0
gdb=${UDROID_KWIN_GDB-0}

case $gdb in
	0|1) ;;
	*) echo "UDROID_PLASMA_SOAK_RESULT status=invalid-gdb" >&2; exit 2 ;;
esac

plasma_log=$(mktemp "${TMPDIR:-/tmp}/udroid-plasma-soak.XXXXXX.log")
gdb_ready=
if [ "$gdb" = 1 ]; then
	gdb_ready=${plasma_log%.log}.gdb-ready
	export UDROID_KWIN_GDB_READY="$gdb_ready"
else
	unset UDROID_KWIN_GDB_READY
fi

cleanup() {
	trap - EXIT INT TERM
	[ -z "${timer_pid:-}" ] || kill "$timer_pid" 2>/dev/null || true
	[ -z "${probe_pid:-}" ] || kill "$probe_pid" 2>/dev/null || true
	[ -z "${plasma_pid:-}" ] || kill "$plasma_pid" 2>/dev/null || true
	if [ -s "$plasma_log" ]; then
		echo "UDROID_PLASMA_SOAK_LOG path=$plasma_log" >&2
		cat "$plasma_log" >&2
	fi
	rm -f "$plasma_log"
	[ -z "$gdb_ready" ] || rm -f "$gdb_ready" "$gdb_ready.starting"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
	echo "UDROID_PLASMA_SOAK_RESULT status=$1"
	exit 1
}

check_plasma() {
	if grep -Eiq 'KCrash|fatal crash|UDROID_KWIN_GDB_DONE' "$plasma_log"; then
		fail plasma-crash
	fi
	if [ -n "${kwin_pid:-}" ] &&
		[ "$(readlink "/proc/$kwin_pid/exe" 2>/dev/null || true)" != /root/udroid-kwin-contract/bin/kwin_wayland ]; then
		fail kwin-exited
	fi
	if ! kill -0 "$plasma_pid" 2>/dev/null; then
		if wait "$plasma_pid"; then plasma_status=0; else plasma_status=$?; fi
		plasma_pid=
		echo "UDROID_PLASMA_EXIT status=$plasma_status"
		fail plasma-exited
	fi
}

/usr/bin/startplasma-wayland >"$plasma_log" 2>&1 &
plasma_pid=$!

i=0
while [ ! -S "$runtime/$inner" ]; do
	check_plasma
	[ "$i" -lt 300 ] || {
		fail "socket-timeout socket=$runtime/$inner"
	}
	i=$((i + 1))
	sleep 0.1
done

case ${UDROID_PLASMA_SOAK_CLIENT:-shm} in
	egl) client=/usr/bin/weston-simple-egl ;;
	shm) client=/usr/bin/weston-simple-shm ;;
	*)
		echo "UDROID_PLASMA_SOAK_RESULT status=invalid-client"
		exit 2
		;;
esac
if [ ! -x "$client" ]; then
	fail client-missing
fi
kwin_pid=
if [ "$gdb" = 1 ]; then
	i=0
	while [ -z "$kwin_pid" ]; do
		check_plasma
		[ ! -s "$gdb_ready" ] || kwin_pid=$(cat "$gdb_ready")
		[ "$i" -lt 300 ] || fail gdb-launch-timeout
		i=$((i + 1))
		sleep 0.1
	done
	case $kwin_pid in *[!0-9]*) fail invalid-kwin-pid ;; esac
	check_plasma
fi

echo "UDROID_PLASMA_SOAK_START seconds=$duration client=$client socket=$inner"
WAYLAND_DISPLAY=$inner "$client" >>"$plasma_log" 2>&1 &
probe_pid=$!

sleep "$duration" &
timer_pid=$!
while kill -0 "$timer_pid" 2>/dev/null; do
	check_plasma
	if ! kill -0 "$probe_pid" 2>/dev/null; then
		if wait "$probe_pid"; then probe_status=0; else probe_status=$?; fi
		probe_pid=
		echo "UDROID_PLASMA_PROBE_EXIT status=$probe_status"
		fail probe-exited-early
	fi
	sleep 0.1
done
wait "$timer_pid"
timer_pid=
check_plasma
kill "$probe_pid" 2>/dev/null || true
wait "$probe_pid" 2>/dev/null || true
probe_pid=
echo "UDROID_PLASMA_SOAK_RESULT status=ok seconds=$duration client=$client"
