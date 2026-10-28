"""AndroidOnly: WP-003 Trusted staging, output caps and real-fact negative fixtures."""

import copy
import base64
import hashlib
import json
import unittest
import zlib
from dataclasses import asdict
from unittest.mock import patch

from fixtures import BASE, HEAD, MERGE, REPO, base_manifest, bundle, policy, test_manifest
from controller.errors import PortError
from controller.gate_runtime import capabilities
from controller.gates import Binding
from controller.historical import import_historical_receipt, migration_proposal
from controller.model import REFERENCE_SHA, git, tree
from controller.publication import (
    CHECK_OUTPUT_LIMIT, GATE_MARKER, STRUCTURED_INPUT_LIMIT, bounded_output, decode_bundle,
    decode_structured_body, encode_bundle, encode_structured_body, prepare_output, require_publisher,
)
from controller.schema import digest
from controller.staging import require_reviewer, stage_review


class PublisherTests(unittest.TestCase):
    def test_compressed_structured_receipt_preserves_real_large_original_families(self):
        manifest = base_manifest()
        catalog = json.loads((REPO / "docs" / "android" / "test-cases.json").read_text(encoding="utf-8"))
        registered = {entry["path"]: entry for entry in catalog["entries"]}
        originals = [registered[entry["path"]] for entry in manifest.inputs("WP-307", False)
                     if entry["kind"] in ("test", "support") and entry["exclusion"] is None
                     and entry["path"] in registered]
        scope_cases = [{"path": entry["path"], **case} for entry in originals for case in entry["cases"]]
        results = [{"path": entry["path"], "blob_sha": entry["blob_sha"],
                    "cases": [{**case, "result": "PASS",
                               "evidence": ["https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/123/attempts/1"]}
                              for case in entry["cases"]]} for entry in originals]
        payload = {"coverage": [{"cases": scope_cases}], "source_tests": results}
        self.assertEqual(len(scope_cases), 334)
        self.assertGreater(len(json.dumps(payload).encode("utf-8")), CHECK_OUTPUT_LIMIT)
        encoded = encode_structured_body(payload, "plain\n", "compressed\n")
        self.assertTrue(encoded.startswith("compressed\n"))
        self.assertLessEqual(len(encoded.encode("utf-8")), CHECK_OUTPUT_LIMIT)
        self.assertEqual(decode_structured_body(encoded, "plain\n", "compressed\n"), payload)
        small = encode_structured_body({"ok": True}, "plain\n", "compressed\n")
        self.assertTrue(small.startswith("plain\n"))
        self.assertEqual(decode_structured_body(small, "plain\n", "compressed\n"), {"ok": True})

    def test_compressed_structured_receipts_fail_closed_for_invalid_or_oversized_input(self):
        pack = lambda raw: "compressed\n" + base64.b64encode(zlib.compress(raw)).decode("ascii")
        for body in (
            "wrong\n{}",
            "compressed\nnot-base64!",
            "compressed\n" + base64.b64encode(zlib.compress(b"{}")[:-1]).decode("ascii"),
            "compressed\n" + base64.b64encode(zlib.compress(b"{}") + b"junk").decode("ascii"),
            pack(b"\xff"),
            pack(b'{"duplicate":1,"duplicate":2}'),
            pack(b"x" * (STRUCTURED_INPUT_LIMIT + 1)),
        ):
            with self.subTest(body=body[:40]), self.assertRaises(PortError):
                decode_structured_body(body, "plain\n", "compressed\n")
        noise = "".join(hashlib.sha256(str(i).encode()).hexdigest() for i in range(3000))
        with self.assertRaises(PortError):
            encode_structured_body({"noise": noise}, "plain\n", "compressed\n")
        with self.assertRaises(PortError):
            encode_structured_body({"value": "x" * STRUCTURED_INPUT_LIMIT}, "plain\n", "compressed\n")

    def payload(self):
        value = bundle(test_manifest("WP-000"), policy())
        return {
            "schema_version": 1, "binding": asdict(value.binding), "evidence": value.evidence,
            "review": value.review, "approvals": value.approvals, "catalog": value.catalog,
        }

    def test_complete_bundle_round_trip_preserves_full_catalog_and_binding(self):
        payload = self.payload()
        payload["catalog"] = {"entries": [{"id": "fixture-case", "parameter_family": "all-fixture-parameters"}]}
        self.assertEqual(decode_bundle(encode_bundle(payload)), payload)
        self.assertEqual(prepare_output(payload)["summary"], encode_bundle(payload))

    def test_summary_and_text_exact_boundary_and_one_character_overflow(self):
        self.assertEqual(len(bounded_output("x" * CHECK_OUTPUT_LIMIT)), CHECK_OUTPUT_LIMIT)
        with self.assertRaises(PortError):
            bounded_output("x" * (CHECK_OUTPUT_LIMIT + 1))
        with self.assertRaises(PortError):
            prepare_output(self.payload(), text="x" * (CHECK_OUTPUT_LIMIT + 1))
        payload = self.payload()
        base_length = len(encode_bundle(payload))
        payload["review"]["summary"] += "x" * (CHECK_OUTPUT_LIMIT - base_length)
        self.assertEqual(len(encode_bundle(payload)), CHECK_OUTPUT_LIMIT)
        payload["review"]["summary"] += "x"
        with self.assertRaises(PortError):
            encode_bundle(payload)

    def test_unicode_byte_bound_and_realistic_full_catalog_overflow_fail_before_publication(self):
        with self.assertRaises(PortError):
            bounded_output("\u4e00" * 22000)
        payload = self.payload()
        payload["catalog"] = {"entries": [{"id": f"fixture-case-{index}", "parameter_family": "complete-original-family"}
                                           for index in range(2000)]}
        original = copy.deepcopy(payload)
        with self.assertRaises(PortError):
            encode_bundle(payload)
        self.assertEqual(payload, original)

    def test_model_pass_details_url_partial_bundle_duplicate_json_and_unknown_schema_fail(self):
        for value in (
            '{"verdict":"PASS"}',
            GATE_MARKER + '{"schema_version":1,"schema_version":1}',
            GATE_MARKER + json.dumps({**self.payload(), "schema_version": 2}),
            GATE_MARKER + json.dumps({**self.payload(), "details_url": "https://fixture.invalid/catalog"}),
        ):
            with self.subTest(value=value[:80]), self.assertRaises(PortError):
                decode_bundle(value)

    def test_missing_or_wrong_publisher_identity_and_unproven_rules_are_blocked(self):
        rules = policy()
        for app_id, trusted in ((None, True), (999, True), (10, False)):
            with self.subTest(app_id=app_id, trusted=trusted), self.assertRaises(PortError):
                require_publisher(rules, app_id, trusted_default_branch=trusted)
        rules["branch_rules_proven"] = False
        with self.assertRaises(PortError):
            require_publisher(rules, 10, trusted_default_branch=True)
        rules["trusted_gate_publisher_app_id"] = None
        with self.assertRaises(PortError):
            require_publisher(rules, 10, trusted_default_branch=True)


class StagingTests(unittest.TestCase):
    def staged_fixture(self, wp_id="WP-003", *, replace_reviewer=False, additional_original_paths=None,
                       reference_override=None, additional_candidate_sources=None):
        from controller import staging

        head = git(REPO, "rev-parse", "HEAD").decode().strip()
        original = tree(REPO, head)
        candidate = dict(original)
        if replace_reviewer:
            candidate[".github/agents/parity-reviewer.agent.md"] = original[".github/agents/android-build-engineer.agent.md"]
        else:
            candidate["tools/android-port/controller/fixture-data.py"] = original["tools/android-port/controller/gates.py"]
        reference = tree(REPO, REFERENCE_SHA)
        if reference_override:
            reference.update(reference_override)
        reader = staging.bounded_git

        def read(repo, maximum, *arguments):
            return b"fixture-only complete diff; not a live PR\n" if arguments[0] == "diff" else reader(repo, maximum, *arguments)

        with patch("controller.staging.tree", side_effect=[original, candidate, reference]), patch("controller.staging.bounded_git", side_effect=read):
            return stage_review(REPO, wp_id, head, HEAD, additional_original_paths=additional_original_paths,
                                additional_candidate_sources=additional_candidate_sources)

    def test_fixture_candidate_staging_uses_actual_trusted_git_profile_skills_and_source(self):
        result = self.staged_fixture()
        self.assertFalse(result["authoritative"])
        self.assertEqual(result["binding"]["source_sha"], REFERENCE_SHA)
        self.assertEqual(result["reviewer"]["tools"], ["read", "search"])
        self.assertTrue(result["candidate_content_is_untrusted_data"])
        self.assertEqual(len([f for f in result["files"] if f["side"] == "trusted-base"]), 7)

    def test_scoped_cross_owner_original_is_pinned_and_in_the_staging_digest(self):
        source = "MC1Services/Tests/MC1ServicesTests/SyncCoordinatorMessageHandlerTests.swift"
        baseline = self.staged_fixture()
        staged = self.staged_fixture(additional_original_paths={source})
        records = [item for item in staged["files"]
                   if item["side"] == "pinned-reference" and item["path"] == source]
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["blob_sha"], "879f8b0a4337fdcec2a56c93cc4e77457aa35e9e")
        self.assertIn("SyncCoordinatorMessageHandlerTests", records[0]["content"])
        self.assertNotEqual(digest(staged), digest(baseline))
        for paths, override in (
            ({"unknown/Test.swift"}, None),
            ({source}, {source: "0" * 40}),
            (["not-a-set"], None),
        ):
            with self.subTest(paths=paths), self.assertRaises(PortError):
                self.staged_fixture(additional_original_paths=paths, reference_override=override)

    def test_scoped_unchanged_candidate_test_is_in_the_staging_digest(self):
        source = "android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/sync/SyncCoordinatorMessageHandlerTests.kt"
        head = git(REPO, "rev-parse", "HEAD").decode().strip()
        blob = tree(REPO, head)[source]
        baseline = self.staged_fixture()
        staged = self.staged_fixture(additional_candidate_sources={source: blob})
        records = [item for item in staged["files"]
                   if item["side"] == "candidate-data" and item["path"] == source]
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["blob_sha"], blob)
        self.assertIn("SyncCoordinatorMessageHandlerTests", records[0]["content"])
        self.assertNotEqual(digest(staged), digest(baseline))
        for sources in ({source: "0" * 40}, {"unknown/Test.kt": blob}, {source: "bad"}, [source]):
            with self.subTest(sources=sources), self.assertRaises(PortError):
                self.staged_fixture(additional_candidate_sources=sources)

    def test_candidate_reviewer_replacement_cannot_grant_execute_or_publish_authority(self):
        result = self.staged_fixture("WP-000", replace_reviewer=True)
        self.assertEqual(result["reviewer"]["tools"], ["read", "search"])
        candidate = next(item for item in result["files"] if item["side"] == "candidate-data")
        self.assertIn('"execute"', candidate["content"])
        self.assertTrue(result["candidate_content_is_untrusted_data"])

    def test_empty_noop_candidate_cannot_be_misrepresented_as_accepted_work(self):
        head = git(REPO, "rev-parse", "HEAD").decode().strip()
        with self.assertRaisesRegex(PortError, "nonempty"):
            stage_review(REPO, "WP-003", head, head)

    def test_mutable_refs_missing_profiles_and_oversized_inputs_fail_closed(self):
        head = git(REPO, "rev-parse", "HEAD").decode().strip()
        for base, candidate in (("main", head), (head, "HEAD")):
            with self.subTest(base=base), self.assertRaises(PortError):
                stage_review(REPO, "WP-003", base, candidate)
        original = tree(REPO, head)
        missing = dict(original)
        missing.pop(".github/agents/parity-reviewer.agent.md")
        with patch("controller.staging.tree", side_effect=[missing, original]), self.assertRaises(PortError):
            stage_review(REPO, "WP-003", head, head)
        with patch("controller.staging.MAX_STAGE_BYTES", 1), self.assertRaisesRegex(PortError, "never truncate"):
            stage_review(REPO, "WP-003", head, head)

    def test_authenticated_author_coordinator_is_ready_but_name_alone_is_not_authority(self):
        rules = policy()
        for arguments in ({}, {"author": "cbattlegear", "independent_human_reviewer": "cbattlegear"},
                          {"author": "cbattlegear", "independent_human_reviewer": "unregistered"}):
            with self.subTest(arguments=arguments), self.assertRaises(PortError):
                require_reviewer(rules, **arguments)
        self.assertTrue(require_reviewer(rules, cli_authenticated=True))
        self.assertTrue(require_reviewer(rules, author="cbattlegear", coordinator="cbattlegear",
                                         coordinator_authenticated=True))
        self.assertTrue(require_reviewer(rules, author="cbattlegear", independent_human_reviewer="cbattlegear",
                                         coordinator_authenticated=True))
        with self.assertRaises(PortError):
            require_reviewer(rules, coordinator="unregistered", coordinator_authenticated=True)
        with self.assertRaises(PortError):
            require_reviewer(rules, coordinator="cbattlegear", coordinator_authenticated="true")

    def test_committed_runtime_switches_credentials_and_foundation_import_remain_blocked(self):
        from controller.schema import load_json

        rules = load_json(REPO / "docs" / "android" / "automation-policy.json")
        self.assertEqual(rules["dispatch_mode"], "off")
        self.assertFalse(rules["paused"])
        self.assertFalse(rules["activation_approved"])
        self.assertIsNone(rules["trusted_gate_publisher_app_id"])
        self.assertFalse(rules["branch_rules_proven"])
        result = capabilities(rules)
        self.assertEqual(result["state"], "BLOCKED")
        self.assertFalse(result["authoritative"])
        self.assertFalse(any("Sole maintainer" in reason for reason in result["blockers"]))
        self.assertTrue(any("publisher" in reason for reason in result["blockers"]))
        self.assertTrue(any("activation" in reason for reason in result["blockers"]))


class HistoricalTests(unittest.TestCase):
    def fixture(self):
        value = bundle(test_manifest("WP-000"), policy())
        original = value.binding
        current = Binding(original.repository, original.work_package, MERGE, HEAD, original.source_sha,
                          "4" * 64, "5" * 64)
        checks = [{"id": 81, "name": "Controller bootstrap (windows-latest)", "app_id": 10,
                   "head_sha": HEAD, "conclusion": "success"}]
        run = {"run_id": 71, "run_attempt": 1, "workflow_id": 21, "head_sha": HEAD,
               "event": "pull_request", "checks": checks}
        request = {
            "schema_version": 1, "kind": "supervised-foundation-revalidation",
            "original_binding": asdict(original), "current_binding": asdict(current),
            "pr_number": 123, "merge_commit_sha": MERGE, "original_runs": [run],
        }
        actual_pr = {**value.pr}
        actual_runs = [{"id": 71, "repository": original.repository, "workflow_id": 21, "run_attempt": 1,
                        "head_sha": HEAD, "event": "pull_request", "status": "completed",
                        "conclusion": "success", "checks": copy.deepcopy(checks)}]
        return request, actual_pr, actual_runs, current

    def test_real_shaped_original_bootstrap_evidence_is_a_blocked_proposal_not_modern_ci_or_import(self):
        request, pr, runs, current = self.fixture()
        proposal = migration_proposal(request, pr, runs, current)
        self.assertEqual(proposal["state"], "BLOCKED")
        self.assertFalse(proposal["imported"])
        self.assertFalse(proposal["authoritative"])
        self.assertNotIn("android-ci", json.dumps(request))
        with self.assertRaisesRegex(PortError, "not activated"):
            import_historical_receipt(proposal)

    def test_open_draft_stale_original_sha_run_and_fabricated_later_check_fail(self):
        for kind in ("draft", "base", "head", "source", "run", "modern-check", "approval"):
            request, pr, runs, current = self.fixture()
            if kind == "draft":
                pr["merged"] = False
            elif kind in ("base", "head"):
                pr[kind + "_sha"] = "0" * 40
            elif kind == "source":
                request["original_binding"]["source_sha"] = "0" * 40
            elif kind == "run":
                runs[0]["conclusion"] = "skipped"
            elif kind == "modern-check":
                request["original_runs"][0]["checks"][0]["name"] = "android-ci"
            else:
                request["human_approved"] = True
            with self.subTest(kind=kind), self.assertRaises(PortError):
                migration_proposal(request, pr, runs, current)

    def test_missing_zero_execution_future_feature_unknown_version_and_stale_current_policy_fail(self):
        for kind in ("missing", "zero", "future", "version", "current"):
            request, pr, runs, current = self.fixture()
            if kind == "missing":
                request["original_runs"] = []
            elif kind == "zero":
                request["original_runs"][0]["run_id"] = 0
            elif kind == "future":
                request["original_binding"]["work_package"] = "WP-101"
            elif kind == "version":
                request["schema_version"] = 2
            else:
                request["current_binding"]["policy_revision"] = "0" * 64
            with self.subTest(kind=kind), self.assertRaises(PortError):
                migration_proposal(request, pr, runs, current)


class ServerProvenanceTests(unittest.TestCase):
    def test_real_check_suite_external_publisher_run_repository_attempt_and_base_are_reconciled(self):
        import test_backends

        for kind in ("run-id", "suite", "external-id", "repository", "attempt", "head", "policy", "summary-overflow"):
            authority, api, prefix, value = test_backends.AuthorityTests().fixture()
            checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
            if kind == "run-id":
                api.routes[("GET", prefix + "/actions/runs/1")]["id"] = 999
            elif kind == "suite":
                checks[0]["check_suite"]["id"] = 999
            elif kind == "external-id":
                checks[-1]["external_id"] = "model-written-PASS"
            elif kind == "repository":
                api.routes[("GET", prefix + "/actions/runs/1")]["repository"]["full_name"] = "elsewhere/repo"
            elif kind == "attempt":
                api.routes[("GET", prefix + "/actions/runs/1")]["run_attempt"] = 2
            elif kind == "head":
                api.routes[("GET", prefix + "/actions/runs/1")]["head_sha"] = BASE
            elif kind == "policy":
                payload = decode_bundle(checks[-1]["output"]["summary"])
                payload["binding"]["policy_revision"] = "0" * 64
                checks[-1]["output"]["summary"] = encode_bundle(payload)
            else:
                checks[-1]["output"]["summary"] += "x" * CHECK_OUTPUT_LIMIT
            from controller.ledger import Identity

            with self.subTest(kind=kind), self.assertRaises(PortError):
                authority.bundle("WP-000", Identity(pr_number=123))

    def test_cloud_repair_receipt_cannot_echo_an_unrelated_pr_or_feedback(self):
        import test_backends
        from controller.ledger import Identity

        cloud, api, prefix, _ = test_backends.cloud_fixture()
        for changes in ({"issue_url": "https://api.github.com/repos/elsewhere/repo/issues/123"}, {"body": "@copilot unrelated"}):
            api.routes[("POST", prefix + "/issues/123/comments")] = {
                "id": 88, "issue_url": "https://api.github.com" + prefix + "/issues/123",
                "body": "@copilot bounded", **changes,
            }
            with self.subTest(changes=changes), self.assertRaises(PortError):
                cloud.repair(Identity(task_id="task-1", pr_number=123), "bounded")

    def test_registered_native_stack_never_uses_legacy_merge_backend(self):
        from controller.backends import MergeBackend
        from fixtures import FakeApi

        api = FakeApi()
        with self.assertRaisesRegex(PortError, "asynchronous native stack API"):
            MergeBackend(api, policy()).merge_exact(123, HEAD, BASE, native_stack_id="fixture-stack")
        self.assertFalse(api.calls)
