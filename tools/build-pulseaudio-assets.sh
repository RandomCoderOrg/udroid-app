#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY_URL="https://packages.termux.dev/apt/termux-main"
PACKAGE_MANIFEST="$ROOT/tools/pulseaudio-packages.tsv"
PULSEAUDIO_VERSION="17.0-3"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/udroid-pulseaudio.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

require_tool() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "Missing required tool: $1" >&2
        exit 1
    }
}

require_tool bsdtar
require_tool curl
require_tool shasum
require_tool zip

fetch_checked() {
    local repository_path="$1"
    local destination="$2"
    local expected="$3"
    curl --fail --location --silent --show-error \
        "$REPOSITORY_URL/$repository_path" \
        --output "$destination"
    local actual
    actual="$(shasum -a 256 "$destination" | cut -d ' ' -f 1)"
    if [[ "$actual" != "$expected" ]]; then
        echo "Checksum mismatch for $repository_path" >&2
        echo "expected $expected" >&2
        echo "actual   $actual" >&2
        exit 1
    fi
}

copy_runtime_file() {
    local source="$1"
    local destination="$2"
    [[ -e "$source" ]] || {
        echo "Missing PulseAudio runtime file: $source" >&2
        exit 1
    }
    mkdir -p "$(dirname "$destination")"
    # Dereference Termux's prefix-relative library symlinks. The app runtime
    # deliberately uses a compact, relocatable directory instead.
    cp -L "$source" "$destination"
    chmod 755 "$destination"
}

build_abi() {
    local android_abi="$1"
    local termux_arch="$2"
    local abi_work="$WORK/$android_abi"
    local package_root="$abi_work/packages"
    local extracted_root="$abi_work/extracted"
    local stage="$abi_work/stage"
    mkdir -p "$package_root" "$extracted_root" "$stage/bin" "$stage/lib" "$stage/modules"

    local matched=0
    while IFS='|' read -r arch package version repository_path sha256; do
        [[ -z "$arch" || "$arch" == \#* ]] && continue
        [[ "$arch" == "$termux_arch" ]] || continue
        matched=$((matched + 1))
        local deb="$package_root/$package.deb"
        local unpacked="$package_root/$package"
        fetch_checked "$repository_path" "$deb" "$sha256"
        mkdir -p "$unpacked"
        bsdtar -xf "$deb" -C "$unpacked"
        local data_archive
        data_archive="$(find "$unpacked" -maxdepth 1 -type f -name 'data.tar.*' -print -quit)"
        [[ -n "$data_archive" ]] || {
            echo "No data archive in $package $version" >&2
            exit 1
        }
        bsdtar -xf "$data_archive" -C "$extracted_root"
    done < "$PACKAGE_MANIFEST"
    [[ "$matched" -gt 0 ]] || {
        echo "No PulseAudio packages are pinned for $termux_arch" >&2
        exit 1
    }

    local prefix="$extracted_root/data/data/com.termux/files/usr"
    copy_runtime_file "$prefix/bin/pulseaudio" "$stage/bin/pulseaudio"

    local library
    for library in \
        libFLAC.so \
        libandroid-execinfo.so \
        libdbus-1.so \
        libiconv.so \
        libltdl.so \
        libmp3lame.so \
        libogg.so \
        libopus.so \
        libprotocol-native.so \
        libpulse.so \
        libsndfile.so \
        libsoxr.so \
        libspeexdsp.so \
        libvorbis.so \
        libvorbisenc.so; do
        copy_runtime_file "$prefix/lib/$library" "$stage/lib/$library"
    done
    copy_runtime_file \
        "$prefix/lib/pulseaudio/libpulsecommon-17.0.so" \
        "$stage/lib/libpulsecommon-17.0.so"
    copy_runtime_file \
        "$prefix/lib/pulseaudio/libpulsecore-17.0.so" \
        "$stage/lib/libpulsecore-17.0.so"

    local module
    for module in \
        module-native-protocol-tcp.so \
        module-sles-sink.so \
        module-sles-source.so; do
        copy_runtime_file \
            "$prefix/lib/pulseaudio/modules/$module" \
            "$stage/modules/$module"
    done

    printf '%s\n' "$PULSEAUDIO_VERSION" > "$stage/VERSION"
    # Keep the checked-in archive byte-for-byte reproducible. zip stores each
    # input mtime even with -X, so normalize files and feed a sorted file list.
    find "$stage" -type f -exec touch -t 202001010000 {} +
    local destination="$ROOT/app/src/main/assets/runtime/$android_abi/pulseaudio-runtime.zip"
    local staging_archive="$abi_work/pulseaudio-runtime.zip"
    (
        cd "$stage"
        find . -type f -print | LC_ALL=C sort | zip -9 -X -q "$staging_archive" -@
    )
    install -m 644 "$staging_archive" "$destination"
    echo "$android_abi: $(shasum -a 256 "$destination" | cut -d ' ' -f 1)"
}

if [[ "$#" -eq 0 ]]; then
    set -- arm64-v8a armeabi-v7a x86_64
fi

for abi in "$@"; do
    case "$abi" in
        arm64-v8a) build_abi "$abi" aarch64 ;;
        armeabi-v7a) build_abi "$abi" arm ;;
        x86_64) build_abi "$abi" x86_64 ;;
        *)
            echo "Unsupported ABI: $abi" >&2
            exit 2
            ;;
    esac
done
