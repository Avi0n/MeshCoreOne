"""WP-407 pinned source-selected catalog and exact notice verification."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("emoji_convert", Path(__file__).parents[1] / "emoji_convert.py")
EMOJI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EMOJI)


class EmojiCatalogTests(unittest.TestCase):
    def test_actual_packaged_full_source_catalog_and_notices(self):
        data = EMOJI.verified_inputs(EMOJI.ASSETS, installed=True)
        self.assertEqual(4, len(data))
        self.assertEqual(624991, len(data["emoji/emojibase.json"]))
        self.assertEqual(1932, sum(EMOJI.COUNTS.values()))

    def test_missing_or_modified_inputs_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(FileNotFoundError):
                EMOJI.verified_inputs(root)
            for name, (relative, _) in EMOJI.INPUTS.items():
                (root / name).write_bytes((EMOJI.ASSETS / relative).read_bytes())
            (root / "emojibase.json").write_bytes(b"{}")
            with self.assertRaisesRegex(ValueError, "Changed pinned emoji input"):
                EMOJI.verified_inputs(root)

    def test_windows_style_git_checkout_preserves_the_exact_resource_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            checkout = root / "checkout"
            source.mkdir()
            checkout.mkdir()
            for relative, data in EMOJI.verified_inputs(EMOJI.ASSETS, installed=True).items():
                target = source / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            (source / ".gitattributes").write_bytes((EMOJI.ASSETS / ".gitattributes").read_bytes())
            for args in (["init", "-q"], ["config", "core.autocrlf", "true"], ["add", "."]):
                subprocess.run(["git", *args], cwd=source, check=True, capture_output=True)
            subprocess.run(["git", f"--work-tree={checkout}", "checkout-index", "--all", "--force"],
                           cwd=source, check=True, capture_output=True)
            self.assertEqual(EMOJI.verified_inputs(EMOJI.ASSETS, installed=True),
                             EMOJI.verified_inputs(checkout, installed=True))
