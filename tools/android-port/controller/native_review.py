"""AndroidOnly: WP-003 Authenticated GitHub review receipts and default-branch Checks publication."""

import argparse
import hashlib
import json
import os
import sys
from dataclasses import asdict
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.backends import CloudBackend, GitHubApi
from controller.engine import GateBundle
from controller.errors import PortError
from controller.gates import Binding, policy_revision, verify_pr_gates
from controller.ledger import Identity
from controller.model import Manifest, git, tree
from controller.publication import bounded_output
from controller.render import marker
from controller.schema import decode_json, digest, fields, positive_integer
from controller.settings import Settings
from controller.staging import object_bytes, stage_review

REVIEW_MARKER = "<!-- android-port-native-review-v1 -->\n"
RESULT_MARKER = "<!-- android-port-native-gates-v1 -->\n"
WORKFLOW_PATH = ".github/workflows/android-parity-review.yml"
CI_PATH = ".github/workflows/android-ci.yml"


def trusted_inputs(repo, revision):
    entries = tree(repo, revision)

    def read(path):
        if path not in entries:
            raise PortError("Missing trusted Git input: " + path)
        return decode_json(object_bytes(repo, entries[path]).decode("utf-8"))

    return (
        Manifest(read("docs/android/port-manifest.json"), read("docs/android/not-ported.json"), repo),
        read("docs/android/automation-policy.json"),
    )


class NativeReview:
    def __init__(self, backend, manifest, policy):
        self.backend, self.manifest, self.policy = backend, manifest, policy
        self.prefix = backend.prefix
        if set(policy["required_checks"]) != {"android-ci", "parity-review", "gate-integrity"}:
            raise PortError("Native transport does not implement the configured required-check set")

    def get(self, path):
        return self.backend.api.request("GET", self.prefix + path)

    def workflow_run(self, run_id, attempt, path, head, events, *, completed=True, review_identity=None):
        positive_integer(run_id, "Actions run ID")
        positive_integer(attempt, "Actions run attempt")
        run = self.get(f"/actions/runs/{run_id}")
        workflow = self.get("/actions/workflows/" + path.rsplit("/", 1)[-1])
        positive_integer(workflow.get("id"), "Trusted workflow ID")
        if (
            run.get("id") != run_id or run.get("run_attempt") != attempt
            or run.get("repository", {}).get("full_name") != self.policy["repository"]
            or run.get("workflow_id") != workflow.get("id") or workflow.get("path") != path
            or run.get("path") != path or run.get("head_sha") != head
            or run.get("event") not in events
            or completed and (run.get("status") != "completed" or run.get("conclusion") != "success")
        ):
            raise PortError("Actions run is stale, failed, skipped, or has untrusted workflow provenance")
        if path == WORKFLOW_PATH and (
            run.get("head_branch") != self.policy["default_branch"]
            or any(run.get(key, {}).get("login") not in self.policy["review_policy"]["authorized_coordinators"]
                   for key in ("actor", "triggering_actor"))
        ):
            raise PortError("Publisher must be dispatched/rerun by an authorized coordinator on the default branch")
        if path == WORKFLOW_PATH:
            if review_identity is None:
                raise PortError("Publisher requires an exact WP/PR/review identity")
            wp_id, number, review_id = review_identity
            jobs = self.backend.pages(
                f"{self.prefix}/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs",
            )
            if len(jobs) != 1 or (
                jobs[0].get("name") != f"Native review {wp_id} PR {number} receipt {review_id}"
                or jobs[0].get("run_id") != run_id or jobs[0].get("head_sha") != head
                or completed and (jobs[0].get("status") != "completed" or jobs[0].get("conclusion") != "success")
            ):
                raise PortError("Server Actions job does not bind this exact WP/PR/review publication")
        suite = self.get(f"/check-suites/{positive_integer(run.get('check_suite_id'), 'Check suite ID')}")
        if (
            suite.get("id") != run["check_suite_id"]
            or suite.get("head_sha") != head or suite.get("app", {}).get("slug") != "github-actions"
        ):
            raise PortError("Actions application/check-suite identity mismatch")
        positive_integer(suite.get("app", {}).get("id"), "Actions application ID")
        return run, suite

    def no_unresolved_threads(self, number):
        owner, name = self.policy["repository"].split("/")
        cursor = None
        for _ in range(100):
            result = self.backend.api.request("POST", "/graphql", {
                "query": (
                    "query PortReviewThreads($owner:String!,$name:String!,$number:Int!,$after:String){"
                    "repository(owner:$owner,name:$name){pullRequest(number:$number){"
                    "reviewThreads(first:100,after:$after){nodes{isResolved}pageInfo{hasNextPage endCursor}}}}}"
                ),
                "variables": {"owner": owner, "name": name, "number": number, "after": cursor},
            }, readonly=True)
            if result.get("errors"):
                raise PortError("Cannot verify unresolved source findings")
            threads = result.get("data", {}).get("repository", {}).get("pullRequest", {}).get("reviewThreads")
            if not isinstance(threads, dict) or not isinstance(threads.get("nodes"), list):
                raise PortError("Missing source finding/thread facts")
            if any(item.get("isResolved") is not True for item in threads["nodes"]):
                raise PortError("Unresolved source review findings block acceptance")
            page = threads.get("pageInfo", {})
            if page.get("hasNextPage") is False:
                return
            next_cursor = page.get("endCursor")
            if page.get("hasNextPage") is not True or not next_cursor or next_cursor == cursor:
                raise PortError("Malformed source finding pagination")
            cursor = next_cursor
        raise PortError("Source finding pagination exceeds bound")

    def evaluate(self, wp_id, number, review_id):
        """Read server facts only; never publish, approve, merge, execute a candidate or launch a reviewer."""
        from controller.authority import GitHubGateAuthority

        positive_integer(number, "Implementation PR")
        positive_integer(review_id, "Source review ID")
        pr = self.get(f"/pulls/{number}")
        if (
            pr.get("number") != number or marker(wp_id) not in (pr.get("body") or "")
            or pr.get("base", {}).get("repo", {}).get("full_name") != self.policy["repository"]
            or pr.get("base", {}).get("ref") != self.policy["default_branch"]
        ):
            raise PortError("PR repository/default-branch/WP identity mismatch")
        base = pr.get("base", {}).get("sha")
        if pr.get("merged") is True:
            commit = self.get("/git/commits/" + pr["merge_commit_sha"])
            parents = commit.get("parents", [])
            if (
                commit.get("sha") != pr["merge_commit_sha"] or len(parents) not in (1, 2)
                or len(parents) == 2 and parents[1].get("sha") != pr.get("head", {}).get("sha")
            ):
                raise PortError("Merged PR has no exact merge-parent provenance")
            base = parents[0].get("sha")
        elif pr.get("state") != "open" or pr.get("draft") is not False or self.backend.current_base() != base:
            raise PortError("Acceptance requires an open ready PR on the exact current base")
        binding = Binding(self.policy["repository"], wp_id, base, pr.get("head", {}).get("sha"),
                          self.manifest.data["reference"]["commit"], self.manifest.sha256,
                          policy_revision(self.manifest, self.policy))
        reviews = self.backend.pages(f"{self.prefix}/pulls/{number}/reviews")
        selected = [item for item in reviews if item.get("id") == review_id]
        if len(selected) != 1:
            raise PortError("Source receipt is not an actual review on this PR")
        actual = selected[0]
        body = bounded_output(actual.get("body"))
        if (
            actual.get("state") not in ("COMMENTED", "APPROVED")
            or actual.get("commit_id") != binding.head_sha
            or actual.get("user", {}).get("type") != "User"
            or actual.get("user", {}).get("login") not in self.policy["review_policy"]["authorized_coordinators"]
            or not body.startswith(REVIEW_MARKER)
        ):
            raise PortError("Missing authenticated exact-head coordinator read-only review receipt")
        receipt = decode_json(body[len(REVIEW_MARKER):])
        fields(receipt, {"schema_version", "binding", "method", "staging_sha256",
                         "review", "evidence", "approvals"}, label="native source receipt")
        if (
            type(receipt["schema_version"]) is not int or receipt["schema_version"] != 1
            or receipt["method"] != "authenticated-coordinator-read-only"
            or Binding.parse(receipt["binding"]) != binding
            or not isinstance(receipt["evidence"], dict)
            or receipt["evidence"].get("schema_version") != 2
        ):
            raise PortError("Stale binding or unsupported native review method/schema")
        staged = stage_review(self.manifest.repo, wp_id, base, binding.head_sha)
        if receipt["staging_sha256"] != digest(staged):
            raise PortError("Review does not attest the complete trusted-profile/data-only staged inputs")
        paths = set()
        for item in self.backend.pages(f"{self.prefix}/pulls/{number}/files"):
            paths.add(item["filename"])
            if item.get("status") == "renamed":
                paths.add(item["previous_filename"])
        if paths != set(staged["changed_paths"]):
            raise PortError("Server PR file inventory disagrees with staged Git objects")
        authorized = set(self.policy["maintainers"]) | set(self.policy["review_policy"]["authorized_coordinators"])
        for item in reviews:
            if (
                item.get("id", 0) > review_id and item.get("commit_id") == binding.head_sha
                and item.get("user", {}).get("login") in authorized
                and item.get("state") in ("COMMENTED", "APPROVED", "CHANGES_REQUESTED", "DISMISSED")
                and f"<!-- android-port-acceptance:{binding.key} -->" not in (item.get("body") or "")
            ):
                raise PortError("Later authorized source feedback supersedes this receipt")
        self.no_unresolved_threads(number)
        GitHubGateAuthority(self.backend, self.manifest, self.policy).validate_server_acceptance(
            receipt["approvals"], reviews, binding,
        )
        catalog = None
        if any(e["kind"] in ("test", "support") and e["exclusion"] is None
               for e in self.manifest.inputs(wp_id, False)):
            entries = tree(self.manifest.repo, base)
            catalog = decode_json(object_bytes(
                self.manifest.repo, entries["docs/android/test-cases.json"],
            ).decode("utf-8"))
        fact = {
            "number": number, "repository": binding.repository, "work_package": wp_id,
            "base_sha": base, "head_sha": binding.head_sha, "merged": pr.get("merged"),
            "merge_commit_sha": pr.get("merge_commit_sha"), "author": pr.get("user", {}).get("login"),
            "labels": [item["name"] for item in pr.get("labels", [])], "changed_paths": sorted(paths),
        }
        value = GateBundle(binding, fact, receipt["evidence"], receipt["review"], receipt["approvals"], catalog, True)
        verify_pr_gates(fact, value.evidence, value.review, value.approvals,
                        binding, self.manifest, self.policy, catalog)
        run_ref = value.evidence["runs"][0]
        run, suite = self.workflow_run(run_ref["run_id"], run_ref["run_attempt"], CI_PATH,
                                       binding.head_sha, ("pull_request",))
        if not any(p.get("number") == number and p.get("base", {}).get("sha") == base
                   for p in run.get("pull_requests", [])):
            raise PortError("Hosted CI does not bind this PR and exact base")
        checks = self.backend.pages(f"{self.prefix}/commits/{binding.head_sha}/check-runs", "check_runs")
        ci = [c for c in checks if c.get("name") == "android-ci"]
        if not ci:
            raise PortError("Missing actual android-ci check")
        latest = max(ci, key=lambda c: positive_integer(c.get("id"), "CI check ID"))
        if (
            latest.get("head_sha") != binding.head_sha or latest.get("status") != "completed"
            or latest.get("conclusion") != "success"
            or latest.get("check_suite", {}).get("id") != run["check_suite_id"]
            or latest.get("app", {}).get("id") != suite["app"]["id"]
        ):
            raise PortError("Latest Android check disagrees with authoritative successful Actions run")
        candidate_tree = tree(self.manifest.repo, binding.head_sha)
        for item in value.evidence["retained_artifacts"]:
            if (
                item["path"] not in candidate_tree
                or hashlib.sha256(object_bytes(self.manifest.repo, candidate_tree[item["path"]])).hexdigest() != item["sha256"]
            ):
                raise PortError("Retained non-reproducible evidence bytes do not match the candidate")
        return value, {"binding": asdict(binding), "pr_number": number, "review_id": review_id,
                       "review_body_sha256": hashlib.sha256(body.encode()).hexdigest()}

    def bundle(self, wp_id, identity):
        number = positive_integer(identity.pr_number, "Implementation PR")
        pr = self.get(f"/pulls/{number}")
        checks = self.backend.pages(f"{self.prefix}/commits/{pr['head']['sha']}/check-runs", "check_runs")
        selected = []
        for name in ("parity-review", "gate-integrity"):
            matches = [c for c in checks if c.get("name") == name]
            if not matches:
                raise PortError("Missing native project check: " + name)
            selected.append(max(matches, key=lambda c: positive_integer(c.get("id"), "Project check ID")))
        summaries = [bounded_output(c.get("output", {}).get("summary")) for c in selected]
        if summaries[0] != summaries[1] or not summaries[0].startswith(RESULT_MARKER):
            raise PortError("Project checks do not reference one complete native review")
        result = decode_json(summaries[0][len(RESULT_MARKER):])
        fields(result, {"binding", "pr_number", "review_id", "review_body_sha256",
                        "publisher_run_id", "publisher_run_attempt"}, label="native gate result")
        value, expected = self.evaluate(wp_id, number, result["review_id"])
        if any(result[key] != item for key, item in expected.items()):
            raise PortError("Native project check has stale review/binding provenance")
        run, suite = self.workflow_run(result["publisher_run_id"], result["publisher_run_attempt"],
                                       WORKFLOW_PATH, value.binding.base_sha, ("workflow_dispatch",),
                                       review_identity=(wp_id, number, result["review_id"]))
        for check in selected:
            if (
                check.get("head_sha") != value.binding.head_sha or check.get("status") != "completed"
                or check.get("conclusion") != "success" or check.get("app", {}).get("id") != suite["app"]["id"]
                or check.get("external_id") != f"{value.binding.key}:{run['id']}:{run['run_attempt']}"
                or check.get("details_url") != f"https://github.com/{value.binding.repository}/actions/runs/{run['id']}"
            ):
                raise PortError("Project check is not from the authenticated default-branch publisher run")
        return value

    def publish(self, wp_id, number, review_id, run_id, attempt):
        base = self.backend.current_base()
        run, _ = self.workflow_run(run_id, attempt, WORKFLOW_PATH, base,
                                   ("workflow_dispatch",), completed=False,
                                   review_identity=(wp_id, number, review_id))
        if run.get("status") != "in_progress" or git(self.manifest.repo, "rev-parse", "HEAD").decode().strip() != base:
            raise PortError("Publisher must execute the exact deployed default-branch checkout")
        pr = self.get(f"/pulls/{positive_integer(number, 'Implementation PR')}")
        if pr.get("merged") is not False or pr.get("state") != "open":
            raise PortError("Only an open unmerged candidate can receive new project checks")
        head = pr.get("head", {}).get("sha")
        binding = Binding(self.policy["repository"], wp_id, base, head,
                          self.manifest.data["reference"]["commit"], self.manifest.sha256,
                          policy_revision(self.manifest, self.policy))
        created = []
        for name in ("parity-review", "gate-integrity"):
            check = self.backend.api.request("POST", self.prefix + "/check-runs", {
                "name": name, "head_sha": head, "status": "in_progress",
                "external_id": f"{binding.key}:{run_id}:{attempt}",
                "details_url": f"https://github.com/{binding.repository}/actions/runs/{run_id}",
            })
            created.append(positive_integer(check.get("id"), "Published check ID"))
        try:
            value, result = self.evaluate(wp_id, number, review_id)
            again, refreshed = self.evaluate(wp_id, number, review_id)
            if value != again or result != refreshed or value.binding != binding:
                raise PortError("Source findings, review, head or base changed during publication")
            result.update(publisher_run_id=run_id, publisher_run_attempt=attempt)
            summary = bounded_output(RESULT_MARKER + json.dumps(result, sort_keys=True, separators=(",", ":")))
        except (PortError, KeyError, ValueError, OSError) as error:
            for check_id in created:
                self.backend.api.request("PATCH", f"{self.prefix}/check-runs/{check_id}", {
                    "status": "completed", "conclusion": "failure",
                    "output": {"title": "Project acceptance blocked", "summary": bounded_output(str(error))},
                })
            raise
        for check_id in created:
            self.backend.api.request("PATCH", f"{self.prefix}/check-runs/{check_id}", {
                "status": "completed", "conclusion": "success",
                "output": {"title": "Authenticated exact-bound project acceptance", "summary": summary},
            })
        return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("inspect", "publish"))
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--trusted-base", required=True)
    parser.add_argument("--wp", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--review-id", required=True, type=int)
    args = parser.parse_args(argv)
    try:
        manifest, policy = trusted_inputs(args.repo, args.trusted_base)
        token = os.environ.get("ANDROID_PORT_GITHUB_TOKEN")
        if not token:
            raise PortError("Explicit authenticated GitHub credential is required")
        publishing = args.command == "publish"
        backend = CloudBackend(GitHubApi(token, allow_mutation=publishing), manifest, policy,
                               Settings.from_env({}, policy))
        native = NativeReview(backend, manifest, policy)
        if backend.current_base() != args.trusted_base:
            raise PortError("Trusted base advanced; stage and review the current candidate again")
        if publishing:
            if (
                os.environ.get("GITHUB_ACTIONS") != "true"
                or os.environ.get("GITHUB_REPOSITORY") != policy["repository"]
                or os.environ.get("GITHUB_SHA") != args.trusted_base
                or os.environ.get("GITHUB_REF") != f"refs/heads/{policy['default_branch']}"
            ):
                raise PortError("Check publication is available only in the deployed default-branch workflow")
            result = native.publish(args.wp, args.pr, args.review_id,
                                    int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"]))
        else:
            value, result = native.evaluate(args.wp, args.pr, args.review_id)
            result.update(state="project-acceptance-verified", merge_authorized=False,
                          binding=asdict(value.binding))
        print(json.dumps(result, indent=2))
        return 0
    except (PortError, OSError, ValueError, KeyError, UnicodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
