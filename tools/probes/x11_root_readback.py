#!/usr/bin/env python3
"""Read an X11 drawable through XGetImage and report deterministic pixel stats."""

import ctypes
import ctypes.util
import hashlib
import sys


X = ctypes.CDLL(ctypes.util.find_library("X11"))
Display = ctypes.c_void_p
Window = ctypes.c_ulong

X.XOpenDisplay.argtypes = [ctypes.c_char_p]
X.XOpenDisplay.restype = Display
X.XDefaultRootWindow.argtypes = [Display]
X.XDefaultRootWindow.restype = Window
X.XGetImage.argtypes = [
    Display,
    Window,
    ctypes.c_int,
    ctypes.c_int,
    ctypes.c_uint,
    ctypes.c_uint,
    ctypes.c_ulong,
    ctypes.c_int,
]
X.XGetImage.restype = ctypes.c_void_p
X.XGetPixel.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
X.XGetPixel.restype = ctypes.c_ulong


def main() -> int:
    if len(sys.argv) not in (6, 7):
        print(
            "usage: x11_root_readback.py X Y WIDTH HEIGHT OUTPUT.ppm|- [WINDOW_ID]",
            file=sys.stderr,
        )
        return 2

    x, y, width, height = (int(value, 0) for value in sys.argv[1:5])
    display = X.XOpenDisplay(None)
    if not display:
        print("cannot open DISPLAY", file=sys.stderr)
        return 1

    target = (
        int(sys.argv[6], 0)
        if len(sys.argv) == 7
        else X.XDefaultRootWindow(display)
    )
    image = X.XGetImage(
        display,
        target,
        x,
        y,
        width,
        height,
        ctypes.c_ulong(~0).value,
        2,  # ZPixmap
    )
    if not image:
        print("XGetImage failed", file=sys.stderr)
        return 1

    output = None
    if sys.argv[5] != "-":
        output = open(sys.argv[5], "wb")
        output.write(f"P6\n{width} {height}\n255\n".encode("ascii"))

    digest = hashlib.sha256()
    nonblack = 0
    channel_minimum = 255
    channel_maximum = 0
    channel_sums = [0, 0, 0]
    unique_rgb = set()
    first_rgb = None
    try:
        row = bytearray(width * 3)
        for py in range(height):
            for px in range(width):
                pixel = X.XGetPixel(image, px, py)
                offset = px * 3
                row[offset] = (pixel >> 16) & 0xFF
                row[offset + 1] = (pixel >> 8) & 0xFF
                row[offset + 2] = pixel & 0xFF
                rgb = row[offset : offset + 3]
                if first_rgb is None:
                    first_rgb = bytes(rgb)
                nonblack += any(rgb)
                channel_minimum = min(channel_minimum, *rgb)
                channel_maximum = max(channel_maximum, *rgb)
                channel_sums[0] += rgb[0]
                channel_sums[1] += rgb[1]
                channel_sums[2] += rgb[2]
                if len(unique_rgb) <= 4096:
                    unique_rgb.add(bytes(rgb))
            digest.update(row)
            if output:
                output.write(row)
    finally:
        if output:
            output.close()

    first_hex = (first_rgb or b"\0\0\0").hex()
    pixel_count = width * height
    means = tuple(total / pixel_count for total in channel_sums)
    unique_summary = ">4096" if len(unique_rgb) > 4096 else str(len(unique_rgb))
    print(
        "X11_READBACK "
        f"window=0x{target:x} region={width}x{height}+{x}+{y} "
        f"sha256={digest.hexdigest()} nonblack={nonblack}/{pixel_count} "
        f"min={channel_minimum} max={channel_maximum} "
        f"mean={means[0]:.2f},{means[1]:.2f},{means[2]:.2f} "
        f"unique={unique_summary} first_rgb={first_hex}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
