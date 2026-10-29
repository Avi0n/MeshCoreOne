"""Deterministic capability admission and advisory reservation reconciliation."""

from dataclasses import dataclass
from fnmatch import fnmatchcase
import re
import xml.etree.ElementTree as ET

from .errors import PortError
from .paths import git_path
from .schema import digest, nonempty


@dataclass(frozen=True)
class Admission:
    capability_id: str
    path: str
    operation: str
    invariants: tuple[str, ...]
    validation: tuple[str, ...]


class CapabilityEngine:
    """Matches trusted policy rules; free-form authorization never participates."""

    def __init__(self, policy: dict):
        reservation = policy.get("reservation_policy")
        if not isinstance(reservation, dict):
            raise PortError("Missing reservation policy")
        capabilities = reservation.get("capabilities")
        if not isinstance(capabilities, list) or not capabilities:
            raise PortError("Missing trusted capability definitions")
        self._rules = {}
        for rule in capabilities:
            if not isinstance(rule, dict) or not isinstance(rule.get("id"), str):
                raise PortError("Malformed capability definition")
            if rule["id"] in self._rules:
                raise PortError(f"Duplicate capability definition: {rule['id']}")
            for key in ("path_rules", "operations", "invariants", "validation"):
                if not isinstance(rule.get(key), list) or not all(
                    isinstance(value, str) and value for value in rule[key]
                ):
                    raise PortError(f"Malformed {rule['id']} capability {key}")
            if "work_packages" in rule and (
                not isinstance(rule["work_packages"], list) or not rule["work_packages"]
                or not all(isinstance(wp, str) and wp for wp in rule["work_packages"])
            ):
                raise PortError(f"Malformed {rule['id']} capability work_packages")
            self._rules[rule["id"]] = rule

    @property
    def revision(self):
        return digest(self._rules)

    @staticmethod
    def _matches(pattern: str, path: str) -> bool:
        pattern = pattern.replace("\\", "/")
        path = path.replace("\\", "/")
        if pattern.endswith("/**"):
            root = pattern[:-3].rstrip("/")
            return path == root or path.startswith(root + "/")
        return fnmatchcase(path, pattern)

    def admit(self, wp_id: str, assigned_paths: list[str], path: str, operation: str) -> Admission:
        nonempty(wp_id, "Capability WP")
        path = git_path(path, subtree=True)
        nonempty(operation, "Capability operation")
        candidates = []
        if any(self._assigned_path(allowed, path) for allowed in assigned_paths):
            candidates.append(self._rules.get("wp-owned"))
        candidates.extend(
            rule for rule in self._rules.values()
            if rule["id"] != "wp-owned"
            and operation in rule["operations"]
            and any(self._matches(pattern, path) for pattern in rule["path_rules"])
        )
        candidates = [
            rule for rule in candidates
            if rule is not None and operation in rule["operations"]
            and ("work_packages" not in rule or wp_id in rule["work_packages"])
            and self._support_path(rule["id"], path)
        ]
        if not candidates:
            raise PortError(
                f"Capability admission blocked: WP {wp_id}, path {path}, operation {operation}; "
                "no trusted path+operation rule"
            )
        rule = sorted(candidates, key=lambda value: value["id"])[0]
        return Admission(rule["id"], path, operation, tuple(rule["invariants"]), tuple(rule["validation"]))

    @staticmethod
    def _support_path(capability_id: str, path: str) -> bool:
        module = r"android/(?:app|(?:core|feature|platform)/[a-z0-9-]+)"
        if capability_id == "reviewed-support-consumer":
            return re.fullmatch(module + r"/src/main/kotlin/.+\.kt", path) is not None
        if capability_id == "reviewed-support-test":
            return re.fullmatch(module + r"/src/(?:test|testDebug|androidTest)/kotlin/.+\.kt", path) is not None
        return True

    @staticmethod
    def _assigned_path(allowed: str, requested: str) -> bool:
        allowed = git_path(allowed, subtree=True).rstrip("/")
        requested = git_path(requested, subtree=True).rstrip("/")
        return requested == allowed or requested.startswith(allowed + "/")

    def admit_scope(self, wp_id: str, assigned_paths: list[str],
                    paths: list[str], operations: list[str]) -> list[Admission]:
        if not paths or not operations:
            raise PortError("Capability scope must contain paths and operations")
        result = [
            self.admit(wp_id, assigned_paths, path, operation)
            for path in sorted(set(paths))
            for operation in sorted(set(operations))
        ]
        return result

    @staticmethod
    def validate_support_changes(path_operations: dict, base_tree: dict, candidate_tree: dict, *,
                                 read_blob=None):
        """Bind shared support operations to the actual immutable base and candidate."""
        for path, operation in path_operations.items():
            if operation == "modify-related-consumer" and (
                path not in base_tree or path not in candidate_tree
            ):
                raise PortError("Related consumer support may modify existing files only")
            if operation == "add-or-update-related-test" and path not in candidate_tree:
                raise PortError("Related test support cannot delete a test")
        resources = {
            path for path, operation in path_operations.items()
            if operation == "add-or-update-contact-import-recovery-copy"
        }
        if resources:
            from .scope_amendment import CONTACT_IMPORT_LOCALIZATION_CAPABILITY

            paths = CONTACT_IMPORT_LOCALIZATION_CAPABILITY["path_rules"]
            if resources - set(paths) or read_blob is None:
                raise PortError("Contact import recovery requires exact paths and an immutable blob reader")
            messages = {}
            for path in paths:
                if path not in candidate_tree:
                    raise PortError("Contact import recovery cannot delete or omit a supported locale resource")
                candidate, message = CapabilityEngine._contact_import_resource(read_blob(candidate_tree[path]))
                messages[path] = message
                if path in resources:
                    original = (
                        CapabilityEngine._contact_import_resource(read_blob(base_tree[path]), required=False)[0]
                        if path in base_tree else ("resources", (), ())
                    )
                    if original != candidate:
                        raise PortError("Contact import recovery cannot change unrelated resource entries")
            english = messages[paths[0]]
            if any(message == english for path, message in messages.items() if path != paths[0]):
                raise PortError("Contact import recovery requires explicit localized copy, not English fallback")

    @staticmethod
    def _contact_import_resource(data: bytes, *, required=True):
        key = "node_config_contact_save_failed"
        try:
            text = data.decode("utf-8")
            if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
                raise PortError("Contact import resource cannot contain DTD or entity declarations")
            root = ET.fromstring(text)
        except (UnicodeError, ET.ParseError) as error:
            raise PortError("Contact import recovery requires valid UTF-8 resource XML") from error
        if root.tag != "resources" or (root.text or "").strip() or any(
            (child.tail or "").strip() for child in root
        ):
            raise PortError("Contact import recovery requires a resources root without stray text")
        selected = [child for child in root if child.get("name") == key]
        if len(selected) > 1 or (required and not selected):
            raise PortError("Contact import recovery requires exactly one recovery string")
        message = None
        if selected:
            value = selected[0]
            if (
                value.tag != "string" or value.attrib != {"name": key}
                or len(value) or not (value.text or "").strip()
            ):
                raise PortError("Contact import recovery must be a nonempty, translatable plain string")
            message = value.text

        def entry(node):
            return (node.tag, tuple(sorted(node.attrib.items())), node.text,
                    tuple((entry(child), child.tail) for child in node))

        return (
            (root.tag, tuple(sorted(root.attrib.items())),
             tuple(entry(child) for child in root if child.get("name") != key)),
            message,
        )

    def overlap_report(self, records: list[dict]) -> list[dict]:
        report = []
        for index, left in enumerate(records):
            for right in records[index + 1:]:
                shared = sorted({
                    (a, b) for a in left.get("paths", []) for b in right.get("paths", [])
                    if self._matches(a, b) or self._matches(b, a)
                })
                capabilities = sorted(
                    set(left.get("capabilities", {}).keys())
                    & set(right.get("capabilities", {}).keys())
                )
                if shared or capabilities:
                    report.append({
                        "left": left["wp"], "right": right["wp"],
                        "paths": [{"left": a, "right": b} for a, b in shared],
                        "capabilities": capabilities,
                        "advisory": True,
                    })
        return report

    def reconcile_intents(self, expected_base: str, current_base: str, path: str,
                          capability_id: str, intents: list[dict]) -> dict:
        """Deterministically combine disjoint semantic edits and preserve every attribution."""
        path = git_path(path, subtree=True)
        if capability_id not in self._rules:
            raise PortError(f"Unknown reconciliation capability: {capability_id}")
        if not any(self._matches(pattern, path)
                   for pattern in self._rules[capability_id]["path_rules"]):
            raise PortError(f"Capability {capability_id} does not admit reconciliation path {path}")
        if expected_base != current_base:
            raise PortError("Current base changed; reconcile advisory claims against the current base")
        if not isinstance(intents, list) or not intents:
            raise PortError("Semantic reconciliation requires at least one intent")
        normalized = []
        for intent in intents:
            if not isinstance(intent, dict):
                raise PortError("Malformed semantic reconciliation intent")
            required = ("owner", "semantic_key", "value_digest", "evidence")
            if any(not isinstance(intent.get(key), str) or not intent[key] for key in required):
                raise PortError("Malformed semantic reconciliation intent")
            normalized.append({key: intent[key] for key in required})
        normalized.sort(key=lambda value: (value["semantic_key"], value["owner"],
                                           value["value_digest"], value["evidence"]))
        by_key = {}
        for intent in normalized:
            previous = by_key.get(intent["semantic_key"])
            if previous and previous["value_digest"] != intent["value_digest"]:
                owners = sorted({previous["owner"], intent["owner"]})
                raise PortError(
                    f"Unresolved semantic ownership collision on {path} "
                    f"key {intent['semantic_key']}: {owners}"
                )
            by_key.setdefault(intent["semantic_key"], intent)
        return {
            "path": path,
            "capability": capability_id,
            "current_base": current_base,
            "intents": normalized,
            "merged": [by_key[key] for key in sorted(by_key)],
            "owners": sorted({intent["owner"] for intent in normalized}),
            "evidence": sorted({intent["evidence"] for intent in normalized}),
            "validation": list(self._rules[capability_id]["validation"]),
            "advisory": True,
            "merge_allowed": True,
        }

    def transfer_intent(self, origin: dict, producer: dict, path: str,
                        capability_id: str, operation: str, *,
                        assigned_paths: list[str] | None = None) -> dict:
        """Record producer handoff without changing either owner's identity or evidence."""
        path = git_path(path, subtree=True)
        nonempty(operation, "Capability transfer operation")
        for label, record in (("origin", origin), ("producer", producer)):
            if not isinstance(record, dict) or any(
                not isinstance(record.get(key), str) or not record[key]
                for key in ("wp", "session", "evidence")
            ):
                raise PortError(f"Malformed {label} intent owner")
        if origin["session"] == producer["session"]:
            raise PortError("Intent transfer requires distinct owner sessions")
        rule = self._rules.get(capability_id)
        if rule is None:
            raise PortError(f"Unknown transfer capability: {capability_id}")
        if operation not in rule["operations"]:
            raise PortError(
                f"Capability {capability_id} does not admit transfer operation {operation}"
            )
        if capability_id == "wp-owned":
            if origin["wp"] != producer["wp"]:
                raise PortError("WP-owned transfer requires the same assigned WP")
            if not isinstance(assigned_paths, list) or not assigned_paths:
                raise PortError("WP-owned transfer requires trusted assigned-WP paths")
            if not any(self._assigned_path(allowed, path) for allowed in assigned_paths):
                raise PortError(
                    f"Capability {capability_id} does not admit transfer path {path}"
                )
            admission = self.admit(origin["wp"], assigned_paths, path, operation)
            if admission.capability_id != capability_id:
                raise PortError(
                    f"Capability {capability_id} does not admit transfer path {path} "
                    f"with operation {operation}"
                )
        else:
            if rule.get("work_packages") and producer["wp"] not in rule["work_packages"]:
                raise PortError(
                    f"Capability {capability_id} does not admit producer WP {producer['wp']}"
                )
            if not any(self._matches(pattern, path) for pattern in rule["path_rules"]):
                raise PortError(f"Capability {capability_id} does not admit transfer path {path}")
        return {
            "path": path, "capability": capability_id, "operation": operation,
            "origin": dict(origin), "producer": dict(producer),
            "attributions": sorted(
                [dict(origin), dict(producer)], key=lambda value: (value["wp"], value["session"])
            ),
            "advisory": True, "file_edit_blocked": False,
        }

    @staticmethod
    def validate_merge(expected_base: str, current_base: str, semantic_conflicts=()):
        if expected_base != current_base:
            raise PortError("Current base changed; reconcile advisory claims against the current base")
        conflicts = sorted(set(semantic_conflicts))
        if conflicts:
            raise PortError(f"Unresolved semantic ownership collision: {conflicts}")
        return {"current_base": current_base, "semantic_conflicts": [], "merge_allowed": True}
