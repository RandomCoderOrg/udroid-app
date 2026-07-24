#!/bin/sh
set -eu

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ANDROID_NDK_HOME must point to an installed Android NDK" >&2
    exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"

build_probe() {
    abi=$1
    compiler=$2
    output_dir="$script_dir/../assets/runtime/$abi"
    mkdir -p "$output_dir"

    "$toolchain/$compiler" \
        -std=c11 -O2 -fPIE -pie -Wall -Wextra -Werror \
        "$script_dir/runtime_probe.c" \
        -o "$output_dir/runtime_probe"

    "$toolchain/llvm-strip" "$output_dir/runtime_probe"
    echo "built $output_dir/runtime_probe"
}

build_probe arm64-v8a aarch64-linux-android26-clang
build_probe armeabi-v7a armv7a-linux-androideabi26-clang
build_probe x86_64 x86_64-linux-android26-clang
