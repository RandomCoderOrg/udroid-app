#!/usr/bin/env python3

from __future__ import annotations

import json
import unittest

from winsys_dual_trace_validator import correlate
from winsys_trace_validator import ContractViolation


def producer(event: str, frame: int, time_ns: int) -> str:
    return "UDROID_WINSYS_PRODUCER " + json.dumps(
        {
            "schema": 1,
            "side": "producer",
            "event": event,
            "resource": 4,
            "generation": 1,
            "frame": frame,
            "monotonic_ns": time_ns,
        }
    )


def presenter(event: str, frame: int | None, time_seconds: float) -> str:
    payload = {
        "schema": 1,
        "event": event,
        "resource": 4,
        "generation": 1,
    }
    if event == "register":
        payload.update(
            surface_generation=1,
            width=720,
            height=1280,
            layers=1,
            format=1,
            usage=768,
            stride=720,
        )
    if frame is not None:
        payload["frame"] = frame
    return f"{time_seconds:.3f} 1 2 I tag: UDROID_WINSYS {json.dumps(payload)}"


class DualTraceValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.producer = [
            producer("register_sent", 0, 1_000_000_000),
            producer("queue_sent", 1, 1_001_000_000),
            producer("release_received", 1, 1_004_000_000),
            producer("reuse_sent", 1, 1_005_000_000),
            producer("retire_sent", 0, 1_006_000_000),
        ]
        self.presenter = [
            presenter("register", None, 1.000),
            presenter("produce_begin", 1, 1.001),
            presenter("queue", 1, 1.001),
            presenter("present_begin", 1, 1.002),
            presenter("release_sent", 1, 1.003),
            presenter("reuse_ready", 1, 1.005),
            presenter("retire", None, 1.006),
        ]

    def test_accepts_matching_independent_traces(self) -> None:
        summary = correlate(
            self.producer,
            self.presenter,
            minimum_frames=1,
            minimum_resources=1,
        )
        self.assertEqual(0, summary["unmatched"])

    def test_rejects_a_missing_release(self) -> None:
        with self.assertRaisesRegex(ContractViolation, "release_received does not match"):
            correlate(
                [line for line in self.producer if "release_received" not in line],
                self.presenter,
                minimum_frames=1,
                minimum_resources=1,
            )

    def test_rejects_reverse_queue_order(self) -> None:
        bad = [line.replace("1001000000", "1010000000") for line in self.producer]
        with self.assertRaisesRegex(ContractViolation, "queue_sent occurred after"):
            correlate(bad, self.presenter, minimum_frames=1, minimum_resources=1)


if __name__ == "__main__":
    unittest.main()
