"""AndroidOnly: WP-503 Deterministic teardown-observer success, boundary and failure cases."""
import subprocess
import unittest
from unittest.mock import patch

from native_device_cleanup import wait_for_transport_exit


class NativeDeviceCleanupTest(unittest.TestCase):
    def setUp(self):
        self.clock = patch("native_device_cleanup.time.monotonic").start()
        self.sleep = patch("native_device_cleanup.time.sleep").start()
        self.run = patch("native_device_cleanup.subprocess.run").start()
        self.addCleanup(patch.stopall)

    def result(self, transports=""):
        return subprocess.CompletedProcess([], 0, "List of devices attached\n" + transports)

    def test_absent_owned_transport_does_not_require_other_users_devices_to_exit(self):
        self.clock.return_value = 0
        self.run.return_value = self.result("emulator-5554\tdevice\n")
        self.assertEqual({"serial": "emulator-5714", "observations": 1,
                          "elapsed_ms": 0, "absent": True},
                         wait_for_transport_exit("adb", "emulator-5714"))
        self.sleep.assert_not_called()
        self.assertEqual(["adb", "devices"], self.run.call_args.args[0])
        self.assertTrue(self.run.call_args.kwargs["check"])

    def test_transient_offline_registration_is_observed_until_actual_removal(self):
        self.clock.side_effect = [0, 0, 0, 0.1, 0.1]
        self.run.side_effect = [self.result("emulator-5714\toffline\n"), self.result()]
        self.assertEqual(2, wait_for_transport_exit("adb", "emulator-5714")["observations"])
        self.sleep.assert_called_once_with(0.1)

    def test_still_registered_at_deadline_fails(self):
        self.clock.side_effect = [0, 0, 10, 10]
        self.run.return_value = self.result("emulator-5714\toffline\n")
        with self.assertRaisesRegex(TimeoutError, "remained registered"):
            wait_for_transport_exit("adb", "emulator-5714")

    def test_absence_observed_after_deadline_is_not_a_pass(self):
        self.clock.side_effect = [0, 0, 10.001, 10.001]
        self.run.return_value = self.result()
        with self.assertRaises(TimeoutError):
            wait_for_transport_exit("adb", "emulator-5714")

    def test_absence_at_exact_deadline_passes(self):
        self.clock.side_effect = [0, 0, 10]
        self.run.return_value = self.result()
        self.assertEqual(10000, wait_for_transport_exit("adb", "emulator-5714")["elapsed_ms"])

    def test_adb_failure_propagates(self):
        self.clock.return_value = 0
        self.run.side_effect = subprocess.CalledProcessError(1, ["adb", "devices"])
        with self.assertRaises(subprocess.CalledProcessError):
            wait_for_transport_exit("adb", "emulator-5714")

    def test_missing_transport_list_header_fails(self):
        self.clock.return_value = 0
        self.run.return_value = subprocess.CompletedProcess([], 0, "")
        with self.assertRaisesRegex(ValueError, "header"):
            wait_for_transport_exit("adb", "emulator-5714")

    def test_duplicate_owned_transport_fails(self):
        self.clock.return_value = 0
        self.run.return_value = self.result("emulator-5714\tdevice\nemulator-5714\toffline\n")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            wait_for_transport_exit("adb", "emulator-5714")


if __name__ == "__main__":
    unittest.main()
