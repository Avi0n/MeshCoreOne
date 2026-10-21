"""AndroidOnly: WP-003 Temporary-ledger arbitration; no host/native/Gradle execution."""

import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from controller.errors import PortError
from controller.ledger import Ledger
from local import check, reserve
from local import advisory


class ExecutionInterlockTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="interlock-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.ledger = Ledger(self.root / "ledger.sqlite3", "fixture/repository")
        self.other = Ledger(self.root / "ledger.sqlite3", "fixture/repository")
        self.commit, self.tree = "a" * 40, "b" * 40

    def own(self, resource, owner="native-owner"):
        self.ledger.acquire_hard_lock(resource, owner, {"wp": "WP-503", "nested": {"original": [1, 2]}}, 123.0)

    def run_owned(self, **kwargs):
        with patch.object(check, "binding", return_value=(self.commit, self.tree)), \
                patch.object(check, "execution_runtime", return_value=self.ledger):
            return check.run_owned(self.root, self.commit, None, check.ALL_STAGES, False, **kwargs)

    def test_held_native_blocks_advisory_and_manual_before_any_transport_or_bundle(self):
        self.own("native-execution")
        before = copy.deepcopy(self.ledger.hard_lock_records())
        for advisory in (True, False):
            with patch.object(check, "transport") as transport, \
                    patch.object(check, "run_candidate") as candidate, \
                    patch.object(check, "run_adapter") as adapter, \
                    self.assertRaisesRegex(ValueError, "Hard-lock collision"):
                self.run_owned(advisory=advisory)
            transport.assert_not_called()
            candidate.assert_not_called()
            adapter.assert_not_called()
            self.assertEqual(self.other.hard_lock_records(), before)

    def test_atomic_interlock_in_both_directions_preserves_entire_incumbent(self):
        for first, second in (("native-execution", "gradle-execution"),
                              ("gradle-execution", "native-execution")):
            self.own(first)
            before = self.ledger.hard_lock_records()
            with self.assertRaisesRegex(PortError, "Hard-lock collision"):
                self.other.acquire_hard_lock(second, "native-owner:looks-related", {}, 999.0)
            self.assertEqual(self.other.hard_lock_records(), before)
            self.ledger.release_hard_lock(first, "native-owner")

    def test_real_two_party_races_have_exactly_one_winner_until_release(self):
        for _ in range(10):
            barrier = threading.Barrier(3)
            attempted = threading.Barrier(3)
            release = threading.Event()
            results = []

            def compete(ledger, resource):
                barrier.wait(timeout=10)
                try:
                    ledger.acquire_hard_lock(resource, resource, {"source": resource}, 1.0)
                except PortError:
                    results.append((resource, False))
                    attempted.wait(timeout=10)
                else:
                    results.append((resource, True))
                    attempted.wait(timeout=10)
                    release.wait(timeout=10)
                    ledger.release_hard_lock(resource, resource)

            threads = [
                threading.Thread(target=compete, args=(self.ledger, "native-execution")),
                threading.Thread(target=compete, args=(self.other, "gradle-execution")),
            ]
            for thread in threads:
                thread.start()
            barrier.wait(timeout=10)
            attempted.wait(timeout=10)
            try:
                self.assertEqual(sorted(won for _, won in results), [False, True])
                self.assertEqual(len(self.ledger.hard_lock_records()), 1)
            finally:
                release.set()
                for thread in threads:
                    thread.join(timeout=10)
                    self.assertFalse(thread.is_alive())
            self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_exact_same_owner_native_packaging_nests_and_other_resources_are_independent(self):
        for first, second in (("native-execution", "gradle-execution"),
                              ("gradle-execution", "native-execution")):
            with self.ledger.hard_lock(first, "exact-owner", {"incumbent": True}, 1.0):
                before = self.ledger.hard_lock_records()
                with self.other.hard_lock(second, "exact-owner", {"nested": True}, 2.0):
                    with self.other.hard_lock("physical-device", "different-owner", {}, 3.0):
                        self.assertEqual(len(self.ledger.hard_lock_records()), 3)
                self.assertEqual(self.ledger.hard_lock_records(), before)
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_runtime_install_cannot_cross_execution_even_for_same_owner(self):
        for first, second in (("native-execution", "installed-git-common-runtime"),
                              ("gradle-execution", "installed-git-common-runtime"),
                              ("installed-git-common-runtime", "native-execution"),
                              ("installed-git-common-runtime", "gradle-execution")):
            self.own(first)
            before = self.ledger.hard_lock_records()
            with self.assertRaises(PortError):
                self.other.acquire_hard_lock(second, "native-owner", {}, 124.0)
            self.assertEqual(self.ledger.hard_lock_records(), before)
            self.ledger.release_hard_lock(first, "native-owner")

    def test_actual_installers_reject_held_fixture_native_without_writes(self):
        self.own("native-execution")
        before = self.ledger.hard_lock_records()
        with patch.object(reserve, "write_runtime") as write, self.assertRaises(PortError):
            reserve.install_runtime(self.root / "installed", self.other, "native-owner", 1.0)
        write.assert_not_called()
        with patch.object(check, "execution_runtime", return_value=self.other), \
                patch.object(check, "install_hook_files") as write, self.assertRaises(ValueError):
            check.install(self.root, [], None)
        write.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records(), before)

    def test_foreign_or_stale_release_never_changes_payload(self):
        self.own("gradle-execution", "local-check:actual-invocation")
        before = self.ledger.hard_lock_records()
        for owner in ("local-check:old-invocation", "local-check", "foreign"):
            with self.assertRaisesRegex(PortError, "Missing or foreign"):
                self.other.release_hard_lock("gradle-execution", owner)
            self.assertEqual(self.ledger.hard_lock_records(), before)
        with self.assertRaises(PortError):
            self.other.acquire_hard_lock("gradle-execution", "local-check:actual-invocation",
                                         {"replacement": True}, 999.0)
        self.assertEqual(self.ledger.hard_lock_records(), before)

    def test_transport_failure_and_unacknowledged_exit_retain_real_ownership(self):
        def candidate(*args, **kwargs):
            locks = self.other.hard_lock_records()
            self.assertEqual(locks[0]["resource"], "gradle-execution")
            with self.assertRaises(PortError):
                self.other.acquire_hard_lock("native-execution", "native-new", {}, 1.0)
            # A returned subprocess status is deliberately not a cleanup acknowledgement.
            return subprocess.CompletedProcess(["fixture"], 0)

        for fails_transport in (True, False):
            with patch.object(check, "transport", side_effect=OSError("transport uncertain")
                              if fails_transport else None, return_value=[]), \
                    patch.object(check, "run_candidate", side_effect=candidate), \
                    contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaises((OSError, ValueError)):
                self.run_owned()
            lock, = self.ledger.hard_lock_records()
            self.assertEqual(lock["payload"]["commit"], self.commit)
            self.assertEqual(lock["payload"]["tree"], self.tree)
            directory = Path(lock["payload"]["completion"]).parent
            self.assertTrue(directory.is_dir())
            self.addCleanup(lambda path=directory: shutil.rmtree(path))
            self.ledger.release_hard_lock("gradle-execution", lock["owner"])

    def test_real_bundle_and_wsl_command_construction_are_inside_atomic_ownership(self):
        launches = []

        def observe(command, **kwargs):
            launches.append(command)
            self.assertEqual(self.other.hard_lock_records()[0]["resource"], "gradle-execution")
            with self.assertRaises(PortError):
                self.other.acquire_hard_lock("native-execution", "foreign", {}, 1.0)
            return subprocess.CompletedProcess(command, 0)

        with patch.object(check, "transport", return_value=["fixture-wsl"]), \
                patch.object(check, "capture", return_value="/fixture-only"), \
                patch.object(check.subprocess, "run", side_effect=observe), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(ValueError):
            self.run_owned()
        self.assertEqual(launches[0][:4], ["git", "-C", str(self.root), "bundle"])
        self.assertEqual(launches[1][:2], ["fixture-wsl", "bash"])
        lock, = self.ledger.hard_lock_records()
        directory = Path(lock["payload"]["completion"]).parent
        self.addCleanup(lambda: shutil.rmtree(directory))
        self.assertTrue((directory / "control/run.sh").is_file())
        self.assertTrue((directory / "admission.json").is_file())
        self.ledger.release_hard_lock("gradle-execution", lock["owner"])

    def test_pre_push_held_native_is_nonblocking_pending_exact_pushed_commit_and_tree(self):
        self.own("native-execution")
        before = self.ledger.hard_lock_records()
        pending = self.root / "pending.json"
        with patch.object(check, "capture", return_value=str(self.root)), \
                patch.object(check, "binding", return_value=(self.commit, self.tree)), \
                patch.object(check, "pending_file", return_value=pending), \
                patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check, "transport") as transport, \
                patch.object(check, "run_candidate") as candidate, \
                patch("sys.stdin", io.StringIO(f"refs/a {self.commit} refs/a {check.ZERO}\n")), \
                contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(check.main(["--pre-push"]), 0)
        transport.assert_not_called()
        candidate.assert_not_called()
        record = json.loads(pending.read_text(encoding="utf-8"))
        self.assertEqual((record["commit"], record["tree"], record["status"]),
                         (self.commit, self.tree, "verification-pending"))
        self.assertIn("UNVERIFIED CANDIDATE", errors.getvalue())
        self.assertEqual(self.other.hard_lock_records(), before)

    def test_missing_old_mismatched_or_loaded_old_runtime_blocks_before_transport(self):
        common = self.root / "common"
        common.mkdir()
        target = common / "meshcore-reservations"
        policy = self.root / "docs/android/automation-policy.json"
        policy.parent.mkdir(parents=True)
        policy.write_text('{"repository":"fixture/repository"}', encoding="utf-8")
        for mode in ("missing", "old", "mismatched", "old-loaded-ledger", "matching"):
            if mode != "missing":
                target.mkdir(exist_ok=True)
                reserve.write_runtime(target)
                if mode == "old":
                    (target / "controller/ledger.py").write_text("# old runtime\n", encoding="utf-8")
                    data = json.loads((target / "runtime-manifest.json").read_text())
                    data["files"]["controller/ledger.py"] = reserve.file_sha256(target / "controller/ledger.py")
                    (target / "runtime-manifest.json").write_text(json.dumps(data), encoding="utf-8")
                elif mode == "mismatched":
                    (target / "run.sh").write_text("# mismatched runner\n", encoding="utf-8")
            from controller import ledger as loaded
            with self.subTest(mode=mode), patch.object(check, "capture", return_value=str(common)), \
                    patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(self.root / "runtime-ledger.sqlite3")}), \
                    patch.object(loaded, "EXECUTION_INTERLOCK_VERSION",
                                 0 if mode == "old-loaded-ledger" else 1):
                if mode == "matching":
                    self.assertEqual(check.execution_runtime(self.root).repository, "fixture/repository")
                else:
                    with self.assertRaises((ValueError, OSError)):
                        check.execution_runtime(self.root)

    def test_malformed_stale_or_symlink_cleanup_never_releases_ownership(self):
        proof = self.root / "cleanup.json"
        for data in (None, [], [{}], {}, {"schema_version": 1, "token": "stale", "status": "owned-children-reaped",
                          "returncode": 0}, {"schema_version": 1, "token": "token",
                                            "status": "process-exited", "returncode": 0}):
            proof.write_text(json.dumps(data), encoding="utf-8")
            self.assertFalse(check.cleaned_execution(proof, "token"))
        proof.write_text("{", encoding="utf-8")
        self.assertFalse(check.cleaned_execution(proof, "token"))

    def test_runtime_revalidation_failure_releases_only_unlaunched_own_lease(self):
        with patch.object(check, "binding", return_value=(self.commit, self.tree)), \
                patch.object(check, "execution_runtime", side_effect=[self.ledger, ValueError("runtime changed")]), \
                patch.object(check, "transport") as transport, self.assertRaises(ValueError):
            check.run_owned(self.root, self.commit, None, check.ALL_STAGES, False)
        transport.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_supervisor_cleanup_failure_never_emits_completion_even_after_zero_exit(self):
        completion = self.root / "cleanup.json"
        with patch.object(advisory.sys, "platform", "linux"), \
                patch.object(advisory.ctypes, "CDLL") as library, \
                patch.object(advisory, "pidfd_open", return_value=100), \
                patch.object(advisory, "pidfd_signal"), patch.object(advisory.os, "close"), \
                patch.object(advisory.os, "fstat", side_effect=OSError), \
                patch.object(Path, "read_text", return_value=""), \
                patch.object(advisory.subprocess, "Popen") as launch, \
                patch.object(advisory, "cleanup", side_effect=RuntimeError("cleanup uncertain")):
            library.return_value.prctl.return_value = 0
            launch.return_value.wait.return_value = 0
            with self.assertRaisesRegex(RuntimeError, "cleanup uncertain"):
                advisory.supervise(["fixture"], completion=completion, token="token")
        self.assertFalse(completion.exists())

    def test_unreapable_owned_descendants_fail_bounded_cleanup_without_acknowledgement(self):
        with patch.object(advisory, "signal_owned"), patch.object(advisory, "reap"), \
                patch.object(advisory, "descendants", return_value={123}), \
                patch.object(advisory.time, "monotonic", side_effect=[0, 3, 4, 7]):
            with self.assertRaisesRegex(RuntimeError, "could not be reaped"):
                advisory.cleanup()


class ExecutionProcessFixtureTests(unittest.TestCase):
    def linux_fixture_or_fail_closed(self, method):
        if sys.platform.startswith("linux"):
            from local.advisory_test import AdvisoryLauncherTests

            getattr(AdvisoryLauncherTests(method), method)()
        else:
            with patch.object(advisory.subprocess, "Popen") as launch, \
                    self.assertRaisesRegex(ValueError, "supported Linux pidfd"):
                advisory.supervise(["must-not-launch"], 0.1)
            launch.assert_not_called()

    def test_real_queued_fixture_cleanup_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_held_lane_records_exact_pending_and_preserves_holder")

    def test_real_timeout_detached_child_cleanup_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_timeout_reaps_detached_term_ignoring_child_and_releases_lane")

    def test_real_manual_completion_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_manual_check_stays_serialized_without_advisory_deadline")

    def test_real_manual_failure_retention_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_manual_failure_remains_failure_without_pending_success")

    def test_real_running_fixture_blocks_native_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_native_cannot_enter_during_actual_owned_fixture_execution")

    def test_internal_mode_cannot_bypass_handoff_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_owned_lock_mode_cannot_skip_real_lane_ownership")
