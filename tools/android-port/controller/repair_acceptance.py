"""AndroidOnly: WP-003 Exact change-scoped repair admission, never whole-WP completion."""

import base64
import hashlib
import json
import os
import re
import subprocess
import threading
from dataclasses import asdict

from .capabilities import CapabilityEngine
from .ci_evidence import counts
from .errors import PortError
from .gates import (
    Binding, _acceptance, hosted_references, human_acceptance_required, policy_revision,
    protected_paths, validate_approval, validate_review,
)
from .model import SHA, git
from .paths import git_path, permits
from .publication import bounded_output, decode_structured_body
from .render import marker
from .schema import decode_json, digest, fields, nonempty, positive_integer

SCOPE_MARKER = "<!-- android-port-repair-scope-v1 -->\n"
COMPRESSED_SCOPE_MARKER = "<!-- android-port-repair-scope-zlib-v1 -->\n"
VERIFICATION = {
    "android-scaffold": {
        "command": ["python", "tools/android-port/controller/ci.py", "run", "--stage", "scaffold"],
        "job": "One Linux Android build",
    },
}
MAX_LOG_BYTES = 64 * 1024 * 1024
ANDROID_MODULE = r"android/((?:core|feature|platform)/[a-z0-9-]+|app)/"


def verification_only(scope):
    version = scope.get("schema_version")
    if type(version) is not int or (version, scope.get("kind")) not in (
        (1, "landed-wp-repair"), (2, "landed-wp-verification"), (3, "whole-wp"),
    ):
        raise PortError("Unknown bounded repair scope version/kind")
    return version == 2


def production_bindings(scope):
    if not isinstance(scope["coverage"], list) or not scope["coverage"]:
        raise PortError("Verification-only additions require production consumer coverage")
    bindings = {}
    for entry in scope["coverage"]:
        consumers = entry.get("production_bindings") if isinstance(entry, dict) else None
        if not isinstance(consumers, dict) or not consumers:
            raise PortError("Every verification-only coverage entry needs unchanged production bindings")
        for path, blob in consumers.items():
            if (
                re.fullmatch(ANDROID_MODULE + r"src/main/.+", git_path(path)) is None
                or not isinstance(blob, str) or SHA.fullmatch(blob) is None
                or path in bindings and bindings[path] != blob
            ):
                raise PortError("Malformed/conflicting verification-only production binding")
            bindings[path] = blob
    return bindings


def repair_producer_bindings(scope, manifest):
    bindings = scope.get("producer_bindings", {})
    if not isinstance(bindings, dict) or ("producer_bindings" in scope and not bindings):
        raise PortError("Malformed repair producer bindings")
    if not bindings:
        return bindings
    wp = manifest.wp(scope["binding"]["work_package"])
    for path, blob in bindings.items():
        if (
            re.fullmatch(ANDROID_MODULE + r"src/main/.+\.kt", git_path(path)) is None
            or not permits(wp["write_paths"], path)
            or not isinstance(blob, str) or SHA.fullmatch(blob) is None
        ):
            raise PortError("Repair producer must be a primary-owned Android source with an exact blob")
    return bindings


def scoped_candidate_sources(scope):
    sources = {}

    def add(path, blob):
        if path in sources and sources[path] != blob:
            raise PortError("Conflicting scoped candidate source identities")
        sources[path] = blob

    for entry in scope["coverage"]:
        for method in entry["hosted_methods"]:
            add(method["path"], method["blob_sha"])
        for path, blob in entry.get("production_bindings", {}).items():
            add(path, blob)
    for path, blob in scope.get("producer_bindings", {}).items():
        add(path, blob)
    for observation in scope["native"]:
        for path, blob in observation["bindings"].items():
            add(path, blob)
    return sources


def scoped_retained_sources(scope):
    sources = {}
    for observation in scope["native"]:
        fixture = observation["fixture"]
        key = (observation["revision"], fixture["path"])
        if key in sources and sources[key] != fixture["blob_sha"]:
            raise PortError("Conflicting retained native fixture identities")
        sources[key] = fixture["blob_sha"]
    return sources


def authenticated_scope(native, comment_id, binding):
    positive_integer(comment_id, "Repair scope comment ID")
    comment = native.get(f"/issues/comments/{comment_id}")
    if comment.get("id") != comment_id:
        raise PortError("Repair scope comment identity does not match")
    body = bounded_output(comment.get("body"))
    if not body.startswith((SCOPE_MARKER, COMPRESSED_SCOPE_MARKER)):
        raise PortError("Missing explicit bounded repair scope")
    scope = decode_structured_body(body, SCOPE_MARKER, COMPRESSED_SCOPE_MARKER)
    fields(scope, {
        "schema_version", "kind", "binding", "repair_pr", "repair_issue", "landed_pr", "owner",
        "summary", "paths", "acceptance", "coverage", "verification", "native",
    }, optional={"producer_bindings"}, label="bounded repair scope")
    verification = verification_only(scope)
    if verification and "producer_bindings" in scope:
        raise PortError("Verification-only scopes use production bindings, not repair producer bindings")
    if (
        Binding.parse(scope["binding"]) != binding
        or scope["owner"] != native.manifest.wp(binding.work_package)["owner"]
    ):
        raise PortError("Stale repair binding or noncanonical WP owner")
    for key in ("repair_pr", "repair_issue", "landed_pr"):
        positive_integer(scope[key], key)
    author = comment.get("user")
    coordinator = (
        isinstance(author, dict) and author.get("type") == "User"
        and author.get("login") in native.policy["review_policy"]["authorized_coordinators"]
    )
    if not coordinator:
        if scope["repair_issue"] != scope["repair_pr"]:
            raise PortError("An issue scope requires an authenticated authorized coordinator")
        pr = native.get(f"/pulls/{scope['repair_pr']}")
        owner = pr.get("user")
        if (
            pr.get("number") != scope["repair_pr"] or not isinstance(author, dict)
            or not isinstance(owner, dict) or author.get("type") not in ("User", "Bot")
            or owner.get("type") != author["type"]
            or type(author.get("id")) is not int or author["id"] < 1
            or owner.get("id") != author["id"]
        ):
            raise PortError("PR scope must be authored by the actual PR author or authorized coordinator")
    if comment.get("issue_url") != (
        f"https://api.github.com/repos/{binding.repository}/issues/{scope['repair_issue']}"
    ):
        raise PortError("Scope comment is not on its original repair issue")
    issue = native.get(f"/issues/{scope['repair_issue']}")
    if issue.get("number") != scope["repair_issue"] or (
        ("pull_request" in issue) != (scope["repair_issue"] == scope["repair_pr"])
    ):
        raise PortError("Repair scope must be on its own issue or the repair PR")
    landed = native.get(f"/pulls/{scope['landed_pr']}")
    if (
        landed.get("number") != scope["landed_pr"] or scope["landed_pr"] == scope["repair_pr"]
        or landed.get("merged") is not True
        or not (
            marker(binding.work_package) in (landed.get("body") or "")
            or (landed.get("title") or "").startswith(f"[{binding.work_package}] ")
        )
        or landed.get("base", {}).get("repo", {}).get("full_name") != binding.repository
        or landed.get("base", {}).get("ref") != native.policy["default_branch"]
        or not isinstance(landed.get("merge_commit_sha"), str)
        or SHA.fullmatch(landed["merge_commit_sha"]) is None
    ):
        raise PortError("Repair must identify an actual already-landed canonical WP PR")
    comparison = native.get(f"/compare/{landed['merge_commit_sha']}...{binding.base_sha}")
    if comparison.get("status") not in ("ahead", "identical"):
        raise PortError("Landed WP producer is not an ancestor of this repair base")
    prior_paths = {
        entry.get("filename") for entry in native.backend.pages(
            f"{native.prefix}/pulls/{scope['landed_pr']}/files",
        )
    }
    repaired_sources = {
        entry["path"] for entry in scope["paths"]
        if isinstance(entry, dict) and isinstance(entry.get("path"), str) and "/src/main/" in entry["path"]
    } if isinstance(scope["paths"], list) else set()
    if verification:
        bindings = production_bindings(scope)
        if any(not set(entry["production_bindings"]).intersection(prior_paths) for entry in scope["coverage"]):
            raise PortError("Verification consumer is not accounted for by the actual landed producer")
        for path, blob in bindings.items():
            for revision in (landed["merge_commit_sha"], binding.base_sha, binding.head_sha):
                entry = git(native.manifest.repo, "ls-tree", "-z", "--full-tree", revision, "--", path)
                if entry not in {
                    f"{mode} blob {blob}\t{path}\0".encode("utf-8") for mode in ("100644", "100755")
                }:
                    raise PortError("Verification consumer lacks an exact regular Git blob at the bound revision")
                actual = native.get(f"/contents/{path}?ref={revision}")
                if actual.get("type") != "file" or actual.get("path") != path or actual.get("sha") != blob:
                    raise PortError("Verification consumer differs from the landed producer/current base/head")
    else:
        bindings = repair_producer_bindings(scope, native.manifest)
        if not repaired_sources:
            raise PortError("Repair production paths are missing; test-only work requires verification-only scope")
        if not repaired_sources.intersection(prior_paths) and not bindings:
            raise PortError("Repair production paths require a changed or explicitly bound landed producer source")
        if bindings.keys() - prior_paths:
            raise PortError("Repair producer binding is not accounted for by the actual landed producer")
        for path, blob in bindings.items():
            for revision in (binding.base_sha, binding.head_sha):
                entry = git(native.manifest.repo, "ls-tree", "-z", "--full-tree", revision, "--", path)
                if entry not in {
                    f"{mode} blob {blob}\t{path}\0".encode("utf-8") for mode in ("100644", "100755")
                }:
                    raise PortError("Repair producer lacks an unchanged regular Git blob at base and head")
                actual = native.get(f"/contents/{path}?ref={revision}")
                if actual.get("type") != "file" or actual.get("path") != path or actual.get("sha") != blob:
                    raise PortError("Repair producer differs from the current base/head")
    nonempty(scope["summary"], "Bounded repair purpose")
    return scope


def validate_scope(scope, binding, manifest, policy, changed_paths, catalog):
    verification = verification_only(scope)
    whole_wp = scope.get("kind") == "whole-wp"
    if whole_wp:
        fields(scope, {"schema_version", "kind", "binding", "owner", "paths", "acceptance", "coverage",
                       "verification", "native"}, label="whole-WP verification scope")
    if verification:
        production_bindings(scope)
    if (
        Binding.parse(scope["binding"]) != binding
        or binding.source_sha != manifest.data["reference"]["commit"]
        or binding.manifest_sha256 != manifest.sha256 or binding.policy_revision != policy_revision(manifest, policy)
    ):
        raise PortError("Stale bounded repair scope")
    if not verification and not whole_wp:
        repair_producer_bindings(scope, manifest)
    if (
        not isinstance(changed_paths, list) or not changed_paths
        or not all(isinstance(path, str) for path in changed_paths)
        or len(set(changed_paths)) != len(changed_paths)
    ):
        raise PortError("Malformed/empty repair changed-path set")
    wp = manifest.wp(binding.work_package)
    if scope["owner"] != wp["owner"]:
        raise PortError("Repair owner differs from canonical WP")
    if not isinstance(scope["paths"], list) or not scope["paths"]:
        raise PortError("Missing repair path admissions")
    admitted = {}
    test_modules, wiring_modules = set(), set()
    for item in scope["paths"]:
        fields(item, {"path", "operation", "capability"}, label="repair path")
        path = git_path(item["path"])
        actual = CapabilityEngine(policy).admit(wp["id"], wp["write_paths"], path, item["operation"])
        if item["capability"] != actual.capability_id or path in admitted:
            raise PortError("Wrong/duplicate trusted repair capability")
        if verification:
            test = re.fullmatch(ANDROID_MODULE + r"src/(?:test|testDebug|androidTest)/.+\.kt", path)
            wiring = re.fullmatch(ANDROID_MODULE + r"build\.gradle\.kts", path)
            if test:
                test_modules.add(test[1])
            elif wiring and actual.capability_id != "wp-owned":
                wiring_modules.add(wiring[1])
            elif not (
                path == f"docs/android/deviations/{wp['id']}.md"
                or path.startswith(f"docs/android/evidence/{wp['id']}/")
            ):
                raise PortError("Verification-only scope permits tests, typed test build wiring and owned evidence only")
        admitted[path] = item["operation"]
    if set(admitted) != set(changed_paths):
        raise PortError("Repair scope omits or invents changed paths")
    if verification and (not test_modules or not wiring_modules.issubset(test_modules)):
        raise PortError("Verification test build wiring requires changed tests in the same module")
    if (
        not isinstance(scope["verification"], list) or scope["verification"] != ["android-scaffold"]
    ):
        raise PortError("Unknown or missing declared affected verification")
    if not isinstance(scope["acceptance"], list) or not scope["acceptance"]:
        raise PortError("Missing bounded repair acceptance criteria")
    if whole_wp:
        if scope["acceptance"] != wp["acceptance"]:
            raise PortError("Whole-WP scope must retain the complete original acceptance")
    else:
        acceptance = []
        for entry in scope["acceptance"]:
            fields(entry, {"id", "description"}, label="repair criterion")
            if not isinstance(entry["id"], str) or re.fullmatch(
                rf"repair-{scope['repair_issue']}-[a-z0-9-]+", entry["id"],
            ) is None or entry["id"] in acceptance:
                raise PortError("Repair criteria must be unique issue-scoped IDs, not whole-WP acceptance")
            nonempty(entry["description"], "Repair criterion")
            acceptance.append(entry["id"])
    if not isinstance(scope["coverage"], list) or not scope["coverage"]:
        raise PortError("Missing changed-path behavioral coverage")
    owned = {entry["path"]: entry for entry in manifest.inputs(wp["id"], False) if entry["exclusion"] is None}
    if scope.get("producer_bindings") and (
        set(scope["producer_bindings"]) & set(changed_paths)
        or not any(
            isinstance(entry, dict) and isinstance(entry.get("source_paths"), list)
            and set(path for path in entry["source_paths"] if isinstance(path, str)) & owned.keys()
            for entry in scope["coverage"]
        )
    ):
        raise PortError("Bound unchanged producers require coverage of a primary-owned original source")
    original_inputs = {entry["path"]: entry for entry in manifest.data["inventory"] if entry["exclusion"] is None}
    catalog_entries = {e["path"]: e for e in catalog["entries"]} if catalog else {}
    if catalog and (catalog.get("schema_version") != 1 or catalog.get("source_sha") != binding.source_sha):
        raise PortError("Stale original-case inventory")
    covered, cases, modules = set(), {}, set()
    for entry in scope["coverage"]:
        fields(entry, {"path", "source_paths", "cases", "native_methods", "hosted_methods", "adaptation", "modules"}
               | ({"production_bindings"} if verification else set()),
               label="repair behavioral coverage")
        path = entry["path"]
        if path not in admitted or path in covered:
            raise PortError("Duplicate or unexplained repair coverage")
        covered.add(path)
        if (
            not isinstance(entry["source_paths"], list) or not entry["source_paths"]
            or not all(isinstance(path, str) for path in entry["source_paths"])
            or any(p not in original_inputs for p in entry["source_paths"])
        ):
            raise PortError("Repair source mapping is unknown, excluded, or empty")
        if any(not isinstance(entry[key], list) for key in ("cases", "native_methods", "hosted_methods")):
            raise PortError("Malformed repair original/native families")
        foreign_sources = set(entry["source_paths"]) - owned.keys()
        if foreign_sources - {
            case["path"] for case in entry["cases"]
            if isinstance(case, dict) and isinstance(case.get("path"), str)
        }:
            raise PortError("Cross-owner source mappings require explicit original case families")
        if not entry["cases"] and not entry["native_methods"] and not entry["hosted_methods"]:
            raise PortError("No original case or explicit native adaptation covers this change")
        if whole_wp and entry["cases"] and not entry["native_methods"] and not entry["hosted_methods"]:
            raise PortError("Whole-WP original cases require actual hosted or native test methods")
        if entry["native_methods"] or entry["hosted_methods"]:
            nonempty(entry["adaptation"], "Original-to-native adaptation rationale")
            if any(not isinstance(method, str) or not method for method in entry["native_methods"]):
                raise PortError("Malformed native method identity")
            for method in entry["hosted_methods"]:
                fields(method, {"path", "blob_sha", "id"}, label="affected hosted test method")
                if (
                    "/src/test" not in git_path(method["path"])
                    or not isinstance(method["blob_sha"], str) or SHA.fullmatch(method["blob_sha"]) is None
                ):
                    raise PortError("Affected hosted test must bind an actual immutable test source")
                nonempty(method["id"], "Affected hosted method ID")
        if (not whole_wp and "/src/test" in path
                and not any(method["path"] == path for method in entry["hosted_methods"])):
            raise PortError("Changed unit-test sources need their own actual hosted method execution")
        if whole_wp and "/src/test" in path and not entry["hosted_methods"]:
            raise PortError("Changed unit-test sources or helpers need actual hosted method execution")
        if "/src/androidTest/" in path and not entry["native_methods"]:
            raise PortError("Changed instrumentation needs actual retained native-method execution")
        for case in entry["cases"]:
            fields(case, {"path", "id", "parameter_family"}, label="affected original case")
            definition = catalog_entries.get(case["path"])
            original = original_inputs.get(case["path"])
            if (
                definition is None or original is None or original["kind"] not in ("test", "support")
                or definition["blob_sha"] != original["blob_sha"] or not definition["has_assertions"]
                or {"id": case["id"], "parameter_family": case["parameter_family"]} not in definition["cases"]
                or (case["path"] not in owned and case["path"] not in entry["source_paths"])
            ):
                raise PortError("Affected original case/family is unknown, excluded, or has stale provenance")
            cases[(case["path"], case["id"], case["parameter_family"])] = original["blob_sha"]
        if not isinstance(entry["modules"], list) or not entry["modules"]:
            raise PortError("Changed behavior requires actual affected-module test discovery")
        for module in entry["modules"]:
            if not isinstance(module, str) or re.fullmatch(r"(?:app|(?:core|feature|platform)/[a-z0-9-]+)", module) is None:
                raise PortError("Invalid affected module")
            modules.add(module)
        if verification:
            for consumer in entry["production_bindings"]:
                module = re.fullmatch(ANDROID_MODULE + r"src/main/.+", consumer)[1]
                if module not in entry["modules"]:
                    raise PortError("Verification coverage omits a bound production consumer module")
        match = re.match(r"android/((?:core|feature|platform)/[^/]+|app)/", path)
        if match and match[1] not in entry["modules"]:
            raise PortError("Coverage omits the changed Android module")
    behavioral = {
        path for path in changed_paths
        if path.startswith("android/") and ("/src/main/" in path or "/src/test" in path or "/src/androidTest/" in path)
    }
    if verification:
        behavioral = {path for path in changed_paths if path.startswith("android/")}
    if not behavioral or not behavioral.issubset(covered):
        raise PortError("Every changed Android behavior/test path needs explicit scoped coverage")
    if whole_wp:
        if not any(entry["hosted_methods"] for entry in scope["coverage"]):
            raise PortError("Whole-WP verification requires at least one executed hosted test method")
        original_tests = {
            path: entry for path, entry in owned.items() if entry["kind"] in ("test", "support")
        }
        expected_cases = set()
        for path, original in original_tests.items():
            definition = catalog_entries.get(path)
            if definition is None or definition.get("blob_sha") != original["blob_sha"]:
                raise PortError("Whole-WP scope requires the complete pinned original-case inventory")
            for case in definition.get("cases", []):
                expected_cases.add((path, case["id"], case["parameter_family"]))
        if not expected_cases.issubset(cases):
            raise PortError("Whole-WP scope must map every original case and parameter family")
    if not isinstance(scope["native"], list):
        raise PortError("Malformed retained native evidence references")
    for item in scope["native"]:
        fields(item, {"revision", "transcript", "sha256", "api", "methods", "bindings",
                      "fixture", "out_of_scope_failures", "execution"}, label="retained native observation")
        if (
            not isinstance(item["revision"], str) or SHA.fullmatch(item["revision"]) is None
            or not isinstance(item["sha256"], str) or re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) is None
            or not isinstance(item["methods"], list) or not item["methods"]
            or not all(isinstance(method, str) and method for method in item["methods"])
        ):
            raise PortError("Native evidence requires exact revision and byte identity")
        git_path(item["transcript"])
        fields(item["fixture"], {"path", "blob_sha"}, label="native fixture")
        git_path(item["fixture"]["path"])
        if not isinstance(item["fixture"]["blob_sha"], str) or SHA.fullmatch(item["fixture"]["blob_sha"]) is None:
            raise PortError("Native fixture requires immutable Git identity")
        if not isinstance(item["bindings"], dict) or not item["bindings"]:
            raise PortError("Native observations require actual source bindings")
        for path, blob in item["bindings"].items():
            git_path(path)
            if not isinstance(blob, str) or SHA.fullmatch(blob) is None:
                raise PortError("Malformed native production source identity")
    return admitted, cases, modules


def verify_repair(value, scope, manifest, policy, catalog):
    binding, pr, evidence = value.binding, value.pr, value.evidence
    if scope["repair_pr"] != pr["number"] or any(
        pr[key] != getattr(binding, key) for key in ("repository", "work_package", "base_sha", "head_sha")
    ):
        raise PortError("Repair PR/binding mismatch")
    _, wanted_cases, modules = validate_scope(scope, binding, manifest, policy, pr["changed_paths"], catalog)
    artifacts, runs = hosted_references(evidence, binding, schema_version=3, extra_fields=("repair_scope",))
    if evidence["repair_scope"] != scope or set(evidence["changed_paths"]) != set(pr["changed_paths"]):
        raise PortError("Evidence repair scope/path set differs from authenticated admission")
    native_refs = {
        f"https://github.com/{binding.repository}/blob/{item['revision']}/{item['transcript']}"
        for item in scope["native"]
    }
    artifacts |= native_refs
    scoped_wp = {**manifest.wp(binding.work_package), "acceptance": scope["acceptance"]}
    _acceptance(evidence["acceptance"], scoped_wp, artifacts, "bounded repair acceptance")
    seen, source_paths = {}, set()
    if not isinstance(evidence["source_tests"], list):
        raise PortError("Malformed affected original cases")
    for result in evidence["source_tests"]:
        fields(result, {"path", "blob_sha", "cases"}, label="repair original result")
        if result["path"] in source_paths:
            raise PortError("Duplicate affected original result path")
        source_paths.add(result["path"])
        if not isinstance(result["cases"], list) or not result["cases"]:
            raise PortError("Zero affected original cases")
        for case in result["cases"]:
            fields(case, {"id", "parameter_family", "result", "evidence"}, label="repair case")
            key = (result["path"], case["id"], case["parameter_family"])
            if (
                key not in wanted_cases or key in seen or result["blob_sha"] != wanted_cases[key]
                or case["result"] != "PASS" or not isinstance(case["evidence"], list) or not case["evidence"]
                or any(reference not in artifacts for reference in case["evidence"])
            ):
                raise PortError("Unknown, excluded, failed, duplicated, or unbound affected original case")
            seen[key] = result["blob_sha"]
    if seen != wanted_cases:
        raise PortError("Missing affected original cases/parameter families")
    human = (
        manifest.wp(binding.work_package)["human_gate"]
        or bool(protected_paths(policy, pr["changed_paths"])) or "needs-human" in pr["labels"]
    )
    validate_review(value.review, binding, scoped_wp, artifacts, human)
    required = human_acceptance_required(scoped_wp, policy, pr["changed_paths"], pr["labels"])
    approval = validate_approval(value.approvals, binding, policy, pr["author"]) if required else None
    return {
        "kind": "bounded-repair-acceptance", "binding": asdict(binding), "scope_sha256": digest(scope),
        "pr_number": pr["number"], "ci_run_ids": runs, "approval_id": approval,
        "wp_complete": False, "dependency_unlock": False, "modules": sorted(modules),
    }


def read_job_log(native, job_id):
    """Read authoritative hosted logs in memory only; never retain a second CI-result bundle."""
    token = getattr(native.backend.api, "token", None)
    if not token:
        raise PortError("Explicit authenticated hosted-log reader is unavailable")
    environment = {**os.environ, "GH_TOKEN": token, "GH_HOST": "github.com", "GH_PAGER": "cat"}
    process = subprocess.Popen(
        ["gh", "run", "view", "--repo", native.policy["repository"], "--job", str(job_id), "--log"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment,
    )
    expired = threading.Event()

    def expire():
        expired.set()
        if process.poll() is None:
            process.kill()

    timer = threading.Timer(120, expire)
    timer.start()
    try:
        data = process.stdout.read(MAX_LOG_BYTES + 1)
        if len(data) > MAX_LOG_BYTES or process.wait(timeout=5) or expired.is_set():
            raise PortError("Hosted log is missing, oversized, or unreadable")
        return data.decode("utf-8")
    except (OSError, subprocess.SubprocessError, UnicodeError) as error:
        raise PortError("Authoritative hosted log reader failed") from error
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdout.close()


def validate_build_log(log, modules, *, plan_for_scopes=None):
    lines = []
    for line in log.splitlines():
        # gh emits job, step and timestamp columns; only the authoritative run step is evidence.
        columns = line.split("\t", 2)
        if len(columns) == 3 and columns[1] in (
            "Run every selected Linux and Android task in one Gradle invocation",
            "Run parallel Gradle validation and Android tasks",
        ):
            lines.append(re.sub(r"^\S+\s?", "", columns[2]))
    text = "\n".join(lines)
    reports = []
    for match in re.finditer(r"(?m)^\{$", text):
        try:
            _, length = json.JSONDecoder().raw_decode(text[match.start():])
            value = decode_json(text[match.start():match.start() + length])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and (
            value.get("stage") == "scaffold" or value.get("kind") == "local-affected-only"
        ):
            reports.append(value)
    if len(reports) != 1:
        raise PortError("Missing/ambiguous actual scaffold report in authoritative hosted log")
    report = reports[0]
    if report.get("kind") == "local-affected-only":
        scopes = report.get("scopes")
        if (
            plan_for_scopes is None or not isinstance(scopes, list) or not scopes
            or not all(isinstance(scope, str) and scope for scope in scopes)
            or len(set(scopes)) != len(scopes)
        ):
            raise PortError("Affected hosted evidence requires the trusted exact-candidate task plan")
        plan = plan_for_scopes(scopes)
        if (
            report.get("result") != "success"
            or any(report.get(key) != plan[key] for key in ("base", "head", "tree", "scopes", "tasks"))
            or not isinstance(report.get("resolved_task_graph"), dict)
            or report["resolved_task_graph"].get("result") != "no-duplicate-ci-evidence"
        ):
            raise PortError("Hosted affected verification disagrees with the trusted candidate plan")
        suites = {}
        for key in ("suites", "scaffold_suites", "module_unit_tests"):
            part = report.get(key, {})
            if not isinstance(part, dict):
                raise PortError("Malformed affected hosted suite inventory")
            for name, value in part.items():
                if key == "scaffold_suites" and name == "contracts":
                    name = "core/contracts"
                if name in suites and suites[name] != value:
                    raise PortError("Conflicting affected hosted suite inventories")
                suites[name] = value
        cases = report.get("junit_cases")
        if not isinstance(cases, list) or not cases:
            raise PortError("Missing affected hosted method inventory")
        inventory = {module: [] for module in modules}
        for case in cases:
            fields(case, {"report", "index", "class", "method", "outcome"},
                   {"factory"}, label="affected hosted method")
            path = git_path(case["report"])
            match = re.fullmatch(ANDROID_MODULE + r"build/test-results/[^/]+/TEST-[^/]+\.xml", path)
            if (
                re.fullmatch(r"android/.+/build/test-results/[^/]+/TEST-[^/]+\.xml", path) is None
                or type(case["index"]) is not int or case["index"] < 0
                or not isinstance(case["class"], str) or not case["class"]
                or not isinstance(case["method"], str) or not case["method"]
                or ("factory" in case and
                    (not isinstance(case["factory"], str) or not case["factory"]))
                or case["outcome"] != "passed"
            ):
                raise PortError("Malformed, failed or skipped affected hosted method")
            if match is None:
                continue
            module = match[1]
            if module in inventory:
                record = {"class": case["class"], "method": case["method"]}
                if "factory" in case:
                    record["factory"] = case["factory"]
                inventory[module].append(record)
        for module in modules:
            found = counts(suites.get(module))
            records = inventory[module]
            identities = {(record["class"], record["method"]) for record in records}
            if len(records) != found["discovered"] or len(identities) != len(records):
                raise PortError("Affected hosted methods disagree with discovered test counts")
        return {"modules": sorted(modules), "positive_discovery": True,
                "source": "authoritative-hosted-log", "methods": inventory}
    from .ci import SCOPED_TASKS, TASKS

    tasks = report.get("tasks")
    if (
        report.get("result") != "success" or report.get("strict_verification") is not True
        or not isinstance(tasks, list) or not all(isinstance(task, str) for task in tasks)
        or len(set(tasks)) != len(tasks) or not set(TASKS["scaffold"]).issubset(tasks)
        or not set(tasks).issubset({task for values in SCOPED_TASKS.values() for task in values})
    ):
        raise PortError("Hosted affected verification did not execute the declared strict task set")
    inventory = report.get("unit_test_methods", {})
    if not isinstance(inventory, dict):
        raise PortError("Malformed authoritative hosted method inventory")
    for module in modules:
        if module == "core/protocol":
            if ":core:protocol:test" not in tasks:
                raise PortError("Protocol discovery requires the declared protocol test task")
            found = report.get("protocol_suite")
        else:
            suites = report.get("suites" if module in ("app", "core/contracts") else "module_unit_tests")
            if not isinstance(suites, dict):
                raise PortError("Missing/malformed affected hosted module discovery")
            found = suites.get("contracts" if module == "core/contracts" else module)
        counts(found)
        if module in ("app", "core/contracts"):
            duplicate = report.get("module_unit_tests", {})
            if not isinstance(duplicate, dict) or (
                module in duplicate and duplicate[module] != found
            ):
                raise PortError("Conflicting hosted module discovery counts")
        records = inventory.get(module)
        if not isinstance(records, list) or len(records) != found["discovered"]:
            raise PortError("Actual hosted method inventory disagrees with discovered test counts")
        seen = set()
        for record in records:
            fields(record, {"class", "method"}, {"factory"}, label="executed hosted method")
            if not all(isinstance(item, str) and item for item in record.values()):
                raise PortError("Malformed executed hosted method identity")
            identity = (record["class"], record["method"])
            if identity in seen:
                raise PortError("Duplicate actual hosted method identity")
            seen.add(identity)
    return {"modules": sorted(modules), "positive_discovery": True, "source": "authoritative-hosted-log",
            "methods": inventory}


def _kotlin_junit_methods(text):
    def comment(index):
        if text.startswith("//", index):
            end = text.find("\n", index)
            return len(text) if end < 0 else end
        depth, index = 1, index + 2
        while index < len(text) and depth:
            if text.startswith("/*", index):
                depth, index = depth + 1, index + 2
            elif text.startswith("*/", index):
                depth, index = depth - 1, index + 2
            else:
                index += 1
        if depth:
            raise PortError("Unterminated Kotlin block comment in hosted test source")
        return index

    def literal(index):
        quote = '"""' if text.startswith('"""', index) else text[index]
        index += len(quote)
        while index < len(text):
            if text.startswith(quote, index):
                end = index + len(quote)
                if quote == '"""':
                    while end < len(text) and text[end] == '"':
                        end += 1
                return end
            if text[index] == "\\" and quote != '"""':
                index += 2
            elif text.startswith("${", index) and quote != "'":
                index = template(index + 2)
            else:
                index += 1
        raise PortError("Unterminated Kotlin literal in hosted test source")

    def template(index):
        depth = 1
        while index < len(text) and depth:
            if text.startswith(("//", "/*"), index):
                index = comment(index)
            elif text[index] in "\"'":
                index = literal(index)
            elif text[index] == "{":
                depth, index = depth + 1, index + 1
            elif text[index] == "}":
                depth, index = depth - 1, index + 1
            else:
                index += 1
        if depth:
            raise PortError("Unterminated Kotlin string template in hosted test source")
        return index

    token = re.compile(r"`[^`\r\n]+`|[^\W\d]\w*|[^\s]")
    identifier = re.compile(r"[^\W\d]\w*")
    tokens, index = [], 0
    while index < len(text):
        if text[index].isspace():
            index += 1
        elif text.startswith(("//", "/*"), index):
            index = comment(index)
        elif text[index] in "\"'":
            index = literal(index)
            tokens.append("<literal>")
        else:
            match = token.match(text, index)
            tokens.append(match[0])
            index = match.end()

    test_annotations = {"Test", "org.junit.Test", "org.junit.jupiter.api.Test", "kotlin.test.Test"}
    family_annotations = {
        "TestFactory", "org.junit.jupiter.api.TestFactory",
        "ParameterizedTest", "org.junit.jupiter.params.ParameterizedTest",
        "RepeatedTest", "org.junit.jupiter.api.RepeatedTest",
        "TestTemplate", "org.junit.jupiter.api.TestTemplate",
    }
    def name_token(value):
        return identifier.fullmatch(value) is not None or (
            value.startswith("`") and value.endswith("`") and len(value) > 2
        )

    for start, value in enumerate(tokens):
        if value != "import":
            continue
        index, parts = start + 1, []
        while index < len(tokens) and name_token(tokens[index]):
            parts.append(tokens[index].strip("`"))
            index += 1
            if index >= len(tokens) or tokens[index] != ".":
                break
            index += 1
        if index + 1 >= len(tokens) or tokens[index] != "as" or not name_token(tokens[index + 1]):
            continue
        imported, alias = ".".join(parts), tokens[index + 1].strip("`")
        if imported in test_annotations:
            test_annotations.add(alias)
        if imported in family_annotations:
            family_annotations.add(alias)
    methods, declaration_positions = {}, {}
    for start, value in enumerate(tokens):
        if value != "@":
            continue
        index, annotations = start, set()
        while index < len(tokens) and tokens[index] == "@":
            index += 1
            if index == len(tokens) or not name_token(tokens[index]):
                break
            name = tokens[index].strip("`")
            index += 1
            while index + 1 < len(tokens) and tokens[index] == "." and name_token(tokens[index + 1]):
                name += "." + tokens[index + 1].strip("`")
                index += 2
            annotations.add(name)
            if index < len(tokens) and tokens[index] == "(":
                stack = []
                while index < len(tokens):
                    value = tokens[index]
                    if value in ("(", "[", "{"):
                        stack.append(value)
                    elif value in (")", "]", "}"):
                        if not stack or stack.pop() != {")": "(", "]": "[", "}": "{"}[value]:
                            raise PortError("Unbalanced Kotlin annotation in hosted test source")
                        if not stack:
                            index += 1
                            break
                    index += 1
                if stack:
                    raise PortError("Unclosed Kotlin annotation in hosted test source")
        while index < len(tokens) and tokens[index] in (
            "public", "internal", "private", "protected", "final", "open", "abstract", "override",
            "suspend", "inline", "tailrec", "external", "infix", "operator", "actual", "expect",
        ):
            index += 1
        if annotations & (test_annotations | family_annotations):
            if index + 2 >= len(tokens) or tokens[index] != "fun" or tokens[index + 2] != "(":
                raise PortError("Cannot identify annotated Kotlin JUnit test declaration")
            name = tokens[index + 1].strip("`")
            kind = "factory" if annotations & family_annotations else "test"
            if name in declaration_positions and declaration_positions[name] != index:
                raise PortError("Ambiguous duplicate JUnit method name in candidate test source")
            if name in methods and methods[name] != kind:
                raise PortError("Ambiguous JUnit test and factory declaration")
            declaration_positions[name] = index
            methods[name] = kind
    return methods


def validate_hosted_methods(scope, candidate_tree, repo, executed):
    from .staging import object_bytes

    verification = verification_only(scope)
    for coverage in scope["coverage"]:
        if verification:
            if any(candidate_tree.get(path) != blob for path, blob in coverage["production_bindings"].items()):
                raise PortError("Verification production consumer does not match candidate source")
        if verification or scope.get("kind") == "whole-wp":
            path = coverage["path"]
            if "/src/test/" in path or "/src/testDebug/" in path or "/src/androidTest/" in path:
                blob = candidate_tree.get(path)
                if blob is None:
                    raise PortError("Verification test source is absent from candidate")
                text = object_bytes(repo, blob).decode("utf-8")
                declared = _kotlin_junit_methods(text)
                if "/src/test/" in path or "/src/testDebug/" in path:
                    identities = [m["id"] for m in coverage["hosted_methods"] if m["path"] == path]
                else:
                    package = re.search(r"^\s*package\s+([\w.]+)", text, re.MULTILINE)
                    test_class = (package[1] + "." if package else "") + path.rsplit("/", 1)[-1][:-3]
                    identities = [m.removeprefix(test_class + "#") for m in coverage["native_methods"]
                                  if m.startswith(test_class + "#")]
                helper = (scope.get("kind") == "whole-wp" and "/src/androidTest/" not in path
                          and not declared and coverage["hosted_methods"])
                if (not declared and not helper) or len(set(identities)) != len(identities) or set(identities) != set(declared):
                    raise PortError("Verification coverage must include every actual changed JUnit method")
        for method in coverage["hosted_methods"]:
            if candidate_tree.get(method["path"]) != method["blob_sha"]:
                raise PortError("Affected hosted regression does not match candidate test source")
            module = re.match(r"android/((?:core|feature|platform)/[^/]+|app)/", method["path"])
            if module is None or module[1] not in coverage["modules"]:
                raise PortError("Affected hosted test is outside the verified module")
            text = object_bytes(repo, method["blob_sha"]).decode("utf-8")
            package = re.search(r"^\s*package\s+([\w.]+)", text, re.MULTILINE)
            test_class = (package[1] + "." if package else "") + method["path"].rsplit("/", 1)[-1][:-3]
            declarations = _kotlin_junit_methods(text)
            kind = declarations.get(method["id"])
            if kind is None:
                raise PortError("Hosted method lacks its actual candidate JUnit test declaration")
            records = executed.get(module[1])
            if not isinstance(records, list) or not records:
                raise PortError("Actual hosted method inventory is missing; counts alone cannot prove selected execution")
            for record in records:
                fields(record, {"class", "method"}, {"factory"}, label="executed hosted method")
                if not all(isinstance(item, str) and item for item in record.values()):
                    raise PortError("Malformed executed hosted method identity")
            if not any(
                record["class"] == test_class and (
                    (kind == "factory" and record.get("factory") == method["id"])
                    or (kind == "test" and "factory" not in record and (
                        record["method"] in (method["id"], method["id"] + "()")
                        or record["method"].startswith(method["id"] + "[")
                        or record["method"].startswith(method["id"] + "()[")
                    ))
                )
                for record in records
            ):
                raise PortError("Selected hosted method did not actually execute; filtered/excluded tests are not accepted")


def retained_file(native, revision, path, expected_hash):
    from urllib.parse import quote

    if not isinstance(revision, str) or SHA.fullmatch(revision) is None:
        raise PortError("Native evidence needs an immutable repository revision")
    path = git_path(path)
    value = native.get(f"/contents/{quote(path, safe='/')}?ref={revision}")
    if value.get("encoding") != "base64" or value.get("path") != path:
        raise PortError("Missing actual retained native evidence")
    try:
        data = base64.b64decode("".join(value["content"].split()), validate=True)
    except (ValueError, KeyError) as error:
        raise PortError("Malformed retained native bytes") from error
    if hashlib.sha256(data).hexdigest() != expected_hash:
        raise PortError("Retained native evidence byte identity mismatch")
    return data


def instrumentation_results(text):
    result, current, active = {}, {}, None
    expected, terminal = None, False
    summary = None
    for line in text.splitlines():
        if terminal and line.strip():
            raise PortError("Unexpected output after the terminal instrumentation result")
        if re.search(r"shortMsg=|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|FATAL EXCEPTION", line):
            raise PortError("Retained instrumentation contains a runner crash/failure")
        if line.startswith("INSTRUMENTATION_") and terminal:
            raise PortError("Instrumentation emitted status after its terminal result")
        if line.startswith("INSTRUMENTATION_RESULT: stream="):
            if summary is not None or active is not None or current or len(result) != expected:
                raise PortError("Instrumentation completion stream is duplicate or precedes complete outcomes")
            summary = [line.partition("stream=")[2]]
            continue
        if summary is not None and not line.startswith("INSTRUMENTATION_CODE:"):
            if line.startswith("INSTRUMENTATION_"):
                raise PortError("Instrumentation status/result follows its completion stream")
            summary.append(line)
            continue
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator and key in ("class", "test", "current", "numtests"):
                if key in current:
                    raise PortError("Duplicate instrumentation status field")
                current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.partition(": ")[2]
            if set(current) != {"class", "test", "current", "numtests"} or not all(current.values()):
                raise PortError("Incomplete instrumentation lifecycle/status fields")
            if not current["numtests"].isdecimal() or not current["current"].isdecimal():
                raise PortError("Malformed instrumentation runner counts")
            count, index = int(current["numtests"]), int(current["current"])
            if count < 1 or index != len(result) + 1 or index > count or expected not in (None, count):
                raise PortError("Inconsistent instrumentation runner discovery/order")
            expected = count
            identity = current["class"] + "#" + current["test"]
            if code == "1":
                if active is not None or identity in result:
                    raise PortError("Duplicate/overlapping instrumentation start")
                active = identity
                current = {}
                continue
            if code not in ("0", "-1", "-2", "-3", "-4") or active != identity:
                raise PortError("Native outcome has no matching actual start")
            result[identity] = code
            current, active = {}, None
        elif line.startswith("INSTRUMENTATION_CODE:"):
            if line != "INSTRUMENTATION_CODE: -1" or active is not None or current or len(result) != expected:
                raise PortError("Instrumentation terminal result is failed/incomplete")
            terminal = True
    if not result or not terminal or active is not None or current or len(result) != expected:
        raise PortError("Missing/zero/incomplete retained native execution")
    if summary is None:
        raise PortError("Missing actual instrumentation completion stream")
    completion = "\n".join(summary)
    times = re.findall(r"^Time: \d+(?:\.\d+)?$", completion, re.MULTILINE)
    successes = re.findall(r"^OK \((\d+) tests?\)$", completion, re.MULTILINE)
    failures = re.findall(r"^Tests run: (\d+),\s+Failures: (\d+)$", completion, re.MULTILINE)
    failure_markers = re.findall(r"^FAILURES!!!$", completion, re.MULTILINE)
    failed = sum(status in ("-1", "-2") for status in result.values())
    executed = len(result) - sum(status == "-3" for status in result.values())
    if len(times) != 1:
        raise PortError("Missing/ambiguous actual instrumentation elapsed-time summary")
    if failed:
        if successes or len(failure_markers) != 1 or failures != [(str(executed), str(failed))]:
            raise PortError("Actual failing runner summary contradicts native lifecycle outcomes")
    elif failure_markers or failures or successes != [str(executed)]:
        raise PortError("Actual successful runner summary contradicts native lifecycle outcomes")
    return result


def retained_repairs_execution(native, item, outcomes):
    """Read the actual legacy WP-503 repairs format, not a new asserted execution schema."""
    reference = item["execution"]
    fields(reference, {"format", "integration", "inputs", "metrics"}, label="retained execution references")
    if reference["format"] != "wp503-device-repairs-v1":
        raise PortError("Unsupported retained execution format; a reviewed actual-format parser is required")
    root = "docs/android/evidence/WP-503/"
    names = {"integration": "device-repairs-integration.json", "inputs": "device-repairs-inputs.json",
             "metrics": f"device-repairs-full-api{item['api']}-metrics.txt"}
    records = {}
    for key, name in names.items():
        record = reference[key]
        fields(record, {"path", "sha256"}, label="independent execution record")
        if record["path"] != root + name:
            raise PortError("Execution record does not match the actual legacy capture family")
        records[key] = retained_file(native, item["revision"], record["path"], record["sha256"])
    if item["transcript"] != root + f"device-repairs-full-api{item['api']}.txt":
        raise PortError("Transcript is not associated with the independent execution family")
    integration = decode_json(records["integration"].decode("utf-8"))
    inputs = decode_json(records["inputs"].decode("utf-8"))
    if not isinstance(integration, dict) or not isinstance(inputs, dict):
        raise PortError("Independent execution records must be actual JSON objects")
    if (
        integration.get("repository") != native.policy["repository"]
        or integration.get("work_package") != "WP-503"
        or integration.get("source") != native.manifest.data["reference"]["commit"]
    ):
        raise PortError("Independent execution repository/WP/frozen source mismatch")
    observed = integration.get("shared_nodes_registration_integration")
    if not isinstance(observed, dict) or inputs.get("head") != observed.get("head"):
        raise PortError("Actual execution source and APK input manifest are not associated")
    head = inputs["head"]
    if not isinstance(head, str) or SHA.fullmatch(head) is None:
        raise PortError("Independent execution has no immutable source revision")
    apks = inputs.get("apks")
    if not isinstance(apks, dict) or set(apks) != {"wp503-device-app.apk", "wp503-device-test.apk"}:
        raise PortError("Independent execution lacks actual App/Test package identities")
    for apk in apks.values():
        fields(apk, {"size", "sha256"}, label="executed APK identity")
        positive_integer(apk["size"], "Executed APK size")
        if not isinstance(apk["sha256"], str) or re.fullmatch(r"[0-9a-f]{64}", apk["sha256"]) is None:
            raise PortError("Malformed executed APK byte identity")
    profiles = set(re.findall(r"WP503_DEVICE_PROFILE\|sdk=(\d+)\|", records["metrics"].decode("utf-8")))
    if profiles != {str(item["api"])}:
        raise PortError("Actual retained runtime API differs from the claimed capture API")
    actual_counts = {"discovered": len(outcomes), "passed": sum(status == "0" for status in outcomes.values())}
    if item["api"] == 37:
        actual_counts.update(failed=sum(status in ("-1", "-2") for status in outcomes.values()),
                             errors=0, skipped=sum(status in ("-3", "-4") for status in outcomes.values()))
    api_record = observed.get(f"native_api{item['api']}")
    if not isinstance(api_record, dict) or api_record.get("full") != actual_counts:
        raise PortError("Actual transcript outcomes disagree with independent execution accounting")
    for path, blob in {**item["bindings"], item["fixture"]["path"]: item["fixture"]["blob_sha"]}.items():
        if native.get(f"/contents/{path}?ref={head}").get("sha") != blob:
            raise PortError("Actual executed source/fixture differs from the retained/candidate bindings")
    fixture = native.get(f"/contents/{item['fixture']['path']}?ref={head}")
    try:
        content = base64.b64decode("".join(fixture["content"].split()), validate=True)
    except (KeyError, ValueError, TypeError) as error:
        raise PortError("Actual executed fixture bytes are unavailable") from error
    blob = hashlib.sha1(b"blob " + str(len(content)).encode("ascii") + b"\0" + content).hexdigest()
    text = content.decode("utf-8")
    if blob != item["fixture"]["blob_sha"]:
        raise PortError("Executed fixture content differs from its Git identity")
    for method in item["methods"]:
        class_name, separator, test = method.rpartition("#")
        if (
            not separator or not re.search(r"\bclass\s+" + re.escape(class_name.rsplit(".", 1)[-1]) + r"\b", text)
            or not re.search(r"@Test\s+(?:public\s+)?fun\s+" + re.escape(test) + r"\s*\(", text)
        ):
            raise PortError("Selected executed method is absent from the actual bound native fixture")
    return head


def verify_native(native, scope, candidate_tree):
    methods = {m for coverage in scope["coverage"] for m in coverage["native_methods"]}
    covered, captures = set(), set()
    for item in scope["native"]:
        fields(item, {"revision", "transcript", "sha256", "api", "methods", "bindings",
                      "fixture", "out_of_scope_failures", "execution"}, label="retained native observation")
        if type(item["api"]) is not int or item["api"] not in (31, 37):
            raise PortError("Native observation must declare supported API31/API37")
        if (
            not isinstance(item["methods"], list) or not item["methods"]
            or not all(isinstance(method, str) and method for method in item["methods"])
            or len(set(item["methods"])) != len(item["methods"])
            or not set(item["methods"]).issubset(methods)
        ):
            raise PortError("Native evidence claims methods outside admitted repair")
        if not re.match(r"docs/android/evidence/WP-\d{3}/", item["transcript"]) or (
            item["transcript"].startswith(f"docs/android/evidence/{scope['binding']['work_package']}/")
        ):
            raise PortError("Native observations must retain independently owned audit provenance")
        raw = retained_file(native, item["revision"], item["transcript"], item["sha256"])
        capture = (item["revision"], item["transcript"], item["sha256"])
        if capture in captures:
            raise PortError("One retained capture cannot be relabelled across APIs")
        captures.add(capture)
        outcomes = instrumentation_results(raw.decode("utf-8"))
        fields(item["fixture"], {"path", "blob_sha"}, label="native fixture")
        if not isinstance(item["bindings"], dict) or not item["bindings"]:
            raise PortError("Native observation lacks producer source continuity")
        retained_repairs_execution(native, item, outcomes)
        failures = {method for method, status in outcomes.items() if status != "0"}
        if not isinstance(item["out_of_scope_failures"], dict) or set(item["out_of_scope_failures"]) != failures:
            raise PortError("All native failures/skips must remain explicitly accounted for")
        for method, reason in item["out_of_scope_failures"].items():
            nonempty(reason, "Unrelated native failure explanation")
            if method in methods:
                raise PortError("An affected native method cannot be excluded from acceptance")
        for method in item["methods"]:
            if outcomes.get(method) != "0":
                raise PortError("Affected native method is absent, failed, skipped or excluded")
            covered.add((method, item["api"]))
        if not isinstance(item["bindings"], dict) or not item["bindings"]:
            raise PortError("Native observation lacks producer source continuity")
        needed = {
            c["path"] for c in scope["coverage"] if set(c["native_methods"]) & set(item["methods"])
            and ("/src/main/" in c["path"] or "/src/androidTest/" in c["path"])
        }
        if verification_only(scope):
            needed |= {
                path for coverage in scope["coverage"] if set(coverage["native_methods"]) & set(item["methods"])
                for path in coverage["production_bindings"]
            }
        if not needed.issubset(item["bindings"]):
            raise PortError("Native evidence omits affected production source bindings")
        for path, blob in item["bindings"].items():
            if candidate_tree.get(path) != blob:
                raise PortError("Retained observation does not match current producer source bytes")
            actual = native.get(f"/contents/{path}?ref={item['revision']}")
            if actual.get("sha") != blob:
                raise PortError("Published audit revision does not contain the claimed producer source")
        fields(item["fixture"], {"path", "blob_sha"}, label="native fixture")
        fixture = native.get(f"/contents/{item['fixture']['path']}?ref={item['revision']}")
        if fixture.get("sha") != item["fixture"]["blob_sha"]:
            raise PortError("Native fixture identity mismatch")
    if covered != {(method, api) for method in methods for api in (31, 37)}:
        raise PortError("Missing affected native-method evidence on API31/API37")
    return {"kind": "retained-source-continuity", "standalone_head_execution": False,
            "affected_methods": sorted(methods)}
