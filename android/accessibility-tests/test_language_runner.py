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
from test_shared_device_runner import FakeDevice
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
            kwargs["stdout"].write(transcript().encode())
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
