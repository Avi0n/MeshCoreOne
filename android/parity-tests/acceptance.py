"""AndroidOnly: WP-501 Read existing authenticated gate authority; never publish or self-approve."""

import audit
from controller.authority import GitHubGateAuthority
from controller.backends import CloudBackend, GitHubApi
from controller.engine import GateBundle
from controller.errors import PortError
from controller.gates import verify_pr_gates
from controller.ledger import Identity
from controller.schema import load_json
from controller.settings import Settings


def require_external_review(authority, identity, manifest, policy, *, base_sha, head_sha):
    bundle = authority.bundle("WP-501", identity)
    if not isinstance(bundle, GateBundle) or bundle.authoritative is not True:
        raise PortError("Candidate/local PASS is not independent acceptance authority")
    if (
        bundle.binding.work_package != "WP-501"
        or bundle.binding.base_sha != base_sha or bundle.binding.head_sha != head_sha
        or bundle.pr.get("number") != identity.pr_number
    ):
        raise PortError("Independent review belongs to another WP/PR/base/head")
    proof = verify_pr_gates(
        bundle.pr, bundle.evidence, bundle.review, bundle.approvals,
        bundle.binding, manifest, policy, bundle.catalog,
    )
    return {
        "acceptance": "PASS",
        "authority": "existing-authenticated-exact-candidate-gates",
        "pr_number": proof["pr_number"],
        "binding": proof["binding"],
        "ci_run_ids": proof["ci_run_ids"],
        "review_sha256": proof["review_sha256"],
    }


def github_review(pr_number, base_sha, head_sha, environment, repo=audit.REPO):
    manifest = audit.load_manifest(repo)
    policy = load_json(repo / "docs" / "android" / "automation-policy.json")
    if not policy["trusted_gate_publisher_app_id"]:
        raise PortError("Existing trusted gate publisher is unconfigured; coordinator acceptance is not inferred")
    settings = Settings.from_env(environment, policy)
    if not settings.token:
        raise PortError("Read-only review lookup requires explicit ANDROID_PORT_GITHUB_TOKEN")
    backend = CloudBackend(GitHubApi(settings.token), manifest, policy, settings)
    return require_external_review(
        GitHubGateAuthority(backend, manifest, policy), Identity(pr_number=pr_number),
        manifest, policy, base_sha=base_sha, head_sha=head_sha,
    )
