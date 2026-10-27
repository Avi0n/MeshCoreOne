"""AndroidOnly: WP-004 Fail-safe scopes for the single Android candidate workflow."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError
from controller.schema import load_json
from oracle.codec_harness import (
    CODEC_TYPES, CRYPTO_TEST_PATH, EXACT_TYPES, HELPERS, HELPER_ROOT,
    SOURCE_TEST_PATH, WHOLE_FILES,
)

SCOPES = ("controller", "scaffold", "protocol", "backup", "external-oracle")
SHA = re.compile(r"[0-9a-f]{40}")
ZERO = "0" * 40
ALL_SCOPE_INPUTS = {
    ".github/workflows/android-ci.yml",
    "tools/android-port/controller/ci.py",
    "tools/android-port/oracle/workflow_scope.py",
}
SHARED_GRADLE_INPUTS = {
    "android/settings.gradle.kts",
    "android/build.gradle.kts",
    "android/gradle.properties",
    "android/gradle/libs.versions.toml",
    "android/gradle/verification-metadata.xml",
    "android/gradle/wrapper/gradle-wrapper.properties",
}
COMMON_ORACLE_INPUTS = {
    "tools/android-port/oracle/reference.py",
    "tools/android-port/oracle/identity.py",
}
ANDROID_DOCUMENTATION_INPUTS = {
    "android/README.md",
    "android/scaffold/README.md",
    "android/tools/meshcli/README.md",
}


def codec_inputs():
    return (
        set(WHOLE_FILES) | set(CODEC_TYPES) | set(EXACT_TYPES)
        | {HELPER_ROOT + name for name in HELPERS}
        | {
            CRYPTO_TEST_PATH, SOURCE_TEST_PATH, "LICENSE", "MeshCore/LICENSE",
            "tools/android-port/oracle/codec_harness.py",
            "tools/android-port/oracle/CodecOracle.swift",
            "tools/android-port/oracle/swift.py",
            "tools/android-port/oracle/test_codec.py",
        }
        | COMMON_ORACLE_INPUTS
    )


RULES = {
    "controller": {
        "exact": {
            ".github/copilot-instructions.md",
            "docs/android/PORTING_PLAN.md",
            "docs/android/automation-policy.json",
            "tools/android-port/controller/requirements-ci.txt",
            "tools/android-port/inventory_rules.py",
        },
        "prefix": (
            ".github/workflows/",
            "tools/android-port/controller/",
            "tools/android-port/local/",
            "tools/android-port/tests/",
            "docs/android/adr/",
            "docs/android/build/",
            "docs/android/ci/",
            "android/parity-tests/", "docs/android/parity/",
        ),
    },
    "scaffold": {
        "exact": {
            "tools/android-port/oracle/run_tests.py",
            "tools/android-port/oracle/foundation_ci.py",
            "tools/android-port/oracle/test_ci.py",
            "tools/android-port/oracle/test_inventory_tools.py",
            "tools/android-port/oracle/test_vectors.py",
            "tools/android-port/oracle/test_workflow_scope.py",
            "tools/android-port/test_inventory.py",
            "tools/android-port/extract_vectors.py",
            *SHARED_GRADLE_INPUTS,
            *COMMON_ORACLE_INPUTS,
        },
        "prefix": (
            "android/app/", "android/build-logic/", "android/core/testing/",
            "android/core/contracts/", "android/core/designsystem/", "android/core/model/",
            "android/core/ble/", "android/core/connectivity/", "android/core/data/",
            "android/core/database/", "android/core/datastore/", "android/core/l10n/",
            "android/core/runtime/", "android/core/ui/",
            "android/core/services/",
            "android/feature/", "android/platform/",
            "android/parity-tests/",
        ),
    },
    "external-oracle": {"exact": codec_inputs(), "prefix": ()},
    "protocol": {
        "exact": {
            "tools/android-port/oracle/test_vectors.py",
            "tools/android-port/extract_vectors.py",
        },
        "prefix": (
            "android/core/protocol/",
            "MeshCore/Sources/MeshCore/Protocol/",
            "MeshCore/Tests/MeshCoreTests/Protocol/",
        ),
    },
    "backup": {
        "exact": {
            "tools/android-port/oracle/wp203_ci.py",
            "tools/android-port/oracle/wp203_interop.py",
            "tools/android-port/oracle/tests/test_wp203_ci.py",
            "tools/android-port/oracle/tests/test_wp203_interop.py",
            *codec_inputs(),
        },
        "prefix": (
            "android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/backup/",
            "android/core/data/src/test/kotlin/com/meshcoreone/android/core/data/backup/",
            "android/core/database/",
            "MC1Services/Sources/",
            "MC1Services/Tests/MC1ServicesTests/Helpers/",
            "MeshCore/Sources/",
            "MeshCore/Tests/MeshCoreTestSupport/",
            "MeshCore/Tests/MeshCoreTests/",
        ),
    },
}


def all_scopes(reason, *, fail_safe=True):
    return {
        "scopes": {scope: True for scope in SCOPES},
        "reason": reason,
        "fail_safe": fail_safe,
    }


def endpoints(event_name: str, event: dict):
    if event_name == "workflow_dispatch":
        return None
    try:
        if event_name == "pull_request":
            base = event["pull_request"]["base"]["sha"]
            head = event["pull_request"]["head"]["sha"]
        elif event_name == "merge_group":
            base, head = event["merge_group"]["base_sha"], event["merge_group"]["head_sha"]
        elif event_name == "push":
            base, head = event["before"], event["after"]
            if base == ZERO:
                raise PortError("Push has no immutable before commit")
        else:
            raise PortError("Unknown workflow event")
    except (KeyError, TypeError) as error:
        raise PortError("Malformed workflow event endpoints") from error
    if not isinstance(base, str) or not isinstance(head, str) or not SHA.fullmatch(base) or not SHA.fullmatch(head):
        raise PortError("Malformed workflow event endpoint SHA")
    return base, head


def classify(paths):
    normalized = sorted(set(paths))
    if not all(isinstance(path, str) and path and "\\" not in path for path in normalized):
        raise PortError("Malformed changed path")
    if set(normalized) & ALL_SCOPE_INPUTS:
        return {scope: True for scope in SCOPES}
    result = {}
    matched_paths = set()
    for scope, rules in RULES.items():
        matches = [
            path for path in normalized if
            path in rules["exact"] or any(path.startswith(prefix) for prefix in rules["prefix"])
        ]
        result[scope] = bool(matches)
        matched_paths.update(matches)
    unclassified = [
        path for path in normalized
        if path.startswith("android/") and path not in ANDROID_DOCUMENTATION_INPUTS
        and path not in matched_paths
    ]
    if unclassified:
        raise PortError("Unclassified Android inputs require full verification: " + ", ".join(unclassified))
    if set(normalized) & SHARED_GRADLE_INPUTS:
        result.update({"scaffold": True, "protocol": True, "backup": True})
    return result


def changed_paths(repo: Path, base: str, head: str):
    completed = subprocess.run(
        ["git", "-C", str(repo), "diff", "--name-only", "--no-renames", base, head, "--"],
        check=True, capture_output=True, text=True,
    )
    return [line for line in completed.stdout.splitlines() if line]


def merge_base(repo: Path, base: str, head: str):
    completed = subprocess.run(
        ["git", "-C", str(repo), "merge-base", "--all", base, head],
        check=True, capture_output=True, text=True,
    )
    candidates = completed.stdout.splitlines()
    if len(candidates) != 1 or not SHA.fullmatch(candidates[0]):
        raise PortError("PR scope requires exactly one complete Git merge base")
    return candidates[0]


def evaluate(repo: Path, event_name: str, event: dict):
    try:
        pair = endpoints(event_name, event)
        if pair is None:
            return all_scopes("workflow_dispatch", fail_safe=False)
        base, head = pair
        diff_base = merge_base(repo, base, head) if event_name == "pull_request" else base
        paths = changed_paths(repo, diff_base, head)
        return {
            "scopes": classify(paths), "reason": "changed-input-classification",
            "fail_safe": False, "base": base, "head": head,
            "diff_base": diff_base, "paths": paths,
        }
    except (OSError, PortError, subprocess.SubprocessError, UnicodeError) as error:
        return all_scopes(f"fail-safe: {error}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--event-path", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        event = load_json(args.event_path)
    except (OSError, PortError, UnicodeError) as error:
        result = all_scopes(f"fail-safe: {error}")
    else:
        result = evaluate(args.repo, args.event_name, event)
    with args.output.open("a", encoding="utf-8", newline="\n") as stream:
        for scope in SCOPES:
            stream.write(f"{scope}={'true' if result['scopes'][scope] else 'false'}\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
