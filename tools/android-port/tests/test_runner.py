import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fixtures import REPO
from controller.test_runner import discover_suites, run_suite


class RunnerDiscoveryTests(unittest.TestCase):
    def test_real_installed_runtime_suite_is_discoverable(self):
        suite = discover_suites([REPO / "tools/android-port/local"])
        self.assertGreaterEqual(suite.countTestCases(), 3)

    def test_each_directory_is_discovered_once_in_one_nonzero_suite(self):
        with tempfile.TemporaryDirectory() as temporary:
            first = Path(temporary)
            second = first / "local"
            second.mkdir()
            cases = [unittest.TestCase(), unittest.TestCase()]
            with patch("unittest.TestLoader.discover", side_effect=[
                unittest.TestSuite([cases[0]]), unittest.TestSuite([cases[1]])
            ]) as discover:
                suite = discover_suites([first, second])
            self.assertEqual(suite.countTestCases(), 2)
            self.assertEqual(discover.call_count, 2)
            for call in discover.call_args_list:
                self.assertEqual(call.kwargs, {"pattern": "*test*.py"})

    def test_suffix_named_mandatory_cases_are_executed_not_omitted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "mandatory_suffix_test.py").write_text(
                "import unittest\n"
                "class Required(unittest.TestCase):\n"
                " def test_required(self): self.assertEqual(2 + 2, 4)\n",
                encoding="utf-8",
            )
            suite = discover_suites([root])
            self.assertEqual(suite.countTestCases(), 1)
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(run_suite(suite, 0), 0)

    def test_missing_empty_or_duplicate_additional_directory_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(ValueError, "missing"):
                discover_suites([root / "missing"])
            with self.assertRaisesRegex(ValueError, "zero"):
                discover_suites([root])
            with patch("unittest.TestLoader.discover",
                       return_value=unittest.TestSuite([unittest.TestCase()])):
                with self.assertRaisesRegex(ValueError, "duplicate"):
                    discover_suites([root, root])

    def test_combined_runner_rejects_skipped_assertions(self):
        class Skipped(unittest.TestCase):
            @unittest.skip("synthetic negative fixture")
            def test_skipped(self):
                pass

        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(run_suite(unittest.TestSuite([Skipped("test_skipped")]), 0), 2)


class CandidateFamilyTests(unittest.TestCase):
    CASE = (
        "import unittest\n"
        "class CommittedFixture(unittest.TestCase):\n"
        "    def test_committed_fixture(self):\n"
        "        self.assertEqual(2 + 2, 4)\n"
    )

    @contextlib.contextmanager
    def repository(self):
        with tempfile.TemporaryDirectory(prefix="candidate-family-") as directory:
            self.root = Path(directory).resolve()
            self.repo = self.root / "repo"
            self.repo.mkdir()
            self.git("init", "--quiet")
            self.git("config", "user.name", "Source fixture")
            self.git("config", "user.email", "fixture@example.invalid")
            self.git("config", "core.autocrlf", "false")
            self.write(".gitignore", "__pycache__/\n")
            self.write("controller-fixtures/test_controller_fixture.py", self.CASE)
            self.base = self.commit()
            yield

    def git(self, *args):
        return subprocess.check_output(
            ["git", "-C", str(self.repo), *args], encoding="utf-8", stderr=subprocess.STDOUT,
        ).strip()

    def write(self, path, text):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        return target

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "--quiet", "-m", "private source selection fixture")
        return self.git("rev-parse", "HEAD")

    def run_candidate(self, base, head, *, scope=False, event=None, event_name="push"):
        event_path = self.root / "event.json"
        event_path.write_text(json.dumps(
            {"before": base, "after": head} if event is None else event,
        ), encoding="utf-8")
        command = [
            sys.executable, str(REPO / "tools/android-port/controller/test_runner.py"),
            "--repo", str(self.repo), "--tests", str(self.repo / "controller-fixtures"),
            "--candidate-event-name", event_name, "--candidate-event-path", str(event_path),
        ]
        if scope:
            output = self.root / "scope-output"
            output.write_text("controller=false\n", encoding="utf-8")
            command += ["--scope-output", str(output)]
        return subprocess.run(command, capture_output=True, encoding="utf-8", timeout=30)

    def test_never_present_producer_only_candidate_is_explicitly_not_selected_not_executed(self):
        with self.repository():
            self.write("tools/android-port/controller/producer.py", "# private producer fixture\n")
            head = self.commit()
            scope = self.run_candidate(self.base, head, scope=True)
            self.assertEqual(scope.returncode, 0, scope.stdout + scope.stderr)
            self.assertIn('"result": "NOT SELECTED"', scope.stdout)
            self.assertEqual((self.root / "scope-output").read_text(), "controller=false\n")
            result = self.run_candidate(self.base, head)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            reports = [json.loads(line) for line in result.stdout.splitlines()]
            self.assertEqual(reports[0]["result"], "NOT SELECTED")
            self.assertEqual(reports[1]["discovered"], 1)
            self.assertEqual(reports[1]["run"], 1)
            self.assertEqual(reports[1]["passed"], 1)

    def test_committed_family_is_mandatory_in_head_or_unchanged_from_base_and_runs_real_discovery(self):
        with self.repository():
            self.write("android/accessibility-tests/test_native_fixture.py", self.CASE)
            present = self.commit()
            for base in (self.base, present):
                self.write("unrelated.txt", f"ordinary unrelated change after {base}\n")
                head = self.commit()
                scope = self.run_candidate(base, head, scope=True)
                self.assertEqual(scope.returncode, 0, scope.stdout + scope.stderr)
                self.assertIn("controller=true", (self.root / "scope-output").read_text())
                result = self.run_candidate(base, head)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                reports = [json.loads(line) for line in result.stdout.splitlines()]
                self.assertTrue(reports[0]["selected"])
                self.assertEqual(reports[1]["discovered"], 2)
                self.assertEqual(reports[1]["passed"], 2)
                self.assertIn("test_native_fixture.CommittedFixture.test_committed_fixture", result.stderr)
            for name, event in (
                ("pull_request", {"pull_request": {"base": {"sha": present}, "head": {"sha": head}}}),
                ("merge_group", {"merge_group": {"base_sha": present, "head_sha": head}}),
            ):
                result = self.run_candidate(present, head, event_name=name, event=event)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_removal_from_immutable_base_cannot_become_not_selected(self):
        with self.repository():
            self.write("android/accessibility-tests/test_native_fixture.py", self.CASE)
            base = self.commit()
            self.git("rm", "-r", "--quiet", "android")
            head = self.commit()
            for scope in (False, True):
                result = self.run_candidate(base, head, scope=scope)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn("Selected native suite is missing/deleted", result.stderr)
                self.assertNotIn("NOT SELECTED", result.stdout)

    def test_relevant_change_or_uncommitted_family_cannot_claim_never_present(self):
        for mode in ("relevant-docs", "uncommitted-family"):
            with self.subTest(mode=mode), self.repository():
                if mode == "relevant-docs":
                    self.write("docs/android/deviations/WP-503.md", "private relevant fixture\n")
                    head = self.commit()
                else:
                    head = self.base
                    self.write("android/accessibility-tests/test_uncommitted.py", self.CASE)
                result = self.run_candidate(self.base, head)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertNotIn("NOT SELECTED", result.stdout)

    def test_selected_suite_missing_dirty_untracked_malformed_zero_failed_or_skipped_fails_closed(self):
        for mode in ("missing", "dirty", "untracked", "malformed", "zero", "failed", "skipped"):
            with self.subTest(mode=mode), self.repository():
                text = self.CASE
                if mode == "malformed":
                    text = "def incomplete(\n"
                elif mode == "zero":
                    text = "# fixture with no test cases\n"
                elif mode == "failed":
                    text = text.replace("2 + 2, 4", "2 + 2, 5")
                elif mode == "skipped":
                    text = text.replace("    def test_", '    @unittest.skip("negative fixture")\n    def test_')
                path = self.write("android/accessibility-tests/test_native_fixture.py", text)
                head = self.commit()
                if mode == "missing":
                    path.unlink()
                elif mode == "dirty":
                    path.write_text(self.CASE + "\n", encoding="utf-8")
                elif mode == "untracked":
                    self.write("android/accessibility-tests/test_uncommitted.py", self.CASE)
                result = self.run_candidate(self.base, head)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertNotIn("NOT SELECTED", result.stdout)

    def test_missing_malformed_unknown_or_stale_event_authority_has_no_absence_fallback(self):
        with self.repository():
            self.write("unrelated.txt", "new head\n")
            head = self.commit()
            for event, name in (
                ([], "push"), ({}, "pull_request"), ({}, "workflow_dispatch"),
                ({"before": self.base, "after": self.base}, "push"),
                ({"before": "1" * 40, "after": head}, "push"),
            ):
                with self.subTest(event=event, name=name):
                    result = self.run_candidate(self.base, head, event=event, event_name=name)
                    self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                    self.assertNotIn("NOT SELECTED", result.stdout)
