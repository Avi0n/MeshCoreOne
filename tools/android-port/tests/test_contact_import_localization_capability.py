"""AndroidOnly: WP-000 Bounded contact-import recovery resource admission."""

import copy
import json
import subprocess
import unittest
from unittest.mock import patch

from fixtures import REPO, historical_policy_text, policy, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.scope_amendment import (
    CONTACT_IMPORT_LOCALIZATION_CAPABILITY,
    routine_support_predecessor,
)


BASE = "2d1d19fb73adcad1e8ff725fb33a0c9b04dfee98"
ROOT = "android/feature/settings/src/main/res/"
LOCALES = ("values", "values-de", "values-es", "values-fr", "values-it", "values-ko",
           "values-nl", "values-pl", "values-pt", "values-ru", "values-uk", "values-b+zh+Hans")
OPERATION = "add-or-update-contact-import-recovery-copy"


class ContactImportLocalizationCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.policy = policy()
        self.engine = CapabilityEngine(self.policy)
        self.manifest = test_manifest("WP-000")
        self.rule = CONTACT_IMPORT_LOCALIZATION_CAPABILITY

    def test_exact_twelve_paths_and_single_wp_semantic_operation(self):
        self.assertEqual(self.rule["path_rules"], [ROOT + locale + "/strings.xml" for locale in LOCALES])
        self.assertEqual(self.rule["work_packages"], ["WP-210"])
        self.assertEqual(self.rule["operations"], [OPERATION])
        self.assertEqual(self.policy["reservation_policy"]["capabilities"][-1], self.rule)
        for path in self.rule["path_rules"]:
            admitted = self.engine.admit("WP-210", self.manifest.wp("WP-210")["write_paths"], path, OPERATION)
            self.assertEqual(admitted.capability_id, self.rule["id"])
            self.assertEqual(admitted.invariants, tuple(self.rule["invariants"]))
            self.assertEqual(admitted.validation, tuple(self.rule["validation"]))

    def test_every_other_wp_denied_and_primary_owner_unchanged(self):
        before = copy.deepcopy(self.policy)
        before["reservation_policy"]["capabilities"].pop()
        previous = CapabilityEngine(before)
        for wp in self.manifest.work_packages:
            assigned = self.manifest.wp(wp)["write_paths"]
            for path in self.rule["path_rules"]:
                if wp != "WP-210":
                    with self.subTest(wp=wp, path=path), self.assertRaises(PortError):
                        self.engine.admit(wp, assigned, path, OPERATION)
                for operation in ("create", "modify", "test", "generate", "delete"):
                    try:
                        old = previous.admit(wp, assigned, path, operation)
                    except PortError:
                        with self.assertRaises(PortError):
                            self.engine.admit(wp, assigned, path, operation)
                    else:
                        self.assertEqual(self.engine.admit(wp, assigned, path, operation), old)

    def test_wrong_operations_nearby_paths_and_reference_denied(self):
        for path in self.rule["path_rules"]:
            for operation in ("create", "modify", "delete", "test", "generate",
                              "modify-related-consumer", "add-or-update-related-test", "unknown"):
                with self.subTest(path=path, operation=operation), self.assertRaises(PortError):
                    self.engine.admit("WP-210", [], path, operation)
        for path in (
            ROOT.rstrip("/"), ROOT + "values", ROOT + "values/strings.xml.backup",
            ROOT + "values/other.xml", ROOT + "values-en/strings.xml",
            ROOT + "values-ja/strings.xml", ROOT + "values-zh/strings.xml",
            ROOT + "values/../values/strings.xml", ROOT + "values//strings.xml",
            "/" + ROOT + "values/strings.xml", "../" + ROOT + "values/strings.xml",
            ROOT.replace("settings", "onboarding") + "values/strings.xml",
            "android/core/l10n/src/main/res/values/l10n_strings.xml",
            "android/feature/settings/build.gradle.kts",
            "android/feature/settings/src/main/AndroidManifest.xml",
            "MC1/Localizable.xcstrings", "docs/android/localization/exclusions.json",
        ):
            with self.subTest(path=path), self.assertRaises(PortError):
                self.engine.admit("WP-210", [], path, OPERATION)

    def test_existing_consumer_and_test_admissions_remain_separate(self):
        root = "android/feature/settings/src/"
        package = "kotlin/com/meshcoreone/android/feature/settings/app/nodeconfig/"
        for path, operation, capability in (
            (root + "main/" + package + "ResourceNodeConfigMessages.kt",
             "modify-related-consumer", "reviewed-support-consumer"),
            (root + "test/" + package + "ResourceNodeConfigMessagesTest.kt",
             "add-or-update-related-test", "reviewed-support-test"),
        ):
            self.assertEqual(self.engine.admit("WP-210", [], path, operation).capability_id, capability)
            with self.assertRaises(PortError):
                self.engine.admit("WP-210", [], path, OPERATION)

    def test_complete_predecessor_object_and_raw_blob_unchanged(self):
        current = json.loads((REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8"))
        untouched = copy.deepcopy(current)
        original_text = subprocess.check_output(
            ["git", "-C", str(REPO), "show", BASE + ":docs/android/automation-policy.json"],
            encoding="utf-8",
        )
        previous = copy.deepcopy(current)
        self.assertEqual(previous["reservation_policy"]["capabilities"].pop(), self.rule)
        self.assertEqual(previous, json.loads(original_text))
        current_text = (REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8")
        marker = '      },\n      {\n        "id": "' + self.rule["id"] + '"'
        start = current_text.index(marker)
        end = current_text.index("    ]\n  },", start)
        self.assertEqual(current_text[:start] + "      }\n" + current_text[end:], original_text)
        self.assertEqual(routine_support_predecessor(current), routine_support_predecessor(previous))
        self.assertEqual(json.loads(historical_policy_text()), routine_support_predecessor(current))
        self.assertEqual(current, untouched)

    def test_mutated_duplicate_reordered_and_malformed_rule_fails_closed(self):
        for field, value in (
            ("path_rules", [ROOT + "**"]), ("work_packages", ["WP-210", "WP-318"]),
            ("operations", [OPERATION, "modify"]), ("invariants", self.rule["invariants"][:-1]),
            ("validation", []), ("extra", True),
        ):
            changed = copy.deepcopy(self.policy)
            changed["reservation_policy"]["capabilities"][-1][field] = value
            with self.subTest(field=field), self.assertRaises(PortError):
                routine_support_predecessor(changed)
        for mode in ("duplicate", "reordered"):
            changed = copy.deepcopy(self.policy)
            rules = changed["reservation_policy"]["capabilities"]
            if mode == "duplicate":
                rules.append(copy.deepcopy(self.rule))
            else:
                rules.insert(0, rules.pop())
            with self.subTest(mode=mode), self.assertRaises(PortError):
                routine_support_predecessor(changed)

    def test_localization_failure_and_authority_obligations_are_retained(self):
        text = " ".join(self.rule["invariants"] + self.rule["validation"])
        for required in (
            "node_config_contact_save_failed", "all-twelve-supported-locales",
            "confirmed-radio-contact-acceptance", "failed-local-save", "retry-import",
            "configImportFailedPartial", "db14559b39d32322b06477c6ae676112f583db50",
            "generated-l10n-bytes", "WP210-deviations", "not-fabricated-Swift-source-map",
            "WP318-primary-consumer", "WP005-localization", "semantic-overlap-serialize-or-transfer",
            "trusted-main-landing", "fresh-same-owner-admission", "immutable-authorization",
            "no-release-recreate-or-private-ledger-edits", "no-feature-acceptance",
            "ResourceNodeConfigMessages", "NodeConfigContactSaveFailure", "partial true and false",
            "ordinary radio failure and cancellation", "mounted partial-import flow",
            "positive hosted method discovery", "zero failures errors or skips",
        ):
            with self.subTest(required=required):
                self.assertIn(required, text)

    def resource_fixture(self):
        base, candidate, blobs = {}, {}, {}
        for index, path in enumerate(self.rule["path_rules"]):
            base[path], candidate[path] = f"base-{index}", f"candidate-{index}"
            blobs[base[path]] = b'<resources><string name="existing">Keep me</string></resources>'
            blobs[candidate[path]] = (
                '<resources><string name="existing">Keep me</string>'
                f'<string name="node_config_contact_save_failed">Localized fixture {index}</string>'
                '</resources>'
            ).encode()
        return base, candidate, blobs

    def test_immutable_resource_validation_accepts_create_and_update_with_formatting(self):
        base, candidate, blobs = self.resource_fixture()
        operations = dict.fromkeys(candidate, OPERATION)
        CapabilityEngine.validate_support_changes(operations, base, candidate, read_blob=blobs.__getitem__)
        for path in base:
            blobs[base[path]] = blobs[candidate[path]].replace(b"Localized fixture", b"Old fixture")
        CapabilityEngine.validate_support_changes(operations, base, candidate, read_blob=blobs.__getitem__)
        for path in candidate:
            blobs[candidate[path]] = blobs[candidate[path]].replace(b"><", b">\n  <")
        CapabilityEngine.validate_support_changes(operations, base, candidate, read_blob=blobs.__getitem__)
        for path in candidate:
            blobs[candidate[path]] = blobs[candidate[path]].replace(b'<string name="existing">Keep me</string>', b"")
        CapabilityEngine.validate_support_changes(operations, {}, candidate, read_blob=blobs.__getitem__)

    def test_resource_deletion_unrelated_edits_and_missing_locale_are_rejected(self):
        for mode in ("delete", "edit", "remove", "add", "reorder", "root", "omit-locale", "English"):
            base, candidate, blobs = self.resource_fixture()
            path = self.rule["path_rules"][0]
            operations = {path: OPERATION}
            if mode == "delete":
                del candidate[path]
            elif mode == "omit-locale":
                del candidate[self.rule["path_rules"][-1]]
            elif mode == "English":
                blobs[candidate[self.rule["path_rules"][-1]]] = blobs[candidate[path]]
            elif mode == "edit":
                blobs[candidate[path]] = blobs[candidate[path]].replace(b"Keep me", b"Changed")
            elif mode == "remove":
                blobs[candidate[path]] = blobs[candidate[path]].replace(
                    b'<string name="existing">Keep me</string>', b"")
            elif mode == "add":
                blobs[candidate[path]] = blobs[candidate[path]].replace(
                    b"</resources>", b'<string name="extra">Extra</string></resources>')
            elif mode == "root":
                blobs[candidate[path]] = blobs[candidate[path]].replace(
                    b"<resources>", b'<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@string/*">')
            else:
                extra = b'<string-array name="array"><item>A</item><item>B</item></string-array>'
                blobs[base[path]] = blobs[base[path]].replace(b"</resources>", extra + b"</resources>")
                blobs[candidate[path]] = blobs[candidate[path]].replace(b"<resources>", b"<resources>" + extra)
            with self.subTest(mode=mode), self.assertRaises(PortError):
                CapabilityEngine.validate_support_changes(operations, base, candidate, read_blob=blobs.__getitem__)

    def test_invalid_missing_duplicate_nonstring_and_nontranslatable_copy_are_rejected(self):
        for xml in (
            b"<resources>", b"\xff", b"<wrong/>", b"<resources>stray text</resources>",
            b'<!DOCTYPE resources [<!ENTITY x "expanded">]><resources/>',
            b"<resources/>",
            b'<resources><string name="node_config_contact_save_failed"/></resources>',
            b'<resources><string name="node_config_contact_save_failed"> </string></resources>',
            b'<resources><plurals name="node_config_contact_save_failed"><item quantity="one">A</item></plurals></resources>',
            b'<resources><string name="node_config_contact_save_failed" translatable="false">A</string></resources>',
            b'<resources><string name="node_config_contact_save_failed"><b>A</b></string></resources>',
            b'<resources><string name="node_config_contact_save_failed">A</string>'
            b'<string name="node_config_contact_save_failed">B</string></resources>',
        ):
            base, candidate, blobs = self.resource_fixture()
            path = self.rule["path_rules"][0]
            blobs[candidate[path]] = xml
            with self.subTest(xml=xml), self.assertRaises(PortError):
                CapabilityEngine.validate_support_changes(
                    {path: OPERATION}, base, candidate, read_blob=blobs.__getitem__)

    def test_formatting_only_English_fallback_is_rejected(self):
        for formatted in (
            b" Localized fixture 0 ", b"\nLocalized fixture 0\n",
            b"Localized   fixture\t0", b"\n  Localized\n    fixture 0  \n",
            b'"Localized fixture 0"', b'" Localized   fixture 0 "',
            b'Localized\\nfixture\\t0', b'\\u004cocalized fixture 0',
        ):
            base, candidate, blobs = self.resource_fixture()
            english, translated = self.rule["path_rules"][0], self.rule["path_rules"][-1]
            blobs[candidate[translated]] = blobs[candidate[english]].replace(b"Localized fixture 0", formatted)
            with self.subTest(formatted=formatted), self.assertRaisesRegex(PortError, "English fallback"):
                CapabilityEngine.validate_support_changes(
                    {english: OPERATION, translated: OPERATION},
                    base, candidate, read_blob=blobs.__getitem__)

    def test_android_quoting_and_escapes_compare_decoded_values(self):
        for source, expected in (
            ('"Quoted text"', "Quoted text"), (r"Don\'t retry", "Don't retry"),
            (r"Contact \"name\"", 'Contact "name"'), (r"Path\\name", "Path\\name"),
            (r"\@contact \?help", "@contact ?help"),
        ):
            with self.subTest(source=source):
                self.assertEqual(CapabilityEngine._android_copy_comparison(source), expected)
        for source in ('"Unclosed', "Incomplete\\", r"\u12", r"\uXXXX", r"\x41", '""', '" "', r"\n\t"):
            with self.subTest(source=source), self.assertRaises(PortError):
                CapabilityEngine._android_copy_comparison(source)

    def test_unrelated_notices_comments_and_processing_instructions_are_preserved(self):
        for marker in (b"<!-- Copyright notice -->", b"<?localization preserve?>"):
            for position in ("before", "inside", "after"):
                base, candidate, blobs = self.resource_fixture()
                path = self.rule["path_rules"][0]

                def insert(xml):
                    if position == "before":
                        return marker + xml
                    if position == "after":
                        return xml + marker
                    return xml.replace(b"<resources>", b"<resources>" + marker)

                blobs[base[path]] = insert(blobs[base[path]])
                with self.subTest(marker=marker, position=position):
                    with self.assertRaisesRegex(PortError, "unrelated resource"):
                        CapabilityEngine.validate_support_changes(
                            {path: OPERATION}, base, candidate, read_blob=blobs.__getitem__)
                    blobs[candidate[path]] = insert(blobs[candidate[path]])
                    CapabilityEngine.validate_support_changes(
                        {path: OPERATION}, base, candidate, read_blob=blobs.__getitem__)

    def test_resource_validation_requires_immutable_reader_and_exact_paths(self):
        base, candidate, blobs = self.resource_fixture()
        path = self.rule["path_rules"][0]
        with self.assertRaises(PortError):
            CapabilityEngine.validate_support_changes({path: OPERATION}, base, candidate)
        with self.assertRaises(PortError):
            CapabilityEngine.validate_support_changes(
                {"android/app/src/main/res/values/strings.xml": OPERATION},
                base, candidate, read_blob=blobs.__getitem__)

    def test_staging_supplies_bounded_immutable_git_blob_reader(self):
        from controller.model import git, tree
        from controller.staging import stage_review

        head = git(REPO, "rev-parse", "HEAD").decode().strip()
        entries = tree(REPO, head)
        path = "docs/android/automation-policy.json"
        expected = git(REPO, "cat-file", "blob", entries[path])

        def validate(operations, base, candidate, *, read_blob):
            self.assertEqual(operations, {})
            self.assertEqual(base, entries)
            self.assertEqual(candidate, entries)
            self.assertEqual(read_blob(entries[path]), expected)
            raise PortError("fixture stopped after actual immutable reader verification")

        with patch.object(CapabilityEngine, "validate_support_changes", side_effect=validate) as check:
            with self.assertRaisesRegex(PortError, "fixture stopped"):
                stage_review(REPO, "WP-000", head, head, path_operations={})
            check.assert_called_once()
