import argparse
import json
import os
import sys
import unittest
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

NATIVE_SUITE = "android/accessibility-tests"
NATIVE_INPUTS = (NATIVE_SUITE + "/", "docs/android/accessibility/", "docs/android/evidence/WP-503/")


def candidate_suite(repo, event_name, event):
    from controller.errors import PortError
    from controller.model import SHA, git, tree
    from oracle.workflow_scope import changed_paths, endpoints

    pair = endpoints(event_name, event)
    if pair is None:
        if (
            not isinstance(event, dict) or not isinstance(event.get("ref"), str)
            or not event["ref"].strip()
            or any(key in event for key in ("pull_request", "merge_group", "before", "after"))
        ):
            raise PortError("Malformed manual workflow event; no PR endpoints may be substituted")
        head = os.environ.get("GITHUB_SHA", "")
        if SHA.fullmatch(head) is None:
            raise PortError("Manual suite selection requires immutable GITHUB_SHA commit provenance")
        base = None
    else:
        base, head = pair
    if git(repo, "rev-parse", "HEAD").decode().strip() != head:
        raise PortError("Native suite selection head differs from the actual checkout")
    if base is None and git(repo, "cat-file", "-t", head).decode().strip() != "commit":
        raise PortError("Manual suite selection requires a commit object")
    before, after = (tree(repo, base) if base is not None else {}), tree(repo, head)
    def family(path):
        return path == NATIVE_SUITE or path.startswith(NATIVE_SUITE + "/")

    committed = {path: blob for path, blob in after.items() if family(path)}
    relevant = any(
        family(path) or path == "docs/android/deviations/WP-503.md"
        or path.startswith(NATIVE_INPUTS)
        for path in (changed_paths(repo, base, head) if base is not None else ())
    )
    selected = bool(committed) or any(family(path) for path in before) or relevant
    root = repo.resolve() / NATIVE_SUITE
    if selected:
        if not committed or not root.is_dir() or root.is_symlink() or root.resolve() != root:
            raise PortError("Selected native suite is missing/deleted or not a committed regular directory")
        actual = set()
        for path in root.rglob("*"):
            if path.is_symlink() or path.resolve() != path:
                raise PortError("Selected native suite contains a noncanonical linked path")
            if not path.is_dir() and not path.is_file():
                raise PortError("Selected native suite contains a nonregular entry")
            if path.is_file() and "__pycache__" not in path.relative_to(root).parts:
                actual.add(path.relative_to(repo.resolve()).as_posix())
        if actual != set(committed):
            raise PortError("Selected native suite checkout differs from its committed file set")
        for path, blob in committed.items():
            local, original = (repo / path).read_bytes(), git(repo, "cat-file", "blob", blob)
            if path.endswith(".py"):
                local, original = local.replace(b"\r\n", b"\n"), original.replace(b"\r\n", b"\n")
            if local != original:
                raise PortError("Selected native suite bytes differ from the committed head: " + path)
    elif root.exists() or root.is_symlink():
        raise PortError("Uncommitted native family cannot be reported NOT SELECTED")
    return {
        "suite": NATIVE_SUITE, "selected": selected, "base": base, "head": head,
        "result": "SELECTED" if selected else "NOT SELECTED",
        "reason": (
            "present-in-manual-commit" if selected else "absent-from-manual-commit"
        ) if base is None else (
            "present-in-base-or-head-or-relevant-change" if selected else "absent-at-both-endpoints-and-no-family-change"
        ),
        "committed_files": len(committed),
    }


def run_suite(suite, verbosity=2):
    discovered = suite.countTestCases()
    if discovered == 0:
        print("BLOCKED: zero discovered controller tests", file=sys.stderr)
        return 2
    result = unittest.TextTestRunner(verbosity=verbosity).run(suite)
    print(json.dumps({
        "discovered": discovered, "run": result.testsRun,
        "passed": result.testsRun - len(result.failures) - len(result.errors) - len(result.skipped),
        "failed": len(result.failures), "errors": len(result.errors), "skipped": len(result.skipped),
        "scope": "controller fixtures only; not Android/iOS/hardware parity",
    }))
    return 0 if result.wasSuccessful() and result.testsRun == discovered and not result.skipped else 2


def discover_suites(directories):
    suite = unittest.TestSuite()
    seen = set()
    for directory in directories:
        directory = directory.resolve()
        if directory in seen:
            raise ValueError("duplicate controller test directory")
        seen.add(directory)
        if not directory.is_dir():
            raise ValueError("missing controller test directory")
        discovered = unittest.TestLoader().discover(str(directory), pattern="*test*.py")
        if discovered.countTestCases() == 0:
            raise ValueError("zero discovered tests in controller test directory")
        suite.addTests(discovered)
    return suite


def main():
    parser = argparse.ArgumentParser(description="Stdlib controller test runner; missing/zero/skipped tests fail")
    parser.add_argument("--tests", type=Path, default=Path(__file__).resolve().parents[1] / "tests")
    parser.add_argument("--include-tests", type=Path, action="append", default=[])
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--candidate-event-name")
    parser.add_argument("--candidate-event-path", type=Path)
    parser.add_argument("--scope-output", type=Path, help="Select scopes only; never executes a test suite")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    from controller.errors import PortError
    from controller.schema import load_json

    try:
        directories = [args.tests, *args.include_tests]
        if args.candidate_event_name or args.candidate_event_path or args.scope_output:
            if not args.candidate_event_name or args.candidate_event_path is None:
                raise PortError("Candidate selection requires both event name and event path")
            selection = candidate_suite(args.repo, args.candidate_event_name, load_json(args.candidate_event_path))
            print(json.dumps(selection))
            if args.scope_output is not None:
                with args.scope_output.open("a", encoding="utf-8", newline="\n") as stream:
                    stream.write(f"accessibility-suite={str(selection['selected']).lower()}\n")
                return 0
            if selection["selected"]:
                directories.append(args.repo / NATIVE_SUITE)
        suite = discover_suites(directories)
    except (ImportError, OSError, ValueError, PortError) as error:
        print(f"BLOCKED: controller discovery failed: {error}", file=sys.stderr)
        return 2
    return run_suite(suite, verbosity=0 if args.quiet else 2)


if __name__ == "__main__":
    sys.exit(main())
