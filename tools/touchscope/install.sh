#!/bin/sh
set -eu

cd "$(dirname "$0")"

install_dependencies=false
prefix=/usr/local

while [ "$#" -gt 0 ]; do
    case "$1" in
        --install-dependencies)
            install_dependencies=true
            ;;
        --prefix)
            shift
            [ "$#" -gt 0 ] || {
                echo "touchscope: --prefix requires a path" >&2
                exit 2
            }
            prefix=$1
            ;;
        --help|-h)
            echo "Usage: ./install.sh [--install-dependencies] [--prefix PATH]"
            exit 0
            ;;
        *)
            echo "touchscope: unknown option: $1" >&2
            exit 2
            ;;
    esac
    shift
done

if [ "$install_dependencies" = true ]; then
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update
        apt-get install -y build-essential pkg-config libx11-dev libxi-dev
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache build-base pkgconf libx11-dev libxi-dev
    elif command -v pacman >/dev/null 2>&1; then
        pacman -S --needed --noconfirm base-devel pkgconf libx11 libxi
    elif command -v xbps-install >/dev/null 2>&1; then
        xbps-install -Sy base-devel pkg-config libX11-devel libXi-devel
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y gcc make pkgconf-pkg-config libX11-devel libXi-devel
    else
        echo "touchscope: unsupported package manager; install a C compiler, make, pkg-config, libX11 headers, and libXi headers" >&2
        exit 1
    fi
fi

missing=
for command_name in make "${CC:-cc}" "${PKG_CONFIG:-pkg-config}"; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        missing="$missing $command_name"
    fi
done

if [ -n "$missing" ]; then
    echo "touchscope: missing tools:$missing" >&2
    echo "Run ./install.sh --install-dependencies inside the Linux system." >&2
    exit 1
fi

if ! "${PKG_CONFIG:-pkg-config}" --exists x11 xi; then
    echo "touchscope: X11/XInput2 development packages are missing" >&2
    echo "Run ./install.sh --install-dependencies inside the Linux system." >&2
    exit 1
fi

make clean
make
make install PREFIX="$prefix"
echo "touchscope: installed $prefix/bin/touchscope"
