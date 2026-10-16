#!/usr/bin/env python3
"""Run the four native microbenchmarks on one device and gate their fresh report."""

import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("native_gate", ROOT / "scripts/check-native-benchmarks.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="adb serial of the physical device")
    parser.add_argument("--offline", action="store_true", help="use already resolved Gradle dependencies")
    args = parser.parse_args()
    run_id = str(uuid.uuid4())
    environment = os.environ.copy()
    environment["ANDROID_SERIAL"] = args.serial
    command = [
        str(ROOT / "gradlew"),
        ":sharedBenchmarks:connectedReleaseAndroidTest",
        "--max-workers=1",
        "-Pkotlin.compiler.execution.strategy=out-of-process",
        "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.payload.runId=" + run_id,
    ]
    if args.offline:
        command.append("--offline")
    print(f"Native benchmark runId={run_id}, device={args.serial}", flush=True)
    subprocess.run(command, cwd=ROOT, env=environment, check=True)
    reports = []
    for path in (ROOT / "sharedBenchmarks/build/outputs").rglob("*-benchmarkData.json"):
        data = json.loads(path.read_text())
        if data.get("context", {}).get("payload", {}).get("runId") == run_id:
            reports.append((path, data))
    if len(reports) != 1:
        raise ValueError(f"expected one fresh benchmark report, found {len(reports)}")
    path, data = reports[0]
    # Retain the original samples even if the performance comparison fails.
    archive = ROOT / "sharedBenchmarks/build/runs" / run_id
    archive.mkdir(parents=True)
    shutil.copy2(path, archive / path.name)
    comparisons = GATE.check_report(data, run_id)
    print("\n".join(comparisons))
    print(f"Both native batch performance gates pass. Report: {archive / path.name}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Native benchmark run failed: {error}")
