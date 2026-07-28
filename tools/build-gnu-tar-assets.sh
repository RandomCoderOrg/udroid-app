#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# NDK 27 introduced timezone_t declarations for Android 15 APIs. GNU tar
# 1.35's bundled gnulib sees the type while cross-building for API 26 and
# incorrectly assumes the API 35 time-zone functions are also available.
# NDK 26.1 provides the same API 26 ABI without that misleading declaration.
NDK="${UDROID_TAR_NDK_HOME:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/26.1.10909125}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
API=26

TAR_VERSION=1.35
TAR_SHA256=4d62ff37342ec7aed748535323930c7cf94acf71c3591882b26a7ea50f3edc16
TERMUX_PACKAGES_COMMIT=fea50ba4649e6fddd1861741402c0aafa63411f2
GLOB_C_SHA256=d9c04df55f97bdc5335c3b76224b47a2b20ccef27c73103208f8074320aba014
GLOB_H_SHA256=ff4512c530aea288693f5be17e98c77faf7f449ea439df46164ad34aab197122

WORK="$(mktemp -d "${TMPDIR:-/tmp}/udroid-gnu-tar-build.XXXXXX")"
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
    "https://mirrors.kernel.org/gnu/tar/tar-${TAR_VERSION}.tar.xz" \
    "$WORK/tar.tar.xz" \
    "$TAR_SHA256"
fetch_checked \
    "https://raw.githubusercontent.com/termux/termux-packages/${TERMUX_PACKAGES_COMMIT}/packages/libandroid-glob/glob.c" \
    "$WORK/glob.c" \
    "$GLOB_C_SHA256"
fetch_checked \
    "https://raw.githubusercontent.com/termux/termux-packages/${TERMUX_PACKAGES_COMMIT}/packages/libandroid-glob/glob.h" \
    "$WORK/glob.h" \
    "$GLOB_H_SHA256"

build_abi() {
    local abi="$1"
    local compiler_triple="$2"
    local host_triple="$3"
    local cc="$TOOLCHAIN/${compiler_triple}${API}-clang"
    local ar="$TOOLCHAIN/llvm-ar"
    local ranlib="$TOOLCHAIN/llvm-ranlib"
    local strip="$TOOLCHAIN/llvm-strip"
    local abi_work="$WORK/$abi"
    local source="$abi_work/source"
    local build="$abi_work/build"
    local deps="$abi_work/deps"

    mkdir -p "$source" "$build" "$deps/include" "$deps/lib"
    tar -xJf "$WORK/tar.tar.xz" -C "$source" --strip-components=1
    install -m 644 "$WORK/glob.h" "$deps/include/glob.h"
    "$cc" -O2 -I"$deps/include" -c "$WORK/glob.c" -o "$deps/lib/glob.o"
    "$ar" rcs "$deps/lib/libandroid-glob.a" "$deps/lib/glob.o"

    (
        cd "$build"
        env \
            CC="$cc" \
            AR="$ar" \
            RANLIB="$ranlib" \
            STRIP="$strip" \
            CPPFLAGS="-I$deps/include" \
            CFLAGS="-O2" \
            LDFLAGS="-L$deps/lib" \
            LIBS="-landroid-glob" \
            gl_cv_struct_dirent_d_ino=yes \
            ac_cv_func_mkfifoat=yes \
            "$source/configure" \
                --host="$host_triple" \
                --prefix="/data/data/org.randomcoder.udroid/files/runtime/gnu-tar-${TAR_VERSION}-$abi" \
                --disable-nls \
                --without-selinux \
                --without-posix-acls \
                --disable-acl \
                --disable-year2038
        make -j"${UDROID_BUILD_JOBS:-8}"
    )

    local destination="$ROOT/app/src/main/assets/runtime/$abi/tar"
    "$strip" "$build/src/tar" -o "$destination"
    chmod 755 "$destination"
    echo "$abi: $(shasum -a 256 "$destination" | cut -d ' ' -f 1)"
}

if [[ "$#" -eq 0 ]]; then
    set -- arm64-v8a armeabi-v7a x86_64
fi

for abi in "$@"; do
    case "$abi" in
        arm64-v8a) build_abi "$abi" aarch64-linux-android aarch64-linux-android ;;
        armeabi-v7a) build_abi "$abi" armv7a-linux-androideabi arm-linux-androideabi ;;
        x86_64) build_abi "$abi" x86_64-linux-android x86_64-linux-android ;;
        *)
            echo "Unsupported ABI: $abi" >&2
            exit 2
            ;;
    esac
done
