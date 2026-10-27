"""AndroidOnly: WP-003 Credential-stripped exact-task executor."""

import argparse
import contextlib
import io
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import unittest
from collections import deque
from xml.etree.ElementTree import ParseError as ETError
from dataclasses import asdict
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.apk_alignment import inspect_alignment
from controller.ci_environment import REPO, candidate_environment, host_name, toolchain_lock, verify_wrapper, write_json
from controller.ci_evidence import PYTHON_MINIMUMS, collect_lint, collect_suites, counts, read_xml, suite_counts, validate_graph_runtime
from controller.errors import PortError
from controller.gates import Binding, policy_revision
from controller.local_scope import SCOPED_TASKS, TASKS
from controller.model import git, load_manifest
from controller.module_junit import check_module_tests, module_method_inventory
from controller.provision import provision
from controller.runtime_inputs import verify_committed_inputs
from controller.schema import decode_json, fields, load_json, positive_integer
from controller.test_runner import run_suite
from portmap import port_map

TASK_GRAPH_GUARD = REPO / "tools" / "android-port" / "controller" / "verify_candidate_task_graph.gradle"

# AndroidOnly: WP-501 Fixed observation identities may precede the bounded failed-command tail.
WP501_OBSERVATION_METHODS = {
    ("WP501_PATH_RECENCY", "com.meshcoreone.android.parity.AdvertisementPathRecencyOriginalPredicateTest"): (
        "originalDirectPathResponseCommitsPhoneRecencyWithoutChangingTheRadioWatermark",
        "originalPathUpdateCompletesBothRealDeltaRoundsWithoutStampingPhoneRecency",
    ),
    ("WP501_ADVERT_ROLLBACK", "com.meshcoreone.android.parity.AdvertisementRollbackOriginalPredicateTest"): (
        "originalSuccessfulCommitRemovesTheRealResurrectedRowWithoutDiscoverOrNotification",
        "originalReAdvertClearsTheDeleteAndKeepsTheReaddedRoomContactAndNewHistory",
        "originalFailedCommitRollsBackItsRealEarlyWriteAndCannotRequeueTheDeletedKey",
        "originalCancelledTeardownStillRollsBackTheRealCommitLandingAfterStop",
    ),
}
WP501_PATH_RECENCY_PHASES = (
    "room-open", "room-close", "room-seed", "room-contact-check", "room-final-check",
    "session-start", "session-stop", "service-monitor-start", "service-initial-drain",
    "body", "predicate", "service-monitor-stop", "owner-cancel-join",
    "service-events-finish", "service-listener-join", "protocol-listener-join",
)
WP501_OBSERVATION_MARKERS = {
    f"{tag}|TEST|{name}|{method}{suffix}|{ending}"
    for (tag, name), methods in WP501_OBSERVATION_METHODS.items()
    for method in methods for suffix in ("", "()", "[31]", "[31]()")
    for ending in ("START", "END|SUCCESS", "END|FAILURE", "END|SKIPPED")
} | {
    f"WP501_PATH_RECENCY|TASK|:app:{task}|{ending}"
    for task in ("compileDebugKotlin", "compileDebugUnitTestKotlin", "testDebugUnitTest")
    for ending in ("START", "END")
} | {
    f"{tag}|PHASE|{name}|{method}{suffix}|{phase}|{ending}"
    for (tag, name), methods in WP501_OBSERVATION_METHODS.items() if tag == "WP501_PATH_RECENCY"
    for method in methods for suffix in ("", "()", "[31]", "[31]()")
    for phase in WP501_PATH_RECENCY_PHASES for ending in ("START", "END")
}
WP501_OBSERVATION_LIMIT = 128


def output_directory(value=None):
    path = value or os.environ.get("ANDROID_CI_WORK") or os.environ.get("ANDROID_CI_OUTPUT")
    if not path or not Path(path).is_absolute():
        raise PortError("Explicit absolute --output/ANDROID_CI_WORK directory is required")
    return Path(path)


def inputs(value=None):
    path = value or os.environ.get("ANDROID_CI_STATE")
    if not path:
        raise PortError("Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness")
    return load_json(Path(path))


def execution_identity():
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if not event_path:
        return None
    event = load_json(Path(event_path))
    name = os.environ.get("GITHUB_EVENT_NAME")
    repository = os.environ.get("GITHUB_REPOSITORY")
    if repository != "cbattlegear/MeshCoreOne-Android":
        raise PortError("Unexpected CI repository")
    if name == "pull_request":
        pr = event["pull_request"]
        if pr["base"]["repo"]["full_name"] != repository:
            raise PortError("PR targets another repository")
        base, head = pr["base"]["sha"], pr["head"]["sha"]
    elif name == "merge_group":
        base, head = event["merge_group"]["base_sha"], event["merge_group"]["head_sha"]
    elif name in ("push", "workflow_dispatch"):
        head = os.environ.get("GITHUB_SHA")
        base = event.get("before") if name == "push" else head
        if not base or base == "0" * 40:
            base = head
    else:
        raise PortError("Unsupported CI event")
    actual = git(REPO, "rev-parse", "HEAD").decode().strip()
    if actual != head:
        raise PortError("Checkout is not the exact candidate/event SHA")
    manifest = load_manifest(REPO)
    policy = load_json(REPO / "docs" / "android" / "automation-policy.json")
    return {
        "binding": asdict(Binding(repository, "WP-003", base, head, manifest.data["reference"]["commit"],
                                  manifest.sha256, policy_revision(manifest, policy))),
        "run_id": positive_integer(int(os.environ["GITHUB_RUN_ID"]), "Workflow run ID"),
        "run_attempt": positive_integer(int(os.environ["GITHUB_RUN_ATTEMPT"]), "Workflow run attempt"),
    }
def print_failed_command_log(log: Path):
    failure = re.compile(r"\S.*\s>\s.+\sFAILED\s*$|\b\d+ tests? completed,\s*\d+ failed\b")
    preceding = deque(maxlen=2)
    observations = deque(maxlen=WP501_OBSERVATION_LIMIT)
    observation_count = 0
    following, tail = 0, ""
    with log.open(encoding="utf-8", errors="replace") as stream:
        while line := stream.readline(65537):
            tail = (tail + line)[-12000:]
            if len(line) > 65536:
                print("BLOCKED: overlong failed-command diagnostic line; identity extraction is incomplete", file=sys.stderr)
                while not line.endswith("\n"):
                    line = stream.readline(65537)
                    if not line:
                        break
                    tail = (tail + line)[-12000:]
                preceding.clear()
                following = 0
                continue
            marker = line.rstrip("\r\n")
            if marker in WP501_OBSERVATION_MARKERS:
                observations.append(marker)
                observation_count += 1
            if failure.search(line):
                if not following:
                    for context in preceding:
                        print(context[:2000].rstrip("\r\n"), file=sys.stderr)
                print(line.rstrip("\r\n"), file=sys.stderr)
                following = 2
            elif following:
                print(line[:2000].rstrip("\r\n"), file=sys.stderr)
                following -= 1
            preceding.append(line)
    if observations:
        print(
            f"WP501_OBSERVATION_REPLAY|retained={len(observations)}|seen={observation_count}|"
            f"truncated={observation_count > len(observations)}|not-test-evidence",
            file=sys.stderr,
        )
        for marker in observations:
            print(marker, file=sys.stderr)
    print(tail, file=sys.stderr)


def print_failed_command_diagnostics(log: Path, failure_reports):
    try:
        if failure_reports is not None:
            from controller.ci_evidence import failed_case_report

            totals, problems = {}, []
            for directory in failure_reports():
                try:
                    observed = failed_case_report([directory])
                    print(json.dumps(observed, indent=2), file=sys.stderr)
                    for name, count in observed["counts"].items():
                        totals[name] = totals.get(name, 0) + count
                except (PortError, OSError, ETError) as error:
                    problems.append(str(error))
                    print("BLOCKED: failed-command JUnit evidence: " + str(error), file=sys.stderr)
            print(json.dumps({"failed_command_report_totals": totals,
                              "evidence_complete": not problems, "problems": problems}), file=sys.stderr)
    finally:
        print_failed_command_log(log)


def execute(command: list[str], environment: dict | None, log: Path, *, timeout=1800, failure_reports=None):
    log.parent.mkdir(parents=True, exist_ok=True)
    print("Executing " + " ".join(str(item) for item in command[:5]), flush=True)
    try:
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(command, cwd=REPO, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired as timeout_error:
        try:
            print_failed_command_diagnostics(log, failure_reports)
        finally:
            # Diagnostics, including a broken stderr, must never replace the actual timeout.
            try:
                diagnostic_error = sys.exception()
                if diagnostic_error is not timeout_error:
                    print(f"BLOCKED: timed-out-command diagnostics failed: {diagnostic_error}", file=sys.stderr)
            finally:
                raise timeout_error
    if result.returncode:
        print_failed_command_diagnostics(log, failure_reports)
        raise PortError(f"Declared command failed with exit {result.returncode}; log: {log.name}")


def validate_candidate_task_graph(path: Path, requested_tasks: list[str]):
    value = load_json(path)
    fields(value, {"schema_version", "tasks"}, label="resolved Gradle task graph")
    if value["schema_version"] != 1 or not isinstance(value["tasks"], list) or not value["tasks"]:
        raise PortError("Malformed or empty resolved Gradle task graph")
    forbidden_name = re.compile(
        r"(?i)^(retain|collect).*evidence.*$|^verify.*evidence.*$|^(evidence|stage).*(report|result|retention).*$"
    )
    observed = set()
    for record in value["tasks"]:
        fields(record, {"path", "type", "command"}, label="resolved Gradle task")
        if not isinstance(record["path"], str) or not record["path"].startswith(":"):
            raise PortError("Malformed resolved Gradle task path")
        if record["path"] in observed:
            raise PortError("Duplicate resolved Gradle task path")
        observed.add(record["path"])
        name = record["path"].rsplit(":", 1)[-1]
        if forbidden_name.fullmatch(name):
            raise PortError("Duplicate candidate evidence task in resolved graph: " + record["path"])
        if not isinstance(record["command"], list) or not all(isinstance(item, str) for item in record["command"]):
            raise PortError("Malformed resolved Gradle command")
        for argument in record["command"]:
            normalized = argument.replace("\\", "/").lower()
            name = normalized.rsplit("/", 1)[-1]
            if "/docs/android/evidence/" in normalized and name in {
                "collect_evidence.py", "collect_native_evidence.py", "retain_raw.py",
                "run_linux_verification.py", "verify_producers.py", "print_failures.py",
                "source_bindings.py",
            }:
                raise PortError("Gradle invokes repository evidence collector: " + record["path"])
    for requested in requested_tasks:
        expected = requested if requested.startswith(":") else ":" + requested
        if expected not in observed:
            raise PortError("Requested Gradle task missing from resolved graph: " + expected)
    return {"tasks": len(observed), "result": "no-duplicate-ci-evidence"}


def preflight(state: dict, output: Path, *, local=False):
    verify_committed_inputs(REPO)
    if platform.python_version() != toolchain_lock()["python"]:
        raise PortError("Build/setup Python must be the exact declared 3.12.4 input")
    verify_wrapper()
    environment = candidate_environment(state, local=local)
    script = REPO / "android" / "scaffold" / "check_environment.py"
    execute([sys.executable, str(script)], environment, output / "preflight.log", timeout=60)
    packages = [
        ("platforms/android-37.2", "platforms;android-37.2", (1, 0, 0)),
        ("build-tools/37.0.0", "build-tools;37.0.0", (37, 0, 0)),
    ]
    if not local:
        packages.append(("cmdline-tools/23.0", "cmdline-tools;23.0", (23, 0, 0)))
    inventory = []
    for path, package_id, expected in packages:
        package = read_xml(Path(state["android_home"]) / path / "package.xml").find("localPackage")
        if package is None or package.get("path") != package_id:
            raise PortError("Installed SDK inventory/package identity mismatch")
        revision = tuple(int(package.findtext(f"revision/{part}", "0")) for part in ("major", "minor", "micro"))
        if revision != expected:
            raise PortError("Installed SDK inventory revision mismatch")
        inventory.append({"package": package_id, "revision": ".".join(map(str, revision))})
    write_json(output / "sdk-inventory.json", {
        "packages": inventory,
        "cli_bootstrap_executed": False,
        "scope": "actual installed package metadata; automatic CLI helper download is not a pinned build prerequisite",
    })
    write_json(output / "worker-readiness.json", {
        "schema_version": 1, "environment_ready": True, "host": state["host"],
        "scope": "explicit current build inputs; not cloud assignability, native host, publisher or human approval",
        "provenance": state["provenance"],
    })


def run_stage(stage: str, state: dict, output: Path, *, local=False, scopes=None):
    dispositions = port_map(load_manifest(REPO))
    print(f"Verified Kotlin source dispositions and pinned provenance: {len(dispositions)} files", flush=True)
    if platform.python_version() != toolchain_lock()["python"]:
        raise PortError("Declared CI executor requires Python 3.12.4")
    environment = candidate_environment(state, local=local)
    task_graph = output / f"gradle-{stage}-task-graph.json"
    verify_wrapper()
    execute([sys.executable, str(REPO / "android" / "scaffold" / "check_environment.py")],
            environment, output / f"{stage}-preflight.log", timeout=60)
    environment["ANDROID_CI_TASK_GRAPH"] = str(task_graph)
    project = REPO / "android"
    cache = Path(state["private_root"]) / "project-root"
    options = [
        "--no-daemon", "--console=plain", "--dependency-verification", "strict",
        "-Pkotlin.compiler.execution.strategy=in-process",
        "-PscaffoldTestHeap=" + ("256m" if local else "512m"),
        "--project-cache-dir", str(cache), "--quiet",
    ]
    if not local:
        options.append("--build-cache")
    if local:
        options.append("-PscaffoldTestJvmArgs=-Xms32m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=256m")
    wrapper = REPO / "android" / ("gradlew.bat" if state["host"] == "windows" else "gradlew")
    default_selection = ("scaffold",) if stage == "scaffold" else ("protocol",) if stage == "protocol" else ()
    selected = list(scopes or default_selection)
    if stage not in ("scaffold", "protocol") or not selected or any(scope not in SCOPED_TASKS for scope in selected):
        tasks = TASKS[stage]
    else:
        tasks = list(dict.fromkeys(task for scope in selected for task in SCOPED_TASKS[scope]))
    gradle_arguments = [*tasks, *options, "--init-script", str(TASK_GRAPH_GUARD)]
    command = [str(wrapper), "-p", str(project), *gradle_arguments]
    if state["host"] == "windows":
        shell = shutil.which("pwsh", path=environment.get("PATH"))
        if shell is None:
            raise PortError("Windows batch execution requires the hosted/local PowerShell 7 input")
        invocation = output / f"gradle-{stage}-invocation.json"
        write_json(invocation, {"wrapper": str(wrapper), "project": str(project), "arguments": gradle_arguments})
        command = [
            shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
            str(Path(__file__).with_name("gradle_windows.ps1")), "-InvocationFile", str(invocation),
        ]
    from controller.ci_evidence import SUITES
    from controller.module_junit import REPORT_LOCATIONS, module_sources

    def report_directories():
        directories = set()
        if "scaffold" in selected:
            directories.update(REPO / "android" / relative for relative, _ in SUITES.values())
            for module in module_sources(REPO):
                locations = [REPO / "android" / module / relative for relative in REPORT_LOCATIONS]
                directories.add(next((path for path in locations if any(path.glob("TEST-*.xml"))), locations[-1]))
        for scope, modules in (("protocol", ("core/protocol",)), ("backup", ("core/data", "core/database")),
                               ("external-oracle", ("core/testing",))):
            if scope in selected:
                directories.update(
                    REPO / "android" / module / "build/test-results" / ("test" if module == "core/protocol" else "testDebugUnitTest")
                    for module in modules
                )
        return sorted(directories)

    execute(command, environment, output / f"gradle-{stage}.log",
            timeout=3600 if stage in ("scaffold", "protocol") else 1800,
            failure_reports=report_directories if selected else None)
    graph = validate_candidate_task_graph(task_graph, tasks)
    if stage == "benchmark":
        from controller.performance import verify_build
        print(json.dumps(verify_build(REPO, Path(state["android_home"]), environment), indent=2))
    report = {
        "stage": stage, "scopes": selected, "result": "success", "tasks": tasks,
        "strict_verification": True, "resolved_task_graph": graph,
    }
    if "scaffold" in selected:
        report["suites"] = collect_suites(REPO / "android")
        report["module_unit_tests"] = check_module_tests(REPO)
        execute([sys.executable, str(REPO / "android" / "parity-tests" / "semantic.py"),
                 "--check", "--verify-execution"], environment, output / "parity-family-execution.log")
        report["unit_test_methods"] = module_method_inventory(REPO, report["module_unit_tests"])
        report["reports"] = collect_lint(REPO / "android")
        validate_graph_runtime(REPO / "android" / "build" / "reports" / "scaffold")
    if "protocol" in selected:
        source = REPO / "android" / "core" / "protocol" / "build" / "test-results" / "test"
        report["protocol_suite"] = suite_counts(source, 84)
    if "backup" in selected:
        report["backup_suites"] = {
            "data": suite_counts(REPO / "android/core/data/build/test-results/testDebugUnitTest", 1),
            "database": suite_counts(REPO / "android/core/database/build/test-results/testDebugUnitTest", 1),
        }
    if "external-oracle" in selected:
        report["foundation_suite"] = suite_counts(
            REPO / "android/core/testing/build/test-results/testDebugUnitTest", 1
        )
    print(json.dumps(report, indent=2))


def python_checks(output: Path):
    from controller.verification_config import check_configuration
    from controller.workflows import validate_workflows

    delivery = verify_committed_inputs(REPO)
    configuration = check_configuration(REPO)
    validate_workflows(REPO)
    execute([sys.executable, str(REPO / "tools" / "android-port" / "controller" / "validate.py")],
            None, output / "manifest-validation.log")
    execute([sys.executable, str(REPO / "tools" / "android-port" / "portmap.py")],
            None, output / "traceability.log")
    execute([sys.executable, str(REPO / "android" / "scaffold" / "sync_notices.py")],
            None, output / "pinned-notices.log")
    execute([sys.executable, str(REPO / "android" / "parity-tests" / "audit.py"), "--check"],
            None, output / "parity-accounting.log")
    execute([sys.executable, str(REPO / "android" / "parity-tests" / "semantic.py"), "--check"],
            None, output / "parity-semantic-proposals.log")
    results = {}
    for name, path, minimum in (
        ("scaffold", REPO / "android" / "scaffold", PYTHON_MINIMUMS["scaffold"]),
        ("parity-accounting", REPO / "android" / "parity-tests", 1),
    ):
        suite = unittest.TestLoader().discover(str(path), pattern="test_*.py")
        text = io.StringIO()
        with contextlib.redirect_stdout(text):
            status = run_suite(suite, verbosity=0)
        if status:
            raise PortError(f"Required {name} Python suite failed")
        result = decode_json(text.getvalue().strip().splitlines()[-1])
        result.pop("scope")
        results[name] = counts(result, minimum=minimum)
    print(json.dumps({"python": results, "verification_overlay": configuration, "runtime_inputs": delivery}, indent=2))


def inspect(state: dict, output: Path, *, local=False):
    environment = candidate_environment(state, local=local)
    script = REPO / "android" / "scaffold" / "inspect_apk.py"
    result = subprocess.run([sys.executable, str(script)], cwd=REPO, env=environment, capture_output=True, text=True, check=True, timeout=90)
    value = decode_json(result.stdout)
    apk = REPO / Path(value["artifact"])
    value.update(inspect_alignment(apk, Path(state["android_home"]), environment, windows=state["host"] == "windows"))
    print(json.dumps(value, indent=2))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("provision", "local-inputs", "preflight", "run", "python", "inspect"))
    parser.add_argument("--state", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--root", type=Path)
    parser.add_argument("--jdk", type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--accept-sdk-license", action="store_true")
    parser.add_argument("--local", action="store_true", help="Measured shared-host build flags only")
    parser.add_argument("--stage", choices=tuple(TASKS))
    parser.add_argument("--scope", action="append", choices=tuple(SCOPED_TASKS))
    args = parser.parse_args(argv)
    try:
        if args.command == "provision":
            verify_committed_inputs(REPO)
            if args.root is None:
                raise PortError("An explicit ephemeral --root is required")
            provision(args.root, accept_sdk_license=args.accept_sdk_license)
        elif args.command == "local-inputs":
            if any(path is None or not path.is_absolute() for path in (args.root, args.jdk, args.sdk)):
                raise PortError("Local validation requires explicit JDK/SDK/private-root paths")
            write_json(args.root / "environment.json", {
                "schema_version": 1, "host": host_name(), "java_home": str(args.jdk),
                "android_home": str(args.sdk), "private_root": str(args.root / "private"),
                "provenance": {"scope": "existing explicitly supplied local installations; no hosted provisioning claim"},
            })
        elif args.command == "python":
            python_checks(output_directory(args.output))
        else:
            state, output = inputs(args.state), output_directory(args.output)
            output.mkdir(parents=True, exist_ok=True)
            if args.command == "preflight":
                preflight(state, output, local=args.local)
            elif args.command == "run":
                if args.stage is None:
                    raise PortError("Exactly one declared --stage is required")
                run_stage(args.stage, state, output, local=args.local, scopes=args.scope)
            else:
                inspect(state, output, local=args.local)
        return 0
    except (PortError, OSError, ValueError, KeyError, ETError, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
