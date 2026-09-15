#!/bin/sh
set -eu

duration=${UDROID_KWIN_SOAK_SECONDS:-30}
runtime=${XDG_RUNTIME_DIR:?XDG_RUNTIME_DIR is required}
inner=wayland-0
inner_owned=0
client=${UDROID_KWIN_SOAK_CLIENT:-/usr/bin/weston-simple-shm}
kwin_log=$(mktemp "${TMPDIR:-/tmp}/udroid-kwin-soak.XXXXXX.log")

export KWIN_COMPOSE=O2ES
export QT_QPA_PLATFORM=wayland
export PATH="/root/udroid-kwin-scheduler-ab-bin:$PATH"

cleanup() {
	trap - EXIT INT TERM
	[ -z "${timer_pid:-}" ] || kill "$timer_pid" 2>/dev/null || true
	[ -z "${client_pid:-}" ] || kill "$client_pid" 2>/dev/null || true
	[ -z "${kwin_pid:-}" ] || kill "$kwin_pid" 2>/dev/null || true
	[ "$inner_owned" = 0 ] || rm -f "$runtime/$inner" "$runtime/$inner.lock"
	if [ -s "$kwin_log" ]; then
		echo "UDROID_KWIN_SOAK_LOG path=$kwin_log" >&2
		cat "$kwin_log" >&2
	fi
	rm -f "$kwin_log"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
	echo "UDROID_KWIN_SOAK_RESULT status=$1"
	exit 1
}

case $duration in
	''|*[!0-9]*) echo "UDROID_KWIN_SOAK_RESULT status=invalid-duration"; exit 2 ;;
esac
if [ -e "$runtime/$inner" ]; then
	fail "socket-exists socket=$runtime/$inner"
fi
if [ ! -x "$client" ]; then
	fail "client-missing client=$client"
fi

check_kwin() {
	if grep -Eiq 'KCrash|fatal crash' "$kwin_log"; then
		fail kwin-crash
	fi
	if ! kill -0 "$kwin_pid" 2>/dev/null; then
		if wait "$kwin_pid"; then kwin_status=0; else kwin_status=$?; fi
		kwin_pid=
		echo "UDROID_KWIN_EXIT status=$kwin_status"
		fail kwin-exited
	fi
}

/root/udroid-gfxstream-fd-exec.py /tmp/kumquat-gpu-0 4 \
	kwin_wayland \
	--socket="$inner" >"$kwin_log" 2>&1 &
kwin_pid=$!

i=0
while [ ! -S "$runtime/$inner" ]; do
	check_kwin
	[ "$i" -lt 300 ] || fail "socket-timeout socket=$runtime/$inner"
	i=$((i + 1))
	sleep 0.1
done
inner_owned=1

echo "UDROID_KWIN_SOAK_START seconds=$duration client=$client socket=$inner"
WAYLAND_DISPLAY=$inner "$client" >>"$kwin_log" 2>&1 &
client_pid=$!

sleep "$duration" &
timer_pid=$!
while kill -0 "$timer_pid" 2>/dev/null; do
	check_kwin
	if ! kill -0 "$client_pid" 2>/dev/null; then
		if wait "$client_pid"; then client_status=0; else client_status=$?; fi
		client_pid=
		echo "UDROID_KWIN_CLIENT_EXIT status=$client_status"
		fail client-exited-early
	fi
	sleep 0.1
done
wait "$timer_pid"
timer_pid=
check_kwin
kill "$client_pid" 2>/dev/null || true
wait "$client_pid" 2>/dev/null || true
client_pid=
echo "UDROID_KWIN_SOAK_RESULT status=ok seconds=$duration client=$client"
