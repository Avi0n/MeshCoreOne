"""AndroidOnly: WP-003 Advisory capability reservations and atomic Git-common runtime install."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

MODULE_ROOT = Path(__file__).resolve().parent
if not (MODULE_ROOT / "controller").is_dir():
    MODULE_ROOT = MODULE_ROOT.parent
sys.path.insert(0, str(MODULE_ROOT))

from controller.errors import PortError
from controller.gates import policy_revision
from controller.ledger import Identity, Ledger
from controller.model import SHA, load_manifest
from controller.schema import decode_json, digest, load_json


def assigned_admissions(repo, policy, wp, paths, operations, session_id, *, record=None):
    """Session-scoped support is usable only after its exact policy is deployed."""
    from controller.capabilities import CapabilityEngine

    engine = CapabilityEngine(policy)
    assignment = {"session_id": session_id} if session_id else None
    admissions = engine.admit_scope(wp["id"], wp["write_paths"], paths, operations,
                                    assignment=assignment)
    scoped = {rule["id"]: rule for rule in policy["reservation_policy"]["capabilities"]
              if "assignment" in rule}
    selected = {item.capability_id for item in admissions} & scoped.keys()
    if not selected:
        return admissions
    # A candidate checkout must not authorize its own support-policy addition.
    response = subprocess.run(
        ["gh", "api", f"repos/{policy['repository']}/git/ref/heads/{policy['default_branch']}"],
        capture_output=True, text=True, check=True, timeout=30,
    )
    base = decode_json(response.stdout).get("object", {}).get("sha")
    if not isinstance(base, str) or SHA.fullmatch(base) is None:
        raise PortError("Assigned support requires an authenticated immutable default-branch SHA")
    trusted = decode_json(git(repo, "show", f"{base}:docs/android/automation-policy.json"))
    CapabilityEngine(trusted)
    trusted_rules = {rule["id"]: rule for rule in trusted["reservation_policy"]["capabilities"]}
    if digest(policy) != digest(trusted) or any(trusted_rules.get(key) != scoped[key] for key in selected):
        raise PortError("Assigned support is not deployed on trusted main; product edits remain blocked")
    subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", base, "HEAD"], check=True)
    if record is not None and (
        record["identity"]["session_id"] != session_id
        or record["branch"] != git(repo, "symbolic-ref", "--short", "HEAD")
        or Path(record["worktree"]).resolve() != repo.resolve()
    ):
        raise PortError("Assigned support evolution belongs to another session/branch/worktree")
    return admissions


def git(repo: Path, *arguments):
    return subprocess.check_output(
        ["git", "-C", str(repo), *arguments], text=True
    ).strip()


def shared_ledger(repo: Path):
    configured = os.environ.get("ANDROID_PORT_LEDGER")
    if configured:
        path = Path(configured)
        if not path.is_absolute():
            raise PortError("ANDROID_PORT_LEDGER must be an absolute shared path")
        return path
    return Path(git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir")) \
        / "android-port-shared.sqlite3"


def runtime_target(repo: Path):
    return Path(git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir")) \
        / "meshcore-reservations"


def runtime_sources():
    files = {
        "reserve.py": Path(__file__).resolve(),
        "hook_test.py": Path(__file__).with_name("hook_test.py"),
        "check.py": Path(__file__).with_name("check.py"),
        "fast.py": Path(__file__).with_name("fast.py"),
        "run.sh": Path(__file__).with_name("run.sh"),
        "advisory.py": Path(__file__).with_name("advisory.py"),
        "advisory_test.py": Path(__file__).with_name("advisory_test.py"),
        "inventory_rules.py": MODULE_ROOT / "inventory_rules.py",
    }
    files.update({
        f"controller/{source.name}": source
        for source in sorted((MODULE_ROOT / "controller").glob("*.py"))
    })
    return files


def file_sha256(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_runtime(directory: Path):
    manifest = {}
    for relative, source in runtime_sources().items():
        destination = directory / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))
        manifest[relative] = file_sha256(destination)
    (directory / "runtime-manifest.json").write_text(
        json.dumps({"schema_version": 1, "files": manifest}, indent=2) + "\n",
        encoding="utf-8", newline="\n",
    )
    return manifest


def verify_runtime(directory: Path):
    manifest_path = directory / "runtime-manifest.json"
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise PortError("Installed reservation runtime manifest is missing or unsafe")
    manifest = load_json(manifest_path)
    if set(manifest) != {"schema_version", "files"} or manifest["schema_version"] != 1:
        raise PortError("Malformed installed reservation runtime manifest")
    files = manifest["files"]
    if not isinstance(files, dict) or not files:
        raise PortError("Installed reservation runtime has no files")
    for relative, expected in files.items():
        path = directory / relative
        if (not path.is_file() or path.is_symlink() or not isinstance(expected, str)
                or len(expected) != 64 or file_sha256(path) != expected):
            raise PortError(f"Installed reservation runtime differs from manifest: {relative}")
    return manifest


def install_runtime(target: Path, ledger: Ledger, owner: str, now: float):
    """Stage, verify, and atomically swap the repository-owned runtime under one hard lock."""
    target.parent.mkdir(parents=True, exist_ok=True)
    with ledger.hard_lock(
        "installed-git-common-runtime", owner,
        {"target": str(target), "source": str(Path(__file__).resolve())}, now,
    ):
        stage = Path(tempfile.mkdtemp(prefix=target.name + ".stage-", dir=target.parent))
        backup = target.with_name(target.name + ".previous")
        try:
            write_runtime(stage)
            expected = verify_runtime(stage)
            if backup.exists():
                raise PortError(f"Stale runtime backup requires reconciliation: {backup}")
            if target.exists():
                os.replace(target, backup)
            try:
                os.replace(stage, target)
            except BaseException:
                if backup.exists() and not target.exists():
                    os.replace(backup, target)
                raise
            actual = verify_runtime(target)
            if actual != expected:
                raise PortError("Installed reservation runtime did not preserve staged bytes")
            hook = target.parent / "hooks" / "meshcore-local"
            if hook.is_dir():
                for source, name in (
                    ("advisory.py", "advisory.py"), ("advisory_test.py", "advisory_test.py"),
                    ("fast.py", "fast.py"), ("run.sh", "run.sh"),
                    ("hook_test.py", "test_check.py"), ("check.py", "check.py"),
                ):
                    replacement = hook / (name + ".next")
                    replacement.write_bytes((target / source).read_bytes())
                    os.replace(replacement, hook / name)
            if backup.exists():
                shutil.rmtree(backup)
            return {
                "target": str(target), "files": len(actual["files"]),
                "manifest_sha256": file_sha256(target / "runtime-manifest.json"),
                "atomic": True,
            }
        finally:
            if stage.exists():
                shutil.rmtree(stage)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    commands = parser.add_subparsers(dest="command", required=True)
    install = commands.add_parser(
        "install-runtime",
        help="Atomically synchronize repository source into the Git-common runtime",
    )
    install.add_argument("--owner", required=True)
    commands.add_parser("status", help="Read advisory claims and hard external-resource locks")
    commands.add_parser(
        "overlaps", help="Report deterministic advisory overlap metadata; never approve file edits"
    )
    terminal = commands.add_parser(
        "reconcile-merged-intent",
        help="Record an authoritative terminal PR merge without deleting advisory history",
    )
    terminal.add_argument("--session-id", required=True)
    terminal.add_argument("--pr", required=True, type=int)
    terminal.add_argument("--head-sha", required=True)
    terminal.add_argument("--merge-sha", required=True)
    terminal.add_argument("--path", action="append", required=True)
    commands.add_parser(
        "migrate-reservations",
        help="Transactionally migrate historical supervised receipts without release/recreate",
    )
    claim = commands.add_parser(
        "claim", help="Backward-compatible advisory claim; path overlap never blocks isolated edits"
    )
    claim.add_argument("--wp", required=True)
    claim.add_argument("--session-id", required=True)
    claim.add_argument("--authorization", required=True)
    claim.add_argument("--base")
    claim.add_argument("--path", action="append")
    claim.add_argument("--additional-path", action="append", default=[])
    claim.add_argument("--operation", default="modify")
    evolve = commands.add_parser(
        "evolve", aliases=["reconcile"],
        help="Idempotently evolve the same advisory owner with trusted path+operation rules",
    )
    evolve.add_argument("--wp", required=True)
    evolve.add_argument("--expected-revision", required=True, type=int)
    evolve.add_argument("--path", action="append", required=True)
    evolve.add_argument("--operation", action="append", required=True)
    evolve.add_argument("--session-id", help="Required for session-assigned support; must match existing receipt")
    transfer = commands.add_parser(
        "transfer", help="Record an advisory current-producer handoff with both attributions"
    )
    transfer.add_argument("--path", required=True)
    transfer.add_argument("--capability", required=True)
    for prefix in ("origin", "producer"):
        transfer.add_argument(f"--{prefix}-wp", required=True)
        transfer.add_argument(f"--{prefix}-session", required=True)
        transfer.add_argument(f"--{prefix}-evidence", required=True)
    release = commands.add_parser(
        "release",
        help="Compatibility name; file intent is retained until authoritative merge reconciliation",
    )
    release.add_argument("--session-id")
    release.add_argument("--pr", type=int)
    admit = commands.add_parser("admit", help="Evaluate one trusted path+operation capability rule")
    admit.add_argument("--wp", required=True)
    admit.add_argument("--path", required=True)
    admit.add_argument("--operation", required=True)
    admit.add_argument("--session-id", help="Existing producer identity for assigned support only")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        from controller.capabilities import CapabilityEngine

        repo = Path(git(args.repo, "rev-parse", "--show-toplevel"))
        policy = load_json(repo / "docs/android/automation-policy.json")
        manifest = load_manifest(repo)
        ledger = Ledger(shared_ledger(repo), policy["repository"])
        if args.command == "install-runtime":
            result = install_runtime(runtime_target(repo), ledger, args.owner, time.time())
        elif args.command == "status":
            result = {
                "claims": ledger.records(), "hard_locks": ledger.hard_lock_records(),
                "intent_reconciliations": ledger.intent_reconciliation_records(),
                "policy_revision": policy_revision(manifest, policy),
            }
        elif args.command == "overlaps":
            result = {"advisory": True, "overlaps": ledger.overlap_report()}
        elif args.command == "migrate-reservations":
            result = {"migrated": ledger.migrate_legacy(), "release_recreate": False}
        elif args.command == "reconcile-merged-intent":
            if subprocess.run(
                ["git", "-C", str(repo), "merge-base", "--is-ancestor",
                 args.head_sha, args.merge_sha],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            ).returncode != 0:
                raise PortError("Merged intent head is not an ancestor of the authoritative merge")
            receipt, created = ledger.reconcile_merged_intent(
                args.session_id, args.pr, args.head_sha, args.merge_sha, args.path, time.time()
            )
            result = {"created": created, "receipt": receipt, "release_recreate": False}
        elif args.command == "claim":
            wp = manifest.wp(args.wp)
            paths = list(dict.fromkeys([*(args.path or wp["write_paths"]), *args.additional_path]))
            admissions = assigned_admissions(repo, policy, wp, paths, [args.operation], args.session_id,
                                             record=ledger.get(args.wp))
            capabilities = {}
            for admission in admissions:
                capabilities.setdefault(admission.capability_id, []).append(admission.path)
            base = git(repo, "rev-parse", "--verify", (args.base or "HEAD") + "^{commit}")
            if SHA.fullmatch(base) is None:
                raise PortError("Advisory claim base is not an immutable commit")
            subprocess.run(
                ["git", "-C", str(repo), "merge-base", "--is-ancestor", base, "HEAD"],
                check=True,
            )
            binding = {
                "repository": policy["repository"], "work_package": args.wp,
                "base_sha": base, "source_sha": manifest.data["reference"]["commit"],
                "manifest_sha256": manifest.sha256,
                "policy_revision": policy_revision(manifest, policy),
            }
            scoped = {**wp, "write_paths": paths}
            record, acquired = ledger.claim(
                scoped, binding, "local", args.session_id, time.time(),
                2_147_483_647, 2_147_483_647, 0, 1_000_000_000, 1,
                capabilities={key: sorted(value) for key, value in capabilities.items()},
                operations=[args.operation],
                authorization_context={"authorization": args.authorization},
                branch=git(repo, "symbolic-ref", "--short", "HEAD"),
                worktree=str(repo.resolve()),
                initial_identity=Identity(session_id=args.session_id),
            )
            assigned_ids = {rule["id"] for rule in policy["reservation_policy"]["capabilities"]
                            if "assignment" in rule}
            if not acquired and any(item.capability_id in assigned_ids for item in admissions):
                raise PortError("Assigned support requires fresh initial acquisition or same-owner CAS evolution")
            result = {"acquired": acquired, "record": record}
        elif args.command in ("evolve", "reconcile"):
            record = ledger.get(args.wp)
            if record is None:
                raise PortError("Capability evolution requires an existing advisory claim")
            wp = manifest.wp(args.wp)
            admissions = assigned_admissions(repo, policy, wp, args.path, args.operation,
                                             args.session_id, record=record)
            capabilities = {}
            for admission in admissions:
                capabilities.setdefault(admission.capability_id, []).append(admission.path)
            result = ledger.evolve_scope(
                args.wp, record["attempt"], args.expected_revision,
                capabilities, args.operation, args.path,
            )
        elif args.command == "transfer":
            origin = {
                "wp": args.origin_wp, "session": args.origin_session,
                "evidence": args.origin_evidence,
            }
            producer = {
                "wp": args.producer_wp, "session": args.producer_session,
                "evidence": args.producer_evidence,
            }
            result = CapabilityEngine(policy).transfer_intent(
                origin, producer, args.path, args.capability
            )
        elif args.command == "release":
            raise PortError(
                "Advisory file intents are not pre-edit locks; release requires authoritative "
                "terminal PR/merge reconciliation through the controller"
            )
        else:
            wp = manifest.wp(args.wp)
            admission = assigned_admissions(repo, policy, wp, [args.path], [args.operation],
                                            args.session_id)[0]
            result = {
                "capability": admission.capability_id, "path": admission.path,
                "operation": admission.operation, "invariants": admission.invariants,
                "validation": admission.validation,
            }
        print(json.dumps(result, indent=2))
        return 0
    except (OSError, PortError, subprocess.SubprocessError, ValueError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
