import fnmatch
import re
from dataclasses import asdict, dataclass

from .errors import PortError
from .model import SHA, Manifest
from .paths import git_path, permits, validate_writes
from .schema import digest, fields, nonempty, positive_integer


@dataclass(frozen=True)
class Binding:
    repository: str
    work_package: str
    base_sha: str
    head_sha: str
    source_sha: str
    manifest_sha256: str
    policy_revision: str

    def __post_init__(self):
        if not all(isinstance(value, str) for value in asdict(self).values()):
            raise PortError("Binding fields must be strings")
        if re.fullmatch(r"[\w.-]+/[\w.-]+", self.repository) is None:
            raise PortError("Invalid repository binding")
        if re.fullmatch(r"WP-\d{3}", self.work_package) is None:
            raise PortError("Invalid work-package binding")
        if any(SHA.fullmatch(v) is None for v in (self.base_sha, self.head_sha, self.source_sha)):
            raise PortError("Binding requires full immutable source/base/head SHAs")
        if any(re.fullmatch(r"[0-9a-f]{64}", v) is None for v in (self.manifest_sha256, self.policy_revision)):
            raise PortError("Binding requires manifest/policy SHA-256 revisions")

    @property
    def key(self):
        return digest(asdict(self))

    @classmethod
    def parse(cls, value: dict):
        fields(value, set(cls.__dataclass_fields__), label="immutable gate binding")
        return cls(**value)


def policy_revision(manifest: Manifest, policy: dict) -> str:
    # Operational stop/start switches cannot invalidate already accepted feature evidence.
    semantics = {k: v for k, v in policy.items() if k not in {
        "dispatch_mode", "paused", "activation_approved", "pending_capabilities",
    }}
    return digest({
        "manifest": manifest.sha256, "policy": semantics,
        "source": manifest.data["reference"]["commit"],
    })


def protected_paths(policy: dict, changed_paths: list[str]) -> list[str]:
    return [
        path for path in changed_paths
        if permits(policy["protected_paths"], path)
        or any(fnmatch.fnmatchcase(git_path(path), pattern) for pattern in policy["protected_patterns"])
    ]


def integrity(base_manifest: dict, candidate_manifest: dict, policy: dict, changed_paths: list[str]) -> dict:
    readonly = [p for p in changed_paths if permits(policy["read_only_paths"], p)]
    if readonly:
        raise PortError(f"Read-only Swift/reference changes: {readonly}")
    base = {w["id"]: w for w in base_manifest["work_packages"]}
    candidate = {w["id"]: w for w in candidate_manifest["work_packages"]}
    removed = base.keys() - candidate.keys()
    if removed:
        raise PortError(f"Removed approved work packages: {sorted(removed)}")
    weakened = [
        wp_id for wp_id, wp in base.items()
        if wp["human_gate"] and not candidate[wp_id]["human_gate"]
        or not {a["id"] for a in wp["acceptance"]}.issubset(
            {a["id"] for a in candidate[wp_id]["acceptance"]}
        )
        or not set(wp["depends_on"]).issubset(candidate[wp_id]["depends_on"])
    ]
    if weakened:
        raise PortError(f"Weakened gates/acceptance/dependencies: {weakened}")
    protected = protected_paths(policy, changed_paths)
    if base_manifest != candidate_manifest and "docs/android/port-manifest.json" not in protected:
        protected.append("docs/android/port-manifest.json")
    return {"protected_paths": protected, "human_gate_required": bool(protected)}


def _binding(value: dict, binding: Binding, label: str, review=False):
    expected = asdict(binding)
    if review:
        expected.pop("source_sha")
        expected.pop("manifest_sha256")
        expected.pop("work_package")
        if value.get("work_packages") != [binding.work_package]:
            raise PortError(f"{label}: work-package binding mismatch")
    for key, wanted in expected.items():
        if value.get(key) != wanted:
            raise PortError(f"{label}: stale or mismatched {key}")


def _acceptance(entries, wp: dict, artifacts: set[str], label: str):
    if not isinstance(entries, list) or not entries:
        raise PortError(f"{label}: missing acceptance evidence")
    found = set()
    for entry in entries:
        fields(entry, {"id", "result", "evidence"}, label=label)
        if entry["id"] in found:
            raise PortError(f"{label}: duplicate acceptance ID")
        found.add(entry["id"])
        if entry["result"] != "PASS":
            raise PortError(f"{label}: non-passing acceptance {entry['id']}")
        if not isinstance(entry["evidence"], list) or not entry["evidence"]:
            raise PortError(f"{label}: missing artifact references")
        if any(path not in artifacts for path in entry["evidence"]):
            raise PortError(f"{label}: unknown evidence artifact")
    if found != {a["id"] for a in wp["acceptance"]}:
        raise PortError(f"{label}: missing or unknown acceptance IDs")


def validate_review(review: dict, binding: Binding, wp: dict, artifacts: set[str], human_required: bool):
    fields(review, {
        "schema_version", "repository", "work_packages", "base_sha", "head_sha", "policy_revision",
        "verdict", "summary", "acceptance", "findings", "blockers", "human_gate_required",
    }, label="parity review")
    if type(review["schema_version"]) is not int or review["schema_version"] != 1:
        raise PortError("Unsupported review schema")
    _binding(review, binding, "parity review", review=True)
    nonempty(review["summary"], "Review summary")
    if review["verdict"] not in ("PASS", "CHANGES_REQUESTED", "BLOCKED"):
        raise PortError("Unknown review verdict")
    if type(review["human_gate_required"]) is not bool or review["human_gate_required"] != human_required:
        raise PortError("Review human-gate declaration disagrees with trusted controller")
    if not isinstance(review["findings"], list) or not isinstance(review["blockers"], list):
        raise PortError("Malformed findings/blockers")
    for finding in review["findings"]:
        fields(finding, {"file", "start_line", "end_line", "reason", "required_fix"}, label="finding")
        git_path(finding["file"])
        positive_integer(finding["start_line"], "Finding start line")
        positive_integer(finding["end_line"], "Finding end line")
        if finding["end_line"] < finding["start_line"]:
            raise PortError("Invalid finding line range")
        nonempty(finding["reason"], "Finding reason")
        nonempty(finding["required_fix"], "Finding correction")
    for blocker in review["blockers"]:
        nonempty(blocker, "Review blocker")
    if review["verdict"] != "PASS":
        raise PortError(f"Independent parity review is {review['verdict']}")
    if review["findings"] or review["blockers"]:
        raise PortError("PASS review contains blocking findings")
    _acceptance(review["acceptance"], wp, artifacts, "parity acceptance")


def validate_cases(evidence: dict, manifest: Manifest, wp: dict, catalog: dict | None, artifacts: set[str],
                   extra_cases: set[tuple[str, str, str]] | None = None):
    originals = {
        e["path"]: e for e in manifest.inputs(wp["id"], cross_references=False)
        if e["kind"] in ("test", "support") and e["exclusion"] is None
    }
    extra_cases = extra_cases or set()
    extra_paths = {path for path, _, _ in extra_cases}
    extra_originals = {
        e["path"]: e for e in manifest.data["inventory"]
        if e["path"] in extra_paths and e["kind"] in ("test", "support") and e["exclusion"] is None
    }
    if extra_paths - (originals.keys() | extra_originals.keys()):
        raise PortError("Unknown or excluded cross-owner original cases")
    accepted_originals = originals | extra_originals
    results = evidence["source_tests"]
    if not isinstance(results, list):
        raise PortError("Malformed original-test evidence")
    if not accepted_originals:
        if results:
            raise PortError("Unknown original-test results")
        return
    if catalog is None:
        raise PortError("Missing independently approved original-case inventory (WP-004)")
    fields(catalog, {"schema_version", "source_sha", "entries"}, label="original-case inventory")
    if catalog["schema_version"] != 1 or catalog["source_sha"] != manifest.data["reference"]["commit"]:
        raise PortError("Stale original-case inventory")
    registered = {}
    for entry in catalog["entries"]:
        fields(entry, {"path", "blob_sha", "has_assertions", "cases"}, label="original-case entry")
        if entry["path"] in registered:
            raise PortError("Duplicate original-case path")
        registered[entry["path"]] = entry
        if type(entry["has_assertions"]) is not bool or not isinstance(entry["cases"], list):
            raise PortError("Malformed original-case definition")
        if entry["has_assertions"] and not entry["cases"]:
            raise PortError("Zero-test original-case inventory")
        if entry["path"] in accepted_originals and entry["path"].endswith("Tests.swift") and not entry["has_assertions"]:
            raise PortError("Original test suite cannot be reclassified as zero-test support")
        ids = set()
        for case in entry["cases"]:
            fields(case, {"id", "parameter_family"}, label="original case")
            nonempty(case["id"], "Original case ID")
            nonempty(case["parameter_family"], "Original parameter family")
            key = (case["id"], case["parameter_family"])
            if key in ids:
                raise PortError("Duplicate original case")
            ids.add(key)
    found = set()
    for result in results:
        fields(result, {"path", "blob_sha", "cases"}, label="original-test result")
        path = result["path"]
        if path in found or path not in accepted_originals or path not in registered:
            raise PortError("Duplicate, unknown or uninventoried original-test result")
        found.add(path)
        original = accepted_originals[path]
        definition = registered[path]
        if result["blob_sha"] != original["blob_sha"] or definition["blob_sha"] != original["blob_sha"]:
            raise PortError("Original-test blob provenance mismatch")
        if not isinstance(result["cases"], list):
            raise PortError("Malformed original-test cases")
        cases = set()
        for case in result["cases"]:
            fields(case, {"id", "parameter_family", "result", "evidence"}, label="original-case result")
            key = (case["id"], case["parameter_family"])
            if key in cases or case["result"] != "PASS":
                raise PortError("Duplicate/non-passing original-case result")
            cases.add(key)
            if not isinstance(case["evidence"], list) or not case["evidence"]:
                raise PortError("Missing original-case evidence")
            if any(p not in artifacts for p in case["evidence"]):
                raise PortError("Unknown original-case artifact")
        expected = (
            {(c["id"], c["parameter_family"]) for c in definition["cases"]}
            if path in originals else
            {(case_id, family) for case_path, case_id, family in extra_cases if case_path == path}
        )
        registered_cases = {(c["id"], c["parameter_family"]) for c in definition["cases"]}
        if not expected.issubset(registered_cases):
            raise PortError("Unknown cross-owner original case")
        if cases != expected:
            raise PortError("Missing/unknown original cases or parameter families")
    if found != accepted_originals.keys():
        raise PortError(f"Missing original-test results: {sorted(accepted_originals.keys() - found)}")


def validate_evidence(evidence: dict, binding: Binding, manifest: Manifest, policy: dict, catalog=None):
    if isinstance(evidence, dict) and evidence.get("schema_version") == 2:
        return validate_hosted_evidence(evidence, binding, manifest, catalog)
    if isinstance(evidence, dict) and evidence.get("schema_version") == 4:
        from .repair_acceptance import validate_scope
        from .verification import require_shared_whole_lane

        wp = manifest.wp(binding.work_package)
        require_shared_whole_lane(manifest, wp)
        artifacts, runs = hosted_references(evidence, binding, schema_version=4, extra_fields=("verification_scope",))
        scope = evidence["verification_scope"]
        if not isinstance(scope, dict) or scope.get("kind") != "whole-wp":
            raise PortError("Missing complete whole-WP verification scope")
        _, scoped_cases, _ = validate_scope(scope, binding, manifest, policy, evidence["changed_paths"], catalog)
        artifacts |= {
            f"https://github.com/{binding.repository}/blob/{item['revision']}/{item['transcript']}"
            for item in scope["native"]
        }
        _acceptance(evidence["acceptance"], wp, artifacts, "implementation acceptance")
        owned_paths = {entry["path"] for entry in manifest.inputs(wp["id"], False)}
        validate_cases(evidence, manifest, wp, catalog, artifacts,
                       {case for case in scoped_cases if case[0] not in owned_paths})
        return artifacts, runs
    fields(evidence, set(asdict(binding)) | {
        "schema_version", "runs", "acceptance", "source_tests", "changed_paths",
    }, label="acceptance evidence")
    if type(evidence["schema_version"]) is not int or evidence["schema_version"] != 1:
        raise PortError("Unsupported evidence schema")
    _binding(evidence, binding, "acceptance evidence")
    wp = manifest.wp(binding.work_package)
    if not wp["verification"]["configured"] or not wp["verification"]["commands"]:
        raise PortError(wp["verification"]["blocker"] or "Verification commands are not configured")
    validate_writes(wp["write_paths"], evidence["changed_paths"])
    if not isinstance(evidence["runs"], list) or not evidence["runs"]:
        raise PortError("Missing required CI evidence")
    checks, run_ids, artifacts = set(), set(), set()
    for run in evidence["runs"]:
        fields(run, {
            "check", "run_id", "run_attempt", "workflow_id", "app_id", "base_sha", "head_sha",
            "status", "conclusion", "test_count", "passed_count", "failed_count", "skipped_count",
            "artifacts",
        }, label="CI run")
        check = run["check"]
        if check in checks:
            raise PortError("Duplicate required CI check")
        checks.add(check)
        positive_integer(run["run_id"], "CI run ID")
        positive_integer(run["run_attempt"], "CI run attempt")
        positive_integer(run["app_id"], "CI publisher app ID")
        positive_integer(run["workflow_id"], "CI workflow ID")
        run_ids.add(run["run_id"])
        if run["app_id"] not in policy["trusted_check_app_ids"] or run["workflow_id"] not in policy["trusted_workflow_ids"]:
            raise PortError("Unconfigured/untrusted CI publisher or workflow identity")
        if run["head_sha"] != binding.head_sha or run["base_sha"] != binding.base_sha:
            raise PortError("Stale CI SHA")
        if run["status"] != "completed" or run["conclusion"] != "success":
            raise PortError("Required CI did not complete successfully (skipped is not success)")
        counts = [run[k] for k in ("test_count", "passed_count", "failed_count", "skipped_count")]
        if any(type(c) is not int or c < 0 for c in counts):
            raise PortError("Malformed discovered-test counts")
        total, passed, failed, skipped = counts
        if total != passed + failed + skipped or failed or skipped:
            raise PortError("Failed/skipped/inconsistent test evidence")
        if check == "android-ci" and total == 0:
            raise PortError("Zero discovered tests")
        if not isinstance(run["artifacts"], list) or not run["artifacts"]:
            raise PortError("Missing CI artifacts")
        for artifact in run["artifacts"]:
            fields(artifact, {"path", "sha256"}, label="artifact")
            path = git_path(artifact["path"])
            if re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]) is None:
                raise PortError("Malformed artifact checksum")
            if path in artifacts:
                raise PortError("Duplicate evidence artifact path")
            artifacts.add(path)
    if checks != set(policy["required_checks"]):
        raise PortError("Missing or unknown required CI checks")
    _acceptance(evidence["acceptance"], wp, artifacts, "implementation acceptance")
    validate_cases(evidence, manifest, wp, catalog, artifacts)
    return artifacts, sorted(run_ids)


def hosted_references(evidence: dict, binding: Binding, *, schema_version=2, extra_fields=()):
    fields(evidence, set(asdict(binding)) | {
        "schema_version", "runs", "acceptance", "source_tests", "changed_paths", "retained_artifacts",
    } | set(extra_fields), label="hosted acceptance evidence")
    if type(evidence["schema_version"]) is not int or evidence["schema_version"] != schema_version:
        raise PortError("Unsupported hosted acceptance schema")
    _binding(evidence, binding, "hosted acceptance evidence")
    if not isinstance(evidence["runs"], list) or len(evidence["runs"]) != 1:
        raise PortError("Exactly one authoritative Android CI run reference is required")
    run = evidence["runs"][0]
    fields(run, {"check", "run_id", "run_attempt"}, label="hosted run reference")
    if run["check"] != "android-ci":
        raise PortError("Unknown hosted CI check")
    positive_integer(run["run_id"], "Hosted run ID")
    positive_integer(run["run_attempt"], "Hosted run attempt")
    artifacts = {
        f"https://github.com/{binding.repository}/actions/runs/{run['run_id']}/attempts/{run['run_attempt']}"
    }
    if not isinstance(evidence["retained_artifacts"], list):
        raise PortError("Malformed retained non-reproducible evidence")
    for item in evidence["retained_artifacts"]:
        fields(item, {"path", "sha256"}, label="retained evidence")
        path = git_path(item["path"])
        if path in artifacts or re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) is None:
            raise PortError("Duplicate or malformed retained evidence")
        artifacts.add(path)
    return artifacts, [run["run_id"]]


def validate_hosted_evidence(evidence: dict, binding: Binding, manifest: Manifest, catalog):
    """Hosted results stay on GitHub; acceptance retains references, never a second CI bundle."""
    artifacts, runs = hosted_references(evidence, binding)
    wp = manifest.wp(binding.work_package)
    if not wp["verification"]["configured"] or not wp["verification"]["commands"]:
        raise PortError("Verification commands are not configured")
    validate_writes(wp["write_paths"], evidence["changed_paths"])
    _acceptance(evidence["acceptance"], wp, artifacts, "implementation acceptance")
    validate_cases(evidence, manifest, wp, catalog, artifacts)
    return artifacts, runs


def human_acceptance_required(wp: dict, policy: dict, changed_paths: list[str], labels=()):
    rules = policy.get("review_policy")
    fields(rules, {
        "code_review_authority", "authorized_coordinators", "human_acceptance_work_packages",
        "human_acceptance_paths", "human_acceptance_patterns",
    }, label="review policy")
    if rules["code_review_authority"] != "trusted-read-only-review":
        raise PortError("Unsupported code review authority")
    for key in ("authorized_coordinators", "human_acceptance_work_packages",
                "human_acceptance_paths", "human_acceptance_patterns"):
        values = rules[key]
        if not isinstance(values, list) or not values or not all(isinstance(v, str) and v for v in values):
            raise PortError(f"Malformed review policy {key}")
    return (
        wp["id"] in rules["human_acceptance_work_packages"]
        or "needs-human" in labels
        or bool(protected_paths({
            "protected_paths": rules["human_acceptance_paths"],
            "protected_patterns": rules["human_acceptance_patterns"],
        }, protected_paths(policy, changed_paths)))
    )


def validate_approval(approvals: list[dict], binding: Binding, policy: dict, author: str):
    """Validate retained human acceptance, not a separate-person code review."""
    nonempty(author, "Actual PR author")
    if not isinstance(approvals, list):
        raise PortError("Malformed maintainer approvals")
    for approval in approvals:
        typed = isinstance(approval, dict) and "kind" in approval
        fields(approval, {
            "review_id", "reviewer", "state", "head_sha", "base_sha", "policy_revision",
            "binding_key",
        } | ({"kind"} if typed else set()), label="human acceptance")
        if typed and approval["kind"] != "human-acceptance":
            raise PortError("Unknown human acceptance kind")
        positive_integer(approval["review_id"], "Review ID")
        states = ("COMMENTED", "APPROVED") if typed else ("APPROVED",)
        if (
            approval["reviewer"] in policy["maintainers"]
            and approval["state"] in states and approval["head_sha"] == binding.head_sha
            and approval["base_sha"] == binding.base_sha
            and approval["policy_revision"] == binding.policy_revision
            and approval["binding_key"] == binding.key
        ):
            return approval["review_id"]
    raise PortError("Missing current-head/current-base bound human acceptance")


def verify_pr_gates(pr: dict, evidence: dict, review: dict, approvals: list[dict],
                    binding: Binding, manifest: Manifest, policy: dict, catalog=None) -> dict:
    if isinstance(evidence, dict) and evidence.get("schema_version") == 3:
        raise PortError("Bounded repair acceptance is not full-WP gate/completion evidence")
    if (
        binding.source_sha != manifest.data["reference"]["commit"]
        or binding.manifest_sha256 != manifest.sha256
        or binding.policy_revision != policy_revision(manifest, policy)
    ):
        raise PortError("Stale source/manifest/trusted-policy binding")
    fields(pr, {
        "number", "repository", "work_package", "base_sha", "head_sha", "merged",
        "merge_commit_sha", "author", "labels", "changed_paths",
    }, label="implementation PR")
    positive_integer(pr["number"], "PR number")
    if type(pr["merged"]) is not bool:
        raise PortError("Malformed implementation PR merge state")
    for field in ("repository", "work_package", "base_sha", "head_sha"):
        if pr[field] != getattr(binding, field):
            raise PortError(f"Implementation PR {field} mismatch")
    if not isinstance(pr["labels"], list) or not all(isinstance(v, str) for v in pr["labels"]):
        raise PortError("Malformed PR labels")
    if set(pr["changed_paths"]) != set(evidence["changed_paths"]):
        raise PortError("Evidence omits or invents changed PR paths")
    artifacts, run_ids = validate_evidence(evidence, binding, manifest, policy, catalog)
    wp = manifest.wp(binding.work_package)
    human = wp["human_gate"] or bool(protected_paths(policy, pr["changed_paths"])) or "needs-human" in pr["labels"]
    validate_review(review, binding, wp, artifacts, human)
    requires_acceptance = human_acceptance_required(wp, policy, pr["changed_paths"], pr["labels"])
    approval_id = validate_approval(approvals, binding, policy, pr["author"]) if requires_acceptance else None
    return {
        "binding": asdict(binding), "pr_number": pr["number"],
        "merge_commit_sha": pr["merge_commit_sha"], "ci_run_ids": run_ids,
        "approval_id": approval_id, "evidence_sha256": digest(evidence), "review_sha256": digest(review),
        "review_authorization": {
            "kind": "trusted-read-only-code-review", "binding_key": binding.key,
            "human_acceptance_required": requires_acceptance,
        },
    }


def verify_completion(pr: dict, evidence: dict, review: dict, approvals: list[dict],
                      binding: Binding, manifest: Manifest, policy: dict, catalog=None) -> dict:
    if isinstance(evidence, dict) and evidence.get("schema_version") == 3:
        raise PortError("Bounded repair cannot complete a WP or unlock dependencies")
    if pr.get("merged") is not True or not isinstance(pr.get("merge_commit_sha"), str):
        raise PortError("Dependency completion requires a merged implementation PR, not a closed issue")
    if SHA.fullmatch(pr["merge_commit_sha"]) is None:
        raise PortError("Missing valid merge commit")
    return verify_pr_gates(pr, evidence, review, approvals, binding, manifest, policy, catalog)


def merge_decision(pr: dict, evidence: dict, review: dict, approvals: list[dict],
                   binding: Binding, manifest: Manifest, policy: dict, current_base: str, catalog=None):
    if policy["paused"] or not policy["activation_approved"] or policy["dispatch_mode"] == "off":
        raise PortError("Paused/off policy blocks merges")
    if policy["admin_bypass"] or policy["merge_strategy"] != "serialized-exact-head-current-base":
        raise PortError("Unsupported merge strategy or admin bypass")
    if current_base != binding.base_sha or pr["head_sha"] != binding.head_sha:
        raise PortError("Current base/head changed; rerun CI/review/approval")
    if pr["merged"]:
        raise PortError("PR already merged; reconcile completion instead")
    result = verify_pr_gates(pr, evidence, review, approvals, binding, manifest, policy, catalog)
    return {"action": "merge", "expected_head_sha": binding.head_sha, "base_sha": current_base,
            "ci_run_ids": result["ci_run_ids"], "approval_id": result["approval_id"]}
