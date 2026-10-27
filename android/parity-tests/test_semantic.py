"""AndroidOnly: WP-501 Test assertion/input proposal extraction, including real Kotlin string/lambda syntax."""

import contextlib
import io
import json
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

from controller.errors import PortError
import semantic


class KotlinProposalTest(unittest.TestCase):
    def units(self, text):
        syntax = semantic.kotlin_syntax(text)
        methods = semantic.kotlin_methods(syntax, text)
        return syntax, methods

    def test_nested_template_quotes_do_not_destroy_delimiter_matching(self):
        text = 'class Tests { @Test fun example() { assertEquals("a${"AB".repeat(2)}", actual) } }'
        syntax, methods = self.units(text)
        self.assertEqual(["example"], [row["name"] for row in methods])
        self.assertEqual('assertEquals("a${"AB".repeat(2)}", actual)',
                         semantic.calls(syntax, text, methods[0]["begin_token"], methods[0]["end_token"])[0]["expression"])

    def test_character_literals_and_nested_comments_do_not_become_code(self):
        syntax = semantic.kotlin_syntax("fun sample() { /* { /* ( */ } */ val x = '}'; assertEquals('\\'', x) }")
        self.assertEqual(["assertEquals"], [row["name"] for row in semantic.calls(syntax, syntax.text, 0, len(syntax.tokens))
                                           if row["assertion_or_checked_wait"]])

    def test_escaped_original_name_is_preserved_as_a_name_not_backticks(self):
        _, methods = self.units('class Tests { @Test fun `Decrypt empty message`() = Fixture.assertResult("") }')
        self.assertEqual("Decrypt empty message", methods[0]["name"])

    def test_action_call_does_not_duplicate_entire_trailing_test_body(self):
        text = 'fun sample() = runTest { assertEquals(3, actual) }'
        syntax, methods = self.units(text)
        calls = semantic.calls(syntax, text, methods[0]["begin_token"], methods[0]["end_token"])
        self.assertEqual(["assertEquals(3, actual)"], [row["expression"] for row in calls])

    def test_assertion_failure_lambda_remains_visible(self):
        text = 'fun sample() { assertFailsWith<Broken> { work() } }'
        syntax, _ = self.units(text)
        assertions = [row for row in semantic.calls(syntax, text, 0, len(syntax.tokens))
                      if row["assertion_or_checked_wait"]]
        self.assertEqual("assertFailsWith<Broken> { work() }", assertions[0]["expression"])

    def test_pair_family_carries_actual_assertions_and_registration(self):
        text = 'class Tests { @TestFactory fun paths() = sourceCases("Paths", "zero" to { assertEquals(0, value) }) }'
        syntax, methods = self.units(text)
        families = semantic.named_lambdas(syntax, text, methods)
        self.assertEqual(["zero"], [row["name"] for row in families])
        calls = semantic.calls(syntax, text, families[0]["begin_token"], families[0]["end_token"])
        self.assertEqual("assertEquals(0, value)", calls[0]["expression"])

    def test_suite_qualified_original_builder_binds_case_not_suite_label(self):
        text = 'class Tests { @TestFactory fun cases() = listOf(original("Suite", "row") { assertTrue(value) }) }'
        syntax, methods = self.units(text)
        families = semantic.named_lambdas(syntax, text, methods)
        self.assertEqual(1, len(families))
        self.assertEqual("row", families[0]["name"])
        self.assertEqual(["Suite::row()"], families[0]["explicit_original_case_ids"])
        self.assertEqual("cases", families[0]["factory_method"])

    def test_generic_named_builder_keeps_first_literal_as_name(self):
        text = 'fun cases() = listOf(otherBuilder("row", "input") { assertTrue(value) })'
        syntax, methods = self.units(text)
        family = semantic.named_lambdas(syntax, text, methods)[0]
        self.assertEqual("row", family["name"])
        self.assertEqual([], family["explicit_original_case_ids"])

    def test_original_file_reader_is_not_a_dynamic_test_builder(self):
        text = 'fun cases() { val source = original("MeshCore/LICENSE"); assertTrue(source.isNotEmpty()) }'
        syntax, methods = self.units(text)
        self.assertEqual([], semantic.named_lambdas(syntax, text, methods))

    def test_original_parameter_signature_and_row_are_retained(self):
        text = ('fun cases() = listOf(original("Suite", "row", "(_ value : Int)", "[value: 3]") '
                '{ assertEquals(3, value) })')
        syntax, methods = self.units(text)
        family = semantic.named_lambdas(syntax, text, methods)[0]
        self.assertEqual("row", family["name"])
        self.assertEqual(["Suite::row(_ value : Int)[value: 3]"], family["explicit_original_case_ids"])

    def test_computed_original_parameter_row_is_preserved_as_unresolved_not_guessed(self):
        text = 'fun cases() = listOf(original("Suite", "row", "(value : Int) [$value]") { assertTrue(value) })'
        syntax, methods = self.units(text)
        family = semantic.named_lambdas(syntax, text, methods)[0]
        self.assertEqual("row", family["name"])
        self.assertEqual([], family["explicit_original_case_ids"])
        self.assertEqual("nonliteral-signature-or-row-requires-explicit-runner-review",
                         family["registration_identity_resolution"])
        self.assertIn("[$value]", family["registration"])

    def test_original_builder_does_not_accept_nonliteral_case_name(self):
        text = 'fun cases() = listOf(original("Suite", "$computed") { assertTrue(value) })'
        syntax, methods = self.units(text)
        with self.assertRaisesRegex(PortError, "literal suite and case"):
            semantic.named_lambdas(syntax, text, methods)

    def test_named_builder_preserves_signature_and_parameter_arguments(self):
        text = 'fun sample() = listOf(advertParameterizedCase("heard", "(policy:)", listOf(0, 1)) { assertEquals(1, it) })'
        syntax, methods = self.units(text)
        family = semantic.named_lambdas(syntax, text, methods)[0]
        self.assertEqual("heard", family["name"])
        self.assertIn('listOf(0, 1)', family["registration"])

    def test_raw_string_braces_are_not_scanned_as_test_bodies(self):
        syntax, methods = self.units('fun sample() { assertEquals("""{ "x": ["]"] }""", actual) }')
        self.assertEqual(1, len(methods))
        self.assertEqual(1, len([row for row in semantic.calls(syntax, syntax.text, 0, len(syntax.tokens))
                                 if row["assertion_or_checked_wait"]]))

    def test_malformed_or_unterminated_syntax_fails_closed(self):
        for text in ('fun test() {', 'fun test() { "]', "fun test() { 'x", "/* /* nested */"):
            with self.subTest(text=text), self.assertRaises(PortError):
                semantic.kotlin_syntax(text)

    def test_helper_assertion_is_not_declared_equivalent_by_its_name(self):
        text = 'fun sample() = CryptoFixtures.assertChannel(CryptoFixtures.message("channel-empty"))'
        syntax, methods = self.units(text)
        calls = semantic.calls(syntax, text, methods[0]["begin_token"], methods[0]["end_token"])
        self.assertEqual("CryptoFixtures.assertChannel", calls[0]["name"])
        self.assertNotIn("equivalent", calls[0])

    def test_plural_source_case_annotation_binds_the_actual_method_body(self):
        text = 'class Tests { @Test @SourceCases("A::first()", "A::second()") fun combined() { assertEquals(1, actual) } }'
        syntax, methods = self.units(text)
        self.assertEqual(["A::first()", "A::second()"],
                         semantic.annotated_cases(syntax, methods)[methods[0]["declaration_token"]])

    def test_kotlin_unicode_escaped_original_identity_is_decoded(self):
        syntax, methods = self.units(
            r'class Tests { @OriginalCase("A::first \u2014 row()") fun checked() { assertTrue(true) } }')
        self.assertEqual(["A::first \u2014 row()"],
                         semantic.annotated_cases(syntax, methods)[methods[0]["declaration_token"]])

    def test_kotlin_surrogate_pair_in_literal_identity_is_one_scalar(self):
        syntax, methods = self.units(
            r'class Tests { @OriginalCase("A::\uD83D\uDE80()") fun checked() { assertTrue(true) } }')
        self.assertEqual(["A::\U0001F680()"],
                         semantic.annotated_cases(syntax, methods)[methods[0]["declaration_token"]])

    def test_kotlin_raw_literal_does_not_decode_backslash_escapes(self):
        syntax = semantic.kotlin_syntax(r'val name = """A::\u2014()"""')
        token = next(token for token in syntax.tokens if token.kind == "string")
        self.assertEqual(r"A::\u2014()", semantic.literal(token))

    def test_interpolated_original_id_cannot_become_literal_authority(self):
        for text in ('"A::$name()"', '"A::${name}()"', '"""A::$name()"""',
                     '"A::$\u00e9()"', '"A::$`name`()"'):
            with self.subTest(text=text):
                syntax, methods = self.units(
                    f'class Tests {{ @OriginalCase({text}) fun checked() {{ assertTrue(true) }} }}')
                with self.assertRaisesRegex(PortError, "literal case identities"):
                    semantic.annotated_cases(syntax, methods)
        syntax, methods = self.units(
            r'class Tests { @OriginalCase("A::\$literal()") fun checked() { assertTrue(true) } }')
        self.assertEqual(["A::$literal()"],
                         semantic.annotated_cases(syntax, methods)[methods[0]["declaration_token"]])

    def test_malformed_kotlin_unicode_identity_fails_closed(self):
        for text in (r"\u12", r"\uGGGG", r"\uD800", r"\u{2014}", r"\q"):
            with self.subTest(text=text):
                syntax, methods = self.units(
                    f'class Tests {{ @OriginalCase("A::{text}()") fun checked() {{ assertTrue(true) }} }}')
                with self.assertRaisesRegex(PortError, "literal case identities"):
                    semantic.annotated_cases(syntax, methods)

    def test_dynamic_labels_inside_a_previous_method_are_not_annotations_on_next(self):
        syntax, methods = self.units('fun first() { @SourceCase("A::first()") local() }; fun next() {}')
        with self.assertRaisesRegex(PortError, "nested inside"):
            semantic.annotated_cases(syntax, methods)

    def test_name_and_explicit_annotation_join_the_same_body_once(self):
        row = {"path": "test.kt", "class": "Test", "method_or_family_name": "checked",
               "line": 10, "body_sha256": "b" * 64, "factory_method": None}
        self.assertEqual([row], semantic.joined_candidates(
            {"checked": [row], "Original::checked()": [row]}, "checked", "Original::checked()"))

    def test_same_named_bodies_in_distinct_factories_remain_distinct(self):
        row = {"path": "test.kt", "class": "Test", "method_or_family_name": "checked",
               "line": 10, "body_sha256": "b" * 64, "factory_method": "contactPaths"}
        other = {**row, "factory_method": "messagePaths", "line": 20}
        self.assertEqual([row, other], semantic.joined_candidates(
            {"checked": [row, other]}, "checked", "Original::checked()"))


class SemanticBindingTest(unittest.TestCase):
    def fixture(self):
        native = {
            "path": "android/native.kt", "method_or_family_name": "checked", "factory_method": None,
            "assertions": [{"name": "assertEquals", "expression": "assertEquals(1, actual)"}], "class": "Native",
        }
        family = {
            "source_path": "Original.swift", "source_blob_sha": "a" * 40, "case_id": "Original::checked()",
            "parameter_family": "single", "declaration_sha256": "b" * 64,
            "assertions": [{"expression": "#expect(actual == 1)"}], "inputs": None,
            "native_proposals": [native],
        }
        group = {
            "id": "semantic", "source_path": "Original.swift", "native_path": "android/native.kt",
            "methods": ["checked"], "helpers": [], "runner_name": "method()", "runner_class": "Native",
            "runner_directory": "core/module/build/test-results/test",
        }
        value = {"schema_version": 1, "source_sha": semantic.SOURCE_SHA,
                 "status": "candidate-semantic-comparisons-not-independent-approval", "groups": [group]}
        return {"WP-101": {"families": [family]}}, value

    def test_actual_assertions_inputs_and_exact_runner_are_bound_not_accepted(self):
        groups, value = self.fixture()
        with patch.object(semantic, "load_json", return_value=value):
            result = semantic.bind_reviews(groups)
        family = result["families"][0]
        self.assertEqual(["Native", "checked()"], family["runner_identity"])
        self.assertEqual("#expect(actual == 1)", family["original_assertions"][0]["expression"])
        self.assertIn("awaiting-independent-review", family["status"])

    def test_missing_original_or_extra_method_fails(self):
        for methods in ([], ["checked", "extra"], ["checked", "checked"]):
            groups, value = self.fixture()
            value["groups"][0]["methods"] = methods
            with self.subTest(methods=methods), patch.object(semantic, "load_json", return_value=value), self.assertRaises(PortError):
                semantic.bind_reviews(groups)

    def test_matching_name_without_assertions_is_not_a_semantic_proposal(self):
        groups, value = self.fixture()
        groups["WP-101"]["families"][0]["native_proposals"][0]["assertions"] = []
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "actual native assertions"):
            semantic.bind_reviews(groups)

    def helper_definition(self, text, name="expectFailure"):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        repo = Path(directory.name)
        path = "android/core/protocol/src/test/kotlin/Helper.kt"
        helper = repo.joinpath(*path.split("/"))
        helper.parent.mkdir(parents=True)
        helper.write_text(text, encoding="utf-8")
        group = {"helpers": [path], "checked_assertion_helpers": {name: path}}
        return semantic.checked_assertion_helpers(group, repo)

    def test_checked_helper_binds_actual_definition_assertions_and_bytes(self):
        text = 'fun expectFailure(event: Event) { val failure = assertIs<Failure>(event); assertTrue(failure.reason.contains("short")) }'
        definition = self.helper_definition(text)["expectFailure"]
        self.assertEqual(semantic.text_digest(text), definition["sha256"])
        self.assertEqual(["assertIs", "assertTrue"], [call["name"] for call in definition["assertions"]])
        self.assertIn('contains("short")', definition["assertions"][1]["expression"])

    def test_checked_helper_without_assertions_cannot_supply_proof_by_name(self):
        with self.assertRaisesRegex(PortError, "no actual assertions"):
            self.helper_definition("fun expectFailure(event: Event) = event")

    def test_missing_checked_helper_definition_fails_closed(self):
        with self.assertRaisesRegex(PortError, "helper definition"):
            self.helper_definition("fun other(event: Event) { assertTrue(true) }")

    def test_overloaded_checked_helper_definition_is_not_guessed(self):
        with self.assertRaisesRegex(PortError, "ambiguous"):
            self.helper_definition("fun expectFailure(event: Event) { assertTrue(true) }\n"
                                   "fun expectFailure(text: String) { assertTrue(true) }")

    def test_checked_helper_must_be_explicitly_bound_test_input(self):
        for mapping in ([], {"expectFailure": "android/production.kt"},
                        {"unknown": "android/core/protocol/src/test/kotlin/Unlisted.kt"}):
            with self.subTest(mapping=mapping), self.assertRaisesRegex(PortError, "unbound"):
                semantic.checked_assertion_helpers(
                    {"helpers": [], "checked_assertion_helpers": mapping}, semantic.audit.REPO)

    def test_invoked_checked_helper_keeps_exact_case_and_unapproved_status(self):
        groups, value = self.fixture()
        native = groups["WP-101"]["families"][0]["native_proposals"][0]
        native.update(assertions=[], actions_and_helper_calls=[
            {"name": "expectFailure", "expression": "expectFailure(actual, reason = \"short\")"}])
        definitions = self.helper_definition("fun expectFailure(event: Event) { assertIs<Failure>(event) }")
        with patch.object(semantic, "load_json", return_value=value), \
                patch.object(semantic, "checked_assertion_helpers", return_value=definitions):
            family = semantic.bind_reviews(groups)["families"][0]
        self.assertEqual(["Native", "checked()"], family["runner_identity"])
        self.assertEqual([], family["native"]["assertions"])
        checked = family["native_checked_helper_assertions"][0]
        self.assertEqual('expectFailure(actual, reason = "short")', checked["invocation"]["expression"])
        self.assertEqual("assertIs", checked["definition"]["assertions"][0]["name"])
        self.assertIn("awaiting-independent-review", family["status"])

    def test_uninvoked_checked_helper_does_not_make_empty_case_a_proposal(self):
        groups, value = self.fixture()
        groups["WP-101"]["families"][0]["native_proposals"][0].update(
            assertions=[], actions_and_helper_calls=[{"name": "unrelated", "expression": "unrelated()"}])
        definitions = self.helper_definition("fun expectFailure(event: Event) { assertIs<Failure>(event) }")
        with patch.object(semantic, "load_json", return_value=value), \
                patch.object(semantic, "checked_assertion_helpers", return_value=definitions), \
                self.assertRaisesRegex(PortError, "actual native assertions"):
            semantic.bind_reviews(groups)

    def test_assert_prefixed_checked_helper_binds_its_real_nested_assertions(self):
        groups, value = self.fixture()
        native = groups["WP-101"]["families"][0]["native_proposals"][0]
        invocation = {"name": "assertFields", "expression": "assertFields(actual)"}
        native.update(assertions=[invocation], actions_and_helper_calls=[])
        definitions = self.helper_definition(
            "fun assertFields(value: Result) { assertEquals(1, value.first); assertEquals(2, value.second) }",
            name="assertFields",
        )
        with patch.object(semantic, "load_json", return_value=value), \
                patch.object(semantic, "checked_assertion_helpers", return_value=definitions):
            family = semantic.bind_reviews(groups)["families"][0]
        self.assertEqual(1, len(family["native_checked_helper_assertions"]))
        checked = family["native_checked_helper_assertions"][0]
        self.assertEqual(invocation, checked["invocation"])
        self.assertEqual(["assertEquals(1, value.first)", "assertEquals(2, value.second)"],
                         [call["expression"] for call in checked["definition"]["assertions"]])
        self.assertEqual(["Native", "checked()"], family["runner_identity"])
        self.assertIn("awaiting-independent-review", family["status"])

    def test_ambiguous_same_named_lambdas_fail_without_factory_qualification(self):
        groups, value = self.fixture()
        groups["WP-101"]["families"][0]["native_proposals"] *= 2
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "ambiguous"):
            semantic.bind_reviews(groups)

    def test_unreviewed_rename_is_not_guessed(self):
        groups, value = self.fixture()
        value["groups"][0]["native_method_overrides"] = {"extra": "different"}
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "unknown original"):
            semantic.bind_reviews(groups)

    def test_candidate_cannot_promote_its_own_comparison_to_approval(self):
        groups, value = self.fixture()
        value["status"] = "PASS"
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "approval"):
            semantic.bind_reviews(groups)

    def test_wrong_runner_class_is_rejected(self):
        groups, value = self.fixture()
        value["groups"][0]["runner_class"] = "Unrelated"
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "runner class"):
            semantic.bind_reviews(groups)

    def test_original_factory_suite_identity_cannot_bind_another_suite(self):
        groups, value = self.fixture()
        value["groups"][0]["runner_name"] = "original-case-id"
        groups["WP-101"]["families"][0]["native_proposals"][0]["explicit_original_case_ids"] = ["Other::checked()"]
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "actual native assertions"):
            semantic.bind_reviews(groups)

    def test_unresolved_parameter_row_cannot_claim_literal_runner_identity(self):
        groups, value = self.fixture()
        value["groups"][0]["runner_name"] = "original-case-id"
        groups["WP-101"]["families"][0]["native_proposals"][0]["registration_identity_resolution"] = \
            "nonliteral-signature-or-row-requires-explicit-runner-review"
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "actual native assertions"):
            semantic.bind_reviews(groups)

    def test_zero_execution_requirement_fails_closed(self):
        with self.assertRaisesRegex(PortError, "Zero reviewed runner"):
            semantic.verify_execution({"families": []}, semantic.audit.REPO / "android")

    def test_junit4_annotation_binds_the_actual_renamed_method_identity(self):
        groups, value = self.fixture()
        native = groups["WP-101"]["families"][0]["native_proposals"][0]
        native["method_or_family_name"] = "nativeChecked"
        native["explicit_original_case_ids"] = ["Original::checked()"]
        value["groups"][0]["runner_name"] = "annotated-method"
        with patch.object(semantic, "load_json", return_value=value):
            result = semantic.bind_reviews(groups)
        self.assertEqual(["Native", "nativeChecked"], result["families"][0]["runner_identity"])

    def test_junit4_matching_name_without_annotation_is_not_guessed(self):
        groups, value = self.fixture()
        value["groups"][0]["runner_name"] = "annotated-method"
        with patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "actual native assertions"):
            semantic.bind_reviews(groups)

    def test_explicit_unannotated_junit4_shape_uses_plain_runner_name(self):
        groups, value = self.fixture()
        value["groups"][0]["runner_name"] = "method"
        with patch.object(semantic, "load_json", return_value=value):
            result = semantic.bind_reviews(groups)
        self.assertEqual(["Native", "checked"], result["families"][0]["runner_identity"])

    def test_disclosed_rename_cannot_bypass_required_original_annotation(self):
        groups, value = self.fixture()
        value["groups"][0].update(runner_name="annotated-method", native_method_overrides={"checked": "renamed"})
        native = groups["WP-101"]["families"][0]["native_proposals"][0]
        native.update(method_or_family_name="renamed", source_references=["Original.swift"])
        with patch.object(semantic, "load_json", return_value=value), \
                patch.object(semantic, "native_units", return_value=({"renamed": [native]}, {})), \
                self.assertRaisesRegex(PortError, "actual native assertions"):
            semantic.bind_reviews(groups)

    def test_explicit_partial_file_keeps_uninspected_families_visible(self):
        groups, value = self.fixture()
        original = groups["WP-101"]["families"][0]
        groups["WP-101"]["families"].append({**original, "case_id": "Original::uninspected()"})
        value["groups"][0]["partial_source_file"] = True
        with patch.object(semantic, "load_json", return_value=value):
            result = semantic.bind_reviews(groups)
        self.assertEqual(1, len(result["families"]))
        self.assertEqual(2, result["families"][0]["source_file_family_count"])
        self.assertEqual(1, result["families"][0]["group_compared_family_count"])
        self.assertTrue(result["families"][0]["partial_source_file"])

    def test_partial_file_flag_cannot_hide_missing_unknown_or_duplicate_methods(self):
        for partial, methods in ((True, []), (True, ["unknown"]), (True, ["checked", "checked"]),
                                 (True, ["checked"]), ("true", ["checked"])):
            groups, value = self.fixture()
            value["groups"][0].update(partial_source_file=partial, methods=methods)
            with self.subTest(partial=partial, methods=methods), \
                    patch.object(semantic, "load_json", return_value=value), self.assertRaises(PortError):
                semantic.bind_reviews(groups)

    def test_same_original_cannot_inflate_comparisons_across_groups(self):
        groups, value = self.fixture()
        value["groups"].append({**value["groups"][0], "id": "duplicate-original"})
        with patch.object(semantic, "load_json", return_value=value), \
                self.assertRaisesRegex(PortError, "more than once"):
            semantic.bind_reviews(groups)

    def test_many_to_many_target_keeps_the_actual_native_class_and_runner(self):
        groups, value = self.fixture()
        native = groups["WP-101"]["families"][0]["native_proposals"][0]
        native.update(path="android/app/native.kt", **{"class": "AppNative"})
        value["groups"][0]["native_target_overrides"] = {"checked": {
            "native_path": "android/app/native.kt", "runner_class": "AppNative",
            "runner_directory": "app/build/test-results/testDebugUnitTest",
        }}
        with patch.object(semantic, "load_json", return_value=value):
            family = semantic.bind_reviews(groups)["families"][0]
        self.assertEqual(["AppNative", "checked()"], family["runner_identity"])
        self.assertEqual("app/build/test-results/testDebugUnitTest", family["runner_directory"])

    def test_unknown_or_partial_target_override_fails_closed(self):
        for target in ({"unknown": {}}, {"checked": {"native_path": "android/app.kt"}}):
            groups, value = self.fixture()
            value["groups"][0]["native_target_overrides"] = target
            with self.subTest(target=target), patch.object(semantic, "load_json", return_value=value), self.assertRaisesRegex(PortError, "target adaptation"):
                semantic.bind_reviews(groups)


class SemanticCommandTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.groups, self.review = SemanticBindingTest().fixture()
        self.source_maps = {"current_source_maps": True}

    def load_review(self, path):
        self.assertEqual(semantic.repo_path(self.repo, semantic.REVIEW_PROPOSALS), path)
        return self.review

    def run_check(self, arguments=("--check",), *, validation_error=None):
        output, errors = io.StringIO(), io.StringIO()
        with patch.object(semantic.audit, "generate", return_value=self.source_maps,
                          side_effect=validation_error) as source, \
                patch.object(semantic, "proposals", return_value=self.groups) as proposals, \
                patch.object(semantic, "load_json", side_effect=self.load_review), \
                patch.object(semantic, "write_or_check") as writer, \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = semantic.main(arguments, self.repo)
        source.assert_called_once_with(self.repo)
        if validation_error is None:
            proposals.assert_called_once_with(self.repo, source_maps=self.source_maps)
        else:
            proposals.assert_not_called()
        writer.assert_not_called()
        return result, output.getvalue(), errors.getvalue()

    def archived_outputs(self):
        archives = (
            semantic.repo_path(self.repo, f"{semantic.GROUP_ROOT}/WP-101.json"),
            semantic.repo_path(self.repo, f"{semantic.GROUP_ROOT}/obsolete-owner.json"),
            semantic.repo_path(self.repo, semantic.REVIEW_BINDINGS),
            semantic.repo_path(self.repo, semantic.audit.OUTPUT),
        )
        for path in archives:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"deliberately stale archived output")
        return {path: path.read_bytes() for path in archives}

    def report(self, body='<testcase classname="Native" name="checked()"/>',
               tests=1, failures=0, errors=0, skipped=0):
        path = self.repo.joinpath("android", "core", "module", "build", "test-results", "test", "TEST-Native.xml")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">{body}</testsuite>',
            encoding="utf-8",
        )
        return path

    def test_stale_and_extra_archives_neither_gate_check_nor_get_written(self):
        before = self.archived_outputs()
        status, output, errors = self.run_check()
        self.assertEqual(0, status, errors)
        self.assertEqual(1, json.loads(output)["candidate_semantically_compared_families"])
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_missing_archives_do_not_get_created_by_check(self):
        status, _, errors = self.run_check()
        self.assertEqual(0, status, errors)
        self.assertEqual([], list(self.repo.rglob("*")))

    def test_verify_execution_uses_current_bindings_not_stale_archive(self):
        before = self.archived_outputs()
        self.report()
        status, output, errors = self.run_check(("--check", "--verify-execution"))
        self.assertEqual(0, status, errors)
        execution = json.loads(output)["execution"]["core/module/build/test-results/test"]
        self.assertEqual(1, execution["passed"])
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_verify_execution_can_limit_checks_to_selected_runner_directories(self):
        self.review["groups"][0]["runner_directory"] = "app/build/test-results/testDebugUnitTest"
        path = self.repo / "android/app/build/test-results/testDebugUnitTest/TEST-Native.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="Native" name="checked()"/></testsuite>',
            encoding="utf-8",
        )
        status, output, errors = self.run_check((
            "--check", "--verify-execution", "--runner-directory",
            "app/build/test-results/testDebugUnitTest",
        ))
        self.assertEqual(0, status, errors)
        self.assertEqual(
            {"app/build/test-results/testDebugUnitTest"},
            set(json.loads(output)["execution"]),
        )

    def test_runner_directory_requires_execution_verification(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            status = semantic.main(
                ("--runner-directory", "app/build/test-results/testDebugUnitTest"),
                self.repo,
            )
        self.assertEqual(2, status)
        self.assertIn("--runner-directory requires --verify-execution", errors.getvalue())

    def test_verify_execution_alone_is_read_only_and_uses_current_runner(self):
        native = self.groups["WP-101"]["families"][0]["native_proposals"][0]
        native["method_or_family_name"] = "renamedCurrent"
        native["explicit_original_case_ids"] = ["Original::checked()"]
        self.review["groups"][0]["runner_name"] = "annotated-method"
        self.report('<testcase classname="Native" name="renamedCurrent"/>')
        status, output, errors = self.run_check(("--verify-execution",))
        self.assertEqual(0, status, errors)
        self.assertEqual(1, json.loads(output)["execution"]["core/module/build/test-results/test"]["passed"])
        self.assertFalse(semantic.repo_path(self.repo, semantic.REVIEW_BINDINGS).exists())

    def test_check_preserves_source_and_policy_validation_failures(self):
        for message in ("Manifest/reference pin drift", "Historical original inventory drift",
                        "Coverage policy drift", "Unknown source or stale native provenance"):
            with self.subTest(message=message):
                status, _, errors = self.run_check(validation_error=PortError(message))
                self.assertEqual(2, status)
                self.assertIn(message, errors)

    def test_check_rejects_review_source_drift(self):
        self.review["source_sha"] = "a" * 40
        status, _, errors = self.run_check()
        self.assertEqual(2, status)
        self.assertIn("source/schema drift", errors)

    def test_check_rejects_ambiguous_current_assertions(self):
        self.groups["WP-101"]["families"][0]["native_proposals"] *= 2
        status, _, errors = self.run_check()
        self.assertEqual(2, status)
        self.assertIn("ambiguous actual native assertions", errors)

    def test_check_rejects_missing_current_assertions(self):
        self.groups["WP-101"]["families"][0]["native_proposals"][0]["assertions"] = []
        status, _, errors = self.run_check()
        self.assertEqual(2, status)
        self.assertIn("actual native assertions", errors)

    def test_check_rejects_zero_bindings(self):
        self.review["groups"] = []
        status, _, errors = self.run_check()
        self.assertEqual(2, status)
        self.assertIn("Zero source/native", errors)

    def test_current_execution_missing_reports_fails(self):
        status, _, errors = self.run_check(("--check", "--verify-execution"))
        self.assertEqual(2, status)
        self.assertIn("BLOCKED:", errors)

    def test_current_execution_negatives_fail_without_archived_fallback(self):
        cases = (
            ("zero", "", 0, 0, 0, 0),
            ("failed", '<testcase classname="Native" name="checked()"><failure/></testcase>', 1, 1, 0, 0),
            ("error", '<testcase classname="Native" name="checked()"><error/></testcase>', 1, 0, 1, 0),
            ("skipped", '<testcase classname="Native" name="checked()"><skipped/></testcase>', 1, 0, 0, 1),
            ("hidden-skip", '<testcase classname="Native" name="checked()"><skipped/></testcase>', 1, 0, 0, 0),
            ("wrong-identity", '<testcase classname="Archive" name="old()"/>', 1, 0, 0, 0),
            ("duplicate", '<testcase classname="Native" name="checked()"/>' * 2, 2, 0, 0, 0),
            ("count-mismatch", '<testcase classname="Native" name="checked()"/>', 2, 0, 0, 0),
        )
        for name, body, tests, failures, errors, skipped in cases:
            with self.subTest(name=name):
                self.report(body, tests, failures, errors, skipped)
                status, _, output = self.run_check(("--check", "--verify-execution"))
                self.assertEqual(2, status)
                self.assertIn("BLOCKED:", output)

    def test_current_execution_malformed_report_fails(self):
        self.report().write_text("<testsuite", encoding="utf-8")
        status, _, errors = self.run_check(("--verify-execution",))
        self.assertEqual(2, status)
        self.assertIn("BLOCKED:", errors)

    def test_explicit_generation_keeps_writing_current_groups_and_bindings(self):
        with patch.object(semantic, "proposals", return_value=self.groups), \
                patch.object(semantic, "load_json", side_effect=self.load_review), \
                patch.object(semantic.audit, "generate") as validator:
            result = semantic.generate(repo=self.repo)
        validator.assert_not_called()
        self.assertEqual(1, result["candidate_semantically_compared_families"])
        group = semantic.repo_path(self.repo, f"{semantic.GROUP_ROOT}/WP-101.json")
        bindings = semantic.repo_path(self.repo, semantic.REVIEW_BINDINGS)
        self.assertEqual(semantic.json_bytes(self.groups["WP-101"]), group.read_bytes())
        self.assertEqual(["Native", "checked()"], json.loads(bindings.read_text())["families"][0]["runner_identity"])


if __name__ == "__main__":
    unittest.main()
