"""AndroidOnly: WP-504 Validate imported AndroidX 1.5 raw measurements, not performance acceptance."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys
import xml.etree.ElementTree as ET

CLASS = "com.meshcoreone.android.benchmark.PerformanceBenchmark"
MODES = ("None", "Full", "BaselineProfile")
JOURNEYS = ("coldStartup", "timelinePaging", "nativeMapPan")
EXPECTED = {f"{journey}[{mode}]" for journey in JOURNEYS for mode in MODES}
ITERATIONS = 10
MAX_INPUT_BYTES = 64 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_input(path):
    require(path.is_file() and not path.is_symlink(), f"Missing/unsafe input: {path.name}")
    require(path.stat().st_size <= MAX_INPUT_BYTES, f"Oversized input: {path.name}")
    with path.open("rb") as stream:
        data = stream.read(MAX_INPUT_BYTES + 1)
    require(len(data) <= MAX_INPUT_BYTES, f"Oversized input: {path.name}")
    return data


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        require(name not in result, f"Duplicate JSON key: {name}")
        result[name] = value
    return result


def read_results(path):
    def invalid_constant(value):
        raise ValueError(f"Non-finite JSON constant: {value}")
    return json.loads(
        read_input(path), object_pairs_hook=unique_object, parse_constant=invalid_constant,
        parse_float=lambda value: number(float(value), "JSON number"),
    )


def object_value(value, label):
    require(isinstance(value, dict), f"Missing/malformed object: {label}")
    return value


def number(value, label, *, minimum=None):
    require(type(value) in (int, float) and abs(value) <= sys.float_info.max and math.isfinite(value),
            f"Non-finite/non-numeric {label}")
    require(minimum is None or value >= minimum, f"Out-of-range {label}")
    return value


def digest(value, length, label):
    require(isinstance(value, str) and re.fullmatch(f"[0-9a-f]{{{length}}}", value), f"Malformed {label}")
    return value


def apk_digest(path):
    require(path.is_file() and not path.is_symlink(), "Missing/unsafe target APK")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def percentile(values, percent):
    ordered = sorted(values)
    index = (len(ordered) - 1) * percent / 100
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] * (1 - (index - lower)) + ordered[upper] * (index - lower)


def distribution(values):
    return {
        "count": len(values), "minimum": min(values), "maximum": max(values),
        **{f"P{percent}": percentile(values, percent) for percent in (50, 90, 95, 99)},
    }


def agrees(actual, expected, label):
    number(actual, label)
    require(math.isclose(actual, expected, rel_tol=1e-9, abs_tol=1e-6), f"Raw/summary mismatch: {label}")


def summarize_metric(metric, label, *, sampled):
    metric = object_value(metric, label)
    runs = metric.get("runs")
    require(isinstance(runs, list) and len(runs) == ITERATIONS, f"{label} needs ten raw iterations")
    allow_negative = label == "frameOverrunMs"
    if sampled:
        require(all(isinstance(run, list) and run for run in runs), f"{label} has empty/malformed frame samples")
        require(sum(map(len, runs)) <= 1_000_000, f"Excessive frame sample count: {label}")
        values = [number(value, label, minimum=None if allow_negative else 0) for run in runs for value in run]
    else:
        values = [number(value, label, minimum=0) for value in runs]
        require(all(value > 0 for value in values), f"{label} needs positive measurements")
        if label == "frameCount":
            require(all(value == int(value) for value in values), "Non-integral frameCount")
    stats = distribution(values)
    if sampled:
        for key in ("P50", "P90", "P95", "P99"):
            agrees(metric.get(key), stats[key], f"{label}.{key}")
    else:
        for key, value in (("minimum", stats["minimum"]), ("maximum", stats["maximum"]), ("median", stats["P50"])):
            agrees(metric.get(key), value, f"{label}.{key}")
        average = statistics.mean(values)
        variation = statistics.stdev(values) / average if average else 0
        agrees(metric.get("coefficientOfVariation"), variation, f"{label}.coefficientOfVariation")
    return {
        "unit": "frames" if label == "frameCount" else "ms",
        "aggregation": "pooled frame samples" if sampled else "one sample per iteration",
        **stats,
        **({"per_iteration": [distribution(run) for run in runs]} if sampled else {}),
        "raw_runs": runs,
    }


def junit_cases(path):
    data = read_input(path).decode("utf-8-sig")
    require("<!DOCTYPE" not in data and "<!ENTITY" not in data, "Unsafe JUnit declaration")
    root = ET.fromstring(data)
    require(root.tag in ("testsuite", "testsuites"), "Malformed JUnit root")
    suites = [root] if root.tag == "testsuite" else list(root)
    require(suites and all(suite.tag == "testsuite" for suite in suites), "Empty/malformed JUnit suites")
    identities = set()
    for suite in suites:
        cases = suite.findall("testcase")
        for key, expected in (("tests", len(cases)), ("failures", 0), ("errors", 0), ("skipped", 0)):
            value = suite.get(key)
            require(value is not None and value.isdecimal() and int(value) == expected,
                    f"Missing/inconsistent/unsuccessful JUnit {key}")
        require(cases, "Zero JUnit assertions")
        for outcome in ("failure", "error", "skipped"):
            require(not list(suite.iter(outcome)), f"Actual JUnit {outcome}")
        for case in cases:
            identity = (case.get("classname"), case.get("name"))
            require(identity not in identities, "Duplicate JUnit case")
            identities.add(identity)
    require(identities == {(CLASS, name) for name in EXPECTED}, "Missing/unexpected JUnit workload cases")
    if root.tag == "testsuites":
        for key, expected in (("tests", len(identities)), ("failures", 0), ("errors", 0), ("skipped", 0)):
            if key in root.attrib:
                value = root.get(key)
                require(value.isdecimal() and int(value) == expected, f"Inconsistent aggregate JUnit {key}")
    return identities


def summarize(results, junit, *, commit, target_sha256, dataset_sha256, device_kind):
    digest(commit, 40, "source commit")
    digest(target_sha256, 64, "target APK SHA-256")
    digest(dataset_sha256, 64, "dataset SHA-256")
    require(device_kind in ("physical", "emulator"), "Explicit physical/emulator label required")
    data = object_value(results, "benchmark output")
    context = object_value(data.get("context"), "device context")
    build = object_value(context.get("build"), "device build")
    version = object_value(build.get("version"), "OS version")
    require(type(version.get("sdk")) is int and version["sdk"] >= 31, "API 31 or later context required")
    for key in ("brand", "device", "fingerprint", "id", "model", "type"):
        require(isinstance(build.get(key), str) and build[key].strip(), f"Missing device build {key}")
    for source, key in ((version, "codename"), (context, "osCodenameAbbreviated"), (context, "compilationMode")):
        require(isinstance(source.get(key), str) and source[key].strip(), f"Missing device context {key}")
    for key in ("cpuCoreCount", "cpuMaxFreqHz", "memTotalBytes", "artMainlineVersion"):
        # AndroidX emits -1 for unavailable frequency, memory or ART metadata.
        require(type(context.get(key)) is int and context[key] >= (0 if key == "cpuCoreCount" else -1),
                f"Malformed device context {key}")
    for key in ("cpuLocked", "sustainedPerformanceModeEnabled"):
        require(type(context.get(key)) is bool, f"Malformed device context {key}")
    payload = object_value(context.get("payload"), "instrumentation payload")
    for key, expected in {
        "wp504.commit": commit, "wp504.apkSha256": target_sha256,
        "wp504.dataset": "wp504-v1", "wp504.datasetSha256": dataset_sha256,
    }.items():
        require(payload.get(key) == expected, f"Run binding mismatch: {key}")
    cases = data.get("benchmarks")
    require(isinstance(cases, list) and len(cases) == len(EXPECTED), "Expected all nine benchmark cases")
    observed = set()
    summaries = []
    for case in cases:
        case = object_value(case, "benchmark case")
        name = case.get("name")
        require(case.get("className") == CLASS and isinstance(name, str) and name in EXPECTED,
                "Unexpected benchmark case identity")
        require(name not in observed, "Duplicate benchmark case")
        observed.add(name)
        require(type(case.get("repeatIterations")) is int and case["repeatIterations"] == ITERATIONS,
                "Expected ten benchmark repeat iterations")
        require(type(case.get("warmupIterations")) is int and case["warmupIterations"] == 0,
                "Unexpected warmup; workload modes require zero warmup iterations")
        require(case.get("params") == {}, "Unexpected named benchmark parameters")
        number(case.get("totalRunTimeNs"), "total runtime", minimum=1)
        thermal = number(case.get("thermalThrottleSleepSeconds"), "thermal sleep", minimum=0)
        require(thermal == 0, "Thermal throttling delay reported; review/repeat before comparison")
        metrics = object_value(case.get("metrics"), "single metrics")
        sampled = object_value(case.get("sampledMetrics"), "sampled metrics")
        require(all(isinstance(key, str) for key in metrics.keys() | sampled.keys()), "Malformed metric names")
        # Do not silently discard new measurements that this version cannot interpret.
        startup = name.startswith("coldStartup[")
        require(set(metrics) <= ({"timeToInitialDisplayMs", "timeToFullDisplayMs"} if startup else {"frameCount"}),
                "Unrecognized single metric")
        require(set(sampled) == (set() if startup else {"frameDurationCpuMs", "frameOverrunMs"}),
                "Missing/unrecognized sampled frame metrics")
        require(("timeToInitialDisplayMs" if startup else "frameCount") in metrics, "Missing required metric")
        summary = {key: summarize_metric(value, key, sampled=False) for key, value in metrics.items()}
        summary.update({key: summarize_metric(value, key, sampled=True) for key, value in sampled.items()})
        if not startup:
            for iteration, (count, cpu, overrun) in enumerate(zip(
                summary["frameCount"]["raw_runs"], summary["frameDurationCpuMs"]["raw_runs"],
                summary["frameOverrunMs"]["raw_runs"],
            )):
                require(count == len(cpu) == len(overrun),
                        f"Frame cardinality mismatch at iteration {iteration}: "
                        f"frameCount={count}, CPU={len(cpu)}, overrun={len(overrun)}")
        summaries.append({"name": name, "iterations": ITERATIONS, "metrics": summary})
    require({(CLASS, name) for name in observed} == junit, "JSON/JUnit case disagreement")
    return {
        "scope": "Imported raw measurement consistency only; not performance acceptance",
        "source_commit": commit, "target_apk_sha256": target_sha256,
        "dataset": "wp504-v1", "dataset_sha256": dataset_sha256,
        "device_kind_reported_by_operator": device_kind,
        "recorded_context": context,
        "benchmarks": sorted(summaries, key=lambda value: value["name"]),
        "budget_evaluated": False, "physical_device_verified": False,
        "profiles_collected_or_provenance_verified": False,
        "anr_battery_strictmode_or_native_runtime_verified": False,
        "review_required": [
            "Raw files/payload and device label are caller-supplied, not an authenticity or same-run certificate",
            "Review unsuppressed runner logs, thermal/battery conditions, APK/R8 build and genuine profile provenance",
            "Macrobenchmark thermal sleep of zero alone does not prove absence of thermal throttling",
            "Derive budgets only from reviewed, repeated, like-for-like real physical-device runs",
        ],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--junit", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--dataset-sha256", required=True)
    parser.add_argument("--device-kind", required=True, choices=("physical", "emulator"))
    args = parser.parse_args(argv)
    try:
        report = summarize(
            read_results(args.results), junit_cases(args.junit), commit=args.commit,
            target_sha256=apk_digest(args.apk), dataset_sha256=args.dataset_sha256, device_kind=args.device_kind,
        )
        print(json.dumps(report, indent=2, allow_nan=False))
        return 0
    except (OSError, ValueError, TypeError, OverflowError, ET.ParseError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
