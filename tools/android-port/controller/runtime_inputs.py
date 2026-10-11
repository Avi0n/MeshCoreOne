"""AndroidOnly: WP-003 Required runtime inputs must exist in the immutable Git tree."""

import argparse
import hashlib
import json
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError
from controller.model import SHA, git, tree

CONTROLLER_MODULES = (
    "__init__", "apk_alignment", "authority", "backends", "capabilities", "ci", "ci_environment", "ci_evidence",
    "dispatch", "engine", "errors", "gate_runtime", "gates", "historical", "ledger", "model", "module_junit",
    "paths", "publication", "provision", "render", "runtime_inputs", "schema", "scope_amendment", "settings",
    "staging", "test_runner", "validate", "verification_config", "workflows",
)
FIXED_INPUTS = (
    ".github/workflows/android-ci.yml", ".github/workflows/copilot-setup-steps.yml",
    "tools/android-port/controller/toolchain-pins.json",
    "tools/android-port/controller/requirements-ci.txt",
    "tools/android-port/controller/gradle_windows.ps1",
    "tools/android-port/controller/verify_candidate_task_graph.gradle",
    "tools/android-port/local/reserve.py",
    "tools/android-port/local/hook_test.py",
    "tools/android-port/local/run.sh",
    "tools/android-port/oracle/workflow_scope.py",
    "tools/android-port/bootstrap.py", "tools/android-port/inventory_rules.py",
    "tools/android-port/wp_metadata.py", "tools/android-port/portmap.py",
    "docs/android/PORTING_PLAN.md", "docs/android/port-manifest.json",
    "docs/android/port-manifest.schema.json", "docs/android/not-ported.json",
    "docs/android/not-ported.schema.json", "docs/android/automation-policy.json",
    "docs/android/scope-amendments/translation-removal.json",
    "android/scaffold/environment-allowlist.json", "android/scaffold/check_environment.py",
    "android/scaffold/inspect_apk.py", "android/scaffold/sync_notices.py",
    "android/gradlew", "android/gradlew.bat", "android/settings.gradle.kts",
    "android/build.gradle.kts", "android/gradle.properties",
    "android/gradle/wrapper/gradle-wrapper.jar", "android/gradle/wrapper/gradle-wrapper.properties",
    "android/gradle/libs.versions.toml", "android/gradle/verification-metadata.xml",
    "android/build-logic/gradle/verification-metadata.xml",
    "docs/android/evidence/WP-201/collect_evidence.py",
    "tools/android-port/tests/test_domain_room_evidence.py",
)


def required_inputs():
    return set(FIXED_INPUTS) | {
        f"tools/android-port/controller/{name}.py" for name in CONTROLLER_MODULES
    } | {
        f".github/skills/{name}/SKILL.md"
        for name in ("android-port-wp", "swift-to-kotlin", "compose-from-swiftui")
    }


def verify_tree_entries(entries: dict, required=None):
    missing = sorted((required_inputs() if required is None else set(required)) - entries.keys())
    if missing:
        raise PortError("Required CI runtime inputs are absent from the committed Git tree: " + ", ".join(missing))
    if any(not isinstance(entries[path], str) or SHA.fullmatch(entries[path]) is None
           for path in (required_inputs() if required is None else set(required))):
        raise PortError("Malformed committed runtime blob identity")


def verify_committed_inputs(repo: Path, revision=None, *, compare_checkout=True):
    revision = revision or git(repo, "rev-parse", "HEAD").decode().strip()
    entries = tree(repo, revision)
    required = required_inputs() | {
        path for path in entries if path.startswith(".github/agents/") and path.endswith(".agent.md")
    }
    verify_tree_entries(entries, required)
    if compare_checkout:
        for path in sorted(required):
            actual = repo / Path(path)
            if not actual.is_file() or actual.is_symlink():
                raise PortError("Committed runtime input is missing/unsafe in checkout: " + path)
            committed = git(repo, "cat-file", "blob", entries[path])
            local = actual.read_bytes()
            if actual.suffix != ".jar":
                local = local.replace(b"\r\n", b"\n")
                committed = committed.replace(b"\r\n", b"\n")
            if hashlib.sha256(local).digest() != hashlib.sha256(committed).digest():
                raise PortError("Runtime checkout differs from its immutable Git input: " + path)
    return {
        "schema_version": 1, "result": "valid", "git_revision": revision,
        "required_inputs": len(required), "checkout_matches_committed_inputs": compare_checkout,
        "scope": "runtime artifact delivery only; not human/WP/activation approval",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--revision", help="Explicit immutable commit/tree SHA; HEAD by default")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(verify_committed_inputs(args.repo, args.revision), indent=2))
        return 0
    except (PortError, OSError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
