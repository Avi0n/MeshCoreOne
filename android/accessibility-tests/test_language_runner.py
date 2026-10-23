"""AndroidOnly: WP-503 Synthetic process/lifecycle regressions, never device evidence."""

from contextlib import ExitStack
from dataclasses import replace
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import language_runner as runner
from controller.ci_environment import host_name
from test_language_binding import source, transcript
from test_shared_device_runner import FakeDevice, completed, record
from shared_device_runner import APP, TEST, SCHEMA, sha256


class SyntheticDevice(FakeDevice):
    def __init__(self, original, api):
        super().__init__()
        self.original = original
        if original is None:
            self.packages.pop(APP)
        else:
            self.packages[APP] = sha256(original)
        self.observed_profile = {"sdk": str(api), "synthetic": True}
        self.retained_registrations = set()
        self.install_failure = None

    def installed(self, package):
        actual = super().installed(package)
        return {**actual, "path": "/data/app/synthetic/base.apk"} if actual else None

    def install(self, path):
        self.calls.append(("install", path))
        self.packages[TEST if path.name == "current-tests.apk" else APP] = sha256(path)
        if self.install_failure is not None:
            failure, self.install_failure = self.install_failure, None
            raise failure

    def run(self, *command, **kwargs):
        if command[:4] == ("shell", "pm", "list", "packages"):
            self.calls.append(command)
            registered = set(self.packages)
            if command[4:6] == ("--user", "all"):
                registered.update(self.retained_registrations)
            return "\n".join("package:" + name for name in sorted(registered) if name.startswith(command[-1]))
        if command[0] == "pull":
            self.calls.append(command)
            shutil.copyfile(self.original, command[2])
            return "synthetic pull"
        if command[0] == "uninstall" and command[1] in (APP, TEST):
            self.calls.append(command)
            del self.packages[command[1]]
            return "Success"
        return super().run(*command, **kwargs)

    def profile(self):
        return self.observed_profile.copy()

    def capture_profile(self):
        return self.profile()

    def confirm_original_absence(self, package):
        runner.ProcessDevice.confirm_original_absence(self, package)

    def prepare_profile(self, original, record):
        if original != self.observed_profile:
            raise RuntimeError("Original synthetic profile changed")

    def restore_profile(self, original, record):
        if original != self.observed_profile:
            raise RuntimeError("Original synthetic profile was not restored")


class LanguageRunnerTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.root = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        sdk = self.root / "sdk"
        adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        adb.parent.mkdir(parents=True)
        adb.touch()
        debug = self.root / "debug"
        debug.mkdir()
        (debug / "debug.keystore").touch()
        state = self.root / "build-inputs.json"
        state.write_text(json.dumps({
            "schema_version": 1, "host": host_name(),
            "java_home": str(self.root / "java"), "android_home": str(sdk),
            "private_root": str(self.root / "private"), "provenance": {},
        }))
        self.config = runner.RunConfig(adb, "127.0.0.1", 5038, "emulator-5580",
                                       state, debug, self.root / "new-operation")
        self.request = runner.request_for(source(), 31)
        self.policy = {"paused": False, "repository": source().repository}
        self.ledger = Mock(repository=source().repository)
        self.ledger.hard_lock_records.return_value = []
        load = runner.load_json
        self.stack.enter_context(patch.object(runner, "load_json", side_effect=lambda path:
            self.policy if path == runner.ROOT / "docs/android/automation-policy.json" else load(path)))
        self.verify = self.stack.enter_context(patch.object(runner, "verify_current_request", return_value=self.policy))
        self.stack.enter_context(patch.object(runner, "load_manifest", return_value=Mock()))
        self.print = self.stack.enter_context(patch("builtins.print"))
        self.packaging = {}
        for label in ("original", "app", "tests"):
            path = self.root / (label + ".apk")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", label.encode() + SCHEMA)
            self.packaging[label] = path
        self.device = SyntheticDevice(self.packaging["original"], 31)
        self.stream, self.process = Mock(), Mock()
        self.stream.poll.return_value = None
        self.process.wait.return_value = 0
        self.commands = []

        def spawn(command, **kwargs):
            self.commands.append(command)
            if command[1] == "logcat":
                kwargs["stdout"].write(b"WP503_SYNTHETIC_ONLY\n")
                return self.stream
            self.assertTrue(self.device.application_state_uncertain)
            kwargs["stdout"].write(transcript(family=self.request.binding.family).encode())
            return self.process

        self.spawn = self.stack.enter_context(patch("shared_device_runner.subprocess.Popen", side_effect=spawn))

        def package(**kwargs):
            self.assertEqual(self.request.operation_owner, kwargs["owner"])
            self.assertEqual(self.request.binding.source_revision, kwargs["revision"])
            self.assertEqual(self.request.operation_owner, self.ledger.acquire_hard_lock.call_args.args[1])
            return {
                "owner": kwargs["owner"], "source_revision": kwargs["revision"],
                "installed_snapshot": {"sha256": sha256(self.packaging["original"]), "package": APP}
                if kwargs["installed_apk"] is not None else None,
                "android_source_tree_sha256": self.request.binding.android_source_tree_sha256,
                "manifest_sha256": self.request.binding.manifest_sha256,
                "policy_revision": self.request.binding.policy_revision,
                **{label: {"path": str(self.packaging[label]), "sha256": sha256(self.packaging[label])}
                   for label in ("app", "tests")},
            }

        self.package = self.stack.enter_context(patch.object(runner, "assemble_debug_pair", side_effect=package))

    def execute(self):
        return runner.execute_language(self.request, self.config, self.ledger, device=self.device)

    def assert_original_restored(self):
        self.assertEqual({APP: sha256(self.packaging["original"])}, self.device.packages)

    def test_real_device_rejects_every_family_before_readiness_packaging_or_mutation(self):
        for family in runner.FAMILIES:
            for api in runner.FAMILIES[family].apis:
                with self.subTest(family=family, api=api):
                    request = runner.request_for(source(family), api)
                    device = runner.ProcessDevice(self.config, api)
                    device.run = Mock()
                    device.prepare_profile = Mock()
                    device.install = Mock()
                    with patch.object(runner, "readiness") as readiness:
                        with self.assertRaisesRegex(
                                RuntimeError, "genuine pre-mutation.*supported snapshot-based recovery"):
                            runner.execute_language(request, self.config, self.ledger, device=device)
                    readiness.assert_not_called()
                    device.run.assert_not_called()
                    device.prepare_profile.assert_not_called()
                    device.install.assert_not_called()
                    self.assertFalse(self.config.output.exists())
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.ledger.release_hard_lock.assert_not_called()

    def test_positive_full_process_lifecycle_on_each_api_uses_only_the_two_real_methods(self):
        for api in (31, 37):
            self.request = runner.request_for(source(), api)
            self.device = SyntheticDevice(self.packaging["original"], api)
            self.config = replace(self.config, output=self.root / ("operation-" + str(api)))
            self.ledger.reset_mock()
            result = self.execute()
            self.assertEqual((2, 2, 2), tuple(result["cohort"][key] for key in ("discovered", "started", "passed")))
            self.assertFalse(result["acceptance"])
            self.assertEqual([APP, TEST], result["mutation_attempts"])
            command = self.commands[-1]
            self.assertEqual(",".join(self.request.identities), command[command.index("class") + 1])
            self.assertEqual(str(api), command[command.index("expectedApi") + 1])
            self.assertEqual(self.config.endpoint, self.ledger.acquire_hard_lock.call_args.args[2]["endpoint"])
            self.assert_original_restored()
            self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
            recovery = json.loads((self.config.output / f"device-language{api}-recovery.json").read_text())
            self.assertEqual(self.request.binding.source_revision, recovery["source_head"])
            self.assertEqual(self.request.operation_owner, recovery["native_lane_operation_owner"])
            self.assertEqual("VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION", recovery["status"])
            self.assertFalse(self.device.application_state_uncertain)

    def configure_diagnostics(self):
        self.request = runner.request_for(source("diagnostics"), 37)
        self.device = SyntheticDevice(self.packaging["original"], 37)
        self.config = replace(self.config, output=self.root / "diagnostics-operation")

    def test_diagnostics_reuses_real_lifecycle_with_only_original_five_and_no_historical_selection(self):
        self.configure_diagnostics()
        with patch("shared_device_runner.selected_methods", side_effect=AssertionError("Historical selector forbidden")):
            result = self.execute()
        self.assertEqual((5, 5, 5, 5), tuple(result["cohort"][key] for key in (
            "discovered", "started", "matching_outcomes", "passed")))
        self.assertEqual("diagnostics", result["family"])
        self.assertEqual("diagnostics", result["cohort"]["cohort"])
        self.assertEqual(",".join(self.request.identities), self.commands[-1][self.commands[-1].index("class") + 1])
        self.assertEqual("37", self.commands[-1][self.commands[-1].index("expectedApi") + 1])
        self.assertEqual("diagnostics-api37-5-original-methods",
                         self.ledger.acquire_hard_lock.call_args.args[2]["purpose"])
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
        receipt = json.loads((self.config.output / "device-diagnostics37-recovery.json").read_text())
        self.assertEqual(list(self.request.identities), receipt["original_identities"])
        self.assertEqual(self.request.operation_owner, receipt["native_lane_operation_owner"])
        self.assertEqual("VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION", receipt["status"])
        self.assertFalse(result["acceptance"])

    def test_diagnostics_stale_binding_and_pause_stop_before_synthetic_device_build_or_lane(self):
        self.configure_diagnostics()
        self.verify.side_effect = runner.PortError("diagnostics source/policy/reservation stale")
        with self.assertRaisesRegex(runner.PortError, "stale"):
            self.execute()
        self.verify.side_effect = None
        self.policy["paused"] = True
        with self.assertRaisesRegex(runner.PortError, "paused=true"):
            self.execute()
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_diagnostics_language_transcript_or_missing_identity_retains_lane_and_restores_packages(self):
        self.configure_diagnostics()
        spawn = self.spawn.side_effect

        def wrong(command, **kwargs):
            process = spawn(command, **kwargs)
            if command[1] != "logcat":
                kwargs["stdout"].seek(0)
                kwargs["stdout"].truncate()
                kwargs["stdout"].write(transcript().encode())
            return process

        self.spawn.side_effect = wrong
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()
        receipt = json.loads((self.config.output / "device-diagnostics37-recovery.json").read_text())
        self.assertEqual(list(self.request.identities), receipt["original_identities"])
        self.assertIn("UNCERTAIN", receipt["status"])

    def test_diagnostics_forced_process_cannot_clear_uncertainty_or_release_lane(self):
        self.configure_diagnostics()
        self.process.wait.side_effect = [subprocess.TimeoutExpired("synthetic diagnostics", 600), 0]
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.process.kill.assert_called_once()
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_diagnostics_cli_uses_same_process_configuration_and_rejects_wrong_api_before_readiness(self):
        with patch.object(runner, "current_binding", return_value=source("diagnostics")) as current, \
                patch.object(runner, "Ledger", return_value=self.ledger), \
                patch.object(runner, "shared_ledger", return_value=self.root / "synthetic-ledger"), \
                patch.object(runner, "readiness") as readiness:
            self.assertEqual(0, runner.main(["--head", source().source_revision, "--family", "diagnostics",
                                           "--api", "37", "--readiness"]))
            result = json.loads(self.print.call_args.args[0])
            self.assertEqual(0, result["native_starts"])
            self.assertEqual(list(runner.FAMILIES["diagnostics"].identities), result["identities"])
            self.assertFalse(result["acceptance"])
            readiness.assert_called_once()
            readiness.reset_mock()
            with patch("sys.stderr", io.StringIO()):
                self.assertEqual(2, runner.main(["--head", source().source_revision, "--family", "diagnostics",
                                               "--api", "31", "--readiness"]))
            readiness.assert_not_called()
        self.assertEqual(2, current.call_count)
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def configure_nodes(self, suffix="operation"):
        self.request = runner.request_for(source("nodes"), 37)
        self.device = SyntheticDevice(self.packaging["original"], 37)
        self.config = replace(self.config, output=self.root / ("nodes-" + suffix))

    def test_nodes_reuses_real_shared_selection_and_preservation_lifecycle_for_exact_original_seven(self):
        self.configure_nodes()
        with patch("shared_device_runner.selected_methods", side_effect=AssertionError("Historical selector forbidden")):
            result = self.execute()
        self.assertEqual((7, 7, 7, 7, 0, 0, 0), tuple(result["cohort"][key] for key in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("nodes", result["family"])
        self.assertEqual("nodes", result["cohort"]["cohort"])
        self.assertFalse(result["acceptance"])
        command = self.commands[-1]
        self.assertEqual(",".join(runner.FAMILIES["nodes"].identities), command[command.index("class") + 1])
        self.assertEqual("37", command[command.index("expectedApi") + 1])
        self.assertEqual("nodes-api37-7-original-methods",
                         self.ledger.acquire_hard_lock.call_args.args[2]["purpose"])
        self.assertTrue(self.request.operation_owner.startswith(self.request.binding.canonical_session + ":nodes:"))
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
        receipt = json.loads((self.config.output / "device-nodes37-recovery.json").read_text())
        self.assertEqual(list(self.request.identities), receipt["original_identities"])
        self.assertEqual(self.request.operation_owner, receipt["native_lane_operation_owner"])
        self.assertEqual(self.request.binding.source_revision, receipt["source_head"])
        self.assertEqual("VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION", receipt["status"])
        self.assertFalse(self.device.application_state_uncertain)

    def test_nodes_stale_source_policy_scope_or_pause_blocks_before_device_build_or_lane(self):
        self.configure_nodes()
        for changed in ("source", "policy", "scope"):
            self.verify.side_effect = runner.PortError("nodes " + changed + " stale")
            with self.subTest(changed=changed), self.assertRaisesRegex(runner.PortError, "stale"):
                self.execute()
        self.verify.side_effect = None
        self.policy["paused"] = True
        with self.assertRaisesRegex(runner.PortError, "paused=true"):
            self.execute()
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_nodes_negative_wrong_missing_duplicate_and_count_outcomes_retain_lane_after_package_cleanup(self):
        positive = transcript(family="nodes")
        identities = runner.FAMILIES["nodes"].identities
        missing = completed("".join(record(identity, 1, 7) + record(identity, 0, 7)
                                    for identity in identities[:-1]), 7)
        values = (
            transcript(), transcript(family="diagnostics"), missing,
            positive.replace(identities[0].partition("#")[2], identities[1].partition("#")[2]),
            positive.replace(identities[0].partition("#")[2], "wrongIdentity"),
            positive.replace("numtests=7", "numtests=6"),
            transcript(-2, family="nodes"), transcript(-3, family="nodes"),
        )
        spawn = self.spawn.side_effect
        for index, value in enumerate(values):
            self.configure_nodes("negative-" + str(index))
            self.ledger.reset_mock()

            def wrong(command, **kwargs):
                process = spawn(command, **kwargs)
                if command[1] != "logcat":
                    kwargs["stdout"].seek(0)
                    kwargs["stdout"].truncate()
                    kwargs["stdout"].write(value.encode())
                return process

            self.spawn.side_effect = wrong
            with self.subTest(index=index), self.assertRaises(BaseExceptionGroup):
                self.execute()
            self.assert_original_restored()
            self.assertTrue(self.device.application_state_uncertain)
            self.ledger.release_hard_lock.assert_not_called()
            receipt = json.loads((self.config.output / "device-nodes37-recovery.json").read_text())
            self.assertEqual(list(self.request.identities), receipt["original_identities"])
            self.assertIn("UNCERTAIN", receipt["status"])

    def test_nodes_forced_termination_cannot_clear_uncertainty_or_release_after_positive_transcript(self):
        self.configure_nodes()
        self.process.wait.side_effect = [subprocess.TimeoutExpired("synthetic nodes", 600), 0]
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.process.kill.assert_called_once()
        self.assertEqual([600, 15], [call.kwargs["timeout"] for call in self.process.wait.call_args_list])
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_nodes_effective_profile_uncertainty_retains_lane_even_with_positive_seven_and_equal_raw_profile(self):
        self.configure_nodes()

        def unresolved(original, record):
            self.assertEqual(original, self.device.capture_profile())
            self.device.profile_restoration_uncertain = True

        self.device.restore_profile = unresolved
        with self.assertRaises(BaseExceptionGroup) as caught:
            self.execute()
        self.assertIn("Original effective runtime profile unresolved", repr(caught.exception))
        self.assert_original_restored()
        self.assertFalse(self.device.application_state_uncertain)
        self.assertTrue(self.device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_nodes_cli_models_both_apis_readiness_without_build_or_spawn(self):
        with patch.object(runner, "current_binding", return_value=source("nodes")) as current, \
                patch.object(runner, "Ledger", return_value=self.ledger), \
                patch.object(runner, "shared_ledger", return_value=self.root / "synthetic-ledger"), \
                patch.object(runner, "readiness") as readiness:
            for api in (31, 37):
                with self.subTest(api=api):
                    self.assertEqual(0, runner.main(["--head", source().source_revision, "--family", "nodes",
                                                   "--api", str(api), "--readiness"]))
                    result = json.loads(self.print.call_args.args[0])
                    self.assertEqual(list(runner.FAMILIES["nodes"].identities), result["identities"])
                    self.assertEqual(0, result["native_starts"])
                    self.assertFalse(result["acceptance"])
                    readiness.assert_called_once()
                    self.assertEqual(api, readiness.call_args.args[0].api)
                    readiness.reset_mock()
        current.assert_called_with(source().source_revision, family="nodes")
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def configure_comparator(self, suffix="operation"):
        self.request = runner.request_for(source("comparator"), 37)
        self.device = SyntheticDevice(self.packaging["original"], 37)
        self.config = replace(self.config, output=self.root / ("comparator-" + suffix))

    def test_comparator_uses_same_shared_selection_preservation_and_positive_eleven_not_locale_pairs(self):
        self.configure_comparator()
        with patch("shared_device_runner.selected_methods", side_effect=AssertionError("Historical selector forbidden")):
            result = self.execute()
        self.assertEqual((11, 11, 11, 11, 0, 0, 0), tuple(result["cohort"][key] for key in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("comparator", result["family"])
        self.assertEqual("comparator", result["cohort"]["cohort"])
        self.assertFalse(result["acceptance"])
        command = self.commands[-1]
        self.assertEqual(",".join(runner.FAMILIES["comparator"].identities), command[command.index("class") + 1])
        self.assertEqual("37", command[command.index("expectedApi") + 1])
        self.assertNotIn("NativeLocalizedCompareLocalePreservationTest", str(command))
        self.assertEqual("comparator-api37-11-original-methods",
                         self.ledger.acquire_hard_lock.call_args.args[2]["purpose"])
        self.assertTrue(self.request.operation_owner.startswith(
            self.request.binding.canonical_session + ":comparator:"))
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
        receipt = json.loads((self.config.output / "device-comparator37-recovery.json").read_text())
        self.assertEqual(list(self.request.identities), receipt["original_identities"])
        self.assertEqual(self.request.operation_owner, receipt["native_lane_operation_owner"])
        self.assertEqual("VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION", receipt["status"])
        self.assertFalse(self.device.application_state_uncertain)

    def test_comparator_stale_source_policy_scope_or_pause_stops_before_any_synthetic_device_build_or_lane(self):
        self.configure_comparator()
        for changed in ("source", "policy", "scope"):
            self.verify.side_effect = runner.PortError("comparator " + changed + " stale")
            with self.subTest(changed=changed), self.assertRaisesRegex(runner.PortError, "stale"):
                self.execute()
        self.verify.side_effect = None
        self.policy["paused"] = True
        with self.assertRaisesRegex(runner.PortError, "paused=true"):
            self.execute()
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_comparator_wrong_missing_duplicate_failed_skipped_or_count_outcomes_restore_packages_but_retain_lane(self):
        positive = transcript(family="comparator")
        identities = runner.FAMILIES["comparator"].identities
        missing = completed("".join(record(identity, 1, 11) + record(identity, 0, 11)
                                    for identity in identities[:-1]), 11)
        values = (
            transcript(), transcript(family="diagnostics"), transcript(family="nodes"), missing,
            positive.replace("NativeLocalizedCompareParityTest", "NativeLocalizedCompareLocalePreservationTest"),
            positive.replace(identities[0].partition("#")[2], identities[1].partition("#")[2]),
            positive.replace("numtests=11", "numtests=13"),
            transcript(-2, family="comparator"), transcript(-3, family="comparator"),
        )
        spawn = self.spawn.side_effect
        for index, value in enumerate(values):
            self.configure_comparator("negative-" + str(index))
            self.ledger.reset_mock()

            def wrong(command, **kwargs):
                process = spawn(command, **kwargs)
                if command[1] != "logcat":
                    kwargs["stdout"].seek(0)
                    kwargs["stdout"].truncate()
                    kwargs["stdout"].write(value.encode())
                return process

            self.spawn.side_effect = wrong
            with self.subTest(index=index), self.assertRaises(BaseExceptionGroup):
                self.execute()
            self.assert_original_restored()
            self.assertTrue(self.device.application_state_uncertain)
            self.ledger.release_hard_lock.assert_not_called()
            receipt = json.loads((self.config.output / "device-comparator37-recovery.json").read_text())
            self.assertEqual(list(self.request.identities), receipt["original_identities"])
            self.assertIn("UNCERTAIN", receipt["status"])

    def test_comparator_forced_termination_retains_lane_even_with_positive_eleven_transcript(self):
        self.configure_comparator()
        self.process.wait.side_effect = [subprocess.TimeoutExpired("synthetic comparator", 600), 0]
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.process.kill.assert_called_once()
        self.assertEqual([600, 15], [call.kwargs["timeout"] for call in self.process.wait.call_args_list])
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_comparator_effective_profile_uncertainty_retains_lane_after_positive_eleven_and_raw_equality(self):
        self.configure_comparator()

        def unresolved(original, record):
            self.assertEqual(original, self.device.capture_profile())
            self.device.profile_restoration_uncertain = True

        self.device.restore_profile = unresolved
        with self.assertRaises(BaseExceptionGroup) as caught:
            self.execute()
        self.assertIn("Original effective runtime profile unresolved", repr(caught.exception))
        self.assert_original_restored()
        self.assertFalse(self.device.application_state_uncertain)
        self.assertTrue(self.device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_comparator_cli_readiness_reuses_configuration_but_rejects_api31_before_readiness(self):
        with patch.object(runner, "current_binding", return_value=source("comparator")) as current, \
                patch.object(runner, "Ledger", return_value=self.ledger), \
                patch.object(runner, "shared_ledger", return_value=self.root / "synthetic-ledger"), \
                patch.object(runner, "readiness") as readiness:
            self.assertEqual(0, runner.main(["--head", source().source_revision, "--family", "comparator",
                                           "--api", "37", "--readiness"]))
            result = json.loads(self.print.call_args.args[0])
            self.assertEqual(list(runner.FAMILIES["comparator"].identities), result["identities"])
            self.assertEqual(0, result["native_starts"])
            self.assertFalse(result["acceptance"])
            readiness.assert_called_once()
            readiness.reset_mock()
            with patch("sys.stderr", io.StringIO()):
                self.assertEqual(2, runner.main(["--head", source().source_revision, "--family", "comparator",
                                               "--api", "31", "--readiness"]))
            readiness.assert_not_called()
        current.assert_called_with(source().source_revision, family="comparator")
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def configure_traz(self, suffix="operation", *, present=True):
        self.request = runner.request_for(source("traz"), 37)
        self.device = SyntheticDevice(self.packaging["original"] if present else None, 37)
        self.config = replace(self.config, output=self.root / ("traz-" + suffix))

    def test_traz_same_shared_lifecycle_selects_both_original_methods_and_preserves_present_or_absent_packages(self):
        for present in (True, False):
            self.configure_traz(str(present), present=present)
            self.ledger.reset_mock()
            with self.subTest(present=present), \
                    patch("shared_device_runner.selected_methods", side_effect=AssertionError("Historical selector forbidden")):
                result = self.execute()
            self.assertEqual((2, 2, 2, 2, 0, 0, 0), tuple(result["cohort"][key] for key in (
                "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
            self.assertEqual("traz", result["family"])
            self.assertEqual("traz", result["cohort"]["cohort"])
            self.assertFalse(result["acceptance"])
            command = self.commands[-1]
            self.assertEqual(",".join(runner.FAMILIES["traz"].identities), command[command.index("class") + 1])
            self.assertEqual("37", command[command.index("expectedApi") + 1])
            self.assertEqual("traz-api37-2-original-methods",
                             self.ledger.acquire_hard_lock.call_args.args[2]["purpose"])
            self.assertTrue(self.request.operation_owner.startswith(self.request.binding.canonical_session + ":traz:"))
            self.assertEqual({APP: sha256(self.packaging["original"])} if present else {}, self.device.packages)
            self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
            receipt = json.loads((self.config.output / "device-traz37-recovery.json").read_text())
            self.assertEqual(list(self.request.identities), receipt["original_identities"])
            self.assertEqual(self.request.operation_owner, receipt["native_lane_operation_owner"])
            self.assertEqual("VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION", receipt["status"])
            self.assertFalse(self.device.application_state_uncertain)

    def test_traz_stale_source_policy_scope_or_pause_blocks_before_device_build_or_lane(self):
        self.configure_traz()
        for changed in ("source", "policy", "scope"):
            self.verify.side_effect = runner.PortError("traz " + changed + " stale")
            with self.subTest(changed=changed), self.assertRaisesRegex(runner.PortError, "stale"):
                self.execute()
        self.verify.side_effect = None
        self.policy["paused"] = True
        with self.assertRaisesRegex(runner.PortError, "paused=true"):
            self.execute()
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_traz_wrong_family_missing_duplicate_failed_skip_or_count_outcomes_keep_lane_after_package_cleanup(self):
        positive = transcript(family="traz")
        identities = runner.FAMILIES["traz"].identities
        missing = completed(record(identities[0], 1, 2) + record(identities[0], 0, 2), 2)
        values = (
            transcript(), transcript(family="comparator"), transcript(family="diagnostics"),
            transcript(family="nodes"), missing,
            positive.replace(identities[0].partition("#")[2], identities[1].partition("#")[2]),
            positive.replace("NativeLocalizedCompareLocalePreservationTest", "NativeLocalizedCompareParityTest"),
            positive.replace("numtests=2", "numtests=13"),
            transcript(-2, family="traz"), transcript(-3, family="traz"),
        )
        spawn = self.spawn.side_effect
        for index, value in enumerate(values):
            self.configure_traz("negative-" + str(index))
            self.ledger.reset_mock()

            def wrong(command, **kwargs):
                process = spawn(command, **kwargs)
                if command[1] != "logcat":
                    kwargs["stdout"].seek(0)
                    kwargs["stdout"].truncate()
                    kwargs["stdout"].write(value.encode())
                return process

            self.spawn.side_effect = wrong
            with self.subTest(index=index), self.assertRaises(BaseExceptionGroup):
                self.execute()
            self.assert_original_restored()
            self.assertTrue(self.device.application_state_uncertain)
            self.ledger.release_hard_lock.assert_not_called()
            receipt = json.loads((self.config.output / "device-traz37-recovery.json").read_text())
            self.assertEqual(list(self.request.identities), receipt["original_identities"])
            self.assertIn("UNCERTAIN", receipt["status"])

    def test_traz_positive_transcript_cannot_release_lane_after_forced_termination(self):
        self.configure_traz()
        self.process.wait.side_effect = [subprocess.TimeoutExpired("synthetic supplemental", 600), 0]
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.process.kill.assert_called_once()
        self.assertEqual([600, 15], [call.kwargs["timeout"] for call in self.process.wait.call_args_list])
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_traz_retains_unknown_effective_profile_even_after_positive_two_and_raw_profile_equality(self):
        self.configure_traz()

        def unresolved(original, record):
            self.assertEqual(original, self.device.capture_profile())
            self.device.profile_restoration_uncertain = True

        self.device.restore_profile = unresolved
        with self.assertRaises(BaseExceptionGroup) as caught:
            self.execute()
        self.assertIn("Original effective runtime profile unresolved", repr(caught.exception))
        self.assert_original_restored()
        self.assertFalse(self.device.application_state_uncertain)
        self.assertTrue(self.device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_traz_cli_uses_shared_readiness_and_rejects_api31_before_readiness_without_locale_option(self):
        with patch.object(runner, "current_binding", return_value=source("traz")) as current, \
                patch.object(runner, "Ledger", return_value=self.ledger), \
                patch.object(runner, "shared_ledger", return_value=self.root / "synthetic-ledger"), \
                patch.object(runner, "readiness") as readiness:
            self.assertEqual(0, runner.main(["--head", source().source_revision, "--family", "traz",
                                           "--api", "37", "--readiness"]))
            result = json.loads(self.print.call_args.args[0])
            self.assertEqual(list(runner.FAMILIES["traz"].identities), result["identities"])
            self.assertEqual(0, result["native_starts"])
            self.assertFalse(result["acceptance"])
            readiness.assert_called_once()
            readiness.reset_mock()
            with patch("sys.stderr", io.StringIO()):
                self.assertEqual(2, runner.main(["--head", source().source_revision, "--family", "traz",
                                               "--api", "31", "--readiness"]))
            readiness.assert_not_called()
        current.assert_called_with(source().source_revision, family="traz")
        with patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as caught:
            runner.main(["--head", source().source_revision, "--family", "traz",
                         "--api", "37", "--readiness", "--locale", "en-US"])
        self.assertEqual(2, caught.exception.code)
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def configure_los(self, suffix="operation"):
        self.request = runner.request_for(source("los"), 37)
        self.device = SyntheticDevice(self.packaging["original"], 37)
        self.config = replace(self.config, output=self.root / ("los-" + suffix))

    def test_los_shared_lifecycle_selects_only_original_saved_endpoint_method_and_restores_packages(self):
        self.configure_los()
        with patch("shared_device_runner.selected_methods", side_effect=AssertionError("Historical selector forbidden")):
            result = self.execute()
        self.assertEqual((1, 1, 1, 1, 0, 0, 0), tuple(result["cohort"][key] for key in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("los", result["family"])
        self.assertEqual("los", result["cohort"]["cohort"])
        self.assertFalse(result["acceptance"])
        command = self.commands[-1]
        self.assertEqual(self.request.identities[0], command[command.index("class") + 1])
        self.assertNotIn(runner.FAMILIES["los"].fixture_methods[0], str(command))
        self.assertEqual("37", command[command.index("expectedApi") + 1])
        self.assertEqual("los-api37-1-original-methods", self.ledger.acquire_hard_lock.call_args.args[2]["purpose"])
        self.assertTrue(self.request.operation_owner.startswith(self.request.binding.canonical_session + ":los:"))
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
        receipt = json.loads((self.config.output / "device-los37-recovery.json").read_text())
        self.assertEqual(list(self.request.identities), receipt["original_identities"])
        self.assertEqual(self.request.operation_owner, receipt["native_lane_operation_owner"])
        self.assertEqual("VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION", receipt["status"])
        self.assertFalse(self.device.application_state_uncertain)

    def test_los_stale_source_policy_scope_or_pause_blocks_before_any_device_build_or_lane(self):
        self.configure_los()
        for changed in ("source", "policy", "scope"):
            self.verify.side_effect = runner.PortError("los " + changed + " stale")
            with self.subTest(changed=changed), self.assertRaisesRegex(runner.PortError, "stale"):
                self.execute()
        self.verify.side_effect = None
        self.policy["paused"] = True
        with self.assertRaisesRegex(runner.PortError, "paused=true"):
            self.execute()
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_los_control_or_whole_class_wrong_missing_duplicate_failed_and_skip_outcomes_retain_lane(self):
        self.configure_los()
        positive = transcript(family="los")
        identity = self.request.identities[0]
        spec = runner.FAMILIES["los"]
        both = tuple(spec.klass + "#" + method for method in spec.fixture_methods)
        whole_class = completed("".join(record(item, 1, 2) + record(item, 0, 2) for item in both), 2)
        values = (
            transcript(), whole_class, positive.replace(spec.methods[0], spec.fixture_methods[0]),
            completed(record(identity, 1, 1), 1),
            completed(record(identity, 1, 1) + record(identity, 0, 1) * 2, 1),
            positive.replace(spec.methods[0], "genericLosProxy"),
            transcript(-2, family="los"), transcript(-3, family="los"),
        )
        spawn = self.spawn.side_effect
        for index, value in enumerate(values):
            self.configure_los("negative-" + str(index))
            self.ledger.reset_mock()

            def wrong(command, **kwargs):
                process = spawn(command, **kwargs)
                if command[1] != "logcat":
                    kwargs["stdout"].seek(0)
                    kwargs["stdout"].truncate()
                    kwargs["stdout"].write(value.encode())
                return process

            self.spawn.side_effect = wrong
            with self.subTest(index=index), self.assertRaises(BaseExceptionGroup):
                self.execute()
            self.assert_original_restored()
            self.assertTrue(self.device.application_state_uncertain)
            self.ledger.release_hard_lock.assert_not_called()
            receipt = json.loads((self.config.output / "device-los37-recovery.json").read_text())
            self.assertEqual(list(self.request.identities), receipt["original_identities"])
            self.assertIn("UNCERTAIN", receipt["status"])

    def test_los_forced_termination_retains_lane_despite_positive_one_transcript_and_restored_packages(self):
        self.configure_los()
        self.process.wait.side_effect = [subprocess.TimeoutExpired("synthetic los", 600), 0]
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.process.kill.assert_called_once()
        self.assertEqual([600, 15], [call.kwargs["timeout"] for call in self.process.wait.call_args_list])
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_los_unknown_effective_profile_retains_lane_even_after_positive_one_and_equal_raw_profile(self):
        self.configure_los()

        def unresolved(original, record):
            self.assertEqual(original, self.device.capture_profile())
            self.device.profile_restoration_uncertain = True

        self.device.restore_profile = unresolved
        with self.assertRaises(BaseExceptionGroup) as caught:
            self.execute()
        self.assertIn("Original effective runtime profile unresolved", repr(caught.exception))
        self.assert_original_restored()
        self.assertFalse(self.device.application_state_uncertain)
        self.assertTrue(self.device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_los_cli_reuses_readiness_but_blocks_api31_before_configuration_or_device_work(self):
        with patch.object(runner, "current_binding", return_value=source("los")) as current, \
                patch.object(runner, "Ledger", return_value=self.ledger), \
                patch.object(runner, "shared_ledger", return_value=self.root / "synthetic-ledger"), \
                patch.object(runner, "readiness") as readiness:
            self.assertEqual(0, runner.main(["--head", source().source_revision, "--family", "los",
                                           "--api", "37", "--readiness"]))
            result = json.loads(self.print.call_args.args[0])
            self.assertEqual(list(runner.FAMILIES["los"].identities), result["identities"])
            self.assertEqual(0, result["native_starts"])
            self.assertFalse(result["acceptance"])
            readiness.assert_called_once()
            readiness.reset_mock()
            with patch("sys.stderr", io.StringIO()):
                self.assertEqual(2, runner.main(["--head", source().source_revision, "--family", "los",
                                               "--api", "31", "--readiness"]))
            readiness.assert_not_called()
        current.assert_called_with(source().source_revision, family="los")
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def configure_cached_chat(self, suffix="operation", *, present=True):
        self.request = runner.request_for(source("cached-chat"), 37)
        self.device = SyntheticDevice(self.packaging["original"] if present else None, 37)
        self.config = replace(self.config, output=self.root / ("cached-chat-" + suffix))

    def test_cached_chat_same_shared_selection_runs_only_original_one_and_restores_present_or_absent_packages(self):
        spec = runner.FAMILIES["cached-chat"]
        for present in (True, False):
            self.configure_cached_chat(str(present), present=present)
            self.ledger.reset_mock()
            with self.subTest(present=present), \
                    patch("shared_device_runner.selected_methods", side_effect=AssertionError("Historical selector forbidden")):
                result = self.execute()
            self.assertEqual((1, 1, 1, 1, 0, 0, 0), tuple(result["cohort"][key] for key in (
                "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
            self.assertEqual("cached-chat", result["family"])
            self.assertEqual("cached-chat", result["cohort"]["cohort"])
            self.assertFalse(result["acceptance"])
            command = self.commands[-1]
            self.assertEqual(self.request.identities[0], command[command.index("class") + 1])
            for method in spec.fixture_methods:
                if method not in spec.methods:
                    self.assertNotIn(spec.klass + "#" + method, command)
            self.assertEqual("37", command[command.index("expectedApi") + 1])
            self.assertEqual("cached-chat-api37-1-original-methods",
                             self.ledger.acquire_hard_lock.call_args.args[2]["purpose"])
            self.assertTrue(self.request.operation_owner.startswith(self.request.binding.canonical_session + ":cached-chat:"))
            self.assertEqual({APP: sha256(self.packaging["original"])} if present else {}, self.device.packages)
            self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
            receipt = json.loads((self.config.output / "device-cached-chat37-recovery.json").read_text())
            self.assertEqual(list(self.request.identities), receipt["original_identities"])
            self.assertEqual(self.request.operation_owner, receipt["native_lane_operation_owner"])
            self.assertEqual("VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION", receipt["status"])
            self.assertFalse(self.device.application_state_uncertain)

    def test_cached_chat_stale_source_policy_scope_or_pause_blocks_before_device_build_and_lane(self):
        self.configure_cached_chat()
        for changed in ("source", "policy", "scope"):
            self.verify.side_effect = runner.PortError("cached-chat " + changed + " stale")
            with self.subTest(changed=changed), self.assertRaisesRegex(runner.PortError, "stale"):
                self.execute()
        self.verify.side_effect = None
        self.policy["paused"] = True
        with self.assertRaisesRegex(runner.PortError, "paused=true"):
            self.execute()
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_cached_chat_ime_controls_whole_class_missing_duplicate_or_bad_outcomes_keep_uncertainty(self):
        positive = transcript(family="cached-chat")
        spec = runner.FAMILIES["cached-chat"]
        identity = spec.identities[0]
        all_identities = tuple(spec.klass + "#" + method for method in spec.fixture_methods)
        whole_class = completed("".join(record(item, 1, 10) + record(item, 0, 10) for item in all_identities), 10)
        values = (
            transcript(family="los"), whole_class,
            completed(record(identity, 1, 1), 1),
            completed(record(identity, 1, 1) + record(identity, 0, 1) * 2, 1),
            positive.replace("numtests=1", "numtests=10"),
            positive.replace("INSTRUMENTATION_CODE: -1", ""),
            transcript(-1, family="cached-chat"), transcript(-2, family="cached-chat"),
            transcript(-3, family="cached-chat"), transcript(-4, family="cached-chat"),
            *(positive.replace(spec.methods[0], method) for method in spec.fixture_methods if method not in spec.methods),
        )
        spawn = self.spawn.side_effect
        for index, value in enumerate(values):
            self.configure_cached_chat("negative-" + str(index))
            self.ledger.reset_mock()

            def wrong(command, **kwargs):
                process = spawn(command, **kwargs)
                if command[1] != "logcat":
                    kwargs["stdout"].seek(0)
                    kwargs["stdout"].truncate()
                    kwargs["stdout"].write(value.encode())
                return process

            self.spawn.side_effect = wrong
            with self.subTest(index=index), self.assertRaises(BaseExceptionGroup):
                self.execute()
            self.assert_original_restored()
            self.assertTrue(self.device.application_state_uncertain)
            self.ledger.release_hard_lock.assert_not_called()
            receipt = json.loads((self.config.output / "device-cached-chat37-recovery.json").read_text())
            self.assertEqual(list(self.request.identities), receipt["original_identities"])
            self.assertIn("UNCERTAIN", receipt["status"])

    def test_cached_chat_forced_termination_keeps_lane_even_after_positive_one_and_package_restoration(self):
        self.configure_cached_chat()
        self.process.wait.side_effect = [subprocess.TimeoutExpired("synthetic cached chat", 600), 0]
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.process.kill.assert_called_once()
        self.assertEqual([600, 15], [call.kwargs["timeout"] for call in self.process.wait.call_args_list])
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_cached_chat_unknown_effective_profile_keeps_lane_after_positive_one_and_raw_profile_equality(self):
        self.configure_cached_chat()

        def unresolved(original, record):
            self.assertEqual(original, self.device.capture_profile())
            self.device.profile_restoration_uncertain = True

        self.device.restore_profile = unresolved
        with self.assertRaises(BaseExceptionGroup) as caught:
            self.execute()
        self.assertIn("Original effective runtime profile unresolved", repr(caught.exception))
        self.assert_original_restored()
        self.assertFalse(self.device.application_state_uncertain)
        self.assertTrue(self.device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_cached_chat_cli_uses_shared_mocked_readiness_and_blocks_api31_before_configuration(self):
        with patch.object(runner, "current_binding", return_value=source("cached-chat")) as current, \
                patch.object(runner, "Ledger", return_value=self.ledger), \
                patch.object(runner, "shared_ledger", return_value=self.root / "synthetic-ledger"), \
                patch.object(runner, "readiness") as readiness:
            self.assertEqual(0, runner.main(["--head", source().source_revision, "--family", "cached-chat",
                                           "--api", "37", "--readiness"]))
            result = json.loads(self.print.call_args.args[0])
            self.assertEqual(list(runner.FAMILIES["cached-chat"].identities), result["identities"])
            self.assertEqual(0, result["native_starts"])
            self.assertFalse(result["acceptance"])
            readiness.assert_called_once()
            readiness.reset_mock()
            with patch("sys.stderr", io.StringIO()):
                self.assertEqual(2, runner.main(["--head", source().source_revision, "--family", "cached-chat",
                                               "--api", "31", "--readiness"]))
            readiness.assert_not_called()
        current.assert_called_with(source().source_revision, family="cached-chat")
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def test_real_pause_fails_before_config_device_lane_or_build(self):
        self.policy["paused"] = True
        with patch.object(runner.RunConfig, "validate") as validate, self.assertRaisesRegex(runner.PortError, "paused=true"):
            self.execute()
        validate.assert_not_called()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def test_stale_binding_and_busy_actual_lanes_fail_before_device_work(self):
        self.verify.side_effect = runner.PortError("source/owner changed")
        with self.assertRaisesRegex(runner.PortError, "source/owner changed"):
            self.execute()
        self.verify.side_effect = None
        for resource in ("native-execution", "gradle-execution"):
            self.ledger.hard_lock_records.return_value = [{"resource": resource, "owner": "retained-other-owner"}]
            with self.subTest(resource=resource), self.assertRaisesRegex(runner.PortError, "held by retained-other-owner"):
                self.execute()
        self.ledger.acquire_hard_lock.assert_not_called()
        self.ledger.release_hard_lock.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_configuration_is_explicit_local_and_non_overwriting(self):
        changes = (
            {"adb": None}, {"adb": self.root / "other.exe"}, {"host": "192.0.2.1"},
            {"host": "not-an-address"}, {"port": 0}, {"serial": "physical-device"},
            {"output": self.root}, {"output": runner.ROOT / "inside-checkout"},
            {"debug_home": self.root / "missing"}, {"state": self.root / "missing.json"},
        )
        for change in changes:
            with self.subTest(change=change), self.assertRaises((runner.PortError, OSError)):
                replace(self.config, **change).validate()
        self.spawn.assert_not_called()

    def test_readiness_checks_configuration_without_device_probe_lane_or_build(self):
        runner.readiness(self.request, self.config, self.ledger)
        self.assertFalse(self.config.output.exists())
        self.assertEqual([], self.device.calls)
        self.ledger.acquire_hard_lock.assert_not_called()
        self.package.assert_not_called()
        self.spawn.assert_not_called()

    def test_preexisting_test_is_not_overwritten_for_present_or_absent_original(self):
        for packages in ({TEST: "foreign-test"}, {APP: sha256(self.packaging["original"]), TEST: "foreign-test"}):
            self.device.packages = packages.copy()
            with self.subTest(packages=packages), self.assertRaisesRegex(RuntimeError, "preserved|overwritten"):
                self.execute()
            self.assertEqual(packages, self.device.packages)
        self.package.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_failed_package_probe_is_not_reinterpreted_as_original_absence(self):
        self.device.installed = Mock(side_effect=subprocess.CalledProcessError(1, ["synthetic-adb"], stderr=b"unknown transport"))
        with self.assertRaises(subprocess.CalledProcessError):
            self.execute()
        self.package.assert_not_called()
        self.spawn.assert_not_called()
        self.assertFalse(any(call[0] in ("install", "uninstall") for call in self.device.calls))

    def test_absent_for_owner_but_registered_or_retained_elsewhere_is_not_empty(self):
        device = runner.ProcessDevice(self.config, 31)
        device.run = Mock(return_value="")
        device.confirm_original_absence(APP)
        for value in ("package:" + APP, "unknown package-manager response"):
            device.run.return_value = value
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                device.confirm_original_absence(APP)
        for package in (APP, TEST):
            device.run = Mock(side_effect=lambda *command:
                "package:" + package if command[4:6] == ("--user", "all") else "")
            with self.subTest(package=package), self.assertRaisesRegex(RuntimeError, "existing registration"):
                device.confirm_original_absence(package)
            device.run.assert_called_once_with("shell", "pm", "list", "packages", "--user", "all", "-u", package)

    def test_all_users_only_original_registration_blocks_before_private_profile_or_build(self):
        for package in (APP, TEST):
            self.device = SyntheticDevice(None, 31)
            self.device.retained_registrations.add(package)
            self.device.capture_profile = Mock(wraps=self.device.capture_profile)
            self.device.prepare_profile = Mock(wraps=self.device.prepare_profile)
            self.config = replace(self.config, output=self.root / ("known-before-build-" + package))
            with self.subTest(package=package), self.assertRaisesRegex(RuntimeError, "existing registration"):
                self.execute()
            self.device.capture_profile.assert_not_called()
            self.device.prepare_profile.assert_not_called()
            self.package.assert_not_called()
            self.spawn.assert_not_called()
            self.assertFalse(any(call[0] in ("install", "uninstall") for call in self.device.calls))

    def test_packaging_public_certificate_failure_cannot_install_a_fallback(self):
        self.package.side_effect = runner.PortError("Public App/Test/installed certificates differ")
        with self.assertRaisesRegex(runner.PortError, "certificates differ"):
            self.execute()
        self.assert_original_restored()
        self.assertFalse(any(call[0] == "install" for call in self.device.calls))
        self.spawn.assert_not_called()

    def test_packaging_cannot_relabel_observed_original_presence_or_absence(self):
        make = self.package.side_effect
        for original in (self.packaging["original"], None):
            self.device = SyntheticDevice(original, 31)
            self.config = replace(self.config, output=self.root / ("wrong-presence-" + str(original is None)))

            def wrong_snapshot(**kwargs):
                result = make(**kwargs)
                result["installed_snapshot"] = None if original is not None else {"sha256": "invented", "package": APP}
                return result

            self.package.side_effect = wrong_snapshot
            with self.subTest(absent=original is None), self.assertRaisesRegex(RuntimeError, "original-presence snapshot"):
                self.execute()
            self.assertEqual({} if original is None else {APP: sha256(original)}, self.device.packages)
            self.spawn.assert_not_called()

    def test_packaging_identity_or_byte_drift_cannot_reach_install(self):
        make = self.package.side_effect
        for field, value in (("owner", "retired-owner"), ("source_revision", "0" * 40),
                             ("manifest_sha256", "0" * 64), ("policy_revision", "0" * 64),
                             ("android_source_tree_sha256", "0" * 64)):
            with self.subTest(field=field):
                self.config = replace(self.config, output=self.root / field)
                self.package.side_effect = lambda **kwargs: {**make(**kwargs), field: value}
                with self.assertRaisesRegex(RuntimeError, "binding"):
                    self.execute()
                self.assert_original_restored()
        self.package.side_effect = lambda **kwargs: {**make(**kwargs), "app": {
            "path": str(self.packaging["app"]), "sha256": "0" * 64,
        }}
        self.config = replace(self.config, output=self.root / "byte-drift")
        with self.assertRaisesRegex(RuntimeError, "APK changed"):
            self.execute()
        self.assertFalse(any(call[0] == "install" for call in self.device.calls))

    def test_source_or_reservation_change_after_packaging_and_after_install_blocks_start(self):
        for stage in (2, 3):
            self.config = replace(self.config, output=self.root / ("stage-" + str(stage)))
            self.verify.side_effect = [self.policy] * (stage - 1) + [runner.PortError("actual source changed")]
            with self.subTest(stage=stage), self.assertRaisesRegex(runner.PortError, "actual source changed"):
                self.execute()
            self.assert_original_restored()
        self.spawn.assert_not_called()

    def test_schema_mismatch_stops_before_any_install(self):
        with patch.object(runner, "has_original_schema", return_value=False), self.assertRaisesRegex(RuntimeError, "schema"):
            self.execute()
        self.assertFalse(any(call[0] == "install" for call in self.device.calls))
        self.spawn.assert_not_called()

    def test_partial_install_and_final_receipt_failure_preserve_both_errors_and_original(self):
        self.device.install_failure = RuntimeError("partial install failed")
        write = Path.write_text
        count = 0

        def fail_final(path, *args, **kwargs):
            nonlocal count
            if path.name == "operations.json":
                count += 1
                if count == 3:
                    raise OSError("final receipt disk full")
            return write(path, *args, **kwargs)

        with patch.object(Path, "write_text", fail_final), self.assertRaises(ExceptionGroup) as caught:
            self.execute()
        self.assertIn("partial install failed", repr(caught.exception))
        self.assertIn("final receipt disk full", repr(caught.exception))
        self.assert_original_restored()
        self.spawn.assert_not_called()

    def test_timeout_retains_lane_even_after_original_apk_restoration(self):
        self.process.wait.side_effect = [subprocess.TimeoutExpired("synthetic-instrument", 600), 0]
        self.device.active = []
        spawn = self.spawn.side_effect

        def active_spawn(command, **kwargs):
            result = spawn(command, **kwargs)
            if command[1] != "logcat":
                self.device.active = [f"ActiveInstrumentation{{abc {TEST}/androidx.test.runner.AndroidJUnitRunner 1 procs}}"]
            return result

        self.spawn.side_effect = active_spawn
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.process.kill.assert_called_once()
        self.assertEqual([600, 15], [call.kwargs["timeout"] for call in self.process.wait.call_args_list])
        self.assertIn(("shell", "am force-stop " + APP), self.device.calls)
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_interrupt_still_kills_and_waits_without_hiding_cancellation(self):
        self.process.wait.side_effect = [KeyboardInterrupt("synthetic cancellation"), 0]
        with self.assertRaises(BaseExceptionGroup) as caught:
            self.execute()
        self.assertIsInstance(caught.exception.exceptions[0], KeyboardInterrupt)
        self.process.kill.assert_called_once()
        self.assertEqual(2, self.process.wait.call_count)
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_not_called()

    def test_log_read_failure_after_positive_transcript_does_not_clear_uncertainty(self):
        with patch.object(Path, "read_bytes", side_effect=OSError("metrics unreadable")), self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.assert_original_restored()
        self.assertTrue(self.device.application_state_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_log_stop_failure_still_waits_and_checks_guest_cleanup(self):
        self.stream.terminate.side_effect = OSError("log termination failed")
        self.device.finish_owned_instrumentation = Mock(wraps=self.device.finish_owned_instrumentation)
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.stream.wait.assert_called_once_with(timeout=15)
        self.device.finish_owned_instrumentation.assert_called_once()
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_not_called()

    def test_changed_runtime_profile_keeps_lane_after_package_restoration(self):
        wait = self.process.wait

        def change_profile(**kwargs):
            self.device.observed_profile["unexpected_change"] = True
            return 0

        wait.side_effect = change_profile
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_not_called()

    def test_transport_command_uses_explicit_current_endpoint_and_never_starts_server(self):
        device = runner.ProcessDevice(self.config, 31)
        with patch.object(runner.socket, "create_connection") as connect:
            command = device.command("get-state")
        self.assertEqual([str(self.config.adb), "-H", "127.0.0.1", "-P", "5038", "-s", "emulator-5580", "get-state"], command)
        connect.assert_called_once_with(("127.0.0.1", 5038), timeout=2)
        with patch.object(runner.socket, "create_connection", side_effect=TimeoutError("actual endpoint unavailable")):
            with self.assertRaisesRegex(TimeoutError, "endpoint unavailable"):
                device.command("get-state")
        self.spawn.assert_not_called()

    def test_actual_profile_queries_reject_unauthorized_wrong_api_or_wrong_geometry(self):
        for api in (31, 37):
            device = runner.ProcessDevice(self.config, api)
            values = {
                "getprop ro.build.version.sdk": str(api), "getprop ro.build.version.release": "synthetic",
                "getprop sys.boot_completed": "1", "getprop init.svc.bootanim": "stopped",
                "getconf _NPROCESSORS_ONLN": "4", "settings get system font_scale": "2.0",
                "settings get secure show_ime_with_hard_keyboard": "0", "wm size": "Override size: 360x900",
                "wm density": "Override density: 160", "getprop ro.build.fingerprint": "synthetic-fingerprint",
                "settings get global animator_duration_scale": "0",
                "settings get global window_animation_scale": "0",
                "settings get global transition_animation_scale": "0",
                "dumpsys activity | grep -F mGlobalConfiguration": "mGlobalConfiguration: {2.0 synthetic configuration}",
            }
            device.run = Mock(side_effect=lambda *command: "device" if command == ("get-state",) else "0")
            device.shell = Mock(side_effect=lambda command: values.get(command, "0"))
            device.active_instrumentations = Mock(return_value=[])
            device.installed = Mock(return_value={"synthetic": True})
            device.app_language = Mock(return_value="")
            self.assertEqual(str(api), device.profile()["sdk"])
            for key, bad in (("getprop ro.build.version.sdk", "30"), ("wm size", "Physical size: 1080x2400"),
                             ("settings get system font_scale", "1"), ("getprop sys.boot_completed", "0"),
                             ("settings get system font_scale", "null"),
                             ("settings get global animator_duration_scale", "null"),
                             ("settings get global window_animation_scale", "1"),
                             ("settings get global transition_animation_scale", "NaN")):
                old, values[key] = values[key], bad
                with self.subTest(api=api, key=key), self.assertRaises(RuntimeError):
                    device.profile()
                values[key] = old
            device.run.side_effect = None
            device.run.return_value = "unauthorized"
            with self.assertRaisesRegex(RuntimeError, "authenticated"):
                device.profile()

    def test_language_precondition_observes_real_storage_contract_without_resetting_user_locale(self):
        for api in (31, 37):
            device = runner.ProcessDevice(self.config, api)
            device.shell = Mock(return_value="WP503_NO_LANGUAGE_PREFS")
            observed = {"user": "0", "locales": f"Locales for {APP} for user 0 are []"}
            device.run = Mock(side_effect=lambda *command:
                observed["user"] if command == ("shell", "am", "get-current-user") else observed["locales"])
            self.assertEqual("", device.app_language())
            device.shell.return_value = '<map><string name="language"></string></map>'
            self.assertEqual("", device.app_language())
            device.shell.return_value = '<map><string name="language">zh-Hans</string></map>'
            with self.subTest(api=api), self.assertRaisesRegex(RuntimeError, "System|migration"):
                device.app_language()
            for malformed in ("invalid xml", '<map><int name="language" value="1"/></map>',
                              '<map><boolean name="native_migrated" value="unknown"/></map>'):
                device.shell.return_value = malformed
                with self.subTest(api=api, malformed=malformed), self.assertRaises((runner.PortError, RuntimeError)):
                    device.app_language()
            device.shell.return_value = "WP503_NO_LANGUAGE_PREFS"
            if api == 37:
                device.run.assert_any_call("shell", "cmd", "locale", "get-app-locales", APP, "--user", "0")
                observed["locales"] = f"Locales for {APP} for user 0 are [de]"
                with self.assertRaisesRegex(RuntimeError, "not System"):
                    device.app_language()
                for invalid in ("unknown locale response", f"Locales for {APP} for user -2 are []",
                                f"Locales for {APP} for user 10 are []"):
                    observed["locales"] = invalid
                    with self.subTest(response=invalid), self.assertRaisesRegex(RuntimeError, "unrecognized"):
                        device.app_language()
                observed["locales"] = f"Locales for {APP} for user 10 are []"
                observed["user"] = "10"
                device.shell.reset_mock()
                with self.assertRaisesRegex(RuntimeError, "owner user0"):
                    device.app_language()
                device.shell.assert_not_called()
                for invalid in ("current", "-2", "invalid", "0;echo invalid"):
                    observed["user"] = invalid
                    with self.subTest(user=invalid), self.assertRaisesRegex(RuntimeError, "current Android user"):
                        device.app_language()
            self.assertTrue(all("set-app-locales" not in str(call) for call in device.run.call_args_list))

    def test_both_apis_reject_non_owner_user_before_private_access(self):
        for api in (31, 37):
            device = runner.ProcessDevice(self.config, api)
            device.run = Mock(return_value="10")
            device.shell = Mock()
            with self.subTest(api=api), self.assertRaisesRegex(RuntimeError, "owner user0"):
                device.app_language()
            device.shell.assert_not_called()

    def test_bound_owner_user_scopes_package_and_instrumentation_commands_and_rejects_switch(self):
        device = runner.ProcessDevice(self.config, 31)
        device.android_user = "0"
        device.current_user = Mock(return_value="0")
        with patch.object(runner.socket, "create_connection"):
            for command in (("install", "-r", "app.apk"), ("uninstall", TEST),
                            ("shell", "pm", "path", APP), ("shell", "am", "instrument", "-w")):
                with self.subTest(command=command):
                    actual = device.command(*command)
                    self.assertEqual("0", actual[actual.index("--user") + 1])
            device.current_user.return_value = "10"
            with self.assertRaisesRegex(RuntimeError, "Current Android user changed"):
                device.command("install", "-r", "app.apk")
        device.run = Mock()
        device.shell("run-as " + APP + " sh -c 'echo synthetic'")
        device.run.assert_called_once_with("shell", "run-as " + APP + " --user 0 sh -c 'echo synthetic'")

    def test_original_absence_removes_only_new_verified_packages_on_both_apis(self):
        for api in (31, 37):
            self.request = runner.request_for(source(), api)
            self.device = SyntheticDevice(None, api)
            self.config = replace(self.config, output=self.root / ("empty-operation-" + str(api)))
            self.ledger.reset_mock()
            result = self.execute()
            self.assertEqual({}, self.device.packages)
            self.assertIsNone(result["installed_before"])
            self.assertEqual("originally-absent", result["preservation_mode"])
            self.assertIn("original_target_absence", result["cleanup"])
            self.assertEqual(2, result["cohort"]["passed"])
            self.assertFalse(any(call[0] == "pull" for call in self.device.calls))
            self.ledger.release_hard_lock.assert_called_once()

    def test_absent_original_partial_install_failures_restore_absence_without_instrumentation(self):
        for failed_name in ("current-app.apk", "current-tests.apk"):
            self.device = SyntheticDevice(None, 31)
            self.config = replace(self.config, output=self.root / ("failed-empty-" + failed_name))
            install = self.device.install

            def fail(path):
                install(path)
                if path.name == failed_name:
                    raise RuntimeError("Actual partial install failure")

            self.device.install = fail
            with self.subTest(package=failed_name), self.assertRaisesRegex(RuntimeError, "partial install failure"):
                self.execute()
            self.assertEqual({}, self.device.packages)
            self.spawn.assert_not_called()

    def test_absent_original_foreign_package_is_never_uninstalled_and_keeps_lane(self):
        self.device = SyntheticDevice(None, 31)

        def changed_install(path):
            self.device.packages[APP] = "foreign-app"
            raise RuntimeError("Actual installed package changed")

        self.device.install = changed_install
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.assertEqual({APP: "foreign-app"}, self.device.packages)
        self.assertNotIn(("uninstall", APP), self.device.calls)
        self.ledger.release_hard_lock.assert_not_called()

    def test_failed_original_apk_recovery_still_restores_temporary_profile_and_error_receipt(self):
        install = self.device.install
        restore = self.device.restore_profile

        def fail_backup(path):
            if path.name == "original-target.apk":
                raise RuntimeError("Actual preserved APK reinstall failed")
            install(path)

        def prepare(original, record):
            record({"synthetic": "temporary-font"})
            self.device.observed_profile["temporary_font"] = "2.0"

        def restore_temporary(original, record):
            record({"synthetic": "restore-original-font"})
            self.device.observed_profile.pop("temporary_font", None)
            restore(original, record)

        def remove_target(**kwargs):
            self.device.packages.pop(APP)
            return 0

        self.device.install = fail_backup
        self.device.prepare_profile = prepare
        self.device.restore_profile = Mock(side_effect=restore_temporary)
        self.process.wait.side_effect = remove_target
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.device.restore_profile.assert_called_once()
        self.assertNotIn("temporary_font", self.device.observed_profile)
        self.assertEqual({}, self.device.packages)
        result = json.loads((self.config.output / "operations.json").read_text())
        self.assertIn("preserved APK reinstall failed", result["cleanup"]["original_target_apk"])
        self.assertIn("Original target APK was not restored", result["cleanup"]["original_target_digest"])
        self.assertTrue(result["cleanup_errors"])
        self.assertTrue(result["profile_restoration_attempts"])
        self.ledger.release_hard_lock.assert_not_called()

    def test_packaging_time_retained_registration_cannot_reach_profile_or_package_mutation(self):
        make = self.package.side_effect
        for package in (APP, TEST):
            self.device = SyntheticDevice(None, 31)
            self.device.prepare_profile = Mock(wraps=self.device.prepare_profile)
            self.config = replace(self.config, output=self.root / ("retained-during-build-" + package))
            self.ledger.reset_mock()

            def retain_registration(**kwargs):
                result = make(**kwargs)
                self.device.retained_registrations.add(package)
                return result

            self.package.side_effect = retain_registration
            with self.subTest(package=package), self.assertRaises(BaseExceptionGroup):
                self.execute()
            self.device.prepare_profile.assert_not_called()
            self.assertFalse(any(call[0] in ("install", "uninstall") for call in self.device.calls))
            self.assertEqual({}, self.device.packages)
            self.assertEqual({package}, self.device.retained_registrations)
            self.spawn.assert_not_called()
            self.ledger.release_hard_lock.assert_not_called()

    def test_profile_restoration_group_does_not_prevent_final_error_receipt(self):
        self.device.restore_profile = Mock(side_effect=ExceptionGroup("Actual cleanup failure", [OSError("Unreadable setting")]))
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        result = json.loads((self.config.output / "operations.json").read_text())
        self.assertEqual(2, result["cohort"]["passed"])
        self.assert_original_restored()
        self.assertNotIn("original_profile_restored", result)

    def test_observed_unset_font_is_not_a_literal_null_or_failed_probe(self):
        device = runner.ProcessDevice(self.config, 31)
        device.run = Mock(side_effect=lambda *command: "device" if command == ("get-state",) else "0")
        values = {
            "getprop ro.build.version.sdk": "31", "getprop ro.build.version.release": "synthetic",
            "getprop sys.boot_completed": "1", "getprop init.svc.bootanim": "stopped",
            "getconf _NPROCESSORS_ONLN": "4", "settings get system font_scale": "null",
            "settings get secure show_ime_with_hard_keyboard": "1", "wm size": "Physical size: 1080x2400",
            "wm density": "Physical density: 420", "getprop ro.build.fingerprint": "synthetic",
            "settings list system": "",
        }
        device.shell = Mock(side_effect=lambda command: values.get(command, "0"))
        device.installed = Mock(return_value=None)
        device.active_instrumentations = Mock(return_value=[])
        self.assertEqual("null", device.capture_profile()["font"])
        values["settings list system"] = "font_scale=null"
        with self.assertRaisesRegex(RuntimeError, "not confirmed absent"):
            device.capture_profile()
        values["settings list system"] = "unknown settings-provider response"
        with self.assertRaisesRegex(RuntimeError, "inventory is unrecognized"):
            device.capture_profile()
        device.shell.side_effect = OSError("Actual settings read failed")
        with self.assertRaisesRegex(OSError, "settings read failed"):
            device.capture_profile()

    def api37_profile_device(self, *, raw="1.25", scale=1.25):
        self.request = runner.request_for(source("comparator"), 37)
        device = runner.ProcessDevice(self.config, 37)
        values = {
            "getprop ro.build.version.sdk": "37", "getprop ro.build.version.release": "synthetic",
            "getprop sys.boot_completed": "1", "getprop init.svc.bootanim": "stopped",
            "getconf _NPROCESSORS_ONLN": "4", "settings get system font_scale": raw,
            "settings get secure show_ime_with_hard_keyboard": "0",
            "wm size": "Physical size: 1344x2992\nOverride size: 360x900",
            "wm density": "Physical density: 480\nOverride density: 160",
            "getprop ro.build.fingerprint": "synthetic-api37",
            **{"settings get global " + key: "0" for key in (
                "animator_duration_scale", "window_animation_scale", "transition_animation_scale")},
        }
        state = {"scale": scale, "configuration": None, "sequence": 1}

        def shell(command):
            if command == "dumpsys activity | grep -F mGlobalConfiguration":
                return state["configuration"] if state["configuration"] is not None else (
                    f"mGlobalConfiguration: {{{state['scale']} synthetic s.{state['sequence']}}}")
            if command == "settings list system":
                value = values["settings get system font_scale"]
                return "" if value == "null" else "font_scale=" + value
            if command.startswith("settings put system font_scale "):
                value = command.removeprefix("settings put system font_scale ")
                values["settings get system font_scale"], state["scale"] = value, float(value)
                return ""
            return values[command]

        device.run = Mock(side_effect=lambda *command: "device" if command == ("get-state",) else "0")
        device.shell = Mock(side_effect=shell)
        device.installed = Mock(return_value=None)
        device.active_instrumentations = Mock(return_value=[])
        device.write_profile_value = Mock(wraps=device.write_profile_value)
        return device, values, state

    def profile_lane(self, device):
        return runner.execution_lane(
            self.ledger, device, lock_owner=self.request.operation_owner,
            canonical=self.request.binding.canonical_session, endpoint=self.config.endpoint,
            purpose="synthetic-api37-effective-font", source=self.request.binding.source_revision,
        )

    def test_api37_baseline_captures_actual_effective_font_not_unset_default_or_configuration_sequence(self):
        device, _, state = self.api37_profile_device(raw="null")
        with self.profile_lane(device):
            original = device.capture_profile()
            device.original_runtime_profile = original
            self.assertEqual("null", original["font"])
            self.assertEqual(1.25, original["effective_font_scale"])
            state["sequence"] = 99
            self.assertEqual(original, device.capture_profile())
        device.write_profile_value.assert_not_called()
        self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)

    def test_api37_valid_restoration_proves_three_quiescent_samples_before_clearing_and_release(self):
        device, values, _ = self.api37_profile_device()
        events = []
        device.active_instrumentations.side_effect = lambda: events.append("quiescent") or []

        def record(item):
            self.assertTrue(device.profile_restoration_uncertain)
            self.ledger.release_hard_lock.assert_not_called()
            if item.get("phase") == "verify-original-effective-font":
                self.assertEqual("quiescent", events[-1])
                self.assertEqual(original, item["observed_profile"])

        receipt = Mock(side_effect=record)
        with self.profile_lane(device), patch.object(runner.time, "sleep") as sleep:
            original = device.capture_profile()
            device.original_runtime_profile = original
            device.prepare_profile(original, receipt)
            self.assertTrue(device.profile_restoration_uncertain)
            self.assertEqual("2.0", values["settings get system font_scale"])
            device.restore_profile(original, receipt)
            self.assertFalse(device.profile_restoration_uncertain)
            self.ledger.release_hard_lock.assert_not_called()
            self.assertEqual([0.1, 0.2], [call.args[0] for call in sleep.call_args_list])
        proofs = [call.args[0] for call in receipt.call_args_list
                  if call.args[0].get("phase") == "verify-original-effective-font"]
        self.assertEqual([0, 0.1, 0.2], [proof["delay_seconds"] for proof in proofs])
        self.assertEqual("1.25", values["settings get system font_scale"])
        self.assertEqual(2, device.write_profile_value.call_count)
        self.ledger.release_hard_lock.assert_called_once_with("native-execution", self.request.operation_owner)
        self.assertFalse(any("dumpsys settings" in call.args[0] for call in device.shell.call_args_list))

    def test_api37_equal_raw_profile_with_wrong_effective_font_retains_uncertainty_and_lane(self):
        device, _, state = self.api37_profile_device()
        record = Mock()
        with self.assertRaises(BaseExceptionGroup), self.profile_lane(device):
            original = device.capture_profile()
            device.original_runtime_profile = original
            state["scale"] = 2.0
            device.restore_profile(original, record)
        observed = record.call_args.args[0]["observed_profile"]
        self.assertEqual(original, {**observed, "effective_font_scale": original["effective_font_scale"]})
        self.assertEqual(2.0, observed["effective_font_scale"])
        self.assertTrue(device.profile_restoration_uncertain)
        device.write_profile_value.assert_not_called()
        self.ledger.release_hard_lock.assert_not_called()

    def test_api37_missing_or_invalid_original_effective_baseline_cannot_clear_or_release(self):
        for invalid in ("missing", None, True, 2, "1.25", 0.0, -1.0, float("nan"), float("inf")):
            device, _, _ = self.api37_profile_device()
            self.ledger.reset_mock()
            with self.subTest(invalid=invalid), self.assertRaises(BaseExceptionGroup), self.profile_lane(device):
                original = device.capture_profile()
                if invalid == "missing":
                    original.pop("effective_font_scale")
                else:
                    original["effective_font_scale"] = invalid
                device.original_runtime_profile = original
                device.restore_profile(original, Mock())
            self.assertTrue(device.profile_restoration_uncertain)
            device.write_profile_value.assert_not_called()
            self.ledger.release_hard_lock.assert_not_called()

    def test_api37_missing_malformed_or_nonpositive_effective_observation_retains_lane(self):
        for invalid in ("", "unknown configuration", "mGlobalConfiguration: {NaN synthetic}",
                        "mGlobalConfiguration: {0.0 synthetic}", "mGlobalConfiguration: {1.25 one}\nextra",
                        "mGlobalConfiguration: {" + "9" * 400 + ".0 synthetic}"):
            device, _, state = self.api37_profile_device()
            self.ledger.reset_mock()
            with self.subTest(invalid=invalid), self.assertRaises(BaseExceptionGroup), self.profile_lane(device):
                original = device.capture_profile()
                device.original_runtime_profile = original
                state["configuration"] = invalid
                device.restore_profile(original, Mock())
            self.assertTrue(device.profile_restoration_uncertain)
            device.write_profile_value.assert_not_called()
            self.ledger.release_hard_lock.assert_not_called()

    def test_api37_effective_instability_after_first_matching_sample_retains_lane(self):
        device, _, _ = self.api37_profile_device()
        record = Mock()
        with self.assertRaises(BaseExceptionGroup), self.profile_lane(device), patch.object(runner.time, "sleep") as sleep:
            original = device.capture_profile()
            device.original_runtime_profile = original
            device.font_configuration = Mock(side_effect=[("synthetic", 1.25), ("synthetic", 2.0), ("synthetic", 1.25)])
            device.restore_profile(original, record)
        self.assertEqual([0, 0.1], [call.args[0]["delay_seconds"] for call in record.call_args_list])
        sleep.assert_called_once_with(0.1)
        self.assertTrue(device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_api37_raw_drift_between_effective_stability_samples_retains_lane(self):
        device, values, _ = self.api37_profile_device()
        with self.assertRaises(BaseExceptionGroup), self.profile_lane(device), patch.object(
            runner.time, "sleep", side_effect=lambda delay: values.update({"settings get secure show_ime_with_hard_keyboard": "1"})
        ):
            original = device.capture_profile()
            device.original_runtime_profile = original
            device.restore_profile(original, Mock())
        self.assertTrue(device.profile_restoration_uncertain)
        device.write_profile_value.assert_not_called()
        self.ledger.release_hard_lock.assert_not_called()

    def test_api37_instrumentation_during_stability_is_not_quiescence_or_release(self):
        device, _, _ = self.api37_profile_device()
        with self.assertRaises(BaseExceptionGroup), self.profile_lane(device), patch.object(runner.time, "sleep"):
            original = device.capture_profile()
            device.original_runtime_profile = original
            device.active_instrumentations.side_effect = [[], ["foreign"], ["foreign"]]
            device.restore_profile(original, Mock())
        self.assertTrue(device.profile_restoration_uncertain)
        device.write_profile_value.assert_not_called()
        self.ledger.release_hard_lock.assert_not_called()

    def test_api37_failed_effective_read_does_not_prevent_independent_raw_restoration(self):
        device, values, _ = self.api37_profile_device()
        with self.assertRaises(BaseExceptionGroup), self.profile_lane(device):
            original = device.capture_profile()
            device.original_runtime_profile = original
            device.prepare_profile(original, Mock())
            device.font_configuration = Mock(side_effect=OSError("Effective probe unavailable"))
            device.restore_profile(original, Mock())
        self.assertEqual("1.25", values["settings get system font_scale"])
        self.assertEqual(2, device.write_profile_value.call_count)
        self.assertTrue(device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_api37_journal_failure_cannot_skip_restoration_proofs_or_release_lane(self):
        device, values, _ = self.api37_profile_device()
        record = Mock(side_effect=OSError("Profile receipt unavailable"))
        with self.assertRaises(BaseExceptionGroup), self.profile_lane(device), patch.object(runner.time, "sleep") as sleep:
            original = device.capture_profile()
            device.original_runtime_profile = original
            device.prepare_profile(original, Mock())
            device.restore_profile(original, record)
        self.assertEqual("1.25", values["settings get system font_scale"])
        self.assertEqual(4, record.call_count)
        self.assertEqual([0.1, 0.2], [call.args[0] for call in sleep.call_args_list])
        self.assertTrue(device.profile_restoration_uncertain)
        self.ledger.release_hard_lock.assert_not_called()

    def test_api37_supported_release_rechecks_effective_profile_after_stability_proof(self):
        device, _, state = self.api37_profile_device()
        with self.assertRaises(BaseExceptionGroup), self.profile_lane(device), patch.object(runner.time, "sleep"):
            original = device.capture_profile()
            device.original_runtime_profile = original
            device.restore_profile(original, Mock())
            self.assertFalse(device.profile_restoration_uncertain)
            state["scale"] = 2.0
        self.ledger.release_hard_lock.assert_not_called()

    def test_api37_native_profile_rejects_raw_font200_without_effective_font200(self):
        device, _, _ = self.api37_profile_device(raw="2.0")
        with self.assertRaisesRegex(RuntimeError, "effective font profile is not 200%"):
            device.profile()
        device.write_profile_value.assert_not_called()
        self.spawn.assert_not_called()

    def test_temporary_profile_restores_exact_absence_and_survives_receipt_write_failure(self):
        device = runner.ProcessDevice(self.config, 31)
        original = {"font": "null", "ime": "1", "animator_duration_scale": "null",
                    "window_animation_scale": "1.0", "transition_animation_scale": "1.0",
                    "size": "Physical size: 1080x2400", "density": "Physical density: 420"}
        values = {key: None if original[name] == "null" else original[name]
                  for name, (_, key, _) in device.SETTINGS.items()}
        values.update(size=None, density=None)
        device.capture_profile = Mock(return_value=original.copy())
        device.font_configuration = Mock(return_value=("synthetic settled configuration", 1))
        device.profile_value = Mock(side_effect=lambda item: values[item["key"]])
        writes = []

        def write(item, value):
            values[item["key"]] = value
            writes.append((item.copy(), value))

        device.write_profile_value = Mock(side_effect=write)
        record = Mock()
        device.prepare_profile(original, record)
        self.assertEqual(7, len(writes))
        self.assertEqual("2.0", values["font_scale"])
        self.assertEqual("360x900", values["size"])
        with self.assertRaises(ExceptionGroup):
            device.restore_profile(original, Mock(side_effect=OSError("Receipt write failed")))
        self.assertEqual(14, len(writes))
        self.assertIsNone(values["font_scale"])
        self.assertIsNone(values["animator_duration_scale"])
        self.assertIsNone(values["size"])
        self.assertEqual("1.0", values["window_animation_scale"])
        device.shell = Mock()
        runner.ProcessDevice.write_profile_value(device, {"kind": "setting", "namespace": "system", "key": "font_scale"}, None)
        device.shell.assert_called_once_with("settings delete system font_scale")
        device.shell.reset_mock()
        runner.ProcessDevice.write_profile_value(device, {"kind": "display", "key": "size"}, None)
        device.shell.assert_called_once_with("wm size reset")

    def materializing_font_device(self, *, materialize=True):
        device = runner.ProcessDevice(self.config, 31)
        item = {"kind": "setting", "namespace": "system", "key": "font_scale",
                "original": None, "temporary": "2.0"}
        original = {"font": "null", "ime": "1"}
        state = {"font": "2.0", "scale": 2, "deletes": 0, "metadata": None, "stuck": False, "extra": {}}
        previous = "1970-01-01 01:39:03 update font_scale\r\n1970-01-01 01:39:03 update font_scale"
        metadata = ("_id:43 name:font_scale pkg:android value:1.0 default:1.0 defaultSystemSet:true\r\n"
                    "1970-01-01 01:39:30 update font_scale\r\n1970-01-01 01:39:30 delete font_scale\r\n" + previous)

        def shell(command):
            if command == "settings delete system font_scale":
                state["deletes"] += 1
                state["font"] = "1.0" if materialize and state["deletes"] == 1 else None
                if not state["stuck"]:
                    state["scale"] = 1
                return "Deleted 1 rows"
            if command == "settings get system font_scale":
                return state["font"] or "null"
            if command == "settings list system":
                return "" if state["font"] is None else "font_scale=" + state["font"]
            if command == "dumpsys activity | grep -F mGlobalConfiguration":
                return "mGlobalConfiguration: {" + str(state["scale"]) + ".0 synthetic configuration}"
            if command == "dumpsys settings | grep -F font_scale":
                return (state["metadata"] or metadata) if state["deletes"] else (
                    "_id:42 name:font_scale pkg:android value:2.0 default:2.0 defaultSystemSet:true\r\n" + previous)
            raise AssertionError("Unexpected font cleanup command: " + command)

        device.shell = Mock(side_effect=shell)
        device.capture_profile = Mock(side_effect=lambda: {
            **original, "font": state["font"] or "null", **state["extra"],
        })
        device.profile_mutations = [item]
        return device, original, state, metadata

    def test_observed_framework_font_materialization_gets_one_provenance_bound_cleanup_delete(self):
        device, original, state, _ = self.materializing_font_device()
        record = Mock()
        device.restore_profile(original, record)
        self.assertEqual(2, state["deletes"])
        self.assertIsNone(state["font"])
        self.assertFalse(device.profile_restoration_uncertain)
        self.assertEqual(original, device.capture_profile())
        self.assertEqual(1, sum(call.args[0].get("phase") == "single-framework-materialization-cleanup"
                                for call in record.call_args_list))
        self.assertFalse(any("settings put" in call.args[0] for call in device.shell.call_args_list))

    def test_already_settled_default_font_restores_absence_with_only_one_delete(self):
        device, original, state, _ = self.materializing_font_device(materialize=False)
        state["scale"] = 1
        device.restore_profile(original, Mock())
        self.assertEqual(1, state["deletes"])
        self.assertIsNone(state["font"])
        self.assertFalse(device.profile_restoration_uncertain)
        self.assertFalse(any("dumpsys settings" in call.args[0] for call in device.shell.call_args_list))

    def test_foreign_or_uncorrelated_font_materialization_is_never_deleted_again(self):
        _, _, _, metadata = self.materializing_font_device()
        variants = (
            metadata.replace("pkg:android", "pkg:foreign.owner"),
            metadata.replace("defaultSystemSet:true", "defaultSystemSet:false"),
            metadata.replace("1970-01-01 01:39:30 delete", "1970-01-01 01:39:30 update"),
            metadata.replace("1970-01-01 01:39:03", "1970-01-01 01:38:03"),
            "\r\n".join(metadata.splitlines()[:3]),
            metadata.replace("1970-01-01 01:39:30", "1970-01-01 01:39:03"),
            metadata + "\r\nunknown response",
            metadata.replace("1970-01-01", "1970-99"),
        )
        for value in variants:
            device, original, state, _ = self.materializing_font_device()
            state["metadata"] = value
            with self.subTest(metadata=value), self.assertRaises(ExceptionGroup):
                device.restore_profile(original, Mock())
            self.assertEqual(1, state["deletes"])
            self.assertEqual("1.0", state["font"])
            self.assertTrue(device.profile_restoration_uncertain)
        device, original, state, _ = self.materializing_font_device()
        state["extra"] = {"ime": "foreign"}
        with self.assertRaises(ExceptionGroup):
            device.restore_profile(original, Mock())
        self.assertEqual(1, state["deletes"])
        self.assertTrue(device.profile_restoration_uncertain)

    def test_unchanged_alternating_font_history_cannot_authorize_an_extra_delete(self):
        device, original, state, metadata = self.materializing_font_device()
        state["metadata"] = metadata
        shell = device.shell.side_effect

        def unchanged(command):
            return metadata if command == "dumpsys settings | grep -F font_scale" else shell(command)

        device.shell.side_effect = unchanged
        with self.assertRaises(ExceptionGroup):
            device.restore_profile(original, Mock())
        self.assertEqual(1, state["deletes"])
        self.assertTrue(device.profile_restoration_uncertain)

    def test_already_raw_unset_font_still_requires_effective_and_stability_proof_without_writes(self):
        for scale in (1, 2):
            device, original, state, _ = self.materializing_font_device()
            state.update(font=None, scale=scale)
            device.active_instrumentations = Mock(return_value=[])
            device.original_runtime_profile = original
            self.ledger.reset_mock()
            with self.subTest(scale=scale), patch.object(runner.time, "monotonic", side_effect=[0, 6]):
                if scale == 1:
                    device.restore_profile(original, Mock())
                    self.assertFalse(device.profile_restoration_uncertain)
                else:
                    with self.assertRaises(BaseExceptionGroup), runner.execution_lane(
                        self.ledger, device, lock_owner=self.request.operation_owner,
                        canonical=self.request.binding.canonical_session, endpoint=self.config.endpoint,
                        purpose="synthetic-already-unset-font", source=self.request.binding.source_revision,
                    ):
                        device.restore_profile(original, Mock())
                    self.assertTrue(device.profile_restoration_uncertain)
                    self.assertEqual(original, device.capture_profile())
                    self.ledger.release_hard_lock.assert_not_called()
            self.assertIsNone(state["font"])
            self.assertEqual(0, state["deletes"])

    def test_font_recovery_receipt_failure_does_not_prevent_independent_exact_cleanup(self):
        device, original, state, _ = self.materializing_font_device()
        with self.assertRaises(ExceptionGroup):
            device.restore_profile(original, Mock(side_effect=OSError("Recovery receipt unavailable")))
        self.assertEqual(2, state["deletes"])
        self.assertIsNone(state["font"])
        self.assertFalse(device.profile_restoration_uncertain)

    def test_font_settlement_timeout_retains_uncertainty_even_when_raw_setting_is_unset(self):
        device, original, state, _ = self.materializing_font_device(materialize=False)
        state["stuck"] = True
        with patch.object(runner.time, "monotonic", side_effect=[0, 6]), self.assertRaises(ExceptionGroup):
            device.restore_profile(original, Mock())
        self.assertEqual(1, state["deletes"])
        self.assertIsNone(state["font"])
        self.assertTrue(device.profile_restoration_uncertain)

    def test_effective_font_query_rejects_unknown_or_foreign_configuration_before_deletion(self):
        for value in ("unknown configuration", "mGlobalConfiguration: {NaN synthetic}",
                      "mGlobalConfiguration: {3.0 foreign}", "mGlobalConfiguration: {1.0 one}\nextra"):
            device, original, state, _ = self.materializing_font_device()
            shell = device.shell.side_effect
            device.shell.side_effect = lambda command: value if "mGlobalConfiguration" in command else shell(command)
            with self.subTest(value=value), self.assertRaises(ExceptionGroup):
                device.restore_profile(original, Mock())
            self.assertEqual(0, state["deletes"])
            self.assertTrue(device.profile_restoration_uncertain)

    def test_unresolved_effective_profile_retains_lane_even_when_raw_profile_matches(self):
        restore = self.device.restore_profile

        def unsettled(original, record):
            restore(original, record)
            self.device.profile_restoration_uncertain = True
            raise RuntimeError("Effective font restoration did not settle")

        self.device.restore_profile = unsettled
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.assert_original_restored()
        self.ledger.release_hard_lock.assert_not_called()

    def test_foreign_profile_value_is_not_overwritten_during_independent_restoration(self):
        device = runner.ProcessDevice(self.config, 31)
        device.profile_mutations = [
            {"kind": "setting", "namespace": "system", "key": "font_scale", "original": None, "temporary": "2.0"},
            {"kind": "setting", "namespace": "secure", "key": "show_ime_with_hard_keyboard", "original": "1", "temporary": "0"},
        ]
        device.profile_value = Mock(side_effect=lambda item: "3.0" if item["key"] == "font_scale" else "0")
        device.write_profile_value = Mock()
        device.capture_profile = Mock(return_value={"foreign": True})
        with self.assertRaises(ExceptionGroup):
            device.restore_profile({}, Mock())
        device.write_profile_value.assert_called_once_with(device.profile_mutations[1], "1")

    def test_cli_has_real_mutually_exclusive_readiness_and_execution_not_a_provider_flag(self):
        with patch.object(runner, "current_binding", return_value=source()), \
                patch.object(runner, "Ledger", return_value=self.ledger), \
                patch.object(runner, "shared_ledger", return_value=self.root / "synthetic-ledger"), \
                patch.object(runner, "readiness") as readiness:
            self.assertEqual(0, runner.main(["--head", source().source_revision, "--api", "31", "--readiness"]))
            result = json.loads(self.print.call_args.args[0])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["acceptance"])
        readiness.assert_called_once()
        self.spawn.assert_not_called()
        with patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as caught:
            runner.main(["--head", source().source_revision, "--api", "31", "--readiness", "--execute"])
        self.assertEqual(2, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
