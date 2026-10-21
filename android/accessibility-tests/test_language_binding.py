"""AndroidOnly: WP-503 Pure fixed-family source/publication guards, not native acceptance."""

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


def source(family="language"):
    spec = binding.family_spec(family)
    return binding.LanguageBinding(
        "cbattlegear/MeshCoreOne-Android", HEAD, "a" * 40, "b" * 64,
        "c" * 64, "d" * 64, binding.CANONICAL,
        binding.CURRENT_APP_SESSION, binding.CURRENT_CLI_SESSION, 14,
        spec.producer, spec.production_blob, spec.fixture_blob, spec.name,
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
        if arguments[0] == "merge-base":
            return ""
        if arguments[0] == "show":
            return "\n".join("@Test fun " + method + "() {}" for method in spec.methods)
        if arguments[0] == "rev-parse":
            path = arguments[1].partition(":")[2]
            if path == "android":
                return "a" * 40
            pins = {spec.fixture: spec.fixture_blob, **{path: blob for path, _, blob in spec.source_pins}}
            return pins[path]
        self.fail(arguments)

    def prepare(self, family="language"):
        manifest = Mock(sha256="c" * 64)
        manifest.wp.return_value = {"write_paths": ["android/accessibility-tests/"]}
        with patch.object(binding, "git", side_effect=self.fake_git if family == "language"
                          else lambda *args: self.family_git(*args, family=family)), \
                patch.object(binding, "source_identity", return_value="b" * 64) as identity, \
                patch.object(binding, "load_manifest", return_value=manifest), \
                patch.object(binding, "load_json", return_value={"repository": source().repository}), \
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
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                self.assertEqual(blob, binding.git("rev-parse", "HEAD:" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, "HEAD"))
        for revision in ("HEAD", spec.producer):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
        with self.assertRaises(TypeError):
            binding.FAMILIES["other"] = spec

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

    def test_diagnostics_current_family_publication_policy_and_revision_guards_are_reused(self):
        bound = source("diagnostics")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="diagnostics")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="f" * 64),
                       replace(bound, source_revision="a" * 40), source()):
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
        first, second = (binding.request_for(bound, 37) for _ in range(2))
        self.assertEqual(binding.NODES.identities, first.identities)
        self.assertEqual(7, len(first.identities))
        self.assertNotEqual(first.operation_owner, second.operation_owner)
        self.assertTrue(first.operation_owner.startswith(binding.CANONICAL + ":nodes:"))
        for api in (30, 31, 33, 34, 36, 38, True, "37", 37.0):
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
        initial = "fc9188ab1dc1549be8788cbc3c180c895d5d5def"
        self.assertEqual("", binding.git("merge-base", "--is-ancestor", initial, spec.producer))
        for revision in (initial, spec.producer, "HEAD"):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
        for path, producer, blob in ((spec.fixture, spec.producer, spec.fixture_blob), *spec.source_pins):
            with self.subTest(path=path):
                self.assertEqual(blob, binding.git("rev-parse", producer + ":" + path))
                self.assertEqual(blob, binding.git("rev-parse", "HEAD:" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, "HEAD"))
        self.assertEqual("android/feature/nodes/src/main", spec.production)
        self.assertIn("AndroidManifest.xml", binding.git("ls-tree", "--name-only", "HEAD:" + spec.production))
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
                        patch.object(binding, "source_identity", return_value="b" * 64), \
                        patch.object(binding, "git", side_effect=changed):
                    with self.assertRaisesRegex(binding.PortError, "drifted"):
                        binding.prepare_binding(HEAD, self.claim, family="nodes")

    def test_nodes_missing_duplicate_extra_wrong_order_identity_or_ancestor_fails_closed(self):
        methods = binding.NODES.methods
        for actual in (methods[:-1], (*methods, "extra"), methods[::-1],
                       (*methods[:-1], methods[0]), (*methods[:-1], "wrongIdentity")):
            def changed(*args):
                if args[0] == "show":
                    return "\n".join("@Test fun " + method + "() {}" for method in actual)
                return self.family_git(*args, family="nodes")
            with self.subTest(methods=actual), patch.object(binding, "source_identity", return_value="b" * 64), \
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
                    patch.object(binding, "git", side_effect=missing):
                with self.assertRaises(subprocess.CalledProcessError):
                    binding.prepare_binding(HEAD, self.claim, family="nodes")

    def test_nodes_reuses_exact_source_scope_policy_family_publication_guards(self):
        bound = source("nodes")
        request = binding.request_for(bound, 37)
        with patch.object(binding, "current_binding", return_value=bound) as current, \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF), \
                patch.object(binding, "trusted_execution_policy", return_value={"paused": True}):
            self.assertEqual({"paused": True}, binding.verify_current_request(request))
        current.assert_called_once_with(HEAD, family="nodes")
        for actual in (replace(bound, reservation_revision=15), replace(bound, policy_revision="e" * 64),
                       replace(bound, source_revision="e" * 40), source("diagnostics"), source()):
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

    def test_nodes_cli_prepares_only_ordered_api37_seven_without_native_or_build_authority(self):
        output = io.StringIO()
        with patch.object(binding, "current_binding", return_value=source("nodes")) as current, \
                patch("sys.stdout", output):
            self.assertEqual(0, binding.main(["--head", HEAD, "--family", "nodes"]))
        current.assert_called_once_with(HEAD, family="nodes")
        result = json.loads(output.getvalue())
        self.assertEqual([37], [request["api"] for request in result["requests"]])
        self.assertEqual(list(binding.NODES.identities), result["requests"][0]["identities"])
        self.assertFalse(result["authority"])
        self.assertEqual(0, result["native_starts"])
        self.assertFalse(result["apks_built"])

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
                self.assertEqual(blob, binding.git("rev-parse", "HEAD:" + path))
                self.assertEqual("", binding.git("merge-base", "--is-ancestor", producer, "HEAD"))
        for revision in (spec.producer, "HEAD"):
            body = binding.git("show", revision + ":" + spec.fixture)
            self.assertEqual(spec.methods, tuple(re.findall(r"@Test fun (\w+)\(", body)))
            self.assertIn("newInstance(Locale.US)", body)
            self.assertNotIn("assertProducerCases", body)
        self.assertEqual("213866562c54c12d1f82f9c397b5cdcac7a4907c",
                         binding.git("rev-parse", "HEAD:" + spec.production + "/LocalizedCompare.kt"))
        supplemental = binding.git(
            "show", "HEAD:" + spec.fixture.replace("ParityTest.kt", "LocalePreservationTest.kt"),
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
