import tempfile
import unittest
import json
import os
import subprocess
import sys
from pathlib import Path

import reserve
from controller.ledger import Ledger


class ReservationRuntimeTests(unittest.TestCase):
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
