#!/usr/bin/env python3
"""Capture the X11 root window to a binary PPM without extra guest packages."""

import ctypes
import sys


class XImage(ctypes.Structure):
    _fields_ = [
        ("width", ctypes.c_int),
        ("height", ctypes.c_int),
        ("xoffset", ctypes.c_int),
        ("format", ctypes.c_int),
        ("data", ctypes.c_void_p),
        ("byte_order", ctypes.c_int),
        ("bitmap_unit", ctypes.c_int),
        ("bitmap_bit_order", ctypes.c_int),
        ("bitmap_pad", ctypes.c_int),
        ("depth", ctypes.c_int),
        ("bytes_per_line", ctypes.c_int),
        ("bits_per_pixel", ctypes.c_int),
        ("red_mask", ctypes.c_ulong),
        ("green_mask", ctypes.c_ulong),
        ("blue_mask", ctypes.c_ulong),
        ("obdata", ctypes.c_void_p),
        ("funcs", ctypes.c_void_p * 8),
    ]


def mask_component(pixel: int, mask: int) -> int:
    if not mask:
        return 0
    shift = (mask & -mask).bit_length() - 1
    value = (pixel & mask) >> shift
    maximum = mask >> shift
    return (value * 255 + maximum // 2) // maximum


def main() -> int:
    output = sys.argv[1] if len(sys.argv) > 1 else "/tmp/x11-root.ppm"
    x11 = ctypes.CDLL("libX11.so.6")
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XDefaultScreen.argtypes = [ctypes.c_void_p]
    x11.XDefaultScreen.restype = ctypes.c_int
    x11.XDisplayWidth.argtypes = [ctypes.c_void_p, ctypes.c_int]
    x11.XDisplayWidth.restype = ctypes.c_int
    x11.XDisplayHeight.argtypes = [ctypes.c_void_p, ctypes.c_int]
    x11.XDisplayHeight.restype = ctypes.c_int
    x11.XRootWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]
    x11.XRootWindow.restype = ctypes.c_ulong
    x11.XGetImage.argtypes = [
        ctypes.c_void_p,
        ctypes.c_ulong,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_uint,
        ctypes.c_uint,
        ctypes.c_ulong,
        ctypes.c_int,
    ]
    x11.XGetImage.restype = ctypes.POINTER(XImage)
    x11.XDestroyImage.argtypes = [ctypes.POINTER(XImage)]
    x11.XDestroyImage.restype = ctypes.c_int
    x11.XCloseDisplay.argtypes = [ctypes.c_void_p]

    display = x11.XOpenDisplay(b":0")
    if not display:
        raise RuntimeError("XOpenDisplay(:0) failed")
    image = None
    try:
        screen = x11.XDefaultScreen(display)
        width = x11.XDisplayWidth(display, screen)
        height = x11.XDisplayHeight(display, screen)
        root = x11.XRootWindow(display, screen)
        image = x11.XGetImage(
            display,
            root,
            0,
            0,
            width,
            height,
            ctypes.c_ulong(-1).value,
            2,  # ZPixmap
        )
        if not image:
            raise RuntimeError("XGetImage(root) failed")
        info = image.contents
        if info.bits_per_pixel not in (24, 32):
            raise RuntimeError(f"unsupported bits_per_pixel={info.bits_per_pixel}")
        pixel_bytes = info.bits_per_pixel // 8
        raw = ctypes.string_at(info.data, info.bytes_per_line * info.height)
        with open(output, "wb") as stream:
            stream.write(f"P6\n{info.width} {info.height}\n255\n".encode())
            row = bytearray(info.width * 3)
            for y in range(info.height):
                base = y * info.bytes_per_line
                for x in range(info.width):
                    offset = base + x * pixel_bytes
                    pixel = int.from_bytes(
                        raw[offset : offset + pixel_bytes],
                        "little" if info.byte_order == 0 else "big",
                    )
                    out = x * 3
                    row[out] = mask_component(pixel, info.red_mask)
                    row[out + 1] = mask_component(pixel, info.green_mask)
                    row[out + 2] = mask_component(pixel, info.blue_mask)
                stream.write(row)
        print(
            f"captured {info.width}x{info.height} depth={info.depth} "
            f"bpp={info.bits_per_pixel} stride={info.bytes_per_line} to {output}"
        )
    finally:
        if image:
            x11.XDestroyImage(image)
        x11.XCloseDisplay(display)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
