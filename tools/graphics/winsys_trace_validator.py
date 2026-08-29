#!/usr/bin/env python3
"""Validate uDroid graphics resource lifecycle traces.

Input may be raw JSONL or logcat output containing ``UDROID_WINSYS`` followed
by one JSON object. The validator intentionally has no Android or third-party
dependencies so the same gate can run locally and in CI.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Iterable, TextIO


TRACE_MARKER = "UDROID_WINSYS"
TRACE_SCHEMA = 1


class ContractViolation(ValueError):
    """Raised when a trace violates the winsys resource contract."""


class Phase(Enum):
    AVAILABLE = "available"
    PRODUCING = "producing"
    QUEUED = "queued"
    CONSUMING = "consuming"
    RELEASE_PENDING = "release_pending"
    RETIRED = "retired"


@dataclass
class Resource:
    resource_id: int
    generation: int
    surface_generation: int
    width: int
    height: int
    layers: int
    format: int
    usage: int
    stride: int
    phase: Phase = Phase.AVAILABLE
    active_frame: int | None = None
    last_frame: int = 0


class ContractValidator:
    def __init__(self) -> None:
        self.resources: dict[tuple[int, int], Resource] = {}
        self.latest_generation: dict[int, int] = {}
        self.event_count = 0
        self.frame_count = 0

    @staticmethod
    def _positive(event: dict[str, object], field: str) -> int:
        value = event.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            raise ContractViolation(f"{field} must be a positive integer")
        return value

    def consume(self, event: dict[str, object]) -> None:
        self.event_count += 1
        if event.get("schema") != TRACE_SCHEMA:
            raise ContractViolation(f"unsupported trace schema {event.get('schema')!r}")
        kind = event.get("event")
        if not isinstance(kind, str):
            raise ContractViolation("event must be a string")

        resource_id = self._positive(event, "resource")
        generation = self._positive(event, "generation")
        key = (resource_id, generation)

        if kind == "register":
            self._register(event, key)
            return

        resource = self.resources.get(key)
        if resource is None:
            raise ContractViolation(
                f"{kind} references unknown resource {resource_id}/{generation}"
            )

        if kind == "retire":
            if resource.phase is not Phase.AVAILABLE:
                raise ContractViolation(
                    f"resource {resource_id}/{generation} retired while {resource.phase.value}"
                )
            resource.phase = Phase.RETIRED
            return

        frame = self._positive(event, "frame")
        transitions = {
            "produce_begin": (Phase.AVAILABLE, Phase.PRODUCING),
            "queue": (Phase.PRODUCING, Phase.QUEUED),
            "present_begin": (Phase.QUEUED, Phase.CONSUMING),
            "release_sent": (Phase.CONSUMING, Phase.RELEASE_PENDING),
            "reuse_ready": (Phase.RELEASE_PENDING, Phase.AVAILABLE),
        }
        transition = transitions.get(kind)
        if transition is None:
            raise ContractViolation(f"unknown event {kind!r}")

        expected, next_phase = transition
        if resource.phase is not expected:
            raise ContractViolation(
                f"resource {resource_id}/{generation} received {kind} while "
                f"{resource.phase.value}; expected {expected.value}"
            )

        if kind == "produce_begin":
            if frame <= resource.last_frame:
                raise ContractViolation(
                    f"resource {resource_id}/{generation} frame {frame} is not newer "
                    f"than {resource.last_frame}"
                )
            resource.active_frame = frame
        elif frame != resource.active_frame:
            raise ContractViolation(
                f"resource {resource_id}/{generation} expected frame "
                f"{resource.active_frame}, received {frame} for {kind}"
            )

        resource.phase = next_phase
        if kind == "reuse_ready":
            resource.last_frame = frame
            resource.active_frame = None
            self.frame_count += 1

    def _register(self, event: dict[str, object], key: tuple[int, int]) -> None:
        resource_id, generation = key
        if key in self.resources:
            raise ContractViolation(
                f"resource {resource_id}/{generation} registered more than once"
            )
        prior_generation = self.latest_generation.get(resource_id)
        if prior_generation is not None:
            prior = self.resources[(resource_id, prior_generation)]
            if prior.phase is not Phase.RETIRED:
                raise ContractViolation(
                    f"resource {resource_id} generation {generation} replaced "
                    f"unretired generation {prior_generation}"
                )
            if generation <= prior_generation:
                raise ContractViolation(
                    f"resource {resource_id} generation did not increase"
                )

        width = self._positive(event, "width")
        height = self._positive(event, "height")
        layers = self._positive(event, "layers")
        stride = self._positive(event, "stride")
        if layers != 1:
            raise ContractViolation("display resource must have exactly one layer")
        if stride < width:
            raise ContractViolation(
                f"allocator stride {stride} is smaller than width {width}"
            )

        resource = Resource(
            resource_id=resource_id,
            generation=generation,
            surface_generation=self._positive(event, "surface_generation"),
            width=width,
            height=height,
            layers=layers,
            format=self._positive(event, "format"),
            usage=self._positive(event, "usage"),
            stride=stride,
        )
        self.resources[key] = resource
        self.latest_generation[resource_id] = generation

    def finish(self, allow_inflight: bool = False) -> dict[str, object]:
        inflight = [
            resource
            for resource in self.resources.values()
            if resource.phase not in (Phase.AVAILABLE, Phase.RETIRED)
        ]
        if inflight and not allow_inflight:
            descriptions = ", ".join(
                f"{resource.resource_id}/{resource.generation}:{resource.phase.value}"
                for resource in inflight
            )
            raise ContractViolation(f"trace ended with in-flight resources: {descriptions}")
        return {
            "events": self.event_count,
            "frames": self.frame_count,
            "resources": len(self.resources),
            "inflight": len(inflight),
        }


def parse_events(lines: Iterable[str]) -> Iterable[dict[str, object]]:
    for line_number, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line:
            continue
        marker = line.find(TRACE_MARKER)
        if marker >= 0:
            line = line[marker + len(TRACE_MARKER) :].lstrip(" :")
        elif not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as error:
            raise ContractViolation(
                f"line {line_number} is not valid trace JSON: {error.msg}"
            ) from error
        if not isinstance(event, dict):
            raise ContractViolation(f"line {line_number} must contain a JSON object")
        yield event


def validate(lines: Iterable[str], allow_inflight: bool = False) -> dict[str, object]:
    validator = ContractValidator()
    for event in parse_events(lines):
        validator.consume(event)
    if validator.event_count == 0:
        raise ContractViolation("input contained no winsys trace events")
    return validator.finish(allow_inflight=allow_inflight)


def _open_input(path: str | None) -> tuple[TextIO, bool]:
    if path is None or path == "-":
        return sys.stdin, False
    return Path(path).open("r", encoding="utf-8"), True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", nargs="?", help="trace file, or stdin when omitted")
    parser.add_argument(
        "--allow-inflight",
        action="store_true",
        help="accept a trace captured in the middle of a frame",
    )
    args = parser.parse_args()

    stream, should_close = _open_input(args.trace)
    try:
        summary = validate(stream, allow_inflight=args.allow_inflight)
    except ContractViolation as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if should_close:
            stream.close()

    print(json.dumps({"status": "pass", **summary}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
