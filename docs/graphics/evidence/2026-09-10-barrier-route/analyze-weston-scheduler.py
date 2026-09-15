#!/usr/bin/env python3
"""Compare cumulative Weston scheduler diagnostics over a bounded interval."""

import argparse
import json
import re
import sys
from pathlib import Path


SURFACE_VIEW_RE = re.compile(
    r"^layerName = [^\n]* SurfaceView\["
    r"org\.randomcoder\.udroid\.dev/"
    r"org\.randomcoder\.udroid\.gfxstream\.GfxstreamPresenterProbeActivity"
    r"\]\(BLAST\)#\d+$",
    re.MULTILINE,
)


def require_last(pattern: str, text: str, label: str) -> re.Match[str]:
    matches = list(re.finditer(pattern, text, re.MULTILINE))
    if not matches:
        raise ValueError(f"missing {label}")
    return matches[-1]


def parse_avg_pairs(payload: str) -> dict[str, int]:
    return {
        key: int(average)
        for key, average in re.findall(r"([a-z-]+)-us\(avg/max\)=(\d+)/(?:\d+)", payload)
    }


def parse_log(path: Path) -> dict[str, object]:
    text = path.read_text(errors="replace")
    timestamp = r"\[[^\]\n]+\]"
    pattern = re.compile(
        timestamp
        + r" GL repaint stages at frame (\d+) \(avg/max us\):([^\n]+)\n"
        + timestamp
        + r" gfxstream output: presents=(\d+) releases=(\d+) "
        + r"present-us\(avg/max\)=(\d+)/(?:\d+) "
        + r"release-signal-after-response-us\(avg/max\)=(\d+)/(?:\d+) "
        + r"no-free-slot=(\d+)\.\n"
        + timestamp
        + r" gfxstream cadence: ([^\n]+)\n"
        + timestamp
        + r" gfxstream repaint: ([^\n]+)\n"
        + timestamp
        + r" gfxstream inter-present buckets \(n=(\d+)\): "
        + r"upper-bound-ms=8\.3/16\.7/25/33\.3/50/>50 "
        + r"counts=(\d+)/(\d+)/(\d+)/(\d+)/(\d+)/(\d+)\.",
        re.MULTILINE,
    )
    reports = []
    for match in pattern.finditer(text):
        frames = int(match.group(1))
        presents = int(match.group(3))
        releases = int(match.group(4))
        no_free_slot = int(match.group(7))
        bucket_n = int(match.group(10))
        bucket_counts = tuple(int(match.group(index)) for index in range(11, 17))
        if (
            frames == presents == releases
            and no_free_slot == 0
            and bucket_n == frames - 1
            and sum(bucket_counts) == bucket_n
        ):
            reports.append(match)
    if not reports:
        raise ValueError(f"{path}: missing complete matched diagnostic report")
    report = reports[-1]
    gl_pairs = {
        key: int(average)
        for key, average in re.findall(
            r"([a-z-]+)=(\d+)/(?:\d+)", report.group(2)
        )
    }

    result: dict[str, object] = {
        "path": path,
        "frames": int(report.group(1)),
        "gl": gl_pairs,
        "presents": int(report.group(3)),
        "releases": int(report.group(4)),
        "present-us": int(report.group(5)),
        "release-signal-after-response-us": int(report.group(6)),
        "no-free-slot": int(report.group(7)),
        "cadence": parse_avg_pairs(report.group(8)),
        "repaint": parse_avg_pairs(report.group(9)),
        "bucket_n": int(report.group(10)),
        "buckets": tuple(int(report.group(index)) for index in range(11, 17)),
    }

    frames = result["frames"]
    presents = result["presents"]
    releases = result["releases"]
    bucket_n = result["bucket_n"]
    no_free_slot = result["no-free-slot"]
    assert isinstance(frames, int)
    assert isinstance(presents, int)
    assert isinstance(releases, int)
    assert isinstance(bucket_n, int)
    assert isinstance(no_free_slot, int)
    if no_free_slot != 0:
        raise ValueError(f"{path}: no-free-slot is nonzero; denominator mapping is unsafe")
    if not (frames == presents == releases):
        raise ValueError(
            f"{path}: expected frame/present/release counts to match with no starvation; "
            f"got {frames}/{presents}/{releases}"
        )
    if bucket_n != presents - 1:
        raise ValueError(
            f"{path}: inter-present n={bucket_n}, expected presents-1={presents - 1}"
        )
    if sum(result["buckets"]) != bucket_n:  # type: ignore[arg-type]
        raise ValueError(f"{path}: inter-present bucket counts do not sum to n")
    return result


def interval_average(
    warm_average: int,
    warm_count: int,
    final_average: int,
    final_count: int,
) -> tuple[float, float]:
    delta_count = final_count - warm_count
    if delta_count <= 0:
        raise ValueError("final counter must be greater than warm counter")
    value = (
        final_average * final_count - warm_average * warm_count
    ) / delta_count
    uncertainty = (final_count + warm_count) / delta_count
    return value, uncertainty


def metric_rows(
    warm: dict[str, object], final: dict[str, object]
) -> list[tuple[str, int, int, int, int]]:
    warm_frames = int(warm["frames"])
    final_frames = int(final["frames"])
    warm_gl = warm["gl"]
    final_gl = final["gl"]
    warm_cadence = warm["cadence"]
    final_cadence = final["cadence"]
    warm_repaint = warm["repaint"]
    final_repaint = final["repaint"]
    assert isinstance(warm_gl, dict) and isinstance(final_gl, dict)
    assert isinstance(warm_cadence, dict) and isinstance(final_cadence, dict)
    assert isinstance(warm_repaint, dict) and isinstance(final_repaint, dict)

    rows: list[tuple[str, int, int, int, int]] = []
    for stage in ("use-output", "setup", "draw", "create-sync", "flush", "post"):
        rows.append(
            (f"gl.{stage}", warm_gl[stage], warm_frames,
             final_gl[stage], final_frames)
        )
    rows.extend(
        [
            ("headless.present", int(warm["present-us"]), int(warm["presents"]),
             int(final["present-us"]), int(final["presents"])),
            ("headless.release-after-response",
             int(warm["release-signal-after-response-us"]), int(warm["releases"]),
             int(final["release-signal-after-response-us"]), int(final["releases"])),
        ]
    )
    for metric in ("inter-repaint", "finish-interval", "finish-to-repaint"):
        rows.append(
            (f"headless.{metric}", warm_cadence[metric], warm_frames - 1,
             final_cadence[metric], final_frames - 1)
        )
    for metric in ("full", "renderer", "fence", "remainder"):
        rows.append(
            (f"headless.repaint-{metric}", warm_repaint[metric], warm_frames,
             final_repaint[metric], final_frames)
        )
    return rows


def parse_surface_view(path: Path) -> dict[str, object]:
    text = path.read_text(errors="replace")
    blocks = re.split(r"(?=^displayRefreshRate =)", text, flags=re.MULTILINE)
    matches = [block for block in blocks if SURFACE_VIEW_RE.search(block)]
    if len(matches) != 1:
        raise ValueError(
            f"{path}: expected exactly one matching SurfaceView layer; found {len(matches)}"
        )
    block = matches[0]
    layer = require_last(r"^layerName = (.+)$", block, "SurfaceView layer name").group(1)
    total_frames = int(require_last(r"^totalFrames = (\d+)$", block, "layer totalFrames").group(1))
    average_fps = float(require_last(r"^averageFPS = ([0-9.]+)$", block, "layer averageFPS").group(1))
    histogram = require_last(
        r"present2present histogram is as below:\n([^\n]+)",
        block,
        "layer present2present histogram",
    ).group(1)
    buckets = {
        int(milliseconds): int(count)
        for milliseconds, count in re.findall(r"(\d+)ms=(\d+)", histogram)
    }
    nonzero = {milliseconds: count for milliseconds, count in buckets.items() if count}
    if sum(buckets.values()) != total_frames:
        raise ValueError(
            f"{path}: SurfaceView P2P buckets sum to {sum(buckets.values())}, "
            f"not totalFrames={total_frames}"
        )
    return {
        "layer": layer,
        "total_frames": total_frames,
        "average_fps": average_fps,
        "nonzero_p2p": nonzero,
    }


def validate_manifest(
    path: Path, warm_log: Path, final_log: Path, timestats: Path
) -> dict[str, object]:
    payload = json.loads(path.read_text())
    if not isinstance(payload, dict) or payload.get("valid") is not True:
        raise ValueError(f"{path}: per-arm manifest is not valid")
    arm = payload.get("arm_repaint_window_ms")
    label = payload.get("run_label")
    if arm not in (7, 16) or not isinstance(label, str) or not re.fullmatch(
        r"[A-Za-z0-9._-]{1,48}", label
    ):
        raise ValueError(f"{path}: invalid arm/run label")
    stem = f"weston-sched{arm}-{label}"
    expected = (
        path.parent / f"{stem}.manifest.json",
        path.parent / f"{stem}.warm.weston.log",
        path.parent / f"{stem}.perf.weston.log",
        path.parent / f"{stem}.timestats.txt",
    )
    actual = (path, warm_log, final_log, timestats)
    if tuple(item.resolve() for item in actual) != tuple(item.resolve() for item in expected):
        raise ValueError(f"{path}: manifest/input arm, run label, or paths do not match")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("warm_log", type=Path)
    parser.add_argument("final_log", type=Path)
    parser.add_argument("timestats", type=Path)
    args = parser.parse_args()

    try:
        validate_manifest(args.manifest, args.warm_log, args.final_log, args.timestats)
        warm = parse_log(args.warm_log)
        final = parse_log(args.final_log)
        surface = parse_surface_view(args.timestats)
        rows = metric_rows(warm, final)
    except (OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    delta_frames = int(final["frames"]) - int(warm["frames"])
    print(
        f"aggregate interval: frames/presents/releases={delta_frames}; "
        f"warm={warm['frames']} final={final['frames']}; no-free-slot=0"
    )
    print("approximate interval means reconstructed from integer cumulative means:")
    print("metric                                  mean_us  rounding_bound_us  samples")
    for name, warm_average, warm_count, final_average, final_count in rows:
        average, uncertainty = interval_average(
            warm_average, warm_count, final_average, final_count
        )
        print(
            f"{name:<39} {average:>8.3f} {uncertainty:>18.3f} "
            f"{final_count - warm_count:>8}"
        )
    warm_buckets = warm["buckets"]
    final_buckets = final["buckets"]
    assert isinstance(warm_buckets, tuple) and isinstance(final_buckets, tuple)
    interval_buckets = tuple(
        final_value - warm_value
        for warm_value, final_value in zip(warm_buckets, final_buckets)
    )
    if any(value < 0 for value in interval_buckets):
        print("error: inter-present bucket counter regressed", file=sys.stderr)
        return 2
    print(
        "inter-present interval buckets <=8.3/<=16.7/<=25/<=33.3/<=50/>50 ms: "
        + "/".join(str(value) for value in interval_buckets)
    )
    print(f"SurfaceView layer: {surface['layer']}")
    print(
        f"SurfaceView totalFrames={surface['total_frames']} "
        f"reported averageFPS={surface['average_fps']:.3f}"
    )
    print(
        "SurfaceView nonzero present2present buckets: "
        + " ".join(
            f"{milliseconds}ms={count}"
            for milliseconds, count in surface["nonzero_p2p"].items()  # type: ignore[union-attr]
        )
    )
    print(
        "CAUTION: maxima were discarded; reconstructed means are approximate due to "
        "integer-microsecond cumulative logging. SurfaceFlinger averageFPS and buckets "
        "are coarse layer diagnostics, not a >59 FPS qualification. The files do not "
        "prove temporal overlap; use only an atomically collected warm/performance set."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
