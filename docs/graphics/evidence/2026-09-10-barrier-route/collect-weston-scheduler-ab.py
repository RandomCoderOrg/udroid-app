#!/usr/bin/env python3
"""Collect one atomic 7 ms / 16 ms Weston scheduler A/B on Android.

The caller supplies a trusted, already shell-quoted PRoot prefix.  This script
only owns the two Weston processes it launches; it never starts/stops the
Android activity or kills processes by name.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import shlex
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Sequence


ACTIVITY = (
    "org.randomcoder.udroid.dev/"
    "org.randomcoder.udroid.gfxstream.GfxstreamPresenterProbeActivity"
)
REMOTE_LAUNCHER = "/root/launch-weston-scheduler-ab.sh"
REMOTE_STAGE = "/root/udroid-weston-socket-v1"
REMOTE_RUNTIME = "/run/weston"
HOST_SOCKET = "/tmp/kumquat-gpu-0"
RUN_LABEL_RE = re.compile(r"^[A-Za-z0-9._-]{1,48}$")
PROOT_SIGNAL_RE = re.compile(r"(?:proot[^\n]*(?:signal|killed)|killed by signal)", re.I)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ADB_CALL_TIMEOUT = 15.0


class CollectionError(RuntimeError):
    pass


@dataclass(frozen=True)
class Timing:
    minimum_warmup: float = 12.0
    startup_timeout: float = 45.0
    measurement: float = 60.0
    poll: float = 0.25
    remote_term_timeout: float = 5.0
    remote_kill_timeout: float = 2.0
    local_wait_timeout: float = 3.0


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    temporary = path.with_suffix(path.suffix + f".tmp.{os.getpid()}")
    temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    os.replace(temporary, path)


def parse_complete_reports(text: str) -> list[dict[str, Any]]:
    """Return only complete, internally count-matched five-line reports."""
    timestamp = r"\[([^\]]+)\]"
    pattern = re.compile(
        timestamp
        + r" GL repaint stages at frame (\d+) \(avg/max us\):[^\n]*\n"
        + timestamp
        + r" gfxstream output: presents=(\d+) releases=(\d+) [^\n]*"
        + r"no-free-slot=(\d+)\.\n"
        + timestamp
        + r" gfxstream cadence: [^\n]*\n"
        + timestamp
        + r" gfxstream repaint: [^\n]*\n"
        + timestamp
        + r" gfxstream inter-present buckets \(n=(\d+)\): [^\n]*"
        + r"counts=(\d+)/(\d+)/(\d+)/(\d+)/(\d+)/(\d+)\.",
        re.MULTILINE,
    )
    reports: list[dict[str, Any]] = []
    for match in pattern.finditer(text):
        frames = int(match.group(2))
        presents = int(match.group(4))
        releases = int(match.group(5))
        no_free = int(match.group(6))
        bucket_n = int(match.group(10))
        buckets = tuple(int(match.group(index)) for index in range(11, 17))
        if not (frames == presents == releases):
            continue
        if no_free != 0 or bucket_n != frames - 1 or sum(buckets) != bucket_n:
            continue
        reports.append(
            {
                "frames": frames,
                "timestamp": match.group(9),
                "start": match.start(),
                "end": match.end(),
            }
        )
    return reports


def last_complete_report(text: str, minimum_frames: int = 0) -> dict[str, Any]:
    reports = [r for r in parse_complete_reports(text) if r["frames"] >= minimum_frames]
    if not reports:
        raise CollectionError(
            f"no complete matched diagnostic report at or above frame {minimum_frames}"
        )
    return reports[-1]


def normalized_activity(component: str) -> str:
    package, separator, activity = component.partition("/")
    if not separator:
        return component
    if activity.startswith("."):
        activity = package + activity
    return f"{package}/{activity}"


def foreground_activity(text: str) -> str:
    lines = text.splitlines()
    display_zero = next(
        (index for index, line in enumerate(lines) if re.match(r"\s*Display #0\b", line)),
        None,
    )
    if display_zero is not None:
        lines = lines[display_zero + 1 :]
        next_display = next(
            (index for index, line in enumerate(lines) if re.match(r"\s*Display #\d+\b", line)),
            len(lines),
        )
        lines = lines[:next_display]

    line = next(
        (line for line in lines if re.search(r"\btopResumedActivity\s*[:=]", line)),
        None,
    )
    if line is None:
        line = next(
            (
                line
                for line in lines
                if re.search(r"\b(?:mResumedActivity|ResumedActivity)\s*[:=]", line)
            ),
            "",
        )
    activities = {
        normalized_activity(token)
        for token in re.findall(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+", line)
    }
    if len(activities) != 1:
        raise CollectionError(
            f"expected exactly one foreground activity, found {sorted(activities)}"
        )
    return activities.pop()


def require_foreground(text: str) -> None:
    actual = foreground_activity(text)
    if actual != ACTIVITY:
        raise CollectionError(f"foreground activity is {actual!r}, expected {ACTIVITY!r}")


def validate_timestats(text: str, expected_duration: float) -> dict[str, int]:
    starts = re.findall(r"^statsStart = (\d+)$", text, re.MULTILINE)
    ends = re.findall(r"^statsEnd = (\d+)$", text, re.MULTILINE)
    if len(starts) != 1 or len(ends) != 1:
        raise CollectionError("timestats must contain exactly one statsStart/statsEnd")
    start, end = int(starts[0]), int(ends[0])
    duration = end - start
    minimum = max(1.0, expected_duration - 1.0)
    maximum = expected_duration + 1.0
    if duration <= 0 or not minimum <= duration <= maximum:
        raise CollectionError(
            f"timestats duration {duration}s is outside {minimum:g}..{maximum:g}s"
        )
    target = re.compile(
        r"^layerName = [^\n]* SurfaceView\[" + re.escape(ACTIVITY) + r"\]\(BLAST\)#\d+$",
        re.MULTILINE,
    )
    blocks = [
        block
        for block in re.split(r"(?=^displayRefreshRate =)", text, flags=re.MULTILINE)
        if target.search(block)
    ]
    if len(blocks) != 1:
        raise CollectionError(
            f"timestats expected exactly one target SurfaceView; found {len(blocks)}"
        )
    frames = re.findall(r"^totalFrames = (\d+)$", blocks[0], re.MULTILINE)
    if len(frames) != 1 or int(frames[0]) <= 0:
        raise CollectionError("target SurfaceView has no frames")
    return {"stats_start": start, "stats_end": end, "total_frames": int(frames[0])}


def parse_pid_record(text: str, label: str, socket_name: str, log_path: str) -> dict[str, Any]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and key not in values:
            values[key] = value
    expected = {"label": label, "socket": socket_name, "log": log_path}
    for key, value in expected.items():
        if values.get(key) != value:
            raise CollectionError(f"PID record {key} mismatch")
    try:
        pid = int(values["pid"])
        starttime = int(values["starttime"])
    except (KeyError, ValueError) as error:
        raise CollectionError("PID record has invalid pid/starttime") from error
    if pid <= 1 or starttime <= 0:
        raise CollectionError("PID record has unsafe pid/starttime")
    return {"pid": pid, "starttime": starttime, **expected}


def valid_png(payload: bytes) -> bool:
    return len(payload) >= 1024 and payload.startswith(PNG_SIGNATURE)


class Adb:
    def __init__(self, binary: str, serial: str, guest_prefix: str):
        self.base = [binary, "-s", serial]
        self.guest_prefix = guest_prefix.strip()
        if not self.guest_prefix:
            raise CollectionError("guest prefix is empty")

    def run(self, arguments: Sequence[str], *, binary: bool = False) -> str | bytes:
        try:
            result = subprocess.run(
                [*self.base, *arguments],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=not binary,
                timeout=ADB_CALL_TIMEOUT,
            )
        except subprocess.TimeoutExpired as error:
            raise CollectionError(
                f"adb {' '.join(arguments)} timed out after {ADB_CALL_TIMEOUT:g}s"
            ) from error
        if result.returncode != 0:
            stderr = result.stderr if isinstance(result.stderr, str) else result.stderr.decode(errors="replace")
            raise CollectionError(
                f"adb {' '.join(arguments)} failed ({result.returncode}): {stderr.strip()}"
            )
        return result.stdout

    def shell(self, command: str) -> str:
        output = self.run(["shell", command])
        assert isinstance(output, str)
        return output

    def guest(self, command: str) -> str:
        return self.shell(
            f"{self.guest_prefix} /bin/sh -lc {shlex.quote(command)}"
        )

    def launch(
        self,
        arm: int,
        label: str,
        stdout_file: Any,
        stderr_file: Any,
        winsys_timing: bool = False,
        async_native_fence: bool = False,
    ) -> subprocess.Popen[bytes]:
        timing_environment = "UDROID_SCHEDULER_TIMING=1 " if winsys_timing else ""
        treatment_environment = f"UDROID_ZINK_ASYNC_NATIVE_FENCE={int(async_native_fence)} "
        guest = (
            f"{timing_environment}{treatment_environment}"
            "exec timeout --signal=TERM --kill-after=5s 150s "
            f"{shlex.quote(REMOTE_LAUNCHER)} {arm} {shlex.quote(label)}"
        )
        command = f"{self.guest_prefix} /bin/sh -lc {shlex.quote(guest)}"
        return subprocess.Popen(
            [*self.base, "shell", command],
            stdout=stdout_file,
            stderr=stderr_file,
            start_new_session=True,
        )

    def foreground(self) -> str:
        return self.shell("dumpsys activity activities")

    def timestats(self, action: str) -> str:
        if action not in {"disable", "clear", "enable", "dump"}:
            raise ValueError(action)
        return self.shell(f"dumpsys SurfaceFlinger --timestats -{action}")

    def screenshot(self) -> bytes:
        output = self.run(["exec-out", "screencap", "-p"], binary=True)
        assert isinstance(output, bytes)
        return output


def remote_paths(arm: int, label: str) -> tuple[str, str, str]:
    socket_name = f"wayland-gfxstream-sched{arm}-20260910-{label}"
    log_path = f"{REMOTE_STAGE}/weston-sched{arm}-20260910-{label}.log"
    pid_path = f"{REMOTE_RUNTIME}/weston-sched{arm}-20260910-{label}.pid"
    return socket_name, log_path, pid_path


def owned_process_action(adb: Adb, record: dict[str, Any], action: str) -> str:
    if action not in {"CHECK", "TERM", "KILL"}:
        raise ValueError(action)
    pid = int(record["pid"])
    starttime = int(record["starttime"])
    token = f"--socket={record['socket']}"
    script = f"""
pid={pid}
expected_start={starttime}
if [ ! -r /proc/$pid/stat ]; then echo GONE; exit 0; fi
stat=$(cat /proc/$pid/stat) || {{ echo GONE; exit 0; }}
rest=${{stat#*) }}
set -- $rest
if [ "${{20}}" != "$expected_start" ]; then echo STALE_START; exit 0; fi
if ! tr '\\000' '\\n' </proc/$pid/cmdline | grep -F -x -- {shlex.quote(token)} >/dev/null; then
    echo STALE_CMDLINE
    exit 0
fi
case {action} in
    CHECK) echo ALIVE ;;
    TERM) kill -TERM "$pid" && echo SIGNALED_TERM ;;
    KILL) kill -KILL "$pid" && echo SIGNALED_KILL ;;
esac
""".strip()
    lines = [line.strip() for line in adb.guest(script).splitlines() if line.strip()]
    if not lines:
        raise CollectionError("remote ownership check returned no status")
    return lines[-1]


def stop_local_process(process: subprocess.Popen[bytes], timing: Timing) -> dict[str, Any]:
    result: dict[str, Any] = {"returncode": process.poll()}
    if process.poll() is None:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
        try:
            process.wait(timeout=timing.local_wait_timeout)
        except subprocess.TimeoutExpired:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            process.wait(timeout=timing.local_wait_timeout)
            result["local_kill_required"] = True
    result["returncode"] = process.returncode
    return result


def cleanup_remote(
    adb: Adb,
    record: dict[str, Any] | None,
    timing: Timing,
    monotonic: Callable[[], float],
    sleep: Callable[[float], None],
) -> dict[str, Any]:
    result: dict[str, Any] = {"verified_gone": False}

    def verified_gone(status: str) -> bool:
        return status in {"GONE", "STALE_START"}

    if record is None:
        result["error"] = "no validated remote PID record"
        return result
    try:
        first = owned_process_action(adb, record, "TERM")
        result["term"] = first
        if verified_gone(first):
            result["verified_gone"] = True
            return result
        if first.startswith("STALE_"):
            result["error"] = "remote ownership revalidation failed; no signal sent"
            return result
        deadline = monotonic() + timing.remote_term_timeout
        while monotonic() < deadline:
            status = owned_process_action(adb, record, "CHECK")
            if verified_gone(status):
                result["verified_gone"] = True
                return result
            if status.startswith("STALE_"):
                result["error"] = "remote identity changed while awaiting TERM"
                return result
            sleep(timing.poll)
        killed = owned_process_action(adb, record, "KILL")
        result["kill"] = killed
        if verified_gone(killed):
            result["verified_gone"] = True
            return result
        if killed.startswith("STALE_"):
            result["error"] = "remote ownership revalidation failed before KILL"
            return result
        deadline = monotonic() + timing.remote_kill_timeout
        while monotonic() < deadline:
            status = owned_process_action(adb, record, "CHECK")
            if verified_gone(status):
                result["verified_gone"] = True
                return result
            if status.startswith("STALE_"):
                result["error"] = "remote identity changed while awaiting KILL"
                return result
            sleep(timing.poll)
        result["error"] = "owned remote process remained after TERM/KILL"
    except CollectionError as error:
        result["error"] = str(error)
    return result


def snapshot_remote_log(adb: Adb, remote_log: str, local_path: Path) -> tuple[str, dict[str, Any]]:
    text = adb.guest(f"cat {shlex.quote(remote_log)}")
    local_path.write_text(text)
    return text, last_complete_report(text)


def wait_until(deadline: float, monotonic: Callable[[], float], sleep: Callable[[float], None], poll: float) -> None:
    while True:
        remaining = deadline - monotonic()
        if remaining <= 0:
            return
        sleep(min(poll, remaining))


def collect_arm(
    adb: Adb,
    arm: int,
    output_dir: Path,
    timing: Timing = Timing(),
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    winsys_timing: bool = False,
    async_native_fence: bool = False,
) -> dict[str, Any]:
    label = f"s{arm}-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{os.getpid()}-{secrets.token_hex(3)}"
    if not RUN_LABEL_RE.fullmatch(label):
        raise AssertionError(label)
    socket_name, remote_log, pid_path = remote_paths(arm, label)
    stem = f"weston-sched{arm}-{label}"
    manifest_path = output_dir / f"{stem}.manifest.json"
    stdout_path = output_dir / f"{stem}.stdout"
    stderr_path = output_dir / f"{stem}.stderr"
    warm_path = output_dir / f"{stem}.warm.weston.log"
    final_path = output_dir / f"{stem}.perf.weston.log"
    timestats_path = output_dir / f"{stem}.timestats.txt"
    screenshot_path = output_dir / f"{stem}.png"
    manifest: dict[str, Any] = {
        "schema": 1,
        "valid": False,
        "arm_repaint_window_ms": arm,
        "winsys_timing": winsys_timing,
        "async_native_fence": async_native_fence,
        "run_label": label,
        "socket": socket_name,
        "remote_log": remote_log,
        "remote_pid_record": pid_path,
        "created_utc": utc_now(),
        "errors": [],
        "events": [],
    }
    write_json(manifest_path, manifest)
    process: subprocess.Popen[bytes] | None = None
    record: dict[str, Any] | None = None
    measurement_ok = False
    stats_enabled = False

    def event(name: str, **details: Any) -> None:
        manifest["events"].append(
            {"event": name, "monotonic": monotonic(), "utc": utc_now(), **details}
        )
        write_json(manifest_path, manifest)

    try:
        with stdout_path.open("wb") as stdout_file, stderr_path.open("wb") as stderr_file:
            launch_time = monotonic()
            process = adb.launch(
                arm,
                label,
                stdout_file,
                stderr_file,
                winsys_timing,
                async_native_fence,
            )
            manifest["local_adb_pid"] = process.pid
            manifest["local_adb_pgid"] = os.getpgid(process.pid)
            event("launch", local_adb_pid=process.pid)

            gate_deadline = launch_time + timing.startup_timeout
            warm_text = ""
            warm_report: dict[str, Any] | None = None
            while monotonic() < gate_deadline:
                returncode = process.poll()
                if returncode is not None:
                    raise CollectionError(f"owned adb/guest exited before warm gate: {returncode}")
                try:
                    record_text = adb.guest(f"cat {shlex.quote(pid_path)}")
                    record = parse_pid_record(record_text, label, socket_name, remote_log)
                    warm_text = adb.guest(f"cat {shlex.quote(remote_log)}")
                    if f"--socket={socket_name}" not in warm_text:
                        raise CollectionError("run-labelled socket missing from Weston command line")
                    if f"Output repaint window is {arm} ms maximum." not in warm_text:
                        raise CollectionError("expected repaint-window setting missing from log")
                    candidate = last_complete_report(warm_text, minimum_frames=120)
                    if monotonic() >= launch_time + timing.minimum_warmup:
                        warm_report = candidate
                        break
                except CollectionError:
                    pass
                sleep(timing.poll)
            if warm_report is None:
                raise CollectionError("startup/warm gate timed out")
            warm_path.write_text(warm_text)
            event("warm_snapshot", report=warm_report, launch_age=monotonic() - launch_time)

            require_foreground(adb.foreground())
            event("foreground_start", activity=ACTIVITY)
            adb.timestats("disable")
            adb.timestats("clear")
            enable_started = monotonic()
            stats_enabled = True
            adb.timestats("enable")
            t0 = monotonic()
            event("measurement_start", enable_call_start=enable_started, enable_completion=t0)

            deadline = t0 + timing.measurement
            wait_until(deadline, monotonic, sleep, timing.poll)
            disable_started = monotonic()
            adb.timestats("disable")
            disable_completed = monotonic()
            stats_enabled = False
            deadline_overshoot = disable_started - deadline
            enabled_duration = disable_completed - t0
            event(
                "measurement_stop",
                deadline=deadline,
                disable_call_start=disable_started,
                disable_call_completion=disable_completed,
                deadline_overshoot=deadline_overshoot,
                enabled_duration_to_disable_start=disable_started - t0,
                enabled_duration=enabled_duration,
            )
            if not 0.0 <= deadline_overshoot <= 1.0:
                raise CollectionError(
                    f"measurement stop missed deadline by {deadline_overshoot:.3f}s"
                )
            if process.poll() is not None:
                raise CollectionError(
                    f"owned adb/guest exited during measurement: {process.returncode}"
                )
            foreground_started = monotonic()
            require_foreground(adb.foreground())
            foreground_completed = monotonic()
            event(
                "foreground_end",
                activity=ACTIVITY,
                check_call_start=foreground_started,
                check_call_completion=foreground_completed,
                completion_overshoot=foreground_completed - deadline,
            )
            timestats = adb.timestats("dump")
            timestats_path.write_text(timestats)
            validate_timestats(timestats, timing.measurement)

            final_text, final_report = snapshot_remote_log(adb, remote_log, final_path)
            if final_report["frames"] <= warm_report["frames"]:
                raise CollectionError("no complete post-warm diagnostic aggregate")
            event("final_snapshot", report=final_report)
            if process.poll() is not None:
                raise CollectionError("owned guest exited before post-window capture")
            require_foreground(adb.foreground())
            screenshot = adb.screenshot()
            if not valid_png(screenshot):
                raise CollectionError("post-window screenshot is not a valid nontrivial PNG")
            screenshot_path.write_bytes(screenshot)
            event("post_window_capture", bytes=len(screenshot))
            stdout_file.flush()
            stderr_file.flush()
            if winsys_timing:
                timing_modes = set(
                    re.findall(
                        r"udroid Zink fence flush .*?\basync-native=([01])(?:\s|$)",
                        stderr_path.read_text(errors="replace"),
                    )
                )
                expected_mode = str(int(async_native_fence))
                if timing_modes != {expected_mode}:
                    raise CollectionError(
                        "timing stderr async-native evidence mismatch: "
                        f"expected {expected_mode}, found {sorted(timing_modes)}"
                    )
            pre_cleanup_stdout = stdout_path.read_text(errors="replace")
            if PROOT_SIGNAL_RE.search(pre_cleanup_stdout):
                raise CollectionError("PRoot signal text appeared before requested cleanup")
            measurement_ok = True
    except (CollectionError, OSError, subprocess.SubprocessError) as error:
        manifest["errors"].append(str(error))
    finally:
        if stats_enabled:
            try:
                disable_started = monotonic()
                adb.timestats("disable")
                event(
                    "finally_timestats_disable",
                    disable_call_start=disable_started,
                    disable_call_completion=monotonic(),
                )
            except CollectionError as error:
                manifest["errors"].append(f"final timestats disable failed: {error}")
        cleanup = cleanup_remote(adb, record, timing, monotonic, sleep)
        manifest["remote_cleanup"] = cleanup
        if process is not None:
            try:
                manifest["local_cleanup"] = stop_local_process(process, timing)
            except (OSError, subprocess.SubprocessError) as error:
                manifest["errors"].append(f"local adb cleanup failed: {error}")
        if not cleanup.get("verified_gone"):
            manifest["errors"].append("owned remote death was not verified")
        manifest["valid"] = measurement_ok and not manifest["errors"]
        manifest["completed_utc"] = utc_now()
        write_json(manifest_path, manifest)
    return manifest


def preflight(adb: Adb) -> None:
    state = adb.run(["get-state"])
    assert isinstance(state, str)
    if state.strip() != "device":
        raise CollectionError(f"adb state is {state.strip()!r}, expected 'device'")
    require_foreground(adb.foreground())
    adb.guest(f"test -S {shlex.quote(HOST_SOCKET)}")


def create_output_dir(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        path.mkdir()
    except FileExistsError as error:
        raise CollectionError(f"output directory already exists: {path}") from error


def scheduler_arm_order(selection: str) -> list[int]:
    if selection == "both":
        return list(secrets.choice(((7, 16), (16, 7))))
    return [int(selection)]


def parse_args(arguments: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--guest-prefix-file", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--arm", choices=("7", "16", "both"), default="both")
    parser.add_argument("--winsys-timing", action="store_true")
    parser.add_argument("--async-native-fence", action="store_true")
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    args = parse_args(arguments)
    try:
        guest_prefix = args.guest_prefix_file.read_text().strip()
        create_output_dir(args.output_dir)
        adb = Adb(args.adb, args.serial, guest_prefix)
        preflight(adb)
    except (CollectionError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    manifests = []
    collection_order = scheduler_arm_order(args.arm)
    for arm in collection_order:
        print(f"collecting repaint-window={arm} ms", flush=True)
        manifests.append(
            collect_arm(
                adb,
                arm,
                args.output_dir,
                winsys_timing=args.winsys_timing,
                async_native_fence=args.async_native_fence,
            )
        )
    summary = {
        "schema": 1,
        "valid": all(manifest["valid"] for manifest in manifests),
        "activity": ACTIVITY,
        "collection_order": collection_order,
        "winsys_timing": args.winsys_timing,
        "async_native_fence": args.async_native_fence,
        "arms": [
            {
                "arm_repaint_window_ms": manifest["arm_repaint_window_ms"],
                "run_label": manifest["run_label"],
                "valid": manifest["valid"],
                "errors": manifest["errors"],
            }
            for manifest in manifests
        ],
        "completed_utc": utc_now(),
    }
    write_json(args.output_dir / "manifest.json", summary)
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if summary["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
