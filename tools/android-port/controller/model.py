import hashlib
import re
import subprocess
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from .errors import PortError
from .paths import git_path
from .schema import check_schema, digest, load_json

REFERENCE_SHA = "db14559b39d32322b06477c6ae676112f583db50"
APPROVED_PLAN_SHA256 = "e5c838d2566d0466175e2be4036d50e44be548319c9fcd8b83bccd4fc03930cd"
HUMAN_GATES = {"WP-000", "WP-001", "WP-002", "WP-003", "WP-006", "WP-505", "WP-506"}
SUPERVISED = {"WP-000", "WP-001", "WP-002", "WP-003"}
REFERENCE_ROOTS = ("MC1/", "MC1Tests/", "MC1Widgets/", "MC1Services/", "MeshCore/", "Shared/", "AppIcon.icon/")
SHA = re.compile(r"^[0-9a-f]{40}$")


def git(repo: Path, *arguments: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repo), *arguments], capture_output=True, check=False
    )
    if result.returncode:
        raise PortError(f"Git command failed: {' '.join(arguments[:2])}")
    return result.stdout


def tree(repo: Path, revision: str) -> dict[str, str]:
    if SHA.fullmatch(revision) is None:
        raise PortError("A full immutable commit SHA is required")
    result = {}
    for row in git(repo, "ls-tree", "-rz", "--full-tree", revision).split(b"\0"):
        if not row:
            continue
        metadata, raw_path = row.split(b"\t", 1)
        mode, kind, blob = metadata.decode().split()
        path = raw_path.decode("utf-8")
        git_path(path)
        if kind != "blob" or mode not in ("100644", "100755"):
            raise PortError(f"Unsupported reference entry: {path}")
        result[path] = blob
    if not result:
        raise PortError("Empty reference tree")
    return result


def plan_rows(text: str) -> dict[str, dict]:
    rows = {}
    for line in text.splitlines():
        if not line.startswith("| WP-"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        wp, title, owner, dependencies = cells[:4]
        if wp in rows:
            raise PortError(f"Duplicate plan work package: {wp}")
        rows[wp] = {
            "title": title,
            "owner": owner,
            "depends_on": [f"WP-{n}" for n in re.findall(r"\b\d{3}\b", dependencies)],
            "human_gate": len(cells) > 4 and cells[4] == "Human",
        }
    if len(rows) != 64 or sum(len(r["depends_on"]) for r in rows.values()) != 178 or "WP-406" in rows:
        raise PortError("Approved active plan must contain exactly 64 WPs and 178 dependency edges, without WP-406")
    return rows


def validate_dag(work_packages: list[dict]):
    lookup = {}
    for wp in work_packages:
        if wp["id"] in lookup:
            raise PortError(f"Duplicate work package: {wp['id']}")
        lookup[wp["id"]] = wp
    visiting, visited = set(), set()

    def visit(wp_id):
        if wp_id not in lookup:
            raise PortError(f"Unknown dependency: {wp_id}")
        if wp_id in visiting:
            raise PortError(f"Dependency cycle at {wp_id}")
        if wp_id in visited:
            return
        visiting.add(wp_id)
        for dependency in lookup[wp_id]["depends_on"]:
            visit(dependency)
        visiting.remove(wp_id)
        visited.add(wp_id)

    for wp_id in lookup:
        visit(wp_id)


def profile(path: Path) -> dict:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise PortError(f"Missing or unreadable profile: {path}") from error
    parts = text.split("---", 2)
    if len(parts) != 3 or parts[0].strip():
        raise PortError(f"Malformed agent frontmatter: {path}")
    metadata = {}
    for line in parts[1].strip().splitlines():
        if ":" not in line:
            raise PortError(f"Unsupported agent frontmatter: {path}")
        key, value = line.split(":", 1)
        if key in metadata:
            raise PortError(f"Duplicate profile property: {key}")
        metadata[key] = value.strip()
    required = {"name", "description", "tools", "disable-model-invocation", "user-invocable"}
    if set(metadata) != required:
        raise PortError(f"Profile must preserve inherited model and reviewed fields: {path}")
    from .schema import decode_json

    metadata["tools"] = decode_json(metadata["tools"])
    if not isinstance(metadata["tools"], list) or not all(
        isinstance(t, str) for t in metadata["tools"]
    ):
        raise PortError(f"Invalid profile tool list: {path}")
    if metadata["name"] != path.name.removesuffix(".agent.md"):
        raise PortError(f"Agent identity does not match filename: {path}")
    if metadata["disable-model-invocation"] != "true" or metadata["user-invocable"] != "true":
        raise PortError(f"Profile invocation policy drift: {path}")
    if metadata["name"] == "parity-reviewer" and metadata["tools"] != ["read", "search"]:
        raise PortError("Parity reviewer must have only read/search tools")
    return metadata


@dataclass(frozen=True)
class Manifest:
    data: dict
    exclusions: dict
    repo: Path

    @property
    def sha256(self) -> str:
        return digest(self.data)

    @property
    def work_packages(self) -> dict[str, dict]:
        return {wp["id"]: wp for wp in self.data["work_packages"]}

    def wp(self, wp_id: str) -> dict:
        if wp_id not in self.work_packages:
            raise PortError(f"Unknown work package: {wp_id}")
        return self.work_packages[wp_id]

    def inputs(self, wp_id: str, cross_references: bool = True) -> list[dict]:
        self.wp(wp_id)
        return [
            entry for entry in self.data["inventory"]
            if entry["primary_owner"] == wp_id
            or (cross_references and wp_id in entry["cross_references"])
        ]

    def counts(self) -> dict:
        kinds = Counter(e["kind"] for e in self.data["inventory"])
        return {
            "tracked_reference_files": len(self.data["inventory"]),
            "kinds": dict(sorted(kinds.items())),
            "owned_files": sum(e["primary_owner"] is not None for e in self.data["inventory"]),
            "excluded_files": sum(e["exclusion"] is not None for e in self.data["inventory"]),
            "work_packages": len(self.work_packages),
            "dependency_edges": sum(len(w["depends_on"]) for w in self.work_packages.values()),
            "human_gates": sorted(w["id"] for w in self.work_packages.values() if w["human_gate"]),
        }


def validate_manifest(
    data: dict, exclusions: dict, repo: Path, verify_checkout=True, amendments: dict | None = None
) -> Manifest:
    directory = repo / "docs" / "android"
    if amendments is None:
        amendments = {"schema_version": 1, "reference_sha": REFERENCE_SHA, "entries": []}
    check_schema(data, load_json(directory / "port-manifest.schema.json"))
    check_schema(exclusions, load_json(directory / "not-ported.schema.json"))
    check_schema(amendments, load_json(directory / "reference-amendments.schema.json"))
    if (
        data["reference"]["commit"] != REFERENCE_SHA
        or exclusions["reference_sha"] != REFERENCE_SHA
        or amendments["reference_sha"] != REFERENCE_SHA
    ):
        raise PortError("Reference pin changed; a reviewed upstream-sync amendment is required")
    validate_dag(data["work_packages"])
    approved = plan_rows((directory / "PORTING_PLAN.md").read_text(encoding="utf-8"))
    lookup = {wp["id"]: wp for wp in data["work_packages"]}
    if lookup.keys() != approved.keys():
        raise PortError("Removed or unknown work packages")
    for wp_id, wp in lookup.items():
        for field in ("title", "owner", "depends_on", "human_gate"):
            if wp[field] != approved[wp_id][field]:
                raise PortError(f"{wp_id}: approved plan {field} drift")
        if wp["supervised"] != (wp_id in SUPERVISED):
            raise PortError(f"{wp_id}: supervised bootstrap gate drift")
        if wp["planned_state"] != "pending":
            raise PortError("Execution/completion state belongs in the lease ledger, not the manifest")
        if not wp["write_paths"]:
            raise PortError(f"{wp_id}: empty write lease")
        for path in wp["write_paths"]:
            git_path(path, subtree=True)
            if path.startswith(REFERENCE_ROOTS) or path in ("LICENSE", "project.yml", "swiftgen.yml"):
                raise PortError(f"{wp_id}: write lease includes read-only Swift reference: {path}")
        if len({a["id"] for a in wp["acceptance"]}) != len(wp["acceptance"]):
            raise PortError(f"{wp_id}: duplicate acceptance IDs")
    if {wp["id"] for wp in lookup.values() if wp["human_gate"]} != HUMAN_GATES:
        raise PortError("Human gates weakened")
    expected = tree(repo, REFERENCE_SHA)
    reference = data["reference"]
    plan_text = (directory / "PORTING_PLAN.md").read_text(encoding="utf-8")
    if reference["source_inventory_sha256"] != digest(expected):
        raise PortError("Reference inventory digest mismatch")
    if reference["tree_sha"] != git(repo, "rev-parse", f"{REFERENCE_SHA}^{{tree}}").decode().strip():
        raise PortError("Reference tree hash mismatch")
    if reference["installed_plan_sha256"] != hashlib.sha256(plan_text.encode()).hexdigest():
        raise PortError("Approved installed plan content drift")
    original_plan = plan_text.replace("../../.github/agents/", "files/agents/").replace(
        "../../.github/skills/", "files/skills/"
    )
    if reference["approved_plan_sha256"] != APPROVED_PLAN_SHA256 or hashlib.sha256(
        original_plan.encode()
    ).hexdigest() != APPROVED_PLAN_SHA256:
        raise PortError("Approved plan changed beyond repository-relative documentation paths")
    from inventory_rules import TRANSLATION_PATHS, exclusion as reviewed_exclusion

    excluded = {}
    for entry in exclusions["entries"]:
        path = git_path(entry["path"])
        if path in excluded:
            raise PortError(f"Duplicate excluded source: {path}")
        if path not in expected or entry["blob_sha"] != expected[path]:
            raise PortError(f"Unknown or mismatched excluded source: {path}")
        if not entry["review_basis"] or not entry["android_adaptation"]:
            raise PortError(f"Unreviewed exclusion: {path}")
        if path in TRANSLATION_PATHS or entry["reason_code"] == "removed-translation":
            reviewed = reviewed_exclusion(path)
            if path not in TRANSLATION_PATHS or (
                entry["reason_code"], entry["adaptation_wp"],
                entry["review_basis"], entry["android_adaptation"],
            ) != reviewed:
                raise PortError(f"Unapproved translation exclusion: {path}")
        if entry["adaptation_wp"] not in lookup:
            raise PortError(f"Unknown exclusion adaptation owner: {path}")
        excluded[path] = entry
    seen, folded = set(), set()
    for entry in data["inventory"]:
        path = git_path(entry["path"])
        if path in seen or path.casefold() in folded:
            raise PortError(f"Duplicate source owner/path: {path}")
        seen.add(path)
        folded.add(path.casefold())
        if path not in expected or expected[path] != entry["blob_sha"]:
            raise PortError(f"Unknown source or source hash mismatch: {path}")
        owner = entry["primary_owner"]
        exclusion = entry["exclusion"]
        if (owner is None) == (exclusion is None):
            raise PortError(f"Source requires exactly one primary owner or reviewed exclusion: {path}")
        if owner is not None and owner not in lookup:
            raise PortError(f"Unknown source owner: {path}")
        if exclusion is not None:
            if path not in excluded or excluded[path]["reason_code"] != exclusion:
                raise PortError(f"Missing reviewed exclusion: {path}")
        elif path in excluded:
            raise PortError(f"Source is both owned and excluded: {path}")
        for consumer in entry["cross_references"]:
            if consumer not in lookup or consumer == owner:
                raise PortError(f"Invalid source cross-reference: {path}")
    if seen != expected.keys():
        raise PortError(f"Unowned source files: {sorted(expected.keys() - seen)}")
    if excluded.keys() != {e["path"] for e in data["inventory"] if e["exclusion"] is not None}:
        raise PortError("Exclusion inventory mismatch")
    if {p for p, e in excluded.items() if e["reason_code"] == "removed-translation"} != TRANSLATION_PATHS:
        raise PortError("Exact user-approved translation exclusions are required")
    for wp in lookup.values():
        if not any(e["primary_owner"] == wp["id"] for e in data["inventory"]):
            if not wp["android_only_reason"]:
                raise PortError(f"{wp['id']}: empty ownership without an Android-only charter")
    for mapping in data["implementations"]:
        git_path(mapping["path"])
        wp = lookup.get(mapping["work_package"])
        if wp is None:
            raise PortError("Unknown implementation WP")
        from .paths import permits

        if not permits(wp["write_paths"], mapping["path"]):
            raise PortError(f"Implementation outside owner lease: {mapping['path']}")
        for source in mapping["sources"]:
            if source not in seen:
                raise PortError(f"Unknown implementation source: {source}")
    agents = directory.parent.parent / ".github" / "agents"
    paths = list(agents.glob("*.agent.md"))
    names = {profile(path)["name"] for path in paths}
    expected_names = {w["owner"] for w in lookup.values()} | {"parity-reviewer"}
    if len(paths) != 17 or names != expected_names:
        raise PortError("Expected exactly the 17 approved agent identities")
    if len(data["agents"]) != 17 or {a["name"] for a in data["agents"]} != expected_names:
        raise PortError("Manifest agent registry does not contain the exact approved roster")
    for agent in data["agents"]:
        content = (agents / f"{agent['name']}.agent.md").read_text(encoding="utf-8")
        if hashlib.sha256(content.encode()).hexdigest() != agent["sha256"]:
            raise PortError(f"Approved profile content drift: {agent['name']}")
    skills = repo / ".github" / "skills"
    expected_skills = {"android-port-wp", "swift-to-kotlin", "compose-from-swiftui"}
    if {p.parent.name for p in skills.glob("*/SKILL.md")} != expected_skills:
        raise PortError("Expected exactly the three approved installed skills")
    amendment_blobs = {}
    for entry in amendments["entries"]:
        if "/Tests/" not in entry["path"]:
            raise PortError(f"Reference amendment must be a test-only path: {entry['path']}")
        if expected.get(entry["path"]) != entry["original_blob_sha"]:
            raise PortError(f"Reference amendment baseline drift: {entry['path']}")
        if not (repo / entry["adr"]).is_file():
            raise PortError(f"Reference amendment missing its ADR: {entry['path']}")
        amendment_blobs[entry["path"]] = entry["approved_blob_sha"]
    if verify_checkout:
        head = git(repo, "rev-parse", "HEAD").decode().strip()
        current = tree(repo, head)
        readonly = lambda p: p.startswith(REFERENCE_ROOTS) or p in ("LICENSE",)
        changed = sorted(
            p for p in expected.keys() | current.keys()
            if readonly(p) and current.get(p) != expected.get(p)
            and current.get(p) != amendment_blobs.get(p)
        )
        if changed:
            raise PortError(f"Swift reference advanced/changed; do not move the pin: {changed}")
        roots = [p.rstrip("/") for p in REFERENCE_ROOTS] + ["LICENSE"]
        dirty = [
            p for p in git(repo, "diff", "--name-only", REFERENCE_SHA, "--", *roots).decode().splitlines()
            if p not in amendment_blobs
        ]
        unknown = git(repo, "ls-files", "--others", "--exclude-standard", "--", *roots).decode().splitlines()
        if dirty or unknown:
            raise PortError(f"Read-only source drift or unknown source: {sorted(set(dirty + unknown))}")
    return Manifest(data, exclusions, repo)


def load_manifest(repo: Path, verify_checkout=True) -> Manifest:
    directory = repo / "docs" / "android"
    return validate_manifest(
        load_json(directory / "port-manifest.json"),
        load_json(directory / "not-ported.json"),
        repo,
        verify_checkout=verify_checkout,
        amendments=load_json(directory / "reference-amendments.json"),
    )
