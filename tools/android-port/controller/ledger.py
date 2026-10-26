import json
import math
import re
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from .errors import PortError
from .model import SHA
from .paths import conflicts, git_path
from .schema import decode_json, digest, nonempty, positive_integer

STATES = {
    "pending", "leased", "dispatching", "running", "repair_wait", "review_wait", "gate_wait",
    "merge_wait", "completed", "blocked", "uncertain",
}
HELD = STATES - {"pending", "completed"}
TRANSITIONS = {
    "pending": {"leased"},
    "leased": {"dispatching", "running", "pending", "blocked", "uncertain"},
    "dispatching": {"running", "review_wait", "uncertain", "blocked"},
    "running": {"repair_wait", "review_wait", "blocked", "uncertain"},
    "repair_wait": {"running", "review_wait", "blocked", "uncertain"},
    "review_wait": {"repair_wait", "gate_wait", "merge_wait", "blocked", "uncertain"},
    "gate_wait": {"repair_wait", "merge_wait", "blocked", "uncertain"},
    "merge_wait": {"completed", "repair_wait", "blocked", "uncertain"},
    "blocked": {"running", "review_wait", "pending", "uncertain"},
    "uncertain": {"running", "review_wait", "pending", "blocked"},
    "completed": set(),
}
HARD_LOCK_RESOURCES = {
    "installed-git-common-runtime", "canonical-ledger", "gradle-execution",
    "native-execution", "physical-device", "credentials-signing-release",
    "repository-settings",
    "disposable-native-execution",
}
EXECUTION_INTERLOCK_VERSION = 2
EXECUTION_RESOURCES = {"native-execution", "gradle-execution"}


@dataclass(frozen=True)
class Identity:
    issue_number: int | None = None
    task_id: str | None = None
    session_id: str | None = None
    pr_number: int | None = None

    def __post_init__(self):
        for value in (self.issue_number, self.pr_number):
            if value is not None:
                positive_integer(value, "Issue/PR identity")
        for value in (self.task_id, self.session_id):
            if value is not None:
                nonempty(value, "Task/session identity")

    def as_dict(self):
        return {
            "issue_number": self.issue_number, "task_id": self.task_id,
            "session_id": self.session_id, "pr_number": self.pr_number,
        }

    @classmethod
    def parse(cls, value: dict):
        from .schema import fields

        fields(value, {"issue_number", "task_id", "session_id", "pr_number"}, label="worker identity")
        return cls(**value)

    def reconcile(self, other):
        result = {}
        for key, previous in self.as_dict().items():
            current = other.as_dict()[key]
            if previous is not None and current is not None and previous != current:
                raise PortError(f"Conflicting existing {key}; do not launch a duplicate")
            result[key] = previous if previous is not None else current
        return Identity(**result)


class Ledger:
    """One durable SQLite ledger shared by both backends, not an Actions runner cache."""

    def __init__(self, path: Path, repository: str):
        self.path = path
        self.repository = repository
        if not path.parent.is_dir():
            raise PortError("Shared ledger parent must already exist and be access-controlled")
        with self.transaction() as connection:
            connection.execute("""
                CREATE TABLE IF NOT EXISTS leases (
                    repository TEXT NOT NULL, wp TEXT NOT NULL, state TEXT NOT NULL,
                    attempt TEXT NOT NULL, binding TEXT NOT NULL, backend TEXT NOT NULL,
                    paths TEXT NOT NULL, expires REAL NOT NULL, reservation REAL NOT NULL,
                    identity TEXT NOT NULL, repairs INTEGER NOT NULL, completion TEXT,
                    PRIMARY KEY (repository, wp)
                )
            """)
            connection.execute("""
                CREATE TABLE IF NOT EXISTS operations (
                    repository TEXT NOT NULL, key TEXT NOT NULL, state TEXT NOT NULL,
                    payload_hash TEXT NOT NULL, identity TEXT,
                    PRIMARY KEY (repository, key)
                )
            """)
            columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(leases)")
            }
            additions = {
                "record_state": "TEXT NOT NULL DEFAULT 'legacy-advisory'",
                "capabilities": "TEXT NOT NULL DEFAULT '{}'",
                "reservation_operations": "TEXT NOT NULL DEFAULT '[]'",
                "overlapping_intents": "TEXT NOT NULL DEFAULT '[]'",
                "authorization_context": "TEXT NOT NULL DEFAULT '{}'",
                "migration_revision": "TEXT NOT NULL DEFAULT ''",
                "revision": "INTEGER NOT NULL DEFAULT 1",
                "branch": "TEXT NOT NULL DEFAULT ''",
                "worktree": "TEXT NOT NULL DEFAULT ''",
                "predecessor_attempt": "TEXT",
                "predecessor_receipt_sha256": "TEXT",
            }
            for name, definition in additions.items():
                if name not in columns:
                    connection.execute(f"ALTER TABLE leases ADD COLUMN {name} {definition}")
            connection.execute("""
                CREATE TABLE IF NOT EXISTS lease_history AS
                SELECT * FROM leases WHERE 0
            """)
            connection.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS lease_history_attempt
                ON lease_history(repository,wp,attempt)
            """)
            connection.execute("""
                CREATE TABLE IF NOT EXISTS successor_transitions (
                    repository TEXT NOT NULL, wp TEXT NOT NULL,
                    predecessor_attempt TEXT NOT NULL, successor_attempt TEXT NOT NULL,
                    payload_hash TEXT NOT NULL, successor_record TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    PRIMARY KEY (repository,wp,predecessor_attempt),
                    UNIQUE (repository,wp,successor_attempt)
                )
            """)
            connection.execute("""
                CREATE TABLE IF NOT EXISTS hard_locks (
                    repository TEXT NOT NULL, resource TEXT NOT NULL,
                    owner TEXT NOT NULL, payload TEXT NOT NULL,
                    acquired REAL NOT NULL, PRIMARY KEY (repository, resource)
                )
            """)
            connection.execute("""
                CREATE TABLE IF NOT EXISTS intent_reconciliations (
                    repository TEXT NOT NULL, session_id TEXT NOT NULL,
                    pr_number INTEGER NOT NULL, head_sha TEXT NOT NULL,
                    merge_sha TEXT NOT NULL, paths TEXT NOT NULL,
                    reconciled_at REAL NOT NULL,
                    PRIMARY KEY (repository, session_id, pr_number)
                )
            """)

    @contextmanager
    def transaction(self):
        connection = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    @staticmethod
    def unpack(row):
        if row is None:
            return None
        value = dict(row)
        if value["state"] not in STATES:
            raise PortError("Unknown persisted execution state")
        for key in ("binding", "paths", "identity", "completion", "capabilities",
                    "reservation_operations", "overlapping_intents", "authorization_context"):
            if value[key] is not None:
                value[key] = decode_json(value[key])
        Identity.parse(value["identity"])
        return value

    def get(self, wp: str):
        with self.transaction() as connection:
            return self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp)
            ).fetchone())

    def records(self):
        with self.transaction() as connection:
            return [self.unpack(row) for row in connection.execute(
                "SELECT * FROM leases WHERE repository=? ORDER BY wp", (self.repository,)
            )]

    def get_attempt(self, wp: str, attempt: str):
        with self.transaction() as connection:
            row = connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=? AND attempt=?",
                (self.repository, wp, attempt),
            ).fetchone()
            if row is None:
                row = connection.execute(
                    "SELECT * FROM lease_history WHERE repository=? AND wp=? AND attempt=?",
                    (self.repository, wp, attempt),
                ).fetchone()
            return self.unpack(row)

    def historical_records(self, wp: str | None = None):
        with self.transaction() as connection:
            if wp is None:
                rows = connection.execute(
                    "SELECT * FROM lease_history WHERE repository=? ORDER BY wp,attempt",
                    (self.repository,),
                )
            else:
                rows = connection.execute(
                    "SELECT * FROM lease_history WHERE repository=? AND wp=? ORDER BY attempt",
                    (self.repository, wp),
                )
            return [self.unpack(row) for row in rows]

    def terminal_receipt(self, session_id: str, pr_number: int):
        with self.transaction() as connection:
            row = connection.execute("""
                SELECT * FROM intent_reconciliations
                WHERE repository=? AND session_id=? AND pr_number=?
            """, (self.repository, session_id, pr_number)).fetchone()
            if row is None:
                return None
            receipt = dict(row)
            receipt["paths"] = decode_json(receipt["paths"])
            receipt["record_state"] = "advisory-terminal-merged"
            return receipt

    def terminal_receipt_for_session(self, session_id: str):
        with self.transaction() as connection:
            rows = list(connection.execute("""
                SELECT * FROM intent_reconciliations
                WHERE repository=? AND session_id=? ORDER BY pr_number
            """, (self.repository, session_id)))
            if len(rows) > 1:
                raise PortError("Multiple terminal PR receipts for predecessor session are ambiguous")
            if not rows:
                return None
            receipt = dict(rows[0])
            receipt["paths"] = decode_json(receipt["paths"])
            receipt["record_state"] = "advisory-terminal-merged"
            return receipt

    def claim(self, wp: dict, binding: dict, backend: str, attempt: str, now: float,
              lease_seconds: int, max_inflight: int, used: float, limit: float, reservation: float,
              external_active_ids=(), *, capabilities=None, operations=None,
              authorization_context=None, branch=None, worktree=None,
              initial_identity: Identity | None = None):
        positive_integer(max_inflight, "Concurrency limit")
        positive_integer(lease_seconds, "Lease duration")
        if any(type(v) not in (int, float) or not math.isfinite(v) for v in (now, used, limit, reservation)):
            raise PortError("Lease time/usage terms must be finite numbers")
        if used < 0 or limit <= 0 or reservation <= 0:
            raise PortError("Lease usage terms are invalid")
        if not wp["write_paths"] or not attempt or backend not in ("cloud", "local"):
            raise PortError("Missing path lease/attempt/backend")
        with self.transaction() as connection:
            rows = [self.unpack(row) for row in connection.execute(
                "SELECT * FROM leases WHERE repository=?", (self.repository,)
            )]
            existing = next((row for row in rows if row["wp"] == wp["id"]), None)
            if existing and existing["state"] != "pending":
                if existing["binding"] != binding or existing["backend"] != backend:
                    raise PortError("Existing WP lease is bound to different source/base/manifest/backend")
                if existing["expires"] < now and existing["state"] in HELD:
                    connection.execute(
                        "UPDATE leases SET state='uncertain' WHERE repository=? AND wp=?",
                        (self.repository, wp["id"]),
                    )
                    # Commit uncertainty before returning; expiry must never free a worker's paths.
                    connection.commit()
                    raise PortError("Expired lease retains locks; reconcile authoritative worker/PR state")
                if existing["state"] in ("uncertain", "blocked"):
                    raise PortError("Existing WP state is uncertain/blocked; inspect, never relaunch")
                return existing, False
            held = [row for row in rows if row["state"] in HELD]
            identities = {
                value for row in held for key, value in row["identity"].items()
                if key in ("task_id", "session_id") and value
            }
            inflight = len(held) + len(set(external_active_ids) - identities)
            if inflight >= max_inflight:
                raise PortError("Concurrency limit reached")
            if used + sum(row["reservation"] for row in held) + reservation > limit:
                raise PortError("Usage budget/reservations exhausted")
            record = {
                "repository": self.repository, "wp": wp["id"], "state": "leased",
                "attempt": attempt, "binding": binding, "backend": backend,
                "paths": wp["write_paths"], "expires": now + lease_seconds,
                "reservation": reservation,
                "identity": (initial_identity or Identity()).as_dict(), "repairs": 0,
                "completion": None,
                "record_state": "advisory",
                "capabilities": capabilities or {"wp-owned": sorted(wp["write_paths"])},
                "reservation_operations": operations or ["modify"],
                "overlapping_intents": [
                    row["wp"] for row in held if conflicts(wp["write_paths"], row["paths"])
                ],
                "authorization_context": authorization_context or {},
                "migration_revision": "capability-reservation-v1",
                "revision": 1,
                "branch": branch or "",
                "worktree": worktree or "",
            }
            connection.execute("""
                INSERT OR REPLACE INTO leases
                (repository,wp,state,attempt,binding,backend,paths,expires,reservation,identity,repairs,completion,
                 record_state,capabilities,reservation_operations,overlapping_intents,authorization_context,
                 migration_revision,revision,branch,worktree)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, (
                self.repository, wp["id"], "leased", attempt, json.dumps(binding), backend,
                json.dumps(record["paths"]), record["expires"], reservation,
                json.dumps(record["identity"]), 0, None, record["record_state"],
                json.dumps(record["capabilities"]), json.dumps(record["reservation_operations"]),
                json.dumps(record["overlapping_intents"]), json.dumps(record["authorization_context"]),
                record["migration_revision"], record["revision"], record["branch"], record["worktree"],
            ))
            return record, True

    def evolve_scope(self, wp: str, attempt: str, expected_revision: int,
                     capabilities: dict, operations: list[str], paths: list[str],
                     overlap_intents=(), authorization_context=None, *, owner_context=None):
        """Idempotently evolve one owner's advisory scope with complete-record CAS."""
        if type(expected_revision) is not int or expected_revision < 1:
            raise PortError("Capability scope revision must be a positive integer")
        with self.transaction() as connection:
            row = self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp)
            ).fetchone())
            if row is None or row["attempt"] != attempt:
                raise PortError("Missing or stale capability reservation")
            if owner_context is not None and (
                not isinstance(owner_context, dict)
                or set(owner_context) != {"session_id", "branch", "worktree"}
                or any(not isinstance(value, str) or not value for value in owner_context.values())
                or owner_context != {
                    "session_id": row["identity"]["session_id"],
                    "branch": row["branch"], "worktree": row["worktree"],
                }
            ):
                raise PortError("Capability evolution caller differs from the stored session/branch/worktree")
            if row["revision"] != expected_revision:
                raise PortError(
                    f"Stale capability reservation revision {expected_revision}; current revision {row['revision']}"
                )
            merged_caps = {
                key: sorted(
                    set(row["capabilities"].get(key, []))
                    | set(capabilities.get(key, []))
                )
                for key in set(row["capabilities"]) | set(capabilities)
            }
            merged_ops = sorted(set(row["reservation_operations"]) | set(operations))
            merged_paths = sorted(set(row["paths"]) | set(paths))
            merged_overlaps = sorted(set(row["overlapping_intents"]) | set(overlap_intents))
            context = row["authorization_context"]
            if (
                merged_caps == row["capabilities"]
                and merged_ops == sorted(row["reservation_operations"])
                and merged_paths == sorted(row["paths"])
                and merged_overlaps == sorted(row["overlapping_intents"])
            ):
                return row
            updated = connection.execute("""
                UPDATE leases SET capabilities=?,reservation_operations=?,paths=?,
                    overlapping_intents=?,authorization_context=?,migration_revision=?,
                    revision=revision+1,record_state='advisory'
                WHERE repository=? AND wp=? AND revision=?
            """, (json.dumps(merged_caps), json.dumps(merged_ops), json.dumps(merged_paths),
                  json.dumps(merged_overlaps), json.dumps(context), "capability-reservation-v1",
                  self.repository, wp, expected_revision))
            if updated.rowcount != 1:
                raise PortError("Capability reservation changed during scope evolution; retry from current revision")
            return self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp)
            ).fetchone())

    def overlap_report(self):
        # The policy is intentionally not stored in the ledger; this report uses path identity only.
        records = self.records()
        result = []
        for index, left in enumerate(records):
            for right in records[index + 1:]:
                overlap = conflicts(left["paths"], right["paths"])
                if overlap:
                    result.append({
                        "left": left["wp"], "right": right["wp"],
                        "paths": [{"left": a, "right": b} for a, b in overlap],
                        "capabilities": sorted(
                            set(left["capabilities"]) & set(right["capabilities"])
                        ),
                        "advisory": True,
                    })
        return result

    def reconcile_merged_intent(self, session_id: str, pr_number: int, head_sha: str,
                                merge_sha: str, paths: list[str], reconciled_at: float):
        """Retain an immutable terminal merge receipt without deleting advisory intent history."""
        if (not session_id or type(pr_number) is not int or pr_number < 1
                or SHA.fullmatch(head_sha) is None or SHA.fullmatch(merge_sha) is None
                or not isinstance(paths, list) or not paths
                or not all(isinstance(path, str) and path for path in paths)
                or type(reconciled_at) not in (int, float) or not math.isfinite(reconciled_at)):
            raise PortError("Malformed authoritative terminal intent reconciliation")
        canonical_paths = sorted(set(paths))
        with self.transaction() as connection:
            existing = connection.execute("""
                SELECT * FROM intent_reconciliations
                WHERE repository=? AND session_id=? AND pr_number=?
            """, (self.repository, session_id, pr_number)).fetchone()
            receipt = {
                "repository": self.repository, "session_id": session_id,
                "pr_number": pr_number, "head_sha": head_sha, "merge_sha": merge_sha,
                "paths": canonical_paths, "reconciled_at": reconciled_at,
                "record_state": "advisory-terminal-merged",
            }
            if existing:
                persisted = dict(existing)
                persisted["paths"] = decode_json(persisted["paths"])
                for key in ("repository", "session_id", "pr_number", "head_sha", "merge_sha", "paths"):
                    if persisted[key] != receipt[key]:
                        raise PortError("Terminal intent receipt collision; preserved record differs")
                receipt["reconciled_at"] = persisted["reconciled_at"]
                return receipt, False
            connection.execute("""
                INSERT INTO intent_reconciliations
                (repository,session_id,pr_number,head_sha,merge_sha,paths,reconciled_at)
                VALUES (?,?,?,?,?,?,?)
            """, (
                self.repository, session_id, pr_number, head_sha, merge_sha,
                json.dumps(canonical_paths), reconciled_at,
            ))
            return receipt, True

    def intent_reconciliation_records(self):
        with self.transaction() as connection:
            result = []
            for row in connection.execute("""
                SELECT * FROM intent_reconciliations WHERE repository=?
                ORDER BY pr_number,session_id
            """, (self.repository,)):
                value = dict(row)
                value["paths"] = decode_json(value["paths"])
                value["record_state"] = "advisory-terminal-merged"
                result.append(value)
            return result

    def admit_successor_attempt(
        self, wp: dict, predecessor_attempt: str, predecessor_revision: int,
        predecessor_identity: Identity, receipt: dict, terminal: dict, binding: dict,
        backend: str, attempt: str, identity: Identity, now: float, lease_seconds: int,
        reservation: float, capabilities: dict, operations: list[str], paths: list[str],
        branch: str, worktree: str,
    ):
        """Atomically archive one terminal attempt and admit its bound successor."""
        if (
            not isinstance(wp, dict) or not isinstance(wp.get("id"), str) or not wp["id"]
            or not isinstance(predecessor_attempt, str) or not predecessor_attempt
            or type(predecessor_revision) is not int or predecessor_revision < 1
            or not isinstance(predecessor_identity, Identity)
            or not isinstance(identity, Identity)
            or not isinstance(attempt, str) or not attempt
            or attempt == predecessor_attempt
            or backend not in ("cloud", "local")
            or type(lease_seconds) is not int or lease_seconds < 1
            or type(now) not in (int, float) or not math.isfinite(now)
            or type(reservation) not in (int, float) or not math.isfinite(reservation)
            or reservation <= 0
            or not isinstance(paths, list) or not paths
            or not all(isinstance(path, str) and path for path in paths)
            or not isinstance(operations, list) or not operations
            or not all(isinstance(operation, str) and operation for operation in operations)
            or not isinstance(capabilities, dict)
            or not all(isinstance(key, str) and isinstance(value, list)
                       and all(isinstance(path, str) for path in value)
                       for key, value in capabilities.items())
            or not isinstance(branch, str) or not isinstance(worktree, str)
        ):
            raise PortError("Malformed successor-attempt admission")
        if identity.session_id is None or identity.session_id == predecessor_identity.session_id:
            raise PortError("Successor admission requires a distinct, actual session identity")
        if any(
            getattr(identity, key) is not None
            and getattr(identity, key) == getattr(predecessor_identity, key)
            for key in ("task_id", "session_id", "pr_number")
        ):
            raise PortError("Successor identity reuses a predecessor task, session, or PR identity")
        if set(binding) != {
            "repository", "work_package", "base_sha", "source_sha",
            "manifest_sha256", "policy_revision",
        }:
            raise PortError("Malformed successor binding")
        if (
            binding["repository"] != self.repository
            or binding["work_package"] != wp["id"]
            or any(not isinstance(binding[key], str) or SHA.fullmatch(binding[key]) is None for key in
                   ("base_sha", "source_sha"))
            or any(not isinstance(binding[key], str)
                   or re.fullmatch(r"[0-9a-f]{64}", binding[key]) is None for key in
                   ("manifest_sha256", "policy_revision"))
        ):
            raise PortError("Successor binding is stale or belongs to another WP/repository")
        paths = sorted({git_path(path, subtree=True) for path in paths})
        operations = sorted(set(operations))
        capabilities = {key: sorted(set(value)) for key, value in capabilities.items()}
        receipt_fields = (
            "repository", "session_id", "pr_number", "head_sha", "merge_sha", "paths",
        )
        if (
            not isinstance(receipt, dict)
            or any(key not in receipt for key in receipt_fields)
            or receipt["repository"] != self.repository
            or receipt["session_id"] != predecessor_identity.session_id
            or type(receipt["pr_number"]) is not int or receipt["pr_number"] < 1
            or not isinstance(receipt["head_sha"], str) or SHA.fullmatch(receipt["head_sha"]) is None
            or not isinstance(receipt["merge_sha"], str) or SHA.fullmatch(receipt["merge_sha"]) is None
            or not isinstance(receipt["paths"], list) or not receipt["paths"]
            or not all(isinstance(path, str) and path for path in receipt["paths"])
        ):
            raise PortError("Missing or malformed immutable terminal merge receipt")
        receipt = {**receipt, "paths": sorted(set(receipt["paths"]))}
        proof_fields = {
            "authoritative", "worker_terminal", "repository", "work_package",
            "session_id", "pr_number", "base_sha", "head_sha", "merge_sha", "paths",
        }
        if not isinstance(terminal, dict) or set(terminal) != proof_fields:
            raise PortError("Malformed authoritative terminal proof")
        if (
            terminal["authoritative"] is not True
            or terminal["worker_terminal"] is not True
            or terminal["repository"] != self.repository
            or terminal["work_package"] != wp["id"]
            or terminal["session_id"] != receipt["session_id"]
            or terminal["pr_number"] != receipt["pr_number"]
            or not isinstance(terminal["base_sha"], str)
            or SHA.fullmatch(terminal["base_sha"]) is None
            or terminal["head_sha"] != receipt["head_sha"]
            or terminal["merge_sha"] != receipt["merge_sha"]
            or not isinstance(terminal["paths"], list)
            or not all(isinstance(path, str) and path for path in terminal["paths"])
            or sorted(set(terminal["paths"])) != receipt["paths"]
        ):
            raise PortError("Terminal proof does not match the authoritative merged receipt")

        payload = {
            "predecessor_attempt": predecessor_attempt,
            "predecessor_revision": predecessor_revision,
            "predecessor_identity": predecessor_identity.as_dict(),
            "receipt": receipt,
            "terminal": terminal,
            "binding": binding,
            "backend": backend,
            "attempt": attempt,
            "identity": identity.as_dict(),
            "capabilities": capabilities,
            "operations": operations,
            "paths": paths,
            "lease_seconds": lease_seconds,
            "reservation": float(reservation),
            "branch": branch,
            "worktree": worktree,
        }
        payload_hash = digest(payload)
        with self.transaction() as connection:
            transition = connection.execute("""
                SELECT * FROM successor_transitions
                WHERE repository=? AND wp=? AND predecessor_attempt=?
            """, (self.repository, wp["id"], predecessor_attempt)).fetchone()
            if transition:
                if transition["payload_hash"] != payload_hash:
                    raise PortError("Successor transition already admitted with different identity or binding")
                return decode_json(transition["successor_record"]), False

            previous = self.unpack(connection.execute("""
                SELECT * FROM leases WHERE repository=? AND wp=?
            """, (self.repository, wp["id"])).fetchone())
            if (
                previous is None or previous["attempt"] != predecessor_attempt
                or previous["revision"] != predecessor_revision
                or previous["identity"] != predecessor_identity.as_dict()
            ):
                raise PortError("Stale predecessor attempt; reconcile from the current ledger revision")
            if previous["state"] not in HELD | {"completed"}:
                raise PortError("Successor predecessor has no admitted historical attempt")
            if terminal["base_sha"] != previous["binding"]["base_sha"]:
                raise PortError("Terminal merge does not bind the predecessor's exact base")
            stored_receipt = connection.execute("""
                SELECT * FROM intent_reconciliations
                WHERE repository=? AND session_id=? AND pr_number=?
            """, (self.repository, receipt["session_id"], receipt["pr_number"])).fetchone()
            if stored_receipt is None:
                raise PortError("Authoritative merged receipt is not present in the ledger")
            stored_receipt = dict(stored_receipt)
            stored_receipt["paths"] = decode_json(stored_receipt["paths"])
            if any(stored_receipt[key] != receipt[key] for key in receipt_fields):
                raise PortError("Immutable terminal receipt differs from the admitted proof")

            known_identities = []
            for row in connection.execute(
                "SELECT identity FROM leases WHERE repository=? AND wp<>?",
                (self.repository, wp["id"]),
            ):
                known_identities.append(Identity.parse(decode_json(row["identity"])))
            for row in connection.execute(
                "SELECT identity FROM lease_history WHERE repository=?",
                (self.repository,),
            ):
                known_identities.append(Identity.parse(decode_json(row["identity"])))
            for known in known_identities:
                for key in ("issue_number", "task_id", "session_id", "pr_number"):
                    value = getattr(identity, key)
                    if value is not None and value == getattr(known, key):
                        raise PortError(f"Successor identity collides with another attempt: {key}")

            predecessor_receipt_hash = digest(receipt)
            overlaps = sorted({
                row["wp"] for row in (
                    self.unpack(value) for value in connection.execute(
                        "SELECT * FROM leases WHERE repository=? AND wp<>?",
                        (self.repository, wp["id"]),
                    )
                )
                if row["state"] in HELD and conflicts(paths, row["paths"])
            })
            successor = {
                **previous,
                "state": "leased",
                "attempt": attempt,
                "binding": binding,
                "backend": backend,
                "paths": paths,
                "expires": now + lease_seconds,
                "reservation": float(reservation),
                "identity": identity.as_dict(),
                "repairs": 0,
                "completion": None,
                "record_state": "advisory-successor",
                "capabilities": capabilities,
                "reservation_operations": operations,
                "overlapping_intents": overlaps,
                "migration_revision": "successor-attempt-v1",
                "revision": 1,
                "branch": branch,
                "worktree": worktree,
                "predecessor_attempt": predecessor_attempt,
                "predecessor_receipt_sha256": predecessor_receipt_hash,
            }
            connection.execute("""
                INSERT INTO lease_history SELECT * FROM leases
                WHERE repository=? AND wp=? AND attempt=?
            """, (self.repository, wp["id"], predecessor_attempt))
            if connection.execute("SELECT changes()").fetchone()[0] != 1:
                raise PortError("Predecessor claim could not be archived atomically")
            connection.execute("""
                UPDATE leases SET
                    state=?,attempt=?,binding=?,backend=?,paths=?,expires=?,reservation=?,
                    identity=?,repairs=?,completion=?,record_state=?,capabilities=?,
                    reservation_operations=?,overlapping_intents=?,migration_revision=?,
                    revision=?,branch=?,worktree=?,predecessor_attempt=?,
                    predecessor_receipt_sha256=?
                WHERE repository=? AND wp=? AND attempt=? AND revision=?
            """, (
                successor["state"], successor["attempt"], json.dumps(successor["binding"]),
                successor["backend"], json.dumps(successor["paths"]), successor["expires"],
                successor["reservation"], json.dumps(successor["identity"]), successor["repairs"],
                None, successor["record_state"], json.dumps(successor["capabilities"]),
                json.dumps(successor["reservation_operations"]),
                json.dumps(successor["overlapping_intents"]), "successor-attempt-v1",
                successor["revision"], successor["branch"], successor["worktree"],
                predecessor_attempt, predecessor_receipt_hash,
                self.repository, wp["id"], predecessor_attempt, predecessor_revision,
            ))
            if connection.execute("SELECT changes()").fetchone()[0] != 1:
                raise PortError("Predecessor changed during successor admission")
            connection.execute("""
                INSERT INTO successor_transitions
                (repository,wp,predecessor_attempt,successor_attempt,payload_hash,successor_record,created_at)
                VALUES (?,?,?,?,?,?,?)
            """, (
                self.repository, wp["id"], predecessor_attempt, attempt, payload_hash,
                json.dumps(successor), now,
            ))
            return successor, True

    def migrate_legacy(self, capability_revision: str = "capability-reservation-v1"):
        """Upgrade old supervised receipts in place without release/recreate."""
        if not capability_revision:
            raise PortError("Capability migration revision is required")
        with self.transaction() as connection:
            rows = connection.execute(
                "SELECT * FROM leases WHERE repository=?", (self.repository,)
            ).fetchall()
            migrated = 0
            for raw in rows:
                row = self.unpack(raw)
                if row["record_state"] != "legacy-advisory":
                    continue
                capabilities = row["capabilities"] or {"wp-owned": sorted(row["paths"])}
                operations = row["reservation_operations"] or ["modify"]
                result = connection.execute("""
                    UPDATE leases SET record_state='advisory',capabilities=?,
                        reservation_operations=?,overlapping_intents=?,
                        migration_revision=?,revision=revision+1
                    WHERE repository=? AND wp=? AND revision=? AND binding=?
                        AND identity=? AND state=? AND repairs=? AND authorization_context=?
                """, (
                    json.dumps(capabilities), json.dumps(operations),
                    json.dumps(row["overlapping_intents"] or []), capability_revision,
                    self.repository, row["wp"], row["revision"], raw["binding"],
                    raw["identity"], raw["state"], raw["repairs"], raw["authorization_context"],
                ))
                if result.rowcount != 1:
                    raise PortError("Legacy reservation changed during migration; retry")
                migrated += 1
            legacy_table = connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='supervised_reservations'"
            ).fetchone()
            if legacy_table:
                for historical in connection.execute(
                    "SELECT receipt FROM supervised_reservations WHERE repository=? ORDER BY session_id",
                    (self.repository,),
                ).fetchall():
                    receipt_raw = historical["receipt"]
                    receipt = decode_json(receipt_raw)
                    required = {
                        "kind", "repository", "wp", "owner", "session_id", "branch",
                        "worktree", "binding", "paths", "authorization",
                    }
                    if set(receipt) != required or receipt["kind"] != "supervised-write-reservation":
                        raise PortError("Malformed historical supervised reservation")
                    if receipt["repository"] != self.repository:
                        raise PortError("Foreign historical supervised reservation")
                    if not isinstance(receipt["paths"], list) or not receipt["paths"]:
                        raise PortError("Malformed historical supervised reservation paths")
                    for path in receipt["paths"]:
                        from .paths import git_path
                        git_path(path, subtree=True)
                    existing = connection.execute(
                        "SELECT * FROM leases WHERE repository=? AND wp=?",
                        (self.repository, receipt["wp"]),
                    ).fetchone()
                    if existing:
                        row = self.unpack(existing)
                        context = row["authorization_context"]
                        if context.get("historical_receipt") != receipt_raw:
                            raise PortError(
                                f"Historical reservation collision for {receipt['wp']}; "
                                "reconcile the complete current record"
                            )
                        continue
                    identity = Identity(session_id=receipt["session_id"]).as_dict()
                    context = {
                        "historical_receipt": receipt_raw,
                        "authorization": receipt["authorization"],
                    }
                    connection.execute("""
                        INSERT INTO leases
                        (repository,wp,state,attempt,binding,backend,paths,expires,reservation,
                         identity,repairs,completion,record_state,capabilities,
                         reservation_operations,overlapping_intents,authorization_context,
                         migration_revision,revision,branch,worktree)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, (
                        self.repository, receipt["wp"], "leased", receipt["session_id"],
                        json.dumps(receipt["binding"]), "local", json.dumps(receipt["paths"]),
                        0.0, 1.0, json.dumps(identity), 0, None, "advisory",
                        json.dumps({"wp-owned": sorted(receipt["paths"])}),
                        json.dumps(["modify"]), json.dumps([]), json.dumps(context),
                        capability_revision, 1, receipt["branch"], receipt["worktree"],
                    ))
                    migrated += 1
            return migrated

    def acquire_hard_lock(self, resource: str, owner: str, payload: dict, now: float, *,
                          expected_existing=None):
        if not resource or not owner:
            raise PortError("Hard-lock resource and owner are required")
        if resource not in HARD_LOCK_RESOURCES:
            raise PortError(f"Unsupported external hard-lock resource: {resource}")
        if resource == "disposable-native-execution":
            raise PortError("Disposable execution requires a freshly allocated private guest, not a resource label")
        if not isinstance(payload, dict) or type(now) not in (int, float) or not math.isfinite(now):
            raise PortError("Malformed hard-lock payload or acquisition time")
        with self.transaction() as connection:
            private = connection.execute(
                "SELECT * FROM hard_locks WHERE repository=? AND resource='disposable-native-execution'",
                (self.repository,),
            ).fetchone()
            if private is not None and resource in EXECUTION_RESOURCES | {"installed-git-common-runtime"}:
                raise PortError("Private guest owns the host execution lane")
            if resource in EXECUTION_RESOURCES | {"installed-git-common-runtime"}:
                peers = connection.execute(
                    "SELECT resource,owner FROM hard_locks WHERE repository=?",
                    (self.repository,),
                ).fetchall()
                for peer in peers:
                    jvm_lane = peer["resource"].startswith("gradle-execution:")
                    same_execution_owner = (
                        resource in EXECUTION_RESOURCES and peer["resource"] in EXECUTION_RESOURCES
                        and peer["owner"] == owner
                    )
                    conflicts = (
                        resource in EXECUTION_RESOURCES
                        and (peer["resource"] in EXECUTION_RESOURCES | {"installed-git-common-runtime"} or jvm_lane)
                        or resource == "installed-git-common-runtime"
                        and (peer["resource"] in EXECUTION_RESOURCES or jvm_lane)
                    )
                    if conflicts and not same_execution_owner:
                        raise PortError(
                            f"Hard-lock collision for external resource {resource}: {peer['resource']} held"
                        )
            row = connection.execute(
                "SELECT * FROM hard_locks WHERE repository=? AND resource=?",
                (self.repository, resource),
            ).fetchone()
            if expected_existing is not None:
                actual = (
                    {**dict(row), "payload": decode_json(row["payload"]), "record_state": "hard"}
                    if row else None
                )
                if (
                    resource not in EXECUTION_RESOURCES or actual != expected_existing
                    or actual is None or actual["owner"] != owner or actual["payload"] != payload
                ):
                    raise PortError("Missing, stale or foreign retained execution receipt")
                return {"resource": resource, "owner": owner, "payload": actual["payload"], "acquired_new": False}
            if row and (
                row["owner"] != owner
                or resource in EXECUTION_RESOURCES | {"installed-git-common-runtime"}
            ):
                raise PortError(f"Hard-lock collision for external resource {resource}")
            connection.execute(
                "INSERT OR REPLACE INTO hard_locks VALUES (?,?,?,?,?)",
                (self.repository, resource, owner, json.dumps(payload), now),
            )
            return {"resource": resource, "owner": owner, "payload": payload, "acquired_new": True}

    def acquire_jvm_lane(self, owner: str, payload: dict, now: float):
        from .jvm_lanes import RESOURCE, host_capacity

        if not owner or not isinstance(payload, dict) or "jvm_lane" in payload:
            raise PortError("JVM execution requires a fresh owned payload")
        if type(now) not in (int, float) or not math.isfinite(now):
            raise PortError("Malformed JVM execution acquisition time")
        with self.transaction() as connection:
            host, capacity = host_capacity()
            records = connection.execute(
                "SELECT resource,owner FROM hard_locks WHERE repository=?", (self.repository,),
            ).fetchall()
            occupied = set()
            for record in records:
                resource = record["resource"]
                if resource in EXECUTION_RESOURCES | {"installed-git-common-runtime", "disposable-native-execution"}:
                    raise PortError(f"JVM lane waits for existing external resource {resource}")
                if resource.startswith("gradle-execution:"):
                    match = RESOURCE.fullmatch(resource)
                    if match is None:
                        raise PortError("Malformed existing JVM execution resource")
                    if record["owner"] == owner:
                        raise PortError("Existing JVM execution owner must reconcile its retained receipt")
                    if match[1] == host:
                        occupied.add(int(match[2]))
            if len(occupied) >= capacity:
                raise PortError("Measured host JVM capacity is fully reserved")
            slot = next(slot for slot in range(capacity) if slot not in occupied)
            resource = f"gradle-execution:{host}:{slot}"
            payload = {**payload, "jvm_lane": {"host": host, "slot": slot, "capacity": capacity}}
            connection.execute(
                "INSERT INTO hard_locks VALUES (?,?,?,?,?)",
                (self.repository, resource, owner, json.dumps(payload), now),
            )
            return {"resource": resource, "owner": owner, "payload": payload, "acquired_new": True}

    def release_hard_lock(self, resource: str, owner: str):
        if resource == "disposable-native-execution":
            raise PortError("Private guest release requires observed owned disposal")
        with self.transaction() as connection:
            if resource == "gradle-execution" and connection.execute(
                "SELECT 1 FROM hard_locks WHERE repository=? AND resource='disposable-native-execution'",
                (self.repository,),
            ).fetchone():
                raise PortError("Private guest host/Gradle guard releases only after observed disposal")
            result = connection.execute(
                "DELETE FROM hard_locks WHERE repository=? AND resource=? AND owner=?",
                (self.repository, resource, owner),
            )
            if result.rowcount != 1:
                raise PortError("Missing or foreign hard lock")

    @contextmanager
    def hard_lock(self, resource: str, owner: str, payload: dict, now: float, *, expected_existing=None):
        """Release only a newly acquired lock; explicit retained-receipt reentry borrows it."""
        lock = self.acquire_hard_lock(resource, owner, payload, now, expected_existing=expected_existing)
        try:
            yield lock
        finally:
            if lock["acquired_new"]:
                self.release_hard_lock(resource, owner)

    def acquire_disposable(self, guest, owner, now):
        """One host lane, independently allocated guest; never rewrite the legacy native row."""
        from .disposable import PrivateGuest

        if not isinstance(guest, PrivateGuest):
            raise PortError("Disposable ownership requires the allocated guest handle")
        payload = guest.fresh_payload(self, owner)
        if type(now) not in (int, float) or not math.isfinite(now):
            raise PortError("Malformed private guest acquisition time")
        with self.transaction() as connection:
            held = connection.execute(
                "SELECT resource FROM hard_locks WHERE repository=? AND (resource IN (?,?,?) OR resource LIKE 'gradle-execution:%')",
                (self.repository, "gradle-execution", "installed-git-common-runtime", "disposable-native-execution"),
            ).fetchall()
            if held:
                raise PortError("Private guest waits for the host/Gradle/runtime lane")
            legacy = connection.execute(
                "SELECT owner FROM hard_locks WHERE repository=? AND resource='native-execution'",
                (self.repository,),
            ).fetchone()
            if legacy is not None and legacy["owner"] == owner:
                raise PortError("Fresh guest needs a distinct execution owner, not the retained legacy owner")
            connection.execute(
                "INSERT INTO hard_locks VALUES (?,?,?,?,?)",
                (self.repository, "disposable-native-execution", owner, json.dumps(payload), now),
            )
            connection.execute(
                "INSERT INTO hard_locks VALUES (?,?,?,?,?)",
                (self.repository, "gradle-execution", owner, json.dumps({
                    "kind": "private-guest-host-guard", "token": guest.token, "revision": guest.revision,
                }), now),
            )
            return {
                "repository": self.repository, "resource": "disposable-native-execution",
                "owner": owner, "payload": json.dumps(payload), "acquired": now,
            }

    def finish_disposable(self, guest, owner):
        from .disposable import PrivateGuest

        if not isinstance(guest, PrivateGuest):
            raise PortError("Disposable ownership requires the allocated guest handle")
        guest.verify_disposed()
        with self.transaction() as connection:
            row = connection.execute(
                "SELECT * FROM hard_locks WHERE repository=? AND resource='disposable-native-execution'",
                (self.repository,),
            ).fetchone()
            guest.verify_lease(self, owner, dict(row) if row else None, disposed=True)
            gradle = connection.execute(
                "SELECT * FROM hard_locks WHERE repository=? AND resource='gradle-execution'",
                (self.repository,),
            ).fetchone()
            if gradle is None or guest.gradle_receipt != dict(gradle) or gradle["owner"] != owner:
                raise PortError("Private guest cannot finish with missing/foreign/stale host Gradle guard")
            connection.execute(
                "DELETE FROM hard_locks WHERE repository=? AND resource='gradle-execution' AND owner=?",
                (self.repository, owner),
            )
            connection.execute(
                "DELETE FROM hard_locks WHERE repository=? AND resource='disposable-native-execution' AND owner=?",
                (self.repository, owner),
            )

    def hard_lock_records(self):
        with self.transaction() as connection:
            return [
                {
                    **dict(row),
                    "payload": decode_json(row["payload"]),
                    "record_state": "hard",
                }
                for row in connection.execute(
                    "SELECT * FROM hard_locks WHERE repository=? ORDER BY resource",
                    (self.repository,),
                )
            ]

    def transition(self, wp: str, attempt: str, state: str, identity: Identity | None = None):
        if state not in STATES:
            raise PortError(f"Unknown execution state: {state}")
        if state in ("completed", "pending", "leased"):
            raise PortError("Claim/release/completion require their validated operations, not a generic transition")
        with self.transaction() as connection:
            row = self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp)
            ).fetchone())
            if row is None or row["attempt"] != attempt:
                raise PortError("Missing or stale lease attempt")
            if state != row["state"] and state not in TRANSITIONS[row["state"]]:
                raise PortError(f"Invalid execution transition {row['state']} -> {state}")
            merged = Identity.parse(row["identity"]).reconcile(identity) if identity else Identity.parse(row["identity"])
            connection.execute(
                "UPDATE leases SET state=?,identity=? WHERE repository=? AND wp=?",
                (state, json.dumps(merged.as_dict()), self.repository, wp),
            )

    def reserve_repair(self, wp: str, attempt: str, repair_limit: int, used: float, limit: float,
                       reservation: float, current_base: str | None = None):
        with self.transaction() as connection:
            row = self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp)
            ).fetchone())
            if row is None or row["attempt"] != attempt:
                raise PortError("Missing or stale repair identity")
            if row["state"] not in ("running", "review_wait", "gate_wait", "merge_wait"):
                raise PortError("Repair requires a reconciled existing worker, never an idle guess")
            if row["repairs"] >= repair_limit:
                connection.execute(
                    "UPDATE leases SET state='blocked' WHERE repository=? AND wp=?", (self.repository, wp)
                )
                connection.commit()
                raise PortError("Repair limit reached; needs-human (no additional launch)")
            total = connection.execute(
                "SELECT COALESCE(SUM(reservation),0) FROM leases WHERE repository=? AND state NOT IN ('pending','completed')",
                (self.repository,),
            ).fetchone()[0]
            if used + total + reservation > limit:
                raise PortError("Usage budget blocks repair execution")
            binding = row["binding"]
            if current_base is not None:
                from .model import SHA

                if SHA.fullmatch(current_base) is None:
                    raise PortError("Repair base must be a verified immutable SHA")
                binding = {**binding, "base_sha": current_base}
            connection.execute("""
                UPDATE leases SET state='repair_wait',repairs=repairs+1,reservation=reservation+?,binding=?,completion=NULL
                WHERE repository=? AND wp=?
            """, (reservation, json.dumps(binding), self.repository, wp))
            return row["repairs"] + 1

    def release(self, wp: str, attempt: str, identity: Identity, worker_state: str,
                pr_open: bool, authoritative: bool):
        if authoritative is not True or type(pr_open) is not bool or worker_state not in ("cancelled", "failed", "completed") or pr_open:
            raise PortError("Release requires authoritative terminal worker and no open implementation PR")
        with self.transaction() as connection:
            row = self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp)
            ).fetchone())
            if row is None or row["attempt"] != attempt or row["state"] == "completed":
                raise PortError("Missing/stale/completed lease cannot be released")
            previous = Identity.parse(row["identity"])
            previous.reconcile(identity)
            if previous.task_id and identity.task_id != previous.task_id or previous.session_id and identity.session_id != previous.session_id:
                raise PortError("Terminal proof omits the actual task/session identity")
            if not (identity.task_id or identity.session_id):
                raise PortError("Missing authoritative terminal worker identity")
            connection.execute(
                "UPDATE leases SET state='pending',reservation=0,completion=NULL WHERE repository=? AND wp=?",
                (self.repository, wp),
            )

    def complete(self, wp: str, attempt: str, proof: dict, authoritative: bool):
        if authoritative is not True:
            raise PortError("Offline/model-written evidence cannot authorize completion")
        with self.transaction() as connection:
            row = self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp)
            ).fetchone())
            if row is None or row["attempt"] != attempt:
                raise PortError("Missing/stale completion attempt")
            if row["state"] not in ("review_wait", "gate_wait", "merge_wait", "completed"):
                raise PortError("Worker execution has not reached verified review/merge state")
            if proof["binding"]["work_package"] != wp or proof["binding"]["repository"] != self.repository:
                raise PortError("Completion proof belongs to another WP/repository")
            for key in ("base_sha", "source_sha", "manifest_sha256", "policy_revision"):
                if proof["binding"][key] != row["binding"][key]:
                    raise PortError("Completion proof disagrees with source/base/manifest/policy lease")
            identity = Identity.parse(row["identity"])
            if identity.pr_number != proof["pr_number"]:
                raise PortError("Completion proof belongs to another implementation PR")
            if row["completion"] is not None and row["completion"] != proof:
                raise PortError("Conflicting completion evidence")
            connection.execute(
                "UPDATE leases SET state='completed',completion=?,reservation=0 WHERE repository=? AND wp=?",
                (json.dumps(proof), self.repository, wp),
            )

    def begin_operation(self, key: str, payload: dict):
        with self.transaction() as connection:
            row = connection.execute(
                "SELECT * FROM operations WHERE repository=? AND key=?", (self.repository, key)
            ).fetchone()
            if row:
                if row["payload_hash"] != digest(payload):
                    raise PortError("Operation identity reused for a different payload")
                if row["state"] != "confirmed":
                    raise PortError("Uncertain prior API mutation; reconcile instead of retrying")
                return decode_json(row["identity"]), False
            connection.execute(
                "INSERT INTO operations VALUES (?,?,?,?,NULL)",
                (self.repository, key, "intent", digest(payload)),
            )
            return None, True

    def confirm_operation(self, key: str, identity: dict):
        with self.transaction() as connection:
            result = connection.execute(
                "UPDATE operations SET state='confirmed',identity=? WHERE repository=? AND key=? AND state='intent'",
                (json.dumps(identity), self.repository, key),
            )
            if result.rowcount != 1:
                raise PortError("Missing or conflicting mutation intent")

    def begin_merge(self, payload: dict):
        with self.transaction() as connection:
            lease = self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?",
                (self.repository, payload.get("work_package")),
            ).fetchone())
            if (
                lease is None or payload.get("attempt") != lease["attempt"]
                or type(payload.get("reservation_revision")) is not int
                or payload["reservation_revision"] != lease["revision"]
                or type(payload.get("pr_number")) is not int
                or payload["pr_number"] != lease["identity"]["pr_number"]
                or payload.get("binding") != lease["binding"]
                or payload.get("base_sha") != lease["binding"].get("base_sha")
                or payload.get("policy_revision") != lease["binding"].get("policy_revision")
                or not isinstance(payload.get("expected_head_sha"), str)
                or SHA.fullmatch(payload["expected_head_sha"]) is None
                or lease["state"] not in ("review_wait", "gate_wait", "merge_wait", "completed")
            ):
                raise PortError("Merge intent disagrees with the current attempt/revision/PR/binding/state")
            row = connection.execute(
                "SELECT * FROM operations WHERE repository=? AND key='merge-lane'", (self.repository,)
            ).fetchone()
            if row and row["state"] != "confirmed":
                raise PortError("Serialized merge lane is in-flight/uncertain; reconcile rather than retry")
            if row and row["payload_hash"] == digest(payload):
                return decode_json(row["identity"]), False
            connection.execute(
                "INSERT OR REPLACE INTO operations VALUES (?,'merge-lane','intent',?,NULL)",
                (self.repository, digest(payload)),
            )
            return None, True

    def import_supervised(self, wp: dict, proof: dict, identity: Identity, authoritative: bool):
        if authoritative is not True or wp["id"] not in ("WP-000", "WP-001", "WP-002", "WP-003"):
            raise PortError("Only actually verified supervised foundation completions may be imported")
        if proof["pr_number"] != identity.pr_number or proof["binding"]["work_package"] != wp["id"]:
            raise PortError("Supervised receipt identity mismatch")
        if proof["binding"]["repository"] != self.repository:
            raise PortError("Supervised receipt repository mismatch")
        with self.transaction() as connection:
            existing = self.unpack(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?", (self.repository, wp["id"])
            ).fetchone())
            if existing:
                if existing["state"] == "completed" and existing["completion"] == proof:
                    return
                raise PortError("Existing supervised execution/receipt must be reconciled, not overwritten")
            binding = {k: v for k, v in proof["binding"].items() if k != "head_sha"}
            connection.execute("""
                INSERT INTO leases
                (repository,wp,state,attempt,binding,backend,paths,expires,reservation,identity,repairs,completion)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """, (
                self.repository, wp["id"], "completed", f"supervised-pr-{proof['pr_number']}",
                json.dumps(binding), "supervised", json.dumps(wp["write_paths"]), 0, 0,
                json.dumps(identity.as_dict()), 0, json.dumps(proof),
            ))

    def recover_merge(self, payload: dict, receipt: dict, authoritative: bool):
        from .model import SHA

        if authoritative is not True or receipt.get("merged") is not True or not isinstance(receipt.get("sha"), str):
            raise PortError("Merge recovery requires an actual verified merged-PR receipt")
        if SHA.fullmatch(receipt["sha"]) is None:
            raise PortError("Malformed actual merge commit")
        with self.transaction() as connection:
            row = connection.execute(
                "SELECT * FROM operations WHERE repository=? AND key='merge-lane'", (self.repository,)
            ).fetchone()
            if row is None or row["payload_hash"] != digest(payload):
                return False
            if row["state"] == "confirmed":
                return True
            if row["state"] != "intent":
                raise PortError("Unknown merge-lane state")
            connection.execute(
                "UPDATE operations SET state='confirmed',identity=? WHERE repository=? AND key='merge-lane'",
                (json.dumps(receipt), self.repository),
            )
            return True
