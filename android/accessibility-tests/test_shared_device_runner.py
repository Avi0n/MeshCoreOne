"""AndroidOnly: WP-503 Fail-closed discovery for the missing-only shared-device adapter."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from shared_device_runner import (
    APP, TEST, Device, execute_cohort, install_pair, install_succeeded,
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

    def test_zero_duplicate_wrong_missing_and_unknown_outcomes_fail(self):
        identity = "example.Test#original"
        cases = ("", record(identity, 1), record(identity, 1) * 2 + record(identity, 0),
                 record(identity, 1) + record("example.Test#other", 0),
                 record(identity, 1) + record(identity, 99))
        for text in cases:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                parse_outcomes(completed(text), [identity])

    def test_failure_error_and_skip_are_not_passes(self):
        for code, field in ((-2, "failed"), (-1, "errors"), (-3, "skipped"), (-4, "skipped")):
            identity = "example.Test#original"
            result = parse_outcomes(completed(record(identity, 1) + record(identity, code)), [identity])
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
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                parse_outcomes(text, [identity])

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

    def test_host_timeout_finishes_only_owned_guest_before_restore_and_lane_release(self):
        device = FakeDevice()
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
            with self.assertRaises(TimeoutError), native_lane(ledger, device):
                try:
                    execute_cohort(device, Path(directory), "chat", {}, "candidate", "test")
                finally:
                    _, errors = restore_packages(device, Path("backup"), "original", "candidate", "test")
                    self.assertFalse(errors)
        self.assertEqual([], device.active)
        self.assertEqual({APP: "original"}, device.packages)
        self.assertLess(device.calls.index(("shell", "am force-stop " + APP)),
                        device.calls.index(("install", Path("backup"))))
        ledger.release_hard_lock.assert_called_once()
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


if __name__ == "__main__":
    unittest.main()
