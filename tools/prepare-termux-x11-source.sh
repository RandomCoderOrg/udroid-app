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
    local applied_marker="${3:-}"

    # Later patches can legitimately change context introduced by an earlier
    # patch, making a reverse dry-run fail even though the earlier patch is
    # present. Prefer a patch-specific semantic marker when one is supplied.
    if [[ -n "$applied_marker" ]] && grep -R -F -q -- "$applied_marker" "$source_dir"; then
        return
    fi

    if patch -p1 -f -R --dry-run -d "$source_dir" -i "$patch_file" >/dev/null 2>&1; then
        return
    fi

    if ! patch -p1 -f -N --dry-run -d "$source_dir" -i "$patch_file" >/dev/null; then
        echo "Termux:X11 patch does not apply cleanly: $patch_file" >&2
        exit 1
    fi

    patch -p1 -f -N -V none -d "$source_dir" -i "$patch_file"
}

revert_if_applied() {
    local source_dir="$1"
    local patch_file="$2"
    local applied_marker="$3"

    if ! grep -R -F -q -- "$applied_marker" "$source_dir"; then
        return
    fi

    if ! patch -p1 -f -R --dry-run -d "$source_dir" -i "$patch_file" >/dev/null; then
        echo "Termux:X11 patch cannot be reverted cleanly: $patch_file" >&2
        exit 1
    fi

    patch -p1 -f -R -V none -d "$source_dir" -i "$patch_file"
}

apply_once \
    "$cpp_root" \
    "$repo_root/patches/termux-x11/0000-xserver-patch-semantic-idempotence.patch" \
    'uDroid: later native patches extend xserver.patch'
apply_once "$cpp_root/libxtrans" "$cpp_root/patches/Xtrans.patch"
apply_once "$cpp_root/pixman" "$cpp_root/patches/pixman.patch"
apply_once "$cpp_root/xkbcomp" "$cpp_root/patches/xkbcomp.patch"
apply_once "$cpp_root/libxkbfile" "$cpp_root/patches/xkbfile.patch"
apply_once "$cpp_root/libx11" "$cpp_root/patches/x11.patch"
apply_once \
    "$cpp_root/libx11" \
    "$repo_root/patches/termux-x11/0001-android-xlocale-include-order.patch" \
    '#include_next <xlocale.h>'
apply_once \
    "$cpp_root/xserver" \
    "$cpp_root/patches/xserver.patch" \
    'ShmGetDevPrivateKeyRec(void)'
apply_once "$cpp_root/libepoxy" "$cpp_root/patches/libepoxy.patch"
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0002-udroid-native-server-entrypoint.patch" \
    'Java_org_randomcoder_udroid_x11_X11NativeBridge_start'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0003-udroid-renderer-bridge.patch" \
    'Java_org_randomcoder_udroid_x11_X11NativeBridge_getXConnection'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0004-udroid-batched-native-touch.patch" \
    'EVENT_TOUCH_FRAME'

# SPHAL is a runtime graphics profile, so its AHardwareBuffer and Present-copy
# contract must be present in every embedded X11 build.
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0005-dmabuf-cpu-read-sync.patch" \
    'DMA_BUF_IOCTL_SYNC'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0006-ahardwarebuffer-external-texture-sampling.patch" \
    'GL_TEXTURE_EXTERNAL_OES'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0007-ahardwarebuffer-content-semantics.patch" \
    'AHARDWAREBUFFER_RGBA_SOCKET_FD'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0008-advertise-buffer-transport-protocol.patch" \
    'UDROID_X11_BUFFER_TRANSPORT_ATOM'
apply_once \
    "$cpp_root" \
    "$repo_root/patches/termux-x11/0009-defer-gpu-only-present-while-detached.patch" \
    'Bool loriePixmapIsGpuOnly(PixmapPtr pixmap) {'
revert_if_applied \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/experiments/0013-fix-present-flip-height-check.patch" \
    'pvfb->root.height != pixmap->drawable.height'

if [[ "${UDROID_X11_DIAGNOSTICS:-0}" == "1" ]]; then
    source_profile="diagnostics"
    apply_once \
        "$cpp_root/lorie" \
        "$repo_root/patches/termux-x11/diagnostics/0012-lorie-raw-fd-import-diagnostics.patch" \
        'raw-fd import stage=mmap'
else
    source_profile="standard"
    revert_if_applied \
        "$cpp_root/lorie" \
        "$repo_root/patches/termux-x11/diagnostics/0012-lorie-raw-fd-import-diagnostics.patch" \
        'raw-fd import stage=mmap'
fi

# Compositors import redirected window pixmaps through DRI3 in every profile.
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0015-export-pixmaps-through-dri3.patch" \
    'DRI3: exported DMA-BUF pixmap'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0016-accept-fd-gpu-copy-sources.patch" \
    'lorieGpuCopySource'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0017-export-ahardwarebuffer-pixmaps-through-dri3.patch" \
    'exportAHardwareBufferDmaBuf'
apply_once \
    "$cpp_root" \
    "$repo_root/patches/termux-x11/0018-present-damage-ordering.patch" \
    'RegionPtr           gpu_copy_damage'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0019-dmabuf-exa-cpu-access-sync.patch" \
    'LorieBuffer_cpuAccessBegin'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0020-renderer-route-capability-probe.patch" \
    'Xlorie: G0 graphics contract'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0021-preserve-logical-ahb-pixmap-size.patch" \
    'logical %ux%u physical %ux%u'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0022-disable-incoherent-ahb-gpu-present.patch" \
    'CPU and GPU views of AHardwareBuffer are not coherent'
apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0023-transfer-ahb-present-ownership.patch" \
    'Transfer an idle CPU mapping back to the GPU'

apply_once \
    "$cpp_root/lorie" \
    "$repo_root/patches/termux-x11/0011-lorie-perfetto-slices.patch" \
    'uDroid/Lorie draw'

echo "Termux:X11 native source is ready ($source_profile profile)."
