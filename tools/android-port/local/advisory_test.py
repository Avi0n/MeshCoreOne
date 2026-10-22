"""AndroidOnly: WP-504 Real test-owned lane/launcher, pending binding and descendant cleanup."""

import contextlib
import ctypes
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

if __package__ == "local":
    from . import check
else:
    import check
runtime = Path(check.__file__).resolve().parent
sys.path.insert(0, str(runtime.parent if runtime.name == "local"
                       else runtime.parents[1] / "meshcore-reservations"))
from controller.ledger import Ledger


def fixture_class(host):
    if host.startswith("linux"):
        return AdvisoryLauncherTests
    if host == "win32":
        return WindowsAdvisoryGuardTests
    raise ValueError("Local advisory fixtures require Linux or native Windows")


def load_tests(loader, tests, pattern):
    selected = fixture_class(sys.platform)
    print(json.dumps({
        "local_advisory_fixture_host": sys.platform,
        "selected": selected.__name__,
        "linux_process_proof": "SELECTED" if sys.platform.startswith("linux") else "NOT SELECTED",
        "reason": "Linux process fixtures and native Windows rejection are distinct host scopes",
    }), flush=True)
    return loader.loadTestsFromTestCase(selected)


class WindowsAdvisoryGuardTests(unittest.TestCase):
    def test_actual_windows_rejects_linux_supervision_before_any_launch(self):
        if sys.platform != "win32":
            raise ValueError("Native Windows rejection fixture requires actual Windows")
        if __package__ == "local":
            from . import advisory
        else:
            import advisory
        with patch.object(advisory.subprocess, "Popen") as launch, \
                patch.object(advisory.ctypes, "CDLL") as libc:
            with self.assertRaisesRegex(ValueError, "supported Linux pidfd runner"):
                advisory.supervise(["must-not-launch"])
        launch.assert_not_called()
        libc.assert_not_called()


class AdvisoryLauncherTests(unittest.TestCase):
    def native(self):
        if sys.platform.startswith("linux"):
            return False
        raise ValueError("Real process fixtures require isolated Linux; no automatic WSL fallback")

    @contextlib.contextmanager
    def fixture(self, fast_source):
        with tempfile.TemporaryDirectory(prefix="meshcore-advisory-test-") as temporary:
            root = Path(temporary)
            repo, work, control = (root / name for name in ("repo", "work", "control"))
            repo.mkdir()
            control.mkdir()
            (work / "local-checks").mkdir(parents=True)
            (repo / "android").mkdir()
            (repo / "android/gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
            subprocess.run(["git", "init", "--quiet", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "-c", "user.name=Fixture",
                            "-c", "user.email=fixture@example.invalid", "commit", "--quiet",
                            "-m", "tooling fixture"], check=True)
            for name in ("run.sh", "advisory.py"):
                (control / name).write_bytes(
                    Path(check.__file__).with_name(name).read_bytes().replace(b"\r\n", b"\n"))
            (control / "fast.py").write_text(fast_source, encoding="utf-8")
            commit, tree = check.binding(repo, "HEAD")
            ledger = Ledger(root / "ledger.sqlite3", "fixture/repository")
            with patch.dict(os.environ, {"ANDROID_LOCAL_HOME": str(work),
                                        "ANDROID_LOCAL_PYTHON": sys.executable}), \
                 patch.object(check, "__file__", str(control / "check.py")), \
                 patch.object(check, "ADVISORY_QUEUE_SECONDS", 0.2), \
                 patch.object(check, "ADVISORY_FAST_SECONDS", 0.3), \
                 patch.object(check, "execution_runtime", return_value=ledger), \
                 patch.object(check, "transport", return_value=[]):
                previous = Path.cwd()
                os.chdir(repo)
                try:
                    yield root, repo, work, commit, tree
                finally:
                    os.chdir(previous)

    @contextlib.contextmanager
    def holder(self, work):
        lock = work / "local-checks/gradle.lock"
        ready, stop = work / "ready", work / "stop"
        script = (
            "import fcntl,pathlib,sys,time\n"
            "lock=open(sys.argv[1],'w'); fcntl.flock(lock,fcntl.LOCK_EX)\n"
            "pathlib.Path(sys.argv[2]).touch()\n"
            "while not pathlib.Path(sys.argv[3]).exists(): time.sleep(.02)\n"
        )
        process = subprocess.Popen([sys.executable, "-c", script, str(lock), str(ready), str(stop)])
        try:
            deadline = time.monotonic() + 5
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists(), "Test-owned lane holder did not start")
            yield process
        finally:
            stop.touch()
            process.wait(timeout=5)

    def pre_push(self, commit):
        with patch("sys.stdin", io.StringIO(f"refs/heads/fixture {commit} refs/heads/fixture {check.ZERO}\n")), \
             contextlib.redirect_stderr(io.StringIO()) as errors:
            result = check.main(["--pre-push"])
        return result, errors.getvalue()

    def test_held_lane_records_exact_pending_and_preserves_holder(self):
        if self.native():
            return
        import fcntl
        with self.fixture("raise RuntimeError('Queued check must not start fast.py')\n") as (_, repo, work, commit, tree):
            with self.holder(work) as holder:
                started = time.monotonic()
                result, errors = self.pre_push(commit)
                self.assertLess(time.monotonic() - started, 3)
                self.assertEqual(result, 0)
                self.assertIn("UNVERIFIED CANDIDATE", errors)
                record = json.loads(check.pending_file(repo).read_text())
                self.assertEqual((record["commit"], record["tree"], record["status"]),
                                 (commit, tree, "verification-pending"))
                self.assertIn("queued beyond", record["reason"])
                self.assertFalse((work / "local-checks/repos").exists())
                self.assertIsNone(holder.poll())
                self.assertEqual(check.execution_runtime(repo).hard_lock_records(), [])
                with (work / "local-checks/gradle.lock").open() as lock:
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_timeout_reaps_detached_term_ignoring_child_and_releases_lane(self):
        if self.native():
            return
        import fcntl
        fast_source = (
            "import os,pathlib,signal,subprocess,sys,threading,time\n"
            "signal.signal(signal.SIGTERM,signal.SIG_IGN)\n"
            "work=pathlib.Path(os.environ['ANDROID_LOCAL_HOME'])\n"
            "code=\"import os,pathlib,signal,sys,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); "
            "pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(60)\"\n"
            "def spawn():\n"
            " subprocess.Popen([sys.executable,'-c',code,str(work/'child.pid')],start_new_session=True)\n"
            " time.sleep(60)\n"
            "threading.Thread(target=spawn,daemon=True).start()\n"
            "time.sleep(60)\n"
        )
        with self.fixture(fast_source) as (_, repo, work, commit, tree):
            started = time.monotonic()
            result, _ = self.pre_push(commit)
            self.assertEqual(result, 0)
            self.assertLess(time.monotonic() - started, 6)
            record = json.loads(check.pending_file(repo).read_text())
            self.assertEqual((record["commit"], record["tree"]), (commit, tree))
            self.assertIn("owned work cleaned", record["reason"])
            self.assertEqual(check.execution_runtime(repo).hard_lock_records(), [])
            pid = int((work / "child.pid").read_text())
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)
            with (work / "local-checks/gradle.lock").open() as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_manual_check_stays_serialized_without_advisory_deadline(self):
        if self.native():
            return
        fast_source = (
            "import pathlib,os,sys\n"
            "assert '--advisory' not in sys.argv\n"
            "pathlib.Path(os.environ['ANDROID_LOCAL_HOME'],'manual-ran').touch()\n"
        )
        with self.fixture(fast_source) as (_, repo, work, _, _):
            results = []
            with self.holder(work):
                thread = threading.Thread(target=lambda: results.append(check.main([])))
                thread.start()
                time.sleep(0.5)
                self.assertTrue(thread.is_alive(), "Manual verification bypassed the held lane")
                self.assertFalse((work / "manual-ran").exists())
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())
            self.assertEqual(results, [0])
            self.assertTrue((work / "manual-ran").exists())
            self.assertEqual(check.execution_runtime(repo).hard_lock_records(), [])
            self.assertEqual(check.main([]), 0)
            self.assertEqual(check.execution_runtime(repo).hard_lock_records(), [])

    def test_manual_failure_remains_failure_without_pending_success(self):
        if self.native():
            return
        with self.fixture("raise RuntimeError('Explicit fixture verification failure')\n") as (_, repo, _, _, _):
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(check.main([]), 1)
            self.assertIn("BLOCKED", errors.getvalue())
            self.assertFalse(check.pending_file(repo).exists())
            self.assertEqual(check.execution_runtime(repo).hard_lock_records(), [])

    def test_native_cannot_enter_during_actual_owned_fixture_execution(self):
        if self.native():
            return
        fast_source = (
            "import pathlib,os,time\n"
            "work=pathlib.Path(os.environ['ANDROID_LOCAL_HOME'])\n"
            "(work/'active').touch()\n"
            "while not (work/'finish').exists(): time.sleep(.02)\n"
        )
        with self.fixture(fast_source) as (_, repo, work, _, _):
            ledger = check.execution_runtime(repo)
            results = []
            thread = threading.Thread(target=lambda: results.append(check.main([])))
            thread.start()
            try:
                deadline = time.monotonic() + 10
                while not (work / "active").exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue((work / "active").exists())
                before = ledger.hard_lock_records()
                self.assertEqual(len(before), 1)
                from controller.errors import PortError
                with self.assertRaises(PortError):
                    ledger.acquire_hard_lock("native-execution", "unrelated-native", {}, 1.0)
                self.assertEqual(ledger.hard_lock_records(), before)
            finally:
                (work / "finish").touch()
                thread.join(timeout=10)
            self.assertFalse(thread.is_alive())
            self.assertEqual(results, [0])
            self.assertEqual(ledger.hard_lock_records(), [])

    def test_owned_persistent_manual_child_retains_until_fixture_owner_reaps_it(self):
        if self.native():
            return
        if __package__ == "local":
            from . import advisory
        else:
            import advisory
        libc = ctypes.CDLL(None, use_errno=True)
        previous = ctypes.c_int()
        self.assertEqual(libc.prctl(37, ctypes.byref(previous), 0, 0, 0), 0)
        self.assertEqual(libc.prctl(36, 1, 0, 0, 0), 0)
        fast_source = (
            "import os,pathlib,subprocess,sys,time\n"
            "work=pathlib.Path(os.environ['ANDROID_LOCAL_HOME'])\n"
            "code=\"import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(60)\"\n"
            "subprocess.Popen([sys.executable,'-c',code,str(work/'persistent.pid')],start_new_session=True)\n"
            "while not (work/'persistent.pid').exists(): time.sleep(.02)\n"
        )
        try:
            with self.fixture(fast_source) as (_, repo, work, _, _):
                ledger = check.execution_runtime(repo)
                pid = None
                try:
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(check.main([]), 1)
                    pid = int((work / "persistent.pid").read_text())
                    before = ledger.hard_lock_records()
                    self.assertEqual(len(before), 1)
                    self.assertFalse(Path(before[0]["payload"]["completion"]).exists())
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(check.main([]), 1)
                    self.assertEqual(ledger.hard_lock_records(), before)
                finally:
                    if pid is None and (work / "persistent.pid").is_file():
                        pid = int((work / "persistent.pid").read_text())
                    if pid is not None:
                        descriptor = advisory.pidfd_open(pid)
                        try:
                            self.assertTrue(advisory.is_owned(pid))
                            advisory.pidfd_signal(descriptor, signal.SIGKILL)
                            os.waitpid(pid, 0)
                        finally:
                            os.close(descriptor)
                lock, = ledger.hard_lock_records()
                ledger.release_hard_lock("gradle-execution", lock["owner"])
                import shutil

                shutil.rmtree(Path(lock["payload"]["completion"]).parent)
        finally:
            self.assertEqual(libc.prctl(36, previous.value, 0, 0, 0), 0)

    def test_owned_lock_mode_cannot_skip_real_lane_ownership(self):
        if self.native():
            return
        with self.fixture("raise RuntimeError('No inherited lock must never reach fast.py')\n") as (_, _, _, _, _):
            launcher = Path(check.__file__).with_name("run.sh")
            result = subprocess.run(["bash", str(launcher), "", "", "", "", "", "", "owned-lock"],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("did not inherit actual shared execution admission", result.stderr)


if __name__ == "__main__":
    unittest.main()
