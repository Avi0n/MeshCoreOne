"""AndroidOnly: WP-003 Versioned migration proposals; historical acceptance stays BLOCKED."""

from .errors import PortError
from .gates import Binding
from .model import SHA, SUPERVISED
from .schema import digest, fields, positive_integer


def migration_proposal(request: dict, actual_pr: dict, actual_runs: list[dict], current: Binding):
    fields(request, {
        "schema_version", "kind", "original_binding", "current_binding", "pr_number",
        "merge_commit_sha", "original_runs",
    }, label="protected historical migration request")
    if type(request["schema_version"]) is not int or request["schema_version"] != 1 or request["kind"] != "supervised-foundation-revalidation":
        raise PortError("Unsupported protected historical migration version/kind")
    original = Binding.parse(request["original_binding"])
    if Binding.parse(request["current_binding"]) != current or original.work_package not in SUPERVISED:
        raise PortError("Historical migration is stale or not a supervised foundation WP")
    if (
        original.repository != current.repository or original.work_package != current.work_package
        or original.source_sha != current.source_sha
    ):
        raise PortError("Historical repository/WP/source binding mismatch")
    positive_integer(request["pr_number"], "Historical implementation PR")
    if not isinstance(request["merge_commit_sha"], str) or SHA.fullmatch(request["merge_commit_sha"]) is None:
        raise PortError("Historical migration requires a genuine merge, never an open dependent draft")
    if (
        actual_pr.get("number") != request["pr_number"] or actual_pr.get("merged") is not True
        or actual_pr.get("repository") != original.repository
        or actual_pr.get("base_sha") != original.base_sha or actual_pr.get("head_sha") != original.head_sha
        or actual_pr.get("merge_commit_sha") != request["merge_commit_sha"]
    ):
        raise PortError("Historical PR/base/head/merge server facts mismatch")
    if not isinstance(request["original_runs"], list) or not request["original_runs"]:
        raise PortError("Actual originally executed historical evidence is missing")
    by_id = {run.get("id"): run for run in actual_runs}
    if len(by_id) != len(actual_runs):
        raise PortError("Duplicate historical execution identity")
    seen = set()
    for claimed in request["original_runs"]:
        fields(claimed, {"run_id", "run_attempt", "workflow_id", "event", "head_sha", "checks"}, label="original execution")
        run_id = positive_integer(claimed["run_id"], "Original executed run")
        if run_id in seen:
            raise PortError("Duplicate original execution")
        seen.add(run_id)
        actual = by_id.get(run_id)
        if (
            actual is None or actual.get("repository") != original.repository
            or actual.get("head_sha") != original.head_sha or claimed["head_sha"] != original.head_sha
            or actual.get("workflow_id") != claimed["workflow_id"] or actual.get("run_attempt") != claimed["run_attempt"]
            or actual.get("event") != claimed["event"] or claimed["event"] not in ("pull_request", "push", "workflow_dispatch")
            or actual.get("status") != "completed" or actual.get("conclusion") != "success"
            or not isinstance(claimed["checks"], list) or not claimed["checks"]
            or actual.get("checks") != claimed["checks"]
        ):
            raise PortError("Invented/stale/skipped historical execution/check provenance")
        positive_integer(claimed["workflow_id"], "Original workflow identity")
        positive_integer(claimed["run_attempt"], "Original run attempt")
        for check in claimed["checks"]:
            fields(check, {"id", "name", "app_id", "head_sha", "conclusion"}, label="original check")
            positive_integer(check["id"], "Original check identity")
            positive_integer(check["app_id"], "Original check publisher")
            if check["head_sha"] != original.head_sha or check["conclusion"] != "success":
                raise PortError("Historical check belongs to a different/unexecuted head")
    return {
        "schema_version": 1, "request_sha256": digest(request), "state": "BLOCKED",
        "imported": False, "authoritative": False,
        "blockers": [
            "Protected historical migration authority and authorized current-policy human acceptance are unconfigured.",
            "Original evidence must be obtained by the isolated server-fact reader, not a candidate JSON/model attestation.",
            "Current gate/catalog semantics must be independently revalidated without inventing later CI at historical commits.",
        ],
    }


def import_historical_receipt(proposal: dict):
    raise PortError("BLOCKED: protected historical receipt migration/import is not activated; a proposal is not approval or dependency completion")
