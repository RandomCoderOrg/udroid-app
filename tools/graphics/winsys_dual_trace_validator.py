#!/usr/bin/env python3
"""Correlate independent Kumquat producer and Android presenter traces."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from winsys_trace_validator import ContractViolation, validate


PRODUCER_MARKER = "UDROID_WINSYS_PRODUCER"
PRESENTER_MARKER = "UDROID_WINSYS"
MONOTONIC_PREFIX = re.compile(r"^\s*(\d+\.\d+)\s")


@dataclass(frozen=True)
class Record:
    event: str
    resource: int
    generation: int
    frame: int
    monotonic_ns: int

    @property
    def identity(self) -> tuple[int, int, int]:
        return self.resource, self.generation, self.frame


def _event(line: str, marker: str) -> dict[str, object] | None:
    location = line.find(marker)
    if location < 0:
        return None
    payload = line[location + len(marker) :].lstrip(" :")
    try:
        value = json.loads(payload)
    except json.JSONDecodeError as error:
        raise ContractViolation(f"invalid {marker} JSON: {error.msg}") from error
    if not isinstance(value, dict):
        raise ContractViolation(f"{marker} payload must be an object")
    return value


def _positive(event: dict[str, object], field: str, *, allow_zero: bool = False) -> int:
    value = event.get(field)
    minimum = 0 if allow_zero else 1
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        qualifier = "non-negative" if allow_zero else "positive"
        raise ContractViolation(f"producer {field} must be a {qualifier} integer")
    return value


def parse_producer(lines: list[str]) -> list[Record]:
    records: list[Record] = []
    for line in lines:
        event = _event(line, PRODUCER_MARKER)
        if event is None:
            continue
        if event.get("schema") != 1 or event.get("side") != "producer":
            raise ContractViolation("unsupported producer trace schema or side")
        kind = event.get("event")
        if not isinstance(kind, str):
            raise ContractViolation("producer event must be a string")
        records.append(
            Record(
                event=kind,
                resource=_positive(event, "resource"),
                generation=_positive(event, "generation"),
                frame=_positive(event, "frame", allow_zero=True),
                monotonic_ns=_positive(event, "monotonic_ns"),
            )
        )
    if not records:
        raise ContractViolation("input contained no producer trace events")
    return records


def parse_presenter(lines: list[str]) -> list[Record]:
    records: list[Record] = []
    for line in lines:
        event = _event(line, PRESENTER_MARKER)
        if event is None:
            continue
        match = MONOTONIC_PREFIX.match(line)
        if match is None:
            raise ContractViolation("presenter trace must use logcat -v monotonic")
        kind = event.get("event")
        if not isinstance(kind, str):
            raise ContractViolation("presenter event must be a string")
        frame = event.get("frame", 0)
        if not isinstance(frame, int) or isinstance(frame, bool) or frame < 0:
            raise ContractViolation("presenter frame must be non-negative")
        records.append(
            Record(
                event=kind,
                resource=_positive(event, "resource"),
                generation=_positive(event, "generation"),
                frame=frame,
                monotonic_ns=int(float(match.group(1)) * 1_000_000_000),
            )
        )
    if not records:
        raise ContractViolation("input contained no presenter trace events")
    return records


def _records_by_event(records: list[Record], event: str) -> list[Record]:
    return [record for record in records if record.event == event]


def _require_match(
    producer: list[Record],
    presenter: list[Record],
    producer_event: str,
    presenter_event: str,
) -> None:
    left = Counter(record.identity for record in _records_by_event(producer, producer_event))
    right = Counter(record.identity for record in _records_by_event(presenter, presenter_event))
    if left != right:
        raise ContractViolation(
            f"{producer_event} does not match {presenter_event}: "
            f"producer_only={sum((left - right).values())} "
            f"presenter_only={sum((right - left).values())}"
        )


def _require_causal_order(
    producer: list[Record],
    presenter: list[Record],
    producer_event: str,
    presenter_event: str,
    tolerance_ns: int = 2_000_000,
) -> None:
    left = {record.identity: record.monotonic_ns for record in _records_by_event(producer, producer_event)}
    right = {record.identity: record.monotonic_ns for record in _records_by_event(presenter, presenter_event)}
    for identity, left_time in left.items():
        right_time = right[identity]
        if left_time > right_time + tolerance_ns:
            raise ContractViolation(
                f"{producer_event} occurred after {presenter_event} for {identity}"
            )


def correlate(
    producer_lines: list[str],
    presenter_lines: list[str],
    minimum_frames: int = 1000,
    minimum_resources: int = 3,
) -> dict[str, int | str]:
    # Preserve the full presenter state-machine validation as the first gate.
    presenter_summary = validate(presenter_lines)
    producer = parse_producer(producer_lines)
    presenter = parse_presenter(presenter_lines)

    pairs = (
        ("register_sent", "register"),
        ("queue_sent", "queue"),
        ("release_received", "release_sent"),
        ("reuse_sent", "reuse_ready"),
        ("retire_sent", "retire"),
    )
    for producer_event, presenter_event in pairs:
        _require_match(producer, presenter, producer_event, presenter_event)

    # A queued acquire must reach Android after Kumquat sends it. A release
    # must reach Kumquat after Android creates and sends it.
    _require_causal_order(producer, presenter, "queue_sent", "present_begin")
    _require_causal_order(presenter, producer, "release_sent", "release_received")

    frames = int(presenter_summary["frames"])
    resources = int(presenter_summary["resources"])
    if frames < minimum_frames:
        raise ContractViolation(f"only {frames} frames; require {minimum_frames}")
    if resources < minimum_resources:
        raise ContractViolation(f"only {resources} resources; require {minimum_resources}")

    return {
        "status": "pass",
        "frames": frames,
        "resources": resources,
        "producer_events": len(producer),
        "presenter_events": len(presenter),
        "unmatched": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("producer", type=Path)
    parser.add_argument("presenter", type=Path)
    parser.add_argument("--minimum-frames", type=int, default=1000)
    parser.add_argument("--minimum-resources", type=int, default=3)
    args = parser.parse_args()

    try:
        summary = correlate(
            args.producer.read_text(encoding="utf-8", errors="replace").splitlines(),
            args.presenter.read_text(encoding="utf-8", errors="replace").splitlines(),
            minimum_frames=args.minimum_frames,
            minimum_resources=args.minimum_resources,
        )
    except ContractViolation as error:
        print(f"FAIL: {error}")
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
