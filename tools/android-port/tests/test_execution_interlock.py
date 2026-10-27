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
from fixtures import base_manifest


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

    @contextlib.contextmanager
    def matching_runtime(self, *, candidate_repository="fixture/repository"):
        common = self.root / "common"
        target = common / "meshcore-reservations"
        target.mkdir(parents=True)
        reserve.write_runtime(target, "fixture/repository")
        policy = self.root / "docs/android/automation-policy.json"
        policy.parent.mkdir(parents=True)
        policy.write_text(json.dumps({"repository": candidate_repository}), encoding="utf-8")
        with patch.object(check, "capture", side_effect=lambda command, **kwargs:
                          str(self.root if "--show-toplevel" in command else common)), \
                patch.object(check, "binding", return_value=(self.commit, self.tree)), \
                patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(self.root / "ledger.sqlite3")}):
            yield

    @contextlib.contextmanager
    def installer_fixture(self, name, *, schema=2):
        repo = self.root / name
        repo.mkdir()
        policy = repo / "docs/android/automation-policy.json"
        policy.parent.mkdir(parents=True)
        policy.write_text('{"repository":"fixture/repository"}', encoding="utf-8")
        hooks = self.root / (name + "-empty-hooks")
        hooks.mkdir()

        def git(*arguments):
            subprocess.run(["git", "-C", str(repo), "-c", "core.hooksPath=" + str(hooks),
                            "-c", "commit.gpgsign=false", "-c", "user.name=Fixture",
                            "-c", "user.email=fixture@example.invalid", *arguments],
                           check=True, capture_output=True)

        git("init", "--quiet")
        git("add", "docs")
        git("commit", "--quiet", "-m", "trusted fixture base")
        git("update-ref", "refs/remotes/origin/main", "HEAD")
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        target = repo / ".git/meshcore-reservations"
        if schema is not None:
            target.mkdir()
            reserve.write_runtime(target, "fixture/repository")
            if schema == 1:
                path = target / "runtime-manifest.json"
                value = json.loads(path.read_text(encoding="utf-8"))
                value.pop("repository")
                value["schema_version"] = 1
                path.write_text(json.dumps(value), encoding="utf-8")
        with patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(self.root / "ledger.sqlite3")}), \
                patch.object(reserve, "load_manifest", return_value=base_manifest()), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            yield repo, target, policy, git

    def test_real_installer_cli_rejects_candidate_partition_drift_without_touching_genuine_execution(self):
        with self.installer_fixture("installer-drift") as (repo, target, policy, git):
            policy.write_text('{"repository":"candidate/incorrect"}', encoding="utf-8")
            git("add", "docs")
            git("commit", "--quiet", "-m", "candidate metadata drift")
            original = {str(p.relative_to(target)): p.read_bytes() for p in target.rglob("*") if p.is_file()}
            for resource in ("native-execution", "gradle-execution"):
                self.own(resource)
                before = self.ledger.hard_lock_records()
                with patch.object(reserve, "write_runtime", wraps=reserve.write_runtime) as write:
                    self.assertEqual(reserve.main([
                        "--repo", str(repo), "install-runtime", "--owner", "candidate-install",
                    ]), 1)
                write.assert_not_called()
                self.assertEqual(self.other.hard_lock_records(), before)
                self.assertEqual({str(p.relative_to(target)): p.read_bytes()
                                  for p in target.rglob("*") if p.is_file()}, original)
                with self.ledger.transaction() as connection:
                    self.assertEqual(connection.execute("SELECT COUNT(*) FROM hard_locks").fetchone()[0], 1)
                self.ledger.release_hard_lock(resource, "native-owner")

    def test_real_installer_bootstraps_initial_and_legacy_only_from_trusted_default_base(self):
        for schema in (None, 1):
            with self.subTest(schema=schema), self.installer_fixture("bootstrap-" + str(schema), schema=schema) as (
                repo, target, _, _
            ):
                original = {str(p.relative_to(target)): p.read_bytes()
                            for p in target.rglob("*") if p.is_file()}
                for resource in ("native-execution", "gradle-execution"):
                    self.own(resource)
                    before = self.ledger.hard_lock_records()
                    with patch.object(reserve, "write_runtime", wraps=reserve.write_runtime) as write:
                        self.assertEqual(reserve.main([
                            "--repo", str(repo), "install-runtime", "--owner", "fixture-install",
                        ]), 1)
                    write.assert_not_called()
                    self.assertEqual(self.ledger.hard_lock_records(), before)
                    self.assertEqual({str(p.relative_to(target)): p.read_bytes()
                                      for p in target.rglob("*") if p.is_file()}, original)
                    self.ledger.release_hard_lock(resource, "native-owner")
                self.assertEqual(reserve.main([
                    "--repo", str(repo), "install-runtime", "--owner", "fixture-install",
                ]), 0)
                manifest = reserve.verify_runtime(target)
                self.assertEqual(manifest["schema_version"], 2)
                self.assertEqual(manifest["repository"], "fixture/repository")
                self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_real_installer_non_object_candidate_or_runtime_metadata_is_a_typed_failure(self):
        with self.installer_fixture("malformed-installer") as (repo, target, policy, _):
            self.own("native-execution")
            before = self.ledger.hard_lock_records()
            manifest = target / "runtime-manifest.json"
            original_policy, original_manifest = policy.read_bytes(), manifest.read_bytes()
            for path, value in ((policy, None), (policy, {"repository": []}),
                                (manifest, []), (manifest, {"schema_version": 2, "repository": []}),
                                (manifest, {**json.loads(original_manifest),
                                            "repository": "foreign/installed"})):
                policy.write_bytes(original_policy)
                manifest.write_bytes(original_manifest)
                path.write_text(json.dumps(value), encoding="utf-8")
                with self.subTest(value=value), patch.object(reserve, "write_runtime") as write:
                    self.assertEqual(reserve.main([
                        "--repo", str(repo), "install-runtime", "--owner", "fixture-install",
                    ]), 1)
                write.assert_not_called()
                self.assertEqual(self.ledger.hard_lock_records(), before)

    def test_installer_without_default_base_fails_closed_instead_of_using_candidate_policy(self):
        for schema in (None, 1, 2):
            with self.subTest(schema=schema), self.installer_fixture(
                "missing-authority-" + str(schema), schema=schema
            ) as (repo, target, _, git):
                original = {str(p.relative_to(target)): p.read_bytes()
                            for p in target.rglob("*") if p.is_file()}
                git("symbolic-ref", "--delete", "refs/remotes/origin/HEAD")
                with patch.object(reserve, "write_runtime") as write:
                    self.assertEqual(reserve.main([
                        "--repo", str(repo), "install-runtime", "--owner", "fixture-install",
                    ]), 1)
                write.assert_not_called()
                self.assertEqual({str(p.relative_to(target)): p.read_bytes()
                                  for p in target.rglob("*") if p.is_file()}, original)
                self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_non_object_or_malformed_namespace_inputs_record_pending_without_typeerror_or_launch(self):
        with self.matching_runtime():
            policy = self.root / "docs/android/automation-policy.json"
            manifest = self.root / "common/meshcore-reservations/runtime-manifest.json"
            original_policy, original_manifest = policy.read_bytes(), manifest.read_bytes()
            for path, value in (
                (policy, None), (policy, []), (policy, {"repository": []}),
                (policy, {"repository": 42}), (manifest, None), (manifest, [{}]),
                (manifest, {"schema_version": 2, "repository": {}, "files": {}}),
                (manifest, {**json.loads(original_manifest), "schema_version": True}),
                (manifest, {**json.loads(original_manifest), "files": {"C:escape": "a" * 64}}),
            ):
                policy.write_bytes(original_policy)
                manifest.write_bytes(original_manifest)
                path.write_text(json.dumps(value), encoding="utf-8")
                with self.subTest(path=path.name, value=value), \
                        patch.object(check, "transport") as transport, \
                        patch.object(check.subprocess, "run") as process, \
                        patch("sys.stdin", io.StringIO(f"refs/a {self.commit} refs/a {check.ZERO}\n")), \
                        contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(check.main(["--pre-push"]), 0)
                transport.assert_not_called()
                process.assert_not_called()
                record = json.loads(check.pending_file(self.root).read_text(encoding="utf-8"))
                self.assertEqual((record["commit"], record["tree"]), (self.commit, self.tree))
                self.assertEqual(record["status"], "verification-pending")
                self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_actual_matching_runtime_rejects_candidate_namespace_drift_with_entire_native_row_unchanged(self):
        self.own("native-execution")
        before = self.ledger.hard_lock_records()
        with self.matching_runtime(candidate_repository="candidate/incorrect"), \
                patch.object(check, "transport") as transport, \
                patch.object(check, "run_candidate") as candidate, \
                patch.object(check, "run_adapter") as adapter, \
                patch.object(check.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "trusted installed runtime identity"):
                check.run_owned(self.root, self.commit, None, check.ALL_STAGES, False)
        for launch in (transport, candidate, adapter, process):
            launch.assert_not_called()
        self.assertEqual(self.other.hard_lock_records(), before)
        with self.ledger.transaction() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM hard_locks").fetchone()[0], 1)

    def test_actual_missing_tool_is_pending_exact_nonblocking_push_without_any_poisoned_lease(self):
        with self.matching_runtime(), patch.object(check.shutil, "which", return_value=None), \
                patch.object(check.subprocess, "run") as process, \
                patch.object(check, "run_candidate") as candidate, \
                patch("sys.stdin", io.StringIO(f"refs/a {self.commit} refs/a {check.ZERO}\n")), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(check.main(["--pre-push"]), 0)
            record = json.loads(check.pending_file(self.root).read_text(encoding="utf-8"))
        self.assertIn("unavailable", record["reason"])
        self.assertEqual((record["commit"], record["tree"]), (self.commit, self.tree))
        self.assertEqual(record["status"], "verification-pending")
        process.assert_not_called()
        candidate.assert_not_called()
        self.assertEqual(self.other.hard_lock_records(), [])

    def test_actual_prelaunch_worktree_refusal_releases_own_identity_without_launch(self):
        with self.matching_runtime(), patch.object(check, "transport") as transport, \
                patch.object(check.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "Working-tree overlays"):
                check.run_owned(self.root, self.commit, None, check.ALL_STAGES, True)
        transport.assert_not_called()
        process.assert_not_called()
        self.assertEqual(self.other.hard_lock_records(), [])

    def test_worktree_and_structural_refusals_never_enter_transport_or_admission_with_held_native(self):
        self.own("native-execution")
        before = self.ledger.hard_lock_records()
        for stages, worktree in ((check.ALL_STAGES, True), (None, False), ([], False),
                                 (["inspect", "inspect"], False), (["inspect", 1], False)):
            with patch.object(check, "transport") as transport, \
                    patch.object(check, "execution_runtime") as runtime, \
                    patch.object(check.subprocess, "run") as process, \
                    self.subTest(stages=stages, worktree=worktree), self.assertRaises(ValueError):
                check.run_owned(self.root, self.commit, None, stages, worktree)
            transport.assert_not_called()
            runtime.assert_not_called()
            process.assert_not_called()
            self.assertEqual(self.other.hard_lock_records(), before)

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
        with patch.object(check, "execution_runtime") as runtime, \
                self.assertRaisesRegex(ValueError, "Automatic pre-push checks have been removed"):
            check.install(self.root, [], None)
        runtime.assert_not_called()
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

    def test_explicit_exact_receipt_reentry_never_releases_live_outer_context(self):
        payload = {"wp": "WP-503", "pre-mutation": True}
        with self.ledger.hard_lock("native-execution", "exact-owner", payload, 123.0):
            receipt, = self.ledger.hard_lock_records()
            with self.other.hard_lock("native-execution", "exact-owner", payload, 999.0,
                                      expected_existing=receipt) as recovered:
                self.assertFalse(recovered["acquired_new"])
                self.assertEqual(self.ledger.hard_lock_records(), [receipt])
            self.assertEqual(self.ledger.hard_lock_records(), [receipt])
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_sequential_pre_mutation_retained_receipt_recovery_preserves_binding_until_explicit_release(self):
        payload = {"wp": "WP-503", "pre-mutation": True}
        self.ledger.acquire_hard_lock("native-execution", "exact-owner", payload, 123.0)
        receipt, = self.ledger.hard_lock_records()
        with self.assertRaisesRegex(ValueError, "fixture pre-mutation failure"):
            with self.other.hard_lock("native-execution", "exact-owner", payload, 999.0,
                                      expected_existing=receipt):
                raise ValueError("fixture pre-mutation failure")
        self.assertEqual(self.ledger.hard_lock_records(), [receipt])
        with self.other.hard_lock("native-execution", "exact-owner", payload, 1000.0,
                                  expected_existing=receipt):
            self.assertEqual(self.ledger.hard_lock_records(), [receipt])
        self.assertEqual(self.ledger.hard_lock_records(), [receipt])
        self.ledger.release_hard_lock("native-execution", "exact-owner")
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_recovery_rejects_foreign_stale_changed_payload_or_missing_receipt(self):
        self.own("native-execution")
        receipt, = self.ledger.hard_lock_records()
        for field, value in (("owner", "foreign"), ("acquired", 999.0),
                             ("payload", {"changed": True}), ("repository", "other/repository")):
            stale = {**receipt, field: value}
            with self.assertRaises(PortError):
                self.other.acquire_hard_lock("native-execution", "native-owner",
                                             receipt["payload"], 999.0, expected_existing=stale)
            self.assertEqual(self.ledger.hard_lock_records(), [receipt])
        with self.assertRaises(PortError):
            self.other.acquire_hard_lock("native-execution", "foreign", receipt["payload"], 999.0,
                                         expected_existing=receipt)
        self.ledger.release_hard_lock("native-execution", "native-owner")
        with self.assertRaises(PortError):
            self.other.acquire_hard_lock("native-execution", "native-owner", receipt["payload"], 999.0,
                                         expected_existing=receipt)
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_transport_failure_and_unacknowledged_exit_retain_real_ownership(self):
        def candidate(*args, **kwargs):
            kwargs["execution"]["started"] = True
            locks = self.other.hard_lock_records()
            self.assertEqual(locks[0]["resource"], "gradle-execution")
            with self.assertRaises(PortError):
                self.other.acquire_hard_lock("native-execution", "native-new", {}, 1.0)
            # A returned subprocess status is deliberately not a cleanup acknowledgement.
            return subprocess.CompletedProcess(["fixture"], 0)

        for fails_transport in (True, False):
            def transport(*args, **kwargs):
                if fails_transport:
                    kwargs["execution"]["started"] = True
                    raise OSError("transport uncertain")
                return []

            with patch.object(check, "transport", side_effect=transport), \
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
        for mode in ("missing", "old-schema", "old", "mismatched", "old-loaded-ledger", "matching"):
            if mode != "missing":
                target.mkdir(exist_ok=True)
                reserve.write_runtime(target, "fixture/repository")
                if mode == "old-schema":
                    data = json.loads((target / "runtime-manifest.json").read_text())
                    data.pop("repository")
                    data["schema_version"] = 1
                    (target / "runtime-manifest.json").write_text(json.dumps(data), encoding="utf-8")
                elif mode == "old":
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
                                 1 if mode == "old-loaded-ledger" else 2):
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

    def test_real_startup_timeout_cleanup_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_startup_timeout_records_pending_without_child_readiness")

    def test_real_manual_completion_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_manual_check_stays_serialized_without_advisory_deadline")

    def test_real_manual_failure_retention_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_manual_failure_remains_failure_without_pending_success")

    def test_real_running_fixture_blocks_native_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_native_cannot_enter_during_actual_owned_fixture_execution")

    def test_internal_mode_cannot_bypass_handoff_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_owned_lock_mode_cannot_skip_real_lane_ownership")

    def test_real_persistent_owned_manual_child_retains_or_unsupported_platform_rejection(self):
        self.linux_fixture_or_fail_closed("test_owned_persistent_manual_child_retains_until_fixture_owner_reaps_it")
