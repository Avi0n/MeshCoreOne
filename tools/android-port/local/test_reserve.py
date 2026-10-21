import tempfile
import unittest
import json
import os
import subprocess
import sys
import io
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import reserve
from controller.ledger import Identity, Ledger


class ReservationRuntimeTests(unittest.TestCase):
    def test_evolution_requires_explicit_receipt_not_current_ledger_adoption(self):
        for command in ("evolve", "reconcile"):
            with self.subTest(command=command), redirect_stderr(io.StringIO()), \
                    self.assertRaises(SystemExit) as stopped:
                reserve.main([command, "--wp", "WP-407", "--expected-revision", "1",
                              "--path", "fixture", "--operation", "modify"])
            self.assertEqual(stopped.exception.code, 2)

    def test_evolution_cli_fences_stored_session_branch_worktree_and_attempt(self):
        repo = Path(__file__).resolve().parents[3]
        manifest = reserve.load_manifest(repo)
        policy = reserve.load_json(repo / "docs/android/automation-policy.json")
        wp = manifest.wp("WP-407")
        path = "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppSettingsContent.kt"
        with tempfile.TemporaryDirectory() as temporary:
            ledger = Ledger(Path(temporary) / "ledger.sqlite", policy["repository"])
            initial, _ = ledger.claim(
                wp, {"base_sha": "a" * 40}, "local", "current-attempt", 1, 1800, 5, 0, 100, 1,
                initial_identity=Identity(session_id="stored-session"),
                branch="owner-branch", worktree=str(repo.resolve()),
                authorization_context={"authorization": "unchanged original assignment"},
            )
            initial = ledger.get(wp["id"])
            invocation = ["--repo", str(repo), "evolve", "--wp", wp["id"],
                          "--attempt", "current-attempt", "--session-id", "stored-session",
                          "--expected-revision", "1", "--path", path,
                          "--operation", "update-launcher-or-composition"]
            branch, checkout = "owner-branch", str(repo)
            def git(_repo, *args):
                return branch if args[0] == "symbolic-ref" else checkout
            with patch.object(reserve, "load_manifest", return_value=manifest), \
                    patch.object(reserve, "Ledger", return_value=ledger), \
                    patch.object(reserve, "shared_ledger", return_value=Path(temporary) / "ledger.sqlite"), \
                    patch.object(reserve, "git", side_effect=git), \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                for flag, value in (("--attempt", "retired-attempt"), ("--session-id", "runtime-alias")):
                    wrong = invocation.copy()
                    wrong[wrong.index(flag) + 1] = value
                    self.assertEqual(reserve.main(wrong), 1)
                    self.assertEqual(ledger.get(wp["id"]), initial)
                branch = "retired-branch"
                self.assertEqual(reserve.main(invocation), 1)
                branch = "owner-branch"
                checkout = str(repo / "retired-checkout")
                self.assertEqual(reserve.main(invocation), 1)
                checkout = str(repo)
                self.assertEqual(ledger.get(wp["id"]), initial)
                self.assertEqual(reserve.main(invocation), 0)
                evolved = ledger.get(wp["id"])
                self.assertEqual(evolved["revision"], 2)
                for key in ("attempt", "identity", "binding", "authorization_context", "branch", "worktree"):
                    self.assertEqual(evolved[key], initial[key])
                self.assertEqual(reserve.main(invocation), 1)
                current = invocation.copy()
                current[current.index("--expected-revision") + 1] = "2"
                current[2] = "reconcile"
                self.assertEqual(reserve.main(current), 0)
                self.assertEqual(ledger.get(wp["id"]), evolved)

    def test_installed_cli_loads_real_manifest_outside_source_import_context(self):
        repo = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "runtime"
            ledger = Ledger(root / "ledger.sqlite3", "cbattlegear/MeshCoreOne-Android")
            reserve.install_runtime(target, ledger, "standalone-session", 1.0)
            environment = {
                key: value for key, value in os.environ.items()
                if not key.startswith("PYTHON") and not key.startswith("ANDROID_PORT")
            }
            environment["ANDROID_PORT_LEDGER"] = str(root / "standalone.sqlite3")
            result = subprocess.run(
                [sys.executable, "-I", str(target / "reserve.py"), "--repo", str(repo), "status"],
                cwd=root, env=environment, capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            status = json.loads(result.stdout)
            self.assertEqual(status["claims"], [])
            self.assertEqual(status["hard_locks"], [])
            self.assertEqual(len(status["policy_revision"]), 64)
            source = reserve.load_manifest(repo)
            policy = reserve.load_json(repo / "docs/android/automation-policy.json")
            self.assertEqual(status["policy_revision"], reserve.policy_revision(source, policy))
            self.assertEqual(source.wp("WP-407")["owner"], "platform-integrations-engineer")
            self.assertIn("inventory_rules.py", reserve.verify_runtime(target)["files"])

    def test_runtime_install_is_atomic_manifest_verified_and_repeatable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ledger = Ledger(root / "ledger.sqlite3", "cbattlegear/MeshCoreOne-Android")
            target = root / "meshcore-reservations"
            first = reserve.install_runtime(target, ledger, "session", 1.0)
            first_manifest = (target / "runtime-manifest.json").read_bytes()
            second = reserve.install_runtime(target, ledger, "session", 2.0)
            self.assertTrue(first["atomic"])
            self.assertEqual(first["manifest_sha256"], second["manifest_sha256"])
            self.assertTrue((target / "run.sh").is_file())
            self.assertEqual((target / "runtime-manifest.json").read_bytes(), first_manifest)
            self.assertEqual(reserve.verify_runtime(target)["schema_version"], 2)
            self.assertEqual(reserve.verify_runtime(target)["repository"], ledger.repository)
            self.assertEqual(ledger.hard_lock_records(), [])

    def test_runtime_verification_rejects_opaque_helper_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            reserve.write_runtime(target, "fixture/repository")
            (target / "reserve.py").write_text("drift\n", encoding="utf-8")
            with self.assertRaisesRegex(Exception, "differs from manifest"):
                reserve.verify_runtime(target)
