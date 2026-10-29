#!/usr/bin/env python3
"""Inspect and control a connected uDroid dev build without tapping its UI."""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(args, *, serial=None, check=True):
    command = [os.environ.get("ADB", "adb")]
    if serial:
        command += ["-s", serial]
    result = subprocess.run(command + args, capture_output=True, text=True)
    if check and result.returncode:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return result


def private_file(package, path, serial):
    return adb(["exec-out", "run-as", package, "cat", path], serial=serial).stdout


def preferences(package, name, serial):
    result = adb(
        ["exec-out", "run-as", package, "cat", f"shared_prefs/{name}.xml"],
        serial=serial,
        check=False,
    )
    if result.returncode:
        if "No such file or directory" in result.stderr:
            return {}
        raise RuntimeError((result.stderr or result.stdout).strip())
    root = ET.fromstring(result.stdout)
    return {
        element.attrib["name"]: element.attrib.get("value", element.text or "")
        for element in root
    }


def show_status(package, serial):
    state = preferences(package, "runtime-supervisor-state", serial)
    active = preferences(package, "installed-rootfs-state", serial).get("active-rootfs", "none")
    print(f"Active Linux: {active}")
    print(
        f"Terminal: {state.get('phase', 'unknown')} · "
        f"{state.get('rootfs-name', 'none')} · {state.get('message', '')}"
    )
    print(
        f"Desktop: {state.get('desktop-phase', 'unknown')} · "
        f"{state.get('desktop-environment-name', 'none')} · "
        f"display :{state.get('desktop-display-number', '?')} · "
        f"{state.get('desktop-message', '')}"
    )
    print(f"Boot ID: {state.get('boot-id', 'none')}")


def show_roots(package, serial):
    result = adb(
        ["exec-out", "run-as", package, "ls", "-1", "files/rootfs"],
        serial=serial,
        check=False,
    )
    if result.returncode:
        print("No Linux systems found")
        return
    for name in result.stdout.splitlines():
        if not name or "/" in name:
            continue
        marker = f"files/rootfs/{name}/.udroid-ready"
        ready = adb(
            ["exec-out", "run-as", package, "test", "-f", marker],
            serial=serial,
            check=False,
        ).returncode == 0
        print(f"{name}{'' if ready else ' (incomplete)'}")


def show_logs(package, serial, limit, raw):
    lines = private_file(package, "files/logs/supervisor.jsonl", serial).splitlines()[-limit:]
    for line in lines:
        if raw:
            print(line)
            continue
        event = json.loads(line)
        print(
            f"{event.get('timestamp', '?')} "
            f"[{event.get('component', '?')}/{event.get('severity', '?')}] "
            f"{event.get('event', '?')}: {event.get('message', '')}"
        )


def wait_for_desktop(package, serial, rootfs, timeout):
    deadline = time.monotonic() + timeout
    last_message = "Desktop state is unavailable"
    while time.monotonic() < deadline:
        state = preferences(package, "runtime-supervisor-state", serial)
        phase = state.get("desktop-phase", "unknown")
        last_message = state.get("desktop-message", last_message)
        if phase == "RUNNING" and state.get("rootfs-name") == rootfs:
            return
        if phase == "CRASHED":
            raise RuntimeError(last_message)
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for {rootfs} desktop: {last_message}")


def launch_application(package, serial, rootfs, application_id):
    adb(
        [
            "shell", "am", "start", "-n",
            f"{package}/org.randomcoder.udroid.MainActivity",
            "-a", "org.randomcoder.udroid.action.LAUNCH_LINUX_APPLICATION",
            "--es", "org.randomcoder.udroid.extra.LINUX_APPLICATION_ID", application_id,
            "--es", "org.randomcoder.udroid.extra.ROOTFS_NAME", rootfs,
            "-f", "0x24000000",  # CLEAR_TOP | SINGLE_TOP
        ],
        serial=serial,
    )


def accessibility_node_center(document, description, package=None):
    root = ET.fromstring(document)
    for node in root.iter("node"):
        if node.attrib.get("content-desc") != description:
            continue
        if package and node.attrib.get("package") != package:
            continue
        match = re.fullmatch(
            r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
            node.attrib.get("bounds", ""),
        )
        if match:
            left, top, right, bottom = map(int, match.groups())
            return (left + right) // 2, (top + bottom) // 2
    return None


def collapse_desktop_controls(package, serial, timeout):
    remote = "/sdcard/udroid-debug-window.xml"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        adb(["shell", "rm", "-f", remote], serial=serial, check=False)
        adb(["shell", "uiautomator", "dump", remote], serial=serial, check=False)
        result = adb(["exec-out", "cat", remote], serial=serial, check=False)
        if result.returncode == 0:
            center = accessibility_node_center(
                result.stdout,
                "Collapse desktop controls",
                package,
            )
            if center:
                adb(["shell", "input", "tap", str(center[0]), str(center[1])], serial=serial)
                return
        time.sleep(0.5)
    raise RuntimeError("Desktop opened, but its Collapse desktop controls button was not found")


def send_command(package, serial, command, rootfs, environment):
    args = [
        "shell", "am", "broadcast", "--receiver-foreground",
        "-n", f"{package}/org.randomcoder.udroid.DebugCommandReceiver",
        "-a", "org.randomcoder.udroid.action.DEBUG_COMMAND",
        "--es", "command", command,
    ]
    if rootfs:
        args += ["--es", "rootfs", rootfs]
    if environment:
        args += ["--es", "environment", environment]
    result = adb(args, serial=serial)
    output = result.stdout.strip()
    if not re.search(r"result=-1(?:\D|$)", output):
        raise RuntimeError(output or "uDroid did not accept the debug command")
    adb(
        [
            "shell", "am", "start", "-n",
            f"{package}/org.randomcoder.udroid.MainActivity",
            "-f", "0x24000000",  # CLEAR_TOP | SINGLE_TOP
        ],
        serial=serial,
    )
    print(output)


def reproduce_damage(package, serial, rootfs, environment, application, timeout, keep_controls):
    state = preferences(package, "runtime-supervisor-state", serial)
    if state.get("desktop-phase") != "STOPPED":
        send_command(package, serial, "stop-desktop", None, None)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = preferences(package, "runtime-supervisor-state", serial)
            if state.get("desktop-phase") == "STOPPED":
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Timed out stopping the existing desktop")
    send_command(package, serial, "start-desktop", rootfs, environment)
    wait_for_desktop(package, serial, rootfs, timeout)
    launch_application(package, serial, rootfs, application)
    if not keep_controls:
        collapse_desktop_controls(package, serial, timeout)
    adb(["shell", "input", "keyevent", "KEYCODE_ESCAPE"], serial=serial)
    print(
        f"Damage reproduction ready: {environment} + {application}. "
        "Drag the window, then type glxgears one character at a time."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--package", default="org.randomcoder.udroid.dev")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("status", "roots", "open-system", "open-desktop", "start-terminal", "stop-terminal", "start-desktop", "stop-desktop"):
        command = sub.add_parser(name)
        if name in ("open-system", "start-terminal", "start-desktop"):
            command.add_argument("--rootfs", help="Installed Linux system; defaults to active")
        if name == "start-desktop":
            command.add_argument("--environment", help="Desktop session ID, e.g. gnome or xfce")
    logs = sub.add_parser("logs")
    logs.add_argument("--limit", type=int, default=80)
    logs.add_argument("--raw", action="store_true", help="Print original JSONL")
    damage = sub.add_parser("repro-damage", help="Open the Panfrost GNOME damage reproduction")
    damage.add_argument("--rootfs", default="noble-raw")
    damage.add_argument("--environment", default="udroid-gnome-panfrost")
    damage.add_argument("--application", default="Alacritty", help="Desktop application ID")
    damage.add_argument("--timeout", type=float, default=30)
    damage.add_argument("--keep-controls", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "status":
            show_status(args.package, args.serial)
        elif args.command == "roots":
            show_roots(args.package, args.serial)
        elif args.command == "logs":
            if args.limit < 1:
                parser.error("--limit must be positive")
            show_logs(args.package, args.serial, args.limit, args.raw)
        elif args.command == "repro-damage":
            if args.timeout <= 0:
                parser.error("--timeout must be positive")
            reproduce_damage(
                args.package,
                args.serial,
                args.rootfs,
                args.environment,
                args.application,
                args.timeout,
                args.keep_controls,
            )
        else:
            send_command(
                args.package,
                args.serial,
                args.command,
                getattr(args, "rootfs", None),
                getattr(args, "environment", None),
            )
    except (OSError, RuntimeError, ET.ParseError, json.JSONDecodeError) as error:
        print(f"udroid-debug: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
