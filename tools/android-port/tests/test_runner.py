import contextlib
import io
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
