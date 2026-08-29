#!/usr/bin/env bash
set -euo pipefail

mode=${1:-steady}
adb_bin=${ADB:-adb}
package=${UDROID_PROBE_PACKAGE:-org.randomcoder.udroid.dev}
activity="$package/org.randomcoder.udroid.gfxstream.GfxstreamPresenterProbeActivity"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
validator="$script_dir/winsys_trace_validator.py"
trace_out=${TRACE_OUT:-"/tmp/udroid-winsys-${mode}.log"}
resized=0

restore_display() {
    if [ "$resized" -eq 1 ]; then
        "$adb_bin" shell wm size reset >/dev/null 2>&1 || true
    fi
}
trap restore_display EXIT INT TERM

case "$mode" in
    steady|cycle|reattach|resize) ;;
    *)
        echo "usage: $0 [steady|cycle|reattach|resize]" >&2
        exit 2
        ;;
esac

"$adb_bin" get-state >/dev/null
"$adb_bin" shell am force-stop "$package"
"$adb_bin" logcat -c

launch_args=(
    am start -W -S -n "$activity"
    --ez contractTrace true
)
if [ "$mode" = cycle ]; then
    launch_args+=(--ei resourceCycleFrames "${RESOURCE_CYCLE_FRAMES:-120}")
fi
"$adb_bin" shell "${launch_args[@]}"

case "$mode" in
    steady)
        sleep "${PROBE_SECONDS:-20}"
        ;;
    cycle)
        sleep "${PROBE_SECONDS:-15}"
        ;;
    reattach)
        for _ in $(seq 1 "${REATTACH_CYCLES:-3}"); do
            "$adb_bin" shell am start \
                -a android.intent.action.MAIN \
                -c android.intent.category.HOME >/dev/null
            sleep 1
            "$adb_bin" shell am start -n "$activity" >/dev/null
            sleep 2
        done
        ;;
    resize)
        if "$adb_bin" shell wm size | grep -q '^Override size:'; then
            echo "refusing to replace an existing Android display-size override" >&2
            exit 1
        fi
        "$adb_bin" shell wm size "${PROBE_RESIZE:-720x1600}" >/dev/null
        resized=1
        sleep 3
        "$adb_bin" shell wm size reset >/dev/null
        resized=0
        sleep 3
        ;;
esac

"$adb_bin" shell input keyevent KEYCODE_BACK
sleep 2
"$adb_bin" logcat -d -s uDroid-Winsys:I > "$trace_out"
python3 "$validator" "$trace_out"

first=$(
    grep '"event":"produce_begin"' "$trace_out" | sed -n '1p' || true
)
last=$(
    grep '"event":"produce_begin"' "$trace_out" | sed -n '$p' || true
)
printf 'trace=%s\n' "$trace_out"
printf 'first=%s\n' "$first"
printf 'last=%s\n' "$last"
