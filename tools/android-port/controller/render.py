import json

from .errors import PortError
from .gates import policy_revision
from .model import Manifest
from .schema import digest, positive_integer
from .verification import declared_software_lane


def marker(wp_id: str) -> str:
    return f"<!-- android-port-wp:{wp_id} -->"


def input_page(manifest: Manifest, wp_id: str, page: int, page_size: int,
               expected_manifest_sha: str, expected_source_sha: str,
               primary_only=False, kind=None) -> dict:
    positive_integer(page, "Input page")
    positive_integer(page_size, "Input page size")
    if page_size > 200:
        raise PortError("Input page size exceeds the bounded transport maximum of 200")
    if expected_manifest_sha != manifest.sha256 or expected_source_sha != manifest.data["reference"]["commit"]:
        raise PortError("Paged input manifest/source pin changed; restart the handoff from trusted inputs")
    complete = sorted(manifest.inputs(wp_id, not primary_only), key=lambda e: e["path"])
    entries = [e for e in complete if kind is None or e["kind"] == kind]
    pages = max(1, (len(entries) + page_size - 1) // page_size)
    if page > pages:
        raise PortError("Input page is beyond the complete pinned selection")
    offset = (page - 1) * page_size
    return {
        "work_package": wp_id, "manifest_sha256": manifest.sha256,
        "source_sha": expected_source_sha,
        "source_inventory_sha256": manifest.data["reference"]["source_inventory_sha256"],
        "primary_only": primary_only, "kind": kind,
        "scope_sha256": digest(complete), "selection_sha256": digest(entries),
        "full_input_count": len(complete), "total_entries": len(entries),
        "page": page, "page_size": page_size, "total_pages": pages,
        "next_page": page + 1 if page < pages else None,
        "entries": entries[offset:offset + page_size],
    }


def render(manifest: Manifest, policy: dict, wp_id: str, attempt="unclaimed-dry-run") -> str:
    wp = manifest.wp(wp_id)
    inputs = manifest.inputs(wp_id)
    lines = [
        marker(wp_id),
        f"# {wp_id}: {wp['title']}",
        "",
        f"Owner/custom_agent: {wp['owner']}",
        f"Repository: {policy['repository']}; base branch: {policy['default_branch']}",
        f"Reference: {manifest.data['reference']['commit']}",
        f"Manifest SHA-256: {manifest.sha256}",
        f"Policy revision: {policy_revision(manifest, policy)}",
        f"Attempt: {attempt}",
        "Dependencies (merged PR + independently verified acceptance, never closed issues): "
        + (", ".join(wp["depends_on"]) or "none"),
        f"Human gate: {wp['human_gate']}; supervised bootstrap: {wp['supervised']}",
        "",
        "Implement ONLY this WP on one managed worktree/branch/PR. Inherit model defaults.",
        "Do not launch additional agents/factories/sessions, alter repository settings, activate schedules, "
        "merge, sign/publish releases, or edit read-only Swift.",
        "Read PORTING_PLAN.md, the three relevant .github/skills and trusted instructions.",
        "Assignment authorizes ordinary work and directly necessary support edits inside trusted capabilities. "
        "Automatically attempt advisory reservation bookkeeping; a historical claim mismatch alone is not "
        "an edit lock. Unknown path/operation capabilities and unresolved live overlaps remain blockers.",
        "A missing actual prerequisite or contract, or an ambiguous live identity, is BLOCKED, not a stub.",
        "",
        "## Exact allowed writes",
        *[f"- {path}" for path in wp["write_paths"]],
        "Directly necessary support paths may also be admitted by trusted path+operation capability rules.",
        "For directly related cross-owner Kotlin support, use modify-related-consumer for existing "
        "production consumers or add-or-update-related-test for tests. These do not admit unrelated "
        "work, deleted tests, build configuration, or an unresolved semantic overlap.",
        "",
        "## Exact pinned inputs (primary ownership or read-only cross-reference)",
    ]
    input_start = len(lines)
    for entry in inputs:
        role = "primary" if entry["primary_owner"] == wp_id else "cross-reference"
        if entry["exclusion"]:
            disposition = (
                "user-approved scope removal; no port or future build requirement"
                if entry["exclusion"] == "removed-translation" else "adaptation still required"
            )
            role += f"; excluded file: {entry['exclusion']} ({disposition})"
        lines.append(f"- {entry['path']}@{entry['blob_sha']} [{entry['kind']}; {role}]")
    if not inputs:
        lines.append(wp["android_only_reason"])
    input_end = len(lines)
    software_lane = []
    if not wp["verification"]["configured"] and not wp["verification"]["commands"]:
        software_lane = [
            "Project-wide declared software lane (not whole-WP or device acceptance):",
            json.dumps(declared_software_lane(manifest), ensure_ascii=True),
        ]
    lines.extend([
        "",
        "## Observable acceptance (file/header coverage is not feature completion)",
        *[f"- {a['id']}: {a['description']}" for a in wp["acceptance"]],
        "",
        "## Verification",
        json.dumps(wp["verification"], ensure_ascii=True),
        *software_lane,
        "An empty verification entry for an eligible Android module WP can use the shared whole-WP "
        "contract; it does not by itself block dispatch or an already-assigned, capability-admitted bounded repair. "
        "Original-inventory, non-module, release and explicitly partially configured WPs still require "
        "their specific verification contract. For full-WP completion use the schema-4 verification scope "
        "and native review schema 3 documented in docs/android/ci/README.md: cover every acceptance "
        "and original case family, and bind actual hosted methods and required native/human evidence. "
        "Use the existing declared affected or scaffold checks for its actual candidate and applicable "
        "acceptance; do not require a separate WP-003 binding or issue solely for an empty macro entry. "
        "Those checks do not prove whole-WP or device acceptance.",
        "Missing or failed local checks leave the candidate unverified but do not prevent feature-branch "
        "publication; report their outcomes and use the exact-candidate hosted checks.",
        "Port every in-scope original case/parameter family from the independently approved WP-004 catalog; "
        "account for exact user-approved exclusions without claiming passing tests/features.",
        "Message translation is removed, not deferred or a future build/release requirement. "
        "Do not register a translation placeholder/provider or treat inert shared contracts as a WP-407 blocker. "
        "Re-admission requires a new user feature request and scope/admission decision.",
        "Use positive test discovery and exact repository/base/head/source/manifest/policy/run evidence.",
        "Headers: // PortedFrom: <git-relative path>@<reference SHA>; Android-only: // AndroidOnly: "
        f"{wp_id} <reason>. Generated outputs name generator and inputs.",
        "",
        "## PR and stop",
        f"Title: [{wp_id}] <bounded change>; include the marker at the top in the PR body.",
        "Use the repository template honestly. Link only an existing issue; do not invent Closes syntax.",
        f"Record deviations/evidence only under docs/android/deviations/{wp_id}.md and "
        f"docs/android/evidence/{wp_id}/.",
        "Candidate builds have read-only credentials, no signing/dispatch/merge secrets.",
        "Code review comes from the authenticated trusted read/search-only reviewer or authorized coordinator; "
        "the PR author may coordinate. Never fabricate a GitHub APPROVED review or human acceptance.",
        "Return the actual task/session/PR/head, commands/discovered counts, evidence and blockers.",
        "Stop at missing required human acceptance or an oversized macro-WP; protected code still requires trusted review. "
        "Propose a reviewed subdivision for oversized work, "
        "never silently amend policy.",
    ])
    text = "\n".join(lines) + "\n"
    if len(text) > 65536:
        pages = (len(inputs) + 99) // 100
        inventory = [
            f"Complete scope: {len(inputs)} exact primary/cross-reference inputs; "
            f"scope SHA-256: {digest(sorted(inputs, key=lambda e: e['path']))}.",
            "The transport references the ENTIRE frozen manifest inventory; no sources/tests/resources "
            "or acceptance items are truncated, excluded or deferred by paging.",
            f"Read docs/android/port-manifest.json at manifest SHA-256 {manifest.sha256}; "
            f"source inventory SHA-256 {manifest.data['reference']['source_inventory_sha256']}.",
            f"Retrieve all {pages} pages in order (100 entries/page), following next_page until null:",
            "python tools/android-port/controller/dispatch.py files "
            f"--wp {wp_id} --page <page> --page-size 100 "
            f"--expected-manifest-sha {manifest.sha256} "
            f"--expected-source-sha {manifest.data['reference']['commit']}",
            "Resolve Git path separators for the host. Check identical manifest/source/scope hashes, "
            f"exactly {len(inputs)} distinct accumulated paths, and total_entries across every page.",
            "Optional --kind/--primary-only filters are inspection aids, not permission to skip the "
            "unfiltered primary + cross-reference scope. Pin drift/unknown sources fail closed.",
            "Paging a wide read-only audit list is not a new WP or a change to the approved DAG/gates. "
            "An actually oversized implementation still needs a human-reviewed scope decision.",
        ]
        text = "\n".join(lines[:input_start] + inventory + lines[input_end:]) + "\n"
        if len(text) > 65536:
            raise PortError(f"{wp_id}: bounded metadata/acceptance itself exceeds the API limit; human review required")
    return text


def issue_payload(manifest: Manifest, policy: dict, wp_id: str) -> dict:
    wp = manifest.wp(wp_id)
    return {
        "title": f"[{wp_id}] {wp['title']}",
        "body": render(manifest, policy, wp_id),
    }


def cloud_payload(manifest: Manifest, policy: dict, wp_id: str, attempt: str) -> dict:
    return {
        "assignees": ["copilot-swe-agent[bot]"],
        "agent_assignment": {
            "target_repo": policy["repository"],
            "base_branch": policy["default_branch"],
            "custom_instructions": render(manifest, policy, wp_id, attempt),
            "custom_agent": manifest.wp(wp_id)["owner"],
        },
    }


def local_payload(manifest: Manifest, policy: dict, wp_id: str, attempt: str) -> dict:
    wp = manifest.wp(wp_id)
    return {
        "name": f"{wp_id} {wp['owner']}"[:40],
        "workspace_type": "worktree",
        "coordinate_with_creator": True,
        "notify_on_idle": "always",
        "kickoff": {
            "agent": wp["owner"], "mode": "autopilot",
            "prompt": render(manifest, policy, wp_id, attempt),
        },
    }
