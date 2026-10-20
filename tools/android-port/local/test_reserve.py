import tempfile
import unittest
import json
import os
import subprocess
import sys
import io
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch
from pathlib import Path

import reserve
from controller.ledger import Ledger
from controller.errors import PortError
from controller.scope_amendment import ONBOARDING_RENDERER_CAPABILITY, onboarding_renderer_predecessor


class ReservationRuntimeTests(unittest.TestCase):
    def test_assigned_renderer_cli_claim_collision_and_evolve_use_real_receipts(self):
        repo, wp, policy, rule = self.assigned_fixture()
        manifest = reserve.load_manifest(repo)
        sid = rule["assignment"]["session_id"]
        base = "a" * 40
        response = subprocess.CompletedProcess(["gh"], 0, json.dumps({"object": {"sha": base}}))
        def git(_repo, *args):
            if args == ("rev-parse", "--show-toplevel"):
                return str(repo)
            if args[0] == "show":
                return json.dumps(policy)
            if args[0] == "rev-parse":
                return base
            return "fixture-owner-branch"
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(reserve, "load_manifest", return_value=manifest), \
                patch.object(reserve, "shared_ledger", return_value=Path(directory) / "claims.sqlite"), \
                patch.object(reserve.subprocess, "run", return_value=response), \
                patch.object(reserve, "git", side_effect=git), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as errors:
            command = ["--repo", str(repo), "claim", "--wp", "WP-303", "--session-id", sid,
                       "--additional-path", rule["path_rules"][0], "--authorization", "original fixture text"]
            self.assertEqual(reserve.main(command), 0)
            ledger = Ledger(Path(directory) / "claims.sqlite", policy["repository"])
            original = ledger.get(wp["id"])
            self.assertEqual(original["revision"], 1)
            self.assertIn(rule["path_rules"][0], original["capabilities"][rule["id"]])
            self.assertEqual(reserve.main(command), 1)
            self.assertIn("fresh initial acquisition", errors.getvalue())
            self.assertEqual(ledger.get(wp["id"]), original)
            evolve = ["--repo", str(repo), "evolve", "--wp", wp["id"], "--session-id", sid,
                      "--path", rule["path_rules"][0], "--operation", "modify",
                      "--expected-revision", "1"]
            self.assertEqual(reserve.main(evolve), 0)
            # The initial claim already has this exact scope; evolution is an idempotent no-op.
            self.assertEqual(ledger.get(wp["id"]), original)
            wrong = command.copy()
            wrong[wrong.index(sid)] = "wrong-session"
            self.assertEqual(reserve.main(wrong), 1)
            self.assertEqual(ledger.get(wp["id"]), original)

    def assigned_fixture(self):
        repo = Path(__file__).resolve().parents[3]
        manifest = reserve.load_manifest(repo)
        policy = reserve.load_json(repo / "docs/android/automation-policy.json")
        rule = ONBOARDING_RENDERER_CAPABILITY
        return repo, manifest.wp("WP-303"), policy, rule

    def test_assigned_renderer_candidate_cannot_authorize_its_own_product_edit(self):
        repo, wp, policy, rule = self.assigned_fixture()
        before = onboarding_renderer_predecessor(policy)
        response = subprocess.CompletedProcess(["gh"], 0, json.dumps({"object": {"sha": "a" * 40}}))
        with patch.object(reserve.subprocess, "run", return_value=response), \
                patch.object(reserve, "git", return_value=json.dumps(before)), \
                self.assertRaisesRegex(PortError, "not deployed"):
            reserve.assigned_admissions(repo, policy, wp, rule["path_rules"], ["modify"],
                                         rule["assignment"]["session_id"])

    def test_assigned_renderer_uses_actual_initial_claim_and_same_owner_cas_evolution(self):
        repo, wp, policy, rule = self.assigned_fixture()
        sid = rule["assignment"]["session_id"]
        base = "a" * 40
        response = subprocess.CompletedProcess(["gh"], 0, json.dumps({"object": {"sha": base}}))
        def git(_repo, *args):
            return json.dumps(policy) if args[0] == "show" else "fixture-owner-branch"
        with patch.object(reserve.subprocess, "run", return_value=response) as calls, \
                patch.object(reserve, "git", side_effect=git):
            admissions = reserve.assigned_admissions(repo, policy, wp, rule["path_rules"], ["modify"], sid)
            with tempfile.TemporaryDirectory() as directory:
                ledger = Ledger(Path(directory) / "fixture.sqlite", policy["repository"])
                binding = {"base_sha": base, "manifest_sha256": "b" * 64}
                original, acquired = ledger.claim(
                    wp, binding, "local", sid, 1, 1800, 5, 0, 100, 1,
                    branch="fixture-owner-branch", worktree=str(repo),
                    initial_identity=reserve.Identity(session_id=sid),
                    authorization_context={"authorization": "fixture immutable assignment"},
                )
                self.assertTrue(acquired)
                admitted = reserve.assigned_admissions(repo, policy, wp, rule["path_rules"], ["modify"],
                                                       sid, record=original)
                caps = {admitted[0].capability_id: rule["path_rules"]}
                evolved = ledger.evolve_scope(wp["id"], sid, 1, caps, ["modify"], rule["path_rules"])
                self.assertEqual(evolved["identity"], original["identity"])
                self.assertEqual(evolved["binding"], binding)
                self.assertEqual(evolved["authorization_context"], original["authorization_context"])
                self.assertEqual(evolved["attempt"], sid)
                self.assertEqual(evolved["revision"], 2)
                self.assertEqual(ledger.evolve_scope(wp["id"], sid, 2, caps, ["modify"], rule["path_rules"]),
                                 evolved)
                with self.assertRaisesRegex(PortError, "Stale"):
                    ledger.evolve_scope(wp["id"], sid, 1, caps, ["modify"], rule["path_rules"])
                for field, value in (("identity", reserve.Identity(session_id="other").as_dict()),
                                     ("branch", "other-branch"), ("worktree", directory)):
                    wrong = {**original, field: value}
                    with self.subTest(field=field), self.assertRaisesRegex(PortError, "another"):
                        reserve.assigned_admissions(repo, policy, wp, rule["path_rules"], ["modify"],
                                                     sid, record=wrong)
            self.assertEqual(admissions[0].capability_id, rule["id"])
            self.assertTrue(any(call.args[0][:2] == ["gh", "api"] for call in calls.call_args_list))

    def test_assigned_renderer_missing_auth_unknown_sha_or_stale_base_is_not_admitted(self):
        repo, wp, policy, rule = self.assigned_fixture()
        sid = rule["assignment"]["session_id"]
        with patch.object(reserve.subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["gh"])), \
                self.assertRaises(subprocess.CalledProcessError):
            reserve.assigned_admissions(repo, policy, wp, rule["path_rules"], ["modify"], sid)
        with patch.object(reserve.subprocess, "run",
                          return_value=subprocess.CompletedProcess(["gh"], 0, '{"object":{"sha":"main"}}')), \
                self.assertRaisesRegex(PortError, "immutable"):
            reserve.assigned_admissions(repo, policy, wp, rule["path_rules"], ["modify"], sid)
        response = subprocess.CompletedProcess(["gh"], 0, json.dumps({"object": {"sha": "a" * 40}}))
        with patch.object(reserve.subprocess, "run",
                          side_effect=[response, subprocess.CalledProcessError(1, ["git"])]), \
                patch.object(reserve, "git", return_value=json.dumps(policy)), \
                self.assertRaises(subprocess.CalledProcessError):
            reserve.assigned_admissions(repo, policy, wp, rule["path_rules"], ["modify"], sid)

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
            self.assertEqual(reserve.verify_runtime(target)["schema_version"], 1)
            self.assertEqual(ledger.hard_lock_records(), [])

    def test_runtime_verification_rejects_opaque_helper_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            reserve.write_runtime(target)
            (target / "reserve.py").write_text("drift\n", encoding="utf-8")
            with self.assertRaisesRegex(Exception, "differs from manifest"):
                reserve.verify_runtime(target)
