import tempfile
import unittest
import json
import os
import subprocess
import sys
import io
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import reserve
from controller.capabilities import CapabilityEngine
from controller.ledger import Identity, Ledger


@contextmanager
def authority_fixture():
    source = Path(__file__).resolve().parents[3]
    with tempfile.TemporaryDirectory(prefix="capability-authority-") as temporary:
        root = Path(temporary)
        repo, hooks = root / "repo", root / "empty-hooks"
        repo.mkdir()
        hooks.mkdir()
        subprocess.run([
            "git", "-c", "core.hooksPath=" + str(hooks), "clone", "--quiet",
            "--shared", "--no-checkout", str(source), str(repo),
        ], check=True)

        def git(*arguments):
            return subprocess.check_output([
                "git", "-C", str(repo), "-c", "core.hooksPath=" + str(hooks),
                "-c", "commit.gpgsign=false", "-c", "user.name=Fixture",
                "-c", "user.email=fixture@example.invalid", *arguments,
            ], encoding="utf-8").strip()

        git("checkout", "--quiet", "-b", "authority-fixture",
            "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c")
        git("update-ref", "refs/remotes/origin/main", "HEAD")
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        reserve.load_manifest(repo)
        ledger_path = root / "ledger.sqlite3"
        ledger = Ledger(ledger_path, "cbattlegear/MeshCoreOne-Android")

        def cli(*arguments):
            output, errors = io.StringIO(), io.StringIO()
            with redirect_stdout(output), redirect_stderr(errors):
                result = reserve.main(["--repo", str(repo), *arguments])
            return result, json.loads(output.getvalue()) if output.getvalue() else None, errors.getvalue()

        with patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(ledger_path)}):
            yield repo, ledger, git, cli


class ReservationRuntimeTests(unittest.TestCase):
    def test_evolution_requires_explicit_receipt_not_current_ledger_adoption(self):
        for command in ("evolve", "reconcile"):
            with self.subTest(command=command), redirect_stderr(io.StringIO()), \
                    self.assertRaises(SystemExit) as stopped:
                reserve.main([command, "--wp", "WP-407", "--expected-revision", "1",
                              "--path", "fixture", "--operation", "modify"])
            self.assertEqual(stopped.exception.code, 2)

    def test_evolution_cli_fences_stored_session_branch_worktree_and_attempt(self):
        path = "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppSettingsContent.kt"
        with authority_fixture() as (repo, ledger, _, _):
            manifest, _ = reserve.trusted_capability_authority(repo)
            wp = manifest.wp("WP-407")
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
            real_git = reserve.git
            def git(_repo, *args):
                if args == ("symbolic-ref", "--short", "HEAD"):
                    return branch
                if args == ("rev-parse", "--show-toplevel"):
                    return checkout
                return real_git(_repo, *args)
            with patch.object(reserve, "git", side_effect=git), \
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
        with authority_fixture() as (repo, _, _, _):
            root = repo.parent
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
            source, policy = reserve.trusted_capability_authority(repo)
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


class CapabilityAuthorityCliTests(unittest.TestCase):
    path = "android/feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/actions/MessageActionsUi.kt"
    operation = "remove-active-message-translation-affordance"

    @staticmethod
    def snapshot(ledger):
        with ledger.transaction() as connection:
            return [tuple(row) for row in connection.execute(
                "SELECT rowid,* FROM leases ORDER BY repository,wp"
            )]

    def claim(self, cli, wp="WP-502", *arguments):
        result, record, errors = cli(
            "claim", "--wp", wp, "--session-id", "existing-owner",
            "--authorization", "immutable original authorization", *arguments,
        )
        self.assertEqual(result, 0, errors)
        return record["record"]

    def assert_rejected(self, repo, ledger, cli, operation):
        before = self.snapshot(ledger)
        product = (repo / self.path).read_bytes()
        for command, options in (
            ("admit", []),
            ("claim", ["--session-id", "existing-owner", "--authorization", "not new authority"]),
            ("evolve", ["--attempt", "existing-owner", "--session-id", "existing-owner", "--expected-revision", "1"]),
            ("reconcile", ["--attempt", "existing-owner", "--session-id", "existing-owner", "--expected-revision", "1"]),
        ):
            result, _, errors = cli(
                command, "--wp", "WP-502", "--path", self.path, "--operation", operation, *options,
            )
            with self.subTest(command=command, operation=operation):
                self.assertEqual(result, 1, errors)
                self.assertIn("no trusted path+operation rule", errors)
                self.assertEqual(self.snapshot(ledger), before)
                self.assertEqual((repo / self.path).read_bytes(), product)

    def test_real_cli_candidate_rule_cannot_admit_evolve_claim_reconcile_or_transfer_itself(self):
        with authority_fixture() as (repo, ledger, git, cli):
            self.claim(cli)
            before = self.snapshot(ledger)
            policy_path = repo / "docs/android/automation-policy.json"
            source = Path(__file__).resolve().parents[3]
            policy_path.write_bytes(subprocess.check_output([
                "git", "-C", str(source), "show",
                "34a03419343036bf37982b1b37c35de4e50a7051:docs/android/automation-policy.json",
            ]))
            git("add", "docs")
            git("commit", "--quiet", "-m", "same-repository candidate-only rule")
            hypothetical = CapabilityEngine(reserve.load_json(policy_path)).admit(
                "WP-502", reserve.load_manifest(repo).wp("WP-502")["write_paths"], self.path, self.operation,
            )
            self.assertEqual(hypothetical.capability_id, "message-actions-translation-removal")
            self.assert_rejected(repo, ledger, cli, self.operation)
            result, _, errors = cli(
                "transfer", "--path", self.path, "--capability", "message-actions-translation-removal",
                "--origin-wp", "WP-502", "--origin-session", "existing-owner", "--origin-evidence", "original",
                "--producer-wp", "WP-502", "--producer-session", "other-producer", "--producer-evidence", "pending",
            )
            self.assertEqual(result, 1, errors)
            self.assertIn("does not admit transfer path", errors)
            self.assertEqual(self.snapshot(ledger), before)
            candidate = json.loads(policy_path.read_text(encoding="utf-8"))
            candidate["repository"] = "candidate/incorrect"
            policy_path.write_text(json.dumps(candidate), encoding="utf-8")
            git("add", "docs")
            git("commit", "--quiet", "-m", "candidate namespace cannot select a foreign partition")
            self.assert_rejected(repo, ledger, cli, self.operation)

    def test_real_cli_candidate_manifest_widening_cannot_grant_wp_owned_authority(self):
        with authority_fixture() as (repo, ledger, git, cli):
            self.claim(cli)
            path = repo / "docs/android/port-manifest.json"
            data = json.loads(path.read_text(encoding="utf-8"))
            next(wp for wp in data["work_packages"] if wp["id"] == "WP-502")["write_paths"].append(self.path)
            path.write_text(json.dumps(data), encoding="utf-8")
            git("add", "docs")
            git("commit", "--quiet", "-m", "candidate-only manifest widening")
            hypothetical = CapabilityEngine(reserve.load_json(repo / "docs/android/automation-policy.json")).admit(
                "WP-502", reserve.load_manifest(repo).wp("WP-502")["write_paths"], self.path, "modify",
            )
            self.assertEqual(hypothetical.capability_id, "wp-owned")
            self.assert_rejected(repo, ledger, cli, "modify")

    def test_real_cli_trusted_owned_and_support_development_survives_candidate_metadata_edits(self):
        with authority_fixture() as (repo, ledger, git, cli):
            original = self.claim(cli, "WP-003", "--path", "docs/android/automation-policy.json")
            (repo / "docs/android/automation-policy.json").write_text("null", encoding="utf-8")
            (repo / "docs/android/port-manifest.json").write_text("[]", encoding="utf-8")
            git("add", "docs")
            git("commit", "--quiet", "-m", "untrusted policy-development draft")
            result, admission, errors = cli(
                "admit", "--wp", "WP-003", "--path", "docs/android/automation-policy.json", "--operation", "modify",
            )
            self.assertEqual(result, 0, errors)
            self.assertEqual(admission["capability"], "wp-owned")
            result, evolved, errors = cli(
                "evolve", "--wp", "WP-003", "--attempt", "existing-owner", "--session-id", "existing-owner",
                "--expected-revision", "1", "--path", "tools/android-port/local/reserve.py",
                "--operation", "add-or-update-validator-rule",
            )
            self.assertEqual(result, 0, errors)
            self.assertEqual(evolved["revision"], 2)
            self.assertEqual(evolved["capabilities"]["traceability-validation"],
                             ["tools/android-port/local/reserve.py"])
            for key in ("binding", "authorization_context", "identity", "attempt", "branch", "worktree", "state", "repairs"):
                self.assertEqual(evolved[key], original[key])

    def test_real_cli_missing_or_malformed_trusted_authority_has_no_candidate_fallback(self):
        with authority_fixture() as (repo, ledger, git, cli):
            original = self.claim(cli)
            before = self.snapshot(ledger)
            base = git("rev-parse", "HEAD")
            for name, value in (
                ("missing-default", None), ("automation-policy.json", None),
                ("port-manifest.json", []), ("port-manifest.schema.json", None),
                ("not-ported.json", None),
            ):
                for tracked in ("automation-policy.json", "port-manifest.json",
                                "port-manifest.schema.json", "not-ported.json"):
                    (repo / "docs/android" / tracked).write_text(
                        git("show", base + ":docs/android/" + tracked) + "\n", encoding="utf-8",
                    )
                if name == "missing-default":
                    git("symbolic-ref", "--delete", "refs/remotes/origin/HEAD")
                else:
                    git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
                    (repo / "docs/android" / name).write_text(json.dumps(value), encoding="utf-8")
                    git("add", "docs")
                    git("commit", "--quiet", "-m", "malformed trusted metadata fixture")
                    git("update-ref", "refs/remotes/origin/main", "HEAD")
                    for tracked in ("automation-policy.json", "port-manifest.json",
                                    "port-manifest.schema.json", "not-ported.json"):
                        (repo / "docs/android" / tracked).write_text(
                            git("show", base + ":docs/android/" + tracked) + "\n", encoding="utf-8",
                        )
                    git("add", "docs")
                    git("commit", "--quiet", "-m", "valid candidate cannot replace missing trusted authority")
                for command, options in (
                    ("admit", []),
                    ("evolve", ["--attempt", "existing-owner", "--session-id", "existing-owner",
                                "--expected-revision", "1"]),
                ):
                    result, _, errors = cli(
                        command, "--wp", "WP-502", "--path", original["paths"][0], "--operation", "modify", *options,
                    )
                    with self.subTest(name=name, command=command):
                        self.assertEqual(result, 1, errors)
                        self.assertIn("BLOCKED:", errors)
                        self.assertNotIn("no trusted path+operation rule", errors)
                        self.assertEqual(self.snapshot(ledger), before)

    def test_real_cli_installed_namespace_or_metadata_disagreement_preserves_all_existing_bytes(self):
        with authority_fixture() as (repo, ledger, _, cli):
            original = self.claim(cli)
            before = self.snapshot(ledger)
            target = reserve.runtime_target(repo)
            target.mkdir()
            reserve.write_runtime(target, "foreign/installed")
            for malformed in (False, True):
                if malformed:
                    (target / "runtime-manifest.json").write_text("[]", encoding="utf-8")
                installed = {str(p.relative_to(target)): p.read_bytes()
                             for p in target.rglob("*") if p.is_file()}
                result, _, errors = cli(
                    "admit", "--wp", "WP-502", "--path", original["paths"][0], "--operation", "modify",
                )
                with self.subTest(malformed=malformed):
                    self.assertEqual(result, 1, errors)
                    self.assertIn("BLOCKED:", errors)
                    self.assertNotIn("no trusted path+operation rule", errors)
                    self.assertEqual(self.snapshot(ledger), before)
                    self.assertEqual({str(p.relative_to(target)): p.read_bytes()
                                      for p in target.rglob("*") if p.is_file()}, installed)
