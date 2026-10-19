import copy
import unittest
from dataclasses import replace

from fixtures import BASE, HEAD, base_manifest, bundle, policy, test_manifest
from controller.errors import PortError
from controller.gates import integrity, merge_decision, policy_revision, validate_approval, validate_evidence, verify_completion


class GateTests(unittest.TestCase):
    def setUp(self):
        self.manifest = test_manifest("WP-000")
        self.policy = policy()
        self.bundle = bundle(self.manifest, self.policy)

    def verify(self, value=None):
        value = value or self.bundle
        return verify_completion(value.pr, value.evidence, value.review, value.approvals,
                                 value.binding, self.manifest, self.policy, value.catalog)

    def test_real_merged_pr_plus_complete_evidence_passes(self):
        proof = self.verify()
        self.assertEqual(proof["pr_number"], 123)
        self.assertEqual(proof["ci_run_ids"], [1, 2, 3])
        self.assertIsNone(proof["approval_id"])
        self.assertEqual(proof["review_authorization"]["kind"], "trusted-read-only-code-review")

    def test_operational_pause_does_not_rewrite_acceptance_but_gate_changes_do(self):
        revision = policy_revision(self.manifest, self.policy)
        operational = {**self.policy, "paused": True, "activation_approved": False, "dispatch_mode": "off"}
        self.assertEqual(policy_revision(self.manifest, operational), revision)
        semantic = {**self.policy, "repair_limit": 2}
        self.assertNotEqual(policy_revision(self.manifest, semantic), revision)

    def test_closed_issue_or_unmerged_pr_never_completes(self):
        value = replace(self.bundle, pr={**self.bundle.pr, "merged": False, "merge_commit_sha": None})
        with self.assertRaisesRegex(PortError, "merged implementation PR"):
            self.verify(value)
        with self.assertRaises(PortError):
            verify_completion({"state": "closed"}, self.bundle.evidence, self.bundle.review, [],
                              self.bundle.binding, self.manifest, self.policy)

    def test_missing_malformed_zero_failed_and_skipped_test_evidence_fail(self):
        scenarios = []
        missing = copy.deepcopy(self.bundle.evidence)
        missing.pop("runs")
        scenarios.append(missing)
        empty = copy.deepcopy(self.bundle.evidence)
        empty["runs"] = []
        scenarios.append(empty)
        for changes in (
            {"test_count": 0, "passed_count": 0},
            {"test_count": "2"},
            {"test_count": True},
            {"test_count": 2, "passed_count": 1, "failed_count": 1},
            {"test_count": 2, "passed_count": 1, "skipped_count": 1},
            {"test_count": 10},
            {"conclusion": "skipped"},
            {"status": "in_progress"},
        ):
            evidence = copy.deepcopy(self.bundle.evidence)
            evidence["runs"][0].update(changes)
            scenarios.append(evidence)
        for evidence in scenarios:
            with self.subTest(evidence=evidence["runs"] if "runs" in evidence else "missing"):
                with self.assertRaises(PortError):
                    self.verify(replace(self.bundle, evidence=evidence))

    def test_missing_duplicate_untrusted_and_stale_ci_checks_fail(self):
        for change in ("missing", "duplicate", "publisher", "workflow", "head", "base", "run-id"):
            evidence = copy.deepcopy(self.bundle.evidence)
            if change == "missing":
                evidence["runs"].pop()
            elif change == "duplicate":
                evidence["runs"].append(copy.deepcopy(evidence["runs"][0]))
            else:
                key, value = {
                    "publisher": ("app_id", 999), "workflow": ("workflow_id", 999),
                    "head": ("head_sha", BASE), "base": ("base_sha", HEAD),
                    "run-id": ("run_id", 0),
                }[change]
                evidence["runs"][0][key] = value
            with self.subTest(change=change), self.assertRaises(PortError):
                self.verify(replace(self.bundle, evidence=evidence))

    def test_stale_sha_manifest_policy_and_repository_review_rejected(self):
        for key, value in (
            ("head_sha", BASE), ("base_sha", HEAD), ("policy_revision", "0" * 64),
            ("repository", "different/repository"), ("work_packages", ["WP-001"]),
        ):
            review = copy.deepcopy(self.bundle.review)
            review[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(PortError, "stale|mismatch"):
                self.verify(replace(self.bundle, review=review))
        evidence = copy.deepcopy(self.bundle.evidence)
        evidence["manifest_sha256"] = "0" * 64
        with self.assertRaisesRegex(PortError, "manifest"):
            self.verify(replace(self.bundle, evidence=evidence))

    def test_malformed_missing_or_nonpassing_review_fails(self):
        for kind in ("missing", "schema", "changes", "blocked", "findings", "acceptance", "artifact", "human"):
            review = copy.deepcopy(self.bundle.review)
            if kind == "missing":
                review.pop("summary")
            elif kind == "schema":
                review["schema_version"] = True
            elif kind == "changes":
                review["verdict"] = "CHANGES_REQUESTED"
            elif kind == "blocked":
                review["verdict"] = "BLOCKED"
            elif kind == "findings":
                review["blockers"] = ["unresolved fixture blocker"]
            elif kind == "acceptance":
                review["acceptance"].pop()
            elif kind == "artifact":
                review["acceptance"][0]["evidence"] = ["invented.json"]
            else:
                review["human_gate_required"] = False
            with self.subTest(kind=kind), self.assertRaises(PortError):
                self.verify(replace(self.bundle, review=review))

    def test_human_acceptance_requires_actual_current_bound_maintainer_record(self):
        for kind in ("missing", "stale-head", "stale-base", "unregistered", "state", "stamp", "kind"):
            approvals = copy.deepcopy(self.bundle.approvals)
            pr = copy.deepcopy(self.bundle.pr)
            pr["labels"] = ["needs-human"]
            approvals[0].update(kind="human-acceptance", state="COMMENTED")
            if kind == "missing":
                approvals = []
            elif kind == "unregistered":
                approvals[0]["reviewer"] = "unregistered"
            else:
                key, value = {
                    "stale-head": ("head_sha", BASE), "stale-base": ("base_sha", HEAD),
                    "state": ("state", "CHANGES_REQUESTED"), "stamp": ("binding_key", "0" * 64),
                    "kind": ("kind", "automated-human-acceptance"),
                }[kind]
                approvals[0][key] = value
            with self.subTest(kind=kind), self.assertRaises(PortError):
                self.verify(replace(self.bundle, pr=pr, approvals=approvals))

    def test_sole_maintainer_code_review_needs_no_formal_github_approval(self):
        value = replace(self.bundle, pr={**self.bundle.pr, "author": "cbattlegear"}, approvals=[])
        proof = self.verify(value)
        self.assertIsNone(proof["approval_id"])
        self.assertFalse(proof["review_authorization"]["human_acceptance_required"])
        self.assertEqual(proof["review_authorization"]["binding_key"], value.binding.key)

    def test_author_can_truthfully_record_human_acceptance_without_self_approved_review(self):
        approvals = [{**self.bundle.approvals[0], "kind": "human-acceptance", "state": "COMMENTED"}]
        value = replace(self.bundle, pr={
            **self.bundle.pr, "author": "cbattlegear", "labels": ["needs-human"],
        }, approvals=approvals)
        self.assertEqual(self.verify(value)["approval_id"], 99)
        legacy = copy.deepcopy(self.bundle.approvals)
        self.assertEqual(validate_approval(legacy, value.binding, self.policy, "fixture-worker"), 99)
        self.assertNotIn("kind", legacy[0])

    def test_ordinary_and_protected_code_use_review_but_explicit_human_label_remains(self):
        manifest = test_manifest("WP-101")
        ordinary = bundle(manifest, self.policy, "WP-101")
        proof = verify_completion(ordinary.pr, ordinary.evidence, ordinary.review, [], ordinary.binding,
                                  manifest, self.policy, ordinary.catalog)
        self.assertIsNone(proof["approval_id"])
        for changed in ("docs/android/port-manifest.json", "android/gradle/libs.versions.toml",
                        "android/app/AppContainer.kt", "android/core/database/schemas/1.json"):
            wp = manifest.wp("WP-101")
            wp["write_paths"].append(changed)
            protected = bundle(manifest, self.policy, "WP-101", [changed])
            with self.subTest(changed=changed):
                proof = verify_completion(protected.pr, protected.evidence, protected.review, [], protected.binding,
                                          manifest, self.policy, protected.catalog)
                self.assertIsNone(proof["approval_id"])
                review = {**protected.review, "verdict": "CHANGES_REQUESTED"}
                with self.assertRaises(PortError):
                    verify_completion(protected.pr, protected.evidence, review, [], protected.binding,
                                      manifest, self.policy, protected.catalog)
        ordinary.pr["labels"] = ["needs-human"]
        ordinary.review["human_gate_required"] = True
        with self.assertRaises(PortError):
            verify_completion(ordinary.pr, ordinary.evidence, ordinary.review, [], ordinary.binding,
                              manifest, self.policy, ordinary.catalog)

    def test_hardware_legal_and_release_work_packages_still_require_human_acceptance(self):
        for wp_id in ("WP-001", "WP-505", "WP-506"):
            manifest = test_manifest(wp_id)
            value = bundle(manifest, self.policy, wp_id, [manifest.wp(wp_id)["write_paths"][-1] + "fixture.json"])
            with self.subTest(wp=wp_id), self.assertRaisesRegex(PortError, "human acceptance"):
                verify_completion(value.pr, value.evidence, value.review, [], value.binding,
                                  manifest, self.policy, value.catalog)

    def test_protected_signing_and_legal_paths_keep_human_acceptance(self):
        from controller.gates import human_acceptance_required

        wp = self.manifest.wp("WP-000")
        for path in ("android/release/release.json", "android/app/signing.properties",
                     "tools/android-port/license-policy.json"):
            with self.subTest(path=path):
                self.assertTrue(human_acceptance_required(wp, self.policy, [path]))
        self.assertFalse(human_acceptance_required(wp, self.policy, ["android/feature/chats/license-label.txt"]))

    def test_missing_unknown_or_malformed_review_policy_is_not_an_approval_bypass(self):
        for kind in ("missing", "unknown", "malformed"):
            rules = copy.deepcopy(self.policy)
            if kind == "missing":
                rules.pop("review_policy")
            elif kind == "unknown":
                rules["review_policy"]["code_review_authority"] = "candidate-pass"
            else:
                rules["review_policy"]["human_acceptance_work_packages"] = "WP-505"
            value = bundle(self.manifest, rules)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                verify_completion(value.pr, value.evidence, value.review, [], value.binding,
                                  self.manifest, rules, value.catalog)

    def test_original_case_catalog_missing_zero_unknown_family_or_results_fail(self):
        manifest = test_manifest("WP-101")
        value = bundle(manifest, self.policy, "WP-101")
        self.assertTrue(value.evidence["source_tests"])
        scenarios = []
        scenarios.append(replace(value, catalog=None))
        catalog = copy.deepcopy(value.catalog)
        catalog["entries"][0]["cases"] = []
        catalog["entries"][0]["has_assertions"] = True
        scenarios.append(replace(value, catalog=catalog))
        for kind in ("missing", "family", "blob", "duplicate", "unknown"):
            evidence = copy.deepcopy(value.evidence)
            if kind == "missing":
                evidence["source_tests"].pop()
            elif kind == "family":
                evidence["source_tests"][0]["cases"][0]["parameter_family"] = "wrong-family"
            elif kind == "blob":
                evidence["source_tests"][0]["blob_sha"] = "0" * 40
            elif kind == "duplicate":
                evidence["source_tests"].append(copy.deepcopy(evidence["source_tests"][0]))
            else:
                evidence["source_tests"][0]["path"] = "unknown.swift"
            scenarios.append(replace(value, evidence=evidence))
        for scenario in scenarios:
            with self.subTest(scenario=scenario.catalog is None), self.assertRaises(PortError):
                verify_completion(scenario.pr, scenario.evidence, scenario.review, scenario.approvals,
                                  scenario.binding, manifest, self.policy, scenario.catalog)

    def test_unconfigured_feature_commands_are_not_success(self):
        original = base_manifest()
        value = bundle(original, self.policy, "WP-101")
        with self.assertRaisesRegex(PortError, "commands"):
            verify_completion(value.pr, value.evidence, value.review, value.approvals,
                              value.binding, original, self.policy, value.catalog)

    def test_integrity_blocks_readonly_removal_weakening_and_identifies_protected_paths(self):
        data = base_manifest().data
        for kind in ("readonly", "remove", "human", "acceptance", "dependency"):
            candidate = copy.deepcopy(data)
            changed = []
            if kind == "readonly":
                changed = ["MC1/MC1App.swift"]
            elif kind == "remove":
                candidate["work_packages"].pop()
            elif kind == "human":
                candidate["work_packages"][0]["human_gate"] = False
            elif kind == "acceptance":
                candidate["work_packages"][0]["acceptance"].pop()
            else:
                candidate["work_packages"][1]["depends_on"] = []
            with self.subTest(kind=kind), self.assertRaises(PortError):
                integrity(data, candidate, self.policy, changed)
        self.assertTrue(integrity(data, data, self.policy, ["tools/android-port/controller/engine.py"])["human_gate_required"])
        self.assertFalse(integrity(data, data, self.policy, ["android/feature/chats/Chat.kt"])["human_gate_required"])

    def test_merge_decision_rejects_pause_off_base_change_or_stale_head(self):
        pending = replace(self.bundle, pr={**self.bundle.pr, "merged": False, "merge_commit_sha": None})
        for kind in ("pause", "off", "activation", "base", "head"):
            rules = copy.deepcopy(self.policy)
            current = BASE
            pr = copy.deepcopy(pending.pr)
            if kind == "pause":
                rules["paused"] = True
            elif kind == "off":
                rules["dispatch_mode"] = "off"
            elif kind == "activation":
                rules["activation_approved"] = False
            elif kind == "base":
                current = HEAD
            else:
                pr["head_sha"] = BASE
            with self.subTest(kind=kind), self.assertRaises(PortError):
                merge_decision(pr, pending.evidence, pending.review, pending.approvals, pending.binding,
                               self.manifest, rules, current)
        decision = merge_decision(pending.pr, pending.evidence, pending.review, pending.approvals,
                                  pending.binding, self.manifest, self.policy, BASE)
        self.assertEqual(decision["action"], "merge")
        self.assertIsNone(pending.pr["merge_commit_sha"])
