"""AndroidOnly: WP-503 Pure fixed-family source/publication guards, not native acceptance."""

import ast
from dataclasses import replace
import hashlib
import io
import json
import re
import subprocess
import sys
import unittest
from unittest.mock import Mock, patch

import language_binding as binding
from test_shared_device_runner import completed, record


HEAD = "9d2366ba5e6eeeb2e01fda9ad3990c25e6af9928"
# Immutable regression fixtures, not a native execution result for today's source.
PINNED_CONFIGURATION = "81dccf57622cdfc90159fbdcb0761110408616c7"
CURRENT_PRODUCTION = {
    "diagnostics": ("e70d637a75be83921a88b603251a297c74ed2b44", "0cb54d923d8c0a343caf37279012c79380c20412"),
    "nodes": ("86cb805495f565a3eb559af979b71eafb40b8f99", "5cd07a338b508ff09d3e5fd0dbcf121ad1cfdec5"),
}


def source(family="language"):
    spec = binding.family_spec(family)
    anchor, production = CURRENT_PRODUCTION.get(family, (None, spec.production_blob))
    return binding.LanguageBinding(
        "cbattlegear/MeshCoreOne-Android", HEAD, "a" * 40, "b" * 64,
        "c" * 64, "d" * 64, binding.CANONICAL,
        binding.CURRENT_APP_SESSION, binding.CURRENT_CLI_SESSION, 14,
        spec.producer, production, spec.fixture_blob, spec.name, anchor,
    )


def transcript(code=0, *, family="language"):
    identities = binding.family_spec(family).identities
    return completed("".join(
        record(identity, 1, len(identities)) + record(identity, code, len(identities)) for identity in identities
    ), len(identities))


class LanguageBindingTest(unittest.TestCase):
    def setUp(self):
        self.claim = {
            "wp": "WP-503", "attempt": binding.CANONICAL,
            "identity": {"session_id": binding.CANONICAL}, "revision": 14,
        }

    def test_historical_regressions_do_not_depend_on_future_checkout_bytes(self):
        historical_checks = (
            self.test_diagnostics_specification_matches_original_and_current_committed_git_objects,
            self.test_diagnostics_current_anchor_is_merged_squash_commit_with_exact_production_tree,
            self.test_nodes_specification_matches_initial_seven_current_original_pins_and_ancestry,
            self.test_comparator_original_current_production_oracle_and_helper_git_objects_are_exact,
            self.test_traz_original_current_git_vectors_and_helpers_are_producer_preservation_not_us_oracle,
            self.test_los_original_and_current_fixture_pins_preserve_inputs_dependencies_and_full_inventory,
            self.test_los_current_fixture_synchronizes_only_its_mount_before_unchanged_count_window_and_assertions,
            self.test_cached_chat_actual_original_fixture_inputs_and_separate_current_delegates_are_pinned,
            self.test_cold_platform_actual_original_fixtures_deadlines_flags_diagnostics_and_app_provenance_are_unchanged,
            self.test_fallback_disclosure_fixed_scope_and_actual_reviewed_fixture_harness_provenance,
        )
        original_git = binding.git

        def historical_only(*arguments):
            if any(argument == "HEAD" or argument.startswith("HEAD:") for argument in arguments):
                raise AssertionError("Historical evidence must not freeze the moving checkout")
            return original_git(*arguments)

        with patch.object(binding, "git", side_effect=historical_only):
            for check in historical_checks:
                with self.subTest(check=check.__name__):
                    check()

    def fake_git(self, *arguments):
        if arguments[0] == "merge-base":
            return ""
        if arguments[0] == "show":
            return "\n".join("@Test fun " + method + "() {}" for method in binding.METHODS)
        name = arguments[1]
        if name.endswith(":" + binding.PRODUCTION):
            return binding.PRODUCTION_BLOB
        if name.endswith(":" + binding.FIXTURE):
            return binding.FIXTURE_BLOB
        if name.endswith(":android"):
            return "a" * 40
        self.fail(arguments)

    def family_git(self, *arguments, family="diagnostics"):
        spec = binding.family_spec(family)
        if arguments == ("rev-parse", "refs/remotes/origin/main"):
            return HEAD
        if arguments == ("rev-list", HEAD):
            return "\n".join((HEAD, CURRENT_PRODUCTION[family][0]))
        if arguments[0] == "merge-base":
            return ""
        if arguments[0] == "show":
            return "\n".join("@Test fun " + method + "() {}" for method in (spec.fixture_methods or spec.methods))
        if arguments[0] == "rev-parse":
            revision, _, path = arguments[1].partition(":")
            if path == "android":
                return "a" * 40
            if path == spec.production and family in CURRENT_PRODUCTION:
                anchor, blob = CURRENT_PRODUCTION[family]
                return blob if revision in (HEAD, anchor) else spec.production_blob
            if path == spec.fixture and revision == spec.producer and spec.original_fixture_blob:
                return spec.original_fixture_blob
            pins = {spec.fixture: spec.fixture_blob, **{path: blob for path, _, blob in spec.source_pins}}
            return pins[path]
        self.fail(arguments)

    def prepare(self, family="language", *, git_effect=None):
        manifest = Mock(sha256="c" * 64)
        manifest.wp.return_value = {"write_paths": ["android/accessibility-tests/"]}
        effect = git_effect or (self.fake_git if family == "language"
                                else lambda *args: self.family_git(*args, family=family))
        with patch.object(binding, "git", side_effect=effect), \
                patch.object(binding, "source_identity", return_value="b" * 64) as identity, \
                patch.object(binding, "load_manifest", return_value=manifest), \
                patch.object(binding, "load_json", return_value={"repository": source().repository}), \
                patch.object(binding, "trusted_execution_policy", return_value={"repository": source().repository}), \
                patch.object(binding, "policy_revision", return_value="d" * 64), \
                patch.object(binding, "CapabilityEngine"):
            actual = binding.prepare_binding(HEAD, self.claim, family=family)
        identity.assert_called_once_with(binding.ROOT, HEAD)
        return actual

    def test_current_source_fixture_and_truthful_replacement_binding(self):
        self.assertEqual(source(), self.prepare())
        self.assertEqual(binding.PRODUCER, self.prepare().producer_revision)

    def test_stale_or_wrong_canonical_claim_fails_before_source_operations(self):
        for changes in (
            {"attempt": "retired"}, {"wp": "WP-407"}, {"revision": 0},
            {"revision": True}, {"identity": {"session_id": "retired"}},
        ):
            with self.subTest(changes=changes), patch.object(binding, "source_identity") as identity:
                claim = {**self.claim, **changes}
                with self.assertRaises(binding.PortError):
                    binding.prepare_binding(HEAD, claim)
                identity.assert_not_called()

    def test_source_dirty_or_not_current_is_not_accepted(self):
        with patch.object(binding, "source_identity", side_effect=binding.PortError("dirty/current mismatch")):
            with self.assertRaisesRegex(binding.PortError, "dirty/current"):
                binding.prepare_binding(HEAD, self.claim)

    def test_producer_or_fixture_drift_is_rejected(self):
        for path in (binding.PRODUCTION, binding.FIXTURE):
            original = self.fake_git

            def changed(*arguments):
                if arguments[0] == "rev-parse" and arguments[1] == HEAD + ":" + path:
                    return "0" * 40
                return original(*arguments)

            with self.subTest(path=path), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=changed):
                with self.assertRaisesRegex(binding.PortError, "drifted"):
                    binding.prepare_binding(HEAD, self.claim)

    def test_extra_missing_or_reordered_methods_are_not_a_language_cohort(self):
        original = self.fake_git
        for methods in (binding.METHODS[:1], (*binding.METHODS, "other"), binding.METHODS[::-1]):
            def changed(*arguments):
                if arguments[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in methods)
                return original(*arguments)

            with self.subTest(methods=methods), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=changed):
                with self.assertRaisesRegex(binding.PortError, "two original"):
                    binding.prepare_binding(HEAD, self.claim)

    def test_exact_two_per_api_and_distinct_operation_owners(self):
        requests = [binding.request_for(source(), api) for api in (31, 37)]
        self.assertEqual([binding.IDENTITIES] * 2, [request.identities for request in requests])
        self.assertEqual(4, sum(len(request.identities) for request in requests))
        self.assertNotEqual(requests[0].operation_owner, requests[1].operation_owner)
        for request in requests:
            self.assertTrue(request.operation_owner.startswith(binding.CANONICAL + ":language:"))
            self.assertNotIn("shared37", request.operation_owner)

    def test_unrequested_apis_and_retired_execution_identity_fail(self):
        for api in (30, 33, 34, 36, 38, True, "31"):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(source(), api)
        with self.assertRaises(binding.PortError):
            binding.request_for(replace(source(), replacement_cli_session="d2980f28-620c-408b-9947-067e2da8eedf"), 31)

    def test_exact_published_current_source_rechecks_without_a_host_callback(self):
        request = binding.request_for(source(), 37)
        with patch.object(binding, "current_binding", return_value=source()) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF) as git, \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}) as policy:
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD)
        git.assert_called_once_with("ls-remote", "origin", binding.PUBLIC_REF)
        policy.assert_called_once_with()

    def test_pause_policy_is_loaded_from_verified_current_main_object_not_a_candidate_grant(self):
        with patch.object(binding, "git", side_effect=[
            HEAD, HEAD + "\t" + binding.MAIN_REF, "", '{"paused":true}',
        ]) as git:
            self.assertEqual({"paused": True}, binding.trusted_execution_policy())
        self.assertEqual(("show", HEAD + ":docs/android/automation-policy.json"), git.call_args.args)
        self.assertEqual(("diff", "--exit-code", "--quiet", HEAD, "--",
                          "docs/android/automation-policy.json", "docs/android/port-manifest.json"),
                         git.call_args_list[2].args)

    def test_stale_main_and_candidate_policy_edits_cannot_unpause_device_execution(self):
        with patch.object(binding, "git", side_effect=[HEAD, "0" * 40 + "\t" + binding.MAIN_REF]):
            with self.assertRaisesRegex(binding.PortError, "Trusted main is stale"):
                binding.trusted_execution_policy()
        with patch.object(binding, "git", side_effect=[
            HEAD, HEAD + "\t" + binding.MAIN_REF, subprocess.CalledProcessError(1, "git diff"),
        ]):
            with self.assertRaisesRegex(binding.PortError, "differs from trusted current main"):
                binding.trusted_execution_policy()

    def test_preparation_cli_is_read_only(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source()), patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD]))
        result = json.loads(output.getvalue())
        self.assertFalse(result["authority"])
        self.assertFalse(result["apks_built"])
        self.assertEqual(0, result["native_starts"])
        self.assertEqual([31, 37], [request["api"] for request in result["requests"]])

    def test_always_fail_admission_placeholder_is_removed_from_cli(self):
        with patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as caught:
            binding.main(["--head", HEAD, "--require-native-admission"])
        self.assertEqual(2, caught.exception.code)

    def test_stale_source_claim_policy_or_unpublished_input_is_rejected(self):
        request = binding.request_for(source(), 31)
        for actual in (
            replace(source(), reservation_revision=15),
            replace(source(), policy_revision="e" * 64),
            replace(source(), source_revision="f" * 40),
        ):
            with self.subTest(actual=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.verify_current_request(request)
                git.assert_not_called()
        for published in ("", "f" * 40 + "\t" + binding.PUBLIC_REF,
                          HEAD + "\trefs/heads/other"):
            with self.subTest(published=published), patch.object(binding, "current_binding", return_value=source()), \
                    patch.object(binding, "git", return_value=published):
                with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                    binding.verify_current_request(request)

    def test_transcript_accounting_uses_current_source_but_grants_no_acceptance(self):
        for api in (31, 37):
            request = binding.request_for(source(), api)
            actual = binding.account_outcomes(transcript(), request, process_exit=0, forced_termination=False)
            self.assertEqual((2, 2, 2, 0, 0, 0), tuple(actual[key] for key in (
                "discovered", "started", "passed", "failed", "errors", "skipped")))
            self.assertEqual(HEAD, actual["source_revision"])
            self.assertEqual(request.operation_owner, actual["native_lane_operation_owner"])
            self.assertFalse(actual["acceptance"])

    def test_failed_skipped_forced_and_nonzero_completion_cannot_clear_uncertainty(self):
        request = binding.request_for(source(), 31)
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(code), request, process_exit=0, forced_termination=False)
        for process_exit, forced in ((1, False), (True, False), (0, True), (0, None)):
            with self.subTest(exit=process_exit, forced=forced), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(), request, process_exit=process_exit, forced_termination=forced)

    def test_wrong_missing_duplicate_and_crashed_transcripts_fail_existing_strict_parser(self):
        request = binding.request_for(source(), 37)
        for value in ("", transcript().replace(binding.METHODS[0], "other"),
                      transcript() + "INSTRUMENTATION_FAILED: process crashed\n",
                      transcript().replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                binding.account_outcomes(value, request, process_exit=0, forced_termination=False)

    def test_diagnostics_binding_pins_original_five_and_entire_production_helper_provenance(self):
        actual = self.prepare("diagnostics")
        self.assertEqual(source("diagnostics"), actual)
        request = binding.request_for(actual, 37)
        self.assertEqual(binding.DIAGNOSTICS.identities, request.identities)
        self.assertEqual(5, len(request.identities))
        self.assertTrue(request.operation_owner.startswith(binding.CANONICAL + ":diagnostics:"))
        self.assertNotIn(":language:", request.operation_owner)
        for name in ("other", "Nodes", None, True):
            with self.subTest(family=name), patch.object(binding, "source_identity") as identity:
                with self.assertRaises(binding.PortError):
                    binding.prepare_binding(HEAD, self.claim, family=name)
                identity.assert_not_called()
        for api in (31, 33, 38, True, "37"):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(actual, api)
        with self.assertRaises(binding.PortError):
            binding.request_for(replace(source(), family="diagnostics"), 37)
        with self.assertRaises(binding.PortError):
            binding.LanguageRequest(actual, 37, binding.CANONICAL + ":language:" + "a" * 32)

    def test_diagnostics_specification_matches_original_and_current_committed_git_objects(self):
        spec = binding.DIAGNOSTICS
        anchor, current_blob = CURRENT_PRODUCTION["diagnostics"]
        self.assertEqual((anchor, current_blob), spec.current_production)
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                expected = current_blob if path == spec.production else blob
                self.assertEqual(expected, binding.git("rev-parse", anchor + ":" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, anchor))
        for revision in (anchor, spec.producer):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
        with self.assertRaises(TypeError):
            binding.FAMILIES["other"] = spec

    def test_diagnostics_current_anchor_is_merged_squash_commit_with_exact_production_tree(self):
        spec = binding.DIAGNOSTICS
        anchor, current_blob = spec.current_production
        self.assertEqual("e70d637a75be83921a88b603251a297c74ed2b44", anchor)
        self.assertEqual("", binding.git("merge-base", "--is-ancestor", spec.producer, anchor))
        self.assertEqual("0cb54d923d8c0a343caf37279012c79380c20412", current_blob)
        self.assertEqual(current_blob, binding.git("rev-parse", anchor + ":" + spec.production))

    def test_diagnostics_every_fixture_helper_production_pin_rejects_candidate_or_original_drift(self):
        spec = binding.DIAGNOSTICS
        pins = ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins)
        for path, producer, _ in pins:
            for revision in (HEAD, producer):
                def changed(*args):
                    if args == ("rev-parse", revision + ":" + path):
                        return "0" * 40
                    return self.family_git(*args)
                with self.subTest(path=path, revision=revision), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(HEAD, self.claim, family="diagnostics")

    def test_diagnostics_wrong_method_count_identity_or_order_and_missing_ancestry_fail(self):
        methods = binding.DIAGNOSTICS.methods
        for actual in (methods[:-1], (*methods, "extra"), methods[::-1], (*methods[:-1], methods[0])):
            def changed(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in actual)
                return self.family_git(*args)
            with self.subTest(methods=actual), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=changed):
                with self.assertRaisesRegex(binding.PortError, "five original"):
                    binding.prepare_binding(HEAD, self.claim, family="diagnostics")
        with patch.object(binding, "source_identity", return_value="b" * 64), \
                patch.object(binding, "git", side_effect=subprocess.CalledProcessError(1, "missing original ancestor")):
            with self.assertRaises(subprocess.CalledProcessError):
                binding.prepare_binding(HEAD, self.claim, family="diagnostics")

    def test_dual_production_bindings_retain_historical_producers_and_exact_current_candidates(self):
        historical = {
            "diagnostics": ("ff42476614d07588eb17f1df58b4f9e0e479b3ff",
                            "c9f4bfeb12dd02729dbf080e6bb5e513d170e73b"),
            "nodes": ("bd325b2e939a33b4ae917429aad9686877aed9de",
                      "7c74a004c993d57014b3cfcd35f8916d8c2a6fc7"),
        }
        for family, (producer, blob) in historical.items():
            with self.subTest(family=family):
                spec = binding.family_spec(family)
                self.assertEqual((producer, blob), (spec.producer, spec.production_blob))
                self.assertTrue(any(path == spec.production and pinned == blob
                                    for path, _, pinned in spec.source_pins))
                actual = self.prepare(family)
                anchor, current_blob = CURRENT_PRODUCTION[family]
                self.assertEqual((anchor, current_blob), spec.current_production)
                self.assertEqual((producer, anchor, current_blob),
                                 (actual.producer_revision, actual.current_source_anchor, actual.production_blob))
                self.assertEqual(spec.identities, binding.request_for(actual, 37).identities)

    def test_unmerged_candidate_does_not_refresh_historical_source_or_accept_its_new_tree(self):
        for family in CURRENT_PRODUCTION:
            spec = binding.family_spec(family)

            def historical(*args):
                if args == ("rev-list", HEAD):
                    return HEAD
                if args == ("rev-parse", HEAD + ":" + spec.production):
                    return spec.production_blob
                return self.family_git(*args, family=family)

            with self.subTest(family=family, source="historical"):
                actual = self.prepare(family, git_effect=historical)
                self.assertIsNone(actual.current_source_anchor)
                self.assertEqual(spec.production_blob, actual.production_blob)
                self.assertEqual(spec.identities, binding.request_for(actual, 37).identities)

            def unreviewed(*args):
                if args == ("rev-list", HEAD):
                    return HEAD
                return self.family_git(*args, family=family)

            with self.subTest(family=family, source="new-tree-without-candidate"), \
                    self.assertRaisesRegex(binding.PortError, "drifted"):
                self.prepare(family, git_effect=unreviewed)

            def stale(*args):
                if args == ("rev-parse", HEAD + ":" + spec.production):
                    return spec.production_blob
                return self.family_git(*args, family=family)

            with self.subTest(family=family, source="old-tree-after-candidate"), \
                    self.assertRaisesRegex(binding.PortError, "drifted"):
                self.prepare(family, git_effect=stale)

    def test_dual_source_bindings_reject_candidate_tree_and_historical_primary_producer_tampering(self):
        for family, (anchor, _) in CURRENT_PRODUCTION.items():
            spec = binding.family_spec(family)
            for revision in (anchor, HEAD, spec.producer):
                def changed(*args):
                    if args == ("rev-parse", revision + ":" + spec.production):
                        return "0" * 40
                    return self.family_git(*args, family=family)

                with self.subTest(family=family, revision=revision), \
                        self.assertRaisesRegex(binding.PortError, "drifted"):
                    self.prepare(family, git_effect=changed)

            def missing(*args):
                if args == ("merge-base", "--is-ancestor", spec.producer, anchor):
                    raise subprocess.CalledProcessError(1, "candidate has no original producer ancestor")
                return self.family_git(*args, family=family)

            with self.subTest(family=family, ancestry="missing"), \
                    self.assertRaises(subprocess.CalledProcessError):
                self.prepare(family, git_effect=missing)

    def test_dual_source_bindings_reject_changed_candidate_metadata(self):
        for family, (anchor, blob) in CURRENT_PRODUCTION.items():
            spec = binding.family_spec(family)
            for current in (("0" * 40, blob), (anchor, "0" * 40)):
                altered = {**binding.FAMILIES, family: replace(spec, current_production=current)}
                with self.subTest(family=family, current=current), \
                        patch.object(binding, "FAMILIES", altered), \
                        self.assertRaisesRegex(binding.PortError, "drifted"):
                    self.prepare(family)

    def test_requests_cannot_mix_historical_and_current_candidate_bindings(self):
        for family in CURRENT_PRODUCTION:
            bound = source(family)
            spec = binding.family_spec(family)
            for changes in (
                {"current_source_anchor": None},
                {"current_source_anchor": "0" * 40},
                {"production_blob": spec.production_blob},
                {"producer_revision": bound.current_source_anchor},
            ):
                with self.subTest(family=family, changes=changes), self.assertRaises(binding.PortError):
                    binding.request_for(replace(bound, **changes), 37)
        with self.assertRaises(binding.PortError):
            binding.request_for(replace(source(), current_source_anchor=CURRENT_PRODUCTION["diagnostics"][0]), 37)

    def test_diagnostics_current_family_publication_policy_and_revision_guards_are_reused(self):
        bound = source("diagnostics")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="diagnostics")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="f" * 64),
                       replace(bound, source_revision="a" * 40),
                       replace(bound, current_source_anchor=None), source()):
            with self.subTest(binding=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.verify_current_request(request)
                git.assert_not_called()
        with patch.object(binding, "current_binding", return_value=bound), \
                patch.object(binding, "git", return_value="a" * 40 + "\t" + binding.PUBLIC_REF):
            with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                binding.verify_current_request(request)

    def test_diagnostics_strict_five_outcome_accounting_never_substitutes_language_or_grants_acceptance(self):
        request = binding.request_for(source("diagnostics"), 37)
        result = binding.account_outcomes(transcript(family="diagnostics"), request,
                                         process_exit=0, forced_termination=False)
        self.assertEqual((5, 5, 5, 5), tuple(result[k] for k in (
            "discovered", "started", "matching_outcomes", "passed")))
        self.assertEqual("diagnostics", result["family"])
        self.assertFalse(result["acceptance"])
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(code, family="diagnostics"), request,
                                         process_exit=0, forced_termination=False)
        missing = completed("".join(record(identity, 1, 5) + record(identity, 0, 5)
                                    for identity in binding.DIAGNOSTICS.identities[:-1]), 5)
        for value in (transcript(), "", missing,
                      transcript(family="diagnostics").replace(binding.DIAGNOSTICS.methods[0], "unknownMethod"),
                      transcript(family="diagnostics").replace(
                          binding.DIAGNOSTICS.methods[0], binding.DIAGNOSTICS.methods[1])):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                binding.account_outcomes(value, request, process_exit=0, forced_termination=False)
        for exit_code, forced in ((1, False), (0, True), (True, False), (0, None)):
            with self.subTest(exit_code=exit_code, forced=forced), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(family="diagnostics"), request,
                                         process_exit=exit_code, forced_termination=forced)

    def test_diagnostics_preparation_cli_selects_only_api37_original_five_without_live_authority(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source("diagnostics")) as current, \
                patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", "diagnostics"]))
        current.assert_called_once_with(HEAD, family="diagnostics")
        result = json.loads(output.getvalue())
        self.assertEqual([37], [request["api"] for request in result["requests"]])
        self.assertEqual(list(binding.DIAGNOSTICS.identities), result["requests"][0]["identities"])
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])

    def test_nodes_binding_restricts_api_producer_and_truthful_unique_owner_to_original_seven(self):
        bound = self.prepare("nodes")
        self.assertEqual(source("nodes"), bound)
        first, second = (binding.request_for(bound, api) for api in (31, 37))
        self.assertEqual((31, 37), binding.NODES.apis)
        self.assertEqual(binding.MAIN_REF, binding.NODES.publication_ref)
        self.assertEqual(binding.NODES.identities, first.identities)
        self.assertEqual(7, len(first.identities))
        self.assertNotEqual(first.operation_owner, second.operation_owner)
        self.assertTrue(first.operation_owner.startswith(binding.CANONICAL + ":nodes:"))
        for api in (30, 32, 33, 34, 36, 38, True, "31", "37", 31.0, 37.0):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(bound, api)
        for changes in ({"producer_revision": "0" * 40}, {"production_blob": "0" * 40},
                        {"fixture_blob": "0" * 40}, {"replacement_app_session": "retired"},
                        {"replacement_cli_session": "retired"}, {"canonical_session": "retired"}):
            with self.subTest(changes=changes), self.assertRaises(binding.PortError):
                binding.request_for(replace(bound, **changes), 37)
        for family in ("language", "diagnostics"):
            with self.subTest(family=family), self.assertRaises(binding.PortError):
                binding.request_for(replace(source(family), family="nodes"), 37)
            with self.assertRaises(binding.PortError):
                binding.LanguageRequest(bound, 37, binding.CANONICAL + ":" + family + ":" + "a" * 32)

    def test_nodes_specification_matches_initial_seven_current_original_pins_and_ancestry(self):
        spec = binding.NODES
        anchor, current_blob = CURRENT_PRODUCTION["nodes"]
        self.assertEqual((anchor, current_blob), spec.current_production)
        initial = "fc9188ab1dc1549be8788cbc3c180c895d5d5def"
        self.assertEqual("", binding.git("merge-base", "--is-ancestor", initial, spec.producer))
        for revision in (initial, spec.producer, anchor):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                expected = current_blob if path == spec.production else blob
                self.assertEqual(expected, binding.git("rev-parse", anchor + ":" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, anchor))
        self.assertEqual("android/feature/nodes/src/main", spec.production)
        self.assertIn("AndroidManifest.xml", binding.git("ls-tree", "--name-only", anchor + ":" + spec.production))
        with self.assertRaises(TypeError):
            binding.FAMILIES["nodes"] = binding.DIAGNOSTICS

    def test_nodes_rejects_each_current_or_original_fixture_helper_and_complete_production_pin_drift(self):
        spec = binding.NODES
        for path, producer, _ in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            for revision in (HEAD, producer):
                def changed(*args):
                    if args == ("rev-parse", revision + ":" + path):
                        return "0" * 40
                    return self.family_git(*args, family="nodes")
                with self.subTest(path=path, revision=revision), \
                        patch.object(binding, "trusted_main_policy_for_source"), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(HEAD, self.claim, family="nodes")

    def test_nodes_subject_pair_uses_real_committed_anchors_and_retains_original_fixture_and_other_helpers(self):
        previous = binding.git("show", "bc34b3d73675d88c3255de778bdc3d283aca9b99:android/accessibility-tests/language_binding.py")
        declaration = next(node.value for node in ast.parse(previous).body
                           if isinstance(node, ast.Assign)
                           and any(isinstance(target, ast.Name) and target.id == "NODES" for target in node.targets))
        original = [ast.literal_eval(argument) for argument in declaration.args]
        spec = binding.NODES
        self.assertEqual((original[2], original[3]), (spec.klass, spec.methods))
        self.assertEqual(original[5], spec.production)
        self.assertEqual((original[7], original[8]), (spec.fixture, spec.fixture_blob))
        self.assertEqual("bd325b2e939a33b4ae917429aad9686877aed9de", spec.producer)
        self.assertEqual("7c74a004c993d57014b3cfcd35f8916d8c2a6fc7", spec.production_blob)
        self.assertIn((spec.fixture, original[4], original[8]), spec.source_pins)
        audit = "android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NodesAccessibilityAudit.kt"
        self.assertIn((audit, "d4d1f9b2f709abc46b8024f7703121c1facab672",
                       "1e7ff135e8786bbe02056e8e6fe6d642e5e2e1a3"), spec.source_pins)
        for pin in original[9]:
            if pin[0] not in (spec.production, audit):
                self.assertIn(pin, spec.source_pins)
        for api in (31, 37):
            for changes in ({"producer_revision": original[4]}, {"production_blob": original[6]}):
                with self.subTest(api=api, changes=changes), self.assertRaises(binding.PortError):
                    binding.request_for(replace(source("nodes"), **changes), api)
        self.assertEqual((31, 37), spec.apis)
        self.assertEqual(binding.MAIN_REF, spec.publication_ref)

    def test_nodes_shared_subject_delta_preserves_every_other_original_predicate_and_both_inventories(self):
        audit = "android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NodesAccessibilityAudit.kt"
        previous = subprocess.check_output(
            ["git", "show", "bc34b3d73675d88c3255de778bdc3d283aca9b99:" + audit],
            cwd=binding.ROOT, encoding="utf-8")
        wrong = '        assertEquals(contact.displayName, payload.getStringExtra(Intent.EXTRA_SUBJECT))'
        correct = (
            '        val shareSubject = activity.getString(C.contactsQrShareSubject)\n'
            '        assertTrue("Fixture must distinguish the localized subject from the contact name",\n'
            '            shareSubject != contact.displayName)\n'
            '        assertEquals(shareSubject, payload.getStringExtra(Intent.EXTRA_SUBJECT))'
        )
        chooser = "        assertEquals(Intent.ACTION_CHOOSER, chooser.action)\n"
        title = "        assertEquals(contact.displayName, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())\n"
        self.assertEqual(1, previous.count(wrong))
        self.assertEqual(1, previous.count(chooser))
        expected = previous.replace(wrong, correct).replace(chooser, chooser + title)
        self.assertEqual(expected, (binding.ROOT / audit).read_text(encoding="utf-8"))
        jvm = "android/accessibility-tests/src/test/kotlin/com/meshcoreone/android/accessibility/NativeNodesAccessibilityTest.kt"
        for path, count in ((binding.NODES.fixture, 7), (jvm, 9)):
            old_body = binding.git("show", "bc34b3d73675d88c3255de778bdc3d283aca9b99:" + path)
            current = (binding.ROOT / path).read_text(encoding="utf-8")
            self.assertEqual(old_body, current.rstrip("\n"))
            self.assertEqual(count, len(re.findall(r"@Test fun (\w+)\(", current)))
        self.assertIn("@Config(sdk = [31, 37]", (binding.ROOT / jvm).read_text(encoding="utf-8"))

    def test_nodes_missing_duplicate_extra_wrong_order_identity_or_ancestor_fails_closed(self):
        methods = binding.NODES.methods
        for actual in ((), methods[:-1], (*methods, "extra"), methods[::-1],
                       (*methods[:-1], methods[0]), (*methods[:-1], "wrongIdentity")):
            def changed(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in actual)
                return self.family_git(*args, family="nodes")
            with self.subTest(methods=actual), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "trusted_main_policy_for_source"), \
                    patch.object(binding, "git", side_effect=changed):
                with self.assertRaisesRegex(binding.PortError, "seven original"):
                    binding.prepare_binding(HEAD, self.claim, family="nodes")
        for _, producer, _ in ((binding.NODES.fixture, binding.NODES.producer, binding.NODES.fixture_blob),
                               *binding.NODES.source_pins):
            def missing(*args):
                if args == ("merge-base", "--is-ancestor", producer, HEAD):
                    raise subprocess.CalledProcessError(1, "missing Nodes ancestor")
                return self.family_git(*args, family="nodes")
            with self.subTest(producer=producer), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "trusted_main_policy_for_source"), \
                    patch.object(binding, "git", side_effect=missing):
                with self.assertRaises(subprocess.CalledProcessError):
                    binding.prepare_binding(HEAD, self.claim, family="nodes")

    def test_nodes_reuses_exact_source_scope_policy_family_publication_guards(self):
        bound = source("nodes")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD) as git, \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="nodes")
        git.assert_called_once_with("rev-parse", "refs/remotes/origin/main")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="e" * 64),
                       replace(bound, source_revision="e" * 40),
                       replace(bound, current_source_anchor=None), source("diagnostics"), source()):
            with self.subTest(binding=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.verify_current_request(request)
                git.assert_not_called()
        for published in ("", "e" * 40 + "\t" + binding.PUBLIC_REF, HEAD + "\trefs/heads/other"):
            with self.subTest(published=published), patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "trusted_execution_policy", return_value={"paused": False}), \
                    patch.object(binding, "git", return_value=published):
                with self.assertRaisesRegex(binding.PortError, "exact current trusted main"):
                    binding.verify_current_request(request)

    def test_nodes_exact_seven_outcomes_require_all_positive_normal_completion_without_acceptance(self):
        request = binding.request_for(source("nodes"), 37)
        result = binding.account_outcomes(transcript(family="nodes"), request,
                                         process_exit=0, forced_termination=False)
        self.assertEqual((7, 7, 7, 7, 0, 0, 0), tuple(result[k] for k in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("nodes", result["family"])
        self.assertEqual(request.operation_owner, result["native_lane_operation_owner"])
        self.assertFalse(result["acceptance"])
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(code, family="nodes"), request,
                                         process_exit=0, forced_termination=False)
        for exit_code, forced in ((1, False), (True, False), (0, True), (0, None)):
            with self.subTest(exit_code=exit_code, forced=forced), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(family="nodes"), request,
                                         process_exit=exit_code, forced_termination=forced)
        missing = completed("".join(record(identity, 1, 7) + record(identity, 0, 7)
                                    for identity in request.identities[:-1]), 7)
        for value in ("", missing, transcript(), transcript(family="diagnostics"),
                      transcript(family="nodes").replace("numtests=7", "numtests=8"),
                      transcript(family="nodes").replace(binding.NODES.methods[0], "unknownMethod"),
                      transcript(family="nodes").replace(binding.NODES.methods[0], binding.NODES.methods[1]),
                      transcript(family="nodes").replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                binding.account_outcomes(value, request, process_exit=0, forced_termination=False)

    def test_nodes_cli_prepares_two_ordered_api31_37_sevens_without_native_or_build_authority(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source("nodes")) as current, \
                patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", "nodes"]))
        current.assert_called_once_with(HEAD, family="nodes")
        result = json.loads(output.getvalue())
        self.assertEqual([31, 37], [request["api"] for request in result["requests"]])
        self.assertEqual([list(binding.NODES.identities)] * 2, [request["identities"] for request in result["requests"]])
        self.assertEqual(2, len({request["operation_owner"] for request in result["requests"]}))
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])

    def test_nodes_current_main_both_apis_use_real_ref_policy_guards_not_the_historical_publisher(self):
        bound = self.prepare("nodes")
        policy = {"repository": bound.repository, "paused": False}
        def current_git(*args):
            if args == ("rev-parse", "refs/remotes/origin/main"):
                return HEAD
            if args == ("ls-remote", "origin", binding.MAIN_REF):
                return HEAD + "\t" + binding.MAIN_REF
            if args[0] == "diff":
                return ""
            if args == ("show", HEAD + ":docs/android/automation-policy.json"):
                return json.dumps(policy)
            self.fail(args)
        for api in (31, 37):
            with self.subTest(api=api), patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "git", side_effect=current_git) as git:
                self.assertEqual(policy, binding.verify_current_request(binding.request_for(bound, api)))
            self.assertNotIn(unittest.mock.call("ls-remote", "origin", binding.PUBLIC_REF), git.call_args_list)
        for remote in ("", "e" * 40 + "\t" + binding.MAIN_REF, HEAD + "\t" + binding.PUBLIC_REF,
                       HEAD + "\trefs/heads/other"):
            def stale(*args):
                if args == ("ls-remote", "origin", binding.MAIN_REF):
                    return remote
                return current_git(*args)
            with self.subTest(remote=remote), patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "git", side_effect=stale), \
                    self.assertRaisesRegex(binding.PortError, "Trusted main is stale"):
                binding.verify_current_request(binding.request_for(bound, 31))
        def changed_policy(*args):
            if args[0] == "diff":
                raise subprocess.CalledProcessError(1, "candidate policy differs")
            return current_git(*args)
        with patch.object(binding, "current_binding", return_value=bound), \
                patch.object(binding, "git", side_effect=changed_policy), \
                self.assertRaisesRegex(binding.PortError, "policy or manifest differs"):
            binding.verify_current_request(binding.request_for(bound, 37))

    def test_nodes_non_main_source_or_stale_claim_is_rejected_before_source_preparation(self):
        for local_ref in ("", PINNED_CONFIGURATION, "0" * 40, HEAD + "\t" + binding.PUBLIC_REF):
            with self.subTest(local_ref=local_ref), \
                    patch.object(binding, "trusted_execution_policy", return_value={"paused": False}), \
                    patch.object(binding, "git", return_value=local_ref), \
                    patch.object(binding, "source_identity") as identity, \
                    self.assertRaisesRegex(binding.PortError, "exact current trusted main"):
                binding.prepare_binding(HEAD, self.claim, family="nodes")
            identity.assert_not_called()
        for changes in ({"revision": 0}, {"revision": True}, {"attempt": "retired"},
                        {"identity": {"session_id": "retired"}}, {"wp": "WP-311"}):
            with self.subTest(changes=changes), patch.object(binding, "trusted_main_policy_for_source") as main, \
                    patch.object(binding, "source_identity") as identity, self.assertRaises(binding.PortError):
                binding.prepare_binding(HEAD, {**self.claim, **changes}, family="nodes")
            main.assert_not_called()
            identity.assert_not_called()

    def test_nodes_cannot_select_qr_only_subsets_extra_or_substituted_methods(self):
        spec = binding.NODES
        for selected in ((), (spec.methods[1],), spec.methods[:-1], spec.methods[::-1],
                         spec.methods * 2, (*spec.methods, "extraMethod"), ("wrongMethod",)):
            with self.subTest(selected=selected), patch.object(binding, "FAMILIES", {
                **binding.FAMILIES, "nodes": replace(spec, methods=selected, fixture_methods=spec.methods),
            }), self.assertRaisesRegex(binding.PortError, "seven original"):
                self.prepare("nodes")
        for option in ("--class", "--method", "--selector", "--api", "--font", "--execute"):
            with self.subTest(option=option), patch("sys.stderr", io.StringIO()), \
                    patch.object(binding, "current_binding") as current, self.assertRaises(SystemExit):
                binding.main(["--head", HEAD, "--family", "nodes", option, "arbitrary"])
            current.assert_not_called()
        self.assertEqual({
            name for name, spec in binding.FAMILIES.items() if spec.publication_ref == binding.MAIN_REF
        }, {"nodes", "fallback-disclosure"})
        self.assertEqual(("realAppFallbackSenderLabelIncludesPossibleMatchDisclosure",),
                         binding.family_spec("fallback-disclosure").methods)
        self.assertEqual(11, len(binding.family_spec("fallback-disclosure").fixture_methods))

    def test_nodes_each_api_requires_exact_seven_normal_positive_outcomes_not_historical_or_partial_credit(self):
        for api in (31, 37):
            request = binding.request_for(source("nodes"), api)
            result = binding.account_outcomes(
                transcript(family="nodes"), request, process_exit=0, forced_termination=False)
            self.assertEqual(api, result["api"])
            self.assertEqual((7, 7, 7, 7, 0, 0, 0), tuple(result[key] for key in (
                "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
            self.assertFalse(result["acceptance"])
            for text in (transcript(-2, family="nodes"), transcript(-3, family="nodes"),
                         transcript(family="fallback-disclosure"), transcript(family="nodes") * 2):
                with self.subTest(api=api, text=text), self.assertRaises((binding.PortError, RuntimeError)):
                    binding.account_outcomes(text, request, process_exit=0, forced_termination=False)
            for exit_code, forced in ((2, False), (0, True), (False, False), (0, None)):
                with self.subTest(api=api, exit=exit_code, forced=forced), self.assertRaises(binding.PortError):
                    binding.account_outcomes(
                        transcript(family="nodes"), request, process_exit=exit_code, forced_termination=forced)

    def test_comparator_binds_only_original_eleven_api37_and_unique_current_owner_not_supplemental_locales(self):
        bound = self.prepare("comparator")
        self.assertEqual(source("comparator"), bound)
        requests = [binding.request_for(bound, 37) for _ in range(2)]
        self.assertEqual(binding.COMPARATOR.identities, requests[0].identities)
        self.assertEqual(11, len(requests[0].identities))
        self.assertNotEqual(requests[0].operation_owner, requests[1].operation_owner)
        self.assertTrue(requests[0].operation_owner.startswith(binding.CANONICAL + ":comparator:"))
        for api in (30, 31, 33, 34, 36, 38, True, "37", 37.0):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(bound, api)
        for name in ("comparator-us", "comparator-tr-az", "tr-TR", "az-AZ", "Comparator", None):
            with self.subTest(family=name), patch.object(binding, "source_identity") as identity:
                with self.assertRaises(binding.PortError):
                    binding.prepare_binding(HEAD, self.claim, family=name)
                identity.assert_not_called()
        for changes in ({"producer_revision": "0" * 40}, {"production_blob": "0" * 40},
                        {"fixture_blob": "0" * 40}, {"replacement_app_session": "retired"},
                        {"replacement_cli_session": "retired"}, {"canonical_session": "retired"}):
            with self.subTest(changes=changes), self.assertRaises(binding.PortError):
                binding.request_for(replace(bound, **changes), 37)
        for family in ("language", "diagnostics", "nodes"):
            with self.subTest(family=family), self.assertRaises(binding.PortError):
                binding.request_for(replace(source(family), family="comparator"), 37)
            with self.assertRaises(binding.PortError):
                binding.LanguageRequest(bound, 37, binding.CANONICAL + ":" + family + ":" + "a" * 32)

    def test_comparator_original_current_production_oracle_and_helper_git_objects_are_exact(self):
        spec = binding.COMPARATOR
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                self.assertEqual(blob, binding.git("rev-parse", PINNED_CONFIGURATION + ":" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, PINNED_CONFIGURATION))
        for revision in (spec.producer, PINNED_CONFIGURATION):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
            self.assertIn("newInstance(Locale.US)", body)
            self.assertNotIn("assertProducerCases", body)
        self.assertEqual("213866562c54c12d1f82f9c397b5cdcac7a4907c",
                         binding.git("rev-parse", PINNED_CONFIGURATION + ":" + spec.production + "/LocalizedCompare.kt"))
        supplemental = binding.git(
            "show", PINNED_CONFIGURATION + ":" + spec.fixture.replace("ParityTest.kt", "LocalePreservationTest.kt"),
        )
        self.assertEqual(2, len(re.findall(r"@Test fun (\w+)\(", supplemental)))
        self.assertTrue(set(spec.methods).isdisjoint(re.findall(r"@Test fun (\w+)\(", supplemental)))

    def test_comparator_each_original_current_fixture_production_oracle_helper_and_ancestor_fails_on_drift(self):
        spec = binding.COMPARATOR
        for path, producer, _ in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            for revision in (HEAD, producer):
                def changed(*args):
                    if args == ("rev-parse", revision + ":" + path):
                        return "0" * 40
                    return self.family_git(*args, family="comparator")
                with self.subTest(path=path, revision=revision), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(HEAD, self.claim, family="comparator")
            def missing(*args):
                if args == ("merge-base", "--is-ancestor", producer, HEAD):
                    raise subprocess.CalledProcessError(1, "missing comparator ancestor")
                return self.family_git(*args, family="comparator")
            with self.subTest(producer=producer), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=missing):
                with self.assertRaises(subprocess.CalledProcessError):
                    binding.prepare_binding(HEAD, self.claim, family="comparator")

    def test_comparator_wrong_count_identity_order_or_supplemental_substitution_cannot_bind(self):
        methods = binding.COMPARATOR.methods
        for actual in (methods[:-1], (*methods, "extra"), methods[::-1],
                       (*methods[:-1], methods[0]),
                       (*methods[:-1], "turkishProducerCasesPreserveLocaleTailoringOnAndroid")):
            def changed(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in actual)
                return self.family_git(*args, family="comparator")
            with self.subTest(methods=actual), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=changed):
                with self.assertRaisesRegex(binding.PortError, "eleven original"):
                    binding.prepare_binding(HEAD, self.claim, family="comparator")

    def test_comparator_reuses_stale_source_policy_scope_family_and_publication_guards(self):
        bound = source("comparator")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="comparator")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="e" * 64),
                       replace(bound, source_revision="e" * 40), source("nodes"), source("diagnostics"), source()):
            with self.subTest(binding=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.verify_current_request(request)
                git.assert_not_called()
        for published in ("", "e" * 40 + "\t" + binding.PUBLIC_REF, HEAD + "\trefs/heads/other"):
            with self.subTest(published=published), patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "git", return_value=published):
                with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                    binding.verify_current_request(request)

    def test_comparator_exact_eleven_outcomes_not_pairs_or_other_families_require_positive_unforced_completion(self):
        request = binding.request_for(source("comparator"), 37)
        positive = transcript(family="comparator")
        actual = binding.account_outcomes(positive, request, process_exit=0, forced_termination=False)
        self.assertEqual((11, 11, 11, 11, 0, 0, 0), tuple(actual[key] for key in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("comparator", actual["family"])
        self.assertEqual(request.operation_owner, actual["native_lane_operation_owner"])
        self.assertFalse(actual["acceptance"])
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(code, family="comparator"), request,
                                         process_exit=0, forced_termination=False)
        for exit_code, forced in ((1, False), (True, False), (0, True), (0, None)):
            with self.subTest(exit_code=exit_code, forced=forced), self.assertRaises(binding.PortError):
                binding.account_outcomes(positive, request, process_exit=exit_code, forced_termination=forced)
        missing = completed("".join(record(identity, 1, 11) + record(identity, 0, 11)
                                    for identity in request.identities[:-1]), 11)
        for value in ("", missing, transcript(), transcript(family="nodes"), transcript(family="diagnostics"),
                      positive.replace("numtests=11", "numtests=13"),
                      positive.replace(request.identities[0].partition("#")[2], request.identities[1].partition("#")[2]),
                      positive.replace("NativeLocalizedCompareParityTest", "NativeLocalizedCompareLocalePreservationTest"),
                      positive.replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                binding.account_outcomes(value, request, process_exit=0, forced_termination=False)

    def test_comparator_cli_prepares_original_us_eleven_api37_without_live_authority(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source("comparator")) as current, \
                patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", "comparator"]))
        current.assert_called_once_with(HEAD, family="comparator")
        result = json.loads(output.getvalue())
        self.assertEqual([37], [request["api"] for request in result["requests"]])
        self.assertEqual(list(binding.COMPARATOR.identities), result["requests"][0]["identities"])
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])

    def test_traz_exact_two_api37_original_binding_and_unique_owner_do_not_admit_arbitrary_locales(self):
        bound = self.prepare("traz")
        self.assertEqual(source("traz"), bound)
        first, second = [binding.request_for(bound, 37) for _ in range(2)]
        self.assertEqual(binding.TRAZ.identities, first.identities)
        self.assertEqual(2, len(first.identities))
        self.assertNotEqual(first.operation_owner, second.operation_owner)
        self.assertTrue(first.operation_owner.startswith(binding.CANONICAL + ":traz:"))
        for api in (30, 31, 33, 34, 36, 38, True, "37", 37.0):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(bound, api)
        for family in ("tr", "az", "tr-TR", "az-AZ", "comparator-tr-az", "TRAZ", None):
            with self.subTest(family=family), patch.object(binding, "source_identity") as identity:
                with self.assertRaises(binding.PortError):
                    binding.prepare_binding(HEAD, self.claim, family=family)
                identity.assert_not_called()
        for changes in ({"producer_revision": "0" * 40}, {"production_blob": "0" * 40},
                        {"fixture_blob": "0" * 40}, {"replacement_app_session": "retired"},
                        {"replacement_cli_session": "retired"}, {"canonical_session": "retired"}):
            with self.subTest(changes=changes), self.assertRaises(binding.PortError):
                binding.request_for(replace(bound, **changes), 37)
        for family in ("language", "diagnostics", "nodes", "comparator"):
            with self.subTest(family=family), self.assertRaises(binding.PortError):
                binding.request_for(replace(source(family), family="traz"), 37)
            with self.assertRaises(binding.PortError):
                binding.LanguageRequest(bound, 37, binding.CANONICAL + ":" + family + ":" + "a" * 32)

    def test_traz_original_current_git_vectors_and_helpers_are_producer_preservation_not_us_oracle(self):
        spec = binding.TRAZ
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                self.assertEqual(blob, binding.git("rev-parse", PINNED_CONFIGURATION + ":" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, PINNED_CONFIGURATION))
        for revision in (spec.producer, PINNED_CONFIGURATION):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
            self.assertIn('assertProducerCases("tr-TR")', body)
            self.assertIn('assertProducerCases("az-AZ")', body)
            self.assertIn("newInstance(Locale.forLanguageTag(tag))", body)
            self.assertIn("independentFoundationOracle=false", body)
            self.assertNotIn("Locale.US", body)
        helper, producer, _ = spec.source_pins[1]
        vectors = binding.git("show", producer + ":" + helper)
        self.assertIn('for (tag in listOf("tr-TR", "az-AZ"))', vectors)
        self.assertIn('"I" to "\u0131"', vectors)
        self.assertIn('"\u0130" to "i"', vectors)
        self.assertFalse(any("oracle-output" in path or "oracle.swift" in path for path, _, _ in spec.source_pins))
        self.assertTrue(set(spec.identities).isdisjoint(binding.COMPARATOR.identities))

    def test_traz_current_original_pin_ancestor_method_count_identity_and_order_drift_fail_closed(self):
        spec = binding.TRAZ
        for path, producer, _ in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            for revision in (HEAD, producer):
                def changed(*args):
                    if args == ("rev-parse", revision + ":" + path):
                        return "0" * 40
                    return self.family_git(*args, family="traz")
                with self.subTest(path=path, revision=revision), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(HEAD, self.claim, family="traz")
            def missing(*args):
                if args == ("merge-base", "--is-ancestor", producer, HEAD):
                    raise subprocess.CalledProcessError(1, "missing supplemental ancestor")
                return self.family_git(*args, family="traz")
            with self.subTest(producer=producer), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=missing):
                with self.assertRaises(subprocess.CalledProcessError):
                    binding.prepare_binding(HEAD, self.claim, family="traz")
        for methods in (spec.methods[:1], (*spec.methods, "extra"), spec.methods[::-1],
                        (spec.methods[0], spec.methods[0]), binding.METHODS, binding.COMPARATOR.methods[:2]):
            def changed_methods(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in methods)
                return self.family_git(*args, family="traz")
            with self.subTest(methods=methods), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=changed_methods):
                with self.assertRaisesRegex(binding.PortError, "two original"):
                    binding.prepare_binding(HEAD, self.claim, family="traz")

    def test_traz_reuses_current_source_policy_scope_family_and_publication_guards(self):
        bound = source("traz")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="traz")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="e" * 64),
                       replace(bound, source_revision="e" * 40), source(), source("comparator"),
                       source("nodes"), source("diagnostics")):
            with self.subTest(binding=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.verify_current_request(request)
                git.assert_not_called()
        for published in ("", "e" * 40 + "\t" + binding.PUBLIC_REF, HEAD + "\trefs/heads/other"):
            with self.subTest(published=published), patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "git", return_value=published):
                with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                    binding.verify_current_request(request)

    def test_traz_exact_positive_two_cannot_substitute_language_us_or_partial_locale_outcomes(self):
        request = binding.request_for(source("traz"), 37)
        positive = transcript(family="traz")
        result = binding.account_outcomes(positive, request, process_exit=0, forced_termination=False)
        self.assertEqual((2, 2, 2, 2, 0, 0, 0), tuple(result[key] for key in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("traz", result["family"])
        self.assertEqual(request.operation_owner, result["native_lane_operation_owner"])
        self.assertFalse(result["acceptance"])
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(code, family="traz"), request,
                                         process_exit=0, forced_termination=False)
        for process_exit, forced in ((1, False), (True, False), (0, True), (0, None)):
            with self.subTest(process_exit=process_exit, forced=forced), self.assertRaises(binding.PortError):
                binding.account_outcomes(positive, request, process_exit=process_exit, forced_termination=forced)
        missing = completed(record(request.identities[0], 1, 2) + record(request.identities[0], 0, 2), 2)
        for value in ("", missing, transcript(), transcript(family="comparator"),
                      transcript(family="nodes"), transcript(family="diagnostics"),
                      positive.replace("numtests=2", "numtests=13"),
                      positive.replace(binding.TRAZ.methods[0], binding.TRAZ.methods[1]),
                      positive.replace("NativeLocalizedCompareLocalePreservationTest", "NativeLocalizedCompareParityTest"),
                      positive.replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                binding.account_outcomes(value, request, process_exit=0, forced_termination=False)

    def test_traz_preparation_cli_selects_two_fixed_methods_on_api37_without_locale_input_or_live_authority(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source("traz")) as current, \
                patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", "traz"]))
        current.assert_called_once_with(HEAD, family="traz")
        result = json.loads(output.getvalue())
        self.assertEqual([37], [request["api"] for request in result["requests"]])
        self.assertEqual(list(binding.TRAZ.identities), result["requests"][0]["identities"])
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])
        with patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as caught:
            binding.main(["--head", HEAD, "--family", "traz", "--locale", "en-US"])
        self.assertEqual(2, caught.exception.code)

    def test_los_binds_only_original_saved_endpoint_method_not_the_separate_platform_control(self):
        bound = self.prepare("los")
        self.assertEqual(source("los"), bound)
        first, second = [binding.request_for(bound, 37) for _ in range(2)]
        self.assertEqual((binding.LOS.klass + "#savedRepeatersExposeNamedNativeActionsForNonGestureEndpointSelectionAtFont200",),
                         first.identities)
        self.assertEqual(1, len(first.identities))
        self.assertNotEqual(first.operation_owner, second.operation_owner)
        self.assertTrue(first.operation_owner.startswith(binding.CANONICAL + ":los:"))
        self.assertNotIn(binding.LOS.klass + "#" + binding.LOS.fixture_methods[0], first.identities)
        for api in (30, 31, 33, 34, 36, 38, True, "37", 37.0):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(bound, api)
        for changes in ({"producer_revision": "0" * 40}, {"production_blob": "0" * 40},
                        {"fixture_blob": "0" * 40}, {"replacement_app_session": "retired"},
                        {"replacement_cli_session": "retired"}, {"canonical_session": "retired"}):
            with self.subTest(changes=changes), self.assertRaises(binding.PortError):
                binding.request_for(replace(bound, **changes), 37)
        for family in ("language", "diagnostics", "nodes", "comparator", "traz"):
            with self.subTest(family=family), self.assertRaises(binding.PortError):
                binding.request_for(replace(source(family), family="los"), 37)
            with self.assertRaises(binding.PortError):
                binding.LanguageRequest(bound, 37, binding.CANONICAL + ":" + family + ":" + "a" * 32)

    def test_los_original_and_current_fixture_pins_preserve_inputs_dependencies_and_full_inventory(self):
        spec = binding.LOS
        registration = "e4c1aa7aa3b2c759524f198f5cfa09024143c062"
        self.assertEqual("", binding.git("merge-base", "--is-ancestor", registration, spec.producer))
        initial = binding.git("show", registration + ":" + spec.fixture)
        self.assertIn(spec.methods[0], re.findall(r"@Test fun (\w+)\(", initial))
        self.assertEqual("f8c2cab5dc964548e938f1e023f72912874fc2a8", spec.original_fixture_blob)
        self.assertNotEqual(spec.original_fixture_blob, spec.fixture_blob)
        for path, producer, blob in ((spec.fixture, spec.producer, spec.original_fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                current_blob = spec.fixture_blob if path == spec.fixture else blob
                self.assertEqual(current_blob, binding.git("rev-parse", PINNED_CONFIGURATION + ":" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, PINNED_CONFIGURATION))
        for revision in (spec.producer, PINNED_CONFIGURATION):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.fixture_methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
            for fixed in ("UUID(0, 301)", "UUID(0, 302)", "UUID(0, 303)", "latitude = 37.8, longitude = -122.4",
                          "latitude = 37.7, longitude = -122.3", "deviceFrequencyKHz = { 906000u }",
                          "return 100.0", 'error("No terrain analysis requested")',
                          "LineOfSightEntry(dependencies", "nativeSavedEndpoint", "assertEndpointState"):
                self.assertIn(fixed, body)
        retained = json.loads(binding.git(
            "show", PINNED_CONFIGURATION + ":docs/android/evidence/WP-503/device-losendpoints-traversal-integration.json",
        ))
        self.assertEqual(spec.original_fixture_blob, retained["audit_fixture_blobs"][spec.fixture])
        for item in retained["source_import"]["exact_producer_files"]:
            if item["path"] == spec.fixture:
                self.assertEqual(spec.original_fixture_blob, item["producer_blob"])
            else:
                self.assertEqual(item["producer_blob"], binding.git("rev-parse", PINNED_CONFIGURATION + ":" + item["path"]))

    def test_los_current_fixture_synchronizes_only_its_mount_before_unchanged_count_window_and_assertions(self):
        spec = binding.LOS
        original = binding.git("show", spec.producer + ":" + spec.fixture)
        current = binding.git("show", PINNED_CONFIGURATION + ":" + spec.fixture)
        mount = 'LineOfSightEntry(dependencies, modifier = Modifier.testTag("los-current-fixture"))'
        synchronization = ('compose.onNode(hasTestTag("los-current-fixture")).assertExists()\n'
                           '        fixtureMounted = true\n'
                           '        compose.waitForIdle()\n'
                           '        compose.waitUntil { catalogReads == 1 }')
        self.assertIn(mount, current)
        self.assertIn(synchronization, current)
        count = "        compose.waitUntil { catalogReads == 1 }"
        self.assertEqual(original[original.index(count):], current[current.index(count):])
        dependencies = "        val dependencies = LineOfSightFeatureDependencies("
        content = "        content { MeshCoreTheme(motionScale = 0f) { AuditSurface {"
        self.assertEqual(original[original.index(dependencies):original.index(content)],
                         current[current.index(dependencies):current.index(content)])
        control = "@Test fun " + spec.fixture_methods[0]
        selected = "@Test fun " + spec.methods[0]
        self.assertEqual(original[original.index(control):original.index(selected)],
                         current[current.index(control):current.index(selected)])
        for forbidden in ("Thread.sleep", "delay(", "timeoutMillis =", "waitUntil(timeout"):
            self.assertNotIn(forbidden, current)
        self.assertIn("override fun failed(error: Throwable, description: Description)", current)
        self.assertIn('description.methodName != "' + spec.methods[0] + '"', current)
        self.assertIn("WP503_LOS_FAILURE_COUNTERS|catalogReads=$catalogReads|elevationReads=$elevationReads", current)
        self.assertIn("currentFixtureSemanticsObserved=$fixtureMounted", current)
        self.assertIn(".printToString(maxDepth = 5).take(8192)", current)

    def test_los_all_original_current_pins_ancestors_and_full_two_method_inventory_fail_on_drift(self):
        spec = binding.LOS
        for path, producer, _ in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            for revision in (HEAD, producer):
                def changed(*args):
                    if args == ("rev-parse", revision + ":" + path):
                        return "0" * 40
                    return self.family_git(*args, family="los")
                with self.subTest(path=path, revision=revision), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(HEAD, self.claim, family="los")
            def missing(*args):
                if args == ("merge-base", "--is-ancestor", producer, HEAD):
                    raise subprocess.CalledProcessError(1, "missing LoS ancestor")
                return self.family_git(*args, family="los")
            with self.subTest(producer=producer), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=missing):
                with self.assertRaises(subprocess.CalledProcessError):
                    binding.prepare_binding(HEAD, self.claim, family="los")
        inventory = spec.fixture_methods
        for methods in (spec.methods, inventory[:1], inventory[::-1], (*inventory, "extra"),
                        (inventory[0], inventory[0]), (inventory[0], "genericLosProxy")):
            def changed_methods(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in methods)
                return self.family_git(*args, family="los")
            with self.subTest(methods=methods), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=changed_methods):
                with self.assertRaisesRegex(binding.PortError, "one original"):
                    binding.prepare_binding(HEAD, self.claim, family="los")
        for selected in ((), (spec.methods[0], spec.methods[0]), ("genericLosProxy",)):
            with self.subTest(selected=selected), \
                    patch.object(binding, "FAMILIES", {**binding.FAMILIES, "los": replace(spec, methods=selected)}), \
                    patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=lambda *args: self.family_git(*args, family="los")):
                with self.assertRaisesRegex(binding.PortError, "one original"):
                    binding.prepare_binding(HEAD, self.claim, family="los")

    def test_los_reuses_source_policy_scope_family_and_publication_rechecks(self):
        bound = source("los")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="los")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="e" * 64),
                       replace(bound, source_revision="e" * 40), source("traz"), source("nodes"), source()):
            with self.subTest(binding=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.verify_current_request(request)
                git.assert_not_called()
        for published in ("", "e" * 40 + "\t" + binding.PUBLIC_REF, HEAD + "\trefs/heads/other"):
            with self.subTest(published=published), patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "git", return_value=published):
                with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                    binding.verify_current_request(request)

    def test_los_exact_positive_one_never_substitutes_control_or_class_count_and_requires_normal_completion(self):
        request = binding.request_for(source("los"), 37)
        positive = transcript(family="los")
        result = binding.account_outcomes(positive, request, process_exit=0, forced_termination=False)
        self.assertEqual((1, 1, 1, 1, 0, 0, 0), tuple(result[key] for key in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("los", result["family"])
        self.assertFalse(result["acceptance"])
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(code, family="los"), request,
                                         process_exit=0, forced_termination=False)
        for process_exit, forced in ((1, False), (True, False), (0, True), (0, None)):
            with self.subTest(process_exit=process_exit, forced=forced), self.assertRaises(binding.PortError):
                binding.account_outcomes(positive, request, process_exit=process_exit, forced_termination=forced)
        both = tuple(binding.LOS.klass + "#" + method for method in binding.LOS.fixture_methods)
        whole_class = completed("".join(record(identity, 1, 2) + record(identity, 0, 2) for identity in both), 2)
        for value in ("", transcript(), transcript(family="comparator"), whole_class,
                      positive.replace(binding.LOS.methods[0], binding.LOS.fixture_methods[0]),
                      positive.replace("numtests=1", "numtests=2"),
                      completed(record(request.identities[0], 1, 1), 1),
                      completed(record(request.identities[0], 1, 1) + record(request.identities[0], 0, 1) * 2, 1),
                      positive.replace("INSTRUMENTATION_CODE: -1", "")):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                binding.account_outcomes(value, request, process_exit=0, forced_termination=False)

    def test_los_preparation_cli_selects_only_original_api37_method_without_native_authority(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source("los")) as current, \
                patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", "los"]))
        current.assert_called_once_with(HEAD, family="los")
        result = json.loads(output.getvalue())
        self.assertEqual([37], [request["api"] for request in result["requests"]])
        self.assertEqual(list(binding.LOS.identities), result["requests"][0]["identities"])
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])

    def test_cached_chat_selects_original_resize_method_only_on_api37_with_unique_current_owner(self):
        bound = self.prepare("cached-chat")
        self.assertEqual(source("cached-chat"), bound)
        spec = binding.CACHED_CHAT
        first, second = [binding.request_for(bound, 37) for _ in range(2)]
        self.assertEqual((spec.klass + "#realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200",),
                         first.identities)
        self.assertEqual(10, len(spec.fixture_methods))
        self.assertNotEqual(first.operation_owner, second.operation_owner)
        self.assertTrue(first.operation_owner.startswith(binding.CANONICAL + ":cached-chat:"))
        for api in (30, 31, 33, 34, 36, 38, True, "37", 37.0):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(bound, api)
        for changes in ({"producer_revision": "0" * 40}, {"production_blob": "0" * 40},
                        {"fixture_blob": "0" * 40}, {"replacement_app_session": "retired"},
                        {"replacement_cli_session": "retired"}, {"canonical_session": "retired"}):
            with self.subTest(changes=changes), self.assertRaises(binding.PortError):
                binding.request_for(replace(bound, **changes), 37)
        for family in ("language", "diagnostics", "nodes", "comparator", "traz", "los"):
            with self.subTest(family=family), self.assertRaises(binding.PortError):
                binding.request_for(replace(source(family), family="cached-chat"), 37)
            with self.assertRaises(binding.PortError):
                binding.LanguageRequest(bound, 37, binding.CANONICAL + ":" + family + ":" + "a" * 32)

    def test_cached_chat_actual_original_fixture_inputs_and_separate_current_delegates_are_pinned(self):
        spec = binding.CACHED_CHAT
        registration = "4f89d2168e03786588e760a2d8310fbe9edc89d0"
        retained = "3f39a777376e322e2bc533f1d1caf92a87a44ed8"
        current_producer = "03306ff125655f977d88c5b517ad4cab26d9e771"
        chats_producer = "32bb28c6ea08f697ee69222f775237c190460883"
        merged_producer = "514c451653531da9e3cc47139a9e4e44fb9d6cf0"
        current_graph = "31557a16ca4492bbd669a7ad0358e702c5c54031"
        self.assertEqual("c466bf76ceadddb370ed9803be6d9f403c0275b5", spec.production_blob)
        self.assertIn((spec.production, current_graph, spec.production_blob), spec.source_pins)
        self.assertEqual("49528b80d06929a403597b389fafb0e8a090d6e6",
                         binding.git("rev-parse", merged_producer + ":" + spec.production))
        self.assertEqual("ea4fbdfcd7100b5c48b48b6a5b5e5bbcd795134e",
                         binding.git("rev-parse", chats_producer + ":" + spec.production))
        self.assertEqual(
            "fbf18c35b67de68a6a1a19f6180ea7f2ea92fa06",
            binding.git("rev-parse", "4b6629ac2bf215577ec1522530a77de61ed577fe:" + spec.production),
        )
        self.assertEqual([
            spec.production + "/kotlin/com/meshcoreone/android/feature/chats/actions/MessageActionsUi.kt",
            spec.production + "/kotlin/com/meshcoreone/android/feature/chats/rooms/RoomConversationScreen.kt",
        ], binding.git("diff", "--name-only", "4b6629ac2bf215577ec1522530a77de61ed577fe",
                       chats_producer, "--", spec.production).splitlines())
        self.assertEqual([
            spec.production + "/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModel.kt",
            spec.production + "/kotlin/com/meshcoreone/android/feature/chats/timeline/TimelineContracts.kt",
        ], binding.git("diff", "--name-only", chats_producer,
                       merged_producer, "--", spec.production).splitlines())
        for path, producer, blob in (
            ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt",
             current_graph, "ee5c5e112dcf041d8dbbd1471e2ad01d755b6bf8"),
            ("android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt",
             merged_producer, "11d18815632db5298a0552636ac86a7c73a657d4"),
            ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppMessageContent.kt",
             merged_producer, "dc2633bc24ee95addc79c3a9869257934cf402a1"),
        ):
            self.assertIn((path, producer, blob), spec.source_pins)
        self.assertEqual("", binding.git("merge-base", "--is-ancestor", registration, spec.producer))
        initial = binding.git("show", registration + ":" + spec.fixture)
        self.assertIn(spec.methods[0], re.findall(r"@Test fun (\w+)\(", initial))
        self.assertEqual(spec.fixture_blob, binding.git("rev-parse", retained + ":" + spec.fixture))
        snapshot = "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityServiceSnapshot.kt"
        self.assertEqual("3dee4f40c5aec6fb0adb1bb406a233fb223b43a4",
                         binding.git("rev-parse", retained + ":" + snapshot))
        self.assertIn("Public accessibility system snapshot failed", binding.git("show", PINNED_CONFIGURATION + ":" + snapshot))
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                self.assertEqual(blob, binding.git("rev-parse", PINNED_CONFIGURATION + ":" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, PINNED_CONFIGURATION))
        for revision in (spec.producer, PINNED_CONFIGURATION):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.fixture_methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
            for fixed in ("UUID(0, 503001)", "UUID(0, 503002)", "UUID(0, 503100L + index)",
                          "ByteArray(32) { 0x50 }", "ByteArray(32) { 0x51 }",
                          'name = "Cached \\u4e2d\\u6587 peer"', 'val draft = "Draft \\u4e2d\\u6587 \\u0645\\u0631\\u062d\\u0628\\u0627"',
                          "val history = List(36)", "1_700_000_000L + index", "val target = history[15]",
                          "MessageDirection.INCOMING", "MessageStatus.DELIVERED, isRead = true",
                          "val alignDelta = 12f - captureViewport().first", "24f, offsetOnly.first, 0.5f",
                          'listOf("900x900" to "navigation-rail", "360x900" to "navigation-hidden")',
                          "row.layoutInfo.isPlaced && coordinates.isAttached", "firstOffset.second, offsetOnly.second",
                          "drafts.set(conversation, originalDraft)", "container.processStore.deleteDeviceAndData(device.id)",
                          'snapshotNativeServiceState(manager, "launcher-restoration-timeout", afterOriginalDeadline = true)',
                          'shell("wm size ${override ?: "reset"}")'):
                self.assertIn(fixed, body)
        historical = (
            ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt",
             "2b5ccd083c536c981169543377919d3c8c1f1a95", "6e191c79de3a5cebf3f8e0603956e180f97bd014"),
            ("android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt",
             "ac0301b1b167a4f0f2a97fb2b522b51e68b67ced", "99ff02f5c15359e6260316d6411a5bac7a62518e"),
        )
        for path, old, current in historical:
            self.assertEqual(old, binding.git("rev-parse", retained + ":" + path))
            self.assertNotEqual(old, current)
            self.assertEqual(current, binding.git("rev-parse", current_producer + ":" + path))
            self.assertEqual(current, binding.git("rev-parse", "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c:" + path))
            def historical_as_current(*args):
                if args == ("rev-parse", HEAD + ":" + path):
                    return old
                return self.family_git(*args, family="cached-chat")
            with patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=historical_as_current):
                with self.assertRaisesRegex(binding.PortError, "drifted"):
                    binding.prepare_binding(HEAD, self.claim, family="cached-chat")
        content = binding.git("show", current_producer + ":" + historical[0][0])
        delegate = binding.git("show", current_producer + ":" + historical[1][0])
        self.assertIn("LaunchedEffect(composer, notificationSource)", content)
        self.assertIn("feature.restoreNotificationDraft(target, composer, notificationSource)", content)
        self.assertIn("if (source !== notifications) return", delegate)
        self.assertIn("consumeDraft(EntityKey(radio, target.contact.id)) ?: return", delegate)
        self.assertEqual("", binding.git("merge-base", "--is-ancestor", current_producer,
                                        "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c"))

    def test_cached_chat_every_original_current_pin_ancestor_inventory_and_selection_reject_drift(self):
        spec = binding.CACHED_CHAT
        for path, producer, _ in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            for revision in (HEAD, producer):
                def changed(*args):
                    if args == ("rev-parse", revision + ":" + path):
                        return "0" * 40
                    return self.family_git(*args, family="cached-chat")
                with self.subTest(path=path, revision=revision), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(HEAD, self.claim, family="cached-chat")
            def missing(*args):
                if args == ("merge-base", "--is-ancestor", producer, HEAD):
                    raise subprocess.CalledProcessError(1, "missing cached-chat ancestor")
                return self.family_git(*args, family="cached-chat")
            with self.subTest(producer=producer), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=missing):
                with self.assertRaises(subprocess.CalledProcessError):
                    binding.prepare_binding(HEAD, self.claim, family="cached-chat")
        inventory = spec.fixture_methods
        for methods in (spec.methods, inventory[:2], inventory[::-1], (*inventory, "extra"),
                        (inventory[0],) * 10, (*inventory[:2], "genericChatProxy", *inventory[3:])):
            def changed_methods(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in methods)
                return self.family_git(*args, family="cached-chat")
            with self.subTest(methods=methods), patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=changed_methods):
                with self.assertRaisesRegex(binding.PortError, "one original"):
                    binding.prepare_binding(HEAD, self.claim, family="cached-chat")
        for selected in ((), (spec.methods[0],) * 2, ("genericChatProxy",)):
            with self.subTest(selected=selected), \
                    patch.object(binding, "FAMILIES", {**binding.FAMILIES, "cached-chat": replace(spec, methods=selected)}), \
                    patch.object(binding, "source_identity", return_value="b" * 64), \
                    patch.object(binding, "git", side_effect=lambda *args: self.family_git(*args, family="cached-chat")):
                with self.assertRaisesRegex(binding.PortError, "one original"):
                    binding.prepare_binding(HEAD, self.claim, family="cached-chat")

    def test_cached_chat_reuses_current_source_policy_scope_family_and_publication_guards(self):
        bound = source("cached-chat")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="cached-chat")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="e" * 64),
                       replace(bound, source_revision="e" * 40), *(source(family) for family in binding.FAMILIES if family != "cached-chat")):
            with self.subTest(binding=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.verify_current_request(request)
                git.assert_not_called()
        for published in ("", "e" * 40 + "\t" + binding.PUBLIC_REF, HEAD + "\trefs/heads/other"):
            with self.subTest(published=published), patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "git", return_value=published):
                with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                    binding.verify_current_request(request)

    def test_cached_chat_positive_one_cannot_substitute_other_launcher_methods_class_or_bad_outcomes(self):
        spec = binding.CACHED_CHAT
        request = binding.request_for(source("cached-chat"), 37)
        positive = transcript(family="cached-chat")
        result = binding.account_outcomes(positive, request, process_exit=0, forced_termination=False)
        self.assertEqual((1, 1, 1, 1, 0, 0, 0), tuple(result[key] for key in (
            "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
        self.assertEqual("cached-chat", result["family"])
        self.assertFalse(result["acceptance"])
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(binding.PortError):
                binding.account_outcomes(transcript(code, family="cached-chat"), request,
                                         process_exit=0, forced_termination=False)
        for process_exit, forced in ((1, False), (True, False), (0, True), (0, None)):
            with self.subTest(process_exit=process_exit, forced=forced), self.assertRaises(binding.PortError):
                binding.account_outcomes(positive, request, process_exit=process_exit, forced_termination=forced)
        identities = tuple(spec.klass + "#" + method for method in spec.fixture_methods)
        whole_class = completed("".join(record(item, 1, 10) + record(item, 0, 10) for item in identities), 10)
        for value in ("", transcript(family="los"), whole_class, positive.replace("numtests=1", "numtests=10"),
                      completed(record(request.identities[0], 1, 1), 1),
                      completed(record(request.identities[0], 1, 1) + record(request.identities[0], 0, 1) * 2, 1),
                      positive.replace("INSTRUMENTATION_CODE: -1", ""),
                      *(positive.replace(spec.methods[0], method) for method in spec.fixture_methods if method not in spec.methods)):
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                binding.account_outcomes(value, request, process_exit=0, forced_termination=False)

    def test_cached_chat_source_only_cli_exposes_fixed_one_and_never_arbitrary_selector_or_live_authority(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source("cached-chat")) as current, \
                patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", "cached-chat"]))
        current.assert_called_once_with(HEAD, family="cached-chat")
        result = json.loads(output.getvalue())
        self.assertEqual([37], [request["api"] for request in result["requests"]])
        self.assertEqual(list(binding.CACHED_CHAT.identities), result["requests"][0]["identities"])
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])
        for option in ("--class", "--method"):
            with self.subTest(option=option), patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as caught:
                binding.main(["--head", HEAD, "--family", "cached-chat", option, "arbitrary"])
            self.assertEqual(2, caught.exception.code)

    def test_cold_platform_controls_bind_one_original_api37_method_and_distinct_truthful_owners(self):
        for name, klass, method in (
            ("platform-service-control", "NativePlatformServiceControlTest",
             "actualServiceStateActivatesAndRestoresWithin1000msWithoutActivityOrComposeRule"),
            ("platform-callback-control", "NativePlatformCallbackDeliveryTest",
             "actualServiceStateRetains1000msWithPublicCallbackAndMainLooperObservations"),
        ):
            with self.subTest(family=name):
                bound = self.prepare(name)
                self.assertEqual(source(name), bound)
                first, second = [binding.request_for(bound, 37) for _ in range(2)]
                self.assertEqual(("com.meshcoreone.android.accessibility." + klass + "#" + method,),
                                 first.identities)
                self.assertNotEqual(first.operation_owner, second.operation_owner)
                self.assertTrue(first.operation_owner.startswith(binding.CANONICAL + ":" + name + ":"))
                for api in (30, 31, 33, 34, 36, 38, True, "37", 37.0):
                    with self.subTest(api=api), self.assertRaises(binding.PortError):
                        binding.request_for(bound, api)
                for changes in ({"producer_revision": "0" * 40}, {"production_blob": "0" * 40},
                                {"fixture_blob": "0" * 40}, {"replacement_app_session": "retired"},
                                {"replacement_cli_session": "retired"}, {"canonical_session": "retired"}):
                    with self.subTest(changes=changes), self.assertRaises(binding.PortError):
                        binding.request_for(replace(bound, **changes), 37)
                for other in binding.FAMILIES:
                    if other != name:
                        with self.subTest(other=other), self.assertRaises(binding.PortError):
                            binding.request_for(replace(source(other), family=name), 37)
                        with self.assertRaises(binding.PortError):
                            binding.LanguageRequest(bound, 37, binding.CANONICAL + ":" + other + ":" + "a" * 32)

    def test_cold_platform_actual_original_fixtures_deadlines_flags_diagnostics_and_app_provenance_are_unchanged(self):
        previous = binding.git("show", "ff0871665b6b071194684a72489fefa0af3810f5:android/accessibility-tests/language_binding.py")
        current = binding.Path(binding.__file__).read_text(encoding="utf-8")
        expected = previous[previous.index("DIAGNOSTICS ="):previous.index("FAMILIES =")]
        expected = expected.replace(
            '"514c451653531da9e3cc47139a9e4e44fb9d6cf0", "49528b80d06929a403597b389fafb0e8a090d6e6"',
            '"31557a16ca4492bbd669a7ad0358e702c5c54031", "c466bf76ceadddb370ed9803be6d9f403c0275b5"',
        ).replace('"49528b80d06929a403597b389fafb0e8a090d6e6"',
                  '"c466bf76ceadddb370ed9803be6d9f403c0275b5"').replace(
            '"03306ff125655f977d88c5b517ad4cab26d9e771", "6e191c79de3a5cebf3f8e0603956e180f97bd014"',
            '"31557a16ca4492bbd669a7ad0358e702c5c54031", "ee5c5e112dcf041d8dbbd1471e2ad01d755b6bf8"',
        )
        boundary = "\n)\nNODES ="
        self.assertEqual(1, expected.count(boundary))
        expected = expected.replace(
            boundary,
            '\n    current_production=(\n'
            '        "e70d637a75be83921a88b603251a297c74ed2b44",\n'
            '        "0cb54d923d8c0a343caf37279012c79380c20412",\n'
            '    ),\n)\nNODES =',
            1,
        )
        actual = current[current.index("DIAGNOSTICS ="):current.index("COLD_PLATFORM_SOURCE_PINS =")]
        start, end = actual.index("NODES ="), actual.index("COMPARATOR =")
        old_start, old_end = expected.index("NODES ="), expected.index("COMPARATOR =")
        self.assertEqual(expected[:old_start] + expected[old_end:], actual[:start] + actual[end:])
        for name in ("platform-service-control", "platform-callback-control"):
            spec = binding.family_spec(name)
            with self.subTest(family=name):
                for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
                    self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                    self.assertEqual(blob, binding.git("rev-parse", PINNED_CONFIGURATION + ":" + path))
                    self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, PINNED_CONFIGURATION))
                body = binding.git("show", PINNED_CONFIGURATION + ":" + spec.fixture)
                self.assertEqual(body, binding.git("show", spec.producer + ":" + spec.fixture))
                self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
                for fixed in ("Stage.values().sumOf", "monitor.addLifecycleCallback",
                              "monitor.removeLifecycleCallback", "originalFlags",
                              "FLAG_REQUEST_TOUCH_EXPLORATION_MODE", "CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION",
                              "info.flags = originalFlags", "automation.serviceInfo = info",
                              "elapsed <= 1000L", "elapsed >= 1000L",
                              "Thread.sleep(minOf(10L, 1000L - elapsed))",
                              "afterOriginalDeadline = true", "snapshotNativeServiceState",
                              "composeRule=false|testDispatcher=false"):
                    self.assertIn(fixed, body)
                for forbidden in ("DeviceAccessibilityHarness", "ActivityScenario", "createAndroidComposeRule",
                                  "runTest", "StandardTestDispatcher", "container.await", "launchActivity"):
                    self.assertNotIn(forbidden, body)
                if name == "platform-callback-control":
                    for fixed in ("addTouchExplorationStateChangeListener(callback, main)",
                                  "removeTouchExplorationStateChangeListener(callback)",
                                  "main.removeCallbacksAndMessages(token)", "Heartbeat",
                                  "main.looper.dump", "afterOriginalDeadline=${sampleEnd > 1000L}"):
                        self.assertIn(fixed, body)
                else:
                    self.assertIn("activityEvents.isEmpty()", body)
                self.assertEqual("android/app/src/main", spec.production)
                self.assertIn((spec.production, "db1478c897f46c5df25c94daf275b3335b327192",
                               "90cc4df7d8f5645b81a2321daf4e6a4c6bdd47c5"), spec.source_pins)
                self.assertIn(("android/app/build.gradle.kts", "ee1104b097899ab08f3f82227d8cde354bc92602",
                               "a0752382e0c641e417b6c92297bc5018f0ace2b0"), spec.source_pins)

    def test_real_app_changes_preserve_historical_pins_but_do_not_admit_current_native_execution(self):
        changed_revision = "4b22c2526618f5cb556f593be4ed169c38448615"
        for family in ("cached-chat", "platform-service-control", "platform-callback-control"):
            spec = binding.family_spec(family)
            with self.subTest(family=family):
                self.assertNotEqual(
                    binding.git("rev-parse", PINNED_CONFIGURATION + ":" + spec.production),
                    binding.git("rev-parse", changed_revision + ":" + spec.production),
                )
                self.assertEqual(spec.fixture_blob, binding.git("rev-parse", changed_revision + ":" + spec.fixture))
                with patch.object(binding, "source_identity", return_value="b" * 64):
                    reviewed = binding.prepare_binding(PINNED_CONFIGURATION, self.claim, family=family)
                    self.assertEqual(PINNED_CONFIGURATION, reviewed.source_revision)
                    self.assertEqual(spec.production_blob, reviewed.production_blob)
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(changed_revision, self.claim, family=family)

    def test_cold_platform_each_current_original_fixture_app_helper_and_ancestor_rejects_drift(self):
        for name in ("platform-service-control", "platform-callback-control"):
            spec = binding.family_spec(name)
            for path, producer, _ in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
                for revision in (HEAD, producer):
                    def changed(*args):
                        if args == ("rev-parse", revision + ":" + path):
                            return "0" * 40
                        return self.family_git(*args, family=name)
                    with self.subTest(family=name, path=path, revision=revision), \
                            patch.object(binding, "source_identity", return_value="b" * 64), \
                            patch.object(binding, "git", side_effect=changed):
                        with self.assertRaisesRegex(binding.PortError, "drifted"):
                            binding.prepare_binding(HEAD, self.claim, family=name)
                def missing(*args):
                    if args == ("merge-base", "--is-ancestor", producer, HEAD):
                        raise subprocess.CalledProcessError(1, "missing cold-control ancestor")
                    return self.family_git(*args, family=name)
                with self.subTest(family=name, producer=producer), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=missing):
                    with self.assertRaises(subprocess.CalledProcessError):
                        binding.prepare_binding(HEAD, self.claim, family=name)

    def test_cold_platform_inventory_cannot_be_empty_duplicated_extended_or_substituted(self):
        for name in ("platform-service-control", "platform-callback-control"):
            spec = binding.family_spec(name)
            for methods in ((), spec.methods * 2, (*spec.methods, "extra"), ("warmFeatureProxy",)):
                def changed(*args):
                    if args[0] == "show":
                        return "\n".join("@Test fun " + method + "() {}" for method in methods)
                    return self.family_git(*args, family=name)
                with self.subTest(family=name, methods=methods), \
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "one original"):
                        binding.prepare_binding(HEAD, self.claim, family=name)

    def test_cold_platform_reuses_current_source_policy_scope_and_exact_publication_guards(self):
        for name in ("platform-service-control", "platform-callback-control"):
            bound = source(name)
            request = binding.request_for(bound, 37)
            with self.subTest(family=name), patch.object(binding, "current_binding", return_value=bound) as current, \
                    patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                    patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
                self.assertEqual({"paused": True}, binding.verify_current_request(request))
            current.assert_called_once_with(HEAD, family=name)
            for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="e" * 64),
                           replace(bound, source_revision="e" * 40),
                           *(source(other) for other in binding.FAMILIES if other != name)):
                with self.subTest(family=name, binding=actual), \
                        patch.object(binding, "current_binding", return_value=actual), \
                        patch.object(binding, "git") as git:
                    with self.assertRaisesRegex(binding.PortError, "changed before"):
                        binding.verify_current_request(request)
                    git.assert_not_called()
            for published in ("", "e" * 40 + "\t" + binding.PUBLIC_REF, HEAD + "\trefs/heads/other"):
                with self.subTest(family=name, published=published), \
                        patch.object(binding, "current_binding", return_value=bound), \
                        patch.object(binding, "git", return_value=published):
                    with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                        binding.verify_current_request(request)

    def test_cold_platform_only_one_exact_positive_with_normal_host_terminal_and_strict_counts_is_accounted(self):
        for name in ("platform-service-control", "platform-callback-control"):
            request = binding.request_for(source(name), 37)
            positive = transcript(family=name)
            for value in (positive, positive.replace("\n", "\r\n")):
                result = binding.account_outcomes(value, request, process_exit=0, forced_termination=False)
                self.assertEqual((1, 1, 1, 1, 0, 0, 0), tuple(result[key] for key in (
                    "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
                self.assertEqual(name, result["family"])
                self.assertFalse(result["acceptance"])
            for code in (-1, -2, -3, -4):
                with self.subTest(family=name, code=code), self.assertRaises(binding.PortError):
                    binding.account_outcomes(transcript(code, family=name), request,
                                             process_exit=0, forced_termination=False)
            for process_exit, forced in ((1, False), (True, False), (None, False), (0, True), (0, None)):
                with self.subTest(family=name, process_exit=process_exit, forced=forced), \
                        self.assertRaises(binding.PortError):
                    binding.account_outcomes(positive, request, process_exit=process_exit, forced_termination=forced)
            for value in ("", *(transcript(family=other) for other in binding.FAMILIES if other != name),
                          positive.replace("numtests=1", "numtests=0"),
                          positive.replace("numtests=1", "numtests=2"),
                          positive.replace(request.identities[0].split("#")[1], "warmFeatureProxy"),
                          completed(record(request.identities[0], 1, 1), 1),
                          completed(record(request.identities[0], 1, 1) + record(request.identities[0], 0, 1) * 2, 1),
                          positive.replace("INSTRUMENTATION_CODE: -1", ""),
                          positive.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                          positive + "\nINSTRUMENTATION_CODE: -1\n"):
                with self.subTest(family=name, value=value), self.assertRaises(RuntimeError):
                    binding.account_outcomes(value, request, process_exit=0, forced_termination=False)

    def test_cold_platform_preparation_cli_has_no_live_authority_or_arbitrary_selector(self):
        for name in ("platform-service-control", "platform-callback-control"):
            output = io.StringIO()
            with patch.object(binding, "current_binding", return_value=source(name)) as current, \
                    patch("sys.stdout", output):
                self.assertEqual(0, binding.main(["--head", HEAD, "--family", name]))
            current.assert_called_once_with(HEAD, family=name)
            result = json.loads(output.getvalue())
            self.assertEqual([37], [request["api"] for request in result["requests"]])
            self.assertEqual(list(binding.family_spec(name).identities), result["requests"][0]["identities"])
            self.assertFalse(result["authority"])
            self.assertEqual(0, result["native_starts"])
            self.assertFalse(result["apks_built"])
            for option in ("--class", "--method", "--cohort", "--scenario"):
                with self.subTest(family=name, option=option), patch("sys.stderr", io.StringIO()), \
                        patch.object(binding, "current_binding") as current, self.assertRaises(SystemExit) as caught:
                    binding.main(["--head", HEAD, "--family", name, option, "arbitrary"])
                self.assertEqual(2, caught.exception.code)
                current.assert_not_called()
        for name in ("platform", "service-control", "callback-control", "full", "PLATFORM-SERVICE-CONTROL", None):
            with self.subTest(family=name), self.assertRaises(binding.PortError):
                binding.family_spec(name)

    def fallback_git(self, *arguments):
        if arguments == ("rev-parse", "refs/remotes/origin/main"):
            return HEAD
        if arguments[0] == "rev-parse" and arguments[1].endswith(":" + binding.FAMILIES["fallback-disclosure"].production):
            return binding.FAMILIES["fallback-disclosure"].production_blob
        return self.family_git(*arguments, family="fallback-disclosure")

    def prepare_fallback(self, git_override=None, claim=None):
        policy = {"repository": source().repository, "paused": False}
        manifest = Mock(sha256="c" * 64)
        manifest.wp.return_value = {"write_paths": ["android/accessibility-tests/"]}
        with patch.object(binding, "git", side_effect=git_override or self.fallback_git), \
                patch.object(binding, "trusted_execution_policy", return_value=policy), \
                patch.object(binding, "source_identity", return_value="b" * 64), \
                patch.object(binding, "load_manifest", return_value=manifest), \
                patch.object(binding, "load_json", return_value=policy), \
                patch.object(binding, "policy_revision", return_value="d" * 64), \
                patch.object(binding, "CapabilityEngine") as engine:
            result = binding.prepare_binding(HEAD, claim or self.claim, family="fallback-disclosure")
        engine.return_value.admit.assert_called_once_with(
            "WP-503", ["android/accessibility-tests/"], binding.ADAPTER, binding.OPERATION)
        return result

    def test_fallback_disclosure_fixed_scope_and_actual_reviewed_fixture_harness_provenance(self):
        spec = binding.family_spec("fallback-disclosure")
        self.assertEqual((31, 37), spec.apis)
        self.assertEqual(("com.meshcoreone.android.accessibility.NativeMessagingFlowAccessibilityTest"
                          "#realAppFallbackSenderLabelIncludesPossibleMatchDisclosure",), spec.identities)
        self.assertEqual(binding.MAIN_REF, spec.publication_ref)
        self.assertEqual(11, len(spec.fixture_methods))
        self.assertEqual((spec.fixture_methods[-1],), spec.methods)
        self.assertEqual(spec.production_blob, binding.git("rev-parse", spec.producer + ":" + spec.production))
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path, producer=producer):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                self.assertEqual(blob, binding.git("rev-parse", spec.producer + ":" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, spec.producer))
        body = binding.git("show", spec.producer + ":" + spec.fixture)
        self.assertEqual(spec.fixture_methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
        for name, historical in binding.FAMILIES.items():
            if name not in (spec.name, "nodes"):
                self.assertEqual(binding.PUBLIC_REF, historical.publication_ref)

    def test_fallback_disclosure_binds_current_main_production_not_an_old_app_tree(self):
        self.assertEqual(source("fallback-disclosure"), self.prepare_fallback())
        spec = binding.family_spec("fallback-disclosure")
        def updated(*args):
            if args == ("rev-parse", HEAD + ":" + spec.production):
                return "e" * 40
            return self.fallback_git(*args)
        bound = self.prepare_fallback(updated)
        self.assertEqual("e" * 40, bound.production_blob)
        self.assertEqual(spec.producer, bound.producer_revision)
        for api in spec.apis:
            self.assertEqual(spec.identities, binding.request_for(bound, api).identities)
        with patch.object(binding, "source_identity", side_effect=binding.PortError("dirty/current mismatch")):
            with patch.object(binding, "trusted_main_policy_for_source"), self.assertRaisesRegex(
                    binding.PortError, "dirty/current"):
                binding.prepare_binding(HEAD, self.claim, family=spec.name)

    def test_fallback_disclosure_rejects_substituted_main_provenance_and_fixture_inventory(self):
        spec = binding.family_spec("fallback-disclosure")
        commands = [
            ("rev-parse", "refs/remotes/origin/main"),
            ("rev-parse", spec.producer + ":" + spec.production),
            ("rev-parse", HEAD + ":" + spec.fixture),
            *(("rev-parse", revision + ":" + path)
              for path, producer, _ in spec.source_pins for revision in (HEAD, producer)),
        ]
        for command in commands:
            def changed(*args):
                return "0" * 40 if args == command else self.fallback_git(*args)
            with self.subTest(command=command), self.assertRaises(binding.PortError):
                self.prepare_fallback(changed)
        for methods in ((), spec.fixture_methods[:-1], spec.fixture_methods * 2,
                        (*spec.fixture_methods, "extraMethod"), spec.methods, tuple(reversed(spec.fixture_methods))):
            def changed(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in methods)
                return self.fallback_git(*args)
            with self.subTest(methods=methods), self.assertRaisesRegex(binding.PortError, "eleven-method"):
                self.prepare_fallback(changed)
        for selected in ((), spec.fixture_methods, (*spec.methods, spec.fixture_methods[0]), ("wrongMethod",),
                         spec.methods * 2):
            with patch.object(binding, "FAMILIES", {
                **binding.FAMILIES, spec.name: replace(spec, methods=selected),
            }), self.subTest(selected=selected), self.assertRaisesRegex(binding.PortError, "eleven-method"):
                self.prepare_fallback()

    def test_fallback_disclosure_exact_main_publication_and_current_binding_are_rechecked(self):
        bound = source("fallback-disclosure")
        request = binding.request_for(bound, 31)
        policy = {"paused": False}
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "trusted_execution_policy", return_value=policy), \
                patch.object(binding, "git", return_value=HEAD) as git:
            self.assertEqual(policy, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family=bound.family)
        git.assert_called_once_with("rev-parse", "refs/remotes/origin/main")
        for failure in ("stale remote main", "Candidate/worktree policy or manifest differs"):
            with patch.object(binding, "current_binding", return_value=bound), \
                    patch.object(binding, "trusted_execution_policy", side_effect=binding.PortError(failure)), \
                    self.subTest(failure=failure), self.assertRaisesRegex(binding.PortError, failure):
                binding.verify_current_request(request)
        with patch.object(binding, "current_binding", return_value=bound), \
                patch.object(binding, "trusted_execution_policy", return_value=policy), \
                patch.object(binding, "git", return_value="f" * 40), \
                self.assertRaisesRegex(binding.PortError, "exact current trusted main"):
            binding.verify_current_request(request)
        for field, value in (
            ("source_revision", "f" * 40), ("android_tree", "f" * 40),
            ("android_source_tree_sha256", "f" * 64), ("manifest_sha256", "f" * 64),
            ("policy_revision", "f" * 64), ("reservation_revision", 45),
            ("production_blob", "f" * 40), ("replacement_app_session", "retired"),
            ("replacement_cli_session", "retired"), ("canonical_session", "retired"),
        ):
            with patch.object(binding, "current_binding", return_value=replace(bound, **{field: value})), \
                    patch.object(binding, "git") as git, self.subTest(field=field), \
                    self.assertRaisesRegex(binding.PortError, "Source, policy, current owner"):
                binding.verify_current_request(request)
            git.assert_not_called()

    def test_fallback_disclosure_preparation_is_two_fixed_cells_with_no_execution_authority(self):
        bound = self.prepare_fallback()
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=bound), patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", bound.family]))
        result = json.loads(output.getvalue())
        self.assertEqual([31, 37], [request["api"] for request in result["requests"]])
        self.assertEqual([list(binding.family_spec(bound.family).identities)] * 2,
                         [request["identities"] for request in result["requests"]])
        self.assertEqual(2, len({request["operation_owner"] for request in result["requests"]}))
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])
        for api in (True, False, "31", 31.0, 30, 32, 36, 38):
            with self.subTest(api=api), self.assertRaises(binding.PortError):
                binding.request_for(bound, api)
        for option in ("--class", "--method", "--api", "--font", "--execute"):
            with patch("sys.stderr", io.StringIO()), patch.object(binding, "current_binding") as current, \
                    self.subTest(option=option), self.assertRaises(SystemExit):
                binding.main(["--head", HEAD, "--family", bound.family, option, "arbitrary"])
            current.assert_not_called()
        for identity in ("canonical_session", "replacement_app_session", "replacement_cli_session"):
            with self.subTest(identity=identity), self.assertRaises(binding.PortError):
                binding.request_for(replace(bound, **{identity: "retired"}), 31)

    def test_fallback_disclosure_accounts_exactly_one_positive_original_per_api_without_acceptance(self):
        for api in (31, 37):
            request = binding.request_for(source("fallback-disclosure"), api)
            result = binding.account_outcomes(
                transcript(family=request.binding.family), request, process_exit=0, forced_termination=False)
            self.assertEqual(1, result["passed"])
            self.assertEqual(api, result["api"])
            self.assertFalse(result["acceptance"])
            for code in (-1, -2, -3, -4):
                with self.subTest(api=api, code=code), self.assertRaises((binding.PortError, RuntimeError)):
                    binding.account_outcomes(
                        transcript(code, family=request.binding.family), request,
                        process_exit=0, forced_termination=False)
            for exit_code, forced in ((1, False), (0, True), (False, False), (0, None)):
                with self.subTest(api=api, exit=exit_code, forced=forced), self.assertRaises(binding.PortError):
                    binding.account_outcomes(
                        transcript(family=request.binding.family), request,
                        process_exit=exit_code, forced_termination=forced)
            for text in ("", transcript(family="language"), transcript(family="cached-chat"),
                         transcript(family=request.binding.family) * 2):
                with self.subTest(api=api, text=text), self.assertRaises(RuntimeError):
                    binding.account_outcomes(text, request, process_exit=0, forced_termination=False)

    def utf8_git_output(self, raw_for_command):
        check_output = subprocess.check_output

        def emit(command, **kwargs):
            self.assertEqual(["git", "-C", str(binding.ROOT)], command[:3])
            return check_output(
                [sys.executable, "-c", "import sys; sys.stdout.buffer.write(bytes.fromhex(sys.argv[1]))",
                 raw_for_command(tuple(command[3:])).hex()],
                **kwargs,
            )

        return emit

    def test_actual_git_helper_decodes_em_dash_and_cjk_strictly_despite_cp1252_default(self):
        text = "// \u2014 \u4e2d\u6587\n@Test fun originalFixture() {}\n"
        with patch.object(binding.subprocess, "_text_encoding", return_value="cp1252") as default, \
                patch.object(binding.subprocess, "check_output",
                             side_effect=self.utf8_git_output(lambda command: text.encode("utf-8"))) as output:
            self.assertEqual(text.strip(), binding.git("show", "original-fixture"))
        default.assert_not_called()
        output.assert_called_once_with(
            ["git", "-C", str(binding.ROOT), "show", "original-fixture"],
            text=True, encoding="utf-8", errors="strict",
        )

    def test_actual_trusted_policy_git_consumer_matches_literal_utf8_hash_under_cp1252_default(self):
        main = "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c"
        raw = subprocess.check_output(
            ["git", "-C", str(binding.ROOT), "show", main + ":docs/android/automation-policy.json"],
        )
        commands = {
            ("rev-parse", "refs/remotes/origin/main"): main.encode(),
            ("ls-remote", "origin", binding.MAIN_REF): (main + "\t" + binding.MAIN_REF).encode(),
            ("diff", "--exit-code", "--quiet", main, "--",
             "docs/android/automation-policy.json", "docs/android/port-manifest.json"): b"",
            ("show", main + ":docs/android/automation-policy.json"): raw,
        }
        manifest = Mock(sha256="977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8")
        manifest.data = {"reference": {"commit": "db14559b39d32322b06477c6ae676112f583db50"}}
        with patch.object(binding.subprocess, "_text_encoding", return_value="cp1252") as default, \
                patch.object(binding.subprocess, "check_output", side_effect=self.utf8_git_output(commands.__getitem__)):
            actual = binding.trusted_execution_policy()
        default.assert_not_called()
        self.assertEqual(json.loads(raw.decode("utf-8", errors="strict")), actual)
        self.assertEqual("e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3",
                         binding.policy_revision(manifest, actual))
        semantics = {key: value for key, value in actual.items() if key not in {
            "dispatch_mode", "paused", "activation_approved", "pending_capabilities",
        }}
        payload = {"manifest": manifest.sha256, "policy": semantics, "source": manifest.data["reference"]["commit"]}
        literal = hashlib.sha256(json.dumps(
            payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
        ).encode()).hexdigest()
        self.assertEqual(literal, binding.policy_revision(manifest, actual))
        self.assertEqual("a657babfe74c12d1f5631e9ee1c306a0084d56badd5c48fe1dba7b3443dcfc1a",
                         binding.policy_revision(manifest, json.loads(raw.decode("cp1252", errors="strict"))))

    def test_malformed_git_utf8_raises_in_helper_policy_consumer_and_cli_without_fallback(self):
        with patch.object(binding.subprocess, "_text_encoding", return_value="cp1252"), \
                patch.object(binding.subprocess, "check_output",
                             side_effect=self.utf8_git_output(lambda command: b"\xff")):
            with self.assertRaises(UnicodeDecodeError):
                binding.git("show", "malformed-fixture")
            with self.assertRaises(UnicodeDecodeError):
                binding.trusted_execution_policy()
            with patch.object(binding, "current_binding",
                              side_effect=lambda *args, **kwargs: binding.git("show", "malformed-fixture")), \
                    patch("sys.stderr", io.StringIO()) as error:
                self.assertEqual(2, binding.main(["--head", HEAD, "--family", "diagnostics"]))
            self.assertIn("BLOCKED:", error.getvalue())
            self.assertIn("utf-8", error.getvalue())


if __name__ == "__main__":
    unittest.main()
