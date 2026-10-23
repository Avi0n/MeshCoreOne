import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "test-app-ci.py"
SPEC = importlib.util.spec_from_file_location("test_app_ci", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class AppCIRunnerTests(unittest.TestCase):
    def setUp(self):
        (runner.ROOT / "tmp").mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="app-ci-", dir=runner.ROOT / "tmp")
        self.root = Path(self.directory.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for tool in ["xcodegen", "swiftgen", "xcrun", "sample"]:
            self.tool(tool, "exit 0")
        self.tool("xcsift", "cat >/dev/null\nexit 0")
        self.environment = patch.dict(os.environ, {"PATH": f"{self.bin}:{os.environ['PATH']}", "CI": "true"})
        self.environment.start()
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(self.environment.stop)

    def tool(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/bash\n" + body + "\n")
        path.chmod(0o755)

    def run_case(self, body, timeout=5):
        self.tool("xcodebuild", body)
        artifacts = self.root / "artifacts"
        with contextlib.redirect_stdout(io.StringIO()):
            status = runner.run(artifacts, timeout, [])
        return status, artifacts

    def test_success_keeps_raw_output_and_result_bundle(self):
        status, artifacts = self.run_case("""
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-resultBundlePath" ]; then mkdir -p "$2"; shift; fi
  shift
done
echo 'native test success'
""")
        self.assertEqual(status, 0)
        self.assertIn("native test success", (artifacts / "xcodebuild.log").read_text())
        self.assertTrue((artifacts / "results.xcresult").is_dir())

    def test_native_failure_survives_zero_status_formatter(self):
        status, artifacts = self.run_case("echo 'native assertion failed'\nexit 65")
        self.assertNotEqual(status, 0)
        self.assertIn("native assertion failed", (artifacts / "xcodebuild.log").read_text())
        self.assertEqual(json.loads((artifacts / "status.json").read_text())["exit_status"], status)

    def test_stall_is_bounded_and_owned_children_are_stopped(self):
        sibling = subprocess.Popen(["sleep", "30"])

        def stop_sibling():
            sibling.terminate()
            sibling.wait(timeout=5)

        self.addCleanup(stop_sibling)
        child_file = self.root / "child.pid"
        started = time.monotonic()

        def watchdog_clock():
            # Exercise cleanup after child entry; process startup is not the timeout under test.
            ready = child_file.exists() or time.monotonic() - started >= 5
            return 1.0 if ready else 0.0

        controlled_time = SimpleNamespace(monotonic=watchdog_clock, sleep=time.sleep)
        with patch.object(runner, "TERMINATE_GRACE_SECONDS", 0.3), \
             patch.object(runner, "time", controlled_time):
            status, artifacts = self.run_case(
                f"sleep 30 &\necho $! > '{child_file}'\nwait", timeout=0.3)
        self.assertEqual(status, 124)
        self.assertLess(time.monotonic() - started, 10)
        self.assertTrue(child_file.exists(), "owned child did not enter before the readiness deadline")
        self.assertIsNone(sibling.poll())
        self.assertTrue((artifacts / "processes.txt").exists())
        child_pid = child_file.read_text().strip()
        state = subprocess.run(["ps", "-o", "stat=", "-p", child_pid], capture_output=True,
                               text=True, check=False).stdout.strip()
        self.assertTrue(not state or state.startswith("Z"), state)

    def test_missing_formatter_fails_before_launching(self):
        (self.bin / "xcsift").unlink()
        real_which = shutil.which
        with patch.object(runner.shutil, "which", side_effect=lambda name:
                          None if name == "xcsift" else real_which(name)):
            with self.assertRaisesRegex(RuntimeError, "xcsift"):
                runner.run(self.root / "artifacts", 1, [])

    def test_cancellation_retains_logs_and_returns_signal_status(self):
        entered = self.root / "entered"
        self.tool("xcodebuild", f"echo running\ntouch '{entered}'\nsleep 30 &\nwait")
        artifacts = self.root / "cancelled"
        process = subprocess.Popen([sys.executable, str(SCRIPT), "--artifacts", str(artifacts)],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 5
            while not entered.exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(entered.exists())
            process.send_signal(signal.SIGTERM)
            cleanup_budget = (runner.INTERRUPT_GRACE_SECONDS + runner.TERMINATE_GRACE_SECONDS
                              + runner.COMMAND_TIMEOUT_SECONDS + 5)
            output, _ = process.communicate(timeout=cleanup_budget)
            self.assertEqual(process.returncode, 128 + signal.SIGTERM, output)
            self.assertIn("running", (artifacts / "xcodebuild.log").read_text())
            self.assertEqual(json.loads((artifacts / "status.json").read_text())["signal"], signal.SIGTERM)
        finally:
            if process.poll() is None:
                process.terminate()
                process.communicate(timeout=cleanup_budget)

    def test_sample_commands_are_bounded_and_skip_unowned_builders(self):
        calls = []

        def capture(command):
            calls.append(command)
            return "sample complete\n"

        with patch.object(runner, "capture", side_effect=capture), \
             patch.object(runner, "pinned_test_host", return_value=None):
            runner.sample_stall(self.root, 20, [(10, 1, 20, "/bin/xcodebuild"),
                                               (11, 1, 99, "/bin/xcodebuild")])
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][:3], ["sample", "10", "3"])
        with patch.object(runner, "pinned_test_host") as host:
            runner.sample_stall(self.root, 20, [(11, 1, 99, "/bin/xcodebuild")])
            host.assert_not_called()
        timeout = subprocess.TimeoutExpired("sample", 5, output=b"Sampling completed\n",
                                            stderr=b"processing symbols\n")
        with patch.object(runner.subprocess, "run", side_effect=timeout):
            output = runner.capture(["sample", "10", "3"])
            self.assertIn("timed out", output)
            self.assertIn("Sampling completed", output)
            self.assertIn("processing symbols", output)

    def test_host_matching_requires_unique_pinned_simulator_and_exact_app_path(self):
        devices = json.dumps({"devices": {"com.apple.CoreSimulator.SimRuntime.iOS-27-0": [
            {"udid": "pinned", "name": "iPhone 17e", "state": "Booted"}]}})
        app = "/Library/Developer/CoreSimulator/Devices/pinned/data/Containers/Bundle/Application/id/MC1.app"
        rows = [(11, 1, 11, app + "/MC1 -AppleLanguages (en)"),
                (12, 1, 12, app.replace("pinned", "other") + "/MC1")]
        with patch.object(runner, "capture", side_effect=[devices, app]):
            self.assertEqual(runner.pinned_test_host(rows), 11)
        with patch.object(runner, "capture", side_effect=[devices, app]):
            self.assertIsNone(runner.pinned_test_host(rows + [(13, 1, 13, app + "/MC1")]))


if __name__ == "__main__":
    unittest.main()
