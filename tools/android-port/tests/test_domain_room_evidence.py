"""AndroidOnly: WP-201 Real bounded Git/XML fixtures; not fabricated Android execution or source acceptance."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from fixtures import REPO
from controller.errors import PortError
from controller.model import Manifest
from controller.schema import load_json
from controller.verification_config import apply_content_scope, project_content_scope
from controller.scope_amendment import apply_translation_scope

COLLECTOR = REPO / "docs/android/evidence/WP-201/collect_evidence.py"
SPEC = importlib.util.spec_from_file_location("domain_room_evidence", COLLECTOR)
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)


class DomainRoomFixture:
    def __init__(self, root):
        self.root = root
        self.repo = root / "repo"
        self.repo.mkdir()
        self.baseline = load_json(REPO / collector.HISTORY)
        self.owned = [{key: value for key, value in item.items() if key not in ("source_sha256", "native_files")}
                      for item in self.baseline["inputs"]]
        self.work_packages = {wp["id"]: wp for wp in load_json(REPO / "docs/android/port-manifest.json")["work_packages"]}
        self.command("init", "--quiet", "--initial-branch=fixture-wp201")
        self.write(".gitignore", "**/build/\n**/Ignored.kt\n")
        for item in self.owned:
            self.write(item["path"], (REPO / item["path"]).read_bytes().replace(b"\r\n", b"\n"))
        for path in collector.CONTROL_INPUTS - {collector.SCHEMA}:
            self.write(path, (REPO / path).read_bytes().replace(b"\r\n", b"\n"))
        self.write("android/core/contracts/build.gradle.kts", "// Fixture-only existing neutral contract runner\n")
        self.write("android/core/contracts/src/test/kotlin/FixtureTest.kt",
                   f"// PortedFrom: {self.owned[0]['path']}@{collector.SOURCE}\nclass FixtureTest\n")
        self.commit()
        self.base = self.head
        headers = "".join(f"// PortedFrom: {item['path']}@{collector.SOURCE}\n" for item in self.owned)
        self.write("android/core/model/src/main/kotlin/FixtureModel.kt", headers + "class FixtureModel\n")
        self.write("android/core/model/src/test/kotlin/FixtureTest.kt", headers + "class FixtureTest\n")
        self.write("android/core/model/build.gradle.kts", "// Fixture-only JVM runner\n")
        self.write("android/core/database/build.gradle.kts", "// Fixture-only Android runner\n")
        self.write("android/core/database/src/main/kotlin/FixtureDatabase.kt", headers + "class FixtureDatabase\n")
        bindings = [case for case in self.baseline["source_cases"] if case["native_test"]["module"] == "database"]
        self.dao_source = "android/core/database/src/test/kotlin/SourceRoomCasesTest.kt"
        self.write(self.dao_source, headers + "package com.meshcoreone.android.core.database\nclass SourceRoomCasesTest {\n"
                   + "".join(f'    @OriginalCase("{case["id"]}")\n    fun {case["native_test"]["name"]}() {{}}\n' for case in bindings)
                   + "}\n")
        self.write(collector.SCHEMA, (REPO / collector.SCHEMA).read_bytes().replace(b"\r\n", b"\n"))
        self.commit()
        self.original = self.head
        self.reports()

    def command(self, *arguments, input=None):
        return subprocess.run(
            ["git", "-c", "core.autocrlf=false", "-c", "core.hooksPath=" + str(self.root / "no-hooks"),
             "-c", "commit.gpgsign=false", "-c", "user.name=Domain evidence fixture",
             "-c", "user.email=fixture@example.invalid", "-C", str(self.repo), *arguments],
            input=input, capture_output=True, text=True, check=True, timeout=30,
        ).stdout.strip()

    def write(self, relative, content):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content if isinstance(content, bytes) else content.encode("utf-8"))
        return path

    def commit(self):
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m",
                     "Temporary bounded evidence fixture\n\nCo-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>")
        self.head = self.command("rev-parse", "HEAD")

    def reports(self, cases=None):
        cases = self.baseline["native_cases"] if cases is None else cases
        result = {}
        for module, directory in collector.MODULES.items():
            selected = [case for case in cases if case["module"] == module]
            suite = ET.Element("testsuite", tests=str(len(selected)), failures="0", errors="0", skipped="0")
            for case in selected:
                ET.SubElement(suite, "testcase", classname=case["class"], name=case["name"])
            result[module] = self.write(directory + "/TEST-fixture.xml", ET.tostring(suite))
        return result

    def manifest(self):
        return Manifest(load_json(self.repo / "docs/android/port-manifest.json"), {}, self.repo)

    def successor(self):
        protocol_scope = next(path for path in self.work_packages["WP-101"]["write_paths"]
                              if path.startswith("android/core/protocol/src/main/") and path.endswith("/"))
        self.write(protocol_scope + "Successor.kt",
                   "// AndroidOnly: WP-101 Fixture-only unrelated successor; never WP-107 production\nclass Successor\n")
        self.write("android/core/ble/src/test/kotlin/NewModuleTest.kt",
                   "// AndroidOnly: WP-205 Fixture-only unrelated active-module source\nclass NewModuleTest\n")
        self.commit()
        return self.head


class DomainRoomEvidenceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.fixture = DomainRoomFixture(Path(temporary.name))
        self.environment = patch.dict(os.environ)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        for name in ("GITHUB_EVENT_PATH", "GITHUB_EVENT_NAME", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
            os.environ.pop(name, None)
        self.loader = patch.object(collector, "load_manifest", side_effect=lambda repo: self.fixture.manifest())
        self.loader.start()
        self.addCleanup(self.loader.stop)

    def report(self, **kwargs):
        return collector.report(self.fixture.repo, **kwargs)

    def test_original_isolated_writer_passes_the_explicit_exact_base_audit(self):
        result = self.report(audit_base=self.fixture.base)
        self.assertEqual(result["writer_scope_audit"]["base_sha"], self.fixture.base)
        self.assertEqual(result["writer_scope_audit"]["head_sha"], self.fixture.original)
        self.assertEqual(len(result["source_cases"]), 114)
        self.assertEqual(result["discovery"]["total"], 215)

    def test_committed_protocol_and_new_module_successors_pass_normal_validation(self):
        head = self.fixture.successor()
        result = self.report()
        self.assertEqual(result["execution"]["head_sha"], head)
        self.assertIsNone(result["writer_scope_audit"])
        self.assertEqual(result["discovery"]["total"], 215)
        self.assertEqual(len(result["source_cases"]), 114)

    def test_explicit_historical_audit_rejects_the_same_valid_successor(self):
        self.fixture.successor()
        with self.assertRaisesRegex(PortError, "writer-scope.*out-of-scope"):
            self.report(audit_base=self.fixture.base)

    def test_writer_audit_uses_requested_base_not_a_fixed_historical_revision(self):
        head = self.fixture.successor()
        result = self.report(audit_base=head)
        self.assertEqual(result["writer_scope_audit"]["base_sha"], head)
        self.assertEqual(result["writer_scope_audit"]["changed_paths"], [])
        for invalid in ("HEAD", "main", "0" * 39, True):
            with self.subTest(base=invalid), self.assertRaisesRegex(PortError, "exact requested"):
                self.report(audit_base=invalid)

    def test_execution_binding_is_actual_and_historical_worker_metadata_is_separate(self):
        result = self.report()
        self.assertEqual(result["execution"], {"head_sha": self.fixture.head, "branch": "fixture-wp201",
                                               "context": "local", "hosted_run": None})
        self.assertNotIn("session", result["execution"])
        self.assertNotIn("base_sha", result["execution"])
        self.assertEqual(result["historical_baseline"]["session"], self.fixture.baseline["session"])
        self.assertEqual(result["historical_baseline"]["base_sha"], self.fixture.baseline["base_sha"])
        self.fixture.command("checkout", "--quiet", "--detach", self.fixture.head)
        self.assertIsNone(self.report()["execution"]["branch"])

    def test_exact_content_scope_emits_current_binding_without_rewriting_original_room_receipt(self):
        for revision, manifest_sha, policy_sha in (
            (None, "39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda",
             "3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20"),
            ("7e2835bad2c03dfb5a088063655f9fc4dbafd00f",
             "fdbce89204ae5e391a2baac1c1aa4910742242b2007d32ac0efb799720cb4958",
             "661f067bd956f1c1867480350c2f50a538e600b952f8e44b03716d9e535afc68"),
        ):
            with self.subTest(revision=revision):
                if revision is not None:
                    for path in ("docs/android/port-manifest.json", "docs/android/automation-policy.json"):
                        self.fixture.write(path, subprocess.check_output(
                            ["git", "-C", str(REPO), "show", f"{revision}:{path}"]).decode("utf-8"))
                    self.fixture.commit()
                original = self.report()
                legacy = project_content_scope(self.fixture.manifest().data)
                amended = apply_content_scope(legacy)
                if revision is None:
                    amended = apply_translation_scope(amended)
                self.fixture.write("docs/android/port-manifest.json", json.dumps(amended))
                self.fixture.commit()
                result = self.report()
                self.assertEqual(result["manifest_sha256"], manifest_sha)
                self.assertEqual(result["policy_revision"], policy_sha)
                self.assertEqual(result["execution"]["head_sha"], self.fixture.head)
                self.assertEqual(result["historical_baseline"], original["historical_baseline"])
                self.assertEqual(result["source_cases"], original["source_cases"])
                self.assertEqual(result["native_cases"], original["native_cases"])
                self.assertEqual(len(result["source_cases"]), 114)
                self.assertEqual(result["discovery"]["total"], 215)
                self.assertEqual(collector.historical_baseline(
                    (self.fixture.repo / collector.HISTORY).read_bytes()), self.fixture.baseline)

    def test_scope_only_lineage_rejects_policy_and_unrelated_ownership_changes(self):
        manifest_path = self.fixture.repo / "docs/android/port-manifest.json"
        policy_path = self.fixture.repo / "docs/android/automation-policy.json"
        original_policy = policy_path.read_bytes()
        amended = apply_content_scope(project_content_scope(self.fixture.manifest().data))
        for mutation in ("foreign-path", "gate", "policy"):
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(amended)
                policy_path.write_bytes(original_policy)
                if mutation == "foreign-path":
                    changed["work_packages"][0]["write_paths"].append("android/app/")
                elif mutation == "gate":
                    changed["work_packages"][0]["human_gate"] = False
                else:
                    policy = json.loads(original_policy)
                    policy["trusted_check_app_ids"] = [999999]
                    policy_path.write_text(json.dumps(policy), encoding="utf-8")
                manifest_path.write_text(json.dumps(changed), encoding="utf-8")
                self.fixture.commit()
                with self.assertRaises(PortError):
                    self.report()

    def test_scope_projection_code_is_an_immutable_current_evidence_input(self):
        path = self.fixture.repo / "tools/android-port/controller/verification_config.py"
        path.write_bytes(path.read_bytes() + b"\n# changed scope projection\n")
        with self.assertRaisesRegex(PortError, "differs.*immutable Git"):
            self.report()

    def test_hosted_binding_uses_the_actual_event_head_base_run_and_attempt(self):
        from controller import ci

        event = {"pull_request": {"base": {"sha": self.fixture.base, "repo": {"full_name": "cbattlegear/MeshCoreOne-Android"}},
                                  "head": {"sha": self.fixture.head}}}
        path = self.fixture.root / "event.json"
        path.write_text(json.dumps(event), encoding="utf-8")
        os.environ.update(GITHUB_EVENT_PATH=str(path), GITHUB_EVENT_NAME="pull_request",
                          GITHUB_REPOSITORY="cbattlegear/MeshCoreOne-Android", GITHUB_RUN_ID="37210533774",
                          GITHUB_RUN_ATTEMPT="2")
        with patch.object(ci, "REPO", self.fixture.repo), patch.object(ci, "load_manifest", return_value=self.fixture.manifest()), \
                patch.object(ci, "policy_revision", return_value=collector.POLICY):
            result = self.report()
            hosted = result["execution"]["hosted_run"]
            self.assertEqual(hosted["binding"]["base_sha"], self.fixture.base)
            self.assertEqual(hosted["binding"]["head_sha"], self.fixture.head)
            self.assertEqual((hosted["run_id"], hosted["run_attempt"]), (37210533774, 2))
            event["pull_request"]["head"]["sha"] = "1" * 40
            path.write_text(json.dumps(event), encoding="utf-8")
            with self.assertRaisesRegex(PortError, "exact candidate"):
                self.report()

    def test_missing_hosted_event_is_not_a_local_success_fallback(self):
        os.environ["GITHUB_RUN_ID"] = "1"
        with self.assertRaisesRegex(PortError, "actual event"):
            self.report()

    def test_additive_cases_are_counted_without_fabricating_original_source_parity(self):
        cases = copy.deepcopy(self.fixture.baseline["native_cases"])
        for module in collector.MODULES:
            cases.append({"module": module, "class": "fixture.additive." + module, "name": "newNativeAssertion", "outcome": "passed"})
        self.fixture.reports(cases)
        result = self.report()
        self.assertEqual(result["discovery"], {"model": 167, "contracts": 5, "database": 46,
                                             "total": 218, "failed": 0, "errors": 0, "skipped": 0})
        self.assertEqual(len(result["source_cases"]), 114)
        self.assertEqual(len(result["additive_native_cases"]), 3)
        self.assertTrue(all("source" not in case for case in result["additive_native_cases"]))

    def test_every_one_of_the_114_original_families_remains_mandatory_with_additive_counts(self):
        for original in self.fixture.baseline["source_cases"]:
            with self.subTest(family=original["id"]):
                identity = collector.case_identity(original["native_test"])
                cases = [case for case in self.fixture.baseline["native_cases"] if collector.case_identity(case) != identity]
                cases.append({"module": identity[0], "class": "fixture.additive", "name": "replacementNotSourceParity", "outcome": "passed"})
                self.fixture.reports(cases)
                with self.assertRaisesRegex(PortError, "mandatory original native test identity"):
                    collector.collect_junit(self.fixture.repo, self.fixture.baseline)

    def test_replacing_a_nonfamily_baseline_assertion_with_an_addition_is_also_rejected(self):
        original_keys = {collector.case_identity(case["native_test"]) for case in self.fixture.baseline["source_cases"]}
        required = next(case for case in self.fixture.baseline["native_cases"] if collector.case_identity(case) not in original_keys)
        cases = [case for case in self.fixture.baseline["native_cases"] if case is not required]
        cases.append({"module": required["module"], "class": "fixture.additive", "name": "replacement", "outcome": "passed"})
        self.fixture.reports(cases)
        with self.assertRaisesRegex(PortError, "mandatory original native test identity"):
            self.report()

    def test_dirty_owned_source_build_schema_and_catalog_inputs_are_rejected(self):
        for path in ("android/core/model/src/main/kotlin/FixtureModel.kt", "android/core/database/build.gradle.kts",
                     collector.SCHEMA, "docs/android/test-cases.json"):
            file = self.fixture.repo / path
            raw = file.read_bytes()
            file.write_bytes(raw + b"\n// dirty compiled input\n")
            with self.subTest(path=path), self.assertRaisesRegex(PortError, "differs.*immutable Git"):
                self.report()
            file.write_bytes(raw)

    def test_ignored_uncommitted_owned_source_is_not_hidden_by_gitignore(self):
        self.fixture.write("android/core/model/src/test/kotlin/Ignored.kt", "class Ignored\n")
        with self.assertRaisesRegex(PortError, "Uncommitted owned"):
            self.report()

    def test_staged_owned_replacement_cannot_hide_behind_a_restored_checkout(self):
        path = self.fixture.repo / "android/core/model/src/main/kotlin/FixtureModel.kt"
        original = path.read_bytes()
        path.write_bytes(original + b"\nclass StagedReplacement\n")
        self.fixture.command("add", "--", path.relative_to(self.fixture.repo).as_posix())
        path.write_bytes(original)
        with self.assertRaisesRegex(PortError, "Staged owned"):
            self.report()

    def test_missing_or_unsafe_committed_source_is_rejected(self):
        path = self.fixture.repo / "android/core/model/src/main/kotlin/FixtureModel.kt"
        path.unlink()
        with self.assertRaisesRegex(PortError, "Missing/unsafe"):
            self.report()
        blob = self.fixture.command("hash-object", "-w", "--stdin", input="../outside")
        self.fixture.command("update-index", "--add", "--cacheinfo",
                             "120000," + blob + ",android/core/model/src/main/kotlin/FixtureModel.kt")
        self.fixture.command("commit", "--quiet", "-m",
                             "Unsafe fixture input\n\nCo-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>")
        with self.assertRaisesRegex(PortError, "Unsupported reference entry"):
            self.report()

    def test_committed_and_dirty_swift_reference_drift_are_rejected(self):
        path = self.fixture.repo / self.fixture.owned[0]["path"]
        path.write_bytes(path.read_bytes() + b"\n// forbidden Swift drift\n")
        with self.assertRaisesRegex(PortError, "differs.*immutable Git"):
            self.report()
        self.fixture.commit()
        with self.assertRaisesRegex(PortError, "Swift reference input changed"):
            self.report()

    def test_only_git_text_newline_normalization_is_permitted(self):
        path = self.fixture.repo / "android/core/model/src/main/kotlin/FixtureModel.kt"
        path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual(self.report()["discovery"]["total"], 215)

    def test_python_collector_crlf_is_git_text_but_changed_code_is_not(self):
        path = self.fixture.repo / "docs/android/evidence/WP-201/collect_evidence.py"
        path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual(self.report()["discovery"]["total"], 215)
        path.write_bytes(path.read_bytes() + b"\r\n# uncommitted collector code\r\n")
        with self.assertRaisesRegex(PortError, "differs.*immutable Git"):
            self.report()

    def test_missing_zero_malformed_and_unsafe_xml_fail_closed(self):
        for kind in ("missing", "zero", "malformed", "doctype", "entity", "suite-root"):
            reports = self.fixture.reports()
            path = reports["model"]
            if kind == "missing":
                path.unlink()
            else:
                data = {
                    "zero": b'<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
                    "malformed": b"<testsuite",
                    "doctype": b"<!DOCTYPE testsuite><testsuite/>",
                    "entity": b'<!DOCTYPE testsuite [<!ENTITY x "unsafe">]><testsuite>&x;</testsuite>',
                    "suite-root": b'<testsuites tests="166" failures="0" errors="0" skipped="0"/>',
                }[kind]
                path.write_bytes(data)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                self.report()

    def test_failed_error_skipped_and_bad_suite_counters_are_rejected(self):
        for kind in ("failure", "error", "skipped", "suite-failures", "suite-errors", "suite-skipped",
                     "false-tests", "missing-count", "negative", "not-integer"):
            path = self.fixture.reports()["model"]
            root = ET.fromstring(path.read_bytes())
            if kind in ("failure", "error", "skipped"):
                ET.SubElement(root.find("testcase"), kind)
            elif kind.startswith("suite-"):
                root.set(kind.removeprefix("suite-"), "1")
            elif kind == "missing-count":
                root.attrib.pop("tests")
            else:
                root.set("tests", {"false-tests": "167", "negative": "-1", "not-integer": "invalid"}[kind])
            path.write_bytes(ET.tostring(root))
            with self.subTest(kind=kind), self.assertRaises(PortError):
                self.report()

    def test_encoded_unsafe_entity_xml_cannot_bypass_the_shared_byte_guard(self):
        for encoding in ("utf-16", "utf-16-be", "utf-32"):
            path = self.fixture.reports()["model"]
            xml = path.read_bytes().decode("utf-8").replace(
                "</testsuite>", "<system-out>&payload;</system-out></testsuite>")
            path.write_bytes((f'<?xml version="1.0" encoding="{encoding}"?>'
                              '<!DOCTYPE testsuite [<!ENTITY payload "fixture-entity">]>' + xml).encode(encoding))
            with self.subTest(encoding=encoding), self.assertRaisesRegex(PortError, "Unsafe declarations"):
                self.report()

    def test_duplicate_and_missing_test_identities_are_rejected(self):
        for kind in ("duplicate", "missing-name", "missing-class"):
            path = self.fixture.reports()["model"]
            root = ET.fromstring(path.read_bytes())
            first, second = root.findall("testcase")[:2]
            if kind == "duplicate":
                second.attrib.update(first.attrib)
            else:
                first.attrib.pop("name" if kind == "missing-name" else "classname")
            path.write_bytes(ET.tostring(root))
            with self.subTest(kind=kind), self.assertRaisesRegex(PortError, "identity"):
                self.report()

    def test_duplicate_suite_file_is_not_extra_discovery(self):
        original = self.fixture.repo / collector.MODULES["model"] / "TEST-fixture.xml"
        self.fixture.write(collector.MODULES["model"] + "/TEST-duplicate.xml", original.read_bytes())
        with self.assertRaisesRegex(PortError, "duplicate"):
            self.report()

    def test_missing_mapping_and_original_dao_annotation_are_rejected(self):
        path = self.fixture.repo / self.fixture.dao_source
        path.write_bytes(path.read_bytes().replace(b"@OriginalCase", b"@RemovedOriginalCase"))
        self.fixture.commit()
        with self.assertRaisesRegex(PortError, "original DAO binding"):
            self.report()
        for path in ("android/core/model/src/main/kotlin/FixtureModel.kt", "android/core/model/src/test/kotlin/FixtureTest.kt",
                     "android/core/database/src/main/kotlin/FixtureDatabase.kt", self.fixture.dao_source):
            file = self.fixture.repo / path
            first = self.fixture.owned[0]["path"].encode("utf-8")
            file.write_bytes(b"\n".join(line for line in file.read_bytes().split(b"\n") if first not in line))
        self.fixture.commit()
        with self.assertRaisesRegex(PortError, "Missing primary input mapping"):
            self.report()

    def test_missing_changed_duplicate_or_stale_original_catalog_families_are_rejected(self):
        original = load_json(self.fixture.repo / "docs/android/test-cases.json")
        path = self.fixture.baseline["source_cases"][0]["source"]
        for kind in ("missing", "changed", "duplicate", "stale-blob", "no-assertions"):
            catalog = copy.deepcopy(original)
            entry = next(entry for entry in catalog["entries"] if entry["path"] == path)
            if kind == "missing":
                entry["cases"].pop()
            elif kind == "changed":
                entry["cases"][0]["parameter_family"] = "unapproved-family"
            elif kind == "duplicate":
                entry["cases"].append(copy.deepcopy(entry["cases"][0]))
            elif kind == "stale-blob":
                entry["blob_sha"] = "0" * 40
            else:
                entry["has_assertions"] = False
            self.fixture.write("docs/android/test-cases.json", json.dumps(catalog))
            self.fixture.commit()
            with self.subTest(kind=kind), self.assertRaisesRegex(PortError, "original.*(family|blob)"):
                self.report()

    def test_historical_record_cannot_be_rewritten_as_current_execution(self):
        history = copy.deepcopy(self.fixture.baseline)
        history["session"] = "fabricated-current-worker"
        self.fixture.write(collector.HISTORY, json.dumps(history))
        self.fixture.commit()
        with self.assertRaisesRegex(PortError, "Frozen historical"):
            self.report()

    def test_schema_version_entities_identity_fields_and_both_foreign_keys_are_frozen(self):
        original = load_json(self.fixture.repo / collector.SCHEMA)
        for kind in ("version", "boolean-version", "entities", "identity", "field", "messages-fk", "trace-fk"):
            value = copy.deepcopy(original)
            schema = value["database"]
            if kind in ("version", "boolean-version"):
                schema["version"] = 2 if kind == "version" else True
            elif kind == "entities":
                schema["entities"].pop()
            elif kind == "identity":
                schema["identityHash"] = "0" * 32
            elif kind == "field":
                schema["entities"][0]["fields"][0]["notNull"] = False
            else:
                table = "message_repeats" if kind == "messages-fk" else "trace_path_runs"
                entity = next(entity for entity in schema["entities"] if entity["tableName"] == table)
                entity["foreignKeys"][0]["onDelete"] = "NO ACTION"
            self.fixture.write(collector.SCHEMA, json.dumps(value))
            self.fixture.commit()
            with self.subTest(kind=kind), self.assertRaisesRegex(PortError, "Room"):
                self.report()

    def test_duplicate_or_malformed_schema_json_is_rejected(self):
        for raw in ('{"formatVersion":1,"formatVersion":1}', '{"database":'):
            self.fixture.write(collector.SCHEMA, raw)
            self.fixture.commit()
            with self.subTest(raw=raw), self.assertRaisesRegex(PortError, "JSON"):
                self.report()

    def test_cli_never_implicitly_audits_or_rewrites_historical_output(self):
        for arguments in (["--audit-writer-scope"], ["--base-sha", self.fixture.base], ["--write"]):
            with self.subTest(arguments=arguments), self.assertRaises(SystemExit) as error:
                collector.main(arguments)
            self.assertEqual(error.exception.code, 2)
        self.assertEqual(collector.main(["--output", str(REPO / collector.HISTORY)]), 2)


if __name__ == "__main__":
    unittest.main()
