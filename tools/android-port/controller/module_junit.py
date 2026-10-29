"""AndroidOnly: WP-003 Retain every active root-module unit suite without claiming source parity."""

import os
import re
import stat
from pathlib import Path

from .errors import PortError
from .model import SHA, git
from .paths import git_path

MODULE_SOURCE = re.compile(r"android/((?:core|feature|platform)/[a-z][a-z0-9-]*)/(.+)")
UNIT_SOURCE = re.compile(r"src/(?:test|testDebug)/(?:java|kotlin)/.+\.(?:java|kt)")
SEPARATE_SUITES = {"core/contracts", "core/protocol", "core/testing"}
REPORT_LOCATIONS = ("build/test-results/test", "build/test-results/testDebugUnitTest")


def module_sources(repo: Path, revision=None):
    revision = revision or git(repo, "rev-parse", "HEAD").decode().strip()
    if not isinstance(revision, str) or SHA.fullmatch(revision) is None:
        raise PortError("Module unit evidence requires an immutable candidate commit")
    entries, active = {}, set()
    for entry in git(repo, "ls-tree", "-rz", "--full-tree", revision, "--", "android").split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        path = git_path(raw_path.decode("utf-8"))
        match = MODULE_SOURCE.fullmatch(path)
        if match is None or match[1] in SEPARATE_SUITES:
            continue
        module, relative = match.groups()
        if relative != "build.gradle.kts" and not relative.startswith(("src/main/", "src/test/", "src/testDebug/")):
            continue
        mode, kind, blob = metadata.decode("ascii").split()
        if mode not in ("100644", "100755") or kind != "blob" or SHA.fullmatch(blob) is None:
            raise PortError("Unsafe/non-file committed module test input: " + path)
        entries.setdefault(module, {})[path] = blob
        if UNIT_SOURCE.fullmatch(relative):
            active.add(module)
    return {module: dict(sorted(entries[module].items())) for module in sorted(active)}


def check_module_tests(repo: Path):
    from .ci_evidence import suite_counts

    expected = module_sources(repo)
    paths = sorted({path for inputs in expected.values() for path in inputs})
    if paths and git(repo, "diff", "--name-only", "HEAD", "--", *paths).strip():
        raise PortError("Compiled module inputs differ from the immutable candidate")
    results = {}
    for module, inputs in expected.items():
        module_root = repo / "android" / module
        candidates = [
            module_root / relative for relative in REPORT_LOCATIONS
            if any((module_root / relative).glob("TEST-*.xml"))
        ]
        if len(candidates) != 1:
            raise PortError("Missing/ambiguous actual unit runner for active module: " + module)
        results[module] = suite_counts(candidates[0], 1)
    return results


def module_method_inventory(repo: Path, modules):
    from .ci_evidence import SUITES, suite_case_ids

    result = {
        "app": suite_case_ids(repo / "android" / SUITES["app"][0]),
        "core/contracts": suite_case_ids(repo / "android" / SUITES["contracts"][0]),
    }
    for module in modules:
        locations = [
            repo / "android" / module / relative for relative in REPORT_LOCATIONS
            if any((repo / "android" / module / relative).glob("TEST-*.xml"))
        ]
        if len(locations) != 1:
            raise PortError("Missing/ambiguous executed method inventory for module: " + module)
        result[module] = suite_case_ids(locations[0])
    return result


def linked(path: Path):
    if path.is_symlink():
        return True
    return os.name == "nt" and path.exists() and bool(path.lstat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def bounded_directory(directory: Path, root: Path):
    if not directory.resolve().is_relative_to(root.resolve()):
        raise PortError("Unit report directory escapes its bounded root")
    for parent in (directory, *directory.parents):
        if linked(parent):
            raise PortError("Linked unit-report directory is not evidence")
        if parent == root:
            break


def safe_reports(directory: Path, root: Path):
    bounded_directory(directory, root)
    files = sorted(directory.glob("TEST-*.xml"), key=lambda path: path.name)
    if not files:
        raise PortError("Missing mandatory active-module JUnit reports: " + str(directory))
    if any(linked(path) or not path.is_file() for path in files):
        raise PortError("Unsafe active-module JUnit report")
    return files


def report_record(root: Path, directory: Path):
    from .ci_environment import file_sha256
    from .ci_evidence import suite_counts

    files = safe_reports(directory, root)
    return {
        "counts": suite_counts(directory, 1),
        "reports": [{
            "path": path.relative_to(root).as_posix(),
            "size": path.stat().st_size,
            "sha256": file_sha256(path),
        } for path in files],
    }
