"""AndroidOnly: WP-003 Installed hook parsing, transport and runtime-copy regressions."""

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

try:
    import check
    import fast
except ImportError:
    repo = Path(__file__).resolve().parents[3]
    common = Path(subprocess.check_output(
        ["git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        text=True,
    ).strip())
    sys.path.insert(0, str(common / "hooks" / "meshcore-local"))
    import check
    import fast

RESERVATIONS = Path(check.__file__).resolve().parents[2] / "meshcore-reservations"
if Path(check.__file__).resolve().parent.name == "local":
    RESERVATIONS = Path(check.__file__).resolve().parent
if (Path(check.__file__).parent.name != "meshcore-local"
        or Path(check.__file__).parent.parent.name != "hooks"
        or not (RESERVATIONS / "reserve.py").is_file()) and Path(check.__file__).resolve().parent.name != "local":
    RESERVATIONS = Path.cwd() / ".git" / "meshcore-local-reservations"
if not (RESERVATIONS / "reserve.py").is_file():
    raise RuntimeError("Missing exact reservation runtime; hook regressions cannot be skipped")
sys.dont_write_bytecode = True
sys.path.insert(0, str(RESERVATIONS))
import reserve
from controller.ledger import Ledger


class LocalCheckTests(unittest.TestCase):
    def test_benchmark_is_optional_and_cannot_replace_complete_merge_verification(self):
        self.assertEqual(check.ALL_STAGES, ["python", "preflight", "scaffold", "inspect"])
        self.assertEqual(check.OPTIONAL_STAGES, {"benchmark", "benchmark-dependencies"})
        with patch.object(check, "run_owned") as run:
            self.assertEqual(check.main(["--stages", "benchmark"]), 0)
        self.assertEqual(run.call_args.args[3], ["benchmark"])

    def test_pre_push_rejects_dependency_generation_stage(self):
        with patch.object(check, "run_owned") as run:
            self.assertEqual(check.main(["--pre-push", "--stages", "benchmark-dependencies"]), 1)
        run.assert_not_called()

    def test_default_all_runner_is_installed_beside_check(self):
        runner = Path(check.__file__).resolve().with_name("run.sh")
        self.assertTrue(runner.is_file(), f"Missing default-all runner: {runner}")
    def test_default_all_discovers_exact_candidate_local_tests(self):
        command = fast.local_test_command(Path("/candidate"))
        self.assertIn(str(Path("/candidate/tools/android-port/local")), command)
        self.assertEqual(command[command.index("-p") + 1], "*test*.py")
        self.assertNotIn(str(Path(fast.__file__).resolve().parent), command)

    def test_advisory_host_selection_preserves_all_linux_cases_and_windows_guard(self):
        import advisory_test
        for host, expected in (
            ("linux", advisory_test.AdvisoryLauncherTests),
            ("linux2", advisory_test.AdvisoryLauncherTests),
            ("win32", advisory_test.WindowsAdvisoryGuardTests),
        ):
            with self.subTest(host=host), patch.object(sys, "platform", host), \
                    contextlib.redirect_stdout(io.StringIO()):
                suite = advisory_test.load_tests(unittest.TestLoader(), None, None)
                self.assertEqual(advisory_test.fixture_class(host), expected)
                expected_names = unittest.TestLoader().getTestCaseNames(expected)
                self.assertEqual([test._testMethodName for test in suite], expected_names)
                self.assertEqual(len(expected_names), 8 if host.startswith("linux") else 1)
        for host in ("darwin", "", "windows", "freebsd"):
            with self.subTest(host=host), self.assertRaisesRegex(ValueError, "Linux or native Windows"):
                advisory_test.fixture_class(host)

    def test_windows_guard_is_not_substituted_for_linux_execution(self):
        import advisory_test
        with patch.object(sys, "platform", "linux"), self.assertRaisesRegex(ValueError, "actual Windows"):
            advisory_test.WindowsAdvisoryGuardTests(
                "test_actual_windows_rejects_linux_supervision_before_any_launch"
            ).test_actual_windows_rejects_linux_supervision_before_any_launch()


    def test_runner_imports_bundle_head_and_namespaced_refs(self):
        runner = Path(check.__file__).resolve().with_name("run.sh").read_text(encoding="utf-8")
        self.assertIn('fetch --quiet "$bundle" HEAD', runner)
        self.assertIn("+refs/*:refs/local-input/*", runner)


    def test_candidate_bundle_includes_an_exact_detached_revision(self):
        self.assertEqual(check.bundle_revisions("a" * 40), ["--all", "a" * 40])



    def test_fast_scaffold_options_execute_without_deleted_evidence_forwarding(self):
        options = fast.scaffold_options()

        self.assertEqual(options.count("--dependency-verification"), 1)
        self.assertEqual(options[options.index("--dependency-verification") + 1], "strict")
        self.assertIn("--build-cache", options)
        self.assertFalse(any("Evidence" in option or "evidence" in option for option in options))
        self.assertFalse(any("Invocation" in option or "invocation" in option for option in options))
        self.assertIn("--no-daemon", options)
        self.assertIn("-Pkotlin.compiler.execution.strategy=in-process", options)
        self.assertIn("--no-daemon", fast.scaffold_options(advisory=True))
        self.assertIn("-Pkotlin.compiler.execution.strategy=in-process", fast.scaffold_options(advisory=True))

    def test_multiple_exact_push_tips_not_head(self):
        first, second = "a" * 40, "b" * 40
        text = (
            f"refs/heads/a {first} refs/heads/a {check.ZERO}\n"
            f"refs/heads/b {second} refs/heads/b {first}\n"
        )
        self.assertEqual(check.push_commits(text), [first, second])

    def test_deletions_duplicates_and_empty_push(self):
        sha = "a" * 40
        deletion = f"(delete) {check.ZERO} refs/heads/deleted {sha}\n"
        update = f"refs/heads/a {sha} refs/heads/a {check.ZERO}\n"
        self.assertEqual(check.push_commits(deletion + update + update), [sha])
        self.assertEqual(check.push_commits(""), [])

    def test_malformed_push_is_blocked(self):
        for text in (
            "bad",
            "refs/a bad refs/a " + check.ZERO,
            "refs/a " + "a" * 40 + " refs/a bad",
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                check.push_commits(text)

    def test_unknown_empty_duplicate_stages_are_blocked(self):
        for stages in ("typo", "", "scaffold,scaffold", "scaffold,"):
            with self.subTest(stages=stages), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(check.main(["--stages", stages]), 1)

    def test_partial_or_worktree_pre_push_is_blocked(self):
        for options in (["--stages", "scaffold"], ["--worktree"], ["--install-hook"]):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(check.main(["--pre-push", *options]), 1)

    def test_failed_runner_publishes_only_as_unverified(self):
        sha = "a" * 40
        with patch("sys.stdin", io.StringIO(f"refs/a {sha} refs/a {check.ZERO}\n")), \
             patch("check.capture", return_value=str(Path.cwd())), \
             patch("check.transport", return_value=[]), \
             patch("check.run_owned",
                   side_effect=subprocess.CalledProcessError(1, ["checks"])), \
             patch("check.record_pending") as pending, \
             contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(check.main(["--pre-push"]), 0)
            pending.assert_called_once()

    def test_adapter_advisory_is_pending_without_unbounded_execution(self):
        commit = "a" * 40
        with patch("sys.stdin", io.StringIO(f"refs/heads/a {commit} refs/heads/a {check.ZERO}\n")), \
             patch("check.capture", return_value=str(Path.cwd())), \
             patch("check.run_adapter") as run, \
             patch("check.record_pending") as pending, \
             contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(check.main(["--pre-push", "--toolchain-state", "adapter.json"]), 0)
            run.assert_not_called()
            pending.assert_called_once()
            self.assertIn("full/manual adapter verification", str(pending.call_args.args[2]))

    def test_hook_runs_every_exact_commit(self):
        first, second = "a" * 40, "b" * 40
        text = f"refs/a {first} {first} {check.ZERO}\nrefs/b {second} {second} {check.ZERO}\n"
        with patch("sys.stdin", io.StringIO(text)), \
             patch("check.capture", return_value=str(Path.cwd())), \
             patch("check.transport", return_value=[]), \
             patch("check.run_owned") as run:
            self.assertEqual(check.main(["--pre-push"]), 0)
            self.assertEqual([call.args[1] for call in run.call_args_list], [first, second])

    def test_missing_or_broken_wsl_does_not_install(self):
        with patch("check.os.name", "nt"), patch("check.shutil.which", return_value=None):
            with self.assertRaisesRegex(ValueError, "WSL is unavailable"):
                check.transport(None)
        with patch("check.os.name", "nt"), patch("check.shutil.which", return_value="wsl.exe"), \
             patch("check.subprocess.run",
                   side_effect=subprocess.CalledProcessError(1, ["wsl"])):
            with self.assertRaises(subprocess.CalledProcessError):
                check.transport("BrokenDistribution", execution={"started": False})

    def test_unsupported_arm_push_is_pending_without_false_pass(self):
        sha = "a" * 40
        text = f"refs/heads/feature {sha} refs/heads/feature {check.ZERO}\n"
        with patch("sys.stdin", io.StringIO(text)), \
             patch("check.capture", return_value=str(Path.cwd())), \
             patch("check.run_owned", side_effect=ValueError("unsupported Darwin arm64")), \
             patch("check.record_pending") as pending:
            self.assertEqual(check.main(["--pre-push"]), 0)
            pending.assert_called_once_with(Path.cwd(), sha, unittest.mock.ANY)

    def valid_result(self, commit="a" * 40, tree="b" * 40):
        return {
            "schema_version": 1, "result": "success", "commit": commit, "tree": tree,
            "stages": list(check.ALL_STAGES), "host": {"system": "Darwin", "machine": "arm64"},
            "toolchain": {
                name: {"version": "pinned", "packages": ["verified-package"]}
                for name in check.REQUIRED_TOOLCHAIN
            },
            "stage_results": [
                {
                    "stage": stage, "result": "success", "tasks": ["declared-task"],
                    "discovered": 1, "failures": 0, "errors": 0, "skipped": 0,
                }
                for stage in check.ALL_STAGES
            ],
            "outputs": {"report.json": "c" * 64},
        }

    def test_native_arm_equivalent_result_is_accepted_for_exact_binding(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = Path(temporary) / "result.json"
            result.write_text(json.dumps(self.valid_result()), encoding="utf-8")
            self.assertEqual(check.validate_result(result, "a" * 40, "b" * 40)["result"], "success")

    def test_reduced_zero_test_failed_or_stale_results_block_merge(self):
        mutations = (
            lambda value: value.update(stages=check.ALL_STAGES[:-1]),
            lambda value: value["stage_results"][0].update(discovered=0),
            lambda value: value["stage_results"][0].update(failures=1),
            lambda value: value.update(commit="d" * 40),
            lambda value: value.update(tree="e" * 40),
        )
        with tempfile.TemporaryDirectory() as temporary:
            result = Path(temporary) / "result.json"
            for mutate in mutations:
                value = self.valid_result()
                mutate(value)
                result.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaises(ValueError):
                    check.validate_result(result, "a" * 40, "b" * 40)

    def test_existing_hook_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            hooks = Path(temporary)
            hook = hooks / "pre-push"
            hook.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            ledger = Ledger(hooks / "ledger.sqlite3", "fixture/repository")
            with patch("check.capture", return_value=str(hooks)), \
                 patch.object(check, "execution_runtime", return_value=ledger), \
                 patch("check.subprocess.run") as run:
                run.return_value.returncode = 1
                with self.assertRaisesRegex(ValueError, "Existing pre-push"):
                    check.install(hooks, ["wsl"], None)
            self.assertEqual(hook.read_text(encoding="utf-8"), "#!/bin/sh\nexit 1\n")


class InstalledRuntimeTests(unittest.TestCase):
    def test_supported_install_delivers_advisory_helpers_before_entrypoint(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "meshcore-reservations"
            hook = root / "hooks/meshcore-local"
            hook.mkdir(parents=True)
            (hook / "config.json").write_text('{"distribution":"fixture"}\n', encoding="utf-8")
            ledger = Ledger(root / "ledger.sqlite3", "fixture/repository")
            replaced = []
            original = os.replace

            def replace(source, destination):
                if Path(destination).parent == hook:
                    replaced.append(Path(destination).name)
                return original(source, destination)

            with patch.object(reserve.os, "replace", side_effect=replace):
                reserve.install_runtime(target, ledger, "fixture-session", 1.0)
            self.assertEqual(replaced[-1], "check.py")
            for name in ("advisory.py", "advisory_test.py", "fast.py", "run.sh", "check.py"):
                self.assertEqual((hook / name).read_bytes(), (target / name).read_bytes())
            self.assertEqual(json.loads((hook / "config.json").read_text()), {"distribution": "fixture"})
            self.assertEqual(ledger.hard_lock_records(), [])

    def test_relocated_runtime_is_exact_and_uses_advisory_schema(self):
        source_map = Path(reserve.__file__).with_name("source-sha256.json")
        if source_map.is_file():
            hashes = json.loads(source_map.read_text(encoding="utf-8"))
            for relative, expected in hashes.items():
                actual = Path(reserve.__file__).parent / relative
                self.assertEqual(hashlib.sha256(actual.read_bytes()).hexdigest(), expected)
        with tempfile.TemporaryDirectory() as temporary:
            ledger = Ledger(Path(temporary) / "ledger.sqlite3", "fixture/repository")
            with ledger.transaction() as connection:
                columns = {
                    row["name"] for row in connection.execute("PRAGMA table_info(leases)")
                }
            self.assertIn("record_state", columns)
            self.assertIn("capabilities", columns)


if __name__ == "__main__":
    unittest.main()
