"""AndroidOnly: WP-003 Installed hook parsing, transport and runtime-copy regressions."""

import contextlib
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
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
from controller.errors import PortError
from controller.local_scope import candidate_plan
from controller import jvm_lanes


class LocalCheckTests(unittest.TestCase):
    def test_raw_candidate_fetch_into_reused_snapshot_uses_actual_shell_arguments(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo, snapshot, bundle = root / "source", root / "snapshot", root / "candidate.bundle"
            repo.mkdir()

            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()

            git("init", "--quiet")
            (repo / "tracked").write_text("fixture\n", encoding="utf-8")
            git("add", ".")
            git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "--quiet", "-m", "initial")
            base = git("rev-parse", "HEAD")
            subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", str(repo), str(snapshot)], check=True)
            candidate = git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                            "commit-tree", git("rev-parse", "HEAD^{tree}"), "-p", base, "-m", "raw candidate")
            self.assertNotIn(candidate, git("for-each-ref", "--format=%(objectname)").splitlines())
            git("bundle", "create", str(bundle), *check.bundle_revisions(candidate))
            absent = subprocess.run(["git", "-C", str(snapshot), "cat-file", "-e", candidate + "^{commit}"],
                                    capture_output=True)
            self.assertNotEqual(absent.returncode, 0)
            fetch = next(line.strip() for line in Path(check.__file__).with_name("run.sh").read_text().splitlines()
                         if line.strip().startswith('git -C "$repo" fetch --quiet "$bundle"'))
            values = {"$repo": str(snapshot), "$bundle": str(bundle), "$revision": candidate}
            subprocess.run([values.get(arg, arg) for arg in shlex.split(fetch)], check=True,
                           capture_output=True, text=True)
            subprocess.run(["git", "-C", str(snapshot), "cat-file", "-e", candidate + "^{commit}"], check=True)
            self.assertEqual(subprocess.check_output(
                ["git", "-C", str(snapshot), "rev-parse", "HEAD"], text=True).strip(), base)

    def test_real_linux_reused_slot_checks_out_unreferenced_raw_candidate(self):
        if not sys.platform.startswith("linux"):
            with self.assertRaisesRegex(PortError, "actual supported Linux"):
                jvm_lanes.host_identity()
            return
        from advisory_test import AdvisoryLauncherTests
        source = (
            "import os,pathlib,subprocess,sys\n"
            "repo=sys.argv[sys.argv.index('--repo')+1]\n"
            "head=subprocess.check_output(['git','-C',repo,'rev-parse','HEAD'],text=True).strip()\n"
            "(pathlib.Path(os.environ['ANDROID_LOCAL_HOME'])/('visited-'+head)).touch()\n"
        )
        with AdvisoryLauncherTests.fixture(self, source) as (root, repo, work, base, tree):
            subprocess.run(["git", "-C", str(repo), "update-ref", "refs/remotes/origin/main", base], check=True)
            subprocess.run(["git", "-C", str(repo), "symbolic-ref",
                            "refs/remotes/origin/HEAD", "refs/remotes/origin/main"], check=True)
            host = jvm_lanes.host_identity()
            with patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(root / "ledger.sqlite3")}), \
                    patch.object(jvm_lanes, "host_capacity", return_value=(host, 3)):
                check.run_owned(repo, base, None, check.ALL_STAGES, False,
                                affected_base=base, parallel_jvm=True)
                snapshots = list((work / "local-checks/lanes" / host / "0/repos").iterdir())
                self.assertEqual(len(snapshots), 1)
                snapshot = snapshots[0]
                self.assertTrue((snapshot / ".git/meshcore-local-snapshot").is_dir())
                candidate = subprocess.check_output([
                    "git", "-C", str(repo), "-c", "user.name=Fixture",
                    "-c", "user.email=fixture@example.invalid", "commit-tree", tree,
                    "-p", base, "-m", "raw unreferenced candidate",
                ], text=True).strip()
                refs = subprocess.check_output(
                    ["git", "-C", str(repo), "for-each-ref", "--format=%(objectname)"], text=True).splitlines()
                self.assertNotIn(candidate, refs)
                check.run_owned(repo, candidate, None, check.ALL_STAGES, False,
                                affected_base=base, parallel_jvm=True)
                self.assertTrue((work / ("visited-" + base)).is_file())
                self.assertTrue((work / ("visited-" + candidate)).is_file())
                self.assertEqual(subprocess.check_output(
                    ["git", "-C", str(snapshot), "rev-parse", "HEAD"], text=True).strip(), candidate)
                self.assertEqual(subprocess.check_output(
                    ["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip(), base)
                self.assertEqual(check.execution_runtime(repo).hard_lock_records(), [])

    def test_staged_control_carries_exact_trusted_lane_helper(self):
        for affected in (False, True):
            with self.subTest(affected=affected), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                completion = root / "cleanup.json"
                completion.write_text(json.dumps({
                    "schema_version": 1, "token": "fixture", "status": "owned-children-reaped", "returncode": 0,
                }), encoding="utf-8")
                plan = {"head": "a" * 40, "tree": "b" * 40, "base": "c" * 40, "stages": check.ALL_STAGES}
                with patch.object(check, "binding", return_value=("a" * 40, "b" * 40)), \
                        patch.object(check.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)):
                    check.run_candidate(root, "a" * 40, [], check.ALL_STAGES, False,
                                        completion=completion, token="fixture", admission=root / "admission.json",
                                        execution={"started": False}, affected_plan=plan if affected else None)
                self.assertEqual((root / "control/jvm_lanes.py").read_bytes(),
                                 Path(jvm_lanes.__file__).read_bytes().replace(b"\r\n", b"\n"))
                self.assertEqual((root / "control/local_scope.py").exists(), affected)
                if affected:
                    from controller import local_scope
                    self.assertEqual((root / "control/local_scope.py").read_bytes(),
                                     Path(local_scope.__file__).read_bytes().replace(b"\r\n", b"\n"))

    def test_real_fast_uses_owned_helper_with_older_candidate_controller(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            controller = root / "candidate/controller"
            controller.mkdir(parents=True)
            (controller / "__init__.py").touch()
            (controller / "errors.py").write_bytes(Path(jvm_lanes.__file__).with_name("errors.py").read_bytes())
            control = root / "control"
            control.mkdir()
            (control / "fast.py").write_bytes(Path(fast.__file__).read_bytes())
            (control / "jvm_lanes.py").write_bytes(Path(jvm_lanes.__file__).read_bytes())
            script = """
import importlib.util, json, pathlib, sys
from unittest.mock import patch
sys.path.insert(0, sys.argv[1])
import controller
assert importlib.util.find_spec('controller.jvm_lanes') is None
spec = importlib.util.spec_from_file_location('owned_fast', sys.argv[2])
fast = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fast)
work = pathlib.Path(sys.argv[3])
descriptor = pathlib.Path('/proc/self/fd/8')
original_is_file, original_read_text = pathlib.Path.is_file, pathlib.Path.read_text
admission = {}
def is_file(path):
    return True if path == descriptor else original_is_file(path)
def read_text(path, *args, **kwargs):
    return json.dumps(admission) if path == descriptor else original_read_text(path, *args, **kwargs)
with patch.object(pathlib.Path, 'is_file', new=is_file), patch.object(pathlib.Path, 'read_text', new=read_text):
    with patch.object(fast, 'owned_lanes', side_effect=AssertionError('Serial must not load lane helper')):
        assert fast.execution_lane(work) is None
    owned = fast.owned_lanes()
    assert pathlib.Path(owned.__file__).resolve() == pathlib.Path(sys.argv[2]).with_name('jvm_lanes.py').resolve()
    admission['jvm_lane'] = {'host': 'a' * 64, 'slot': 1, 'capacity': 3}
    with patch.object(owned, 'host_identity', return_value='a' * 64), \
         patch.object(owned, 'cpu_capacity', return_value=3), \
         patch.object(fast, 'owned_lanes', return_value=owned):
        assert fast.execution_lane(work) == work / 'local-checks/lanes' / ('a' * 64) / '1'
assert importlib.util.find_spec('controller.jvm_lanes') is None
"""
            subprocess.run([sys.executable, "-c", script, str(controller.parent),
                            str(control / "fast.py"), str(root / "work")], check=True,
                           capture_output=True, text=True)

    def test_real_linux_slot_flocks_overlap_or_windows_rejects_before_launch(self):
        if not sys.platform.startswith("linux"):
            with self.assertRaisesRegex(PortError, "actual supported Linux"):
                jvm_lanes.host_identity()
            return
        from advisory_test import AdvisoryLauncherTests
        source = (
            "import json,os,pathlib,time\n"
            "binding=json.loads(pathlib.Path('/proc/self/fd/8').read_text())['jvm_lane']\n"
            "work=pathlib.Path(os.environ['ANDROID_LOCAL_HOME'])\n"
            "(work/('ready-'+str(binding['slot']))).touch()\n"
            "while not (work/'release').exists(): time.sleep(.02)\n"
        )
        with AdvisoryLauncherTests.fixture(self, source) as (root, repo, work, commit, tree):
            subprocess.run(["git", "-C", str(repo), "update-ref", "refs/remotes/origin/main", commit], check=True)
            subprocess.run(["git", "-C", str(repo), "symbolic-ref",
                            "refs/remotes/origin/HEAD", "refs/remotes/origin/main"], check=True)
            host = jvm_lanes.host_identity()
            with patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(root / "ledger.sqlite3")}), \
                    patch.object(jvm_lanes, "host_capacity", return_value=(host, 3)), \
                    ThreadPoolExecutor(max_workers=2) as workers:
                futures = [
                    workers.submit(check.run_owned, repo, commit, None, check.ALL_STAGES, False,
                                   affected_base=commit, parallel_jvm=True)
                    for _ in range(2)
                ]
                try:
                    deadline = time.monotonic() + 15
                    while not all((work / f"ready-{slot}").exists() for slot in (0, 1)) \
                            and time.monotonic() < deadline:
                        time.sleep(.02)
                    self.assertTrue(all((work / f"ready-{slot}").exists() for slot in (0, 1)),
                                    "Both actual shell lanes must enter before either releases")
                    for slot in (0, 1):
                        lane = work / "local-checks/lanes" / host / str(slot)
                        self.assertTrue((lane / "gradle.lock").is_file())
                        self.assertTrue((lane / "input.bundle").is_file())
                        self.assertTrue((lane / "repos").is_dir())
                    self.assertFalse((work / "local-checks/gradle.lock").exists())
                finally:
                    (work / "release").touch()
                for future in futures:
                    future.result(timeout=20)
                self.assertEqual(check.execution_runtime(repo).hard_lock_records(), [])

    def test_parallel_flag_requires_and_forwards_the_affected_contract(self):
        with patch.object(check, "run_owned") as run, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(check.main(["--parallel-jvm"]), 1)
        run.assert_not_called()
        with patch.object(check, "run_owned") as run:
            self.assertEqual(check.main(["--parallel-jvm", "--affected-base", "a" * 40]), 0)
        self.assertIs(run.call_args.kwargs["parallel_jvm"], True)
        with patch.object(check.sys, "platform", "win32"), \
                patch.object(check, "execution_runtime") as runtime, \
                self.assertRaisesRegex(ValueError, "Invalid exact"):
            check.run_owned(Path.cwd(), "HEAD", None, check.ALL_STAGES, False,
                            affected_base="a" * 40, parallel_jvm=True)
        runtime.assert_not_called()

    def test_supervisor_lane_is_bounded_and_serial_admission_is_unchanged(self):
        import advisory
        self.assertIsNone(advisory.admission_lane({}))
        value = {"host": "a" * 64, "slot": 2, "capacity": 3}
        self.assertEqual(advisory.admission_lane({"jvm_lane": value}), "a" * 64 + "/2")
        for changed in ({"host": "../escape"}, {"slot": True}, {"slot": 3}, {"capacity": 0}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                advisory.admission_lane({"jvm_lane": {**value, **changed}})
        runner = Path(check.__file__).with_name("run.sh").read_text(encoding="utf-8")
        for actual in (
            'exec 9>"$lane_root/gradle.lock"', 'repo="$lane_root/repos/$key"',
            'bundle="$lane_root/input.bundle"', '"$lane_root/run-XXXXXXXX"',
        ):
            self.assertIn(actual, runner)

    def test_affected_scope_cannot_replace_full_pre_push_adapter_or_result_contract(self):
        base = "a" * 40
        options = (
            ["--scope", "protocol"],
            ["--affected-base", "HEAD"],
            ["--affected-base", base, "--pre-push"],
            ["--affected-base", base, "--stages", "scaffold"],
            ["--affected-base", base, "--toolchain-state", "adapter.json"],
            ["--affected-base", base, "--validate-result", "result.json"],
            ["--affected-base", base, "--worktree"],
            ["--affected-base", base, "--install-hook"],
        )
        for arguments in options:
            with self.subTest(arguments=arguments), patch.object(check, "run_owned") as run, \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(check.main(arguments), 1)
            run.assert_not_called()

    def test_explicit_affected_contract_forwards_base_and_additional_scopes_only(self):
        with patch.object(check, "run_owned") as run:
            self.assertEqual(check.main(["--affected-base", "a" * 40, "--scope", "protocol"]), 0)
        self.assertEqual(run.call_args.kwargs, {
            "advisory": False, "affected_base": "a" * 40, "scopes": ["protocol"],
        })
        self.assertEqual(run.call_args.args[3], check.ALL_STAGES)

    def test_shell_forwards_scoped_request_through_existing_supervision_only(self):
        runner = Path(check.__file__).with_name("run.sh").read_text(encoding="utf-8")
        self.assertIn('affected_plan="${13:-}"', runner)
        self.assertIn('affected_base="${14:-}"', runner)
        self.assertEqual(runner.count('"$admission" "$affected_plan" "$affected_base"'), 2)
        self.assertIn('"$mode" == "manual-lane"', runner)
        self.assertIn('options+=(--affected-plan "$affected_plan" --affected-base "$affected_base")', runner)

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
        self.assertIn('fetch --quiet "$bundle" "$revision" HEAD', runner)
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

    def test_retired_installer_preserves_existing_hooks_without_launching_checks(self):
        with tempfile.TemporaryDirectory() as temporary:
            hooks = Path(temporary)
            hook = hooks / "pre-push"
            hook.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            with patch.object(check, "execution_runtime") as runtime, \
                 patch("check.subprocess.run") as run:
                with self.assertRaisesRegex(ValueError, "Automatic pre-push checks have been removed"):
                    check.install(hooks, ["wsl"], None)
            runtime.assert_not_called()
            run.assert_not_called()
            self.assertEqual(hook.read_text(encoding="utf-8"), "#!/bin/sh\nexit 1\n")

    def test_retired_installer_cli_cannot_recreate_hook_or_start_transport(self):
        for options in (["--install-hook"], ["--install-hook", "--pre-push"]):
            with self.subTest(options=options), \
                 patch.object(check, "capture") as capture, \
                 patch.object(check, "run_owned") as run, \
                 patch.object(check, "transport") as transport, \
                 contextlib.redirect_stderr(io.StringIO()) as error:
                self.assertEqual(check.main(options), 1)
            self.assertIn("Automatic pre-push checks have been removed", error.getvalue())
            capture.assert_not_called()
            run.assert_not_called()
            transport.assert_not_called()


class AffectedRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "--quiet")
        self.git("config", "user.name", "Owned scope fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.write("android/core/protocol/build.gradle.kts", 'plugins { id("mesh.jvm.library") }\n')
        self.base = self.commit()
        self.git("update-ref", "refs/remotes/origin/main", self.base)
        self.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        self.write("android/core/protocol/src/test/kotlin/FrameTest.kt", "fixture")
        self.head = self.commit()
        self.ledger = Ledger(self.root / "fixture-ledger.sqlite3", "fixture/repository")

    def git(self, *arguments):
        return subprocess.check_output(
            ["git", "-C", str(self.repo), *arguments], text=True, stderr=subprocess.PIPE,
        ).strip()

    def write(self, name, text):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def commit(self):
        self.git("add", "--all")
        self.git("-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false",
                 "commit", "--quiet", "-m", "owned scope fixture")
        return self.git("rev-parse", "HEAD")

    def cleanup(self, **arguments):
        arguments["completion"].write_text(json.dumps({
            "schema_version": 1, "token": arguments["token"],
            "status": "owned-children-reaped", "returncode": 0,
        }), encoding="utf-8")

    def test_parallel_receipt_is_forwarded_and_only_its_owned_slot_releases(self):
        def candidate(*args, **kwargs):
            admission = json.loads(kwargs["admission"].read_text(encoding="utf-8"))
            self.assertEqual(admission["jvm_lane"], {"host": "a" * 64, "slot": 1, "capacity": 3})
            self.assertEqual(admission["commit"], self.head)
            self.assertEqual(kwargs["affected_plan"]["head"], self.head)
            self.cleanup(**kwargs)

        with patch.object(jvm_lanes, "host_capacity", return_value=("a" * 64, 3)), \
                patch.object(check.sys, "platform", "linux"), \
                patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(self.ledger.path)}), \
                patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check, "transport", return_value=[]), \
                patch.object(check, "run_candidate", side_effect=candidate), \
                contextlib.redirect_stdout(io.StringIO()):
            retained = self.ledger.acquire_jvm_lane("other-live-owner", {
                "commit": self.head, "tree": self.git("rev-parse", "HEAD^{tree}"),
                "token": "retained-fixture", "mode": "manual",
            }, 1.0)
            check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False,
                            affected_base=self.base, parallel_jvm=True)
        self.assertEqual([row["resource"] for row in self.ledger.hard_lock_records()], [retained["resource"]])

    def test_parallel_rejects_private_or_changed_canonical_ledger_before_launch(self):
        private = self.repo / "private.sqlite3"
        private.touch()
        with patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(private)}):
            with self.assertRaisesRegex(ValueError, "private checkout"):
                check.parallel_ledger(self.repo)
        foreign = Ledger(self.root / "foreign.sqlite3", "fixture/repository")
        with patch.object(jvm_lanes, "host_capacity", return_value=("a" * 64, 3)), \
                patch.object(check.sys, "platform", "linux"), \
                patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(self.ledger.path)}), \
                patch.object(check, "execution_runtime", side_effect=[self.ledger, foreign]), \
                patch.object(check, "transport") as transport:
            with self.assertRaisesRegex(ValueError, "authority changed"):
                check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False,
                                affected_base=self.base, parallel_jvm=True)
        transport.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records(), [])
        self.assertEqual(foreign.hard_lock_records(), [])

    def test_parallel_fast_cache_and_private_paths_use_actual_inherited_binding(self):
        value = {"host": "a" * 64, "slot": 1, "capacity": 3}
        with patch.object(Path, "is_file", return_value=True), \
                patch.object(Path, "read_text", return_value=json.dumps({"jvm_lane": value})), \
                patch.object(fast, "owned_lanes", return_value=jvm_lanes), \
                patch.object(jvm_lanes, "host_identity", return_value="a" * 64), \
                patch.object(jvm_lanes, "cpu_capacity", return_value=3):
            self.assertEqual(fast.execution_lane(self.root), self.root / "local-checks/lanes" / ("a" * 64) / "1")
        with patch.object(Path, "is_file", return_value=True), \
                patch.object(Path, "read_text", return_value=json.dumps({"jvm_lane": value})), \
                patch.object(fast, "owned_lanes", return_value=jvm_lanes), \
                patch.object(jvm_lanes, "host_identity", return_value="b" * 64):
            with self.assertRaisesRegex(PortError, "actual host"):
                fast.execution_lane(self.root)

    def test_parallel_unproven_child_cleanup_retains_only_its_actual_slot(self):
        directory = self.root / "unproven-execution"
        directory.mkdir()

        def candidate(*args, **kwargs):
            kwargs["execution"]["started"] = True

        with patch.object(jvm_lanes, "host_capacity", return_value=("a" * 64, 3)), \
                patch.object(check.sys, "platform", "linux"), \
                patch.dict(os.environ, {"ANDROID_PORT_LEDGER": str(self.ledger.path)}), \
                patch.object(check.tempfile, "mkdtemp", return_value=str(directory)), \
                patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check, "transport", return_value=[]), \
                patch.object(check, "run_candidate", side_effect=candidate), \
                contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaisesRegex(ValueError, "without proven owned-child cleanup"):
            check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False,
                            affected_base=self.base, parallel_jvm=True)
        rows = self.ledger.hard_lock_records()
        self.assertEqual(len(rows), 1)
        self.assertTrue(rows[0]["resource"].startswith("gradle-execution:"))
        self.assertEqual(rows[0]["payload"]["commit"], self.head)
        self.assertTrue(directory.exists())

    def test_scoped_request_is_derived_only_under_actual_fixture_ownership(self):
        original = check.affected_request

        def derive(*arguments):
            self.assertEqual([row["resource"] for row in self.ledger.hard_lock_records()], ["gradle-execution"])
            self.assertEqual(self.ledger.hard_lock_records()[0]["payload"]["affected_base"], self.base)
            return original(*arguments)

        def candidate(*args, **kwargs):
            self.assertEqual(kwargs["affected_plan"]["base"], self.base)
            self.assertEqual(kwargs["affected_plan"]["head"], self.head)
            self.assertIn(":core:protocol:test", kwargs["affected_plan"]["tasks"])
            self.cleanup(**kwargs)

        text = io.StringIO()
        with patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check, "affected_request", side_effect=derive), \
                patch.object(check, "transport", return_value=[]), \
                patch.object(check, "run_candidate", side_effect=candidate), \
                contextlib.redirect_stdout(text):
            check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False, affected_base=self.base)
        self.assertEqual(self.ledger.hard_lock_records(), [])
        self.assertIn("AFFECTED LOCAL CHECK PASSED", text.getvalue())

    def test_native_collision_blocks_selection_transport_and_launch(self):
        self.ledger.acquire_hard_lock("native-execution", "fixture-native", {"source": "fixture"}, 1.0)
        with patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check, "affected_request") as derive, \
                patch.object(check, "transport") as transport, \
                patch.object(check, "run_candidate") as candidate:
            with self.assertRaises(ValueError):
                check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False, affected_base=self.base)
        derive.assert_not_called()
        transport.assert_not_called()
        candidate.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records()[0]["owner"], "fixture-native")

    def test_incomplete_scope_runs_nothing_and_releases_only_own_unused_lock(self):
        with patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check, "transport") as transport, \
                patch.object(check, "run_candidate") as candidate:
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False,
                                affected_base=self.base, scopes=["backup"])
        transport.assert_not_called()
        candidate.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_base_movement_during_owned_execution_cannot_report_current_success(self):
        def candidate(*args, **kwargs):
            self.git("update-ref", "refs/remotes/origin/main", self.head)
            self.cleanup(**kwargs)

        text = io.StringIO()
        with patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check, "transport", return_value=[]), \
                patch.object(check, "run_candidate", side_effect=candidate), \
                contextlib.redirect_stdout(text):
            with self.assertRaisesRegex(ValueError, "Stale affected base after execution"):
                check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False, affected_base=self.base)
        self.assertEqual(self.ledger.hard_lock_records(), [])
        self.assertNotIn("AFFECTED LOCAL CHECK PASSED", text.getvalue())

    def test_release_failure_cannot_print_scoped_success(self):
        directory = self.root / "owned-execution"
        directory.mkdir()
        text = io.StringIO()

        def candidate(*args, **kwargs):
            self.cleanup(**kwargs)

        with patch.object(check, "execution_runtime", return_value=self.ledger), \
                patch.object(check.tempfile, "mkdtemp", return_value=str(directory)), \
                patch.object(check, "transport", return_value=[]), \
                patch.object(check, "run_candidate", side_effect=candidate), \
                patch.object(self.ledger, "release_hard_lock", side_effect=PortError("fixture release failed")), \
                contextlib.redirect_stdout(text):
            with self.assertRaisesRegex(ValueError, "Own execution release failed"):
                check.run_owned(self.repo, self.head, None, check.ALL_STAGES, False, affected_base=self.base)
        self.assertNotIn("AFFECTED LOCAL CHECK PASSED", text.getvalue())
        self.assertEqual(len(self.ledger.hard_lock_records()), 1)

    def read_request(self, plan, admission, stages=None):
        path = self.root / "affected-plan.json"
        path.write_text(json.dumps(plan), encoding="utf-8")
        original = Path.read_text

        def read_text(path, *args, **kwargs):
            if path == Path("/proc/self/fd/8"):
                return json.dumps(admission)
            return original(path, *args, **kwargs)

        with patch.object(Path, "read_text", new=read_text), \
                patch.object(fast, "owned_scope", return_value=sys.modules[candidate_plan.__module__]):
            return fast.affected_request(self.repo, path, self.base, stages or plan["stages"], False)

    def test_fast_requires_exact_owned_admission_and_stage_binding(self):
        plan = candidate_plan(self.repo, self.base, self.head)
        admission = {
            "schema_version": 1, "interlock_version": 2, "owner": "local-check:fixture",
            "token": "fixture", "commit": self.head, "tree": plan["tree"],
        }
        self.assertEqual(self.read_request(plan, admission), plan)
        with patch.object(jvm_lanes, "host_identity", return_value="a" * 64), \
                patch.object(fast, "owned_lanes", return_value=jvm_lanes), \
                patch.object(jvm_lanes, "cpu_capacity", return_value=3):
            self.assertEqual(self.read_request(plan, {
                **admission, "jvm_lane": {"host": "a" * 64, "slot": 1, "capacity": 3},
            }), plan)
        for changed in (
            {"commit": "a" * 40}, {"tree": "b" * 40}, {"owner": "other"},
            {"schema_version": True}, {"interlock_version": 1}, {"token": ""},
        ):
            with self.subTest(changed=changed), self.assertRaises(PortError):
                self.read_request(plan, {**admission, **changed})
        with self.assertRaises(PortError):
            self.read_request(plan, admission, ["scaffold"])
        path = self.root / "affected-plan.json"
        with self.assertRaises(PortError):
            fast.affected_request(self.repo, path, self.base, plan["stages"], True)
        with self.assertRaises(PortError):
            fast.affected_request(self.repo, None, self.base, plan["stages"], False)

    def test_scoped_result_is_not_accepted_by_unchanged_full_result_parser(self):
        path = self.root / "affected-plan.json"
        path.write_text(json.dumps(candidate_plan(self.repo, self.base, self.head)), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Malformed"):
            check.validate_result(path, self.head, self.git("rev-parse", "HEAD^{tree}"))

    def test_full_fallback_dispatches_inspection_and_never_claims_default_result(self):
        from controller import ci, ci_environment, local_scope
        self.write("android/core/protocol/build.gradle.kts",
                   'plugins { id("mesh.jvm.library") }\n// broad build change\n')
        head = self.commit()
        plan = candidate_plan(self.repo, self.base, head)
        work = self.root / "work"
        (work / "toolchain").mkdir(parents=True)
        (work / "toolchain/environment.json").write_text(
            json.dumps({"java_home": str(self.root / "fixture-java")}), encoding="utf-8",
        )
        output = self.root / "output"
        output.mkdir()
        text = io.StringIO()
        with patch.object(sys, "argv", [
            "fast.py", "--repo", str(self.repo), "--work", str(work), "--output", str(output),
            "--stages", ",".join(plan["stages"]), "--affected-plan", str(self.root / "request.json"),
            "--affected-base", self.base,
        ]), patch.object(fast, "affected_request", return_value=plan), \
                patch.object(ci_environment, "candidate_environment", return_value={}), \
                patch.object(ci_environment, "verify_wrapper"), \
                patch.object(ci, "python_checks"), patch.object(ci, "preflight"), \
                patch.object(ci, "inspect") as inspect, patch.object(fast, "execute") as execute, \
                patch.object(fast, "owned_scope", return_value=local_scope), \
                patch.object(local_scope, "run_scaffold", return_value={"kind": "local-affected-only"}) as run, \
                contextlib.redirect_stdout(text):
            fast.main()
        inspect.assert_called_once()
        run.assert_called_once()
        self.assertIn("test_runner.py", execute.call_args.args[0][1])
        self.assertIn("AFFECTED SNAPSHOT CHECKS COMPLETE", text.getvalue())
        self.assertNotIn("AFFECTED LOCAL CHECK PASSED", text.getvalue())
        self.assertNotIn("FAST LOCAL CHECK PASSED", text.getvalue())


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
