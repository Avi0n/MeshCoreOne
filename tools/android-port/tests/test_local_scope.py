import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from controller.errors import PortError
from controller.local_scope import candidate_plan, collect_targeted_reports, report_inputs, run_scaffold, select_paths, validate_plan


class LocalScopeSelectionTests(unittest.TestCase):
    def test_direct_test_then_direct_production_then_consumers_preserves_all_tasks(self):
        modules = {
            "core/data": {"runner": "testDebugUnitTest", "tests": True, "lint": True, "dependencies": []},
            "core/runtime": {"runner": "test", "tests": True, "lint": False, "dependencies": ["core/data"]},
            "app": {"runner": "testDebugUnitTest", "tests": True, "lint": True,
                    "dependencies": ["core/data", "core/runtime"]},
        }
        tasks = {
            "app": [":app:testDebugUnitTest", ":app:compileDebugKotlin", ":app:lintDebug",
                    ":app:compileDebugAndroidTestKotlin"],
            "core/data": [":core:data:testDebugUnitTest", ":core:data:check",
                          ":core:data:compileDebugKotlin", ":core:data:lintDebug"],
            "core/runtime": [":core:runtime:test", ":core:runtime:check"],
        }
        for paths, order in (
            (["android/core/runtime/src/test/kotlin/RuntimeOwnershipTest.kt"], ["core/runtime", "app"]),
            (["android/core/data/src/main/kotlin/Repository.kt"], ["core/data", "app", "core/runtime"]),
            (["android/core/data/src/main/kotlin/Repository.kt",
              "android/core/runtime/src/test/kotlin/RuntimeOwnershipTest.kt"], ["core/runtime", "core/data", "app"]),
            (["android/core/data/src/testDebug/kotlin/RepositoryTest.kt",
              "android/core/runtime/src/test/kotlin/RuntimeOwnershipTest.kt"], ["core/data", "core/runtime", "app"]),
            (["android/core/data/src/main/kotlin/Repository.kt",
              "android/core/runtime/src/androidTest/kotlin/RuntimeTest.kt"], ["core/runtime", "core/data", "app"]),
        ):
            with self.subTest(paths=paths):
                plan = select_paths(paths, modules=modules)
                original = [task for module in sorted(order) for task in tasks[module]]
                original.extend(["validateModuleGraph", "runtimeDependencyInventory"])
                self.assertCountEqual(plan["tasks"], original)
                self.assertEqual(plan["tasks"], [task for module in order for task in tasks[module]]
                                 + ["validateModuleGraph", "runtimeDependencyInventory"])
                self.assertEqual(plan["scopes"], ["module:" + module for module in sorted(order)])
                self.assertEqual(plan["stages"], ["python", "preflight", "scaffold"])
                self.assertFalse(plan["full_scaffold"])
        backup = select_paths([
            "android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/Codec.kt",
            "android/core/runtime/src/test/kotlin/RuntimeOwnershipTest.kt",
        ], modules=modules)
        self.assertEqual(backup["tasks"][0], ":core:runtime:test")
        self.assertCountEqual(backup["tasks"], [
            *(task for module in sorted(tasks) for task in tasks[module]),
            ":core:data:verifyBackupTests", ":core:data:verifyPersistenceRepositoryTests",
            ":core:database:testDebugUnitTest", "validateModuleGraph", "runtimeDependencyInventory",
        ])
        self.assertEqual(backup["scopes"], ["backup", "module:app", "module:core/data", "module:core/runtime"])

    def test_full_fallback_order_and_obligations_are_unchanged_by_direct_tests(self):
        from controller.local_scope import SCOPED_TASKS
        modules = {"core/runtime": {"runner": "test", "tests": True, "lint": False, "dependencies": []}}
        original = list(dict.fromkeys(task for tasks in SCOPED_TASKS.values() for task in tasks))
        for extra, graph_error in (
            (["android/unclassified/input.bin"], None),
            (["android/core/runtime/build.gradle.kts"], None),
            ([], "unclassified committed dependency"),
        ):
            with self.subTest(extra=extra, graph_error=graph_error):
                plan = select_paths(["android/core/runtime/src/test/kotlin/RuntimeTest.kt", *extra],
                                    modules=modules, graph_error=graph_error)
                self.assertEqual(plan["tasks"], original)
                self.assertEqual(plan["scopes"], list(SCOPED_TASKS))
                self.assertEqual(plan["stages"], ["python", "preflight", "scaffold", "inspect"])
                self.assertTrue(plan["full_scaffold"])

    def test_protocol_uses_actual_declared_tasks_and_graph_checks(self):
        plan = select_paths(["android/core/protocol/src/main/kotlin/Frame.kt"])
        self.assertEqual(plan["scopes"], ["protocol"])
        self.assertEqual(plan["tasks"], [
            ":core:protocol:test", "validateModuleGraph", "runtimeDependencyInventory",
        ])
        self.assertFalse(plan["full_scaffold"])

    def test_backup_preserves_both_declared_behavioral_verifiers(self):
        plan = select_paths([
            "android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/Codec.kt",
            "android/core/database/src/main/kotlin/Store.kt",
        ])
        self.assertEqual(plan["scopes"], ["backup"])
        self.assertIn(":core:data:verifyBackupTests", plan["tasks"])
        self.assertIn(":core:data:verifyPersistenceRepositoryTests", plan["tasks"])
        self.assertIn(":core:database:testDebugUnitTest", plan["tasks"])

    def test_mixed_scopes_are_union_not_first_match(self):
        plan = select_paths([
            "android/core/protocol/src/test/kotlin/FrameTest.kt",
            "android/core/database/src/test/kotlin/StoreTest.kt",
        ])
        self.assertEqual(plan["scopes"], ["protocol", "backup"])

    def test_unknown_incomplete_duplicate_and_empty_scopes_fail(self):
        path = "android/core/protocol/src/main/kotlin/Frame.kt"
        for requested in ([], ["backup"], ["typo"], ["protocol", "protocol"]):
            with self.subTest(scopes=requested), self.assertRaises(PortError):
                select_paths([path], requested)

    def test_broad_unclassified_and_build_changes_fall_back_to_full(self):
        for path in (
            "android/app/src/main/kotlin/Main.kt", "android/unknown/input.bin",
            "android/core/protocol/build.gradle.kts", "android/gradle/dependency-locks/core.lockfile",
            "android/core/l10n/src/main/res/values/strings.xml",
        ):
            with self.subTest(path=path):
                plan = select_paths([path])
                self.assertTrue(plan["full_scaffold"])
                self.assertEqual(plan["stages"], ["python", "preflight", "scaffold", "inspect"])
                self.assertIn("resolveScaffoldDependencies", plan["tasks"])
                self.assertIn("lintScaffold", plan["tasks"])
                with self.assertRaises(PortError):
                    select_paths([path], ["protocol"])

    def test_controller_change_is_not_gradle_proxy_evidence(self):
        plan = select_paths(["tools/android-port/controller/dispatch.py"])
        self.assertEqual(plan["stages"], ["python"])
        self.assertEqual(plan["tasks"], [])

    def test_empty_diff_is_full_not_zero_work_success(self):
        self.assertTrue(select_paths([])["full_scaffold"])
        self.assertIn("inspect", select_paths([])["stages"])

    def test_caller_can_add_but_not_remove_scopes(self):
        plan = select_paths(["android/core/protocol/src/test/kotlin/Test.kt"], ["protocol", "backup"])
        self.assertEqual(plan["scopes"], ["protocol", "backup"])

    def test_services_runtime_and_app_select_complete_consumer_suites(self):
        jvm = {"runner": "test", "tests": True, "lint": False, "dependencies": []}
        modules = {
            "core/services": dict(jvm), "core/runtime": dict(jvm),
            "app": {"runner": "testDebugUnitTest", "tests": True, "lint": True,
                    "dependencies": ["core/services", "core/runtime"]},
        }
        plan = select_paths([
            "android/core/services/src/main/kotlin/Sync.kt",
            "android/core/runtime/src/main/kotlin/Owner.kt",
        ], modules=modules)
        self.assertFalse(plan["full_scaffold"])
        self.assertNotIn("inspect", plan["stages"])
        for task in (
            ":core:services:test", ":core:runtime:test", ":app:testDebugUnitTest",
            ":app:compileDebugKotlin", ":app:compileDebugAndroidTestKotlin", ":app:lintDebug",
            "validateModuleGraph", "runtimeDependencyInventory",
        ):
            self.assertIn(task, plan["tasks"])
        with self.assertRaisesRegex(PortError, "Incomplete"):
            select_paths(["android/core/services/src/main/kotlin/Sync.kt"],
                         ["module:core/services"], modules=modules)

    def test_module_graph_unknowns_and_external_app_sources_fail_safe(self):
        app = {"runner": "testDebugUnitTest", "tests": True, "lint": True, "dependencies": []}
        for path in ("android/ui-tests/src/test/kotlin/Flow.kt", "android/parity-tests/src/test/kotlin/Case.kt"):
            plan = select_paths([path], modules={"app": app})
            self.assertEqual(plan["scopes"], ["module:app"])
            self.assertIn(":app:testDebugUnitTest", plan["tasks"])
        self.assertTrue(select_paths(["android/core/protocol/src/main/kotlin/X.kt"],
                                     graph_error="unclassified graph")["full_scaffold"])

    def test_ci_task_lists_are_shared_and_l10n_obligations_remain(self):
        from controller import ci, local_scope
        self.assertIs(ci.TASKS, local_scope.TASKS)
        self.assertIs(ci.SCOPED_TASKS, local_scope.SCOPED_TASKS)
        plan = select_paths(["android/core/l10n/src/main/res/values/strings.xml"], modules={
            "core/l10n": {"runner": "testDebugUnitTest", "tests": True, "lint": True, "dependencies": []},
        })
        self.assertIn(":core:l10n:verifyL10nTests", plan["tasks"])
        self.assertIn(":core:l10n:check", plan["tasks"])


class LocalScopeBindingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Scope fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.write(".gitignore", "**/build/\noutput/\n")
        self.write("README", "base")
        self.write("android/core/protocol/build.gradle.kts", 'plugins { id("mesh.jvm.library") }\n')
        self.write("android/core/services/build.gradle.kts", 'plugins { id("mesh.jvm.library") }\n')
        self.write("android/core/services/src/test/kotlin/SyncTest.kt", "fixture")
        self.base = self.commit()
        self.git("update-ref", "refs/remotes/origin/main", self.base)
        self.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        self.write("android/core/protocol/src/test/kotlin/Frame.kt", "fixture")
        self.head = self.commit()

    def git(self, *arguments):
        return subprocess.check_output(
            ["git", "-C", str(self.repo), *arguments], text=True, stderr=subprocess.PIPE,
        ).strip()

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", "--all")
        self.git("-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false",
                 "commit", "--quiet", "-m", "scope fixture")
        return self.git("rev-parse", "HEAD")

    def test_actual_git_diff_binds_base_head_tree_and_scopes(self):
        plan = candidate_plan(self.repo, self.base, self.head)
        self.assertEqual(plan["paths"], ["android/core/protocol/src/test/kotlin/Frame.kt"])
        self.assertEqual(plan["head"], self.head)
        self.assertEqual(plan["tree"], self.git("rev-parse", "HEAD^{tree}"))
        self.assertEqual(validate_plan(self.repo, json.loads(json.dumps(plan))), plan)

    def test_wp201_data_production_and_runtime_test_keep_exact_reports_and_consumers(self):
        self.write("android/core/data/build.gradle.kts", 'plugins { id("mesh.android.library") }\n')
        self.write("android/core/data/src/test/kotlin/RepositoryTest.kt", "existing tests")
        self.write("android/core/runtime/build.gradle.kts",
                   'plugins { id("mesh.jvm.library") }\ndependencies { implementation(project(":core:data")) }\n')
        self.write("android/app/build.gradle.kts",
                   'plugins { id("mesh.android.application") }\ndependencies {'
                   ' implementation(project(":core:data")); implementation(project(":core:runtime")) }\n')
        self.write("android/core/data/src/main/kotlin/Repository.kt", "base")
        self.write("android/core/runtime/src/test/kotlin/RuntimeOwnershipTest.kt", "base")
        base = self.commit()
        self.git("update-ref", "refs/remotes/origin/main", base)
        self.write("android/core/data/src/main/kotlin/Repository.kt", "changed production")
        self.write("android/core/runtime/src/test/kotlin/RuntimeOwnershipTest.kt", "changed regression")
        plan = candidate_plan(self.repo, base, self.commit())
        self.assertEqual(plan["tasks"], [
            ":core:runtime:test", ":core:runtime:check",
            ":core:data:testDebugUnitTest", ":core:data:check", ":core:data:compileDebugKotlin", ":core:data:lintDebug",
            ":app:testDebugUnitTest", ":app:compileDebugKotlin", ":app:lintDebug", ":app:compileDebugAndroidTestKotlin",
            "validateModuleGraph", "runtimeDependencyInventory",
        ])
        self.assertEqual(plan["scopes"], ["module:app", "module:core/data", "module:core/runtime"])
        directories, lint = report_inputs(self.repo, plan)
        self.assertEqual(directories, [
            self.repo / "android/app/build/test-results/testDebugUnitTest",
            self.repo / "android/core/data/build/test-results/testDebugUnitTest",
            self.repo / "android/core/runtime/build/test-results/test",
        ])
        self.assertEqual(lint, ["app", "core/data"])
        self.assertEqual(validate_plan(self.repo, plan), plan)
        with self.assertRaisesRegex(PortError, "required obligations"):
            validate_plan(self.repo, {**plan, "tasks": sorted(plan["tasks"])})
        source = Path(__file__).resolve().parents[3]
        legacy = types.ModuleType("controller._legacy_3a_scope")
        legacy.__package__ = "controller"
        exec(subprocess.check_output([
            "git", "-C", str(source), "show",
            "3a292b6498e8dcef0c374022a93c8e30c68f5bd5:tools/android-port/controller/local_scope.py",
        ], text=True), legacy.__dict__)
        control = self.repo / "owned-control"
        control.mkdir()
        (control / "local_scope.py").write_bytes(
            (source / "tools/android-port/controller/local_scope.py").read_bytes())
        (control / "fast.py").write_bytes((source / "tools/android-port/local/fast.py").read_bytes())
        spec = importlib.util.spec_from_file_location("owned_fast_fixture", control / "fast.py")
        fast = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fast)
        owned = fast.owned_scope()
        self.assertEqual(Path(owned.__file__).resolve(), (control / "local_scope.py").resolve())

        class CapturedExecution(Exception):
            pass

        def execute(command, repo, environment, log):
            self.assertEqual(command, [
                str(self.repo / "android/gradlew"), "-p", str(self.repo / "android"),
                *plan["tasks"], *fast.scaffold_options(), "--init-script",
                str(self.repo / "tools/android-port/controller/verify_candidate_task_graph.gradle"),
            ])
            raise CapturedExecution

        with self.assertRaisesRegex(PortError, "required obligations"):
            legacy.run_scaffold(self.repo, plan, {}, self.repo / "output", execute,
                                fast.scaffold_options(), trusted_base=base)
        with self.assertRaises(CapturedExecution):
            owned.run_scaffold(self.repo, plan, {}, self.repo / "output", execute,
                               fast.scaffold_options(), trusted_base=base)
        self.assertEqual(self.git("rev-parse", "HEAD"), plan["head"])
        import controller
        from controller import ci, ci_environment
        self.write("work/toolchain/environment.json", json.dumps({"java_home": str(self.repo / "fixture-java")}))
        self.write("request.json", json.dumps(plan))
        output = self.repo / "output"
        output.mkdir(exist_ok=True)
        admission = {
            "schema_version": 1, "interlock_version": 2, "owner": "local-check:fixture",
            "token": "fixture", "commit": plan["head"], "tree": plan["tree"],
        }
        original_read = Path.read_text

        def read_text(path, *args, **kwargs):
            return json.dumps(admission) if path == Path("/proc/self/fd/8") else original_read(path, *args, **kwargs)

        calls, emit = self.execute_fixture(plan)

        def complete(command, repo, environment, log):
            if "ANDROID_CI_TASK_GRAPH" not in environment:
                return
            emit(command, repo, environment, log)
            self.write("android/build/reports/scaffold/module-graph.tsv",
                       "consumer\tproducer\tconfiguration\n:core:runtime\t:core:data\timplementation\n"
                       ":app\t:core:data\timplementation\n:app\t:core:runtime\timplementation\n")

        text = io.StringIO()
        with patch.dict(sys.modules, {"controller.local_scope": legacy}), \
                patch.object(controller, "local_scope", legacy), \
                patch.object(sys, "argv", [
                    "fast.py", "--repo", str(self.repo), "--work", str(self.repo / "work"),
                    "--output", str(output), "--stages", ",".join(plan["stages"]),
                    "--affected-plan", str(self.repo / "request.json"), "--affected-base", base,
                ]), patch.object(Path, "read_text", new=read_text), \
                patch.object(fast, "execution_lane", return_value=None), \
                patch.object(fast, "execute", side_effect=complete), \
                patch.object(ci_environment, "candidate_environment", return_value={}), \
                patch.object(ci_environment, "verify_wrapper"), \
                patch.object(ci, "python_checks"), patch.object(ci, "preflight"), \
                contextlib.redirect_stdout(text):
            fast.main()
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][3:3 + len(plan["tasks"])], plan["tasks"])
        self.assertIn('"kind": "local-affected-only"', text.getvalue())
        self.assertIn("AFFECTED SNAPSHOT CHECKS COMPLETE", text.getvalue())
        self.assertNotIn("FAST LOCAL CHECK PASSED", text.getvalue())
        self.assertEqual(self.git("rev-parse", "HEAD"), plan["head"])

    def test_stale_base_and_head_are_rejected(self):
        plan = candidate_plan(self.repo, self.base, self.head)
        self.git("update-ref", "refs/remotes/origin/main", self.head)
        with self.assertRaisesRegex(PortError, "Stale affected base"):
            candidate_plan(self.repo, self.base, self.head)
        with self.assertRaisesRegex(PortError, "Stale affected base"):
            validate_plan(self.repo, plan)
        self.write("README", "later")
        self.commit()
        with self.assertRaisesRegex(PortError, "Stale affected head"):
            validate_plan(self.repo, plan, trusted_base=self.base)

    def test_omitted_path_task_or_scope_is_rejected_from_real_diff(self):
        original = candidate_plan(self.repo, self.base, self.head)
        for field in ("paths", "tasks", "scopes"):
            plan = {**original, field: []}
            with self.subTest(field=field), self.assertRaises(PortError):
                validate_plan(self.repo, plan)

    def test_dirty_or_untracked_inputs_cannot_claim_the_committed_candidate(self):
        plan = candidate_plan(self.repo, self.base, self.head)
        self.write("android/core/protocol/src/test/kotlin/Frame.kt", "dirty")
        with self.assertRaisesRegex(PortError, "checkout differs"):
            validate_plan(self.repo, plan)
        self.write("android/core/protocol/src/test/kotlin/Frame.kt", "fixture")
        self.write("android/core/protocol/src/main/kotlin/Uncommitted.kt", "untracked")
        with self.assertRaisesRegex(PortError, "Uncommitted"):
            validate_plan(self.repo, plan)

    def test_branch_names_and_nonancestor_base_fail(self):
        with self.assertRaisesRegex(PortError, "immutable"):
            candidate_plan(self.repo, "main", self.head)
        with self.assertRaisesRegex(PortError, "ancestor"):
            candidate_plan(self.repo, self.head, self.base, trusted_base=self.head)

    def test_unknown_committed_graph_widens_without_blocking_full_report_preparation(self):
        self.write("android/core/services/build.gradle.kts",
                   'plugins { id("mesh.jvm.library") }\ndependencies { implementation(project(dynamic)) }\n')
        head = self.commit()
        plan = candidate_plan(self.repo, self.base, head)
        self.assertTrue(plan["full_scaffold"])
        self.assertIn("Unclassified project dependency expression", plan["reason"])
        directories, lint = report_inputs(self.repo, plan)
        self.assertIn("app", lint)
        self.assertTrue(any("core" in path.parts and "services" in path.parts for path in directories))

    def test_full_fallback_cannot_omit_the_required_apk_inspection_stage(self):
        self.write("android/core/protocol/build.gradle.kts",
                   'plugins { id("mesh.jvm.library") }\n// changed build input\n')
        plan = candidate_plan(self.repo, self.base, self.commit())
        self.assertTrue(plan["full_scaffold"])
        self.assertEqual(plan["stages"], ["python", "preflight", "scaffold", "inspect"])
        validate_plan(self.repo, plan)
        with self.assertRaisesRegex(PortError, "required obligations"):
            validate_plan(self.repo, {**plan, "stages": ["python", "preflight", "scaffold"]})

    def test_zero_missing_malformed_and_skipped_required_reports_fail(self):
        path = "android/core/testing/build/test-results/testDebugUnitTest/TEST-Fixture.xml"
        with self.assertRaisesRegex(PortError, "Missing"):
            collect_targeted_reports(self.repo, ["external-oracle"])
        for report in (
            "<testsuite tests='0' failures='0' errors='0' skipped='0'/>",
            "<broken",
            "<testsuite tests='1' failures='0' errors='0' skipped='0'>"
            "<testcase classname='Fixture' name='test'><skipped/></testcase></testsuite>",
            "<testsuite tests='1' failures='0' errors='0' skipped='1'>"
            "<testcase classname='Fixture' name='test'><skipped/></testcase></testsuite>",
        ):
            self.write(path, report)
            with self.subTest(report=report), self.assertRaises(PortError):
                collect_targeted_reports(self.repo, ["external-oracle"])
        self.write(path, "<testsuite tests='1' failures='0' errors='0' skipped='0'>"
                   "<testcase classname='Fixture' name='test'/></testsuite>")
        self.assertEqual(
            collect_targeted_reports(self.repo, ["external-oracle"])["core/testing"]["passed"], 1,
        )

    def services_plan(self):
        self.git("update-ref", "refs/remotes/origin/main", self.head)
        self.write("android/core/services/src/main/kotlin/Sync.kt", "changed fixture")
        return candidate_plan(self.repo, self.head, self.commit())

    def execute_fixture(self, plan, *, emit=True, graph=True, skipped=False):
        calls = []

        def execute(command, repo, environment, log):
            calls.append(command)
            if graph:
                Path(environment["ANDROID_CI_TASK_GRAPH"]).write_text(json.dumps({
                    "schema_version": 1,
                    "tasks": [{
                        "path": task if task.startswith(":") else ":" + task,
                        "type": "fixture", "command": [],
                    } for task in plan["tasks"]],
                }), encoding="utf-8")
            if emit:
                directories, lint = report_inputs(self.repo, plan)
                for directory in directories:
                    self.write(
                        directory / "TEST-Sync.xml",
                        "<testsuite tests='1' failures='0' errors='0' skipped='0'>"
                        "<testcase classname='SyncTest' name='required'>"
                        + ("<skipped/>" if skipped else "") + "</testcase></testsuite>",
                    )
                for module in lint:
                    self.write(f"android/{module}/build/reports/lint-results-debug.xml",
                               '<issues format="6" by="lint fixture"/>')
                self.write("android/build/reports/scaffold/module-graph.tsv",
                           "consumer\tproducer\tconfiguration\n:core:services\t:core:protocol\timplementation\n")
                self.write("android/build/reports/scaffold/runtime-dependencies.tsv",
                           "artifact\tdeclared_license\tlicense_url\tlicense_pom\tlicense_pom_sha256\tlegal_gate\n"
                           "org:fixture:1\tMIT\thttps://example.invalid/license\torg:fixture:1\t"
                           + "a" * 64 + "\thuman-review-pending\n")
        return calls, execute

    def test_executor_runs_fixed_tasks_and_parses_real_current_reports(self):
        plan = self.services_plan()
        output = self.repo / "output"
        output.mkdir()
        calls, execute = self.execute_fixture(plan)
        report = run_scaffold(self.repo, plan, {}, output, execute,
                              ["--dependency-verification", "strict"], trusted_base=plan["base"])
        self.assertEqual(len(calls), 1)
        self.assertIn(":core:services:test", calls[0])
        self.assertIn(":core:services:check", calls[0])
        self.assertIn("--init-script", calls[0])
        self.assertEqual(report["kind"], "local-affected-only")
        self.assertEqual(report["suites"]["core/services"]["passed"], 1)

    def test_skipped_task_cannot_reuse_previous_success_reports(self):
        plan = self.services_plan()
        output = self.repo / "output"
        output.mkdir()
        _, old = self.execute_fixture(plan)
        old([], self.repo, {"ANDROID_CI_TASK_GRAPH": str(output / "old-graph.json")}, None)
        _, execute = self.execute_fixture(plan, emit=False)
        with self.assertRaisesRegex(PortError, "Missing mandatory test reports"):
            run_scaffold(self.repo, plan, {}, output, execute, [], trusted_base=plan["base"])

    def test_actual_skipped_case_and_missing_current_graph_fail(self):
        plan = self.services_plan()
        output = self.repo / "output"
        output.mkdir()
        _, execute = self.execute_fixture(plan, skipped=True)
        with self.assertRaisesRegex(PortError, "failed/skipped"):
            run_scaffold(self.repo, plan, {}, output, execute, [], trusted_base=plan["base"])
        _, execute = self.execute_fixture(plan, graph=False)
        with self.assertRaises(PortError):
            run_scaffold(self.repo, plan, {}, output, execute, [], trusted_base=plan["base"])

    def test_actual_resolved_consumer_cannot_be_omitted_by_static_selection(self):
        plan = self.services_plan()
        output = self.repo / "output"
        output.mkdir()
        _, emit = self.execute_fixture(plan)

        def execute(*arguments):
            emit(*arguments)
            self.write("android/build/reports/scaffold/module-graph.tsv",
                       "consumer\tproducer\tconfiguration\n:app\t:core:services\timplementation\n")

        with self.assertRaisesRegex(PortError, "omitted consumers.*app"):
            run_scaffold(self.repo, plan, {}, output, execute, [], trusted_base=plan["base"])

    def test_command_failure_and_cancellation_propagate_without_report_success(self):
        plan = self.services_plan()
        output = self.repo / "output"
        output.mkdir()
        for failure in (subprocess.CalledProcessError(1, ["fixture"]), KeyboardInterrupt()):
            def execute(*args):
                raise failure
            with self.subTest(failure=type(failure)), self.assertRaises(type(failure)):
                run_scaffold(self.repo, plan, {}, output, execute, [], trusted_base=plan["base"])
