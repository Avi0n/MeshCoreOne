"""AndroidOnly: WP-504 Real test-owned lane/launcher, pending binding and descendant cleanup."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import check


class AdvisoryLauncherTests(unittest.TestCase):
    def native(self):
        if os.name != "nt":
            return False
        prefix = check.transport("Ubuntu-22.04")
        source = check.linux_path(Path(__file__).resolve(), prefix)
        python = check.capture([*prefix, "bash", "-c",
                                'printf "%s" "$HOME/meshcoreone-work/venv312/bin/python"'])
        subprocess.run([*prefix, python, source, self.id().split(".", 1)[-1]],
                       check=True, timeout=45)
        return True

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
            with patch.dict(os.environ, {"ANDROID_LOCAL_HOME": str(work),
                                        "ANDROID_LOCAL_PYTHON": sys.executable}), \
                 patch.object(check, "__file__", str(control / "check.py")), \
                 patch.object(check, "ADVISORY_QUEUE_SECONDS", 0.2), \
                 patch.object(check, "ADVISORY_FAST_SECONDS", 0.3), \
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
        with self.fixture(fast_source) as (_, _, work, _, _):
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

    def test_manual_failure_remains_failure_without_pending_success(self):
        if self.native():
            return
        with self.fixture("raise RuntimeError('Explicit fixture verification failure')\n") as (_, repo, _, _, _):
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(check.main([]), 1)
            self.assertIn("BLOCKED", errors.getvalue())
            self.assertFalse(check.pending_file(repo).exists())

    def test_owned_lock_mode_cannot_skip_real_lane_ownership(self):
        if self.native():
            return
        with self.fixture("raise RuntimeError('No inherited lock must never reach fast.py')\n") as (_, _, _, _, _):
            launcher = Path(check.__file__).with_name("run.sh")
            result = subprocess.run(["bash", str(launcher), "", "", "", "", "", "", "owned-lock"],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("did not inherit its actual lane lock", result.stderr)


if __name__ == "__main__":
    unittest.main()
