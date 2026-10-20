"""AndroidOnly: WP-503 Pure language source/publication guards, not native acceptance."""

from dataclasses import replace
import io
import json
import subprocess
import unittest
from unittest.mock import Mock, patch

import language_binding as binding
from test_shared_device_runner import completed, record


HEAD = "9d2366ba5e6eeeb2e01fda9ad3990c25e6af9928"


def source():
    return binding.LanguageBinding(
        "cbattlegear/MeshCoreOne-Android", HEAD, "a" * 40, "b" * 64,
        "c" * 64, "d" * 64, binding.CANONICAL,
        binding.CURRENT_APP_SESSION, binding.CURRENT_CLI_SESSION, 14,
    )


def transcript(code=0):
    return completed("".join(
        record(identity, 1, 2) + record(identity, code, 2) for identity in binding.IDENTITIES
    ), 2)


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

    def prepare(self):
        manifest = Mock(sha256="c" * 64)
        manifest.wp.return_value = {"write_paths": ["android/accessibility-tests/"]}
        with patch.object(binding, "git", side_effect=self.fake_git), \
                patch.object(binding, "source_identity", return_value="b" * 64) as identity, \
                patch.object(binding, "load_manifest", return_value=manifest), \
                patch.object(binding, "load_json", return_value={"repository": source().repository}), \
                patch.object(binding, "policy_revision", return_value="d" * 64), \
                patch.object(binding, "CapabilityEngine"):
            actual = binding.prepare_binding(HEAD, self.claim)
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


if __name__ == "__main__":
    unittest.main()
