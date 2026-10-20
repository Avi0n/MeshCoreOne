"""AndroidOnly: WP-000 Exact reversible user-approved message-translation removal."""

import copy
from pathlib import Path

from .errors import PortError
from .schema import digest, load_json

REPO = Path(__file__).resolve().parents[3]
AMENDMENT_PATH = "docs/android/scope-amendments/translation-removal.json"
AMENDMENT_SHA256 = "32b5f19e1a04da77e64168fbd101cac93c4453a1eb4fbb329eb75822db633fb1"
PLAN_SHA256 = "e5c838d2566d0466175e2be4036d50e44be548319c9fcd8b83bccd4fc03930cd"
REVIEW_POLICY = {
    "code_review_authority": "trusted-read-only-review",
    "authorized_coordinators": ["cbattlegear"],
    "human_acceptance_work_packages": ["WP-001", "WP-505", "WP-506"],
    "human_acceptance_paths": ["android/release/"],
    "human_acceptance_patterns": [
        "android/**/signing*", "android/**/keystore*", "**/*license*", "**/*LICENSE*", "**/*legal*",
    ],
}
DEBUG_PACKAGING_CAPABILITY = {
    "id": "debug-test-packaging",
    "work_packages": ["WP-003", "WP-503"],
    "path_rules": ["tools/android-port/local/debug_packaging.py", "tools/android-port/tests/test_debug_packaging.py"],
    "operations": ["implement-debug-packaging-adapter", "build-debug-test-apks-with-existing-local-identity"],
    "invariants": [
        "WP-503-execution-only", "exact-clean-source-and-fixtures", "existing-native-and-exclusive-gradle-lanes",
        "existing-local-default-debug-identity-only", "public-app-test-installed-certificate-match",
        "no-private-key-read-export-copy-or-change", "no-retained-apk-resign",
        "no-install-uninstall-wipe-or-release-task",
    ],
    "validation": [
        "debug-packaging regression tests", "actual source-build-output hashes",
        "public certificate verification before any separately guarded install",
    ],
}


def debug_packaging_predecessor(policy):
    """Reverse only the exact admitted debug-support delta, preserving all historical receipts."""
    result = copy.deepcopy(policy)
    if "reservation_policy" not in result:
        return result
    reservation = result.get("reservation_policy")
    rules = reservation.get("capabilities") if isinstance(reservation, dict) else None
    if not isinstance(rules, list):
        raise PortError("Debug support amendment requires the canonical capability catalog")
    selected = [index for index, rule in enumerate(rules)
                if isinstance(rule, dict) and rule.get("id") == "debug-test-packaging"]
    if not selected:
        return result
    if (
        len(selected) != 1 or rules[selected[0]] != DEBUG_PACKAGING_CAPABILITY
        or selected[0] + 1 >= len(rules) or rules[selected[0] + 1].get("id") != "merge-integration"
    ):
        raise PortError("Debug support capability changed outside the exact authorized amendment")
    rules.pop(selected[0])
    return result


def review_policy_predecessor(policy):
    """Reverse exact authorized process deltas; historical policy receipts stay immutable."""
    result = debug_packaging_predecessor(policy)
    if "review_policy" not in result:
        return result
    if result.pop("review_policy") != REVIEW_POLICY:
        raise PortError("Review authority changed outside the authorized policy amendment")
    gates = result.get("merge_gate_policy")
    if not isinstance(gates, dict) or (
        gates.pop("protected_paths_require_trusted_code_review_for_main_merge", None) is not True
        or gates.pop("human_hardware_legal_signing_acceptance_required", None) is not True
        or "protected_paths_require_human_approval_for_main_merge" in gates
    ):
        raise PortError("Review authority merge-gate amendment drift")
    gates["protected_paths_require_human_approval_for_main_merge"] = True
    return result


def amendment(repo=REPO):
    record = load_json(repo / AMENDMENT_PATH, 512 * 1024)
    if digest(record) != AMENDMENT_SHA256:
        raise PortError("Protected translation scope amendment identity drift")
    return record


def is_translation_scope(data):
    return isinstance(data, dict) and isinstance(data.get("reference"), dict) \
        and data["reference"].get("approved_plan_sha256") == PLAN_SHA256


def transform(data, *, reverse=False, repo=REPO):
    record = amendment(repo)
    source, target = ("current", "previous") if reverse else ("previous", "current")
    matches = [stage for stage in record["revisions"].values() if stage[source] == digest(data)]
    if len(matches) != 1:
        raise PortError("Translation scope requires the complete exact catalog preimage")
    result = copy.deepcopy(data)
    if result["reference"] != record["reference"][source]:
        raise PortError("Translation scope reference metadata drift")
    result["reference"] = copy.deepcopy(record["reference"][target])
    for section, key in (("agents", "name"), ("work_packages", "id"), ("inventory", "path")):
        for change in record[section]:
            before, after = change[source], change[target]
            found = [item for item in result[section] if item[key] == change[key]]
            if found != ([] if before is None else [before]):
                raise PortError(f"Translation scope exact delta mismatch: {change[key]}")
            if before is not None:
                index = result[section].index(found[0])
                if after is None:
                    result[section].pop(index)
                else:
                    result[section][index] = copy.deepcopy(after)
            elif after is not None:
                result[section].insert(change["index"], copy.deepcopy(after))
    if digest(result) != matches[0][target]:
        raise PortError("Translation scope changed the complete catalog outside its exact delta")
    return result


def apply_translation_scope(data, repo=REPO):
    return transform(data, repo=repo)


def project_translation_scope(data, repo=REPO):
    return transform(data, reverse=True, repo=repo)


def project_translation_exclusions(exclusions, repo=REPO):
    record = amendment(repo)
    if digest(exclusions) != record["current_exclusions_sha256"]:
        raise PortError("Translation exclusions require the complete exact current catalog")
    result = copy.deepcopy(exclusions)
    for entry in record["added_exclusions"]:
        result["entries"].remove(entry)
    if digest(result) != record["previous_exclusions_sha256"]:
        raise PortError("Translation exclusions changed the historical catalog outside the exact delta")
    return result


def current_revisions(manifest, policy):
    from .gates import policy_revision

    project_translation_scope(manifest.data, manifest.repo)
    actual = policy_revision(manifest, policy)
    predecessor = review_policy_predecessor(policy)
    if policy_revision(manifest, predecessor) != amendment(manifest.repo)["current_policy_revision"]:
        raise PortError("Translation scope current catalog/policy cannot be cross-bound")
    return {"manifest_sha256": manifest.sha256, "policy_revision": actual}
