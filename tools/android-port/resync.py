"""AndroidOnly: WP-006 Read-only, owner-routed upstream proposals; never advance the source."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest

from controller.errors import PortError
from controller.gates import policy_revision
from controller.model import REFERENCE_SHA, SHA, git, load_manifest, tree
from controller.paths import git_path
from controller.schema import digest, load_json
from portmap import port_map

UPSTREAM = "https://github.com/Avi0n/MeshCoreOne.git"
REPO = Path(__file__).resolve().parents[2]


def require_revision(value):
    if not isinstance(value, str) or SHA.fullmatch(value) is None:
        raise PortError("Upstream comparison requires a full immutable 40-character lowercase commit SHA")
    return value


def changes(repo, original, candidate):
    require_revision(original)
    require_revision(candidate)
    before, after = tree(repo, original), tree(repo, candidate)
    def modes(revision):
        result = {}
        for entry in git(repo, "ls-tree", "-rz", "--full-tree", revision).split(b"\0"):
            if entry:
                metadata, path = entry.split(b"\t", 1)
                result[git_path(path.decode("utf-8"))] = metadata.decode("ascii").split()[0]
        return result

    before_modes, after_modes = modes(original), modes(candidate)
    raw = git(repo, "diff", "--name-status", "-z", "--find-renames", original, candidate, "--")
    fields = raw.split(b"\0")
    if not fields or fields[-1] != b"":
        raise PortError("Malformed NUL-delimited Git change report")
    fields.pop()
    result = []
    position = 0
    while position < len(fields):
        try:
            status = fields[position].decode("ascii")
            position += 1
            if re.fullmatch(r"[AMD]", status):
                path = git_path(fields[position].decode("utf-8"))
                position += 1
                old_path = None if status == "A" else path
                new_path = None if status == "D" else path
                similarity = None
            elif re.fullmatch(r"R[0-9]{3}", status) and int(status[1:]) <= 100:
                old_path = git_path(fields[position].decode("utf-8"))
                new_path = git_path(fields[position + 1].decode("utf-8"))
                position += 2
                similarity = int(status[1:])
            else:
                raise PortError(f"Unsupported upstream change status: {status}")
        except (IndexError, UnicodeError) as failure:
            raise PortError("Malformed or unsupported Git change path") from failure
        old_blob = before.get(old_path) if old_path else None
        new_blob = after.get(new_path) if new_path else None
        if (old_path is not None and old_blob is None) or (new_path is not None and new_blob is None):
            raise PortError("Git change report does not match the actual immutable source trees")
        result.append({
            "status": status[0], "old_path": old_path, "new_path": new_path,
            "old_blob": old_blob, "new_blob": new_blob, "rename_similarity": similarity,
            "old_mode": before_modes.get(old_path), "new_mode": after_modes.get(new_path),
        })
    expected_before = {
        path for path in before
        if before.get(path) != after.get(path) or before_modes.get(path) != after_modes.get(path)
    }
    expected_after = {
        path for path in after
        if after.get(path) != before.get(path) or before_modes.get(path) != after_modes.get(path)
    }
    if {row["old_path"] for row in result if row["old_path"]} != expected_before:
        raise PortError("Git report omitted or duplicated an original source change")
    if {row["new_path"] for row in result if row["new_path"]} != expected_after:
        raise PortError("Git report omitted or duplicated an incoming source change")
    if len(result) != len({digest(row) for row in result}):
        raise PortError("Duplicate upstream change records")
    return sorted(result, key=lambda row: (row["old_path"] or "", row["new_path"] or ""))


def implementation_sources(mapping):
    sources = set(mapping["sources"])
    for declaration in mapping["generated_inputs"]:
        _, separator, values = declaration.partition("; inputs: ")
        if not separator:
            raise PortError("Malformed generated provenance in upstream comparison")
        for value in values.split(", "):
            path, separator, revision = value.rpartition("@")
            if not separator or revision != REFERENCE_SHA:
                raise PortError("Stale generated provenance in upstream comparison")
            sources.add(git_path(path))
    return sources


def prior_ids(reports, repository, manifest_sha, semantic_policy):
    found = set()
    for report in reports:
        if not isinstance(report, dict) or report.get("schema_version") != 1:
            raise PortError("Invalid existing upstream proposal report")
        binding = report.get("binding")
        if not isinstance(binding, dict) or any(binding.get(key) != value for key, value in {
            "repository": repository, "source_sha": REFERENCE_SHA,
            "manifest_sha256": manifest_sha, "policy_revision": semantic_policy,
        }.items()):
            raise PortError("Existing upstream proposals have stale source/manifest/policy binding")
        proposals = report.get("proposals")
        if not isinstance(proposals, list):
            raise PortError("Missing existing owner proposals")
        for proposal in proposals:
            if not isinstance(proposal, dict) or not isinstance(proposal.get("id"), str) or re.fullmatch(r"resync:[0-9a-f]{64}", proposal["id"]) is None:
                raise PortError("Malformed existing proposal identity")
            if not isinstance(proposal.get("changes"), list) or not proposal["changes"]:
                raise PortError("An existing proposal cannot have empty change evidence")
            if proposal["id"] != proposal_id(proposal.get("work_package"), proposal["changes"]):
                raise PortError("Existing proposal identity disagrees with its full source changes")
            found.add(proposal["id"])
    return found


def proposal_id(work_package, source_changes):
    if not isinstance(work_package, str) or re.fullmatch(r"WP-[0-9]{3}", work_package) is None:
        raise PortError("Invalid proposal work package")
    required = {
        "status", "old_path", "new_path", "old_blob", "new_blob",
        "rename_similarity", "old_mode", "new_mode",
    }
    deltas = []
    for row in source_changes:
        if not isinstance(row, dict) or not required.issubset(row) or row["status"] not in ("A", "M", "D", "R"):
            raise PortError("Malformed existing source-change fingerprint")
        delta = {key: row[key] for key in required}
        for path in (delta["old_path"], delta["new_path"]):
            if path is not None:
                git_path(path)
        for blob in (delta["old_blob"], delta["new_blob"]):
            if blob is not None and (not isinstance(blob, str) or SHA.fullmatch(blob) is None):
                raise PortError("Malformed existing source-blob fingerprint")
        if delta["old_mode"] not in (None, "100644", "100755") or delta["new_mode"] not in (None, "100644", "100755"):
            raise PortError("Malformed existing source-mode fingerprint")
        deltas.append(delta)
    deltas.sort(key=lambda row: (row["old_path"] or "", row["new_path"] or ""))
    if len(deltas) != len({digest(row) for row in deltas}):
        raise PortError("Duplicate existing source-change fingerprints")
    return "resync:" + digest({"work_package": work_package, "source_changes": deltas})


def compare(manifest, policy, candidate, source_changes, mappings, existing=()):
    require_revision(candidate)
    if not isinstance(policy, dict) or not isinstance(policy.get("repository"), str) or re.fullmatch(
        r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+", policy["repository"],
    ) is None:
        raise PortError("Upstream proposals require a valid trusted repository policy")
    repository = policy["repository"]
    semantic_policy = policy_revision(manifest, policy)
    seen = prior_ids(existing, repository, manifest.sha256, semantic_policy)
    inventory = {entry["path"]: entry for entry in manifest.data["inventory"]}
    indexed = [(mapping["implementation"], implementation_sources(mapping)) for mapping in mappings]
    grouped = defaultdict(list)
    blockers, exclusions = [], []
    accounted = set()
    for change in source_changes:
        original_path, incoming_path = change["old_path"], change["new_path"]
        entry = inventory.get(original_path) if original_path else None
        if entry is None:
            blockers.append({"reason": "new_unowned_source", "change": change})
            continue
        if entry["blob_sha"] != change["old_blob"]:
            raise PortError("Upstream report's original blob disagrees with the pinned ownership inventory")
        identity = digest(change)
        affected = set(entry["cross_references"])
        if entry["primary_owner"]:
            affected.add(entry["primary_owner"])
        if incoming_path and incoming_path != original_path:
            blockers.append({
                "reason": "renamed_destination_requires_reviewed_mapping",
                "suggested_primary_owner": entry["primary_owner"], "change": change,
            })
        if entry["exclusion"] is not None:
            exclusions.append({
                "change": change, "original_exclusion": entry["exclusion"],
                "disposition": (
                    "retain_user_removed_translation; not a re-port or future requirement; re-admission requires a new user request and scope/admission decision"
                    if entry["exclusion"] == "removed-translation"
                    else "review_changed_exclusion; never automatically discard incoming behavior"
                ),
            })
            if entry["exclusion"] == "removed-translation":
                accounted.add(identity)
                continue
        if not affected and entry["exclusion"] is None:
            blockers.append({"reason": "source_has_no_owner_or_consumers", "change": change})
            continue
        implementations = sorted(path for path, sources in indexed if original_path in sources)
        evidence = {
            **change, "kind": entry["kind"], "primary_owner": entry["primary_owner"],
            "consumer_work_packages": sorted(entry["cross_references"]),
            "android_implementations": implementations,
        }
        for work_package in sorted(affected):
            manifest.wp(work_package)
            grouped[work_package].append(evidence)
        accounted.add(identity)
    proposals = []
    for work_package, inputs in sorted(grouped.items()):
        wp = manifest.wp(work_package)
        inputs = sorted(inputs, key=lambda row: (row["old_path"] or "", row["new_path"] or ""))
        identity = proposal_id(work_package, inputs)
        implementations = sorted({path for row in inputs for path in row["android_implementations"]})
        changed_paths = [
            f"- {row['status']} {row['old_path'] or '(new)'} -> {row['new_path'] or '(deleted)'}; "
            f"kind={row['kind']}; blobs={row['old_blob'] or '(none)'} -> {row['new_blob'] or '(none)'}"
            for row in inputs
        ]
        acceptances = [item["id"] for item in wp["acceptance"]]
        prompt = (
            f"Review upstream resync for {work_package} ({wp['owner']}) at candidate {candidate}. "
            f"Reference remains {REFERENCE_SHA}; do not advance it, rewrite source, weaken fixtures "
            "or start a worker/issue/schedule without the separate reviewed reference/ownership amendment. "
            "Inspect every changed production/test/resource/license declaration and consumer, "
            "including renamed/deleted inputs. Preserve current task/PR identities. "
            "After approval, re-port affected behavior and all source cases using independent "
            "wire/backup/Room evidence as appropriate; headers alone are not acceptance.\n\n"
            "Message translation remains user-excluded, not a future build requirement; upstream "
            "changes or source headers do not re-admit it without a new user request and scope/admission decision.\n\n"
            "Complete changed inputs (including original tests/resources):\n"
            + "\n".join(changed_paths)
            + "\n\nCurrent affected Android implementations:\n"
            + ("\n".join(f"- {path}" for path in implementations) or "- Not implemented yet; do not invent acceptance")
            + "\n\nRetained acceptance IDs:\n" + "\n".join(f"- {value}" for value in acceptances)
        )
        proposals.append({
            "id": identity, "work_package": work_package, "owner": wp["owner"],
            "title": f"[{work_package}] Review upstream source resync",
            "already_proposed": identity in seen, "changes": inputs,
            "android_implementations": implementations, "follow_up_prompt": prompt,
            "acceptance_ids": acceptances,
        })
    known = {digest(row) for row in source_changes if row["old_path"] in inventory}
    if not known.issubset(accounted | {digest(item["change"]) for item in blockers}):
        raise PortError("A known upstream source change has no explicit disposition")
    return {
        "schema_version": 1,
        "binding": {
            "repository": repository, "source_sha": REFERENCE_SHA, "candidate_sha": candidate,
            "manifest_sha256": manifest.sha256, "policy_revision": semantic_policy,
        },
        "status": "blocked" if blockers else ("review_required" if source_changes else "no_changes"),
        "source_changes": source_changes, "blockers": blockers, "changed_exclusions": exclusions,
        "proposals": proposals,
        "summary": {
            "changed_inputs": len(source_changes), "owner_proposals": len(proposals),
            "new_owner_proposals": sum(not proposal["already_proposed"] for proposal in proposals),
            "blocking_inputs": len(blockers),
        },
        "operation": {
            "read_only_comparison": True, "reference_advanced": False, "source_rewritten": False,
            "issues_created": False, "workers_launched": False, "schedule_enabled": False,
            "formal_review_or_activation": False,
        },
    }


def export_report(path, report, repo):
    if not path.is_absolute():
        raise PortError("Comparison artifacts require an explicit absolute private output path")
    path, repo = path.resolve(), repo.resolve()
    if path == repo or path.is_relative_to(repo):
        raise PortError("Full comparison reports must go to a private artifact path outside the checkout")
    if path.exists():
        raise PortError("Refusing to overwrite an existing upstream comparison artifact")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(report, sort_keys=True, indent=2, ensure_ascii=True) + "\n")


def self_test(repo):
    suite = unittest.defaultTestLoader.discover(
        str(repo / "docs" / "android" / "upstream"), pattern="test_resync.py",
    )
    discovered = suite.countTestCases()
    if discovered < 24:
        raise PortError("Missing or incomplete upstream comparison test discovery")
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    if not result.wasSuccessful() or result.skipped or result.testsRun != discovered:
        raise PortError("Upstream comparison assertions failed, errored, skipped or did not fully execute")
    return {
        "discovered": discovered, "run": result.testsRun, "passed": discovered,
        "failed": 0, "errors": 0, "skipped": 0,
        "scope": "read-only proposal/Git fixture assertions; not reference advance or Android feature acceptance",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=REPO)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--candidate")
    mode.add_argument("--self-test", action="store_true")
    parser.add_argument("--fetch-candidate", action="store_true")
    parser.add_argument("--existing", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.self_test:
            if args.fetch_candidate or args.existing:
                raise PortError("Fixture tests cannot fetch candidates or import live proposal inputs")
            report = self_test(args.repo)
            if args.output:
                export_report(args.output, report, args.repo)
            print(json.dumps(report, indent=2))
            return 0
        candidate = require_revision(args.candidate)
        manifest = load_manifest(args.repo)
        policy = load_json(args.repo / "docs" / "android" / "automation-policy.json")
        if args.fetch_candidate:
            fetched = subprocess.run(
                ["git", "-C", str(args.repo), "fetch", "--no-tags", "--no-write-fetch-head", UPSTREAM, candidate],
                capture_output=True, check=False, timeout=180,
            )
            if fetched.returncode:
                raise PortError("Could not fetch the explicit upstream commit from the approved read-only origin")
        report = compare(
            manifest, policy, candidate, changes(args.repo, REFERENCE_SHA, candidate),
            port_map(manifest), [load_json(path) for path in args.existing],
        )
        if report["source_changes"] and args.output is None:
            raise PortError("Changed upstream inputs require a private full-report artifact path; nothing is truncated")
        if args.output:
            export_report(args.output, report, args.repo)
        print(json.dumps({
            "status": report["status"], "binding": report["binding"], "summary": report["summary"],
            "operation": report["operation"], "full_report": str(args.output) if args.output else None,
            "inline_report_omitted": True,
        }, indent=2))
        return 2 if report["blockers"] else 0
    except (PortError, OSError, subprocess.SubprocessError) as failure:
        print(f"BLOCKED: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
