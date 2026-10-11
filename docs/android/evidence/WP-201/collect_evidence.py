"""WP-201 current source/DAO assertions; historical writer scope is an explicit, separate audit."""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tools" / "android-port"))

from controller.ci_evidence import read_xml, suite_counts
from controller.errors import PortError
from controller.gates import policy_revision
from controller.model import SHA, git, load_manifest, tree
from controller.module_junit import bounded_directory, linked, safe_reports
from controller.paths import permits
from controller.runtime_inputs import verify_tree_entries
from controller.schema import decode_json, digest, fields, load_json, nonempty
from controller.verification_config import content_scope_revisions
from portmap import port_map

SOURCE = "db14559b39d32322b06477c6ae676112f583db50"
MANIFEST = "ceb84b5e26fcc9ece5c0b3fb6c68b4d2965f9f24114fa81b7434ff73d1ed7904"
POLICY = "f52513bf818fffb013758e9abe48023816976a39b9bce9331b1f317cd828bb7f"
HISTORY = "docs/android/evidence/WP-201/local-evidence.json"
HISTORY_SHA256 = "c53017a93cc37fecdd442057423113e72146a18a023bd865909781c1db2fdca8"
SCHEMA = "android/core/database/schemas/com.meshcoreone.android.core.database.MeshCoreDatabase/1.json"
SCHEMA_DIGEST = "3cf9b98ff8078c548341f66397fb1f444b954ec986c33cad2bbd1b73a55de7ce"
MODULES = {
    "model": "android/core/model/build/test-results/test",
    "contracts": "android/core/contracts/build/test-results/test",
    "database": "android/core/database/build/test-results/testDebugUnitTest",
}
NATIVE_ROOTS = tuple(f"android/core/{module}/" for module in MODULES)
SCOPES = (
    "android/core/model/",
    "android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/",
    "android/core/database/",
    "docs/android/deviations/WP-201.md",
    "docs/android/evidence/WP-201/",
)
CONTROL_INPUTS = {
    HISTORY, SCHEMA, "docs/android/test-cases.json", "docs/android/port-manifest.json",
    "docs/android/automation-policy.json", "docs/android/evidence/WP-201/collect_evidence.py",
    "tools/android-port/controller/verification_config.py",
    "tools/android-port/controller/scope_amendment.py",
    "docs/android/scope-amendments/translation-removal.json",
}
TEXT_SUFFIXES = {".swift", ".kt", ".java", ".kts", ".py", ".json", ".xml", ".md", ".txt", ".properties", ".lockfile"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def blob_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()


def immutable_inputs(repo, head, owned):
    entries = tree(repo, head)
    required = CONTROL_INPUTS | {item["path"] for item in owned} | {
        path for path in entries if path.startswith(NATIVE_ROOTS)
    }
    verify_tree_entries(entries, required)
    for item in owned:
        if entries[item["path"]] != item["blob_sha"]:
            raise PortError("Swift reference input changed in the candidate: " + item["path"])
    roots = [root + "src" for root in NATIVE_ROOTS] + ["android/core/database/schemas"]
    # Include ignored source files: a global ignore must not hide compiled, uncommitted inputs.
    unknown = git(repo, "ls-files", "--others", "-z", "--", *roots).split(b"\0")
    if any(unknown):
        raise PortError("Uncommitted owned source/schema inputs: " + ", ".join(p.decode("utf-8") for p in unknown if p))
    scopes = sorted(CONTROL_INPUTS | {item["path"] for item in owned}) + [root.rstrip("/") for root in NATIVE_ROOTS]
    if git(repo, "diff", "--cached", "--name-only", head, "--", *scopes).strip():
        raise PortError("Staged owned inputs differ from the immutable candidate")
    raw_inputs = {}
    for path in sorted(required):
        local = repo / path
        bounded_directory(local.parent, repo)
        if linked(local) or not local.is_file():
            raise PortError("Missing/unsafe committed evidence input: " + path)
        raw = local.read_bytes()
        canonical = raw.replace(b"\r\n", b"\n") if local.suffix in TEXT_SUFFIXES else raw
        if blob_sha(raw) == entries[path]:
            committed = raw
        elif blob_sha(canonical) == entries[path]:
            committed = canonical
        else:
            committed = git(repo, "cat-file", "blob", entries[path])
            expected = committed.replace(b"\r\n", b"\n") if local.suffix in TEXT_SUFFIXES else committed
            if canonical != expected:
                raise PortError("Compiled/evidence checkout differs from its immutable Git input: " + path)
        raw_inputs[path] = committed
    return {path: entries[path] for path in sorted(required)}, raw_inputs


def historical_baseline(raw):
    if sha(raw.replace(b"\r\n", b"\n")) != HISTORY_SHA256:
        raise PortError("Frozen historical WP-201 evidence changed; it is not current execution output")
    return decode_json(raw.decode("utf-8"))


def case_identity(case):
    return case["module"], case["class"], case["name"]


def collect_junit(repo, baseline):
    cases = []
    suites = []
    discovery = {}
    minimums = Counter(case["module"] for case in baseline["native_cases"])
    for module, relative in MODULES.items():
        directory = repo / relative
        reports = safe_reports(directory, repo)
        for file in reports:
            if file.stat().st_size > 8 * 1024 * 1024:
                raise PortError("Oversized mandatory JUnit report: " + file.name)
            # The shared byte-level XML guard must not miss UTF-16/32 declarations.
            declarations = file.read_bytes().replace(b"\0", b"").upper()
            if b"<!DOCTYPE" in declarations or b"<!ENTITY" in declarations:
                raise PortError("Unsafe declarations in mandatory JUnit XML: " + file.name)
        counts = suite_counts(directory, minimums[module])
        for file in reports:
            raw = file.read_bytes()
            doc = read_xml(file)
            if raw != file.read_bytes():
                raise PortError("Raw JUnit changed during parsing: " + file.name)
            nodes = doc.findall("testcase")
            for node in nodes:
                cases.append({"module": module, "class": node.attrib["classname"], "name": node.attrib["name"], "outcome": "passed"})
            suites.append({"module": module, "path": file.relative_to(repo).as_posix(), "sha256": sha(raw), "cases": len(nodes)})
        discovery[module] = counts["discovered"]
        if sum(suite["cases"] for suite in suites if suite["module"] == module) != counts["discovered"]:
            raise PortError("JUnit changed during collection: " + module)
    identities = [case_identity(case) for case in cases]
    if len(identities) != len(set(identities)):
        raise PortError("Duplicate actual native test identity")
    missing = {case_identity(case) for case in baseline["native_cases"]} - set(identities)
    if missing:
        raise PortError("Missing mandatory original native test identity: " + repr(sorted(missing)[0]))
    discovery.update(total=len(cases), failed=0, errors=0, skipped=0)
    return cases, suites, discovery


def original_cases(repo, owned, catalog, baseline, cases):
    fields(catalog, {"schema_version", "source_sha", "entries"}, label="source-case catalog")
    if catalog["schema_version"] != 1 or catalog["source_sha"] != SOURCE or not isinstance(catalog["entries"], list):
        raise PortError("Invalid source-case catalog version/pin/entries")
    original_paths = {entry["path"] for entry in owned if entry["kind"] == "test"}
    blobs = {entry["path"]: entry["blob_sha"] for entry in owned}
    families, paths = {}, set()
    for entry in catalog["entries"]:
        fields(entry, {"path", "blob_sha", "has_assertions", "cases"}, label="source-case entry")
        nonempty(entry["path"], "source-case path")
        if entry["path"] in paths:
            raise PortError("Duplicate source-case catalog path: " + entry["path"])
        paths.add(entry["path"])
        if entry["path"] not in original_paths:
            continue
        if entry["blob_sha"] != blobs[entry["path"]] or entry["has_assertions"] is not True or not isinstance(entry["cases"], list):
            raise PortError("Invalid original source-case blob/assertions: " + entry["path"])
        for case in entry["cases"]:
            fields(case, {"id", "parameter_family"}, label="original assertion family")
            nonempty(case["id"], "original assertion identity")
            nonempty(case["parameter_family"], "original parameter family")
            key = (entry["path"], entry["blob_sha"], case["id"], case["parameter_family"])
            if key in families:
                raise PortError("Duplicate original assertion family: " + case["id"])
            families[key] = case
    expected = {(case["source"], case["blob_sha"], case["id"], case["parameter_family"]) for case in baseline["source_cases"]}
    if not original_paths <= paths or set(families) != expected:
        raise PortError("Missing/changed frozen original assertion family/source identity")
    actual = {case_identity(case): case for case in cases}
    db_bindings = {}
    for file in sorted((repo / "android/core/database/src/test/kotlin").rglob("*.kt")):
        content = file.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", content, re.MULTILINE)
        classes = re.findall(r"^\s*(?:public\s+)?class\s+(\w+)", content, re.MULTILINE)
        for identity, method in re.findall(r'@OriginalCase\("([^"]+)"\)\s+fun\s+(\w+)\s*\(', content):
            if identity in db_bindings:
                raise PortError("Duplicate original DAO annotation: " + identity)
            hits = [case for case in cases if case["module"] == "database" and case["name"] == method
                    and package and case["class"] in {package[1] + "." + name for name in classes}]
            if len(hits) != 1:
                raise PortError("Missing/ambiguous executed original DAO annotation: " + identity)
            db_bindings[identity] = hits[0]
    originals = []
    for original in baseline["source_cases"]:
        identity = original["id"]
        hit = actual.get(case_identity(original["native_test"]))
        if hit is None:
            raise PortError("Missing original assertion family: " + identity)
        if hit["module"] == "database" and db_bindings.get(identity) != hit:
            raise PortError("Missing/changed original DAO binding: " + identity)
        if hit["module"] == "model":
            hits = [case for case in cases if case["module"] == "model" and case["name"] == identity]
            if hits != [hit]:
                raise PortError("Missing/ambiguous original model binding: " + identity)
        originals.append({**original, "native_test": hit})
    return originals


def room_schema(raw):
    value = decode_json(raw.decode("utf-8"))
    if not isinstance(value, dict) or type(value.get("formatVersion")) is not int or value["formatVersion"] != 1:
        raise PortError("Invalid initial Room schema format")
    schema = value.get("database")
    if not isinstance(schema, dict) or type(schema.get("version")) is not int or schema["version"] != 1:
        raise PortError("Invalid frozen initial Room schema version")
    if not isinstance(schema.get("entities"), list) or len(schema["entities"]) != 17:
        raise PortError("Missing/corrupt initial Room entities")
    if digest(schema) != SCHEMA_DIGEST:
        raise PortError("Frozen Room v1 fields/identity/foreign-key relationships changed")
    return {"generator": "Room 2.8.5 / KSP 2.3.12", "path": SCHEMA,
            "sha256": sha(raw), "canonical_lf_sha256": sha(raw.replace(b"\r\n", b"\n")),
            "version": 1, "entities": 17, "identity_hash": schema["identityHash"], "explicit_cascade_relationships": 2}


def writer_scope_audit(repo, base, head):
    if not isinstance(base, str) or SHA.fullmatch(base) is None:
        raise PortError("Writer-scope audit requires the exact requested immutable base commit")
    if git(repo, "rev-parse", "--verify", base + "^{commit}").decode().strip() != base:
        raise PortError("Writer-scope audit base is not a commit")
    if git(repo, "merge-base", base, head).decode().strip() != base:
        raise PortError("Writer-scope audit base is not an ancestor of the candidate")
    changes = git(repo, "diff", "--name-only", "--no-renames", "-z", base).split(b"\0")
    staged = git(repo, "diff", "--cached", "--name-only", "-z", "HEAD").split(b"\0")
    unknown = git(repo, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    paths = sorted({path.decode("utf-8") for path in changes + staged + unknown if path})
    rejected = [path for path in paths if not permits(SCOPES, path)]
    if rejected:
        raise PortError("Explicit WP-201 writer-scope audit rejected out-of-scope paths: " + ", ".join(rejected))
    return {"base_sha": base, "head_sha": head, "changed_paths": paths, "result": "passed"}


def execution_context(repo, head):
    branch = git(repo, "branch", "--show-current").decode().strip() or None
    hosted = None
    if any(name in os.environ for name in ("GITHUB_EVENT_PATH", "GITHUB_EVENT_NAME", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")):
        from controller import ci

        if not os.environ.get("GITHUB_EVENT_PATH") or ci.REPO.resolve() != repo.resolve():
            raise PortError("Hosted candidate identity requires the actual event and owning checkout")
        try:
            hosted = ci.execution_identity()
        except (KeyError, TypeError, ValueError) as error:
            raise PortError("Malformed hosted candidate/run identity") from error
        if hosted is None or hosted["binding"]["head_sha"] != head:
            raise PortError("Hosted identity differs from the immutable candidate")
    return {"head_sha": head, "branch": branch, "context": "hosted" if hosted else "local", "hosted_run": hosted}


def report(repo=ROOT, *, audit_base=None):
    repo = repo.resolve()
    head = git(repo, "rev-parse", "HEAD").decode().strip()
    manifest = load_manifest(repo)
    policy = load_json(repo / "docs/android/automation-policy.json")
    current_revisions = content_scope_revisions(manifest, policy)
    owned = [item for item in manifest.data["inventory"] if item["primary_owner"] == "WP-201"]
    inputs, raw_inputs = immutable_inputs(repo, head, owned)
    baseline = historical_baseline(raw_inputs[HISTORY])
    if len(owned) != len(baseline["inputs"]):
        raise PortError("Frozen primary source input inventory changed")
    native = [item for item in port_map(manifest) if item["implementation"].startswith(SCOPES[:3])]
    input_map = []
    for item in owned:
        outputs = [entry["implementation"] for entry in native if item["path"] in entry["sources"]]
        if not outputs:
            raise PortError("Missing primary input mapping: " + item["path"])
        input_map.append({**item, "source_sha256": sha(raw_inputs[item["path"]]), "native_files": outputs})
    cases, suites, discovery = collect_junit(repo, baseline)
    originals = original_cases(repo, owned, load_json(repo / "docs/android/test-cases.json"), baseline, cases)
    schema = room_schema(raw_inputs[SCHEMA])
    execution = execution_context(repo, head)
    audit = writer_scope_audit(repo, audit_base, head) if audit_base is not None else None
    if git(repo, "rev-parse", "HEAD").decode().strip() != head or immutable_inputs(repo, head, owned)[0] != inputs:
        raise PortError("Candidate inputs changed during evidence collection")
    for suite in suites:
        if sha((repo / suite["path"]).read_bytes()) != suite["sha256"]:
            raise PortError("Raw JUnit changed during evidence collection: " + suite["path"])
    historical_identities = {case_identity(case) for case in baseline["native_cases"]}
    return {
        "schema_version": 2, "repository": "cbattlegear/MeshCoreOne-Android", "work_package": "WP-201",
        "execution": execution, "source_sha": SOURCE, **current_revisions,
        "historical_baseline": {
            "path": HISTORY, "canonical_lf_sha256": HISTORY_SHA256,
            "session": baseline["session"], "branch": baseline["branch"], "base_sha": baseline["base_sha"],
            "input_counts": baseline["input_counts"], "original_families": len(baseline["source_cases"]),
            "native_identity_minimums": dict(Counter(case["module"] for case in baseline["native_cases"])),
        },
        "writer_scope_audit": audit,
        "scope": "local source/field/DAO assertions; not formal review, gate approval, hardware or bidirectional backup restore",
        "input_counts": dict(Counter(item["kind"] for item in owned)), "candidate_input_blobs": inputs,
        "inputs": input_map, "source_cases": originals, "junit_suites": suites, "native_cases": cases,
        "additive_native_cases": [case for case in cases if case_identity(case) not in historical_identities],
        "discovery": discovery, "schema": schema,
    }


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit-writer-scope", action="store_true", help="One-off owner/coordinator audit, not normal root CI")
    parser.add_argument("--base-sha", help="Exact requested immutable base; required only with --audit-writer-scope")
    parser.add_argument("--output", type=Path, help="Optional current report in an absolute, private directory outside the repository")
    args = parser.parse_args(argv)
    if args.audit_writer_scope != (args.base_sha is not None):
        parser.error("--audit-writer-scope and --base-sha must be requested together")
    try:
        if args.output and (not args.output.is_absolute() or args.output.resolve().is_relative_to(ROOT.resolve())):
            raise PortError("Current output must be outside the repository; historical local-evidence.json is immutable")
        result = report(audit_base=args.base_sha)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
        print(json.dumps({
            "result": "passed", "execution": result["execution"], "historical_baseline": result["historical_baseline"],
            "writer_scope_audit": result["writer_scope_audit"],
            "original_declarations": len(result["source_cases"]), "asserted_or_native_equivalent": len(result["source_cases"]),
            "deferred_owned_cases": 0, "additive_native_assertions": len(result["additive_native_cases"]),
            "discovery": result["discovery"], "schema": result["schema"],
        }, indent=2))
        return 0
    except (PortError, OSError, UnicodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
