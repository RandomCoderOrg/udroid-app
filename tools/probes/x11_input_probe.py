#!/usr/bin/env python3
"""Inject deterministic X11 pointer motion/clicks through XTEST."""

import ctypes
import os
import sys
import time


def main() -> int:
    if len(sys.argv) < 3:
        raise SystemExit("usage: x11_input_probe.py X Y [click | key KEYSYM]")

    x, y = map(int, sys.argv[1:3])
    action = sys.argv[3] if len(sys.argv) > 3 else None
    x11 = ctypes.CDLL("libX11.so.6")
    xtst = ctypes.CDLL("libXtst.so.6")
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XFlush.argtypes = [ctypes.c_void_p]
    x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
    x11.XStringToKeysym.restype = ctypes.c_ulong
    x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    x11.XKeysymToKeycode.restype = ctypes.c_uint
    xtst.XTestFakeMotionEvent.argtypes = [
        ctypes.c_void_p,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_ulong,
    ]
    xtst.XTestFakeButtonEvent.argtypes = [
        ctypes.c_void_p,
        ctypes.c_uint,
        ctypes.c_int,
        ctypes.c_ulong,
    ]
    xtst.XTestFakeKeyEvent.argtypes = [
        ctypes.c_void_p,
        ctypes.c_uint,
        ctypes.c_int,
        ctypes.c_ulong,
    ]

    display = x11.XOpenDisplay(os.environ.get("DISPLAY", ":0").encode())
    if not display:
        raise SystemExit("could not open X display")
    if not xtst.XTestFakeMotionEvent(display, -1, x, y, 0):
        raise SystemExit("XTestFakeMotionEvent failed")
    x11.XFlush(display)
    time.sleep(0.15)
    if action == "click":
        xtst.XTestFakeButtonEvent(display, 1, 1, 0)
        xtst.XTestFakeButtonEvent(display, 1, 0, 0)
        x11.XFlush(display)
    elif action == "key":
        if len(sys.argv) != 5:
            raise SystemExit("key action requires a KEYSYM")
        keysym = x11.XStringToKeysym(sys.argv[4].encode())
        keycode = x11.XKeysymToKeycode(display, keysym)
        if not keysym or not keycode:
            raise SystemExit(f"could not resolve keysym: {sys.argv[4]}")
        xtst.XTestFakeKeyEvent(display, keycode, 1, 0)
        xtst.XTestFakeKeyEvent(display, keycode, 0, 0)
        x11.XFlush(display)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
