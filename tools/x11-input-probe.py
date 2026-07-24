#!/usr/bin/env python3
"""Minimal dependency-free X11 input probe for uDroid device tests.

The probe speaks enough of the X11 core protocol to create one window and log
pointer, button, focus, and keyboard events. It intentionally uses only the
Python standard library so a minimal PRoot image can run it without xev,
python-xlib, a compiler, or network access.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys


EVENT_NAMES = {
    2: "key-press",
    3: "key-release",
    4: "button-press",
    5: "button-release",
    6: "pointer-motion",
    7: "pointer-enter",
    8: "pointer-leave",
    9: "focus-in",
    10: "focus-out",
    12: "expose",
    17: "destroy",
}

KEY_PRESS_MASK = 1 << 0
KEY_RELEASE_MASK = 1 << 1
BUTTON_PRESS_MASK = 1 << 2
BUTTON_RELEASE_MASK = 1 << 3
ENTER_WINDOW_MASK = 1 << 4
LEAVE_WINDOW_MASK = 1 << 5
POINTER_MOTION_MASK = 1 << 6
EXPOSURE_MASK = 1 << 15
STRUCTURE_NOTIFY_MASK = 1 << 17
FOCUS_CHANGE_MASK = 1 << 21

CW_BACK_PIXEL = 1 << 1
CW_EVENT_MASK = 1 << 11


def read_exact(connection: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise RuntimeError("X11 connection closed")
        data.extend(chunk)
    return bytes(data)


def connect_x11(socket_path: str) -> tuple[socket.socket, int, int, int]:
    connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.connect(socket_path)
    connection.sendall(struct.pack("<BBHHHHH", ord("l"), 0, 11, 0, 0, 0, 0))

    prefix = read_exact(connection, 8)
    status, reason_length, major, minor, extra_words = struct.unpack(
        "<BBHHH", prefix
    )
    payload = read_exact(connection, extra_words * 4)
    if status != 1:
        reason = payload[:reason_length].decode("utf-8", "replace")
        raise RuntimeError(f"X11 setup failed ({major}.{minor}): {reason}")

    resource_base, resource_mask = struct.unpack_from("<II", payload, 4)
    vendor_length = struct.unpack_from("<H", payload, 16)[0]
    root_count = payload[20]
    format_count = payload[21]
    if root_count == 0:
        raise RuntimeError("X11 server reported no screens")

    roots_offset = 32 + ((vendor_length + 3) & ~3) + format_count * 8
    root_window = struct.unpack_from("<I", payload, roots_offset)[0]
    background_pixel = struct.unpack_from("<I", payload, roots_offset + 8)[0]
    window_id = resource_base | (1 & resource_mask)
    return connection, root_window, background_pixel, window_id


def create_window(
    connection: socket.socket,
    root_window: int,
    background_pixel: int,
    window_id: int,
    geometry: tuple[int, int, int, int],
) -> None:
    x, y, width, height = geometry
    event_mask = (
        KEY_PRESS_MASK
        | KEY_RELEASE_MASK
        | BUTTON_PRESS_MASK
        | BUTTON_RELEASE_MASK
        | ENTER_WINDOW_MASK
        | LEAVE_WINDOW_MASK
        | POINTER_MOTION_MASK
        | EXPOSURE_MASK
        | STRUCTURE_NOTIFY_MASK
        | FOCUS_CHANGE_MASK
    )
    value_mask = CW_BACK_PIXEL | CW_EVENT_MASK
    request = struct.pack(
        "<BBHIIhhHHHHII",
        1,
        0,
        10,
        window_id,
        root_window,
        x,
        y,
        width,
        height,
        2,
        1,
        0,
        value_mask,
    )
    request += struct.pack("<II", background_pixel, event_mask)
    connection.sendall(request)
    connection.sendall(struct.pack("<BBHI", 8, 0, 2, window_id))
    connection.sendall(struct.pack("<BBHII", 42, 1, 3, window_id, 0))


def log_event(event: bytes) -> bool:
    event_type = event[0] & 0x7F
    name = EVENT_NAMES.get(event_type)
    if name is None:
        return True
    if event_type == 17:
        print("event=destroy", flush=True)
        return False
    if event_type in (9, 10, 12):
        print(f"event={name}", flush=True)
        return True

    detail = event[1]
    root_x, root_y, event_x, event_y, state = struct.unpack_from(
        "<hhhhH", event, 20
    )
    print(
        f"event={name} detail={detail} root={root_x},{root_y} "
        f"window={event_x},{event_y} state=0x{state:04x}",
        flush=True,
    )
    return True


def parse_geometry(value: str) -> tuple[int, int, int, int]:
    try:
        size, position = value.split("+", 1)
        width, height = (int(part) for part in size.split("x", 1))
        x, y = (int(part) for part in position.split("+", 1))
    except (ValueError, TypeError) as error:
        raise argparse.ArgumentTypeError("expected WIDTHxHEIGHT+X+Y") from error
    return x, y, width, height


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", default="/tmp/.X11-unix/X0")
    parser.add_argument(
        "--geometry",
        type=parse_geometry,
        default=parse_geometry("700x600+120+300"),
    )
    args = parser.parse_args()

    connection, root_window, background_pixel, window_id = connect_x11(args.socket)
    create_window(connection, root_window, background_pixel, window_id, args.geometry)
    print(
        f"ready window=0x{window_id:08x} geometry={args.geometry}",
        flush=True,
    )
    try:
        while log_event(read_exact(connection, 32)):
            pass
    except KeyboardInterrupt:
        return 130
    finally:
        connection.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
