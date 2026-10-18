#!/usr/bin/env python3
"""Inspect and control a connected uDroid dev build without tapping its UI."""

import argparse
import json
import os
import re
import subprocess
import sys
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
