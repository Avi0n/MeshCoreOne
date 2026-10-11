"""AndroidOnly: WP-501 Real gate validation is reachable; untrusted/stale/incomplete reviews cannot promote accounting."""

import copy
from dataclasses import replace
import importlib.util
from types import SimpleNamespace
import unittest

import acceptance
import audit
from controller.errors import PortError
from controller.ledger import Identity

SPEC = importlib.util.spec_from_file_location(
    "wp501_gate_fixtures", audit.REPO / "tools" / "android-port" / "tests" / "fixtures.py",
)
fixtures = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixtures)


class AcceptanceReaderTest(unittest.TestCase):
    def setUp(self):
        self.manifest = fixtures.test_manifest("WP-501")
        self.policy = fixtures.policy()
        self.bundle = fixtures.bundle(self.manifest, self.policy, "WP-501", merged=False)
        self.identity = Identity(pr_number=123)

    def verify(self, bundle=None, *, base=fixtures.BASE, head=fixtures.HEAD):
        value = self.bundle if bundle is None else bundle
        return acceptance.require_external_review(
            SimpleNamespace(bundle=lambda wp, identity: value), self.identity,
            self.manifest, self.policy, base_sha=base, head_sha=head,
        )

    def test_authenticated_complete_external_review_resolves_acceptance_without_merge(self):
        result = self.verify()
        self.assertEqual("PASS", result["acceptance"])
        self.assertEqual(fixtures.HEAD, result["binding"]["head_sha"])
        self.assertEqual([1, 2, 3], result["ci_run_ids"])
        self.assertFalse(self.bundle.pr["merged"])

    def test_candidate_dict_and_non_authoritative_bundle_cannot_approve(self):
        for value in ({"verdict": "PASS"}, replace(self.bundle, authoritative=False)):
            with self.subTest(value=type(value)), self.assertRaisesRegex(PortError, "authority"):
                self.verify(value)

    def test_stale_expected_base_and_head_are_rejected(self):
        for values in ({"base": "a" * 40}, {"head": "a" * 40}):
            with self.subTest(values=values), self.assertRaisesRegex(PortError, "base/head"):
                self.verify(**values)

    def test_another_pr_is_not_the_requested_review(self):
        with self.assertRaisesRegex(PortError, "WP/PR"):
            self.verify(replace(self.bundle, pr={**self.bundle.pr, "number": 456}))

    def test_missing_or_failed_acceptance_id_cannot_resolve(self):
        for change in ("missing", "failed"):
            review = copy.deepcopy(self.bundle.review)
            if change == "missing":
                review["acceptance"].pop()
            else:
                review["acceptance"][0]["result"] = "BLOCKED"
            with self.subTest(change=change), self.assertRaises(PortError):
                self.verify(replace(self.bundle, review=review))

    def test_current_sha_but_stale_policy_is_still_rejected(self):
        review = {**self.bundle.review, "policy_revision": "a" * 64}
        with self.assertRaisesRegex(PortError, "policy_revision"):
            self.verify(replace(self.bundle, review=review))

    def test_success_shaped_review_with_findings_is_rejected(self):
        review = {**self.bundle.review, "blockers": ["Original family is missing"]}
        with self.assertRaisesRegex(PortError, "blocking findings"):
            self.verify(replace(self.bundle, review=review))

    def test_skip_or_zero_execution_remains_a_failure(self):
        for changes in ({"test_count": 0, "passed_count": 0}, {"skipped_count": 1}):
            evidence = copy.deepcopy(self.bundle.evidence)
            evidence["runs"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(PortError):
                self.verify(replace(self.bundle, evidence=evidence))

    def test_unconfigured_publisher_cannot_be_replaced_by_local_json(self):
        with self.assertRaisesRegex(PortError, "publisher is unconfigured"):
            acceptance.github_review(165, fixtures.BASE, fixtures.HEAD, {})


if __name__ == "__main__":
    unittest.main()
