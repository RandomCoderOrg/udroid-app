#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-release-jni-contract.sh <release.apk>}"

if [[ ! -f "$apk" ]]; then
    echo "Release APK does not exist: $apk" >&2
    exit 1
fi

apkanalyzer="${APKANALYZER:-}"
if [[ -z "$apkanalyzer" ]]; then
    apkanalyzer="$(command -v apkanalyzer || true)"
fi
if [[ -z "$apkanalyzer" ]]; then
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" ]]; then
        apkanalyzer="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"
    fi
fi
if [[ -z "$apkanalyzer" || ! -x "$apkanalyzer" ]]; then
    echo "apkanalyzer was not found in PATH or the Android SDK" >&2
    exit 1
fi

dex_listing="$(mktemp)"
trap 'rm -f "$dex_listing"' EXIT
"$apkanalyzer" dex packages --defined-only "$apk" > "$dex_listing"

required_symbols=(
    "org.randomcoder.udroid.x11.X11DisplayView void nativeInit()"
    "org.randomcoder.udroid.x11.X11DisplayView void surfaceChanged(android.view.Surface)"
    "org.randomcoder.udroid.x11.X11DisplayView void setViewport(int,int,int,int,int,int)"
    "org.randomcoder.udroid.x11.X11DisplayView void setFiltering(int)"
    "org.randomcoder.udroid.x11.X11DisplayView void connect(int)"
    "org.randomcoder.udroid.x11.X11DisplayView boolean connected()"
    "org.randomcoder.udroid.x11.X11DisplayView void sendWindowChange(int,int,int,java.lang.String)"
    "org.randomcoder.udroid.x11.X11DisplayView void sendMouseEvent(float,float,int,boolean,boolean)"
    "org.randomcoder.udroid.x11.X11DisplayView void sendTouchEvent(int,int,int,int)"
    "org.randomcoder.udroid.x11.X11DisplayView boolean sendKeyEvent(int,int,boolean,int)"
    "org.randomcoder.udroid.x11.X11DisplayView void sendTextEvent(byte[])"
    "org.randomcoder.udroid.x11.X11DisplayView void resetIme()"
    "org.randomcoder.udroid.x11.X11DisplayView void setClipboardText(java.lang.String)"
    "org.randomcoder.udroid.x11.X11DisplayView void requestClipboard()"
    "org.randomcoder.udroid.x11.X11NativeBridge boolean start(java.lang.String[])"
    "org.randomcoder.udroid.x11.X11NativeBridge android.os.ParcelFileDescriptor getXConnection()"
)

missing=0
for symbol in "${required_symbols[@]}"; do
    if ! grep -Fq "$symbol" "$dex_listing"; then
        echo "Missing release JNI symbol: $symbol" >&2
        missing=1
    fi
done

if (( missing )); then
    exit 1
fi

echo "Release JNI contract verified (${#required_symbols[@]} symbols)."
