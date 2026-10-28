import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from controller.errors import PortError
from oracle.workflow_scope import ALL_SCOPE_INPUTS, SCOPES, ZERO, classify, endpoints, evaluate

BASE = "a" * 40
HEAD = "b" * 40


class WorkflowScopeTests(unittest.TestCase):
    def test_installed_runtime_inputs_select_controller_verification(self):
        for path in (
            "tools/android-port/local/reserve.py",
            "tools/android-port/local/test_reserve.py",
            "tools/android-port/inventory_rules.py",
        ):
            with self.subTest(path=path):
                self.assertEqual(classify([path]), {
                    scope: scope == "controller" for scope in SCOPES
                })

    def test_feature_changes_schedule_scaffold_without_unrelated_oracles(self):
        for paths in (
            ["android/feature/content/src/main/kotlin/ContentScreen.kt",
             "android/feature/location/src/test/kotlin/LocationTest.kt"],
            ["android/feature/navigation/src/main/kotlin/NavigationGraph.kt"],
        ):
            with self.subTest(paths=paths):
                result = classify(paths)
                self.assertTrue(result["scaffold"])
                self.assertFalse(result["external-oracle"])
                self.assertFalse(result["backup"])
                self.assertFalse(result["protocol"])

    def test_codec_source_and_harness_schedule_codec(self):
        for path in (
            "MC1Services/Sources/MC1Services/Services/AppBackupEnvelope.swift",
            "tools/android-port/oracle/codec_harness.py",
        ):
            with self.subTest(path=path):
                self.assertTrue(classify([path])["external-oracle"])

    def test_service_production_tests_and_build_select_native_verification(self):
        for path in (
            "android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/diagnostics/RxLogService.kt",
            "android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/diagnostics/RxLogServiceReprocessTest.kt",
            "android/core/services/build.gradle.kts",
        ):
            with self.subTest(path=path):
                self.assertEqual(classify([path]), {
                    scope: scope == "scaffold" for scope in SCOPES
                })

    def test_issue194_ble_production_and_tests_select_native_verification(self):
        paths = [
            "android/core/ble/src/main/kotlin/com/meshcoreone/android/core/ble/BleTransport.kt",
            "android/core/ble/src/test/kotlin/com/meshcoreone/android/core/ble/BleWriteTest.kt",
            "docs/android/deviations/WP-205.md",
        ]
        expected = {scope: scope == "scaffold" for scope in SCOPES}
        self.assertEqual(classify(paths), expected)
        with patch("oracle.workflow_scope.changed_paths", return_value=paths):
            result = evaluate(Path.cwd(), "push", {"before": BASE, "after": HEAD})
        self.assertEqual(result["scopes"], expected)
        self.assertFalse(result["fail_safe"])
        self.assertEqual(result["paths"], paths)
        self.assertEqual((result["base"], result["head"]), (BASE, HEAD))
        self.assertEqual(result["diff_base"], BASE)

    def test_pull_request_scope_ignores_base_only_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)

            def git(*args):
                return subprocess.run(
                    ["git", "-C", str(repo), *args],
                    check=True, capture_output=True, text=True,
                ).stdout.strip()

            git("init", "--quiet", "-b", "main")
            git("config", "user.email", "scope-test@example.invalid")
            git("config", "user.name", "Scope Test")
            (repo / "README.md").write_text("common\n", encoding="utf-8")
            git("add", "README.md")
            git("commit", "--quiet", "-m", "common")
            common = git("rev-parse", "HEAD")

            git("checkout", "--quiet", "-b", "feature")
            feature_path = Path("android/core/protocol/src/main/kotlin/Protocol.kt")
            (repo / feature_path).parent.mkdir(parents=True)
            (repo / feature_path).write_text("feature\n", encoding="utf-8")
            git("add", str(feature_path))
            git("commit", "--quiet", "-m", "protocol change")
            head = git("rev-parse", "HEAD")

            git("checkout", "--quiet", "main")
            base_only_path = Path("android/app/src/main/kotlin/App.kt")
            (repo / base_only_path).parent.mkdir(parents=True)
            (repo / base_only_path).write_text("base only\n", encoding="utf-8")
            git("add", str(base_only_path))
            git("commit", "--quiet", "-m", "base-only app change")
            base = git("rev-parse", "HEAD")

            result = evaluate(repo, "pull_request", {"pull_request": {
                "base": {"sha": base}, "head": {"sha": head},
            }})

        self.assertFalse(result["fail_safe"])
        self.assertEqual((result["base"], result["head"], result["diff_base"]), (base, head, common))
        self.assertEqual(result["paths"], ["android/core/protocol/src/main/kotlin/Protocol.kt"])
        self.assertEqual(result["scopes"], {
            "controller": False, "scaffold": False, "protocol": True,
            "backup": False, "external-oracle": False,
        })

    def test_pull_request_merge_base_failure_selects_all_scopes(self):
        with patch("oracle.workflow_scope.merge_base",
                   side_effect=subprocess.CalledProcessError(1, ["git", "merge-base"])):
            result = evaluate(Path.cwd(), "pull_request", {"pull_request": {
                "base": {"sha": BASE}, "head": {"sha": HEAD},
            }})
        self.assertTrue(result["fail_safe"])
        self.assertEqual(result["scopes"], {scope: True for scope in SCOPES})

    def test_known_native_core_modules_select_scaffold(self):
        for module in ("ble", "connectivity", "data", "datastore", "l10n", "runtime", "ui"):
            for suffix in ("src/main/kotlin/Production.kt", "src/test/kotlin/ProductionTest.kt", "build.gradle.kts"):
                path = f"android/core/{module}/{suffix}"
                with self.subTest(path=path):
                    self.assertEqual(classify([path]), {
                        scope: scope == "scaffold" for scope in SCOPES
                    })

    def test_database_and_backup_inputs_retain_backup_and_native_verification(self):
        for path in (
            "android/core/database/src/main/kotlin/com/meshcoreone/android/core/database/MeshDatabase.kt",
            "android/core/database/src/test/kotlin/DatabaseTest.kt",
            "android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/AppBackupCodec.kt",
        ):
            with self.subTest(path=path):
                self.assertEqual(classify([path]), {
                    scope: scope in ("scaffold", "backup") for scope in SCOPES
                })

    def test_unclassified_android_inputs_require_full_fail_safe_verification(self):
        for path in (
            "android/core/new-module/src/main/kotlin/Production.kt",
            "android/core/new-module/src/test/kotlin/ProductionTest.kt",
            "android/core/new-module/src/main/assets/help.md",
            "android/core/ble2/src/main/kotlin/Production.kt",
            "android/new-plugin/build.gradle.kts",
            "android/gradle/new-toolchain.json",
            "android/scaffold/invoke-gradle.ps1",
        ):
            with self.subTest(path=path):
                with self.assertRaisesRegex(PortError, "Unclassified Android inputs"):
                    classify([path])
                with patch("oracle.workflow_scope.changed_paths", return_value=[path]):
                    result = evaluate(Path.cwd(), "push", {"before": BASE, "after": HEAD})
                self.assertEqual(result["scopes"], {scope: True for scope in SCOPES})
                self.assertTrue(result["fail_safe"])
                self.assertIn(path, result["reason"])

    def test_recognized_input_does_not_hide_unclassified_android_input(self):
        paths = [
            "android/app/src/main/kotlin/App.kt",
            "android/core/new-module/src/main/kotlin/Production.kt",
        ]
        with patch("oracle.workflow_scope.changed_paths", return_value=paths):
            result = evaluate(Path.cwd(), "push", {"before": BASE, "after": HEAD})
        self.assertEqual(result["scopes"], {scope: True for scope in SCOPES})
        self.assertTrue(result["fail_safe"])
        self.assertIn(paths[1], result["reason"])

    def test_declared_android_readmes_and_unrelated_docs_remain_unselected(self):
        self.assertEqual(classify([
            "android/README.md", "android/scaffold/README.md",
            "android/tools/meshcli/README.md",
            "docs/android/evidence/WP-004/result.json", "CONTRIBUTING.md",
        ]), {scope: False for scope in SCOPES})

    def test_wp203_room_backup_and_harness_schedule_complete_backup_chain(self):
        for path in (
            "android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/AppBackupCodec.kt",
            "android/core/database/src/main/kotlin/com/meshcoreone/android/core/database/MeshDatabase.kt",
            "tools/android-port/oracle/wp203_interop.py",
        ):
            with self.subTest(path=path):
                self.assertTrue(classify([path])["backup"])

    def test_wp_oracle_evidence_and_deviations_do_not_schedule_candidate_execution(self):
        for path in (
            "docs/android/evidence/WP-203/WP203InteropTests.swift",
            "docs/android/evidence/WP-316/oracle-output.txt",
            "docs/android/evidence/WP-315/source-cases.json",
            "docs/android/deviations/WP-314.md",
        ):
            with self.subTest(path=path):
                self.assertEqual(classify([path]), {scope: False for scope in SCOPES})

    def test_protocol_only_change_schedules_protocol_only(self):
        result = classify([
            "android/core/protocol/src/main/kotlin/com/meshcoreone/android/core/protocol/bytes/Bytes.kt"
        ])
        self.assertEqual(result, {
            "controller": False, "scaffold": False, "protocol": True,
            "backup": False, "external-oracle": False,
        })

    def test_workflow_and_provenance_changes_schedule_all(self):
        for path in ALL_SCOPE_INPUTS:
            with self.subTest(path=path):
                self.assertEqual(classify([path]), {scope: True for scope in SCOPES})

    def test_plan_is_controller_only_and_shared_build_inputs_select_all_gradle_scopes(self):
        plan = classify(["docs/android/PORTING_PLAN.md"])
        self.assertTrue(plan["controller"])
        self.assertFalse(plan["scaffold"])
        shared = classify(["android/gradle/libs.versions.toml"])
        self.assertTrue(shared["scaffold"])
        self.assertTrue(shared["protocol"])
        self.assertTrue(shared["backup"])
        self.assertFalse(shared["external-oracle"])

    def test_event_endpoints_are_exact(self):
        self.assertEqual(
            endpoints("pull_request", {"pull_request": {
                "base": {"sha": BASE}, "head": {"sha": HEAD}
            }}),
            (BASE, HEAD),
        )
        self.assertEqual(
            endpoints("merge_group", {"merge_group": {"base_sha": BASE, "head_sha": HEAD}}),
            (BASE, HEAD),
        )
        self.assertEqual(endpoints("push", {"before": BASE, "after": HEAD}), (BASE, HEAD))
        self.assertIsNone(endpoints("workflow_dispatch", {}))

    def test_dispatch_runs_all_without_being_a_failure_fallback(self):
        result = evaluate(Path.cwd(), "workflow_dispatch", {})
        self.assertFalse(result["fail_safe"])
        self.assertEqual(result["scopes"], {scope: True for scope in SCOPES})

    def test_malformed_unknown_zero_push_and_diff_failure_run_all(self):
        events = (
            ("pull_request", {}),
            ("unknown", {}),
            ("push", {"before": ZERO, "after": HEAD}),
        )
        for name, event in events:
            with self.subTest(name=name):
                result = evaluate(Path.cwd(), name, event)
                self.assertTrue(result["fail_safe"])
                self.assertEqual(result["scopes"], {scope: True for scope in SCOPES})
        with patch("oracle.workflow_scope.changed_paths",
                   side_effect=subprocess.CalledProcessError(1, ["git", "diff"])):
            result = evaluate(Path.cwd(), "push", {"before": BASE, "after": HEAD})
        self.assertTrue(result["fail_safe"])
        self.assertEqual(result["scopes"], {scope: True for scope in SCOPES})
