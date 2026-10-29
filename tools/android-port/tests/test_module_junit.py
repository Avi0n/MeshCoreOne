"""AndroidOnly: WP-003 Validate active-module JUnit directly in build outputs."""

import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from controller.errors import PortError
from controller.module_junit import check_module_tests, module_method_inventory, module_sources
from test_ci_evidence import junit_report


class ModuleJUnitTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.command("init", "--quiet")
        (self.repo / ".gitignore").write_text("**/build/\n", encoding="utf-8")
        self.source("core/model", "src/main/kotlin/Model.kt", "class Model\n")
        self.source("core/model", "src/test/kotlin/ModelTest.kt", "class ModelTest\n")
        self.source("core/model", "build.gradle.kts", 'plugins { id("mesh.jvm.library") }\n')
        self.source("core/database", "src/main/kotlin/Database.kt", "class Database\n")
        self.source("core/database", "src/test/java/DatabaseTest.java", "class DatabaseTest {}\n")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "fixture")
        self.report("core/model")
        self.report("core/database", runner="testDebugUnitTest")

    def command(self, *arguments):
        return subprocess.run(
            ["git", "-c", "core.autocrlf=false", "-c", "core.hooksPath=" + str(self.root / "hooks"),
             "-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c",
             "user.email=fixture@example.invalid", "-C", str(self.repo), *arguments],
            capture_output=True, text=True, check=True, timeout=30,
        ).stdout.strip()

    def source(self, module, relative, content):
        path = self.repo / "android" / module / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def report(self, module, count=3, runner="test"):
        path = self.repo / "android" / module / "build" / "test-results" / runner / "TEST-fixture.xml"
        junit_report(path, count)
        return path

    def test_committed_sources_select_active_modules(self):
        sources = module_sources(self.repo)
        self.assertEqual(set(sources), {"core/model", "core/database"})
        self.assertIn("android/core/model/src/main/kotlin/Model.kt", sources["core/model"])

    def test_live_reports_are_validated_without_copying(self):
        result = check_module_tests(self.repo)
        self.assertEqual(result["core/model"]["passed"], 3)
        self.assertEqual(result["core/database"]["passed"], 3)
        self.assertFalse((self.root / "evidence").exists())

    def test_method_inventory_includes_separately_collected_contracts_and_app(self):
        self.report("app", runner="testDebugUnitTest")
        self.report("core/contracts")
        inventory = module_method_inventory(self.repo, check_module_tests(self.repo))
        self.assertEqual(set(inventory), {"app", "core/contracts", "core/model", "core/database"})
        self.assertEqual(len(inventory["core/contracts"]), 3)

    def test_missing_or_ambiguous_runner_rejects(self):
        database = self.repo / "android/core/database/build/test-results/testDebugUnitTest/TEST-fixture.xml"
        database.unlink()
        with self.assertRaisesRegex(PortError, "Missing/ambiguous"):
            check_module_tests(self.repo)
        self.report("core/database", runner="testDebugUnitTest")
        self.report("core/model", runner="testDebugUnitTest")
        with self.assertRaisesRegex(PortError, "Missing/ambiguous"):
            check_module_tests(self.repo)

    def test_zero_failed_skipped_and_malformed_reports_reject(self):
        for kind in ("zero", "failure", "error", "skipped", "malformed"):
            with self.subTest(kind=kind):
                path = self.report("core/model", 0 if kind == "zero" else 3)
                if kind == "malformed":
                    path.write_text("<testsuite>", encoding="utf-8")
                elif kind != "zero":
                    root = ET.fromstring(path.read_bytes())
                    ET.SubElement(root.find("testcase"), kind)
                    path.write_bytes(ET.tostring(root))
                with self.assertRaises(PortError):
                    check_module_tests(self.repo)

    def test_dirty_compiled_inputs_reject(self):
        self.source("core/model", "src/main/kotlin/Model.kt", "class ChangedModel\n")
        with self.assertRaisesRegex(PortError, "differ"):
            check_module_tests(self.repo)


if __name__ == "__main__":
    unittest.main()
