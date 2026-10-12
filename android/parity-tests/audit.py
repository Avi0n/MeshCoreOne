"""AndroidOnly: WP-501 Complete pinned accounting; candidate mappings are NOT behavioral acceptance."""

import argparse
from collections import Counter, defaultdict
import json
import os
from pathlib import Path
import re
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools" / "android-port"))

from controller.errors import PortError
from controller.model import load_manifest
from controller.schema import digest, load_json
from oracle.reference import FrozenReference, SOURCE_SHA, json_bytes, repo_path, write_or_check
from test_inventory import generate as original_inventory

OUTPUT = "docs/android/parity/accounting.json"
POLICY = "docs/android/parity/coverage-policy.json"
DETAILS = "docs/android/evidence/WP-004/inventory-details.json"
SOURCE_HEADER = re.compile(r"// PortedFrom: ([^\s@]+)@([0-9a-f]{40})")
CASE_MARKER = re.compile(r'@(?:OriginalCase|SourceCase)\("((?:\\.|[^"\\])*)"\)')


def case_key(path, case):
    return path, case["id"], case["parameter_family"]


def index_cases(catalog, details):
    files = {entry["path"]: entry for entry in details["files"]}
    if len(files) != len(details["files"]):
        raise PortError("Duplicate detailed original path")
    indexed = {}
    for entry in catalog["entries"]:
        detail = files.pop(entry["path"], None)
        if detail is None or detail["blob_sha"] != entry["blob_sha"]:
            raise PortError("Original catalog/detail path or blob mismatch")
        detailed = {case_key(entry["path"], case): case for case in detail["cases"]}
        if len(detailed) != len(detail["cases"]):
            raise PortError("Duplicate original path/case/parameter-family identity")
        for case in entry["cases"]:
            key = case_key(entry["path"], case)
            if key in indexed or key not in detailed:
                raise PortError("Missing or duplicate original case family")
            indexed[key] = detailed.pop(key)
        if detailed:
            raise PortError("Unaccounted detailed case family")
    if files or not indexed:
        raise PortError("Missing or zero original inventory")
    return indexed


def current_policy(repo, manifest):
    value = load_json(repo_path(repo, POLICY))
    expected = {
        "schema_version": 1,
        "source_sha": SOURCE_SHA,
        "accounting": "all-manifest-paths-and-all-original-path-case-parameter-families",
        "passing_counts_are_not_parity": True,
        "headers_and_case_markers_are_traceability_only": True,
        "historical_results_are_not_candidate_execution": True,
        "missing_independent_evidence_blocks_acceptance": True,
        "mandatory_skips_allowed": False,
        "new_exclusions_allowed": False,
        "exclusions_sha256": digest([
            {"path": row["path"], "blob_sha": row["blob_sha"], "exclusion": row["exclusion"]}
            for row in manifest.data["inventory"] if row["exclusion"] is not None
        ]),
        "review_requirement": "trusted-independent-exact-candidate-behavior-and-family-review",
    }
    if value != expected:
        raise PortError("Coverage policy drift or unapproved exclusion/threshold change")
    return value


def native_candidates(repo, inventory, originals):
    by_source = defaultdict(list)
    by_case = defaultdict(list)
    by_id = defaultdict(list)
    for key in originals:
        by_id[key[1]].append(key)
    diagnostics = []
    for path in sorted((repo / "android").rglob("*.kt")):
        relative = path.relative_to(repo).as_posix()
        if "build" in path.relative_to(repo / "android").parts:
            continue
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        references = set()
        for source, pin in SOURCE_HEADER.findall(text):
            if pin != SOURCE_SHA or source not in inventory:
                raise PortError(f"Unknown source or stale native provenance: {relative}: {source}@{pin}")
            references.add(source)
            by_source[source].append(relative)
        for raw in CASE_MARKER.findall(text):
            try:
                identity = json.loads('"' + raw + '"')
            except ValueError as error:
                raise PortError(f"Malformed native case marker: {relative}") from error
            keys = [key for key in by_id[identity] if key[0] in references]
            if not keys:
                diagnostics.append({"native_path": relative, "case_id": identity,
                                    "reason": "marker-does-not-resolve-to-a-header-qualified-original"})
            for key in keys:
                by_case[key].append({"kind": "candidate-case-marker", "path": relative})
    # Preserve explicit predecessor proposals without trusting their claimed historical outcomes.
    for path in sorted((repo / "docs" / "android" / "evidence").glob("WP-*/source-cases.json")):
        value = load_json(path)
        if value.get("source_sha") != SOURCE_SHA:
            raise PortError(f"Historical case mapping source drift: {path.name}")
        records = value.get("cases", value.get("original_cases", []))
        if not isinstance(records, list):
            raise PortError(f"Malformed predecessor case mapping: {path}")
        relative = path.relative_to(repo).as_posix()
        for offset, record in enumerate(records):
            if not isinstance(record, dict):
                raise PortError(f"Malformed predecessor case record: {relative}#{offset}")
            identity = record.get("id", record.get("case_id"))
            source = record.get("source", record.get("source_path"))
            candidates = by_id.get(identity, [])
            if source is not None:
                candidates = [key for key in candidates if key[0] == source]
            if len(candidates) != 1:
                diagnostics.append({"native_path": relative, "record": offset, "case_id": identity,
                                    "reason": "historical-mapping-needs-qualified-family-review"})
                continue
            key = candidates[0]
            if record.get("parameter_family", key[2]) != key[2]:
                raise PortError(f"Predecessor parameter-family drift: {relative}#{offset}")
            by_case[key].append({"kind": "historical-mapping-not-current-proof", "path": relative,
                                 "record": offset})
    return by_source, by_case, diagnostics


def build_accounting(manifest, policy, catalog, details, sources, candidates, diagnostics):
    originals = index_cases(catalog, details)
    inventory = {entry["path"]: entry for entry in manifest.data["inventory"]}
    if len(inventory) != len(manifest.data["inventory"]):
        raise PortError("Duplicate manifest path")
    source_records = []
    for path, entry in sorted(inventory.items()):
        source_records.append({
            "path": path, "blob_sha": entry["blob_sha"], "kind": entry["kind"],
            "owner": entry["primary_owner"], "exclusion": entry["exclusion"],
            "candidate_native_paths": sorted(set(sources.get(path, []))),
            "acceptance": "scope-excluded-not-passed" if entry["exclusion"] else "independent-behavior-review-required",
        })
    cases = []
    for key, detail in sorted(originals.items()):
        path, identity, family = key
        entry = inventory[path]
        cases.append({
            "source_path": path, "source_blob_sha": entry["blob_sha"], "case_id": identity,
            "parameter_family": family, "owner": entry["primary_owner"],
            "exclusion": entry["exclusion"],
            "original_detail_sha256": digest(detail),
            "assertion_status": detail["assertion_status"],
            "candidate_mappings": sorted(candidates.get(key, []), key=lambda item: json.dumps(item, sort_keys=True)),
            "acceptance": "scope-excluded-not-passed" if entry["exclusion"] else
                "independent-family-review-required" if candidates.get(key) else "no-explicit-family-mapping",
        })
    return {
        "schema_version": 1, "generator": "android/parity-tests/audit.py",
        "source_sha": SOURCE_SHA, "manifest_sha256": manifest.sha256,
        "coverage_policy_sha256": digest(policy), "original_details": DETAILS,
        "original_details_sha256": digest(details),
        "counts": {
            "source_paths": len(source_records), "original_case_families": len(cases),
            "source_dispositions": dict(sorted(Counter(row["acceptance"] for row in source_records).items())),
            "family_dispositions": dict(sorted(Counter(row["acceptance"] for row in cases).items())),
            "verified_behavioral_acceptances": 0,
        },
        "sources": source_records, "case_families": cases,
        "unresolved_candidate_mappings": diagnostics,
        "acceptance": "BLOCKED",
        "blocker": "Independent source/assertion/parameter-family and observable consumer review is missing; candidates and historical passes do not establish equivalence.",
    }


def generate(repo=REPO):
    reference = FrozenReference(repo)
    manifest = load_manifest(repo)
    policy = current_policy(repo, manifest)
    catalog, details = original_inventory(reference)
    # The inherited inventory remains immutable; complete semantic comparison, not just counts.
    if catalog != load_json(repo / "docs" / "android" / "test-cases.json") or details != load_json(repo_path(repo, DETAILS)):
        raise PortError("Historical original inventory drift")
    originals = index_cases(catalog, details)
    sources, candidates, diagnostics = native_candidates(repo, reference.inventory, originals)
    return build_accounting(manifest, policy, catalog, details, sources, candidates, diagnostics)


def verify_acceptance_tests(directory, expected):
    from controller.ci_evidence import require_case_identities

    return require_case_identities(directory, expected)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--generate", action="store_true")
    mode.add_argument("--require-acceptance", action="store_true")
    parser.add_argument("--pr", type=int, help="Existing implementation PR for authenticated acceptance lookup")
    parser.add_argument("--base-sha", help="Expected exact reviewed base (not guessed from main)")
    parser.add_argument("--head-sha", help="Expected exact reviewed candidate")
    args = parser.parse_args(argv)
    try:
        value = generate()
        if args.check:
            print(json.dumps({
                "result": "passed",
                "scope": "frozen source and native provenance; historical accounting is not a freshness gate",
            }))
        else:
            write_or_check(repo_path(REPO, OUTPUT), json_bytes(value), check=not args.generate)
            print(json.dumps({"accounting": "complete", "acceptance": value["acceptance"], **value["counts"]}))
        if args.require_acceptance:
            from acceptance import github_review

            if args.pr is None or not args.base_sha or not args.head_sha:
                raise PortError("Acceptance lookup requires --pr, --base-sha and --head-sha; candidate accounting is not approval")
            print(json.dumps(github_review(args.pr, args.base_sha, args.head_sha, os.environ)))
        return 0
    except (OSError, ValueError, KeyError, PortError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
