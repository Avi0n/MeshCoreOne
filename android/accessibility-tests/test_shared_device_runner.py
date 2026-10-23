"""AndroidOnly: WP-503 Fail-closed discovery for the missing-only shared-device adapter."""
from pathlib import Path, PurePosixPath, PureWindowsPath
import hashlib
import json
import runpy
import subprocess
import tempfile
import unittest
import uuid
from unittest.mock import Mock, patch

from shared_device_runner import (
    APP, TEST, Device, execute_cohort, execute_selection, install_pair, install_succeeded,
    native_lane, parse_outcomes, restore_packages, selected_methods,
)


def record(identity, status, count=1):
    klass, method = identity.split("#")
    return (f"INSTRUMENTATION_STATUS: class={klass}\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: numtests={count}\n"
            f"INSTRUMENTATION_STATUS_CODE: {status}\n")


def completed(text, count=1):
    return (text + f"INSTRUMENTATION_RESULT: stream=\nTime: 0.1\nOK ({count} tests)\n"
            "INSTRUMENTATION_CODE: -1\n")


class FakeDevice(Device):
    def __init__(self, fail_install=None):
        self.packages = {APP: "original"}
        self.fail_install = fail_install
        self.calls = []
        self.active = []

    def require_fixture_recovery(self):
        """Admit only this in-memory fake to exercise downstream lifecycle regressions."""

    def installed(self, package):
        return {"sha256": self.packages[package]} if package in self.packages else None

    def install(self, path):
        self.calls.append(("install", path))
        package, digest = {Path("app"): (APP, "candidate"), Path("test"): (TEST, "test"),
                           Path("backup"): (APP, "original")}[path]
        self.packages[package] = digest
        if self.fail_install and path == self.fail_install[0]:
            failure = self.fail_install[1]
            self.fail_install = None
            raise failure

    def run(self, *command, **_kwargs):
        self.calls.append(command)
        if command == ("uninstall", TEST):
            del self.packages[TEST]
            return "Success"
        raise AssertionError(command)

    def active_instrumentations(self):
        return self.active.copy()

    def shell(self, command):
        self.calls.append(("shell", command))
        if command == "am force-stop " + APP:
            self.active = []
            return ""
        raise AssertionError(command)

    def profile(self):
        return {}

    def command(self, *command):
        return ["fake-adb", *command]


class SharedDeviceDiscoveryTest(unittest.TestCase):
    def test_windows_signer_path_import_is_independent_of_host_path_flavour(self):
        from shared_device_runner import ADB
        source = Path(__file__).with_name("shared_device_runner.py")
        with patch("pathlib.Path", side_effect=lambda value: PurePosixPath(value) if value == ADB else Path(value)), \
                patch("subprocess.Popen") as spawn, patch("subprocess.check_output") as command:
            imported = runpy.run_path(str(source))
            spawn.assert_not_called()
            command.assert_not_called()
            expected = PureWindowsPath(
                r"C:\Users\camer\AppData\Local\Android\Sdk\build-tools\37.0.0\apksigner.bat"
            )
            self.assertIsInstance(imported["SIGNER"], PureWindowsPath)
            self.assertEqual(expected, imported["SIGNER"])
            command.return_value = "certificate SHA-256 digest: " + "a" * 64
            self.assertEqual("a" * 64, imported["certificate"](Path("candidate.apk")))
            command.assert_called_once_with(
                [str(expected), "verify", "--print-certs", "candidate.apk"],
                text=True, stderr=subprocess.STDOUT,
            )

    def test_unavailable_recovery_blocks_install_before_attempt_or_mutation(self):
        device = Device()
        device.install = Mock()
        attempt = Mock()
        with self.assertRaisesRegex(RuntimeError, "genuine pre-mutation.*supported snapshot-based recovery"):
            install_pair(device, {"app": Path("app"), "test": Path("test")}, attempt)
        device.install.assert_not_called()
        attempt.assert_not_called()

    def test_unavailable_recovery_blocks_instrumentation_before_device_or_process_access(self):
        device = Device()
        device.profile = Mock()
        device.installed = Mock()
        device.command = Mock()
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen") as spawn:
            with self.assertRaisesRegex(RuntimeError, "genuine pre-mutation.*supported snapshot-based recovery"):
                execute_selection(
                    device, Path(directory), "nodes", {}, "candidate", "test",
                    expected=["example.Test#original"], api=37, source="synthetic",
                    android_tree="synthetic", canonical="synthetic", endpoint="synthetic",
                    stem_name="synthetic", event_prefix="SYNTHETIC",
                )
            self.assertEqual([], list(Path(directory).iterdir()))
        device.profile.assert_not_called()
        device.installed.assert_not_called()
        device.command.assert_not_called()
        spawn.assert_not_called()

    def test_legacy_execute_blocks_before_binding_output_or_lane(self):
        from shared_device_runner import main
        with patch("sys.argv", ["shared_device_runner.py", "--apks", "synthetic",
                              "--output", "synthetic", "--execute"]), \
                patch("shared_device_runner.verify_binding") as binding, \
                patch("shared_device_runner.native_lane") as lane:
            with self.assertRaisesRegex(RuntimeError, "genuine pre-mutation.*supported snapshot-based recovery"):
                main()
        binding.assert_not_called()
        lane.assert_not_called()

    def test_exact_installer_success_forms_and_failures(self):
        install_succeeded("Success")
        install_succeeded("Performing Streamed Install\nSuccess")
        for text in ("", "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", "Success\nFailure"):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                install_succeeded(text)

    def test_exact_missing_selector_families(self):
        from shared_device_runner import COHORTS
        self.assertEqual([11, 2, 5, 7, 1, 1], [len(selected_methods(name)) for name in COHORTS])

    def test_positive_matching_discovery(self):
        identity = "example.Test#original"
        result = parse_outcomes(completed(record(identity, 1) + record(identity, 0)), [identity])
        self.assertEqual((1, 1, 1, 0, 0, 0), tuple(result[k] for k in
                         ("discovered", "started", "passed", "failed", "errors", "skipped")))
        self.assertTrue(result["runner_completed"])

    def test_retained_nodes37_crlf_and_lf_preserve_exact_seven_outcomes(self):
        from language_binding import family_spec
        fixture = json.loads((Path(__file__).parent / "fixtures" / "nodes37-325-instrumentation.json").read_text(
            encoding="utf-8"))
        raw = fixture["stdout_utf8"].encode("utf-8")
        self.assertEqual("325138b4ecc8436437c0914ab9fd488d72eb005c", fixture["execution_revision"])
        self.assertEqual(5688, fixture["stdout_bytes"])
        self.assertEqual(fixture["stdout_bytes"], len(raw))
        self.assertEqual("adcff0fe61e35d8847505ba1d51102f3b7a4d39888d4f4ce795e8517464ba3f3",
                         fixture["stdout_sha256"])
        self.assertEqual(fixture["stdout_sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual((0, ""), (fixture["host_exit"], fixture["stderr_utf8"]))
        expected = family_spec("nodes").identities
        reference = parse_outcomes(fixture["stdout_utf8"].replace("\r\n", "\n"), expected)
        for text in (raw.decode("utf-8", errors="strict"), fixture["stdout_utf8"].replace("\r\n", "\n")):
            with self.subTest(crlf="\r\n" in text):
                result = parse_outcomes(text, expected)
                self.assertEqual((7, 7, 7, 7, 0, 0, 0), tuple(result[k] for k in
                                 ("discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
                self.assertEqual(set(expected), {identity for identity, _ in result["outcomes"]})
                self.assertEqual(["43.83"], result["seconds"])
                self.assertTrue(result["runner_completed"])
                self.assertEqual(-1, result["terminal_instrumentation_code"])
                self.assertEqual(reference, result)

    def test_only_crlf_record_delimiters_are_normalized(self):
        identity = "example.Test#original"
        text = completed(record(identity, 1) + record(identity, 0))
        for delimiter in ("\r", "\r\r\n", "\v", "\f", "\x85", "\u2028", "\u2029"):
            with self.subTest(delimiter=repr(delimiter)), self.assertRaises(RuntimeError):
                parse_outcomes(text.replace("\n", delimiter), [identity])

    def test_zero_duplicate_wrong_missing_and_unknown_outcomes_fail(self):
        identity = "example.Test#original"
        cases = ("", record(identity, 1), record(identity, 1) * 2 + record(identity, 0),
                 record(identity, 1) + record("example.Test#other", 0),
                 record(identity, 1) + record(identity, 99),
                 record(identity, 1, count=2) + record(identity, 0, count=2))
        for text in cases:
            for delimiter in ("\n", "\r\n"):
                with self.subTest(text=text, delimiter=repr(delimiter)), self.assertRaises(RuntimeError):
                    parse_outcomes(completed(text).replace("\n", delimiter), [identity])

    def test_failure_error_and_skip_are_not_passes(self):
        for code, field in ((-2, "failed"), (-1, "errors"), (-3, "skipped"), (-4, "skipped")):
            identity = "example.Test#original"
            for delimiter in ("\n", "\r\n"):
                with self.subTest(code=code, delimiter=repr(delimiter)):
                    result = parse_outcomes(
                        completed(record(identity, 1) + record(identity, code)).replace("\n", delimiter), [identity])
                    self.assertEqual(0, result["passed"])
                    self.assertEqual(1, result[field])

    def test_complete_method_statuses_do_not_hide_runner_failure_or_missing_completion(self):
        identity = "example.Test#original"
        statuses = record(identity, 1) + record(identity, 0)
        cases = (
            statuses, completed(statuses).replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            completed(statuses) + "INSTRUMENTATION_FAILED: process crashed\n",
            completed(statuses) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n",
            completed(statuses).replace("Time: 0.1", "Process crashed.\nTime: 0.1"),
            completed(statuses).replace("OK (1 tests)", "OK (2 tests)"),
            completed(statuses) + "INSTRUMENTATION_CODE: -1\n",
        )
        for text in cases:
            for delimiter in ("\n", "\r\n"):
                with self.subTest(text=text, delimiter=repr(delimiter)), self.assertRaises(RuntimeError):
                    parse_outcomes(text.replace("\n", delimiter), [identity])

    def test_successful_app_mutation_then_timeout_or_rejected_output_is_restored(self):
        for failure in (subprocess.TimeoutExpired("install", 120),
                        RuntimeError("Unexpected installer output after actual installation")):
            device = FakeDevice((Path("app"), failure))
            attempts = []
            with self.assertRaises(type(failure)):
                install_pair(device, {"app": Path("app"), "test": Path("test")}, attempts.append)
            self.assertEqual([APP], attempts)
            states, errors = restore_packages(device, Path("backup"), "original", "candidate", "test")
            self.assertFalse(errors)
            self.assertEqual({APP: "original"}, device.packages)
            self.assertEqual({"verified"}, set(states.values()))

    def test_partial_test_install_is_removed_even_when_installer_raises(self):
        failure = subprocess.TimeoutExpired("test install", 120)
        device = FakeDevice((Path("test"), failure))
        attempts = []
        with self.assertRaises(subprocess.TimeoutExpired):
            install_pair(device, {"app": Path("app"), "test": Path("test")}, attempts.append)
        self.assertEqual([APP, TEST], attempts)
        _, errors = restore_packages(device, Path("backup"), "original", "candidate", "test")
        self.assertFalse(errors)
        self.assertEqual({APP: "original"}, device.packages)
        self.assertIn(("uninstall", TEST), device.calls)

    def test_restore_failure_does_not_prevent_other_owned_cleanup(self):
        device = FakeDevice((Path("backup"), RuntimeError("Restore output uncertain")))
        device.packages = {APP: "candidate", TEST: "test"}
        states, errors = restore_packages(device, Path("backup"), "original", "candidate", "test")
        self.assertEqual(1, len(errors))
        self.assertIn("Restore output uncertain", states["original_target_apk"])
        self.assertNotIn(TEST, device.packages)
        self.assertEqual("verified", states["original_target_digest"])

    def test_foreign_packages_are_not_overwritten_or_removed(self):
        device = FakeDevice()
        device.packages = {APP: "foreign", TEST: "foreign-test"}
        _, errors = restore_packages(device, Path("backup"), "original", "candidate", "test")
        self.assertEqual(3, len(errors))
        self.assertEqual([], device.calls)

    def test_seeded_chat_timeout_restores_apk_but_retains_lane_for_uncertain_user_state(self):
        device = FakeDevice()
        device.seeded_fixture_rows = {"chat503001": ["contact503002", "message503115"]}
        device.original_preferences = {"onboarding": False, "draft": "original draft"}
        device.actual_preferences = {"onboarding": True, "draft": "fixture draft"}
        device.actual_window = "900x900"
        device.packages = {APP: "candidate", TEST: "test"}
        device.active = [f"ActiveInstrumentation{{abc {TEST}/androidx.test.runner.AndroidJUnitRunner 1 procs}}"]
        stream = Mock()
        stream.poll.return_value = None
        process = Mock()
        process.wait.side_effect = [subprocess.TimeoutExpired("instrument", 600), 0]
        ledger = Mock()
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                side_effect=[stream, process]), patch("shared_device_runner.selected_methods",
                return_value=["example.Test#original"]), patch("builtins.print"):
            with self.assertRaises(BaseExceptionGroup) as caught, native_lane(ledger, device):
                try:
                    execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
                finally:
                    _, errors = restore_packages(device, Path("backup"), "original", "candidate", "test")
                    self.assertFalse(errors)
            recovery = json.loads((Path(directory) / "device-shared37-chat-recovery.json").read_text())
            self.assertEqual("UNCERTAIN_APPLICATION_STATE_NATIVE_LANE_RETAINED", recovery["status"])
            self.assertFalse(recovery["fixture_preferences_and_window_restoration_verified"])
            self.assertFalse(recovery["original_fixture_rows_and_preferences_snapshot_available"])
            self.assertEqual(["example.Test#original"], recovery["original_identities"])
        self.assertIsInstance(caught.exception.exceptions[0], TimeoutError)
        self.assertEqual([], device.active)
        self.assertEqual({APP: "original"}, device.packages)
        self.assertLess(device.calls.index(("shell", "am force-stop " + APP)),
                        device.calls.index(("install", Path("backup"))))
        self.assertTrue(device.application_state_uncertain)
        self.assertEqual({"chat503001": ["contact503002", "message503115"]}, device.seeded_fixture_rows)
        self.assertNotEqual(device.original_preferences, device.actual_preferences)
        self.assertEqual("900x900", device.actual_window)
        ledger.release_hard_lock.assert_not_called()
        process.kill.assert_called_once()

    def test_foreign_guest_is_not_stopped_and_unresolved_lane_is_retained(self):
        device = FakeDevice()
        device.active = ["ActiveInstrumentation{abc other.test/Runner 1 procs}"]
        with self.assertRaises(RuntimeError):
            device.finish_owned_instrumentation("candidate", "test")
        self.assertEqual([], device.calls)
        ledger = Mock()
        primary = RuntimeError("Original primary failure")
        with self.assertRaises(BaseExceptionGroup) as caught:
            with native_lane(ledger, device):
                raise primary
        self.assertIs(primary, caught.exception.exceptions[0])
        ledger.release_hard_lock.assert_not_called()

    def test_guest_cleanup_failure_keeps_timeout_and_retains_lane(self):
        device = FakeDevice()
        device.packages = {APP: "candidate", TEST: "test"}
        device.active = [f"ActiveInstrumentation{{abc {TEST}/androidx.test.runner.AndroidJUnitRunner 1 procs}}"]
        device.finish_owned_instrumentation = Mock(side_effect=RuntimeError("Guest stop failed"))
        stream = Mock()
        stream.poll.return_value = None
        process = Mock()
        process.wait.side_effect = [subprocess.TimeoutExpired("instrument", 600), 0]
        ledger = Mock()
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                side_effect=[stream, process]), patch("shared_device_runner.selected_methods",
                return_value=["example.Test#original"]), patch("builtins.print"):
            with self.assertRaises(BaseExceptionGroup) as caught:
                with native_lane(ledger, device):
                    execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
        nested = caught.exception.exceptions[0]
        self.assertIsInstance(nested, BaseExceptionGroup)
        self.assertIsInstance(nested.exceptions[0], TimeoutError)
        self.assertIn("Guest stop failed", str(nested.exceptions[1]))
        self.assertEqual({APP: "candidate", TEST: "test"}, device.packages)
        ledger.release_hard_lock.assert_not_called()

    def test_unrestored_preserved_target_retains_lane_even_if_guest_is_quiescent(self):
        device = FakeDevice()
        device.packages = {APP: "candidate", TEST: "test"}
        device.original_target_digest = "original"
        ledger = Mock()
        with self.assertRaises(BaseExceptionGroup), native_lane(ledger, device):
            pass
        ledger.release_hard_lock.assert_not_called()

    def test_new_invocation_cannot_reacquire_its_own_uncertain_lane(self):
        class RetainedLedger:
            owner = None

            def acquire_hard_lock(self, resource, owner, payload, now):
                if self.owner is not None and owner != self.owner:
                    raise RuntimeError("Retained native lane collision")
                self.owner = owner

            def release_hard_lock(self, resource, owner):
                self.owner = None

        ledger = RetainedLedger()
        first = FakeDevice()
        first.application_state_uncertain = True
        with self.assertRaises(BaseExceptionGroup), native_lane(ledger, first):
            pass
        retained = ledger.owner
        with self.assertRaisesRegex(RuntimeError, "Retained native lane collision"):
            with native_lane(ledger, FakeDevice()):
                self.fail("A new invocation must not replace the prior uncertain recovery lane")
        self.assertEqual(retained, ledger.owner)

    def test_native_lane_payload_binds_wp_and_operation_owner_for_future_packaging(self):
        from shared_device_runner import OWNER
        device, ledger = FakeDevice(), Mock()
        with native_lane(ledger, device):
            args = ledger.acquire_hard_lock.call_args.args
            self.assertEqual("native-execution", args[0])
            self.assertEqual(device.native_lane_owner, args[1])
            self.assertNotEqual(OWNER, device.native_lane_owner)
            self.assertEqual(OWNER + ":shared37:", device.native_lane_owner[:-32])
            self.assertEqual(device.native_lane_owner[-32:], uuid.UUID(device.native_lane_owner[-32:]).hex)
            self.assertEqual("WP-503", args[2]["wp"])
            self.assertEqual(OWNER, args[2]["canonical_session_id"])
        ledger.release_hard_lock.assert_called_once_with("native-execution", device.native_lane_owner)

    def test_receipt_write_failure_does_not_skip_owned_host_or_guest_stop(self):
        device = FakeDevice()
        device.packages = {APP: "candidate", TEST: "test"}
        device.active = [f"ActiveInstrumentation{{abc {TEST}/androidx.test.runner.AndroidJUnitRunner 1 procs}}"]
        stream, process = Mock(), Mock()
        stream.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("instrument", 600), 0]
        ledger = Mock()
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                side_effect=[stream, process]), patch("shared_device_runner.selected_methods",
                return_value=["example.Test#original"]), patch("pathlib.Path.write_text",
                side_effect=OSError("Recovery receipt disk full")), patch("builtins.print"):
            with self.assertRaises(BaseExceptionGroup) as caught, native_lane(ledger, device):
                execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
        process.kill.assert_called_once()
        self.assertEqual(2, process.wait.call_count)
        self.assertIn(("shell", "am force-stop " + APP), device.calls)
        self.assertEqual([], device.active)
        self.assertTrue(device.application_state_uncertain)
        self.assertIn("Recovery receipt disk full", repr(caught.exception))
        ledger.release_hard_lock.assert_not_called()

    def test_completed_host_quiescent_guest_transcript_read_failure_retains_lane(self):
        device = FakeDevice()
        device.packages = {APP: "candidate", TEST: "test"}
        stream, process = Mock(), Mock()
        stream.poll.return_value = None
        process.wait.return_value = 0
        ledger = Mock()
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                side_effect=[stream, process]), patch("shared_device_runner.selected_methods",
                return_value=["example.Test#original"]), patch("pathlib.Path.read_text",
                side_effect=OSError("Transcript cannot be read")), patch("builtins.print"):
            with self.assertRaises(BaseExceptionGroup) as caught, native_lane(ledger, device):
                execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
        self.assertIsInstance(caught.exception.exceptions[0], OSError)
        self.assertTrue(device.application_state_uncertain)
        self.assertEqual([], device.active)
        self.assertEqual([], device.calls)
        process.kill.assert_not_called()
        ledger.release_hard_lock.assert_not_called()

    def test_normal_positive_completion_clears_uncertainty_and_releases_operation(self):
        device, ledger = FakeDevice(), Mock()
        device.packages = {APP: "candidate", TEST: "test"}
        stream, process = Mock(), Mock()
        stream.poll.return_value = None
        process.wait.return_value = 0

        def spawn(command, **kwargs):
            if command[1] == "logcat":
                return stream
            self.assertTrue(device.application_state_uncertain)
            return process

        identity = "example.Test#original"
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                side_effect=spawn), patch("shared_device_runner.selected_methods", return_value=[identity]), \
                patch("pathlib.Path.read_text", return_value=completed(record(identity, 1) + record(identity, 0))), \
                patch("builtins.print"):
            with native_lane(ledger, device):
                result = execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
        self.assertEqual(1, result["passed"])
        self.assertFalse(device.application_state_uncertain)
        self.assertFalse(device.fixture_completion_untrusted)
        self.assertEqual([], device.calls)
        process.wait.assert_called_once_with(timeout=600)
        process.kill.assert_not_called()
        ledger.release_hard_lock.assert_called_once_with("native-execution", device.native_lane_owner)

    def test_nonpositive_or_forced_completion_and_decode_failure_retain_lane(self):
        identity = "example.Test#original"
        positive = completed(record(identity, 1) + record(identity, 0))
        cases = (
            ("decode", 0, UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid"), False),
            ("malformed", 0, "no completion proof", False),
            ("nonzero-host", 1, positive, False),
            ("forced-guest", 0, positive, True),
            ("failed-test", 0, completed(record(identity, 1) + record(identity, -2)), False),
            ("skipped-test", 0, completed(record(identity, 1) + record(identity, -3)), False),
        )
        for name, code, transcript, active in cases:
            with self.subTest(name=name):
                device, ledger = FakeDevice(), Mock()
                device.packages = {APP: "candidate", TEST: "test"}
                if active:
                    device.active = [f"ActiveInstrumentation{{abc {TEST}/androidx.test.runner.AndroidJUnitRunner 1 procs}}"]
                stream, process = Mock(), Mock()
                stream.poll.return_value = None
                process.wait.return_value = code
                read = {"side_effect": transcript} if isinstance(transcript, Exception) else {"return_value": transcript}
                with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                        side_effect=[stream, process]), patch("shared_device_runner.selected_methods",
                        return_value=[identity]), patch("pathlib.Path.read_text", **read), patch("builtins.print"):
                    with self.assertRaises(BaseExceptionGroup), native_lane(ledger, device):
                        execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
                    self.assertTrue((Path(directory) / "device-shared37-chat-recovery.json").exists())
                self.assertTrue(device.application_state_uncertain)
                self.assertTrue(device.fixture_completion_untrusted)
                ledger.release_hard_lock.assert_not_called()

    def test_actual_host_exit_is_persisted_before_failed_positive_proof(self):
        identity = "example.Test#original"
        for host_exit, status in ((0, -2), (7, 0)):
            with self.subTest(host_exit=host_exit, status=status):
                device, ledger = FakeDevice(), Mock()
                device.packages = {APP: "candidate", TEST: "test"}
                stream, process = Mock(), Mock()
                stream.poll.return_value = None
                process.wait.return_value = host_exit
                raw = completed(record(identity, 1) + record(identity, status))
                read_text = Path.read_text

                def parse_after_exit_receipt(text, expected):
                    recovery = json.loads(read_text(device.fixture_recovery_path))
                    self.assertEqual(host_exit, recovery["host_instrumentation_process_exit"])
                    self.assertFalse(recovery["fixture_preferences_and_window_restoration_verified"])
                    parsed = parse_outcomes(text, expected)
                    self.assertEqual(-1, parsed["terminal_instrumentation_code"])
                    return parsed

                with tempfile.TemporaryDirectory() as directory, \
                        patch("shared_device_runner.subprocess.Popen", side_effect=[stream, process]), \
                        patch("shared_device_runner.selected_methods", return_value=[identity]), \
                        patch("shared_device_runner.parse_outcomes", side_effect=parse_after_exit_receipt), \
                        patch("pathlib.Path.read_text", return_value=raw), patch("builtins.print"):
                    with self.assertRaises(BaseExceptionGroup), native_lane(ledger, device):
                        execute_cohort(device, Path(directory), "nodes", {}, "candidate", "test")
                    receipt = Path(directory) / "device-shared37-nodes-recovery.json"
                    recovery = json.loads(read_text(receipt))
                    self.assertEqual(host_exit, recovery["host_instrumentation_process_exit"])
                    self.assertEqual("UNCERTAIN_APPLICATION_STATE_NATIVE_LANE_RETAINED", recovery["status"])
                    self.assertFalse(recovery["fixture_preferences_and_window_restoration_verified"])
                    self.assertFalse((Path(directory) / "device-shared37-nodes.json").exists())
                self.assertTrue(device.application_state_uncertain)
                ledger.release_hard_lock.assert_not_called()
                process.kill.assert_not_called()

    def test_exit_receipt_failure_still_finishes_owned_guest_and_retains_lane(self):
        device, ledger = FakeDevice(), Mock()
        device.packages = {APP: "candidate", TEST: "test"}
        device.active = [f"ActiveInstrumentation{{abc {TEST}/androidx.test.runner.AndroidJUnitRunner 1 procs}}"]
        stream, process = Mock(), Mock()
        stream.poll.return_value = None
        process.wait.return_value = 0
        with tempfile.TemporaryDirectory() as directory, \
                patch("shared_device_runner.subprocess.Popen", side_effect=[stream, process]), \
                patch("shared_device_runner.selected_methods", return_value=["example.Test#original"]), \
                patch("pathlib.Path.write_text", side_effect=OSError("Exit receipt unavailable")), \
                patch("builtins.print"):
            with self.assertRaises(BaseExceptionGroup), native_lane(ledger, device):
                execute_cohort(device, Path(directory), "nodes", {}, "candidate", "test")
        self.assertEqual(0, device.fixture_recovery_context["host_instrumentation_process_exit"])
        self.assertTrue(device.application_state_uncertain)
        self.assertEqual([], device.active)
        self.assertIn(("shell", "am force-stop " + APP), device.calls)
        stream.terminate.assert_called_once()
        process.wait.assert_called_once_with(timeout=600)
        process.kill.assert_not_called()
        ledger.release_hard_lock.assert_not_called()

    def test_spawn_and_preproof_interruptions_retain_uncertainty(self):
        for failure in ("spawn", "wait", "log-consumer"):
            with self.subTest(failure=failure):
                device, ledger = FakeDevice(), Mock()
                device.packages = {APP: "candidate", TEST: "test"}
                stream, process = Mock(), Mock()
                stream.poll.side_effect = [None, 1] if failure == "log-consumer" else None
                stream.poll.return_value = None
                process.wait.side_effect = KeyboardInterrupt("interrupted") if failure == "wait" else None
                process.wait.return_value = 0
                spawned = OSError("instrumentation spawn failed") if failure == "spawn" else process
                with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                        side_effect=[stream, spawned]), patch("shared_device_runner.selected_methods",
                        return_value=["example.Test#original"]), patch("builtins.print"):
                    with self.assertRaises(BaseExceptionGroup), native_lane(ledger, device):
                        execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
                self.assertTrue(device.application_state_uncertain)
                ledger.release_hard_lock.assert_not_called()

    def test_uncertain_prior_fixture_blocks_subsequent_cohort_before_spawn(self):
        device = FakeDevice()
        device.application_state_uncertain = True
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen") as spawn:
            with self.assertRaisesRegex(RuntimeError, "Prior fixture state is uncertain"):
                execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
        spawn.assert_not_called()

    def test_host_kill_failure_still_attempts_bounded_wait_and_owned_guest_stop(self):
        device, ledger = FakeDevice(), Mock()
        device.packages = {APP: "candidate", TEST: "test"}
        device.active = [f"ActiveInstrumentation{{abc {TEST}/androidx.test.runner.AndroidJUnitRunner 1 procs}}"]
        stream, process = Mock(), Mock()
        stream.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("instrument", 600), 0]
        process.kill.side_effect = OSError("host kill failed")
        with tempfile.TemporaryDirectory() as directory, patch("shared_device_runner.subprocess.Popen",
                side_effect=[stream, process]), patch("shared_device_runner.selected_methods",
                return_value=["example.Test#original"]), patch("builtins.print"):
            with self.assertRaises(BaseExceptionGroup) as caught, native_lane(ledger, device):
                execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
        self.assertIn("host kill failed", repr(caught.exception))
        self.assertEqual([600, 15], [call.kwargs["timeout"] for call in process.wait.call_args_list])
        self.assertIn(("shell", "am force-stop " + APP), device.calls)
        self.assertTrue(device.application_state_uncertain)
        ledger.release_hard_lock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
