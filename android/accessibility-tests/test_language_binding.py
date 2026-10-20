"""AndroidOnly: WP-503 Pure language binding/admission guards, not a live Android host."""

from dataclasses import replace
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

    def test_no_provider_static_diagnostic_or_session_dispatch_is_not_device_admission(self):
        class SessionHost:
            create_session = Mock()
            capabilities = Mock(return_value={"authenticated": True})

        host = SessionHost()
        for provider in (None, {"state": "PASS", "authoritative": True}, host):
            with self.subTest(provider=provider), self.assertRaisesRegex(binding.PortError, "admit_language_device"):
                binding.admission_scope(provider, binding.request_for(source(), 31))
        host.create_session.assert_not_called()
        host.capabilities.assert_not_called()

    def test_provider_must_supply_guarded_scope_not_boolean_or_json_authority(self):
        for result in (True, {"admitted": True}, None):
            provider = Mock(admit_language_device=Mock(return_value=result))
            request = binding.request_for(source(), 37)
            with self.subTest(result=result), patch.object(binding, "current_binding", return_value=source()), \
                    patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF):
                with self.assertRaisesRegex(binding.PortError, "guarded preservation/lane"):
                    binding.admission_scope(provider, request)
            provider.admit_language_device.assert_called_once_with(request)

    def test_injected_scope_is_returned_without_entering_or_starting_a_device(self):
        class FixtureScope:
            entries = 0

            def __enter__(self):
                self.entries += 1

            def __exit__(self, *_args):
                return False

        scope = FixtureScope()
        provider = Mock(admit_language_device=Mock(return_value=scope))
        request = binding.request_for(source(), 37)
        with patch.object(binding, "current_binding", return_value=source()), \
                patch.object(binding, "git", return_value=HEAD + "\t" + binding.PUBLIC_REF):
            self.assertIs(scope, binding.admission_scope(provider, request))
        self.assertEqual(0, scope.entries)
        provider.admit_language_device.assert_called_once_with(request)

    def test_stale_source_claim_policy_or_unpublished_input_never_calls_provider(self):
        request = binding.request_for(source(), 31)
        for actual in (
            replace(source(), reservation_revision=15),
            replace(source(), policy_revision="e" * 64),
            replace(source(), source_revision="f" * 40),
        ):
            provider = Mock()
            with self.subTest(actual=actual), patch.object(binding, "current_binding", return_value=actual), \
                    patch.object(binding, "git") as git:
                with self.assertRaisesRegex(binding.PortError, "changed before"):
                    binding.admission_scope(provider, request)
                git.assert_not_called()
                provider.admit_language_device.assert_not_called()
        for published in ("", "f" * 40 + "\t" + binding.PUBLIC_REF,
                          HEAD + "\trefs/heads/other"):
            provider = Mock()
            with self.subTest(published=published), patch.object(binding, "current_binding", return_value=source()), \
                    patch.object(binding, "git", return_value=published):
                with self.assertRaisesRegex(binding.PortError, "exact currently published"):
                    binding.admission_scope(provider, request)
                provider.admit_language_device.assert_not_called()

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
