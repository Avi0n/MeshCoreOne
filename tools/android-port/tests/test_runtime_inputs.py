"""AndroidOnly: WP-003 Committed-tree delivery, not merely a populated developer filesystem."""

import copy
import unittest
from unittest.mock import patch

from fixtures import REPO
from controller.errors import PortError
from controller.model import git, tree
from controller.runtime_inputs import required_inputs, verify_committed_inputs, verify_tree_entries


class RuntimeInputTests(unittest.TestCase):
    def test_every_required_input_exists_in_the_exact_committed_head(self):
        result = verify_committed_inputs(REPO)
        self.assertEqual(result["result"], "valid")
        self.assertTrue(result["checkout_matches_committed_inputs"])
        self.assertIn("tools/android-port/controller/toolchain-pins.json", required_inputs())
        self.assertIn("docs/android/evidence/WP-201/collect_evidence.py", required_inputs())
        self.assertIn("tools/android-port/tests/test_domain_room_evidence.py", required_inputs())

    def test_missing_pins_requirements_or_reader_cannot_be_hidden_by_local_files(self):
        head = git(REPO, "rev-parse", "HEAD").decode().strip()
        entries = tree(REPO, head)
        for path in ("tools/android-port/controller/toolchain-pins.json",
                     "tools/android-port/controller/requirements-ci.txt",
                     "tools/android-port/controller/ci_evidence.py",
                     "tools/android-port/controller/disposable.py",
                     "tools/android-port/controller/windows_guest.py",
                     "tools/android-port/tests/test_disposable.py",
                     "tools/android-port/tests/test_windows_guest.py",
                     "tools/android-port/tests/test_runner.py",
                     "docs/android/evidence/WP-201/collect_evidence.py",
                     "tools/android-port/tests/test_domain_room_evidence.py"):
            changed = copy.deepcopy(entries)
            changed.pop(path, None)
            with self.subTest(path=path), self.assertRaisesRegex(PortError, "committed Git tree"):
                verify_tree_entries(changed)

    def test_checkout_drift_or_uncommitted_replacement_is_not_committed_delivery(self):
        from pathlib import Path

        original = Path.read_bytes

        for name in ("toolchain-pins.json", "collect_evidence.py", "test_domain_room_evidence.py"):
            def read(path):
                return b"uncommitted fixture replacement" if path.name == name else original(path)

            with self.subTest(input=name), patch.object(Path, "read_bytes", read), self.assertRaisesRegex(PortError, "differs"):
                verify_committed_inputs(REPO)
