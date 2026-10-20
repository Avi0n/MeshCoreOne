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
        self.manifest = base_manifest()
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
        for index, field in ((0, "package"), (0, "certificate_sha256"), (1, "certificate_sha256"),
                             (2, "certificate_sha256"), (1, "package"), (2, "package")):
            values = copy.deepcopy([installed, installed, tests])
            values[index][field] = "mismatch"
            with self.subTest(index=index, field=field), self.assertRaises(PortError):
                packaging.verify_pair(*values)

    def test_packaging_fixed_tasks_preserves_native_lane_and_starts_no_tests_or_installs(self):
        self.exercise()

    def test_originally_absent_packaging_has_no_fabricated_snapshot_or_preserved_apk_read(self):
        self.exercise(originally_absent=True)

    def test_originally_absent_still_requires_both_exact_packages_and_public_certificate_match(self):
        app = {"package": packaging.PACKAGES[0], "certificate_sha256": "a" * 64}
        tests = {**app, "package": packaging.PACKAGES[1]}
        packaging.verify_pair(None, app, tests)
        for target, field, value in (
            (0, "package", "unrelated.app"), (1, "package", "unrelated.test"),
            (0, "certificate_sha256", "b" * 64), (1, "certificate_sha256", "b" * 64),
        ):
            values = copy.deepcopy([app, tests])
            values[target][field] = value
            with self.subTest(target=target, field=field), self.assertRaises(PortError):
                packaging.verify_pair(None, *values)

    def test_originally_absent_failed_build_or_certificate_mismatch_never_emits_success(self):
        self.exercise(originally_absent=True, build_error=True)
        self.exercise(originally_absent=True, certificate_error=True)

    def test_preserved_snapshot_drift_still_fails_instead_of_falling_back_to_absent(self):
        self.exercise(snapshot_drift=True)

    def test_dirty_sources_and_unbound_head_fail_before_packaging(self):
        for values in ([BASE.encode(), b" M android/fixture.kt"], [b"0" * 40]):
            with patch.object(packaging, "git", side_effect=values), self.assertRaises(PortError):
                packaging.source_identity(self.root, BASE)

    def test_failed_build_and_certificate_mismatch_release_gradle_without_replacing_original(self):
        self.exercise(build_error=True)
        self.exercise(certificate_error=True)

    def exercise(self, *, build_error=False, certificate_error=False, originally_absent=False,
                 snapshot_drift=False):
        home = self.root / "existing-debug-home"
        home.mkdir(exist_ok=True)
        (home / "debug.keystore").touch()
        label = ("failure-build" if build_error else "failure-cert" if certificate_error
                 else "snapshot-drift" if snapshot_drift else "success")
        output = self.root / (("absent-" if originally_absent else "") + label)
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
            apk_reader = stack.enter_context(patch.object(
                packaging, "apk_identity",
                side_effect=[app, tests] if originally_absent else [
                    installed, app, tests, {**installed, "sha256": "c" * 64} if snapshot_drift else installed,
                ],
            ))
            runner = stack.enter_context(patch.object(packaging.subprocess, "run"))
            if build_error:
                runner.side_effect = subprocess.CalledProcessError(1, ["fixture"])
            stack.enter_context(self.ledger.hard_lock("native-execution", "session", {"wp": "WP-503"}, NOW))
            arguments = dict(
                manifest=self.manifest, policy=policy(), ledger=self.ledger, owner="session",
                revision=BASE, state={"android_home": str(self.root / "sdk")},
                debug_home=home, installed_apk=None if originally_absent else self.root / "preserved.apk", output=output,
            )
            if build_error or certificate_error or snapshot_drift:
                with self.assertRaises(PortError):
                    packaging.assemble_debug_pair(**arguments)
                self.assertFalse((output / "debug-packaging.json").exists())
            else:
                result = packaging.assemble_debug_pair(**arguments)
                self.assertFalse(result["installed"])
                self.assertFalse(result["acceptance"])
                self.assertEqual(result["tests_started"], 0)
                self.assertEqual(result["installed_snapshot"], None if originally_absent else installed)
                self.assertEqual(result["preservation_mode"],
                                 "originally-absent" if originally_absent else "originally-present")
                self.assertEqual(json.loads((output / "debug-packaging.json").read_text())["installed_snapshot"],
                                 result["installed_snapshot"])
                self.assertEqual(apk_reader.call_count, 2 if originally_absent else 4)
            if originally_absent:
                self.assertTrue(all(call.args[0] is not None for call in apk_reader.call_args_list))
                self.assertFalse(any(call.args[0] == self.root / "preserved.apk"
                                     for call in apk_reader.call_args_list))
            command = runner.call_args.args[0]
            invocation = output / "debug-invocation.json"
            arguments = json.loads(invocation.read_text())["arguments"] if invocation.exists() else command[1:]
            self.assertEqual(arguments[:2], list(packaging.TASKS))
            self.assertFalse(any("release" in item.lower() or "install" in item.lower() for item in command))
            self.assertEqual(runner.call_args.kwargs["env"]["ANDROID_USER_HOME"], str(home))
            self.assertEqual([lock["resource"] for lock in self.ledger.hard_lock_records()], ["native-execution"])
