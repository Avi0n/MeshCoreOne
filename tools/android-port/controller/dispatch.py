import argparse
import json
import os
import sqlite3
import sys
import time
from contextlib import closing
from dataclasses import asdict
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.authority import GitHubGateAuthority
from controller.backends import CloudBackend, GitHubApi
from controller.engine import Controller
from controller.errors import PortError
from controller.gates import Binding, verify_completion
from controller.ledger import Identity, Ledger
from controller.model import load_manifest
from controller.render import cloud_payload, input_page, issue_payload, local_payload, render
from controller.schema import decode_json, load_json
from controller.settings import Settings


def ledger_snapshot(settings, repository):
    if settings.ledger_path is None or not settings.ledger_path.is_file():
        return {}
    # Read-only commands never initialize or mutate a ledger.
    uri = settings.ledger_path.resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        try:
            rows = connection.execute(
                "SELECT * FROM leases WHERE repository=? ORDER BY wp", (repository,)
            ).fetchall()
        except sqlite3.Error as error:
            raise PortError("Existing ledger schema is missing or unreadable") from error
        return {row["wp"]: Ledger.unpack(row) for row in rows}


def live_controller(manifest, policy, settings, backend):
    settings.require_live(policy, backend, backend == "cloud")
    if backend == "local":
        raise PortError(
            "Python/Actions cannot call app-native create_session. A separately authenticated "
            "NativeHost adapter must inject callbacks, obtain the same lease and reconcile receipts."
        )
    api = GitHubApi(settings.token, allow_mutation=True)
    cloud = CloudBackend(api, manifest, policy, settings)
    ledger = Ledger(settings.ledger_path, policy["repository"])
    return Controller(manifest, policy, settings, ledger, cloud, GitHubGateAuthority(cloud, manifest, policy))


def build_parser():
    parser = argparse.ArgumentParser(description="Paused Android-port controller; every command defaults to dry-run")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--live", action="store_true", help="Explicit side effects, still subject to every trusted gate")
    mode.add_argument("--dry-run", action="store_true", help="Default; no network, ledger mutation, dispatch or merge")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status")
    commands.add_parser("overlaps", help="Report advisory path/capability overlaps; never blocks local edits")
    commands.add_parser("migrate-reservations", help="Migrate legacy receipts in one ledger transaction")
    files = commands.add_parser("files")
    files.add_argument("--wp")
    files.add_argument("--primary-only", action="store_true")
    files.add_argument("--page", type=int)
    files.add_argument("--page-size", type=int, default=100)
    files.add_argument("--kind", choices=("production", "test", "support", "resource", "license", "reference", "generated", "apple-glue"))
    files.add_argument("--expected-manifest-sha")
    files.add_argument("--expected-source-sha")
    rendering = commands.add_parser("render")
    rendering.add_argument("--wp", required=True)
    rendering.add_argument("--format", choices=("prompt", "issue", "cloud", "local"), default="prompt")
    for command in ("sync-issues", "claim", "release", "cloud", "local"):
        child = commands.add_parser(command)
        child.add_argument("--wp", required=True)
        if command == "sync-issues":
            child.add_argument("--confirm-sync", action="store_true")
        if command in ("claim", "release"):
            child.add_argument("--backend", choices=("cloud", "local"), default="local")
        if command in ("cloud", "local"):
            child.add_argument("--repair-feedback")
    report = commands.add_parser("report")
    report.add_argument("--wp")
    report.add_argument("--binding", type=Path)
    report.add_argument("--pr", type=Path)
    report.add_argument("--evidence", type=Path)
    report.add_argument("--review", type=Path)
    report.add_argument("--approvals", type=Path)
    report.add_argument("--catalog", type=Path)
    report.add_argument("--pr-number", type=int)
    report.add_argument("--import-supervised", action="store_true",
                        help="Explicit trusted merged-PR import for manual WPs000-003 only, never a launch")
    return parser


def main(argv=None, environment=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        manifest = load_manifest(args.repo)
        policy = load_json(args.repo / "docs" / "android" / "automation-policy.json")
        settings = Settings.from_env(os.environ if environment is None else environment, policy)
        records = ledger_snapshot(settings, policy["repository"])
        dry_run = not args.live
        if hasattr(args, "wp") and args.wp:
            manifest.wp(args.wp)
        if args.command == "status":
            result = {
                "dry_run": dry_run, "dispatch_mode": settings.mode, "paused": settings.paused,
                "policy_paused": policy["paused"],
                "activation_approved": policy["activation_approved"],
                "blockers": settings.blockers(policy, settings.mode),
                "blocker_scope": "automated-dispatch-repair-and-merge",
                "local_authoring": {
                    "fleet_activation_required": False,
                    "pre_edit_approval_required": policy["reservation_policy"]["pre_edit_approval_required"],
                    "capability_admission_required": policy["merge_gate_policy"]["capability_admission_required"],
                    "file_intents_are_advisory": True,
                    "semantic_overlap_action": policy["reservation_policy"]["overlapping_semantic_edit_action"],
                    "merge_authorized": False,
                },
                "review_policy": policy["review_policy"],
                "native_review_guide": "docs/android/ci/README.md#native-authenticated-review-publication-bounded-wp-003-repair",
                "inventory": manifest.counts(),
                "work_packages": [
                    {
                        "id": wp["id"], "owner": wp["owner"], "human_gate": wp["human_gate"],
                        "supervised": wp["supervised"],
                        "execution_state": records.get(wp["id"], {}).get("state", "pending"),
                        "completion_reconciled": False,
                        "dependencies": wp["depends_on"],
                        "primary_input_count": len(manifest.inputs(wp["id"], False)),
                    } for wp in manifest.work_packages.values()
                ],
            }
        elif args.command == "overlaps":
            result = {"advisory": True, "overlaps": Ledger(settings.ledger_path, policy["repository"]).overlap_report()} \
                if settings.ledger_path and settings.ledger_path.is_file() else {"advisory": True, "overlaps": []}
        elif args.command == "migrate-reservations":
            if dry_run:
                result = {"dry_run": True, "action": "preview-migrate-reservations",
                          "side_effects": False, "record_state": "legacy-advisory"}
            else:
                if not settings.ledger_path:
                    raise PortError("Reservation migration requires a durable ledger")
                result = {"action": "migrated-reservations",
                          "count": Ledger(settings.ledger_path, policy["repository"]).migrate_legacy()}
        elif args.command == "files":
            if args.page is not None:
                if not args.wp or not args.expected_manifest_sha or not args.expected_source_sha:
                    raise PortError("Paged handoff input reads require WP and explicit manifest/source pin guards")
                result = input_page(manifest, args.wp, args.page, args.page_size,
                                    args.expected_manifest_sha, args.expected_source_sha,
                                    args.primary_only, args.kind)
            else:
                result = manifest.inputs(args.wp, not args.primary_only) if args.wp else manifest.data["inventory"]
                if args.kind:
                    result = [e for e in result if e["kind"] == args.kind]
        elif args.command == "render":
            factories = {
                "issue": lambda: issue_payload(manifest, policy, args.wp),
                "cloud": lambda: cloud_payload(manifest, policy, args.wp, "unclaimed-dry-run"),
                "local": lambda: local_payload(manifest, policy, args.wp, "unclaimed-dry-run"),
            }
            if args.format == "prompt":
                print(render(manifest, policy, args.wp), end="")
                return 0
            result = factories[args.format]()
        elif args.command == "report":
            paths = (args.binding, args.pr, args.evidence, args.review, args.approvals)
            if args.live:
                if not args.wp or args.pr_number is None:
                    raise PortError("Live report requires an existing WP/PR identity and trusted API evidence")
                if args.import_supervised:
                    if not settings.token or not settings.ledger_path or not settings.user_login:
                        raise PortError("Supervised receipt import requires explicit authenticated user and durable ledger")
                    cloud = CloudBackend(GitHubApi(settings.token), manifest, policy, settings)
                    cloud.authenticate()
                    controller = Controller(
                        manifest, policy, settings, Ledger(settings.ledger_path, policy["repository"]), cloud,
                        GitHubGateAuthority(cloud, manifest, policy),
                    )
                    proof = controller.import_supervised_completion(args.wp, Identity(pr_number=args.pr_number))
                else:
                    controller = live_controller(manifest, policy, settings, settings.mode)
                    record = controller.ledger.get(args.wp)
                    if record is None or Identity.parse(record["identity"]).pr_number != args.pr_number:
                        raise PortError("Live completion requires the existing leased task/session/PR identity")
                    proof = controller.record_completion(args.wp)
                result = {"authoritative": True, "completion": proof}
            elif any(paths):
                if not args.wp or not all(paths):
                    raise PortError("Offline gate evaluation requires WP/binding/PR/evidence/review/approvals")
                binding = Binding.parse(load_json(args.binding))
                if binding.work_package != args.wp:
                    raise PortError("Requested WP does not match evidence binding")
                proof = verify_completion(
                    load_json(args.pr), load_json(args.evidence), load_json(args.review),
                    load_json(args.approvals), binding, manifest, policy,
                    load_json(args.catalog) if args.catalog else None,
                )
                result = {"dry_run": True, "authoritative": False, "completion_recorded": False,
                          "offline_evaluation": proof}
            else:
                result = {
                    "dry_run": True, "authoritative": False,
                    "traceability_only": True, **manifest.counts(),
                    "implementations": manifest.data["implementations"],
                    "verified_feature_completions": 0,
                    "pending_capabilities": policy["pending_capabilities"],
                }
        elif dry_run:
            wp = manifest.wp(args.wp)
            backend = args.command if args.command in ("cloud", "local") else getattr(args, "backend", settings.mode)
            feedback = getattr(args, "repair_feedback", None)
            preview = {
                "action": "feedback-to-existing-worker-only",
                "identity": records.get(args.wp, {}).get("identity"),
                "feedback": feedback,
                "new_worker": False,
            } if feedback else issue_payload(manifest, policy, args.wp) if args.command == "sync-issues" else (
                cloud_payload(manifest, policy, args.wp, "unclaimed-dry-run") if backend == "cloud"
                else local_payload(manifest, policy, args.wp, "unclaimed-dry-run")
            )
            result = {
                "dry_run": True, "action": f"preview-{args.command}", "work_package": args.wp,
                "side_effects": False, "dependency_completion": "must reconcile merged PR + evidence live",
                "blockers": settings.blockers(policy, backend, backend == "cloud")
                + ([f"{args.wp} requires supervised implementation"] if wp["supervised"] else []),
                "write_paths": wp["write_paths"], "payload": preview,
            }
            if feedback and not records.get(args.wp, {}).get("identity"):
                result["blockers"].append("repair requires an existing reconciled task/session/PR")
        else:
            backend = args.command if args.command in ("cloud", "local") else getattr(args, "backend", settings.mode)
            controller = live_controller(manifest, policy, settings, backend)
            if args.command == "sync-issues":
                result = controller.sync_issue(args.wp, args.confirm_sync)
            elif args.command == "claim":
                record, acquired = controller.claim(args.wp, time.time())
                result = {"action": "lease-acquired" if acquired else "existing-lease", "record": record}
            elif args.command == "release":
                result = controller.release(args.wp)
            elif args.repair_feedback:
                result = controller.repair(args.wp, args.repair_feedback, time.time())
            else:
                result = controller.launch(args.wp, time.time())
        print(json.dumps(result, indent=2, ensure_ascii=True))
        return 0
    except (PortError, OSError, UnicodeError, sqlite3.Error) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
