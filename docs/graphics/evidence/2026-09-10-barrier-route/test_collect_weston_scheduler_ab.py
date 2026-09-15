#!/usr/bin/env python3

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "collect_weston_scheduler_ab", HERE / "collect-weston-scheduler-ab.py"
)
assert SPEC and SPEC.loader
collector = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = collector
SPEC.loader.exec_module(collector)
ANALYZER_SPEC = importlib.util.spec_from_file_location(
    "analyze_weston_scheduler", HERE / "analyze-weston-scheduler.py"
)
assert ANALYZER_SPEC and ANALYZER_SPEC.loader
analyzer = importlib.util.module_from_spec(ANALYZER_SPEC)
sys.modules[ANALYZER_SPEC.name] = analyzer
ANALYZER_SPEC.loader.exec_module(analyzer)


def report(frames, timestamp="00:00:01"):
    buckets = f"{frames - 1}/0/0/0/0/0"
    return (
        f"[{timestamp}] GL repaint stages at frame {frames} (avg/max us): ok\n"
        f"[{timestamp}] gfxstream output: presents={frames} releases={frames} "
        "no-free-slot=0.\n"
        f"[{timestamp}] gfxstream cadence: ok\n"
        f"[{timestamp}] gfxstream repaint: ok\n"
        f"[{timestamp}] gfxstream inter-present buckets (n={frames - 1}): "
        f"counts={buckets}."
    )


def timestats_payload(start=1, end=2, frames=60):
    return (
        f"statsStart = {start}\n"
        f"statsEnd = {end}\n"
        "displayRefreshRate = 60 fps\n"
        f"layerName = test SurfaceView[{collector.ACTIVITY}](BLAST)#1\n"
        f"totalFrames = {frames}\n"
    )


class FakeClock:
    def __init__(self, wake_overshoot=0):
        self.now = 0.0
        self.wake_overshoot = wake_overshoot

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds
        if self.wake_overshoot and self.now >= 1.0:
            self.now += self.wake_overshoot
            self.wake_overshoot = 0


class FakeProcess:
    pid = 321

    def __init__(self):
        self.returncode = None

    def poll(self):
        return self.returncode


class FakeAdb:
    def __init__(self, clock=None):
        self.process = FakeProcess()
        self.log_reads = 0
        self.timestats_actions = []
        self.arm = None
        self.label = None
        self.winsys_timing = None
        self.async_native_fence = None
        self.clock = clock

    def launch(
        self,
        arm,
        label,
        stdout_file,
        stderr_file,
        winsys_timing=False,
        async_native_fence=False,
    ):
        self.arm, self.label = arm, label
        self.winsys_timing = winsys_timing
        self.async_native_fence = async_native_fence
        if winsys_timing:
            stderr_file.write(
                b"MESA: info: udroid Zink fence flush count=1 async-native="
                + str(int(async_native_fence)).encode()
                + b"\n"
            )
        return self.process

    def guest(self, command):
        socket, log, _ = collector.remote_paths(self.arm, self.label)
        if command.startswith("cat "):
            if command.endswith(".pid"):
                return (
                    f"pid=42\nstarttime=99\nlabel={self.label}\n"
                    f"socket={socket}\nlog={log}\n"
                )
            self.log_reads += 1
            frames = 120 if self.log_reads == 1 else 240
            return (
                f"command: --socket={socket}\n"
                f"Output repaint window is {self.arm} ms maximum.\n"
                + report(frames)
            )
        if "case TERM in" in command:
            self.process.returncode = 0
            return "SIGNALED_TERM\n"
        if "case CHECK in" in command:
            return "GONE\n"
        raise AssertionError(command)

    def foreground(self):
        return (
            "mResumedActivity: ActivityRecord{1 u0 "
            f"{collector.ACTIVITY} t1}}"
        )

    def timestats(self, action):
        self.timestats_actions.append(action)
        return timestats_payload() if action == "dump" else ""

    def screenshot(self):
        return collector.PNG_SIGNATURE + b"0" * 1024


class CollectorTests(unittest.TestCase):
    def test_real_weston_clock_log_has_twenty_complete_reports(self):
        reports = collector.parse_complete_reports(
            (HERE / "weston-clock-20260910.weston.log").read_text()
        )
        self.assertEqual(len(reports), 20)
        self.assertEqual(reports[-1]["frames"], 2400)

    def test_analyzer_uses_prior_complete_report_when_latest_is_truncated(self):
        lines = (HERE / "weston-clock-20260910.weston.log").read_text().splitlines()
        del lines[max(index for index, line in enumerate(lines) if "gfxstream repaint:" in line)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "truncated.log"
            path.write_text("\n".join(lines))
            parsed = analyzer.parse_log(path)
        self.assertEqual(parsed["frames"], 2280)
        self.assertEqual(parsed["presents"], 2280)

    def test_analyzer_manifest_rejects_cross_run_and_cross_arm_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stem = "weston-sched7-s7-run"
            manifest = root / f"{stem}.manifest.json"
            warm = root / f"{stem}.warm.weston.log"
            final = root / f"{stem}.perf.weston.log"
            stats = root / f"{stem}.timestats.txt"
            payload = {"valid": True, "arm_repaint_window_ms": 7, "run_label": "s7-run"}
            manifest.write_text(json.dumps(payload))
            self.assertTrue(analyzer.validate_manifest(manifest, warm, final, stats)["valid"])
            with self.assertRaisesRegex(ValueError, "do not match"):
                analyzer.validate_manifest(
                    manifest, warm, root / "weston-sched7-other.perf.weston.log", stats
                )
            payload["arm_repaint_window_ms"] = 16
            manifest.write_text(json.dumps(payload))
            with self.assertRaisesRegex(ValueError, "do not match"):
                analyzer.validate_manifest(manifest, warm, final, stats)

    def test_last_complete_report_ignores_mismatched_and_truncated_tail(self):
        mismatched = report(240).replace("presents=240", "presents=239")
        truncated = report(360).split("\n", 2)[0]
        self.assertEqual(
            collector.last_complete_report(
                "\n".join((report(120), mismatched, truncated))
            )["frames"],
            120,
        )

    def test_foreground_activity_normalizes_relative_component(self):
        self.assertEqual(
            collector.foreground_activity(
                "topResumedActivity=ActivityRecord{1 u0 com.example/.MainActivity t1}"
            ),
            "com.example/com.example.MainActivity",
        )
        collector.require_foreground(
            "mResumedActivity: com.example/.StaleActivity\n"
            f"topResumedActivity={collector.ACTIVITY}"
        )
        collector.require_foreground(
            "Display #0 (activities from top to bottom):\n"
            f"  topResumedActivity={collector.ACTIVITY}\n"
            "  Task{hidden stale history}\n"
            "    topResumedActivity=com.android.launcher/.Launcher\n"
            "Display #1 (activities from top to bottom):\n"
            "  topResumedActivity=com.example/.ExternalDisplay"
        )
        with self.assertRaisesRegex(collector.CollectionError, "exactly one"):
            collector.foreground_activity("mFocusedApp: com.example/.Ignored")

    def test_adb_timeout_is_a_collection_error(self):
        adb = collector.Adb("adb", "serial", "guest")
        with mock.patch.object(
            collector.subprocess,
            "run",
            side_effect=subprocess.TimeoutExpired(["adb"], collector.ADB_CALL_TIMEOUT),
        ) as run:
            with self.assertRaisesRegex(collector.CollectionError, "timed out after 15s"):
                adb.run(["get-state"])
        self.assertEqual(run.call_args.kwargs["timeout"], collector.ADB_CALL_TIMEOUT)

    def test_adb_launch_enables_timing_only_when_requested(self):
        adb = collector.Adb("adb", "serial", "guest")
        with mock.patch.object(collector.subprocess, "Popen") as popen:
            adb.launch(16, "run", object(), object(), True, True)
            timed = popen.call_args.args[0][-1]
            adb.launch(16, "run", object(), object())
            quiet = popen.call_args.args[0][-1]
        self.assertIn("UDROID_SCHEDULER_TIMING=1", timed)
        self.assertIn("UDROID_ZINK_ASYNC_NATIVE_FENCE=1", timed)
        self.assertNotIn("UDROID_SCHEDULER_TIMING", quiet)
        self.assertIn("UDROID_ZINK_ASYNC_NATIVE_FENCE=0", quiet)

    def test_timestats_requires_one_nonempty_target_layer_and_valid_window(self):
        fixture = (HERE / "udroid-gbm-reuse-timestats-20260910.txt").read_text()
        self.assertEqual(collector.validate_timestats(fixture, 42)["total_frames"], 88)
        duplicate = "displayRefreshRate" + timestats_payload().split(
            "displayRefreshRate", 1
        )[1]
        with self.assertRaisesRegex(collector.CollectionError, "exactly one target"):
            collector.validate_timestats(fixture + duplicate, 42)
        with self.assertRaisesRegex(collector.CollectionError, "no frames"):
            collector.validate_timestats(timestats_payload(frames=0), 1)
        with self.assertRaisesRegex(collector.CollectionError, "duration"):
            collector.validate_timestats(timestats_payload(end=1), 1)
        for duration in (59, 61):
            with self.subTest(duration=duration):
                collector.validate_timestats(timestats_payload(100, 100 + duration), 60)
        for duration in (58, 62):
            with self.subTest(duration=duration):
                with self.assertRaisesRegex(collector.CollectionError, "duration"):
                    collector.validate_timestats(timestats_payload(100, 100 + duration), 60)

    def test_pid_record_requires_expected_identity_and_safe_numbers(self):
        valid = "pid=42\nstarttime=99\nlabel=run\nsocket=wayland-run\nlog=/tmp/run.log\n"
        self.assertEqual(
            collector.parse_pid_record(valid, "run", "wayland-run", "/tmp/run.log")["pid"],
            42,
        )
        with self.assertRaisesRegex(collector.CollectionError, "socket mismatch"):
            collector.parse_pid_record(
                valid.replace("socket=wayland-run", "socket=other"),
                "run",
                "wayland-run",
                "/tmp/run.log",
            )
        with self.assertRaisesRegex(collector.CollectionError, "unsafe pid/starttime"):
            collector.parse_pid_record(
                valid.replace("pid=42", "pid=1"),
                "run",
                "wayland-run",
                "/tmp/run.log",
            )

    def test_cleanup_refuses_stale_owned_process(self):
        class StaleAdb:
            def __init__(self):
                self.calls = 0

            def guest(self, command):
                self.calls += 1
                return "STALE_CMDLINE\n"

        adb = StaleAdb()
        result = collector.cleanup_remote(
            adb,
            {"pid": 42, "starttime": 99, "socket": "wayland-run"},
            collector.Timing(),
            lambda: 0,
            lambda _: None,
        )
        self.assertFalse(result["verified_gone"])
        self.assertEqual(result["term"], "STALE_CMDLINE")
        self.assertIn("no signal sent", result["error"])
        self.assertEqual(adb.calls, 1)

    def test_cleanup_accepts_pid_reuse_after_term(self):
        class ReusedPidAdb:
            def __init__(self):
                self.statuses = iter(("SIGNALED_TERM", "STALE_START"))

            def guest(self, command):
                return next(self.statuses) + "\n"

        result = collector.cleanup_remote(
            ReusedPidAdb(),
            {"pid": 42, "starttime": 99, "socket": "wayland-run"},
            collector.Timing(),
            lambda: 0,
            lambda _: None,
        )
        self.assertTrue(result["verified_gone"])
        self.assertEqual(result["term"], "SIGNALED_TERM")
        self.assertNotIn("error", result)

    def test_collect_arm_event_order_and_measurement_deadline(self):
        clock = FakeClock()
        adb = FakeAdb(clock)
        timing = collector.Timing(minimum_warmup=0, measurement=1, poll=0.25)
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            collector.os, "getpgid", return_value=321
        ):
            manifest = collector.collect_arm(
                adb,
                7,
                Path(directory),
                timing,
                clock.monotonic,
                clock.sleep,
                winsys_timing=True,
                async_native_fence=True,
            )

        self.assertTrue(manifest["valid"], manifest["errors"])
        self.assertTrue(manifest["winsys_timing"])
        self.assertTrue(manifest["async_native_fence"])
        self.assertTrue(adb.winsys_timing)
        self.assertTrue(adb.async_native_fence)
        self.assertEqual(
            [event["event"] for event in manifest["events"]],
            [
                "launch",
                "warm_snapshot",
                "foreground_start",
                "measurement_start",
                "measurement_stop",
                "foreground_end",
                "final_snapshot",
                "post_window_capture",
            ],
        )
        stop = next(
            event for event in manifest["events"] if event["event"] == "measurement_stop"
        )
        self.assertEqual(stop["deadline"], 1.0)
        self.assertEqual(stop["deadline_overshoot"], 0.0)
        self.assertEqual(stop["enabled_duration_to_disable_start"], 1.0)
        self.assertEqual(stop["enabled_duration"], 1.0)
        self.assertEqual(adb.timestats_actions, ["disable", "clear", "enable", "disable", "dump"])
        self.assertTrue(manifest["remote_cleanup"]["verified_gone"])

    def test_collect_arm_rejects_mismatched_async_native_fence_evidence(self):
        class WrongModeAdb(FakeAdb):
            def launch(self, arm, label, stdout_file, stderr_file, winsys_timing=False,
                       async_native_fence=False):
                return super().launch(
                    arm, label, stdout_file, stderr_file, winsys_timing, False
                )

        clock = FakeClock()
        adb = WrongModeAdb(clock)
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            collector.os, "getpgid", return_value=321
        ):
            manifest = collector.collect_arm(
                adb,
                16,
                Path(directory),
                collector.Timing(minimum_warmup=0, measurement=1, poll=0.25),
                clock.monotonic,
                clock.sleep,
                winsys_timing=True,
                async_native_fence=True,
            )
        self.assertFalse(manifest["valid"])
        self.assertIn("expected 1, found ['0']", manifest["errors"][0])

    def test_collect_arm_deadline_overshoot_boundary(self):
        for overshoot, valid in ((1.0, True), (1.01, False)):
            with self.subTest(overshoot=overshoot):
                clock = FakeClock(wake_overshoot=overshoot)
                adb = FakeAdb(clock)
                with tempfile.TemporaryDirectory() as directory, mock.patch.object(
                    collector.os, "getpgid", return_value=321
                ):
                    manifest = collector.collect_arm(
                        adb,
                        7,
                        Path(directory),
                        collector.Timing(minimum_warmup=0, measurement=1, poll=0.25),
                        clock.monotonic,
                        clock.sleep,
                    )
                self.assertEqual(manifest["valid"], valid, manifest["errors"])

    def test_collect_arm_disables_stats_after_enable_transport_error(self):
        class EnableFailsAdb(FakeAdb):
            def timestats(self, action):
                result = super().timestats(action)
                if action == "enable":
                    raise collector.CollectionError("enable response lost")
                return result

        clock = FakeClock()
        adb = EnableFailsAdb(clock)
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            collector.os, "getpgid", return_value=321
        ):
            manifest = collector.collect_arm(
                adb,
                7,
                Path(directory),
                collector.Timing(minimum_warmup=0, measurement=1, poll=0.25),
                clock.monotonic,
                clock.sleep,
            )
        self.assertFalse(manifest["valid"])
        self.assertEqual(adb.timestats_actions, ["disable", "clear", "enable", "disable"])

    def test_main_records_selected_arms_and_timing_mode(self):
        cases = (
            ([], [16, 7], False, False),
            (
                ["--arm", "16", "--winsys-timing", "--async-native-fence"],
                [16],
                True,
                True,
            ),
        )
        for extra_arguments, expected_order, expected_timing, expected_async in cases:
            with self.subTest(arguments=extra_arguments), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                prefix = root / "prefix"
                prefix.write_text("guest")
                output = root / "output"
                modes = []

                def collected(
                    _adb,
                    arm,
                    _output,
                    winsys_timing=False,
                    async_native_fence=False,
                ):
                    modes.append((winsys_timing, async_native_fence))
                    return {
                        "arm_repaint_window_ms": arm,
                        "run_label": f"s{arm}-run",
                        "valid": True,
                        "errors": [],
                    }

                with mock.patch.object(
                    collector.secrets, "choice", return_value=(16, 7)
                ), mock.patch.object(
                    collector, "Adb", return_value=object()
                ), mock.patch.object(
                    collector, "preflight"
                ), mock.patch.object(
                    collector, "collect_arm", side_effect=collected
                ), mock.patch("builtins.print"):
                    result = collector.main(
                        [
                            "--adb", "adb",
                            "--serial", "serial",
                            "--guest-prefix-file", str(prefix),
                            "--output-dir", str(output),
                            *extra_arguments,
                        ]
                    )
                summary = json.loads((output / "manifest.json").read_text())
            self.assertEqual(result, 0)
            self.assertEqual(summary["collection_order"], expected_order)
            self.assertEqual(summary["winsys_timing"], expected_timing)
            self.assertEqual(summary["async_native_fence"], expected_async)
            self.assertEqual(
                modes, [(expected_timing, expected_async)] * len(expected_order)
            )


class LauncherTests(unittest.TestCase):
    def test_launcher_rejects_missing_or_invalid_label(self):
        launcher = HERE / "launch-weston-scheduler-ab.sh"
        for arguments in (("7",), ("7", ""), ("7", "bad label")):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    [str(launcher), *arguments],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    check=False,
                )
                self.assertEqual(result.returncode, 2)

    def test_launcher_rejects_invalid_timing_mode(self):
        launcher = HERE / "launch-weston-scheduler-ab.sh"
        for value in ("", "2", "yes"):
            with self.subTest(value=value):
                result = subprocess.run(
                    [str(launcher), "16", "run"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    check=False,
                    env={**os.environ, "UDROID_SCHEDULER_TIMING": value},
                )
                self.assertEqual(result.returncode, 2)
                self.assertIn("invalid UDROID_SCHEDULER_TIMING", result.stderr)


if __name__ == "__main__":
    unittest.main()
