from __future__ import annotations

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--exact", type=Path, required=True)
    parser.add_argument("--fast-exact", type=Path, required=True)
    parser.add_argument("--builtin", type=Path, required=True)
    args = parser.parse_args()

    exact = load(args.exact)
    fast = load(args.fast_exact)
    builtin = load(args.builtin)
    failures: list[str] = []

    if exact.get("read_ok") != 114:
        failures.append(f"Exact read_ok={exact.get('read_ok')} expected 114")
    if exact.get("mismatch_count") != 0:
        failures.append(f"Exact mismatch_count={exact.get('mismatch_count')}")
    if fast.get("read_ok") != 114:
        failures.append(f"Fast exact read_ok={fast.get('read_ok')} expected 114")
    if fast.get("mismatch_count") != 0:
        failures.append(f"Fast exact mismatch_count={fast.get('mismatch_count')}")
    if builtin.get("frames") != 114:
        failures.append(f"Builtin frames={builtin.get('frames')} expected 114")
    if builtin.get("error_count") != 0:
        failures.append(f"Builtin error_count={builtin.get('error_count')}")

    edge = builtin.get("action_edge_error", {})
    if float(edge.get("max_ratio", 1.0)) > 0.01:
        failures.append(f"Builtin max action-edge error={edge.get('max_ratio')} > 1%")

    timing = builtin.get("timing", {})
    if float(timing.get("p95_ms", 999.0)) > 1.0:
        failures.append(f"Builtin raw P95={timing.get('p95_ms')}ms > 1ms")
    if float(timing.get("frame_median_p95_ms", 999.0)) > 1.0:
        failures.append(
            f"Builtin frame-median P95={timing.get('frame_median_p95_ms')}ms > 1ms"
        )

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        raise SystemExit(1)

    print("PASS: Vision V3 sea-salt stage-1 gate")
    print(
        "  exact parity=0 mismatches; fast-exact parity=0 mismatches; "
        f"builtin errors=0; builtin P95={timing['p95_ms']:.3f}ms; "
        f"edge max={edge['max_ratio'] * 100:.3f}%"
    )


if __name__ == "__main__":
    main()
