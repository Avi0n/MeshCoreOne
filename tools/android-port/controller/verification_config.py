"""AndroidOnly: WP-003 Protected verification-only overlay on the frozen WP-000 generator."""

import argparse
import copy
import json
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError
from controller.schema import digest, load_json
from controller.scope_amendment import (
    apply_translation_scope, current_revisions, is_translation_scope, project_translation_scope,
    project_translation_exclusions,
)

BOOTSTRAP_POLICY_AMENDMENT = {
    "schema_version": 1,
    "amendment_id": "WP-000-capability-reservations-v1",
    "policy_commit_sha": "4658275ca7f753527aa14fd5eacbad3ae606536e",
    "previous_approved_plan_sha256": "0afc364cd63a99f438bffe7df8542d49503bfd113c363ed89e60d284b6a3c837",
    "approved_plan_sha256": "badb1f34f22295d0f7ac4316eba0cc5f6189c9012ac7c96981fb352e9133944c",
    "previous_generated_manifest_sha256": "f3fd3a0a51841a8fb43d3f2c4b3953e6ef564d4e96e74f4e3035200face5c90e",
    "generated_manifest_sha256": "b30b20c2d2225c98e13c52020a251d7bad87febd27e9dc1929c142c19c10d8b6",
    "previous_final_manifest_sha256": "78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746",
    "final_manifest_sha256": "ceb84b5e26fcc9ece5c0b3fb6c68b4d2965f9f24114fa81b7434ff73d1ed7904",
    "semantic_policy_revision": "0a56002d4ba794901880a65a85e68518d36acdfe0ff50b4db42e938522800981",
    "reference_sha": "db14559b39d32322b06477c6ae676112f583db50",
    "work_packages": 65,
    "dependency_edges": 185,
    "agents": 17,
    "human_gates": 8,
}
BOOTSTRAP_MANIFEST_SHA256 = BOOTSTRAP_POLICY_AMENDMENT["generated_manifest_sha256"]
WP_003_BOOTSTRAP_MANIFEST_SHA256 = "c8d7f2baba33c0cf4bfea24c9a7105654adebbcbc39226ea3f8ae397e8c2a061"
POLICY_AMENDMENT_EVIDENCE = Path("docs/android/evidence/WP-000/bootstrap-verifier.json")
VERIFICATION_MANIFEST_SHA256 = "78a22920beaa5899f9618806b5cd2b27d50399a9b29b4d8dbd79f755717ec746"
WP_003_MANIFEST_REVISION = "1dd7bc4f74f10e566b66fc8bc3822bb2bd66068053e1aa866e520326aab4dde6"
PRIOR_CONTENT_MANIFEST_REVISION = "4f8328f7295d2fdecce10489f99d992cd6b2d861c709f32297e21ed9c5b8fdf5"
PRIOR_CONTENT_POLICY_REVISION = "375c9252499e63787449b907755e7bfa0dfb49281f0a46954527480165734428"
WP_003_SEMANTIC_POLICY_REVISION = "08ee454858fa350783ba37e02c0c2720d4b36a29b6f135a8e7a6f7e41c73954d"
CONTENT_SCOPE_PATHS = (
    "android/app/src/main/kotlin/com/meshcoreone/android/app/content/",
    "android/app/src/test/kotlin/com/meshcoreone/android/app/content/",
)
CI = "tools/android-port/controller/ci.py"
AMENDMENTS = {
    "WP-002": {
        "configured": True,
        "commands": [
            ["python", CI, "preflight"],
            ["python", CI, "run", "--stage", "scaffold"],
            ["python", CI, "inspect"],
        ],
        "blocker": "",
    },
    "WP-003": {
        "configured": True,
        "commands": [
            ["python", "tools/android-port/controller/verification_config.py", "--check"],
            ["python", "tools/android-port/controller/workflows.py"],
            ["python", "tools/android-port/controller/test_runner.py"],
            ["python", CI, "python"],
            ["python", CI, "preflight"],
            ["python", CI, "run", "--stage", "scaffold"],
            ["python", CI, "inspect"],
        ],
        "blocker": "",
    },
}
LEGACY_COMMANDS = {
    "WP-002": [
        ["python", CI, "preflight"],
        ["python", CI, "run", "--stage", "verify"],
        ["python", CI, "run", "--stage", "standalone"],
        ["python", CI, "run", "--stage", "assemble"],
        ["python", CI, "run", "--stage", "lint"],
        ["python", CI, "inspect"],
    ],
    "WP-003": [
        ["python", "tools/android-port/controller/verification_config.py", "--check"],
        ["python", "tools/android-port/controller/workflows.py"],
        ["python", "tools/android-port/controller/test_runner.py"],
        ["python", CI, "python"],
        ["python", CI, "preflight"],
        ["python", CI, "run", "--stage", "verify"],
        ["python", CI, "run", "--stage", "standalone"],
        ["python", CI, "run", "--stage", "assemble"],
        ["python", CI, "run", "--stage", "lint"],
        ["python", CI, "inspect"],
    ],
}


def apply_overlay(data: dict, repo: Path = Path(__file__).resolve().parents[3]):
    if is_translation_scope(data):
        return apply_translation_scope(apply_overlay(project_translation_scope(data, repo), repo), repo)
    source = digest(data)
    if source not in (BOOTSTRAP_MANIFEST_SHA256, WP_003_BOOTSTRAP_MANIFEST_SHA256):
        raise PortError("Frozen bootstrap generator changed outside the protected WP-003 overlay")
    result = copy.deepcopy(data)
    for wp in result["work_packages"]:
        if wp["id"] in AMENDMENTS:
            wp["verification"] = (
                copy.deepcopy(AMENDMENTS[wp["id"]])
                if source == WP_003_BOOTSTRAP_MANIFEST_SHA256
                else {**copy.deepcopy(AMENDMENTS[wp["id"]]), "commands": copy.deepcopy(LEGACY_COMMANDS[wp["id"]])}
            )
    expected = (
        WP_003_MANIFEST_REVISION if source == WP_003_BOOTSTRAP_MANIFEST_SHA256
        else BOOTSTRAP_POLICY_AMENDMENT["final_manifest_sha256"]
    )
    if digest(result) != expected:
        raise PortError("Frozen bootstrap final manifest changed outside the protected WP-003 overlay")
    return result


def apply_content_scope(data: dict, repo: Path = Path(__file__).resolve().parents[3]):
    if is_translation_scope(data):
        return apply_translation_scope(apply_content_scope(project_translation_scope(data, repo), repo), repo)
    if not isinstance(data, dict) or digest(data) not in (
            VERIFICATION_MANIFEST_SHA256, BOOTSTRAP_POLICY_AMENDMENT["final_manifest_sha256"],
            WP_003_MANIFEST_REVISION):
        raise PortError("Content scope requires the complete frozen verification manifest")
    result = copy.deepcopy(data)
    wp = next(item for item in result["work_packages"] if item["id"] == "WP-218")
    wp["write_paths"][2:2] = CONTENT_SCOPE_PATHS
    return result


def project_content_scope(data: dict, repo: Path = Path(__file__).resolve().parents[3]):
    if is_translation_scope(data):
        data = project_translation_scope(data, repo)
    if not isinstance(data, dict):
        raise PortError("Malformed content-scope manifest lineage")
    result = copy.deepcopy(data)
    baselines = (
        VERIFICATION_MANIFEST_SHA256,
        BOOTSTRAP_POLICY_AMENDMENT["final_manifest_sha256"],
        WP_003_MANIFEST_REVISION,
    )
    if digest(result) in baselines:
        return result
    packages = result.get("work_packages")
    if not isinstance(packages, list) or not all(isinstance(wp, dict) for wp in packages):
        raise PortError("Malformed content-scope work packages")
    owned = [wp for wp in packages if wp.get("id") == "WP-218"]
    if len(owned) != 1:
        raise PortError("Content scope must preserve exactly one original WP-218")
    paths = owned[0].get("write_paths")
    if not isinstance(paths, list) or paths[2:4] != list(CONTENT_SCOPE_PATHS):
        raise PortError("Content scope must contain only the exact ordered two-prefix addition")
    del paths[2:4]
    if digest(result) not in baselines:
        raise PortError("Content scope changed the frozen manifest outside its exact two-prefix addition")
    return result


def project_policy_amendment(data: dict):
    if not isinstance(data, dict):
        raise PortError("Malformed policy-amendment manifest lineage")
    result = copy.deepcopy(data)
    if digest(result) == VERIFICATION_MANIFEST_SHA256:
        return result
    if digest(result) == WP_003_MANIFEST_REVISION:
        result["reference"]["approved_plan_sha256"] = BOOTSTRAP_POLICY_AMENDMENT["approved_plan_sha256"]
        result["reference"]["installed_plan_sha256"] = "87de92c5236d5891c358df22d72b51bba68f839113660f6c9d1fe6624e1b5213"
        for wp in result["work_packages"]:
            if wp["id"] in LEGACY_COMMANDS:
                wp["verification"]["commands"] = LEGACY_COMMANDS[wp["id"]]
    if digest(result) != BOOTSTRAP_POLICY_AMENDMENT["final_manifest_sha256"]:
        raise PortError("Policy amendment requires the complete frozen WP-000 catalog")
    result["reference"]["approved_plan_sha256"] = BOOTSTRAP_POLICY_AMENDMENT["previous_approved_plan_sha256"]
    result["reference"]["installed_plan_sha256"] = "1d6f194d8c40ecec1618a1650056d37777d8981ce029249755a22d0603b52cd7"
    orchestrator = next(agent for agent in result["agents"] if agent["name"] == "port-orchestrator")
    orchestrator["sha256"] = "8f0bcc7d4359c04094d2f2f644c125e8453a23390b4fefa755890ea53d1ff99e"
    if digest(result) != VERIFICATION_MANIFEST_SHA256:
        raise PortError("Policy amendment changed the historical catalog outside its exact metadata fields")
    return result


def content_scope_predecessor(manifest):
    from controller.model import Manifest

    exclusions = (
        project_translation_exclusions(manifest.exclusions, manifest.repo)
        if is_translation_scope(manifest.data) else manifest.exclusions
    )
    return Manifest(project_policy_amendment(project_content_scope(manifest.data, manifest.repo)), exclusions, manifest.repo)


def content_scope_manifest_revision(manifest):
    from controller.model import Manifest

    if is_translation_scope(manifest.data):
        project_translation_scope(manifest.data, manifest.repo)
        return manifest.sha256
    data = project_content_scope(manifest.data, manifest.repo)
    project_policy_amendment(data)
    baseline = Manifest(data, manifest.exclusions, manifest.repo)
    if baseline.sha256 == VERIFICATION_MANIFEST_SHA256:
        return manifest.sha256
    return PRIOR_CONTENT_MANIFEST_REVISION


def content_scope_revisions(manifest, policy):
    from controller.gates import policy_revision
    from controller.model import Manifest

    if is_translation_scope(manifest.data):
        return current_revisions(manifest, policy)
    data = project_content_scope(manifest.data, manifest.repo)
    project_policy_amendment(data)
    baseline = Manifest(data, manifest.exclusions, manifest.repo)
    historical = baseline.sha256 == VERIFICATION_MANIFEST_SHA256
    expected = ("56bdc53548bc86d631245795dfa38b4fc86048e0e7cbe1c7d5695879b035b42a"
                if historical else WP_003_SEMANTIC_POLICY_REVISION)
    if policy_revision(baseline, policy) != expected:
        raise PortError("Frozen predecessor policy changed outside the approved content scope")
    if historical:
        return {"manifest_sha256": manifest.sha256, "policy_revision": policy_revision(manifest, policy)}
    return {
        "manifest_sha256": PRIOR_CONTENT_MANIFEST_REVISION,
        "policy_revision": PRIOR_CONTENT_POLICY_REVISION,
    }


def inventory_details_predecessor(details, manifest):
    predecessor = content_scope_predecessor(manifest)
    if not isinstance(details, dict) or details.get("source_sha") != predecessor.data["reference"]["commit"]:
        raise PortError("Original inventory details do not bind the actual canonical catalog")
    return copy.deepcopy(details)


def check_configuration(repo: Path):
    from bootstrap import build_inventory

    generated, exclusions = build_inventory(repo)
    expected = apply_overlay(generated, repo)
    actual = load_json(repo / "docs" / "android" / "port-manifest.json")
    amendment = load_json(repo / POLICY_AMENDMENT_EVIDENCE)
    if amendment != BOOTSTRAP_POLICY_AMENDMENT:
        raise PortError("Protected bootstrap policy amendment evidence drift")
    if actual != apply_content_scope(expected, repo) or load_json(repo / "docs" / "android" / "not-ported.json") != exclusions:
        raise PortError("Protected verification overlay or frozen inventory drift")
    return {
        "schema_version": 1, "result": "valid", "bootstrap_manifest_sha256": digest(generated),
        "manifest_sha256": digest(actual), "verification_amendments": sorted(AMENDMENTS),
        "content_scope_amended": actual != expected,
        "policy_amendment": amendment["amendment_id"],
        "translation_scope_removed": is_translation_scope(actual),
        "feature_verification_configured": False, "human_or_dependency_acceptance": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--check", action="store_true", help="Read-only; never refresh the frozen bootstrap")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(check_configuration(args.repo), indent=2))
        return 0
    except (PortError, OSError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
