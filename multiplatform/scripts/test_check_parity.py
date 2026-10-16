"""Unit tests for check-parity localization and DTO gates.

Run: python3 -m unittest discover -s multiplatform/scripts
"""

import contextlib
import importlib.util
import io
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

_spec = importlib.util.spec_from_file_location(
    "check_parity", Path(__file__).with_name("check-parity.py")
)
cp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cp)

SNIPPET = """
public struct WidgetDTO: Sendable, Equatable, Codable {
  public var id: UUID
  public var radioID: UUID
  /// Derived.
  public var hashSize: Int {
    1
  }
  public var name: String
  public var hasLocation: Bool {
    false
  }
}
"""


class SwiftDtoFieldTests(unittest.TestCase):
    def test_stored_properties_skip_computed(self):
        fields = cp.swift_dto_stored_fields(SNIPPET, "WidgetDTO")
        self.assertEqual(fields, ["id", "radioID", "name"])

    def test_missing_struct_returns_none(self):
        self.assertIsNone(cp.swift_dto_stored_fields(SNIPPET, "MissingDTO"))


class SwiftToKotlinNameTests(unittest.TestCase):
    def test_dto_suffix_maps_to_kotlin(self):
        self.assertEqual(cp.swift_dto_kotlin_name("ContactDTO"), "ContactDto")
        self.assertEqual(cp.swift_dto_kotlin_name("DeviceDTO"), "DeviceDto")


class StringsParityTests(unittest.TestCase):
    def setUp(self):
        temp_root = cp.REPO / "tmp"
        temp_root.mkdir(exist_ok=True)
        temp = tempfile.TemporaryDirectory(dir=temp_root)
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.ios = self.root / "ios"
        self.compose = self.root / "compose"
        self.supplement = self.root / "supplement"
        self.host = self.root / "host"
        self.supplement.mkdir()
        self.stc = cp._load_strings_module()
        self.stc.FAMILIES = ["Chats"]
        self.stc.LOCALE_QUALIFIER = {"en": "", "de": "de"}
        self.stc.ANDROID_APP_FROM_COMPOSE = [("radio_tile_label", "chats_title")]
        for locale in self.stc.LOCALE_QUALIFIER:
            lproj = self.ios / f"{locale}.lproj"
            lproj.mkdir(parents=True)
            (lproj / "Chats.strings").write_text('"chats.title" = "Talk to %@";\n')
            (lproj / "Chats.stringsdict").write_text("""<plist><dict>
<key>chats.count</key><dict>
<key>NSStringLocalizedFormatKey</key><string>%#@count@</string>
<key>count</key><dict>
<key>NSStringFormatSpecTypeKey</key><string>NSStringPluralRuleType</string>
<key>one</key><string>%d radio</string>
<key>other</key><string>%d radios</string>
</dict></dict></dict></plist>""")
            (self.supplement / f"{locale}.strings").write_text('"android_only" = "Android";\n')
        self.generate()
        manifest = self.root / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                            '<application android:localeConfig="@xml/locales_config" /></manifest>')
        for name, value in {
            "REPO": self.root,
            "IOS_STRINGS": self.ios,
            "COMPOSE_VALUES": self.compose / "values/strings.xml",
            "SUPPLEMENT_ROOT": self.supplement,
            "ANDROID_APP_RES": self.host,
            "ANDROID_MANIFEST": manifest,
            "LOCALES_CONFIG": self.host / "xml/locales_config.xml",
        }.items():
            patcher = patch.object(cp, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch.object(cp, "_load_strings_module", return_value=self.stc)
        patcher.start()
        self.addCleanup(patcher.stop)

    def generate(self):
        for locale in self.stc.LOCALE_QUALIFIER:
            self.stc.convert_locale(self.ios, self.compose, locale, self.supplement, self.host)
        self.stc.write_locales_config(self.host, list(self.stc.LOCALE_QUALIFIER))

    def mutate(self, path, old, new):
        text = path.read_text()
        self.assertIn(old, text)
        path.write_text(text.replace(old, new))

    def assert_drift(self, *details):
        errors = cp.check_strings()
        self.assertTrue(errors)
        for detail in details:
            self.assertIn(detail, "\n".join(errors))

    def test_generated_resources_pass_without_rewriting_tracked_files(self):
        before = {path: path.read_bytes() for path in self.root.rglob("*.xml")}
        self.assertEqual(cp.check_strings(), [])
        self.assertEqual({path: path.read_bytes() for path in self.root.rglob("*.xml")}, before)

    def test_translated_text_and_placeholder_drift(self):
        path = self.compose / "values-de/strings.xml"
        self.mutate(path, "Talk to %s", "Talk to %d")
        self.assert_drift("compose/values-de/strings.xml", "changed", "chats_title")

    def test_stringsdict_only_plural_quantity_drift(self):
        path = self.compose / "values-de/strings.xml"
        self.mutate(path, '        <item quantity="one">%d radio</item>\n', "")
        self.assert_drift("compose/values-de/strings.xml", "chats_count")

    def test_resource_kind_drift(self):
        path = self.compose / "values/strings.xml"
        self.mutate(path, '<string name="chats_title">Talk to %s</string>',
                    '<plurals name="chats_title"><item quantity="other">Talk to %s</item></plurals>')
        self.assert_drift("chats_title")

    def test_android_supplement_drift(self):
        (self.supplement / "de.strings").write_text('"android_only" = "Updated";\n')
        self.assert_drift("compose/values-de/strings.xml", "android_only")

    def test_translated_android_host_drift(self):
        path = self.host / "values-de/strings.xml"
        self.mutate(path, "Talk to %s", "Old label")
        self.assert_drift("host/values-de/strings.xml", "radio_tile_label")

    def test_default_android_host_is_hand_authored(self):
        path = self.host / "values/strings.xml"
        path.parent.mkdir()
        path.write_text('<resources><string name="app_name">MC1</string></resources>')
        self.assertEqual(cp.check_strings(), [])

    def test_missing_translated_output(self):
        (self.compose / "values-de/strings.xml").unlink()
        self.assert_drift("compose/values-de/strings.xml", "missing")

    def test_missing_ios_family_does_not_pass_after_regeneration(self):
        (self.ios / "de.lproj/Chats.strings").unlink()
        (self.ios / "de.lproj/Chats.stringsdict").unlink()
        self.stc.ANDROID_APP_FROM_COMPOSE = []
        self.generate()
        self.assert_drift("ios/de.lproj/Chats.strings", "missing")

    def test_missing_supplement_does_not_pass_after_regeneration(self):
        (self.supplement / "de.strings").unlink()
        self.generate()
        self.assert_drift("supplement/de.strings", "missing")

    def test_unexpected_resource(self):
        path = self.compose / "values/strings.xml"
        self.mutate(path, "</resources>", '<string name="stale_key">Old</string></resources>')
        self.assert_drift("unexpected", "stale_key")

    def test_duplicate_resource(self):
        path = self.compose / "values/strings.xml"
        self.mutate(path, "</resources>", '<string name="chats_title">Talk to %s</string></resources>')
        self.assert_drift("duplicate", "chats_title")

    def test_duplicate_plural_quantity(self):
        path = self.compose / "values-de/strings.xml"
        self.mutate(path, "</plurals>", '<item quantity="one">%d radio</item></plurals>')
        self.assert_drift("duplicate", "chats_count", "one")

    def test_duplicate_source_keys_are_rejected(self):
        path = self.ios / "en.lproj/Chats.strings"
        self.mutate(path, '"chats.title" = "Talk to %@";',
                    '"chats.title" = "Talk to %@";\n"chats.title" = "Talk to %@";')
        self.assert_drift("generated resources", "duplicate", "chats_title")

    def test_malformed_xml_and_other_locale_drift_both_reported(self):
        (self.compose / "values/strings.xml").write_text("<resources>")
        self.mutate(self.compose / "values-de/strings.xml", "Talk to %s", "Old label")
        self.assert_drift("compose/values/strings.xml", "compose/values-de/strings.xml")

    def test_malformed_sources_do_not_abort_other_locales(self):
        for family_file in ("Chats.strings", "Chats.stringsdict"):
            with self.subTest(family_file=family_file):
                path = self.ios / "en.lproj" / family_file
                original = path.read_text()
                path.write_text("invalid")
                self.mutate(self.compose / "values-de/strings.xml", "Talk to %s", "Old label")
                self.assert_drift("localization source en", "compose/values-de/strings.xml")
                path.write_text(original)
                self.generate()

    def test_malformed_locale_config_preserves_resource_errors(self):
        (self.host / "xml/locales_config.xml").write_text("<locale-config>")
        self.mutate(self.compose / "values-de/strings.xml", "Talk to %s", "Old label")
        self.assert_drift("locales_config.xml", "compose/values-de/strings.xml")

    def test_malformed_stringsdict_integer_does_not_abort_other_locales(self):
        self.mutate(self.ios / "en.lproj/Chats.stringsdict", "</dict></dict></dict>",
                    "<key>extra</key><integer>invalid</integer></dict></dict></dict>")
        self.mutate(self.compose / "values-de/strings.xml", "Talk to %s", "Old label")
        self.assert_drift("localization source en", "compose/values-de/strings.xml")

    def test_missing_android_host_output(self):
        (self.host / "values-de/strings.xml").unlink()
        self.assert_drift("host/values-de/strings.xml", "missing")

    def test_nested_string_content_is_rejected(self):
        path = self.compose / "values-de/strings.xml"
        self.mutate(path, "Talk to %s", "Talk to <b>%s</b>")
        self.assert_drift("nested content", "chats_title")

    def test_resource_attributes_affect_parity(self):
        path = self.compose / "values-de/strings.xml"
        self.mutate(path, 'name="chats_title"', 'name="chats_title" formatted="false"')
        self.assert_drift("changed", "chats_title")

    def test_string_whitespace_is_significant(self):
        self.mutate(self.compose / "values-de/strings.xml", "Talk to %s", " Talk to %s ")
        self.assert_drift("chats_title")

    def test_xml_formatting_and_resource_order_are_ignored(self):
        path = self.compose / "values-de/strings.xml"
        root = ET.parse(path).getroot()
        root[:] = list(reversed(list(root)))
        plural = root.find("plurals")
        plural[:] = list(reversed(list(plural)))
        ET.indent(root, space="  ")
        path.write_text(ET.tostring(root, encoding="unicode"))
        self.assertEqual(cp.check_strings(), [])


class MissingInputTests(unittest.TestCase):
    def test_missing_or_empty_swift_models_do_not_pass(self):
        temp_root = cp.REPO / "tmp"
        temp_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as directory:
            root = Path(directory)
            backup = root / "BackupDtos.kt"
            backup.write_text("")
            for models in (root / "missing", root):
                with self.subTest(models=models), patch.object(cp, "SWIFT_MODELS", models), \
                        patch.object(cp, "BACKUP_DTOS", backup):
                    self.assertTrue(cp.check_swift_dto())

    def test_input_error_does_not_abort_other_checks(self):
        with patch.object(cp, "check_strings", side_effect=OSError("missing strings")), \
                patch.object(cp, "check_backup_room", return_value=["missing Room column"]) as room, \
                contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(cp.main(["strings", "backup-room"]), 1)
        room.assert_called_once()
        self.assertIn("strings: missing strings", stderr.getvalue())
        self.assertIn("missing Room column", stderr.getvalue())

    def test_programming_errors_are_not_swallowed(self):
        with patch.object(cp, "check_strings", side_effect=TypeError("programming error")):
            with self.assertRaises(TypeError):
                cp.main(["strings"])


class CommandLineTests(unittest.TestCase):
    def test_no_arguments_run_all_checks(self):
        with contextlib.ExitStack() as stack:
            checks = [stack.enter_context(patch.object(cp, name, return_value=[])) for name in
                      ("check_strings", "check_backup_room", "check_swift_dto", "check_bridge")]
            stdout = stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            self.assertEqual(cp.main([]), 0)
        for check in checks:
            check.assert_called_once()
        self.assertIn("parity ok: strings, backup-room, swift-dto, bridge", stdout.getvalue())

    def test_unknown_check_is_rejected_before_running_checks(self):
        with patch.object(cp, "check_strings") as strings, \
                contextlib.redirect_stderr(io.StringIO()) as stderr:
            with self.assertRaises(SystemExit) as result:
                cp.main(["unknown"])
        self.assertEqual(result.exception.code, 2)
        self.assertIn("unknown", stderr.getvalue())
        strings.assert_not_called()
