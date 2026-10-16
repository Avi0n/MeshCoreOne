#!/usr/bin/env python3
"""Validate a fresh physical-device native benchmark report and both relative gates."""

import argparse
import json
import math
import statistics
from pathlib import Path

BENCHMARK_CLASS = "one.meshcore.shared.benchmarks.NativeBridgeBenchmark"
CASES = {"regionBatched", "regionPerRow", "decryptBatched", "decryptPerRow"}


def positive_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("measurement must be numeric")
    if not math.isfinite(value) or value <= 0:
        raise ValueError("measurement must be finite and positive")
    return value


def check_report(report, run_id):
    context = report["context"]
    if not run_id or context["payload"]["runId"] != run_id:
        raise ValueError("report is from a different run")
    build = context["build"]
    fields = [build[key] for key in ("brand", "device", "fingerprint", "model")]
    if any(not isinstance(value, str) or not value.strip() for value in fields):
        raise ValueError("complete device identity is required")
    identity = " ".join(fields)
    if not identity.strip() or any(word in identity.lower() for word in ("generic", "emulator", "sdk_gphone")):
        raise ValueError("a physical-device report is required")
    medians = {}
    for result in report["benchmarks"]:
        name = result["name"]
        if result["className"] != BENCHMARK_CLASS or name not in CASES or name in medians:
            raise ValueError("unexpected or duplicate benchmark")
        positive_number(result["warmupIterations"])
        positive_number(result["repeatIterations"])
        metric = result["metrics"]["timeNs"]
        runs = [positive_number(value) for value in metric["runs"]]
        if len(runs) < 2:
            raise ValueError("multiple measurement samples are required")
        median = positive_number(metric["median"])
        if not math.isclose(median, statistics.median(runs), rel_tol=1e-9):
            raise ValueError("median does not match raw measurements")
        medians[name] = median
    if set(medians) != CASES:
        raise ValueError("all four benchmark cases are required")
    comparisons = []
    for workload in ("region", "decrypt"):
        batch = medians[workload + "Batched"]
        per_row = medians[workload + "PerRow"]
        comparisons.append(f"{workload}: batch={batch / 1e6:.3f}ms, per-row={per_row / 1e6:.3f}ms, ratio={batch / per_row:.3f}")
        if batch > per_row:
            raise ValueError("batch must not cost more than row-at-a-time: " + comparisons[-1])
    return comparisons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--run-id", required=True, help="unique runId passed to benchmark instrumentation")
    args = parser.parse_args()
    try:
        comparisons = check_report(json.loads(args.report.read_text()), args.run_id)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Native benchmark gate failed: {error}\n")
    print("\n".join(comparisons))
    print("Both native batch performance gates pass.")


if __name__ == "__main__":
    main()
