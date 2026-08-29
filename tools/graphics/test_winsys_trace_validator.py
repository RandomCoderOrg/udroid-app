#!/usr/bin/env python3

from __future__ import annotations

import io
import unittest

from winsys_trace_validator import ContractViolation, validate


def event(kind: str, frame: int | None = None, **extra: int) -> str:
    fields = {
        "schema": 1,
        "event": kind,
        "resource": extra.pop("resource", 7),
        "generation": extra.pop("generation", 3),
        **extra,
    }
    if frame is not None:
        fields["frame"] = frame
    import json

    return json.dumps(fields)


def registration(**extra: int) -> str:
    return event(
        "register",
        surface_generation=extra.pop("surface_generation", 2),
        width=extra.pop("width", 1080),
        height=extra.pop("height", 2400),
        layers=extra.pop("layers", 1),
        format=extra.pop("format", 1),
        usage=extra.pop("usage", 0x300),
        stride=extra.pop("stride", 1088),
        **extra,
    )


class WinsysTraceValidatorTest(unittest.TestCase):
    def test_accepts_two_complete_reuse_cycles(self) -> None:
        lines = [registration()]
        for frame in (1, 2):
            lines.extend(
                event(kind, frame)
                for kind in (
                    "produce_begin",
                    "queue",
                    "present_begin",
                    "release_sent",
                    "reuse_ready",
                )
            )
        lines.append(event("retire"))

        summary = validate(io.StringIO("\n".join(lines)))

        self.assertEqual(2, summary["frames"])
        self.assertEqual(1, summary["resources"])
        self.assertEqual(0, summary["inflight"])

    def test_rejects_reuse_before_release_fence_is_ready(self) -> None:
        lines = [
            registration(),
            event("produce_begin", 1),
            event("queue", 1),
            event("present_begin", 1),
            event("release_sent", 1),
            event("produce_begin", 2),
        ]

        with self.assertRaisesRegex(ContractViolation, "release_pending"):
            validate(io.StringIO("\n".join(lines)))

    def test_rejects_stale_frame_identity(self) -> None:
        lines = [
            registration(),
            event("produce_begin", 9),
            event("queue", 8),
        ]

        with self.assertRaisesRegex(ContractViolation, "expected frame 9"):
            validate(io.StringIO("\n".join(lines)))

    def test_rejects_replacement_of_inflight_generation(self) -> None:
        lines = [
            registration(resource=4, generation=1),
            event("produce_begin", 1, resource=4, generation=1),
            registration(resource=4, generation=2),
        ]

        with self.assertRaisesRegex(ContractViolation, "unretired generation"):
            validate(io.StringIO("\n".join(lines)))

    def test_rejects_inferred_tight_stride(self) -> None:
        with self.assertRaisesRegex(ContractViolation, "smaller than width"):
            validate(io.StringIO(registration(width=1080, stride=1024)))

    def test_accepts_logcat_prefix(self) -> None:
        lines = [
            f"08-29 10:00:00.000 I/uDroid-Winsys: UDROID_WINSYS {registration()}",
            *[
                f"08-29 10:00:00.001 I/uDroid-Winsys: UDROID_WINSYS {event(kind, 1)}"
                for kind in (
                    "produce_begin",
                    "queue",
                    "present_begin",
                    "release_sent",
                    "reuse_ready",
                )
            ],
        ]

        summary = validate(io.StringIO("\n".join(lines)))

        self.assertEqual(1, summary["frames"])

    def test_accepts_interleaved_swapchain_resources(self) -> None:
        lines = [
            registration(resource=1, generation=1),
            registration(resource=2, generation=1),
            registration(resource=3, generation=1),
        ]
        for resource, frame in ((1, 1), (2, 1), (3, 1), (1, 2)):
            lines.extend(
                event(kind, frame, resource=resource, generation=1)
                for kind in (
                    "produce_begin",
                    "queue",
                    "present_begin",
                    "release_sent",
                    "reuse_ready",
                )
            )
        lines.extend(
            event("retire", resource=resource, generation=1)
            for resource in (1, 2, 3)
        )

        summary = validate(io.StringIO("\n".join(lines)))

        self.assertEqual(4, summary["frames"])
        self.assertEqual(3, summary["resources"])

    def test_rejects_retire_before_producer_acknowledges_release(self) -> None:
        lines = [
            registration(),
            event("produce_begin", 1),
            event("queue", 1),
            event("present_begin", 1),
            event("release_sent", 1),
            event("retire"),
        ]

        with self.assertRaisesRegex(ContractViolation, "retired while release_pending"):
            validate(io.StringIO("\n".join(lines)))


if __name__ == "__main__":
    unittest.main()
