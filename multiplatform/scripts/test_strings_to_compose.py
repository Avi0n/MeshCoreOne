"""Unit tests for the .strings -> Compose resources converter.

Run: python3 -m unittest discover -s multiplatform/scripts
"""

import importlib.util
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "strings_to_compose", Path(__file__).with_name("strings-to-compose.py")
)
stc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(stc)


def parse_literal(value: str) -> str:
    """Parse a single .strings entry and return its decoded value."""
    with tempfile.NamedTemporaryFile("w", suffix=".strings", delete=False) as f:
        f.write(f'"k" = "{value}";\n')
        path = Path(f.name)
    try:
        entries = stc.parse_strings(path)
        assert len(entries) == 1
        return entries[0][1]
    finally:
        path.unlink()


class ParseStringsTests(unittest.TestCase):
    def test_escaped_apostrophe_and_quote_decode(self):
        self.assertEqual(parse_literal(r"don\'t say \" twice"), "don't say \" twice")

    def test_backslash_newline_tab_unicode(self):
        self.assertEqual(parse_literal(r"a\\b\nc\td \U00e9"), "a\\b\nc\td é")


class AndroidEscapeTests(unittest.TestCase):
    def test_quotes_stay_literal_for_compose_resources(self):
        self.assertEqual(stc.android_escape("don't \"quote\""), "don't \"quote\"")

    def test_backslash_newline_and_xml_entities(self):
        self.assertEqual(stc.android_escape("a\\b\nc & <d>"), "a\\\\b\\nc &amp; &lt;d&gt;")

    def test_leading_at_and_question_mark_escaped(self):
        self.assertEqual(stc.android_escape("@name"), "\\@name")
        self.assertEqual(stc.android_escape("?why"), "\\?why")


class RemapSpecifierTests(unittest.TestCase):
    def test_common_ios_specifiers(self):
        self.assertEqual(stc.remap_specifiers("%@ %1$@ %d %u %lld %%"), "%s %1$s %d %d %d %%")

    def test_width_precision_preserved(self):
        self.assertEqual(stc.remap_specifiers("%.1f %03d"), "%.1f %03d")


class KeyMappingTests(unittest.TestCase):
    def test_dot_key_gets_family_prefix(self):
        self.assertEqual(stc.derive_key("Chats", "welcome.title"), "chats_welcome_title")

    def test_camel_case_splits(self):
        self.assertEqual(stc.derive_key("Onboarding", "welcome.getStarted"), "onboarding_welcome_get_started")
        self.assertEqual(stc.derive_key("Tools", "nodeCLIHelp"), "tools_node_cli_help")

    def test_family_prefix_not_doubled(self):
        self.assertEqual(stc.derive_key("Chats", "chats.blockSender"), "chats_block_sender")

    def test_localizable_family_keeps_prefix(self):
        self.assertEqual(stc.derive_key("Localizable", "common.ok"), "localizable_common_ok")


class AndroidAppFromComposeTests(unittest.TestCase):
    def test_host_xml_maps_fgs_keys_that_used_to_stay_english(self):
        names = {name for name, _ in stc.ANDROID_APP_FROM_COMPOSE}
        sources = {src for _, src in stc.ANDROID_APP_FROM_COMPOSE}
        for required in (
            "shortcut_open_chats_long",
            "notification_packet_rate",
            "notification_channel_discovery",
            "notification_action_speak_status",
        ):
            self.assertIn(required, names)
        for required in (
            "android_shortcut_open_chats_long",
            "android_notification_packet_rate",
            "android_notification_channel_discovery",
            "android_notification_action_speak_status",
        ):
            self.assertIn(required, sources)


class ConvertLocaleCollisionTests(unittest.TestCase):
    def _make_roots(self, chats_lines: str, supplement: str | None = None):
        root = Path(tempfile.mkdtemp())
        lproj = root / "ios" / "en.lproj"
        lproj.mkdir(parents=True)
        (lproj / "Chats.strings").write_text(chats_lines)
        supp_root = None
        if supplement is not None:
            supp_root = root / "supp"
            supp_root.mkdir()
            (supp_root / "en.strings").write_text(supplement)
        return root / "ios", root / "out", supp_root

    def test_same_family_collision_exits(self):
        ios, out, _ = self._make_roots('"blockSender" = "a";\n"block_sender" = "b";\n')
        with self.assertRaises(SystemExit):
            stc.convert_locale(ios, out, "en")

    def test_supplement_collision_with_derived_key_exits(self):
        ios, out, supp = self._make_roots('"blockSender" = "a";\n', '"chats_block_sender" = "b";\n')
        with self.assertRaises(SystemExit):
            stc.convert_locale(ios, out, "en", supplement_root=supp)

    def test_clean_convert_emits_expected_key(self):
        ios, out, supp = self._make_roots('"blockSender" = "don\\\'t";\n', '"android_only_key" = "x";\n')
        counts = stc.convert_locale(ios, out, "en", supplement_root=supp)
        xml = (out / "values" / "strings.xml").read_text()
        self.assertIn('<string name="chats_block_sender">don\'t</string>', xml)
        self.assertIn('<string name="android_only_key">x</string>', xml)
        self.assertEqual(counts["Chats"], 1)
        self.assertEqual(counts["Supplement"], 1)

    def test_stringsdict_replaces_the_always_other_string(self):
        ios, out, _ = self._make_roots(
            '"status.neighborsNotShown" = "%d neighbors not shown";\n'
        )
        (ios / "en.lproj" / "Chats.stringsdict").write_text(
            """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>status.neighborsNotShown</key>
    <dict>
        <key>NSStringLocalizedFormatKey</key>
        <string>%#@count@</string>
        <key>count</key>
        <dict>
            <key>NSStringFormatSpecTypeKey</key>
            <string>NSStringPluralRuleType</string>
            <key>NSStringFormatValueTypeKey</key>
            <string>d</string>
            <key>one</key>
            <string>%d neighbor not shown</string>
            <key>other</key>
            <string>%d neighbors not shown</string>
        </dict>
    </dict>
</dict>
</plist>
"""
        )
        stc.convert_locale(ios, out, "en")
        xml = (out / "values" / "strings.xml").read_text()
        self.assertNotIn('<string name="chats_status_neighbors_not_shown">', xml)
        self.assertIn('<plurals name="chats_status_neighbors_not_shown">', xml)
        self.assertIn('<item quantity="one">%d neighbor not shown</item>', xml)
        self.assertIn('<item quantity="other">%d neighbors not shown</item>', xml)


class ParseStringsdictTests(unittest.TestCase):
    def test_positional_plural_variable_and_required_other(self):
        with tempfile.NamedTemporaryFile("w", suffix=".stringsdict", delete=False) as handle:
            handle.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>dangerZone.partial</key>
    <dict>
        <key>NSStringLocalizedFormatKey</key>
        <string>%2$#@totalNodes@</string>
        <key>totalNodes</key>
        <dict>
            <key>NSStringFormatSpecTypeKey</key>
            <string>NSStringPluralRuleType</string>
            <key>NSStringFormatValueTypeKey</key>
            <string>d</string>
            <key>one</key>
            <string>Removed %1$d of %2$d node</string>
            <key>few</key>
            <string>Removed %1$d of %2$d nodes</string>
            <key>other</key>
            <string>Removed %1$d of %2$d nodes</string>
        </dict>
    </dict>
</dict>
</plist>
"""
            )
            path = Path(handle.name)
        try:
            parsed = stc.parse_stringsdict(path)
        finally:
            path.unlink()
        self.assertEqual(
            parsed["dangerZone.partial"],
            {
                "one": "Removed %1$d of %2$d node",
                "few": "Removed %1$d of %2$d nodes",
                "other": "Removed %1$d of %2$d nodes",
            },
        )

    def test_missing_other_quantity_raises(self):
        with tempfile.NamedTemporaryFile("w", suffix=".stringsdict", delete=False) as handle:
            handle.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0">
<dict>
    <key>only.one</key>
    <dict>
        <key>NSStringLocalizedFormatKey</key>
        <string>%#@count@</string>
        <key>count</key>
        <dict>
            <key>NSStringFormatSpecTypeKey</key>
            <string>NSStringPluralRuleType</string>
            <key>one</key>
            <string>%d item</string>
        </dict>
    </dict>
</dict>
</plist>
"""
            )
            path = Path(handle.name)
        try:
            with self.assertRaises(stc.StringsdictParseError):
                stc.parse_stringsdict(path)
        finally:
            path.unlink()


if __name__ == "__main__":
    unittest.main()
