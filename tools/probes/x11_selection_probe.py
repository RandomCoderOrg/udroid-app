#!/usr/bin/env python3
"""Report the owner of an X11 selection such as _NET_WM_CM_S0."""

import ctypes
import os
import sys


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: x11_selection_probe.py SELECTION")

    x11 = ctypes.CDLL("libX11.so.6")
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    x11.XInternAtom.restype = ctypes.c_ulong
    x11.XGetSelectionOwner.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    x11.XGetSelectionOwner.restype = ctypes.c_ulong

    display = x11.XOpenDisplay(os.environ.get("DISPLAY", ":0").encode())
    if not display:
        raise SystemExit("could not open X display")
    atom = x11.XInternAtom(display, sys.argv[1].encode(), 0)
    owner = x11.XGetSelectionOwner(display, atom)
    print(f"{sys.argv[1]} owner=0x{owner:x}")
    return 0 if owner else 1


if __name__ == "__main__":
    raise SystemExit(main())
