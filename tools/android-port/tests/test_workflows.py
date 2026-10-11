"""AndroidOnly: WP-003 Negative workflow/schema and protected-overlay assertions."""

import copy
import json
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from fixtures import REPO, base_manifest
from bootstrap import build_inventory
from controller.errors import PortError
from controller.schema import digest
from controller.verification_config import (
    AMENDMENTS, BOOTSTRAP_POLICY_AMENDMENT, POLICY_AMENDMENT_EVIDENCE,
    apply_overlay, check_configuration, project_content_scope,
)
from controller.workflows import parse_yaml, validate_candidate, validate_setup, validate_trusted, validate_workflows


class WorkflowTests(unittest.TestCase):
    def read(self, name):
        text = (REPO / ".github" / "workflows" / name).read_text(encoding="utf-8")
        return parse_yaml(text), text

    def test_actual_workflows_parse_and_have_the_real_trust_boundaries(self):
        self.assertEqual(validate_workflows(REPO)["result"], "valid")

    def test_consolidated_controller_installs_hash_pinned_yaml_runtime_before_tests(self):
        workflow, text = self.read("android-ci.yml")
        steps = workflow["jobs"]["controller"]["steps"]
        installer = next(index for index, step in enumerate(steps)
                         if "requirements-ci.txt" in step.get("run", ""))
        runner = next(index for index, step in enumerate(steps)
                      if "controller/test_runner.py" in step.get("run", ""))
        self.assertLess(installer, runner)
        self.assertIn("--require-hashes", steps[installer]["run"])
        self.assertIn("--only-binary=:all:", steps[installer]["run"])
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        self.assertEqual(steps[0]["with"]["persist-credentials"], "false")
        self.assertNotIn("secrets.", text)

    def test_protocol_workflow_executes_the_real_nonzero_jvm_suite_on_linux(self):
        from controller.ci import TASKS

        workflow, text = self.read("android-ci.yml")
        job = workflow["jobs"]["build"]
        self.assertEqual(TASKS["protocol"], [":core:protocol:test"])
        self.assertEqual(job["runs-on"], "ubuntu-24.04")
        self.assertEqual(job["needs"], ["scope", "controller"])
        self.assertIn("needs.scope.outputs.protocol", job["if"])
        self.assertNotIn("strategy", job)
        self.assertIn("merge_group", workflow["on"])
        self.assertNotIn("paths", workflow["on"]["pull_request"])
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        command = next(step for step in job["steps"]
                       if "RUN_PROTOCOL" in step.get("env", {}))
        self.assertNotIn("if", command)
        self.assertNotIn("continue-on-error", command)
        self.assertEqual(job["steps"][0]["with"]["persist-credentials"], "false")
        self.assertNotIn("secrets.", text)

    def test_consolidated_jobs_use_scope_outputs_and_default_fail_closed_dependencies(self):
        workflow, _ = self.read("android-ci.yml")
        jobs = workflow["jobs"]
        self.assertEqual(
            set(jobs["scope"]["outputs"]), {"controller", "scaffold", "protocol", "backup", "external-oracle"}
        )
        expected = {
            "external-oracle": ("external-oracle", ["scope", "controller"]),
            "backup-oracle": ("backup", ["scope", "build"]),
        }
        for job_name, (scope, needs) in expected.items():
            with self.subTest(job=job_name):
                self.assertEqual(jobs[job_name]["needs"], needs)
                self.assertEqual(
                    jobs[job_name]["if"],
                    ("${{ needs.scope.outputs['external-oracle'] == 'true' }}"
                     if scope == "external-oracle"
                     else "${{ needs.scope.outputs." + scope + " == 'true' }}"),
                )
                self.assertNotIn("continue-on-error", jobs[job_name])
                self.assertNotEqual(jobs[job_name].get("if"), "${{ always() }}")
        scope_run = jobs["scope"]["steps"][1]["run"]
        self.assertIn('github.event_name', scope_run)
        self.assertIn('github.event_path', scope_run)
        self.assertIn('$GITHUB_OUTPUT', scope_run)

    def test_duplicate_yaml_keys_and_invalid_yaml_are_rejected(self):
        for text in ("jobs: {}\njobs: {}\n", "jobs: ["):
            with self.subTest(text=text), self.assertRaises(PortError):
                parse_yaml(text)

    def test_required_ci_allows_scope_filters_but_preserves_merge_and_gate_contract(self):
        original, text = self.read("android-ci.yml")
        build_runs = [step["run"] for step in original["jobs"]["build"]["steps"] if "run" in step]
        gradle_runs = [run for run in build_runs if "ci.py run --stage" in run]
        self.assertEqual(len(gradle_runs), 1)
        self.assertIn("ci.py run --stage scaffold", gradle_runs[0])
        self.assertNotIn(":convention:test", text)
        scoped = copy.deepcopy(original)
        scoped["on"]["pull_request"]["paths"] = ["android/**", "tools/android-port/**"]
        validate_candidate(scoped, text)
        cached = copy.deepcopy(original)
        cached["jobs"]["build"]["steps"].append({
            "uses": "actions/cache@5a3ec84eff668545956fd18022155c47e93e2684",
            "with": {"path": "~/.gradle/caches", "key": "gradle-read-only-fixture"},
        })
        validate_candidate(cached, text)
        for kind in ("merge", "gate", "needs", "host", "strategy"):
            value = copy.deepcopy(original)
            if kind == "merge":
                value["on"].pop("merge_group")
            elif kind == "gate":
                value["jobs"]["android-ci"]["if"] = "success()"
            elif kind == "needs":
                value["jobs"]["android-ci"]["needs"] = []
            elif kind == "host":
                value["jobs"]["build"]["runs-on"] = "windows-2025"
            elif kind == "strategy":
                value["jobs"]["build"]["strategy"] = {"matrix": {"host": ["linux", "windows"]}}
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_candidate(value, text)

    def test_read_only_checkout_ephemeral_runners_pins_and_secret_absence(self):
        original, text = self.read("android-ci.yml")
        for kind in ("permissions", "credentials", "action", "secret", "runner", "ignored-failure"):
            value, changed = copy.deepcopy(original), text
            if kind == "permissions":
                value["permissions"]["checks"] = "write"
            elif kind == "credentials":
                value["jobs"]["build"]["steps"][0]["with"]["persist-credentials"] = "true"
            elif kind == "action":
                value["jobs"]["build"]["steps"][0]["uses"] = "actions/checkout@v4"
            elif kind == "runner":
                changed += "\n# self-hosted\n"
            elif kind == "ignored-failure":
                value["jobs"]["build"]["steps"][0]["continue-on-error"] = "true"
            else:
                changed += "\n# ${{ secrets.DISPATCH_TOKEN }}\n"
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_candidate(value, changed)

    def test_gradle_graph_has_no_duplicate_ci_evidence_retention_hooks(self):
        gradle_files = sorted((REPO / "android").rglob("build.gradle.kts"))
        text = "\n".join(path.read_text(encoding="utf-8") for path in gradle_files)
        self.assertNotRegex(text, r"(?i)\bretain\w*Evidence\b")
        self.assertNotRegex(text, r"(?i)\bverify\w*Evidence\b")
        self.assertNotRegex(text, r"(?i)\bcollect\w*Evidence\b")
        self.assertNotRegex(text, r"(?i)\b(?:evidence|stage)\w*(?:Report|Result|Retention)\b")
        self.assertNotIn("collect_evidence.py", text)
        self.assertNotIn("meshCliEvidence", text)
        self.assertNotIn("wp109-invocation", text)
        self.assertNotIn("raw-retention", text)
        controller = (REPO / "tools/android-port/controller/ci.py").read_text(encoding="utf-8")
        self.assertIn("validate_candidate_task_graph(task_graph, tasks)", controller)
        self.assertIn("--init-script", controller)

    def test_cloud_setup_supported_single_job_and_only_supported_properties(self):
        original, text = self.read("copilot-setup-steps.yml")
        for kind in ("job", "timeout", "host", "env", "defaults", "condition", "strategy"):
            value = copy.deepcopy(original)
            job = value["jobs"]["copilot-setup-steps"]
            if kind == "job":
                value["jobs"]["extra"] = copy.deepcopy(job)
            elif kind == "timeout":
                job["timeout-minutes"] = "60"
            elif kind == "host":
                job["runs-on"] = "macos-latest"
            else:
                job[{"condition": "if"}.get(kind, kind)] = {}
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_setup(value, text)

    def test_trusted_duties_never_run_candidate_workflows_or_publish_preview_pass(self):
        original, text = self.read("android-parity-review.yml")
        for kind in ("event", "name", "checkout", "ref", "schedule"):
            value = copy.deepcopy(original)
            job = value["jobs"]["staging"]
            if kind in ("event", "schedule"):
                value["on"]["workflow_run" if kind == "event" else "schedule"] = {}
            elif kind == "name":
                job["name"] = "parity-review"
            elif kind == "checkout":
                job["steps"][0]["with"]["ref"] = "${{ inputs.candidate_sha }}"
            else:
                job["if"] = "github.ref != github.event.repository.default_branch"
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_trusted(value, text)

    def test_verification_overlay_is_reproducible_and_only_changes_two_real_configs(self):
        original, _ = build_inventory(REPO)
        amended = apply_overlay(original)
        from controller.scope_amendment import apply_translation_scope, project_translation_scope
        self.assertEqual(amended, apply_translation_scope(project_content_scope(base_manifest().data)))
        for before, after in zip(original["work_packages"], amended["work_packages"], strict=True):
            if before["id"] in AMENDMENTS:
                self.assertEqual({k: v for k, v in before.items() if k != "verification"},
                                 {k: v for k, v in after.items() if k != "verification"})
            else:
                self.assertEqual(before, after)
        result = check_configuration(REPO)
        self.assertEqual(result["verification_amendments"], ["WP-002", "WP-003"])
        from controller.verification_config import WP_003_BOOTSTRAP_MANIFEST_SHA256, WP_003_MANIFEST_REVISION
        self.assertEqual(digest(project_translation_scope(original)), WP_003_BOOTSTRAP_MANIFEST_SHA256)
        self.assertEqual(result["bootstrap_manifest_sha256"], digest(original))
        self.assertEqual(digest(project_content_scope(base_manifest().data)),
                         WP_003_MANIFEST_REVISION)
        self.assertEqual(result["manifest_sha256"], base_manifest().sha256)
        self.assertEqual(result["policy_amendment"], "WP-000-capability-reservations-v1")

    def test_previous_or_incorrect_frozen_digest_cannot_admit_candidate(self):
        original, _ = build_inventory(REPO)
        for candidate in (
            BOOTSTRAP_POLICY_AMENDMENT["previous_generated_manifest_sha256"],
            "0" * 64,
        ):
            with self.subTest(candidate=candidate), patch(
                "controller.verification_config.WP_003_BOOTSTRAP_MANIFEST_SHA256", candidate
            ), self.assertRaisesRegex(PortError, "Frozen bootstrap generator changed"):
                apply_overlay(original)

    def test_changed_generator_digest_cannot_self_authorize_without_matching_evidence(self):
        original, exclusions = build_inventory(REPO)
        changed = copy.deepcopy(original)
        changed["reference"]["installed_plan_sha256"] = "0" * 64
        amended = copy.deepcopy(changed)
        for wp in amended["work_packages"]:
            if wp["id"] in AMENDMENTS:
                wp["verification"] = copy.deepcopy(AMENDMENTS[wp["id"]])
        candidate = {
            **BOOTSTRAP_POLICY_AMENDMENT,
            "generated_manifest_sha256": digest(changed),
            "final_manifest_sha256": digest(amended),
        }
        with patch("bootstrap.build_inventory", return_value=(changed, exclusions)), patch(
            "controller.verification_config.BOOTSTRAP_POLICY_AMENDMENT", candidate
        ), patch(
            "controller.verification_config.WP_003_BOOTSTRAP_MANIFEST_SHA256",
            candidate["generated_manifest_sha256"],
        ), patch(
            "controller.verification_config.WP_003_MANIFEST_REVISION",
            candidate["final_manifest_sha256"],
        ), self.assertRaisesRegex(PortError, "complete exact catalog preimage"):
            check_configuration(REPO)

    def test_verification_configuration_check_is_read_only(self):
        paths = (
            REPO / "docs" / "android" / "port-manifest.json",
            REPO / "docs" / "android" / "not-ported.json",
            REPO / POLICY_AMENDMENT_EVIDENCE,
        )
        before = {path: path.read_bytes() for path in paths}
        self.assertEqual(check_configuration(REPO)["result"], "valid")
        self.assertEqual({path: path.read_bytes() for path in paths}, before)

    def test_generator_or_future_feature_configuration_drift_is_rejected(self):
        original, _ = build_inventory(REPO)
        changed = copy.deepcopy(original)
        changed["work_packages"][4]["verification"]["configured"] = True
        with self.assertRaises(PortError):
            apply_overlay(changed)
        changed = copy.deepcopy(base_manifest().data)
        changed["inventory"][0]["primary_owner"] = "WP-003"
        from controller.schema import load_json

        real = load_json
        with patch("controller.verification_config.load_json", side_effect=lambda path: changed if path.name == "port-manifest.json" else real(path)):
            with self.assertRaises(PortError):
                check_configuration(REPO)
