#!/usr/bin/env python3
"""Run the hosted app suite with bounded diagnostics and retained Xcode output."""

import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
SIMULATOR = "platform=iOS Simulator,name=iPhone 17e,OS=27.0"
TIMEOUT_SECONDS = 12 * 60
HEARTBEAT_SECONDS = 30
SAMPLE_SECONDS = 3
COMMAND_TIMEOUT_SECONDS = 5
INTERRUPT_GRACE_SECONDS = 10
TERMINATE_GRACE_SECONDS = 5


def capture(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True,
                                timeout=COMMAND_TIMEOUT_SECONDS, check=False)
        return result.stdout + result.stderr
    except subprocess.TimeoutExpired as error:
        partial = "".join(part.decode(errors="replace") if isinstance(part, bytes) else part or ""
                          for part in (error.stdout, error.stderr))
        return partial + f"\n{shlex.join(command)}: {error}\n"
    except OSError as error:
        return f"{shlex.join(command)}: {error}\n"


def process_snapshot(artifacts):
    snapshot = capture(["ps", "-axo", "pid=,ppid=,pgid=,command="])
    (artifacts / "processes.txt").write_text(snapshot)
    rows = []
    for line in snapshot.splitlines():
        fields = line.strip().split(None, 3)
        if len(fields) == 4 and all(field.isdigit() for field in fields[:3]):
            rows.append((*(int(field) for field in fields[:3]), fields[3]))
    return rows


def pinned_test_host(rows):
    # The app host is launched by launchd_sim, outside make's process group.
    try:
        devices = json.loads(capture(["xcrun", "simctl", "list", "devices", "available", "--json"]))
        matches = [device["udid"] for runtime, entries in devices["devices"].items()
                   if runtime.endswith(".iOS-27-0") for device in entries
                   if device["name"] == "iPhone 17e" and device["state"] == "Booted"]
        if len(matches) != 1:
            return None
        app = capture(["xcrun", "simctl", "get_app_container", matches[0],
                       "io.pocketmeshtest.app", "app"]).strip()
        executable = f"{app}/MC1"
        hosts = [pid for pid, _, _, command in rows
                 if f"/Devices/{matches[0]}/" in executable
                 and (command == executable or command.startswith(executable + " "))]
        return hosts[0] if len(hosts) == 1 else None
    except (KeyError, TypeError, ValueError):
        return None


def sample_stall(artifacts, process_group, rows):
    builders = [pid for pid, _, group, command in rows
                if group == process_group and Path(command.split()[0]).name == "xcodebuild"]
    host = pinned_test_host(rows) if builders else None
    candidates = list(dict.fromkeys(builders[:2] + ([host] if host else [])))
    (artifacts / "sample-targets.json").write_text(json.dumps(candidates) + "\n")
    for pid in candidates:
        output = capture(["sample", str(pid), str(SAMPLE_SECONDS), "-file",
                          str(artifacts / f"sample-{pid}.txt")])
        (artifacts / f"sample-{pid}-status.txt").write_text(output)


def signal_group(process, signum):
    try:
        os.killpg(process.pid, signum)
    except ProcessLookupError:
        pass


def stop(process, rows):
    builders = [pid for pid, _, group, command in rows
                if group == process.pid and Path(command.split()[0]).name == "xcodebuild"]
    if builders:
        for pid in builders:
            try:
                os.kill(pid, signal.SIGINT)
            except ProcessLookupError:
                pass
        try:
            process.wait(timeout=INTERRUPT_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            pass
    # Reap the whole owned pipeline, including descendants if make already exited.
    signal_group(process, signal.SIGTERM)
    try:
        process.wait(timeout=TERMINATE_GRACE_SECONDS)
    except subprocess.TimeoutExpired:
        pass
    signal_group(process, signal.SIGKILL)
    process.wait(timeout=COMMAND_TIMEOUT_SECONDS)


def run(artifacts, timeout, extra_arguments):
    artifacts.mkdir(parents=True, exist_ok=False)
    for executable in ["make", "xcodebuild", "xcodegen", "swiftgen", "xcsift", "xcrun", "sample"]:
        if shutil.which(executable) is None:
            raise RuntimeError(f"Required tool is missing: {executable}")
    arguments = ["-resultBundlePath", str(artifacts / "results.xcresult"),
                 "-derivedDataPath", str(ROOT / ".derivedData"),
                 'CODE_SIGN_IDENTITY=-', "CODE_SIGNING_REQUIRED=NO", "CODE_SIGNING_ALLOWED=NO",
                 "COMPILER_INDEX_STORE_ENABLE=NO", *extra_arguments]
    command = ["make", "test-app", f"SIM={SIMULATOR}",
               f"XCODEBUILD_TEST_ARGS={shlex.join(arguments)}",
               'XCSIFT_WITH_HEARTBEAT=tee "$$MC1_CI_RAW_LOG" | xcsift -f toon']
    environment = dict(os.environ, MC1_CI_RAW_LOG=str(artifacts / "xcodebuild.log"))
    started = time.monotonic()
    cancelled = 0

    def handle_signal(signum, _frame):
        nonlocal cancelled
        cancelled = signum

    previous_handlers = {signum: signal.signal(signum, handle_signal)
                         for signum in (signal.SIGINT, signal.SIGTERM)}
    timed_out = False
    process = None
    try:
        with (artifacts / "runner.log").open("w") as log:
            log.write(shlex.join(command) + "\n")
            log.flush()
            process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            next_heartbeat = started + HEARTBEAT_SECONDS
            while process.poll() is None and not cancelled:
                elapsed = time.monotonic() - started
                if elapsed >= timeout:
                    timed_out = True
                    rows = process_snapshot(artifacts)
                    sample_stall(artifacts, process.pid, rows)
                    break
                if time.monotonic() >= next_heartbeat:
                    print(f"App tests still running ({elapsed:.0f}s); raw log: {artifacts / 'xcodebuild.log'}",
                          flush=True)
                    next_heartbeat += HEARTBEAT_SECONDS
                time.sleep(0.1)
    finally:
        try:
            if process is not None and (process.poll() is None or timed_out or cancelled):
                stop(process, process_snapshot(artifacts))
        finally:
            for signum, handler in previous_handlers.items():
                signal.signal(signum, handler)
    status = 128 + cancelled if cancelled else 124 if timed_out else process.returncode
    if status and not timed_out and not cancelled:
        sample_stall(artifacts, process.pid, process_snapshot(artifacts))
    (artifacts / "status.json").write_text(json.dumps({
        "exit_status": status, "timed_out": timed_out, "signal": cancelled,
        "elapsed_seconds": round(time.monotonic() - started, 2), "simulator": SIMULATOR,
    }, indent=2) + "\n")
    print((artifacts / "runner.log").read_text(), end="")
    print(f"App test artifacts: {artifacts}", flush=True)
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, default=ROOT / "tmp/ci-app-tests")
    parser.add_argument("--timeout", type=float, default=TIMEOUT_SECONDS)
    parser.add_argument("xcodebuild_args", nargs=argparse.REMAINDER)
    options = parser.parse_args()
    arguments = options.xcodebuild_args
    if arguments[:1] == ["--"]:
        arguments = arguments[1:]
    if options.timeout <= 0:
        parser.error("--timeout must be positive")
    try:
        return run(options.artifacts.resolve(), options.timeout, arguments)
    except (OSError, RuntimeError) as error:
        print(f"App test runner failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
