import re

from .backends import CloudBackend
from .engine import GateBundle, TerminalMerge
from .errors import PortError
from .gates import Binding, policy_revision, validate_evidence
from .ledger import Identity
from .model import Manifest, SHA
from .publication import GATE_MARKER, decode_bundle
from .render import marker
from .schema import decode_json, fields, positive_integer

class GitHubGateAuthority:
    """Read server facts from native GitHub reviews/checks or a legacy trusted publisher.

    Never load a candidate script, model-written file, PR body verdict or closed issue
    as authority. Manual native publication requires deployed default-branch code.
    """

    def __init__(self, backend: CloudBackend, manifest: Manifest, policy: dict):
        self.backend, self.manifest, self.policy = backend, manifest, policy

    def merged_terminal(self, wp_id: str, session_id: str, receipt: dict, expected_base_sha: str):
        if (
            not isinstance(receipt, dict)
            or receipt.get("repository") != self.policy["repository"]
            or receipt.get("session_id") != session_id
            or not isinstance(session_id, str) or not session_id
            or type(receipt.get("pr_number")) is not int or receipt["pr_number"] < 1
            or not isinstance(receipt.get("head_sha"), str)
            or SHA.fullmatch(receipt["head_sha"]) is None
            or not isinstance(receipt.get("merge_sha"), str)
            or SHA.fullmatch(receipt["merge_sha"]) is None
            or not isinstance(receipt.get("paths"), list)
            or not all(isinstance(path, str) and path for path in receipt["paths"])
            or not isinstance(expected_base_sha, str) or SHA.fullmatch(expected_base_sha) is None
        ):
            raise PortError("Malformed immutable terminal receipt")
        prefix = self.backend.prefix
        number = receipt["pr_number"]
        pr = self.backend.api.request("GET", f"{prefix}/pulls/{number}")
        if (
            pr.get("number") != number
            or marker(wp_id) not in (pr.get("body") or "")
            or pr.get("base", {}).get("repo", {}).get("full_name") != self.policy["repository"]
            or pr.get("base", {}).get("ref") != self.policy["default_branch"]
            or pr.get("merged") is not True
            or pr.get("head", {}).get("sha") != receipt["head_sha"]
            or pr.get("merge_commit_sha") != receipt["merge_sha"]
        ):
            raise PortError("Server PR is not the exact merged WP terminal receipt")
        commit = self.backend.api.request("GET", f"{prefix}/git/commits/{receipt['merge_sha']}")
        parents = commit.get("parents")
        if (
            commit.get("sha") != receipt["merge_sha"]
            or not isinstance(parents, list) or len(parents) not in (1, 2)
            or any(not isinstance(parent, dict) for parent in parents)
            or any(not isinstance(parent.get("sha"), str) or SHA.fullmatch(parent["sha"]) is None
                   for parent in parents)
            or len(parents) == 2 and parents[1].get("sha") != receipt["head_sha"]
        ):
            raise PortError("Merged terminal commit does not bind the exact predecessor base and head")
        if parents[0]["sha"] != expected_base_sha:
            for descendant in dict.fromkeys((parents[0]["sha"], receipt["head_sha"])):
                comparison = self.backend.api.request(
                    "GET", f"{prefix}/compare/{expected_base_sha}...{descendant}",
                )
                if (
                    not isinstance(comparison, dict)
                    or comparison.get("status") not in ("ahead", "identical")
                    or not isinstance(comparison.get("base_commit"), dict)
                    or not isinstance(comparison.get("merge_base_commit"), dict)
                    or comparison.get("base_commit", {}).get("sha") != expected_base_sha
                    or comparison.get("merge_base_commit", {}).get("sha") != expected_base_sha
                    or type(comparison.get("behind_by")) is not int or comparison["behind_by"] != 0
                ):
                    raise PortError("Merged terminal commit does not bind the exact predecessor base and head ancestry")
        changed = set()
        for item in self.backend.pages(f"{prefix}/pulls/{number}/files"):
            filename = item.get("filename")
            if not isinstance(filename, str) or not filename:
                raise PortError("Malformed merged PR file inventory")
            changed.add(filename)
            if item.get("status") == "renamed":
                previous = item.get("previous_filename")
                if not isinstance(previous, str) or not previous:
                    raise PortError("Merged PR rename has no previous path")
                changed.add(previous)
        paths = tuple(sorted(changed))
        if paths != tuple(sorted(set(receipt["paths"]))):
            raise PortError("Merged PR changed paths disagree with the immutable terminal receipt")
        return TerminalMerge(
            self.policy["repository"], wp_id, session_id, number,
            expected_base_sha, receipt["head_sha"], receipt["merge_sha"], paths, True,
        )

    def bundle(self, wp_id: str, identity: Identity):
        if not self.policy["trusted_gate_publisher_app_id"]:
            from .native_review import NativeReview

            return NativeReview(self.backend, self.manifest, self.policy).bundle(wp_id, identity)
        number = positive_integer(identity.pr_number, "Existing implementation PR")
        prefix = self.backend.prefix
        pr = self.backend.api.request("GET", f"{prefix}/pulls/{number}")
        if pr.get("number") != number or marker(wp_id) not in (pr.get("body") or ""):
            raise PortError("Implementation PR identity/WP marker mismatch")
        if pr.get("base", {}).get("repo", {}).get("full_name") != self.policy["repository"]:
            raise PortError("Implementation PR targets another repository")
        head = pr.get("head", {}).get("sha")
        checks = self.backend.pages(f"{prefix}/commits/{head}/check-runs", "check_runs")
        latest = {}
        for check in checks:
            name = check.get("name")
            if name in self.policy["required_checks"]:
                positive_integer(check.get("id"), "Check-run ID")
                if name not in latest or check["id"] > latest[name]["id"]:
                    latest[name] = check
        if latest.keys() != set(self.policy["required_checks"]):
            raise PortError("Missing server-side required checks")
        publisher = latest["gate-integrity"]
        if publisher.get("app", {}).get("id") != self.policy["trusted_gate_publisher_app_id"]:
            raise PortError("Gate output is not from the isolated trusted publisher")
        summary = publisher.get("output", {}).get("summary")
        payload = decode_bundle(summary)
        fields(payload["binding"], {
            "repository", "work_package", "base_sha", "head_sha", "source_sha",
            "manifest_sha256", "policy_revision",
        }, label="gate binding")
        binding = Binding(**payload["binding"])
        if (
            binding.repository != self.policy["repository"] or binding.work_package != wp_id
            or binding.head_sha != head or binding.manifest_sha256 != self.manifest.sha256
            or binding.source_sha != self.manifest.data["reference"]["commit"]
            or binding.policy_revision != policy_revision(self.manifest, self.policy)
        ):
            raise PortError("Trusted gate bundle has stale/mismatched repository/head/source/policy")
        base = pr.get("base", {}).get("sha")
        if pr.get("merged") is True:
            commit = self.backend.api.request("GET", f"{prefix}/git/commits/{pr.get('merge_commit_sha')}")
            parents = commit.get("parents")
            if not isinstance(parents, list) or not parents:
                raise PortError("Missing historical merged-PR base provenance")
            base = parents[0].get("sha")
        if base != binding.base_sha:
            raise PortError("Server-side current/historical base disagrees with gate binding")
        evidence = payload["evidence"]
        if not isinstance(evidence, dict) or not isinstance(evidence.get("runs"), list):
            raise PortError("Malformed trusted CI evidence")
        validate_evidence(evidence, binding, self.manifest, self.policy, payload["catalog"])
        for run in evidence["runs"]:
            check = latest.get(run.get("check"))
            if check is None:
                raise PortError("Unknown claimed CI check")
            if (
                check.get("head_sha") != head or check.get("status") != "completed"
                or check.get("conclusion") != "success" or check.get("app", {}).get("id") != run.get("app_id")
            ):
                raise PortError("CI evidence disagrees with real server-side check result")
            wanted = f"https://github.com/{self.policy['repository']}/actions/runs/{run.get('run_id')}"
            details = check.get("details_url") or ""
            if details != wanted and not details.startswith(wanted + "/"):
                raise PortError("Check-run/Actions-run identity mismatch")
            actual = self.backend.api.request("GET", f"{prefix}/actions/runs/{run.get('run_id')}")
            expected_runner_sha = head if run["check"] == "android-ci" else binding.base_sha
            expected_events = ("pull_request", "merge_group") if run["check"] == "android-ci" else ("workflow_dispatch",)
            if (
                actual.get("id") != run.get("run_id")
                or
                actual.get("repository", {}).get("full_name") != self.policy["repository"]
                or actual.get("workflow_id") != run.get("workflow_id")
                or actual.get("run_attempt") != run.get("run_attempt")
                or actual.get("head_sha") != expected_runner_sha
                or actual.get("event") not in expected_events
                or actual.get("status") != "completed" or actual.get("conclusion") != "success"
            ):
                raise PortError("CI run is stale, skipped, untrusted, or executes candidate code with publisher authority")
            if run["check"] == "android-ci":
                if (
                    type(actual.get("check_suite_id")) is not int
                    or check.get("check_suite", {}).get("id") != actual["check_suite_id"]
                ):
                    raise PortError("Required Android check is not part of the claimed actual Actions run")
            elif check.get("external_id") != f"{binding.key}:{run['run_id']}:{run['run_attempt']}":
                raise PortError("Trusted publisher check lacks the exact binding/run/attempt external identity")
        changed = []
        for item in self.backend.pages(f"{prefix}/pulls/{number}/files"):
            changed.append(item["filename"])
            if item.get("status") == "renamed":
                changed.append(item["previous_filename"])
        pr_fact = {
            "number": number, "repository": self.policy["repository"], "work_package": wp_id,
            "base_sha": base, "head_sha": head, "merged": pr.get("merged"),
            "merge_commit_sha": pr.get("merge_commit_sha"), "author": pr.get("user", {}).get("login"),
            "labels": [label["name"] for label in pr.get("labels", [])],
            "changed_paths": sorted(set(changed)),
        }
        reviews = self.backend.pages(f"{prefix}/pulls/{number}/reviews")
        self.validate_server_acceptance(payload["approvals"], reviews, binding)
        return GateBundle(
            binding, pr_fact, evidence, payload["review"], payload["approvals"],
            payload["catalog"], True,
        )

    def validate_server_acceptance(self, approvals, reviews, binding):
        head = binding.head_sha
        if not isinstance(approvals, list):
            raise PortError("Malformed trusted approval records")
        by_id = {review["id"]: review for review in reviews}
        latest_decisive = {}
        latest_formal = {}
        for item in reviews:
            body = item.get("body") or ""
            acceptance = f"<!-- android-port-acceptance:{binding.key} -->"
            if item.get("state") in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED"):
                login = item.get("user", {}).get("login")
                if login not in latest_formal or item["id"] > latest_formal[login]["id"]:
                    latest_formal[login] = item
            if item.get("state") in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED") or (
                item.get("state") == "COMMENTED" and acceptance in body
            ):
                login = item.get("user", {}).get("login")
                if login not in latest_decisive or item["id"] > latest_decisive[login]["id"]:
                    latest_decisive[login] = item
        if any(
            login in set(self.policy["maintainers"]) | set(self.policy["review_policy"]["authorized_coordinators"])
            and item.get("state") == "CHANGES_REQUESTED" and item.get("commit_id") == head
            for login, item in latest_formal.items()
        ):
            raise PortError("Approval/acceptance blocked by current-head review findings")
        for approval in approvals:
            if not isinstance(approval, dict):
                raise PortError("Malformed trusted acceptance record")
            actual = by_id.get(approval.get("review_id"))
            typed = "kind" in approval
            if typed and approval["kind"] != "human-acceptance":
                raise PortError("Unknown human acceptance kind")
            stamp_kind = "acceptance" if typed else "approval"
            stamp = f"<!-- android-port-{stamp_kind}:{binding.key} -->"
            states = ("COMMENTED", "APPROVED") if typed else ("APPROVED",)
            if actual is None or (
                actual.get("state") not in states or actual.get("state") != approval.get("state")
                or actual.get("commit_id") != head
                or actual.get("user", {}).get("login") != approval.get("reviewer")
                or approval.get("reviewer") not in self.policy["maintainers"]
                or stamp not in (actual.get("body") or "")
                or latest_decisive.get(approval.get("reviewer"), {}).get("id") != approval.get("review_id")
            ):
                raise PortError("Approval/acceptance is not an actual current-head explicitly bound maintainer review")
