"""AndroidOnly: WP-003 Debug packaging fixtures are not APK/device execution evidence."""

from contextlib import ExitStack
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from fixtures import BASE, NOW, base_manifest, policy
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.ledger import Ledger
from local import debug_packaging as packaging


class DebugPackagingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.ledger = Ledger(self.root / "ledger.sqlite", policy()["repository"])

    def test_exact_capability_is_not_release_or_general_signing_permission(self):
        engine = CapabilityEngine(policy())
        admission = engine.admit("WP-503", [], packaging.ADAPTER, packaging.OPERATION)
        self.assertEqual(admission.capability_id, "debug-test-packaging")
        for wp, path, operation in (
            ("WP-316", packaging.ADAPTER, packaging.OPERATION),
            ("WP-503", "android/release/signing.gradle.kts", packaging.OPERATION),
            ("WP-503", packaging.ADAPTER, "resign-retained-apk"),
            ("WP-503", packaging.ADAPTER, "export-private-key"),
        ):
            with self.subTest(wp=wp, path=path, operation=operation), self.assertRaises(PortError):
                engine.admit(wp, [], path, operation)

    def test_native_owner_and_wp_and_exclusive_gradle_lane_are_required(self):
        with self.assertRaises(PortError):
            packaging.native_lane(self.ledger, "session")
        with self.ledger.hard_lock("native-execution", "session", {"wp": "WP-503"}, NOW):
            packaging.native_lane(self.ledger, "session")
            with self.assertRaises(PortError):
                packaging.native_lane(self.ledger, "foreign")
            with self.ledger.hard_lock("gradle-execution", "other", {}, NOW), self.assertRaises(PortError):
                packaging.native_lane(self.ledger, "session")
        with self.ledger.hard_lock("native-execution", "session", {"wp": "WP-316"}, NOW), self.assertRaises(PortError):
            packaging.native_lane(self.ledger, "session")

    def test_public_package_and_both_certificate_matches_are_mandatory(self):
        installed = {"package": packaging.PACKAGES[0], "certificate_sha256": "a" * 64}
        tests = {**installed, "package": packaging.PACKAGES[1]}
        packaging.verify_pair(installed, installed, tests)
        for index, field in ((1, "certificate_sha256"), (2, "certificate_sha256"), (2, "package")):
            values = copy.deepcopy([installed, installed, tests])
            values[index][field] = "mismatch"
            with self.subTest(index=index, field=field), self.assertRaises(PortError):
                packaging.verify_pair(*values)

    def test_packaging_fixed_tasks_preserves_native_lane_and_starts_no_tests_or_installs(self):
        self.exercise()

    def test_dirty_sources_and_unbound_head_fail_before_packaging(self):
        for values in ([BASE.encode(), b" M android/fixture.kt"], [b"0" * 40]):
            with patch.object(packaging, "git", side_effect=values), self.assertRaises(PortError):
                packaging.source_identity(self.root, BASE)

    def test_failed_build_and_certificate_mismatch_release_gradle_without_replacing_original(self):
        self.exercise(build_error=True)
        self.exercise(certificate_error=True)

    def exercise(self, *, build_error=False, certificate_error=False):
        home = self.root / "existing-debug-home"
        home.mkdir(exist_ok=True)
        (home / "debug.keystore").touch()
        output = self.root / ("failure-build" if build_error else "failure-cert" if certificate_error else "success")
        installed = {"package": packaging.PACKAGES[0], "certificate_sha256": "a" * 64, "sha256": "b" * 64}
        app = dict(installed)
        tests = {**installed, "package": packaging.PACKAGES[1]}
        if certificate_error:
            tests["certificate_sha256"] = "c" * 64
        with ExitStack() as stack:
            stack.enter_context(patch.object(packaging, "source_identity", return_value="d" * 64))
            stack.enter_context(patch.object(packaging, "verify_wrapper"))
            stack.enter_context(patch.object(packaging, "candidate_environment", return_value={"PATH": "tools"}))
            stack.enter_context(patch.object(packaging, "preflight"))
            stack.enter_context(patch("shutil.which", return_value="pwsh"))
            stack.enter_context(patch.object(packaging, "apk_identity", side_effect=[installed, app, tests, installed]))
            runner = stack.enter_context(patch.object(packaging.subprocess, "run"))
            if build_error:
                runner.side_effect = subprocess.CalledProcessError(1, ["fixture"])
            stack.enter_context(self.ledger.hard_lock("native-execution", "session", {"wp": "WP-503"}, NOW))
            arguments = dict(
                manifest=base_manifest(), policy=policy(), ledger=self.ledger, owner="session",
                revision=BASE, state={"android_home": str(self.root / "sdk")},
                debug_home=home, installed_apk=self.root / "preserved.apk", output=output,
            )
            if build_error or certificate_error:
                with self.assertRaises(PortError):
                    packaging.assemble_debug_pair(**arguments)
                self.assertFalse((output / "debug-packaging.json").exists())
            else:
                result = packaging.assemble_debug_pair(**arguments)
                self.assertFalse(result["installed"])
                self.assertFalse(result["acceptance"])
                self.assertEqual(result["tests_started"], 0)
            command = runner.call_args.args[0]
            invocation = output / "debug-invocation.json"
            arguments = json.loads(invocation.read_text())["arguments"] if invocation.exists() else command[1:]
            self.assertEqual(arguments[:2], list(packaging.TASKS))
            self.assertFalse(any("release" in item.lower() or "install" in item.lower() for item in command))
            self.assertEqual(runner.call_args.kwargs["env"]["ANDROID_USER_HOME"], str(home))
            self.assertEqual([lock["resource"] for lock in self.ledger.hard_lock_records()], ["native-execution"])
