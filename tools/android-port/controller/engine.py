import uuid
from dataclasses import dataclass
from typing import Protocol

from .backends import Backend, Observation
from .capabilities import CapabilityEngine
from .errors import PortError, ReceiptError
from .gates import Binding, merge_decision, policy_revision, verify_completion
from .ledger import Identity, Ledger
from .model import Manifest, SHA
from .render import issue_payload
from .settings import Settings
from .schema import digest, positive_integer
from .verification import require_shared_whole_lane


@dataclass(frozen=True)
class GateBundle:
    binding: Binding
    pr: dict
    evidence: dict
    review: dict
    approvals: list[dict]
    catalog: dict | None
    authoritative: bool

    def __post_init__(self):
        if not isinstance(self.binding, Binding) or type(self.authoritative) is not bool:
            raise PortError("Malformed gate binding/authority")
        if any(not isinstance(v, dict) for v in (self.pr, self.evidence, self.review)):
            raise PortError("Gate bundle PR/evidence/review must be structured objects")
        if not isinstance(self.approvals, list) or self.catalog is not None and not isinstance(self.catalog, dict):
            raise PortError("Malformed gate approval/catalog data")


@dataclass(frozen=True)
class TerminalMerge:
    repository: str
    work_package: str
    session_id: str
    pr_number: int
    base_sha: str
    head_sha: str
    merge_sha: str
    paths: tuple[str, ...]
    authoritative: bool

    def __post_init__(self):
        if (
            not all(isinstance(value, str) and value for value in (
                self.repository, self.work_package, self.session_id,
            ))
            or type(self.pr_number) is not int or self.pr_number < 1
            or SHA.fullmatch(self.base_sha) is None
            or SHA.fullmatch(self.head_sha) is None
            or SHA.fullmatch(self.merge_sha) is None
            or not isinstance(self.paths, tuple)
            or not self.paths or not all(isinstance(path, str) and path for path in self.paths)
            or tuple(sorted(set(self.paths))) != self.paths
            or type(self.authoritative) is not bool
        ):
            raise PortError("Malformed authoritative terminal merge")


class GateAuthority(Protocol):
    """Authenticate native review/check references or legacy publisher output, never candidate PASS files."""

    def bundle(self, wp_id: str, identity: Identity) -> GateBundle: ...
    def merged_terminal(
        self, wp_id: str, session_id: str, receipt: dict, expected_base_sha: str,
    ) -> TerminalMerge: ...


class Controller:
    def __init__(self, manifest: Manifest, policy: dict, settings: Settings, ledger: Ledger,
                 backend: Backend, authority: GateAuthority | None = None,
                 attempt_factory=lambda: str(uuid.uuid4())):
        self.manifest, self.policy, self.settings = manifest, policy, settings
        self.ledger, self.backend, self.authority = ledger, backend, authority
        self.capabilities = CapabilityEngine(policy)
        self.attempt_factory = attempt_factory

    def binding(self, wp_id: str, base_sha: str):
        return {
            "repository": self.policy["repository"], "work_package": wp_id, "base_sha": base_sha,
            "source_sha": self.manifest.data["reference"]["commit"],
            "manifest_sha256": self.manifest.sha256,
            "policy_revision": policy_revision(self.manifest, self.policy),
        }

    def completed(self, wp_id: str):
        record = self.ledger.get(wp_id)
        if record is None or record["state"] != "completed" or record["completion"] is None:
            raise PortError(f"{wp_id}: no verified merged-PR completion (closed issue/idle worker is insufficient)")
        if self.authority is None:
            raise PortError("Missing authenticated gate/evidence authority; completion cannot be reconciled")
        bundle = self.authority.bundle(wp_id, Identity.parse(record["identity"]))
        if not isinstance(bundle, GateBundle) or not bundle.authoritative:
            raise PortError("Unverified gate bundle; model-written PASS is not merge authority")
        proof = verify_completion(
            bundle.pr, bundle.evidence, bundle.review, bundle.approvals, bundle.binding,
            self.manifest, self.policy, bundle.catalog,
        )
        if proof != record["completion"]:
            raise PortError("Merged-PR completion evidence changed or is ambiguous")
        return proof

    def require_foundation(self):
        for wp_id in self.policy["foundation_required"]:
            self.completed(wp_id)

    def require_ready(self, wp_id: str):
        wp = self.manifest.wp(wp_id)
        if wp["supervised"]:
            raise PortError(f"{wp_id} is supervised bootstrap work; never automatically dispatched")
        if not wp["verification"]["configured"] or not wp["verification"]["commands"]:
            require_shared_whole_lane(self.manifest, wp)
        self.require_foundation()
        for dependency in wp["depends_on"]:
            self.completed(dependency)
        return wp

    def claim(self, wp_id: str, now: float, adopting: Identity | None = None):
        self.settings.require_live(self.policy, self.backend.name, self.backend.name == "cloud")
        wp = self.require_ready(wp_id)
        base = self.backend.current_base()
        self.backend.preflight(wp_id, base)
        usage = self.backend.usage(self.settings.budget, now)
        usage.validate(self.settings.budget, now)
        self.require_usage_scope(usage)
        tracked = {
            value for row in self.ledger.records() if row["state"] not in ("pending", "completed")
            for key, value in row["identity"].items() if key in ("task_id", "session_id") and value
        }
        adopted_ids = {
            value for key, value in (adopting.as_dict() if adopting else {}).items()
            if key in ("task_id", "session_id") and value
        }
        if set(usage.active_ids) - tracked - adopted_ids:
            raise PortError("Unleased/unknown active worker paths must be reconciled before live claims")
        admissions = self.capabilities.admit_scope(
            wp_id, wp["write_paths"], wp["write_paths"], ["modify"]
        )
        capabilities = {}
        for admission in admissions:
            capabilities.setdefault(admission.capability_id, set()).add(admission.path)
        return self.ledger.claim(
            wp, self.binding(wp_id, base), self.backend.name, self.attempt_factory(), now,
            self.settings.lease_seconds, self.settings.max_inflight,
            usage.used, self.settings.budget.limit, self.settings.budget.reserve_per_launch,
            tuple(i for i in usage.active_ids if i not in adopted_ids),
            capabilities={key: sorted(value) for key, value in capabilities.items()},
            operations=["modify"],
            branch=getattr(self.backend, "branch", ""),
            worktree=getattr(self.backend, "worktree", ""),
            initial_identity=adopting,
        )

    def evolve_reservation(self, wp_id: str, paths: list[str], operations: list[str],
                           expected_revision: int, overlap_intents=(), *, attempt: str):
        """Admit and evolve the same owner; authorization prose remains unchanged."""
        record = self.ledger.get(wp_id)
        if record is None or record["attempt"] != attempt:
            raise PortError("Capability evolution requires the caller's current reservation attempt")
        wp = self.manifest.wp(wp_id)
        admissions = self.capabilities.admit_scope(wp_id, wp["write_paths"], paths, operations)
        capabilities = {}
        for admission in admissions:
            capabilities.setdefault(admission.capability_id, []).append(admission.path)
        return self.ledger.evolve_scope(
            wp_id, attempt, expected_revision, capabilities, operations, paths,
            overlap_intents, record["authorization_context"],
        )

    def admit_successor_attempt(
        self, wp_id: str, predecessor_attempt: str, predecessor_revision: int,
        identity: Identity, now: float,
    ):
        """Admit one current-base attempt only after server and worker terminal reconciliation."""
        if (
            not isinstance(identity, Identity) or identity.session_id is None
            or self.backend.name != self.settings.mode
        ):
            raise PortError("Successor requires the configured backend and an actual new session identity")
        predecessor = self.ledger.get_attempt(wp_id, predecessor_attempt)
        if predecessor is None or predecessor["revision"] != predecessor_revision:
            raise PortError("Missing or stale predecessor attempt")
        if self.authority is None:
            raise PortError("Successor admission requires authenticated merged-PR authority")
        previous_identity = Identity.parse(predecessor["identity"])
        if previous_identity.session_id is None:
            raise PortError("Successor predecessor has no actual session identity")
        receipt = self.ledger.terminal_receipt_for_session(previous_identity.session_id)
        if receipt is None:
            raise PortError("Successor admission requires an immutable terminal merge receipt")
        observation = self.backend.reconcile(wp_id, previous_identity)
        if (
            not observation.authoritative or observation.ambiguous or observation.pr_open
            or observation.identity.session_id != previous_identity.session_id
            or any(
                getattr(previous_identity, key) is not None
                and getattr(observation.identity, key) != getattr(previous_identity, key)
                for key in ("issue_number", "task_id", "pr_number")
            )
            or observation.worker_state not in {"completed", "failed", "timed_out", "cancelled"}
        ):
            raise PortError("Successor predecessor worker is active, ambiguous, or not authoritatively terminal")
        terminal = self.authority.merged_terminal(
            wp_id, previous_identity.session_id, receipt, predecessor["binding"]["base_sha"],
        )
        if (
            not isinstance(terminal, TerminalMerge) or not terminal.authoritative
            or terminal.repository != self.policy["repository"]
            or terminal.work_package != wp_id
            or terminal.session_id != previous_identity.session_id
            or terminal.pr_number != receipt["pr_number"]
            or terminal.base_sha != predecessor["binding"]["base_sha"]
            or terminal.head_sha != receipt["head_sha"]
            or terminal.merge_sha != receipt["merge_sha"]
            or list(terminal.paths) != receipt["paths"]
        ):
            raise PortError("Server-side merged terminal facts disagree with the immutable receipt")
        wp = self.manifest.wp(wp_id)
        base = self.backend.current_base()
        if SHA.fullmatch(base) is None:
            raise PortError("Successor current base is not an immutable commit")
        self.backend.preflight(wp_id, base)
        binding = self.binding(wp_id, base)
        admissions = self.capabilities.admit_scope(
            wp_id, wp["write_paths"], wp["write_paths"], ["modify"]
        )
        capabilities = {}
        for admission in admissions:
            capabilities.setdefault(admission.capability_id, set()).add(admission.path)
        successor_attempt = "successor-" + digest({
            "predecessor_attempt": predecessor_attempt,
            "binding": binding,
            "backend": self.backend.name,
            "identity": identity.as_dict(),
        })
        terminal_proof = {
            "authoritative": terminal.authoritative,
            "worker_terminal": True,
            "repository": terminal.repository,
            "work_package": terminal.work_package,
            "session_id": terminal.session_id,
            "pr_number": terminal.pr_number,
            "base_sha": terminal.base_sha,
            "head_sha": terminal.head_sha,
            "merge_sha": terminal.merge_sha,
            "paths": list(terminal.paths),
        }
        return self.ledger.admit_successor_attempt(
            wp, predecessor_attempt, predecessor_revision, previous_identity,
            receipt, terminal_proof, binding, self.backend.name, successor_attempt,
            identity, now, self.settings.lease_seconds, self.settings.budget.reserve_per_launch,
            {key: sorted(value) for key, value in capabilities.items()},
            ["modify"], wp["write_paths"],
            getattr(self.backend, "branch", ""), getattr(self.backend, "worktree", ""),
        )

    def launch(self, wp_id: str, now: float):
        self.settings.require_live(self.policy, self.backend.name, self.backend.name == "cloud")
        self.require_ready(wp_id)
        existing = self.ledger.get(wp_id)
        known = Identity.parse(existing["identity"]) if existing else Identity()
        observation = self.backend.reconcile(wp_id, known)
        if not observation.authoritative:
            raise PortError("Backend reconciliation is not authoritative")
        known = known.reconcile(observation.identity)
        if observation.ambiguous:
            if existing and existing["state"] != "completed":
                self.ledger.transition(wp_id, existing["attempt"], "uncertain", observation.identity)
            raise PortError(observation.reason or "Ambiguous existing execution; do not launch a duplicate")
        if existing and existing["state"] == "completed":
            return {"action": "reused-completion", "proof": self.completed(wp_id)}
        if existing and observation.worker_state != "absent" and (known.task_id or known.session_id):
            expected = self.binding(wp_id, existing["binding"]["base_sha"])
            if existing["binding"] != expected or existing["backend"] != self.backend.name:
                raise PortError("Existing execution has stale source/manifest/policy/backend binding")
            self.observe(wp_id, existing["attempt"], observation)
            return {"action": "reconciled-existing-worker", "identity": observation.identity.as_dict(),
                    "worker_state": observation.worker_state}
        record, acquired = self.claim(wp_id, now, known)
        known = Identity.parse(record["identity"]).reconcile(observation.identity)
        if observation.worker_state != "absent":
            if not (known.task_id or known.session_id):
                self.ledger.transition(wp_id, record["attempt"], "uncertain", known)
                raise PortError("Existing execution lacks a task/session receipt")
            self.observe(wp_id, record["attempt"], observation)
            return {"action": "reused-existing-worker", "identity": known.as_dict(),
                    "worker_state": observation.worker_state}
        if not acquired and record["state"] != "leased":
            self.ledger.transition(wp_id, record["attempt"], "uncertain")
            raise PortError("Persisted prior dispatch has no authoritative worker; do not repeat the launch")
        if not acquired and any(v is not None for v in record["identity"].values()):
            raise PortError("Previously bound worker identities cannot be forgotten")
        # Persist intention AND known issue identity before the potentially non-idempotent API call.
        self.ledger.transition(wp_id, record["attempt"], "dispatching", known)
        try:
            receipt = self.backend.launch(wp_id, record["attempt"])
            if not isinstance(receipt, Observation) or not receipt.authoritative or receipt.ambiguous:
                raise PortError("Unverified/ambiguous backend launch receipt")
            if receipt.worker_state == "assignment_accepted":
                self.ledger.transition(wp_id, record["attempt"], "dispatching", receipt.identity)
                return {"action": "assignment-accepted-task-unconfirmed", "identity": receipt.identity.as_dict()}
            self.observe(wp_id, record["attempt"], receipt)
            return {"action": "worker-receipt-confirmed", "identity": receipt.identity.as_dict(),
                    "worker_state": receipt.worker_state}
        except PortError as error:
            identity = error.identity if isinstance(error, ReceiptError) else None
            self.ledger.transition(wp_id, record["attempt"], "uncertain", identity)
            raise

    def require_usage_scope(self, usage):
        required = {self.backend.name} | {
            row["backend"] for row in self.ledger.records()
            if row["backend"] in ("cloud", "local") and row["state"] != "pending"
        }
        if not required.issubset(usage.covered_backends):
            raise PortError("Cross-backend usage is incomplete; obtain an authenticated aggregate meter before switching/mixing modes")

    def observe(self, wp_id: str, attempt: str, observation: Observation):
        if observation.ambiguous or not observation.authoritative:
            raise PortError("Ambiguous/non-authoritative execution observation")
        if not (observation.identity.task_id or observation.identity.session_id):
            raise PortError("Missing actual worker identity")
        if observation.worker_state == "completed":
            if not observation.identity.pr_number:
                self.ledger.transition(wp_id, attempt, "blocked", observation.identity)
                raise PortError("Completed worker has no implementation PR; WP acceptance remains blocked")
            target = "review_wait"
        elif observation.worker_state in ("queued", "in_progress", "idle", "waiting_for_user"):
            target = "running"
        elif observation.worker_state in ("failed", "timed_out", "cancelled"):
            target = "blocked"
        else:
            raise PortError("Unknown worker execution state")
        self.ledger.transition(wp_id, attempt, target, observation.identity)

    def repair(self, wp_id: str, feedback: str, now: float):
        self.settings.require_live(self.policy, self.backend.name, self.backend.name == "cloud")
        record = self.ledger.get(wp_id)
        if record is None:
            raise PortError("Repair requires an existing task/session/PR, never a new launch")
        known = Identity.parse(record["identity"])
        observation = self.backend.reconcile(wp_id, known)
        if observation.ambiguous or not observation.authoritative:
            raise PortError("Repair identity is uncertain")
        identity = known.reconcile(observation.identity)
        if (
            not observation.pr_open or identity.pr_number is None
            or observation.worker_state in ("absent", "unknown", "assignment_accepted")
            or self.backend.name == "local" and not identity.session_id
            or self.backend.name == "cloud" and not identity.task_id
        ):
            raise PortError("Repair requires an actual existing same worker and open implementation PR")
        expected = self.binding(wp_id, record["binding"]["base_sha"])
        if record["binding"] != expected:
            raise PortError("Repair requires reviewed current source/manifest/policy binding")
        current_base = self.backend.current_base()
        self.backend.preflight(wp_id, current_base)
        usage = self.backend.usage(self.settings.budget, now)
        usage.validate(self.settings.budget, now)
        self.require_usage_scope(usage)
        round_number = self.ledger.reserve_repair(
            wp_id, record["attempt"], self.policy["repair_limit"], usage.used,
            self.settings.budget.limit, self.settings.budget.reserve_per_launch, current_base,
        )
        try:
            receipt = self.backend.repair(identity, feedback)
        except PortError:
            self.ledger.transition(wp_id, record["attempt"], "uncertain")
            raise
        return {"action": "repair-feedback-delivered", "round": round_number,
                "identity": identity.as_dict(), "receipt": receipt}

    def release(self, wp_id: str):
        record = self.ledger.get(wp_id)
        if record is None:
            raise PortError("No existing lease to release")
        observation = self.backend.reconcile(wp_id, Identity.parse(record["identity"]))
        if observation.ambiguous:
            raise PortError("Ambiguous worker identity blocks lease release")
        self.ledger.release(
            wp_id, record["attempt"], observation.identity, observation.worker_state,
            observation.pr_open, observation.authoritative,
        )
        return {"action": "terminal-lease-released", "work_package": wp_id}

    def record_completion(self, wp_id: str):
        record = self.ledger.get(wp_id)
        if record is None or self.authority is None:
            raise PortError("Completion requires existing identity and an authenticated gate authority")
        observation = self.backend.reconcile(wp_id, Identity.parse(record["identity"]))
        if observation.ambiguous or not observation.authoritative or observation.worker_state != "completed":
            raise PortError("Only an actually completed existing worker can enter acceptance; idle is insufficient")
        bundle = self.authority.bundle(wp_id, Identity.parse(record["identity"]))
        if not isinstance(bundle, GateBundle) or not bundle.authoritative:
            raise PortError("Candidate/offline JSON cannot authorize completion")
        proof = verify_completion(
            bundle.pr, bundle.evidence, bundle.review, bundle.approvals, bundle.binding,
            self.manifest, self.policy, bundle.catalog,
        )
        self.ledger.complete(wp_id, record["attempt"], proof, authoritative=True)
        self.ledger.recover_merge({
            "work_package": wp_id, "pr_number": proof["pr_number"], "action": "merge",
            "attempt": record["attempt"], "reservation_revision": record["revision"],
            "binding": record["binding"],
            "expected_head_sha": proof["binding"]["head_sha"], "base_sha": proof["binding"]["base_sha"],
            "ci_run_ids": proof["ci_run_ids"], "approval_id": proof["approval_id"],
            "policy_revision": proof["binding"]["policy_revision"],
        }, {"merged": True, "sha": proof["merge_commit_sha"]}, authoritative=True)
        return proof

    def import_supervised_completion(self, wp_id: str, identity: Identity):
        if not self.manifest.wp(wp_id)["supervised"] or self.authority is None:
            raise PortError("Only supervised foundation receipts can be explicitly imported")
        bundle = self.authority.bundle(wp_id, identity)
        if not bundle.authoritative or bundle.pr["number"] != identity.pr_number:
            raise PortError("Supervised receipt is unverified or belongs to another PR")
        proof = verify_completion(
            bundle.pr, bundle.evidence, bundle.review, bundle.approvals, bundle.binding,
            self.manifest, self.policy, bundle.catalog,
        )
        self.ledger.import_supervised(self.manifest.wp(wp_id), proof, identity, authoritative=True)
        return proof

    def sync_issue(self, wp_id: str, confirm: bool):
        self.settings.require_live(self.policy, self.backend.name)
        if not confirm:
            raise PortError("Issue synchronization is a separate explicitly confirmed live side effect")
        self.require_foundation()
        if self.backend.name != "cloud" or not hasattr(self.backend, "find_issue"):
            raise PortError("Issue synchronization requires the isolated GitHub issues adapter")
        existing = self.backend.find_issue(wp_id)
        if existing:
            return {"action": "reused-existing-issue", "issue_number": existing["number"]}
        payload = issue_payload(self.manifest, self.policy, wp_id)
        key = f"issue-sync:{wp_id}:{self.manifest.sha256}"
        identity, new = self.ledger.begin_operation(key, payload)
        if not new:
            raise PortError("Previously confirmed issue disappeared; inspect instead of recreating")
        result = self.backend.api.request("POST", self.backend.prefix + "/issues", payload)
        if result.get("body") != payload["body"] or not isinstance(result.get("number"), int):
            raise PortError("Issue creation receipt is missing or mismatched; do not retry")
        positive_integer(result["number"], "Actual created issue number")
        identity = {"issue_number": result["number"]}
        self.ledger.confirm_operation(key, identity)
        return {"action": "issue-created", **identity}

    def decide_merge(self, wp_id: str):
        self.settings.require_live(self.policy, self.backend.name, self.backend.name == "cloud")
        if self.authority is None:
            raise PortError("Isolated review/check authority is unavailable")
        record = self.ledger.get(wp_id)
        if record is None:
            raise PortError("Merge candidate has no existing lease/identity")
        bundle = self.authority.bundle(wp_id, Identity.parse(record["identity"]))
        if not bundle.authoritative:
            raise PortError("Unverified merge evidence")
        for key in ("repository", "work_package", "base_sha", "source_sha", "manifest_sha256", "policy_revision"):
            if getattr(bundle.binding, key) != record["binding"][key]:
                raise PortError("Merge evidence disagrees with existing all-write-path lease binding")
        return merge_decision(
            bundle.pr, bundle.evidence, bundle.review, bundle.approvals, bundle.binding,
            self.manifest, self.policy, self.backend.current_base(), bundle.catalog,
        )

    def merge(self, wp_id: str, merge_backend):
        record = self.ledger.get(wp_id)
        decision = self.decide_merge(wp_id)
        if record is None or self.ledger.get(wp_id) != record:
            raise PortError("Merge reservation changed during gate evaluation; re-evaluate current attempt")
        identity = Identity.parse(record["identity"])
        observation = self.backend.reconcile(wp_id, identity)
        if observation.ambiguous or not observation.authoritative or observation.worker_state != "completed":
            raise PortError("Only a completed, reconciled worker may be marked ready")
        payload = {"work_package": wp_id, "pr_number": identity.pr_number, **decision,
                   "attempt": record["attempt"], "reservation_revision": record["revision"],
                   "binding": record["binding"],
                   "policy_revision": record["binding"]["policy_revision"]}
        receipt, acquired = self.ledger.begin_merge(payload)
        if not acquired:
            return {"action": "reconciled-real-merge", "receipt": receipt,
                    "completion": self.record_completion(wp_id)}
        self.ledger.transition(wp_id, record["attempt"], "merge_wait")
        try:
            receipt = merge_backend.merge_exact(
                identity.pr_number, decision["expected_head_sha"], decision["base_sha"]
            )
        except PortError:
            self.ledger.transition(wp_id, record["attempt"], "uncertain")
            raise
        self.ledger.confirm_operation("merge-lane", receipt)
        return {"action": "real-merge-confirmed", "receipt": receipt,
                "completion": self.record_completion(wp_id)}
