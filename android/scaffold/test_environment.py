"""Android-only WP-002 fail-closed candidate environment assertions; no host credential values used."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from check_environment import allowed_environment_names, check_environment


class EnvironmentIsolationTests(unittest.TestCase):
    def test_toolchain_and_private_cache_names_are_allowed(self):
        allowed = allowed_environment_names()
        self.assertTrue({"java_home", "android_home", "android_user_home", "gradle_user_home"} <= allowed)

    def test_known_and_unknown_secret_names_are_not_allowed(self):
        allowed = allowed_environment_names()
        for name in ("GH_TOKEN", "COPILOT_TOKEN", "SPEECH_KEY", "CUSTOM_AUTHENTICATION"):
            self.assertNotIn(name.casefold(), allowed)

    def test_inherited_secret_is_rejected_without_echoing_its_value(self):
        with patch.dict(os.environ, {"SPEECH_KEY": "test-only-not-a-credential"}, clear=True):
            with self.assertRaises(ValueError) as failure:
                check_environment()
        self.assertIn("SPEECH_KEY", str(failure.exception))
        self.assertNotIn("test-only-not-a-credential", str(failure.exception))

    def test_missing_required_environment_cannot_return_ready(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "JAVA_HOME"):
                check_environment()

    def test_platform_revision_is_exact_and_other_readiness_checks_remain_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk = root / "sdk"
            environment = {
                "JAVA_HOME": str(root / "jdk"),
                "ANDROID_HOME": str(sdk),
                "GRADLE_USER_HOME": str(root / "gradle"),
                "ANDROID_USER_HOME": str(root / "android-user"),
            }
            for path, package_id, revision in (
                ("platforms/android-37.2", "platforms;android-37.2", (2, 0, 0)),
                ("build-tools/37.0.0", "build-tools;37.0.0", (37, 0, 0)),
            ):
                target = sdk / path / "package.xml"
                target.parent.mkdir(parents=True)
                repository = ET.Element("repository")
                package = ET.SubElement(repository, "localPackage", {"path": package_id})
                version = ET.SubElement(package, "revision")
                for part, value in zip(("major", "minor", "micro"), revision, strict=True):
                    ET.SubElement(version, part).text = str(value)
                target.write_bytes(ET.tostring(repository))
            jar = sdk / "platforms/android-37.2/android.jar"
            jar.touch()
            license_file = sdk / "licenses/android-sdk-license"
            license_file.parent.mkdir()
            license_file.touch()
            metadata = sdk / "platforms/android-37.2/package.xml"
            result = subprocess.CompletedProcess([], 0, "", 'openjdk version "21.0.12.1"')
            with patch.dict(os.environ, environment, clear=True), patch(
                "check_environment.subprocess.run", return_value=result
            ):
                self.assertEqual("37.2", check_environment()["compile_sdk"])
                for major, micro in (("1", "0"), ("3", "0"), ("2", "1")):
                    with self.subTest(platform_revision=(major, 0, micro)):
                        tree = ET.parse(metadata)
                        tree.find("localPackage/revision/major").text = major
                        tree.find("localPackage/revision/micro").text = micro
                        tree.write(metadata)
                        with self.assertRaisesRegex(ValueError, "unexpected revision"):
                            check_environment()
                tree = ET.parse(metadata)
                tree.find("localPackage/revision/major").text = "2"
                tree.find("localPackage/revision/micro").text = "0"
                tree.write(metadata)
                jar.unlink()
                with self.assertRaisesRegex(ValueError, "android.jar is absent"):
                    check_environment()
                jar.touch()
                license_file.unlink()
                with self.assertRaisesRegex(ValueError, "license acceptance record is absent"):
                    check_environment()


if __name__ == "__main__":
    unittest.main()
