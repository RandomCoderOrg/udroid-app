#!/usr/bin/env python3
"""Move an existing X11 window repeatedly to probe CopyWindow/Expose integrity."""

import ctypes
import ctypes.util
import math
import sys
import time


X = ctypes.CDLL(ctypes.util.find_library("X11"))
Display = ctypes.c_void_p
Window = ctypes.c_ulong

X.XOpenDisplay.argtypes = [ctypes.c_char_p]
X.XOpenDisplay.restype = Display
X.XQueryTree.argtypes = [
    Display,
    Window,
    ctypes.POINTER(Window),
    ctypes.POINTER(Window),
    ctypes.POINTER(ctypes.POINTER(Window)),
    ctypes.POINTER(ctypes.c_uint),
]
X.XQueryTree.restype = ctypes.c_int
X.XMoveWindow.argtypes = [Display, Window, ctypes.c_int, ctypes.c_int]
X.XMoveWindow.restype = ctypes.c_int
X.XSync.argtypes = [Display, ctypes.c_int]
X.XSync.restype = ctypes.c_int


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: x11_move_probe.py WINDOW_ID", file=sys.stderr)
        return 2

    display = X.XOpenDisplay(None)
    if not display:
        print("cannot open DISPLAY", file=sys.stderr)
        return 1

    client = Window(int(sys.argv[1], 0))
    root = Window()
    parent = Window()
    children = ctypes.POINTER(Window)()
    count = ctypes.c_uint()
    if not X.XQueryTree(
        display,
        client,
        ctypes.byref(root),
        ctypes.byref(parent),
        ctypes.byref(children),
        ctypes.byref(count),
    ):
        print("XQueryTree failed", file=sys.stderr)
        return 1

    target = parent if parent.value != root.value else client
    started = time.monotonic()
    frames = 0
    while time.monotonic() - started < 8.0:
        phase = frames / 60.0
        x = 80 + int(230 * (1.0 + math.sin(phase * 2.3)))
        y = 280 + int(300 * (1.0 + math.sin(phase * 1.7)))
        X.XMoveWindow(display, target, x, y)
        X.XSync(display, 0)
        frames += 1
        time.sleep(1.0 / 60.0)

    print(f"moved window {hex(target.value)} for {frames} frames")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
