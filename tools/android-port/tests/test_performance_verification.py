"""AndroidOnly: WP-504 Reject debug, wrong-package, missing-profileable and fixture-leaked APK declarations."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from controller.errors import PortError
from controller.performance import FIXTURE, verify_manifest
from controller import ci


class PerformanceVerificationTests(unittest.TestCase):
    def badging(self, benchmark=True):
        return "package: name='com.meshcoreone.android" + (".benchmark" if benchmark else "") + "'\nsdkVersion:'31'\ntargetSdkVersion:'37'\n"

    def tree(self):
        return (
            "E: application\n  A: android:debuggable(0x0101000f)=(type 0x12)0x0\n"
            "  A: android:testOnly(0x01010272)=(type 0x12)0xffffffff\n"
            "  E: profileable\n    A: android:shell(0x0101056a)=(type 0x12)0xffffffff\n"
            "  E: activity\n    A: android:name=\"" + FIXTURE + "\"\n"
            "  E: meta-data\n    A: android:name=\"wp504.profileSource\"\n"
            "    A: android:value(0x01010024)=(type 0x12)0x0\n"
            "  E: meta-data\n    A: android:name=\"wp504.applicationProfileInput\"\n"
            "    A: android:value(0x01010024)=(type 0x12)0x0\n"
        )

    def test_actual_declared_benchmark_build_tasks(self):
        self.assertEqual(ci.TASKS["benchmark"], [":benchmark:verifyBenchmarkHarness", "validateModuleGraph"])

    def test_nondebuggable_profileable_test_only_target(self):
        result = verify_manifest(self.badging(), self.tree(), benchmark=True)
        self.assertFalse(result["debuggable"])
        self.assertEqual(result["package"], "com.meshcoreone.android.benchmark")

    def test_actual_aapt37_minimum_sdk_field(self):
        verify_manifest(self.badging().replace("sdkVersion:", "minSdkVersion:"), self.tree(), benchmark=True)

    def test_debug_target_rejected(self):
        with self.assertRaisesRegex(PortError, "debuggable"):
            verify_manifest(self.badging(), self.tree().replace("0x0101000f)=(type 0x12)0x0", "0x0101000f)=(type 0x12)0xffffffff"), benchmark=True)

    def test_profileable_shell_cannot_come_from_another_element(self):
        tree = self.tree().replace("  E: profileable\n", "  E: unrelated\n")
        with self.assertRaisesRegex(PortError, "profileable"):
            verify_manifest(self.badging(), tree, benchmark=True)

    def test_non_test_only_target_rejected(self):
        with self.assertRaisesRegex(PortError, "test-only"):
            verify_manifest(self.badging(), self.tree().replace("android:testOnly", "android:unrelated"), benchmark=True)

    def test_product_package_cannot_be_used_as_fixture_target(self):
        with self.assertRaisesRegex(PortError, "package"):
            verify_manifest(self.badging(False), self.tree(), benchmark=True)

    def test_sdk_substitution_rejected(self):
        with self.assertRaisesRegex(PortError, "SDK"):
            verify_manifest(self.badging().replace("'37'", "'36'"), self.tree(), benchmark=True)

    def test_release_fixture_leak_rejected(self):
        with self.assertRaisesRegex(PortError, "leaked"):
            verify_manifest(self.badging(False), self.tree(), benchmark=False)

    def test_plain_release_is_not_claimed_profileable(self):
        result = verify_manifest(self.badging(False), "E: application\n", benchmark=False)
        self.assertEqual(result["package"], "com.meshcoreone.android")

    def test_profile_collection_variant_has_true_typed_identity(self):
        tail = '"wp504.profileSource"\n    A: android:value(0x01010024)=(type 0x12)'
        tree = self.tree().replace(tail + "0x0", tail + "0xffffffff")
        verify_manifest(self.badging(), tree, benchmark=True, profile_source=True)
        with self.assertRaisesRegex(PortError, "variant identity"):
            verify_manifest(self.badging(), tree, benchmark=True)

    def test_missing_variant_identity_rejected(self):
        with self.assertRaisesRegex(PortError, "variant identity"):
            verify_manifest(self.badging(), self.tree().replace("wp504.profileSource", "unrelated"), benchmark=True)

    def test_application_input_flag_cannot_be_substituted(self):
        tail = '"wp504.applicationProfileInput"\n    A: android:value(0x01010024)=(type 0x12)'
        tree = self.tree().replace(tail + "0x0", tail + "0xffffffff")
        verify_manifest(self.badging(), tree, benchmark=True, application_profile_input=True)
        with self.assertRaisesRegex(PortError, "applicationProfileInput"):
            verify_manifest(self.badging(), tree, benchmark=True)

    def test_string_instead_of_boolean_identity_rejected(self):
        with self.assertRaisesRegex(PortError, "variant identity"):
            verify_manifest(self.badging(), self.tree().replace("(type 0x12)0x0\n", '"false"\n'), benchmark=True)

    def test_test_only_release_rejected(self):
        with self.assertRaisesRegex(PortError, "test-only"):
            verify_manifest(self.badging(False),
                "E: application\n A: android:testOnly(0x01010272)=(type 0x12)0xffffffff\n", benchmark=False)
