"""AndroidOnly: WP-003 Debug packaging fixtures are not APK/device execution evidence."""

from contextlib import ExitStack
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from fixtures import BASE, NOW, base_manifest, policy
from controller.capabilities import CapabilityEngine
from controller.ci_environment import host_name
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

    def test_actual_apk_identity_accepts_exact_sdk37_v2_and_numbered_signer_formats(self):
        certificate = "000be798aca5806230ec26d9feee36cbd0acadc857cf22a4ba711f43a4a2c8b7"
        for label, value in (("V2 Signer:", certificate), ("Signer #1", certificate.upper())):
            output = (
                f"{label} certificate DN: CN=Android Debug, O=Android, C=US\n"
                f"{label} certificate SHA-256 digest: {value}\n"
                f"{label} certificate SHA-1 digest: {'a' * 40}\n"
                f"{label} certificate MD5 digest: {'b' * 32}\n"
            )
            with self.subTest(label=label):
                result, calls = self.apk_fixture(output)
                self.assertEqual(result["certificate_sha256"], certificate)
                self.assertEqual(result["package"], packaging.PACKAGES[0])
                for call in calls:
                    self.assertEqual(call.kwargs["encoding"], "utf-8")
                    self.assertEqual(call.kwargs["errors"], "strict")
                self.assertIn("-Dfile.encoding=UTF-8", calls[0].args[0])
                self.assertEqual(calls[0].args[0][-3:-1], ["verify", "--print-certs"])

    def test_actual_apk_identity_rejects_ambiguous_unknown_malformed_signers_and_packages(self):
        line = "V2 Signer: certificate SHA-256 digest: " + "a" * 64
        for output in (
            "", line + "\n" + line,
            line + "\nSigner #2 certificate SHA-256 digest: " + "b" * 64,
            line + "\nV3 Signer: certificate SHA-256 digest: " + "b" * 64,
            line.replace("V2 Signer:", "Unknown Signer:"),
            line[:-1], line + "0", line.replace("a" * 64, "g" * 64),
        ):
            with self.subTest(output=output), self.assertRaises(PortError):
                self.apk_fixture(output)
        for badging in ("", "package: name='one'\npackage: name='two'"):
            with self.subTest(badging=badging), self.assertRaises(PortError):
                self.apk_fixture(line, badging=badging)

    def test_actual_apk_identity_requires_successful_verification_and_strict_text_decoding(self):
        for error in (subprocess.CalledProcessError(1, ["apksigner"]),
                      UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid")):
            with self.subTest(error=type(error).__name__), self.assertRaisesRegex(PortError, "verification failed"):
                self.apk_fixture(error)

    def apk_fixture(self, output, *, badging=None):
        apk = self.root / "public-fixture.apk"
        apk.write_bytes(b"fixture, not a real APK")
        if badging is None:
            badging = f"package: name='{packaging.PACKAGES[0]}' versionCode='1'\n"
        with patch.object(packaging.subprocess, "check_output",
                          side_effect=output if isinstance(output, Exception) else [output, badging]) as run:
            result = packaging.apk_identity(apk, self.root / "sdk-tools", {"JAVA_HOME": str(self.root / "jdk")})
        self.assertEqual(result["sha256"], packaging.sha256(apk))
        return result, run.call_args_list

    def test_full_assembly_uses_two_gib_with_real_local_environment_and_preserves_native_lane(self):
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
        state = {
            "schema_version": 1, "host": host_name(), "java_home": str(self.root / "jdk"),
            "android_home": str(self.root / "sdk"), "private_root": str(self.root / "private"),
            "provenance": {"scope": "local fixture, not hosted/device evidence"},
        }
        inherited = {
            "PATH": "tools", "GRADLE_USER_HOME": str(self.root / "existing-gradle-cache"),
            "GRADLE_OPTS": "-Xmx99g -Duntrusted=fixture", "JAVA_OPTS": "-Duntrusted=fixture",
            "GH_TOKEN": "fixture-not-a-credential", "SIGNING_PASSWORD": "fixture-not-a-credential",
        }
        with ExitStack() as stack:
            stack.enter_context(patch.dict(os.environ, inherited, clear=True))
            stack.enter_context(patch.object(packaging, "source_identity", return_value="d" * 64))
            stack.enter_context(patch.object(packaging, "verify_wrapper"))
            readiness = stack.enter_context(patch.object(packaging, "preflight"))
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
                revision=BASE, state=state,
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
            self.assertIn("--dependency-verification", arguments)
            self.assertEqual(arguments[arguments.index("--dependency-verification") + 1], "strict")
            self.assertIn("--max-workers=1", arguments)
            self.assertNotIn("--no-build-cache", arguments)
            self.assertFalse(any("release" in item.lower() or "install" in item.lower() for item in command))
            environment = runner.call_args.kwargs["env"]
            self.assertEqual(environment["ANDROID_USER_HOME"], str(home))
            self.assertEqual(environment["GRADLE_USER_HOME"], inherited["GRADLE_USER_HOME"])
            self.assertEqual(environment["JAVA_HOME"], state["java_home"])
            self.assertIn("-Xmx2048m", environment["GRADLE_OPTS"])
            self.assertNotIn("-Xmx768m", environment["GRADLE_OPTS"])
            self.assertIn("-XX:MaxMetaspaceSize=512m", environment["GRADLE_OPTS"])
            for variable, cache in (("JAVA_OPTS", "32m"), ("GRADLE_OPTS", "96m")):
                for flag in ("-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2",
                             "-XX:TieredStopAtLevel=1", f"-XX:ReservedCodeCacheSize={cache}"):
                    self.assertIn(flag, environment[variable])
                self.assertNotIn("untrusted", environment[variable])
            self.assertNotIn("GH_TOKEN", environment)
            self.assertNotIn("SIGNING_PASSWORD", environment)
            allowed = json.loads((packaging.REPO / "android/scaffold/environment-allowlist.json").read_text())
            self.assertLessEqual({key.casefold() for key in environment},
                                 {key.casefold() for key in allowed["allowed_names"]})
            readiness.assert_called_once_with(state, output / "readiness", local=True)
            self.assertEqual(dict(os.environ), inherited)
            self.assertEqual([lock["resource"] for lock in self.ledger.hard_lock_records()], ["native-execution"])
