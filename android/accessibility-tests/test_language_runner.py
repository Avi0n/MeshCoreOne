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
        self.packages[APP] = sha256(original)
        self.observed_profile = {"sdk": str(api), "synthetic": True}
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
        if command[0] == "pull":
            self.calls.append(command)
            shutil.copyfile(self.original, command[2])
            return "synthetic pull"
        return super().run(*command, **kwargs)

    def profile(self):
        return self.observed_profile.copy()


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

    def test_missing_original_or_preexisting_test_is_not_overwritten(self):
        for packages in ({}, {APP: sha256(self.packaging["original"]), TEST: "foreign-test"}):
            self.device.packages = packages.copy()
            with self.subTest(packages=packages), self.assertRaisesRegex(RuntimeError, "preserved|overwritten"):
                self.execute()
            self.assertEqual(packages, self.device.packages)
        self.package.assert_not_called()
        self.assertEqual([], self.device.calls)

    def test_packaging_public_certificate_failure_cannot_install_a_fallback(self):
        self.package.side_effect = runner.PortError("Public App/Test/installed certificates differ")
        with self.assertRaisesRegex(runner.PortError, "certificates differ"):
            self.execute()
        self.assert_original_restored()
        self.assertFalse(any(call[0] == "install" for call in self.device.calls))
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
            device.run = Mock(return_value="device")
            device.shell = Mock(side_effect=lambda command: values.get(command, "0"))
            device.active_instrumentations = Mock(return_value=[])
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
                self.assertEqual("", device.app_language())
                for invalid in ("current", "-2", "invalid", "0;echo invalid"):
                    observed["user"] = invalid
                    with self.subTest(user=invalid), self.assertRaisesRegex(RuntimeError, "current Android user"):
                        device.app_language()
            self.assertTrue(all("set-app-locales" not in str(call) for call in device.run.call_args_list))

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
