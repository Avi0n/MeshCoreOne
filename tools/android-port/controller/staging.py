"""AndroidOnly: WP-003 Immutable trusted-base reviewer inputs; candidate content is data."""

import base64
import hashlib
import subprocess
from dataclasses import asdict
from pathlib import Path

from .errors import PortError
from .gates import Binding, integrity, policy_revision
from .model import Manifest, REFERENCE_SHA, SHA, git, tree
from .paths import validate_writes
from .schema import decode_json

TRUSTED_PATHS = (
    ".github/agents/parity-reviewer.agent.md",
    ".github/skills/android-port-wp/SKILL.md",
    ".github/skills/swift-to-kotlin/SKILL.md",
    ".github/skills/compose-from-swiftui/SKILL.md",
    "docs/android/automation-policy.json",
    "docs/android/port-manifest.json",
    "docs/android/not-ported.json",
)
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_STAGE_BYTES = 32 * 1024 * 1024
MAX_STAGE_FILES = 2000


def bounded_git(repo: Path, maximum: int, *arguments):
    process = subprocess.Popen(["git", "-C", str(repo), *arguments], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        data = process.stdout.read(maximum + 1)
        if len(data) > maximum:
            raise PortError("Complete immutable Git input exceeds the bounded reader")
        if process.wait(timeout=60):
            raise PortError("Immutable Git input is unavailable")
        return data
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdout.close()


def object_bytes(repo: Path, blob: str):
    if SHA.fullmatch(blob) is None:
        raise PortError("Staging requires a verified immutable Git blob")
    size = git(repo, "cat-file", "-s", blob).decode().strip()
    if not size.isdecimal() or int(size) > MAX_FILE_BYTES:
        raise PortError("Staged file exceeds the bounded immutable reader")
    return bounded_git(repo, MAX_FILE_BYTES, "cat-file", "blob", blob)


def record(repo: Path, side: str, path: str, blob: str):
    data = object_bytes(repo, blob)
    try:
        content, encoding = data.decode("utf-8"), "utf-8"
    except UnicodeError:
        content, encoding = base64.b64encode(data).decode("ascii"), "base64"
    return {
        "side": side, "path": path, "blob_sha": blob, "sha256": hashlib.sha256(data).hexdigest(),
        "size_bytes": len(data), "encoding": encoding, "content": content,
    }


def stage_review(repo: Path, wp_id: str, trusted_base: str, candidate: str, *,
                 path_operations=None, additional_original_paths=None, additional_candidate_sources=None,
                 additional_retained_sources=None):
    if any(not isinstance(value, str) or SHA.fullmatch(value) is None for value in (trusted_base, candidate)):
        raise PortError("Reviewer staging requires exact immutable base/head SHAs")
    base_tree, candidate_tree = tree(repo, trusted_base), tree(repo, candidate)
    if any(path not in base_tree for path in TRUSTED_PATHS):
        raise PortError("Trusted reviewer/profile/skill/policy inputs are missing")
    if "docs/android/port-manifest.json" not in candidate_tree:
        raise PortError("Candidate removed the canonical manifest")
    staged, size = [], 0

    def append(side, path, blob, revision=None):
        nonlocal size
        if len(staged) >= MAX_STAGE_FILES:
            raise PortError("Complete reviewer inputs exceed the file bound; never truncate")
        item = record(repo, side, path, blob)
        if revision is not None:
            item["revision"] = revision
        size += item["size_bytes"]
        if size > MAX_STAGE_BYTES:
            raise PortError("Complete reviewer inputs exceed the byte bound; never truncate")
        staged.append(item)

    for path in TRUSTED_PATHS:
        append("trusted-base", path, base_tree[path])
    by_path = {item["path"]: item for item in staged}
    data = decode_json(by_path["docs/android/port-manifest.json"]["content"])
    policy = decode_json(by_path["docs/android/automation-policy.json"]["content"])
    exclusions = decode_json(by_path["docs/android/not-ported.json"]["content"])
    manifest = Manifest(data, exclusions, repo)
    if policy["repository"] != "cbattlegear/MeshCoreOne-Android":
        raise PortError("Trusted repository identity mismatch")
    wp = manifest.wp(wp_id)
    reviewer = by_path[".github/agents/parity-reviewer.agent.md"]
    registered = [entry for entry in data["agents"] if entry["name"] == "parity-reviewer"]
    if len(registered) != 1 or registered[0]["sha256"] != reviewer["sha256"]:
        raise PortError("Reviewer profile does not match the trusted-base approved identity")
    parts = reviewer["content"].split("---", 2)
    metadata = dict(line.split(":", 1) for line in parts[1].strip().splitlines() if ":" in line) if len(parts) == 3 else {}
    if decode_json(metadata.get("tools", "null").strip()) != ["read", "search"]:
        raise PortError("Reviewer must retain only read/search, without execute/edit/network/publish authority")
    source_sha = data["reference"]["commit"]
    if source_sha != REFERENCE_SHA:
        raise PortError("Trusted staging cannot silently advance the original source pin")
    reference_tree = tree(repo, source_sha)
    candidate_data = decode_json(object_bytes(repo, candidate_tree["docs/android/port-manifest.json"]).decode("utf-8"))
    changed = sorted(path for path in base_tree.keys() | candidate_tree.keys() if base_tree.get(path) != candidate_tree.get(path))
    if path_operations is None:
        validate_writes(wp["write_paths"], changed)
    else:
        from .capabilities import CapabilityEngine

        if not isinstance(path_operations, dict) or set(path_operations) != set(changed):
            raise PortError("Repair admissions must explain every changed path")
        CapabilityEngine.validate_support_changes(path_operations, base_tree, candidate_tree)
        for path in changed:
            CapabilityEngine(policy).admit(wp_id, wp["write_paths"], path, path_operations[path])
    audit = integrity(data, candidate_data, policy, changed)
    for path in changed:
        for side, entries in (("base-data", base_tree), ("candidate-data", candidate_tree)):
            if path in entries:
                append(side, path, entries[path])
    if additional_candidate_sources is not None:
        if not isinstance(additional_candidate_sources, dict) or not all(
            isinstance(path, str) and isinstance(blob, str) and SHA.fullmatch(blob)
            for path, blob in additional_candidate_sources.items()
        ):
            raise PortError("Malformed scoped candidate inputs")
        for path, blob in sorted(additional_candidate_sources.items()):
            if candidate_tree.get(path) != blob:
                raise PortError("Scoped candidate source differs from the immutable candidate")
            if path not in changed:
                append("candidate-data", path, blob)
    if additional_retained_sources is not None:
        if not isinstance(additional_retained_sources, dict) or not all(
            isinstance(key, tuple) and len(key) == 2
            and all(isinstance(part, str) for part in key)
            and SHA.fullmatch(key[0]) and isinstance(blob, str) and SHA.fullmatch(blob)
            for key, blob in additional_retained_sources.items()
        ):
            raise PortError("Malformed retained native fixture inputs")
        revisions = {}
        for (revision, path), blob in sorted(additional_retained_sources.items()):
            if revision not in revisions:
                revisions[revision] = tree(repo, revision)
            if revisions[revision].get(path) != blob:
                raise PortError("Retained native fixture differs from its immutable observation revision")
            append("retained-native", path, blob, revision)
    originals = {entry["path"]: entry for entry in manifest.inputs(wp_id)}
    if additional_original_paths is not None:
        if not isinstance(additional_original_paths, set) or not all(
            isinstance(path, str) for path in additional_original_paths
        ):
            raise PortError("Malformed scoped original inputs")
        allowed = {
            entry["path"]: entry for entry in data["inventory"]
            if entry["kind"] in ("test", "support") and entry["exclusion"] is None
        }
        if additional_original_paths - allowed.keys():
            raise PortError("Unknown or excluded scoped original input")
        originals.update({path: allowed[path] for path in sorted(additional_original_paths)})
    for entry in originals.values():
        if reference_tree.get(entry["path"]) != entry["blob_sha"]:
            raise PortError("Staged original input changed from the pinned source")
        append("pinned-reference", entry["path"], entry["blob_sha"])
    diff = bounded_git(repo, MAX_STAGE_BYTES, "diff", "--no-ext-diff", "--no-textconv", "--binary", trusted_base, candidate)
    binding = Binding(policy["repository"], wp_id, trusted_base, candidate, source_sha,
                      manifest.sha256, policy_revision(manifest, policy))
    return {
        "schema_version": 1, "binding": asdict(binding), "authoritative": False,
        "reviewer": {"name": "parity-reviewer", "tools": ["read", "search"], "trusted_base": trusted_base,
                     "profile_blob_sha": reviewer["blob_sha"], "profile_sha256": reviewer["sha256"]},
        "acceptance": wp["acceptance"], "changed_paths": changed, "gate_audit": audit,
        "files": staged, "diff": {"sha256": hashlib.sha256(diff).hexdigest(), "content": diff.decode("utf-8")},
        "candidate_content_is_untrusted_data": True,
        "scope": "complete immutable staging only; no reviewer invocation, check publication or human approval",
    }


def require_reviewer(policy: dict, *, cli_authenticated=False, coordinator=None,
                     coordinator_authenticated=False, independent_human_reviewer=None, author=None):
    # The legacy keyword remains an identity alias, never proof of authentication.
    reviewer = coordinator if coordinator is not None else independent_human_reviewer
    authorized = (
        coordinator_authenticated is True
        and isinstance(reviewer, str)
        and reviewer in policy["review_policy"]["authorized_coordinators"]
    )
    if cli_authenticated is not True and not authorized:
        raise PortError("BLOCKED: authenticated isolated reviewer or authorized coordinator is absent")
    return True
