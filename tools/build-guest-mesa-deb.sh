#!/usr/bin/env bash
set -euo pipefail

source_dir="${1:?usage: build-guest-mesa-deb.sh <mesa-source> <output-dir>}"
output_dir="${2:?usage: build-guest-mesa-deb.sh <mesa-source> <output-dir>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
commit=7c8f8371df1c9e359becf9e902ba23b450b739c6
version=26.1.5+git7c8f8371-1

[[ "$(uname -m)" == aarch64 ]] || { echo 'Mesa guest build requires an ARM64 runner' >&2; exit 1; }
. /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 ]] || {
    echo 'Mesa guest build requires Ubuntu 22.04 for its glibc floor' >&2
    exit 1
}
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$commit" ]] || {
    echo 'Mesa source revision does not match the pinned commit' >&2
    exit 1
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$output_dir"

meson setup "$work/zink" "$source_dir" \
    --buildtype=debugoptimized \
    -Dplatforms=x11 -Dgallium-drivers=zink -Dvulkan-drivers= \
    -Dopengl=true -Dglx=dri -Degl=enabled -Dgbm=enabled \
    -Dglvnd=enabled -Dllvm=disabled -Dvideo-codecs= -Dtools=zink
meson compile -C "$work/zink"

meson setup "$work/virtio" "$source_dir" \
    --buildtype=debugoptimized \
    -Dplatforms=x11 -Dgallium-drivers= -Dvulkan-drivers=virtio \
    -Dopengl=false -Dglx=disabled -Degl=disabled -Dgbm=disabled \
    -Dllvm=disabled -Dvideo-codecs=
meson compile -C "$work/virtio"

package="$work/package"
bundle="$package/opt/udroid/venus-mesa"
mkdir -p "$bundle/bin" "$bundle/lib/dri" "$bundle/share/doc" "$package/DEBIAN"
cp -a "$root/app/src/main/assets/runtime/arm64-v8a/venus-mesa/share/". "$bundle/share/"
install -m 644 "$source_dir/docs/license.rst" "$bundle/share/doc/license.rst"

install -m 755 "$work/zink/src/gallium/drivers/zink/check_requirements/zink_check_requirements" "$bundle/bin/"
install -m 755 "$work/zink/src/gallium/targets/dri/libgallium-26.1.5.so" "$bundle/lib/"
install -m 755 "$work/zink/src/gallium/targets/dri/zink_dri.so" "$bundle/lib/dri/"
install -m 755 "$work/zink/src/egl/libEGL_mesa.so.0" "$bundle/lib/"
install -m 755 "$work/zink/src/glx/libGLX_mesa.so.0" "$bundle/lib/"
install -m 755 "$work/virtio/src/virtio/vulkan/libvulkan_virtio.so" "$bundle/lib/"

for binary in "$bundle/bin/zink_check_requirements" "$bundle/lib/"*.so* "$bundle/lib/dri/"*.so; do
    readelf -h "$binary" | grep -q 'Machine:.*AArch64'
    max_glibc="$(readelf --version-info "$binary" | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -1 || true)"
    if [[ -n "$max_glibc" && "$(printf '%s\n' GLIBC_2.34 "$max_glibc" | sort -V | tail -1)" != GLIBC_2.34 ]]; then
        echo "$binary requires $max_glibc, newer than Ubuntu 22.04" >&2
        exit 1
    fi
done

manifest="$bundle/MANIFEST.properties"
printf 'format=1\nruntime=%s\nabi=arm64-v8a\nsource_package=mesa\nmesa_commit=%s\n' "$version" "$commit" > "$manifest"
while IFS= read -r file; do
    relative="${file#"$bundle"/}"
    printf '%s.sha256=%s\n' "$relative" "$(sha256sum "$file" | cut -d ' ' -f 1)" >> "$manifest"
done < <(find "$bundle" -type f ! -name MANIFEST.properties | LC_ALL=C sort)

printf 'Package: udroid-mesa-venus\nVersion: %s\nArchitecture: arm64\nMaintainer: RandomCoderOrg\nDepends: libc6 (>= 2.34), libdrm2, libx11-6, libxcb1, libvulkan1\nDescription: uDroid private Mesa Venus and Zink guest runtime\n Installs under /opt/udroid without replacing distribution Mesa.\n' "$version" > "$package/DEBIAN/control"
archive="$output_dir/udroid-mesa-venus_${version}_arm64.deb"
dpkg-deb --build --root-owner-group "$package" "$archive"
sha256sum "$archive" > "$output_dir/SHA256SUMS"
