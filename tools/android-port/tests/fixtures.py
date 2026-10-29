import copy
import json
import sys
from dataclasses import asdict
from pathlib import Path

PORT_TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PORT_TOOLS))
REPO = PORT_TOOLS.parents[1]

from controller.backends import Observation
from controller.engine import GateBundle, TerminalMerge
from controller.gates import Binding, policy_revision, protected_paths
from controller.ledger import Identity
from controller.model import Manifest, REFERENCE_SHA, load_manifest
from controller.schema import load_json
from controller.settings import Budget, Settings, Usage

BASE = "1" * 40
HEAD = "2" * 40
MERGE = "3" * 40
NOW = 1000.0
_BASE_MANIFEST = None


def base_manifest():
    global _BASE_MANIFEST
    if _BASE_MANIFEST is None:
        _BASE_MANIFEST = load_manifest(REPO)
    return _BASE_MANIFEST


def historical_policy_text():
    from controller.scope_amendment import routine_support_predecessor

    text = (REPO / "docs" / "android" / "automation-policy.json").read_text(encoding="utf-8")
    marker = '      },\n      {\n        "id": "reviewed-support-'
    if marker not in text:
        return text
    start = text.index(marker)
    end = text.index('    ]\n  },', start)
    projected = text[:start] + '      }\n' + text[end:]
    if json.loads(projected) != routine_support_predecessor(json.loads(text)):
        raise ValueError("Text and object historical policy projections differ")
    return projected


def policy(backend="local"):
    value = copy.deepcopy(load_json(REPO / "docs" / "android" / "automation-policy.json"))
    value.update({
        "dispatch_mode": backend, "paused": False, "activation_approved": True,
        "trusted_check_app_ids": [10], "trusted_workflow_ids": [20],
        "trusted_gate_publisher_app_id": 10, "branch_rules_proven": True,
        "foundation_required": [],
    })
    return value


def test_manifest(wp_id="WP-101", no_dependencies=True):
    original = base_manifest()
    data = copy.deepcopy(original.data)
    wp = next(w for w in data["work_packages"] if w["id"] == wp_id)
    wp["verification"] = {"configured": True, "commands": [["python", "fixture-only"]], "blocker": ""}
    if no_dependencies:
        wp["depends_on"] = []
    return Manifest(data, original.exclusions, REPO)


def settings(ledger_path, backend="local", max_inflight=5, limit=100):
    return Settings(
        backend, False, max_inflight, Budget("ai_credits", limit, 5, 0), ledger_path,
        "personal_access_token", "fixture-user", "fixture-not-a-credential", 1800,
    )


def bundle(manifest, rules, wp_id="WP-000", changed_paths=None, merged=True):
    wp = manifest.wp(wp_id)
    changed = changed_paths or [wp["write_paths"][0].rstrip("/") + "/Fixture.kt"]
    if wp_id == "WP-000" and changed_paths is None:
        changed = ["tools/android-port/controller/engine.py"]
    binding = Binding(
        rules["repository"], wp_id, BASE, HEAD, REFERENCE_SHA,
        manifest.sha256, policy_revision(manifest, rules),
    )
    runs = []
    for index, name in enumerate(rules["required_checks"], start=1):
        runs.append({
            "check": name, "run_id": index, "run_attempt": 1, "workflow_id": 20, "app_id": 10,
            "base_sha": BASE, "head_sha": HEAD, "status": "completed", "conclusion": "success",
            "test_count": 2 if name == "android-ci" else 0,
            "passed_count": 2 if name == "android-ci" else 0, "failed_count": 0, "skipped_count": 0,
            "artifacts": [{"path": f"evidence/{name}.json", "sha256": str(index) * 64}],
        })
    acceptance = [
        {"id": a["id"], "result": "PASS", "evidence": ["evidence/android-ci.json"]}
        for a in wp["acceptance"]
    ]
    originals = [e for e in manifest.inputs(wp_id, False) if e["kind"] in ("test", "support")]
    catalog = {"schema_version": 1, "source_sha": REFERENCE_SHA, "entries": []}
    results = []
    for entry in originals:
        assertions = entry["kind"] == "test" or entry["path"].endswith("Tests.swift")
        cases = ([{"id": "fixture-original-case", "parameter_family": "boundary-family"}] if assertions else [])
        catalog["entries"].append({
            "path": entry["path"], "blob_sha": entry["blob_sha"], "has_assertions": assertions,
            "cases": copy.deepcopy(cases),
        })
        results.append({
            "path": entry["path"], "blob_sha": entry["blob_sha"],
            "cases": [{**case, "result": "PASS", "evidence": ["evidence/android-ci.json"]} for case in cases],
        })
    evidence = {
        "schema_version": 1, **asdict(binding), "runs": runs, "acceptance": acceptance,
        "source_tests": results, "changed_paths": changed,
    }
    human = wp["human_gate"] or bool(protected_paths(rules, changed))
    review = {
        "schema_version": 1, "repository": binding.repository, "work_packages": [wp_id],
        "base_sha": BASE, "head_sha": HEAD, "policy_revision": binding.policy_revision,
        "verdict": "PASS", "summary": "Fixture-only structured review; not product evidence.",
        "acceptance": copy.deepcopy(acceptance), "findings": [], "blockers": [],
        "human_gate_required": human,
    }
    approvals = [{
        "review_id": 99, "reviewer": "cbattlegear", "state": "APPROVED",
        "head_sha": HEAD, "base_sha": BASE, "policy_revision": binding.policy_revision,
        "binding_key": binding.key,
    }] if human else []
    pr = {
        "number": 123, "repository": binding.repository, "work_package": wp_id,
        "base_sha": BASE, "head_sha": HEAD, "merged": merged,
        "merge_commit_sha": MERGE if merged else None, "author": "fixture-worker",
        "labels": [], "changed_paths": changed,
    }
    return GateBundle(binding, pr, evidence, review, approvals, catalog if originals else None, True)


class FakeApi:
    def __init__(self, routes=None):
        self.routes = routes or {}
        self.calls = []

    def request(self, method, path, payload=None, *, readonly=False):
        self.calls.append((method, path, copy.deepcopy(payload), readonly))
        key = (method, path)
        if key not in self.routes:
            raise AssertionError(f"Unconfigured fake API request: {method} {path}")
        value = self.routes[key]
        if isinstance(value, Exception):
            raise value
        if callable(value):
            return value(payload)
        return copy.deepcopy(value)


class FakeBackend:
    name = "local"

    def __init__(self):
        self.observation = Observation(Identity(), "absent", False, True)
        self.launches = []
        self.repairs = []
        self.base = BASE
        self.failure = None
        self.usage_value = Usage("ai_credits", 0, NOW, True, (), ("local", "cloud"))
        self.preflights = []

    def current_base(self):
        return self.base

    def preflight(self, wp_id, base_sha):
        self.preflights.append((wp_id, base_sha))

    def reconcile(self, wp_id, known):
        return self.observation

    def launch(self, wp_id, attempt):
        self.launches.append((wp_id, attempt))
        if self.failure:
            raise self.failure
        self.observation = Observation(Identity(session_id="session-1", pr_number=123), "in_progress", True, True)
        return self.observation

    def repair(self, identity, prompt):
        self.repairs.append((identity, prompt))
        if self.failure:
            raise self.failure
        return {"session_id": identity.session_id, "delivered": True}

    def usage(self, budget, now):
        return self.usage_value


class FakeAuthority:
    def __init__(self, bundles):
        self.bundles = bundles
        self.terminal = None

    def bundle(self, wp_id, identity):
        return self.bundles[wp_id]

    def merged_terminal(self, wp_id, session_id, receipt, expected_base_sha):
        if self.terminal is None:
            raise AssertionError("Unconfigured terminal merge fixture")
        return self.terminal
