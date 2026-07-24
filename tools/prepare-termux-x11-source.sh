#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cpp_root="$repo_root/third_party/termux-x11/lorie/src/main/cpp"

if [[ ! -f "$cpp_root/CMakeLists.txt" ]]; then
    echo "Termux:X11 source is missing. Run: git submodule update --init --recursive" >&2
    exit 1
fi

apply_once() {
    local source_dir="$1"
    local patch_file="$2"

    if patch -p1 -f -R --dry-run -d "$source_dir" -i "$patch_file" >/dev/null 2>&1; then
        return
    fi

    if ! patch -p1 -f -N --dry-run -d "$source_dir" -i "$patch_file" >/dev/null; then
        echo "Termux:X11 patch does not apply cleanly: $patch_file" >&2
        exit 1
    fi

    patch -p1 -f -N -V none -d "$source_dir" -i "$patch_file"
}

apply_once "$cpp_root/libxtrans" "$cpp_root/patches/Xtrans.patch"
apply_once "$cpp_root/pixman" "$cpp_root/patches/pixman.patch"
apply_once "$cpp_root/xkbcomp" "$cpp_root/patches/xkbcomp.patch"
apply_once "$cpp_root/libxkbfile" "$cpp_root/patches/xkbfile.patch"
apply_once "$cpp_root/libx11" "$cpp_root/patches/x11.patch"
apply_once \
    "$cpp_root/libx11" \
    "$repo_root/patches/termux-x11/0001-android-xlocale-include-order.patch"
apply_once "$cpp_root/xserver" "$cpp_root/patches/xserver.patch"
apply_once "$cpp_root/libepoxy" "$cpp_root/patches/libepoxy.patch"
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0002-udroid-native-server-entrypoint.patch"

echo "Termux:X11 native source patches are ready."
