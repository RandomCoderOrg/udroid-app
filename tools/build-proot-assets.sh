#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/28.2.13676358}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
API=26

PROOT_VERSION=5.1.107.86
PROOT_SHA256=692da7f952ac390eb65c4117d360cad23a052525eea4eb110ae42f8a4a7d7bb8
TALLOC_VERSION=2.4.3
TALLOC_SHA256=dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd

WORK="$(mktemp -d "${TMPDIR:-/tmp}/udroid-proot-build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

fetch_checked() {
    local url="$1"
    local output="$2"
    local expected="$3"
    curl --fail --location --silent --show-error "$url" --output "$output"
    local actual
    actual="$(shasum -a 256 "$output" | cut -d ' ' -f 1)"
    if [[ "$actual" != "$expected" ]]; then
        echo "Checksum mismatch for $url" >&2
        echo "expected $expected" >&2
        echo "actual   $actual" >&2
        exit 1
    fi
}

fetch_checked \
    "https://github.com/termux/proot/archive/v${PROOT_VERSION}.zip" \
    "$WORK/proot.zip" \
    "$PROOT_SHA256"
fetch_checked \
    "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz" \
    "$WORK/talloc.tar.gz" \
    "$TALLOC_SHA256"

build_abi() {
    local abi="$1"
    local triple="$2"
    local cc="$TOOLCHAIN/${triple}${API}-clang"
    local ar="$TOOLCHAIN/llvm-ar"
    local strip="$TOOLCHAIN/llvm-strip"
    local abi_work="$WORK/$abi"
    local prefix="$abi_work/prefix"
    local wrappers="$abi_work/tool-wrappers"

    mkdir -p \
        "$abi_work/proot" \
        "$abi_work/talloc" \
        "$prefix/lib" \
        "$prefix/include" \
        "$wrappers"
    ln -s "$TOOLCHAIN/llvm-readelf" "$wrappers/readelf"
    unzip -q "$WORK/proot.zip" -d "$abi_work/proot-source"
    tar -xzf "$WORK/talloc.tar.gz" -C "$abi_work/talloc" --strip-components=1
    patch -d "$abi_work/proot-source/proot-${PROOT_VERSION}" -p1 \
        < "$ROOT/tools/proot-ndk28.patch"

    (
        cd "$abi_work/talloc"
        CC="$cc" AR="$ar" RANLIB="$TOOLCHAIN/llvm-ranlib" \
            ./configure \
                --prefix="$prefix" \
                --disable-rpath \
                --disable-python \
                --cross-compile \
                --cross-answers="$ROOT/tools/proot-cross-answers.txt"
        make
        "$ar" rcs "$prefix/lib/libtalloc.a" bin/default/talloc*.o
        install -m 644 talloc.h "$prefix/include/talloc.h"
    )

    local proot_source="$abi_work/proot-source/proot-${PROOT_VERSION}"
    (
        cd "$proot_source"
        PATH="$wrappers:$PATH" make \
            -C src \
            CC="$cc" \
            LD="$cc" \
            STRIP="$strip" \
            OBJCOPY="$TOOLCHAIN/llvm-objcopy" \
            OBJDUMP="$TOOLCHAIN/llvm-objdump" \
            CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"${PROOT_VERSION}\\\" -I. -I$prefix/include" \
            CFLAGS="-Wall -Wextra -O2" \
            LDFLAGS="-L$prefix/lib -ltalloc -Wl,-z,noexecstack"
        "$strip" src/proot
    )

    local destination="$ROOT/app/src/main/assets/runtime/$abi/proot"
    local loader_destination="$ROOT/app/src/main/jniLibs/$abi/libproot-loader.so"
    install -m 755 "$proot_source/src/proot" "$destination"
    mkdir -p "$(dirname "$loader_destination")"
    install -m 755 "$proot_source/src/loader/loader" "$loader_destination"
    echo "$abi: $(shasum -a 256 "$destination" | cut -d ' ' -f 1)"
}

if [[ "$#" -eq 0 ]]; then
    set -- arm64-v8a armeabi-v7a x86_64
fi

for abi in "$@"; do
    case "$abi" in
        arm64-v8a) build_abi "$abi" aarch64-linux-android ;;
        armeabi-v7a) build_abi "$abi" armv7a-linux-androideabi ;;
        x86_64) build_abi "$abi" x86_64-linux-android ;;
        *)
            echo "Unsupported ABI: $abi" >&2
            exit 2
            ;;
    esac
done
