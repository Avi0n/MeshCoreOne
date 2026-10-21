"""AndroidOnly: WP-003 Same CI checks, warm Gradle execution, local-only reports."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def scaffold_options(advisory=False):
    options = [
        "--console=plain", "--dependency-verification", "strict", "--build-cache",
        "--no-parallel", "--max-workers=4", "-PscaffoldTestHeap=512m",
        "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process",
    ]
    return options


def local_test_command(repo):
    return [
        sys.executable, "-m", "unittest", "discover",
        "-s", str(repo / "tools/android-port/local"), "-p", "*test*.py", "-v",
    ]


def execute(command, repo, environment, log):
    print("Executing: " + " ".join(map(str, command)), flush=True)
    with log.open("w", encoding="utf-8") as stream:
        with subprocess.Popen(command, cwd=repo, env=environment, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, errors="replace") as process:
            for line in process.stdout:
                print(line, end="", flush=True)
                stream.write(line)
            status = process.wait()
    if status:
        raise subprocess.CalledProcessError(status, command)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--stages", required=True)
    parser.add_argument("--advisory", action="store_true")
    args = parser.parse_args()
    sys.path.insert(0, str(args.repo / "tools" / "android-port"))
    from controller import ci
    from controller.ci_environment import candidate_environment, verify_wrapper, write_json
    from controller.ci_evidence import collect_lint, collect_suites, validate_graph_runtime
    from controller.module_junit import check_module_tests

    stages = args.stages.split(",")
    state = json.loads((args.work / "toolchain" / "environment.json").read_text(encoding="utf-8"))
    # Keep environment/credential stripping identical to CI, but retain local caches.
    state["private_root"] = str(args.work / "local-checks" / "private")
    environment = candidate_environment(state)
    environment["PATH"] = os.pathsep.join([str(Path(state["java_home"]) / "bin"),
                                          str(Path(sys.executable).parent), environment.get("PATH", "")])
    environment["GRADLE_USER_HOME"] = str(args.work / ".gradle-fast")
    environment["GRADLE_OPTS"] = '-Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"'
    # Explicitly serial project execution: cross-project graph resolution is not
    # compatible with parallel-project execution. Gradle can still use four workers.
    verify_wrapper()
    started = time.monotonic()
    for stage in stages:
        print(f"\n===== FAST LOCAL: {stage} =====", flush=True)
        if stage == "python":
            ci.python_checks(args.output)
            execute(local_test_command(args.repo), args.repo, environment, args.output / "local-tool-tests.log")
        elif stage == "preflight":
            ci.preflight(state, args.output)
        elif stage == "scaffold":
            options = scaffold_options(args.advisory)
            project = args.repo / "android"
            execute([str(args.repo / "android" / "gradlew"), "-p", str(project),
                     *ci.TASKS[stage], *options], args.repo, environment, args.output / f"gradle-{stage}.log")
            report = {"stage": stage, "result": "success", "tasks": ci.TASKS[stage], "warm_cache": True}
            report["suites"] = collect_suites(args.repo / "android")
            report["module_unit_tests"] = check_module_tests(args.repo)
            report["reports"] = collect_lint(args.repo / "android")
            validate_graph_runtime(args.repo / "android/build/reports/scaffold")
            execute([sys.executable, str(args.repo / "tools/android-port/l10n_convert.py"), "--check",
                     "--verify-android-tests", str(args.repo / "android/core/l10n/build/test-results/testDebugUnitTest"),
                     "--copy-android-junit", str(args.output / "l10n/junit")],
                    args.repo, environment, args.output / "l10n-junit.log")
            print(json.dumps(report, indent=2), flush=True)
        elif stage == "inspect":
            ci.inspect(state, args.output)
        elif stage in ("benchmark", "benchmark-dependencies"):
            from controller.performance import verify_build
            options = scaffold_options(args.advisory)
            if stage == "benchmark-dependencies":
                options += ["--write-locks", "--write-verification-metadata", "sha256"]
            execute([str(args.repo / "android" / "gradlew"), "-p", str(args.repo / "android"),
                     *ci.TASKS["benchmark"], *options], args.repo, environment, args.output / f"gradle-{stage}.log")
            if stage == "benchmark":
                print(json.dumps(verify_build(args.repo, Path(state["android_home"]), environment), indent=2))
        else:
            raise ValueError("Unknown local stage: " + stage)
        print(f"===== PASS: {stage} =====", flush=True)
    binding = {
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.repo, text=True).strip(),
        "tree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=args.repo, text=True).strip(),
        "stages": stages, "elapsed_seconds": round(time.monotonic() - started, 2),
        "scope": "Local pre-push check; not hosted/cold-cache, hardware, signing or human-gate evidence",
    }
    print(json.dumps(binding, indent=2), flush=True)
    print("FAST LOCAL CHECK PASSED. No second cold local run is required.", flush=True)


if __name__ == "__main__":
    # Tracebacks and underlying command/log locations intentionally remain visible.
    main()
