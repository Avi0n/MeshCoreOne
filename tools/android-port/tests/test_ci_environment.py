"""AndroidOnly: WP-003 Deterministic isolation/archive tests, not live-host evidence."""

import contextlib
import copy
import hashlib
import io
import json
import os
import tempfile
import tarfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import asdict
from pathlib import Path
from unittest.mock import patch

from fixtures import REPO
from controller.ci_environment import candidate_environment, host_name, toolchain_lock, verify_wrapper
from controller.errors import PortError
from controller.gates import Binding, policy_revision
from controller.model import git, load_manifest
from controller.provision import download_archive, extract_archive, local_package_xml, provision, safe_member, sdk_metadata
from controller.schema import load_json


class EnvironmentTests(unittest.TestCase):
    def state(self, directory):
        return {
            "schema_version": 1, "host": host_name(), "java_home": str(directory / "jdk"),
            "android_home": str(directory / "sdk"), "private_root": str(directory / "private"),
            "provenance": {"scope": "fixture only"},
        }

    def forwarding_identity(self):
        manifest = load_manifest(REPO)
        rules = load_json(REPO / "docs" / "android" / "automation-policy.json")
        head = git(REPO, "rev-parse", "HEAD").decode().strip()
        return {
            "binding": asdict(Binding(
                "cbattlegear/MeshCoreOne-Android", "WP-003", "1" * 40, head,
                manifest.data["reference"]["commit"], manifest.sha256, policy_revision(manifest, rules),
            )),
            "run_id": 123, "run_attempt": 2,
        }

    def captured_stage(self, directory, host, stage, identity, *, local=False, scopes=None):
        from controller.ci import run_stage

        state = {**self.state(directory), "host": host}
        output = directory / "evidence"
        calls = []

        def execute(command, environment, log, **kwargs):
            calls.append((command, environment, log, kwargs))
            if log.name == f"gradle-{stage}-preflight.log":
                graph = Path(environment["ANDROID_CI_TASK_GRAPH"])
                graph.parent.mkdir(parents=True, exist_ok=True)
                graph.write_text(json.dumps({
                    "schema_version": 1,
                    "tasks": [
                        {"path": ":" + task, "type": "fixture", "command": []}
                        for task in (
                            "resolveScaffoldDependencies", "validateModuleGraph",
                            "runtimeDependencyInventory",
                        )
                    ],
                }), encoding="utf-8")
            elif log.name == f"gradle-{stage}.log":
                raise PortError("Declared command fixture failure; never a real JVM run")

        with (
            patch("controller.ci_environment.host_name", return_value=host),
            patch("controller.ci.execution_identity", return_value=identity) as observe_identity,
            patch("controller.ci.verify_wrapper"),
            patch("controller.ci.shutil.which", return_value="fixture-pwsh"),
            patch("controller.ci.execute", side_effect=execute),
            patch.dict(os.environ, {"GH_TOKEN": "fixture-only", "GITHUB_TOKEN": "fixture-only",
                                    "ANDROID_CI_OUTPUT": str(output)}),
            self.assertRaises(PortError),
        ):
            run_stage(stage, state, output, local=local, scopes=scopes)
        split_preflight = stage == "scaffold" and (scopes is None or "scaffold" in scopes)
        self.assertEqual(3 if split_preflight else 2, len(calls))
        self.assertFalse((output / f"stage-{stage}.json").exists(), "A failed command cannot publish success")
        main_index = len(calls) - 1
        preflight_arguments = None
        if host == "windows":
            wrapper_record = load_json(output / f"gradle-{stage}-invocation.json")
            arguments = wrapper_record["arguments"]
            self.assertIn("-InvocationFile", calls[main_index][0])
            self.assertEqual(str(output / f"gradle-{stage}-invocation.json"), calls[main_index][0][-1])
            if split_preflight:
                preflight_record = load_json(output / f"gradle-{stage}-preflight-invocation.json")
                preflight_arguments = preflight_record["arguments"]
                self.assertEqual(
                    str(output / f"gradle-{stage}-preflight-invocation.json"), calls[1][0][-1],
                )
        else:
            arguments = calls[main_index][0][3:]
            if split_preflight:
                preflight_arguments = calls[1][0][3:]
        for key in ("GH_TOKEN", "GITHUB_TOKEN", "ANDROID_CI_OUTPUT", "GITHUB_RUN_ID", "GITHUB_EVENT_PATH"):
            self.assertNotIn(key, calls[main_index][1], "No credential or disallowed parent environment forwarding")
        self.assertIn("--dependency-verification", arguments)
        self.assertEqual("strict", arguments[arguments.index("--dependency-verification") + 1])
        self.assertNotIn("--rerun-tasks", arguments)
        self.assertIn("--build-cache", arguments)
        self.assertIn("--max-workers=8", arguments)
        self.assertIn("--parallel", arguments)
        self.assertNotIn("--no-build-cache", arguments)
        if split_preflight:
            self.assertIn("--parallel", preflight_arguments)
            self.assertIn("--max-workers=8", preflight_arguments)
            self.assertNotIn("--max-workers=1", preflight_arguments)
            self.assertIn("--build-cache", preflight_arguments)
        return output, state, arguments, observe_identity.call_count, preflight_arguments

    def test_scaffold_selects_android_test_compilation_without_install_or_execution(self):
        expected = [
            "verifyScaffoldTests", "verifyRoomSchema", "validateModuleGraph",
            "runtimeDependencyInventory", "resolveScaffoldDependencies",
            ":app:assembleDebug", ":app:compileDebugAndroidTestKotlin", "lintScaffold",
        ]
        for host in ("linux", "windows"):
            for scopes in (None, ["scaffold"], ["scaffold", "protocol"]):
                with self.subTest(host=host, scopes=scopes), tempfile.TemporaryDirectory() as temporary:
                    _, _, arguments, _, preflight_arguments = self.captured_stage(
                        Path(temporary), host, "scaffold", self.forwarding_identity(), scopes=scopes,
                    )
                    build_tasks = arguments[:arguments.index("--no-daemon")]
                    preflight_tasks = preflight_arguments[:preflight_arguments.index("--no-daemon")]
                    self.assertEqual(
                        preflight_tasks,
                        ["resolveScaffoldDependencies", "validateModuleGraph", "runtimeDependencyInventory"],
                    )
                    tasks = [*preflight_tasks, *build_tasks]
                    self.assertCountEqual(
                        tasks,
                        expected + ([":core:protocol:test"] if scopes and "protocol" in scopes else []),
                    )
                    self.assertEqual(build_tasks.count(":app:compileDebugAndroidTestKotlin"), 1)
                    self.assertFalse(any(
                        forbidden in task.lower()
                        for task in tasks for forbidden in ("connected", "install", "device")
                    ))

    def test_scaffold_graph_requires_the_declared_android_test_compiler(self):
        from controller.ci import TASKS, validate_candidate_task_graph

        compiler = ":app:compileDebugAndroidTestKotlin"
        self.assertIn(compiler, TASKS["scaffold"])
        records = [
            {"path": task if task.startswith(":") else ":" + task, "type": "fixture", "command": []}
            for task in TASKS["scaffold"]
        ]
        with tempfile.TemporaryDirectory() as temporary:
            graph = Path(temporary) / "graph.json"
            graph.write_text(json.dumps({"schema_version": 1, "tasks": records}), encoding="utf-8")
            self.assertEqual(validate_candidate_task_graph(graph, TASKS["scaffold"])["tasks"], len(records))
            graph.write_text(json.dumps({
                "schema_version": 1, "tasks": [record for record in records if record["path"] != compiler],
            }), encoding="utf-8")
            with self.assertRaisesRegex(PortError, "Requested Gradle task missing.*compileDebugAndroidTestKotlin"):
                validate_candidate_task_graph(graph, TASKS["scaffold"])

    def test_successful_compile_fixture_cannot_replace_missing_or_zero_unit_reports(self):
        from controller import ci

        collect_suites = ci.collect_suites
        for empty_report in (False, True):
            with self.subTest(empty_report=empty_report), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                android = directory / "android"
                if empty_report:
                    reports = android / "build-logic/convention/build/test-results/test"
                    reports.mkdir(parents=True)
                    (reports / "TEST-Fixture.xml").write_text(
                        '<testsuite tests="0" failures="0" errors="0" skipped="0"/>', encoding="utf-8",
                    )

                def execute(command, environment, log, **kwargs):
                    if log.name in ("gradle-scaffold-preflight.log", "gradle-scaffold.log"):
                        if log.name == "gradle-scaffold.log":
                            self.assertIn(":app:compileDebugAndroidTestKotlin", command)
                        graph = Path(environment["ANDROID_CI_TASK_GRAPH"])
                        graph.parent.mkdir(parents=True, exist_ok=True)
                        requested = []
                        for argument in command[3:]:
                            if argument.startswith("-"):
                                break
                            requested.append(argument)
                        graph.write_text(json.dumps({
                            "schema_version": 1,
                            "tasks": [
                                {"path": task if task.startswith(":") else ":" + task,
                                 "type": "fixture", "command": []}
                                for task in requested
                            ],
                        }), encoding="utf-8")

                with (
                    patch.object(ci, "verify_wrapper"),
                    patch.object(ci, "candidate_environment", return_value={}),
                    patch.object(ci, "execute", side_effect=execute),
                    patch.object(ci, "collect_suites", side_effect=lambda _: collect_suites(android)) as collect,
                    contextlib.redirect_stdout(io.StringIO()) as printed,
                    self.assertRaisesRegex(PortError, "Missing mandatory test reports|Zero, failed, skipped"),
                ):
                    ci.run_stage("scaffold", {**self.state(directory), "host": "linux"}, directory / "output")
                collect.assert_called_once()
                self.assertNotIn('"result": "success"', printed.getvalue())

    def test_prepare_resolves_dependencies_without_demanding_test_reports(self):
        from controller.ci import run_stage

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            calls = []
            def capture(command, environment, log, **kwargs):
                calls.append((command, log))
                if log.name == "gradle-prepare.log":
                    graph = Path(environment["ANDROID_CI_TASK_GRAPH"])
                    graph.parent.mkdir(parents=True, exist_ok=True)
                    graph.write_text(
                        '{"schema_version":1,"tasks":[{"path":":resolveScaffoldDependencies",'
                        '"type":"fixture","command":[]}]}\n',
                        encoding="utf-8",
                    )
            with (
                patch("controller.ci.verify_wrapper"),
                patch("controller.ci.candidate_environment", return_value={}),
                patch("controller.ci.execute", side_effect=capture),
                patch("controller.ci.collect_suites", side_effect=AssertionError("prepare read scaffold reports")),
                patch("controller.ci.check_module_tests", side_effect=AssertionError("prepare read module reports")),
                patch("controller.ci.collect_lint", side_effect=AssertionError("prepare read lint reports")),
                patch("controller.ci.suite_counts", side_effect=AssertionError("prepare read suite reports")),
            ):
                run_stage("prepare", self.state(directory), directory / "evidence")
            self.assertEqual(2, len(calls))
            arguments = (
                load_json(directory / "evidence/gradle-prepare-invocation.json")["arguments"]
                if host_name() == "windows" else calls[1][0]
            )
            self.assertIn("resolveScaffoldDependencies", arguments)
            self.assertNotIn("verifyScaffoldTests", arguments)
            self.assertIn("--build-cache", arguments)
            self.assertIn("--max-workers=8", arguments)
            self.assertIn("--parallel", arguments)
            self.assertIn("--init-script", arguments)
            self.assertTrue(arguments[arguments.index("--init-script") + 1].endswith(
                "verify_candidate_task_graph.gradle"
            ))

    def test_only_wp203_test_task_is_cacheable_and_its_side_effects_are_declared(self):
        script = (REPO / "tools/android-port/controller/verify_candidate_task_graph.gradle").read_text(
            encoding="utf-8",
        )
        cache_policy = (
            'task.outputs.cacheIf("Only WP-203 test inputs and interop outputs are explicitly declared") {\n'
            '            task.path == ":core:data:testDebugUnitTest"\n'
            "        }"
        )
        self.assertIn(cache_policy, script)
        self.assertEqual(script.count("task.outputs.cacheIf("), 1)
        self.assertLess(script.index(cache_policy), script.index('if (task.path == ":core:data:testDebugUnitTest")'))
        self.assertIn('task.outputs.dir(interopOutput)', script)
        self.assertIn("task.inputs.dir(interopInput)", script)
        self.assertIn("task.doFirst", script)
        self.assertIn("task.inputs.files.files.any", script)
        self.assertLess(script.index("task.doFirst {"), script.index("task.inputs.files.files.any"))
        self.assertLess(script.index("task.inputs.files.files.any"), script.index("gradle.taskGraph.whenReady"))
        self.assertIn("WP-203 interop artifacts are not declared Gradle task outputs", script)
        self.assertIn("WP-203 interoperability fixture is not a Gradle task input", script)

    def test_app_tests_reuse_the_worker_jvm_instead_of_forking_for_each_class(self):
        app_build = (REPO / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        conventions = (
            REPO / "android/build-logic/convention/src/main/kotlin/"
            "com/meshcoreone/buildlogic/BuildConventions.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("forkEvery = 0", app_build)
        self.assertNotIn("forkEvery = 1", app_build)
        self.assertIn("maxParallelForks = 1", conventions)

    def test_resolved_gradle_graph_rejects_all_duplicate_result_task_and_script_families(self):
        from controller.ci import validate_candidate_task_graph

        safe = [
            {"path": ":verifyScaffoldTests", "type": "fixture", "command": []},
            {"path": ":core:protocol:test", "type": "fixture", "command": []},
        ]
        rejected = (
            {"path": ":retainMeshCliEvidence", "type": "fixture", "command": []},
            {"path": ":collectProtocolEvidence", "type": "fixture", "command": []},
            {"path": ":verifyRuntimeEvidenceReaders", "type": "fixture", "command": []},
            {"path": ":stageResultReport", "type": "fixture", "command": []},
            {
                "path": ":verifyInputs",
                "type": "fixture",
                "command": ["python", str(REPO / "docs/android/evidence/WP-999/collect_evidence.py")],
            },
        )
        with tempfile.TemporaryDirectory() as temporary:
            graph = Path(temporary) / "graph.json"
            graph.write_text(
                json.dumps({"schema_version": 1, "tasks": safe}) + "\n",
                encoding="utf-8",
            )
            self.assertEqual(
                validate_candidate_task_graph(graph, ["verifyScaffoldTests", ":core:protocol:test"]),
                {"tasks": 2, "result": "no-duplicate-ci-evidence"},
            )
            packaging = {
                "path": ":verifyPackaging",
                "type": "fixture",
                "command": ["python", str(REPO / "docs/android/evidence/WP-999/inspect_packaging.py"), "--self-test"],
            }
            graph.write_text(
                json.dumps({"schema_version": 1, "tasks": [safe[0], packaging]}) + "\n",
                encoding="utf-8",
            )
            self.assertEqual(
                validate_candidate_task_graph(graph, ["verifyScaffoldTests"]),
                {"tasks": 2, "result": "no-duplicate-ci-evidence"},
            )
            for record in rejected:
                with self.subTest(record=record["path"]):
                    graph.write_text(
                        json.dumps({"schema_version": 1, "tasks": [safe[0], record]}) + "\n",
                        encoding="utf-8",
                    )
                    with self.assertRaises(PortError):
                        validate_candidate_task_graph(graph, ["verifyScaffoldTests"])

    def test_parent_allowlist_is_preserved_and_credentials_are_not_inherited(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = self.state(Path(temporary))
            inherited = {
                "PATH": "fixture-path", "GH_TOKEN": "fixture-not-a-secret",
                "GITHUB_TOKEN": "fixture-not-a-secret", "COPILOT_GITHUB_TOKEN": "fixture-not-a-secret",
                "ACTIONS_RUNTIME_TOKEN": "fixture-not-a-secret", "SIGNING_PASSWORD": "fixture-not-a-secret",
                "ANDROID_PORT_GITHUB_TOKEN": "fixture-not-a-secret", "NODE_OPTIONS": "fixture-options",
                "JAVA_OPTS": "-Dcredential=fixture", "GRADLE_OPTS": "-Dcredential=fixture",
                "GRADLE_USER_HOME": str(Path(temporary) / "restored-gradle-cache"),
            }
            value = candidate_environment(state, inherited)
            for key in inherited.keys() - {"PATH", "JAVA_OPTS", "GRADLE_OPTS", "GRADLE_USER_HOME"}:
                self.assertNotIn(key, value)
            self.assertNotIn("credential", value["JAVA_OPTS"] + value["GRADLE_OPTS"])
            self.assertEqual(value["ANDROID_HOME"], state["android_home"])
            self.assertEqual(value["GRADLE_USER_HOME"], inherited["GRADLE_USER_HOME"])
            standalone = candidate_environment(state, inherited, standalone=True)
            self.assertEqual(value["GRADLE_USER_HOME"], standalone["GRADLE_USER_HOME"])

    def test_private_absolute_inputs_and_exact_host_are_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = self.state(Path(temporary))
            for key, value in (("java_home", "relative"), ("private_root", ""), ("host", "macos"), ("schema_version", 2)):
                with self.subTest(key=key), self.assertRaises(PortError):
                    candidate_environment({**state, key: value}, {})

    def test_hosted_budgets_do_not_copy_local_shared_host_jit_flags(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = self.state(Path(temporary))
            hosted = candidate_environment(state, {})
            local = candidate_environment(state, {}, local=True)
            self.assertNotIn("TieredStopAtLevel", hosted["GRADLE_OPTS"])
            self.assertIn("TieredStopAtLevel=1", local["GRADLE_OPTS"])
            self.assertIn("-Xmx2048m", hosted["GRADLE_OPTS"])
            self.assertIn("-Xmx768m", local["GRADLE_OPTS"])

    def test_exact_host_archive_sets_and_unchanged_wrapper_pins(self):
        lock = toolchain_lock()
        for host in ("linux", "windows"):
            selected = [a for a in lock["archives"] if a["host"] in ("all", host)]
            self.assertEqual(len(selected), 4)
            self.assertEqual({a["id"] for a in selected}, {
                "jdk", "platforms;android-37.2", "build-tools;37.0.0", "cmdline-tools;23.0",
            })
        verify_wrapper()

    def test_full_assembly_selects_only_local_heap_without_changing_default_profiles(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = self.state(Path(temporary))
            before = copy.deepcopy(state)
            local = candidate_environment(state, {}, local=True)
            assembly = candidate_environment(state, {}, local=True, full_assembly=True)
            self.assertEqual(assembly, {
                **local, "GRADLE_OPTS": local["GRADLE_OPTS"].replace("-Xmx768m", "-Xmx2048m"),
            })
            self.assertEqual(candidate_environment(state, {}, full_assembly=True),
                             candidate_environment(state, {}))
            self.assertEqual(state, before)

    def test_archive_digest_and_size_are_verified_before_extracting(self):
        content = b"publisher-fixture-bytes"
        record = {
            "url": "https://dl.google.com/android/repository/fixture.zip", "size": len(content),
            "sha256": hashlib.sha256(content).hexdigest(), "sha1": hashlib.sha1(content).hexdigest(),
        }
        with tempfile.TemporaryDirectory() as temporary:
            for index, changes in enumerate(({}, {"sha256": "0" * 64}, {"size": 1}, {"sha1": "0" * 40})):
                with self.subTest(changes=changes), patch("urllib.request.urlopen", return_value=io.BytesIO(content)):
                    target = Path(temporary) / f"{index}.zip"
                    if changes:
                        with self.assertRaises(PortError):
                            download_archive({**record, **changes}, target)
                    else:
                        download_archive(record, target)
                        self.assertEqual(target.read_bytes(), content)

    def test_archive_paths_cannot_escape_private_installation(self):
        for name in ("../outside", "/outside", r"C:\outside", "root/../../outside", r"root\outside", "C:outside"):
            with self.subTest(name=name), self.assertRaises(PortError):
                safe_member(name)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "fixture.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("../outside", "fixture")
            with self.assertRaises(PortError):
                extract_archive(archive, root / "private")
            self.assertFalse((root / "outside").exists())

    def test_sdk_xml_preserves_real_type_namespaces_without_duplicate_attributes(self):
        data = b"""<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:generic="http://schemas.android.com/repository/android/generic/03">
          <license id="android-sdk-license">fixture terms</license>
          <remotePackage path="build-tools;37.0.0"><type-details xsi:type="generic:genericDetailsType"/>
            <revision><major>37</major></revision><archives/><channelRef ref="channel-0"/>
          </remotePackage></sdk:sdk-repository>"""
        result = local_package_xml(ET.fromstring(data), data, "build-tools;37.0.0")
        parsed = ET.fromstring(result)
        self.assertEqual(parsed.find("localPackage").get("path"), "build-tools;37.0.0")
        self.assertIsNone(parsed.find("localPackage/archives"))
        self.assertIn(b"xmlns:generic=", result)

    def test_changed_sdk_terms_and_metadata_are_not_silently_accepted(self):
        lock = toolchain_lock()
        data = b'<repository><license id="android-sdk-license">changed fixture terms</license></repository>'
        with self.assertRaisesRegex(PortError, "terms changed"):
            sdk_metadata(lock, [], data)
        for data in (b"<!DOCTYPE repository><repository/>", b"<!ENTITY fixture>"):
            with self.subTest(data=data), self.assertRaises(PortError):
                sdk_metadata(lock, [], data)

    def test_sdk_revision_and_host_mismatches_identify_package_without_accepting_drift(self):
        lock = copy.deepcopy(toolchain_lock())
        terms = b"fixture SDK terms"
        lock["sdk_license_sha256"] = hashlib.sha256(terms).hexdigest()
        record = next(a for a in lock["archives"] if a["id"] == "build-tools;37.0.0" and a["host"] == "linux")
        repository = ET.Element("repository")
        ET.SubElement(repository, "license", {"id": lock["sdk_license_id"]}).text = terms.decode()
        package = ET.SubElement(repository, "remotePackage", {"path": record["id"]})
        revision = ET.SubElement(package, "revision")
        for part, value in zip(("major", "minor", "micro"), record["revision"].split("."), strict=True):
            ET.SubElement(revision, part).text = value
        archive_list = ET.SubElement(package, "archives")
        archive = ET.SubElement(archive_list, "archive")
        host = ET.SubElement(archive, "host-os")
        host.text = record["host"]
        complete = ET.SubElement(archive, "complete")
        for key, value in (("url", record["url"].rsplit("/", 1)[1]), ("size", record["size"]), ("checksum", record["sha1"])):
            ET.SubElement(complete, key).text = str(value)

        def verify():
            return sdk_metadata(lock, [record], ET.tostring(repository))

        self.assertIsNotNone(verify())
        revision.find("major").text = "38"
        with self.assertRaises(PortError) as error:
            verify()
        self.assertIn("'build-tools;37.0.0'", str(error.exception))
        self.assertIn("expected revision '37.0.0', host 'linux'", str(error.exception))
        self.assertIn("observed revision '38.0.0', hosts ['linux'] (1 total archives, 1 matching host)", str(error.exception))

        revision.find("major").text = "37"
        host.text = "windows\nunexpected"
        with self.assertRaises(PortError) as error:
            verify()
        self.assertIn(r"hosts ['windows\nunexpected'] (1 total archives, 0 matching host)", str(error.exception))
        self.assertNotIn("\n", str(error.exception))

        host.text = "linux"
        archive_list.append(copy.deepcopy(archive))
        with self.assertRaises(PortError) as error:
            verify()
        self.assertIn("hosts ['linux', 'linux'] (2 total archives, 2 matching host)", str(error.exception))

    def test_both_host_installers_extract_verified_archives_without_executing_or_downloading_cli_helpers(self):
        for host in ("windows", "linux"):
            with self.subTest(host=host), tempfile.TemporaryDirectory() as temporary:
                lock = copy.deepcopy(toolchain_lock())
                terms = b"fixture SDK terms"
                lock["sdk_license_sha256"] = hashlib.sha256(terms).hexdigest()
                lock["sdk_license_sha1"] = hashlib.sha1(terms).hexdigest()
                selected = [a for a in lock["archives"] if a["host"] in ("all", host)]
                downloads = {}
                repository = ET.Element("repository")
                ET.SubElement(repository, "license", {"id": "android-sdk-license"}).text = terms.decode()
                for item in selected:
                    content = io.BytesIO()
                    if item["id"] == "jdk" and host == "linux":
                        with tarfile.open(fileobj=content, mode="w:gz") as archive:
                            file = tarfile.TarInfo("publisher-root/bin/java")
                            file.size = 7
                            archive.addfile(file, io.BytesIO(b"fixture"))
                    else:
                        with zipfile.ZipFile(content, "w") as archive:
                            archive.writestr("publisher-root/source.properties", "fixture only")
                            archive.writestr("publisher-root/bin/android", "must never execute")
                    data = content.getvalue()
                    item.update({"size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                                 "sha1": None if item["id"] == "jdk" else hashlib.sha1(data).hexdigest()})
                    downloads[item["url"]] = data
                    if item["id"] == "jdk":
                        continue
                    package = ET.SubElement(repository, "remotePackage", {"path": item["id"]})
                    revision = ET.SubElement(package, "revision")
                    for part, value in zip(("major", "minor", "micro"), item["revision"].split("."), strict=True):
                        ET.SubElement(revision, part).text = value
                    archive = ET.SubElement(ET.SubElement(package, "archives"), "archive")
                    if item["host"] != "all":
                        ET.SubElement(archive, "host-os").text = host
                    complete = ET.SubElement(archive, "complete")
                    for key, value in (("size", item["size"]), ("checksum", item["sha1"]), ("url", item["url"].rsplit("/", 1)[1])):
                        ET.SubElement(complete, key).text = str(value)
                downloads[lock["sdk_metadata_url"]] = ET.tostring(repository)
                requested = []

                def fetch(request, **kwargs):
                    url = request if isinstance(request, str) else request.full_url
                    requested.append(url)
                    if url not in downloads:
                        raise AssertionError("Undeclared CLI/helper download")
                    return io.BytesIO(downloads[url])

                with (
                    patch("controller.provision.toolchain_lock", return_value=lock),
                    patch("controller.provision.host_name", return_value=host),
                    patch("urllib.request.urlopen", side_effect=fetch),
                    patch("subprocess.run") as run,
                    patch("subprocess.Popen") as popen,
                ):
                    state = provision(Path(temporary) / "isolated", accept_sdk_license=True)
                run.assert_not_called()
                popen.assert_not_called()
                self.assertEqual(set(requested), set(downloads))
                self.assertEqual(len(requested), 5)
                self.assertFalse(state["provenance"]["cli_bootstrap_executed"])
                self.assertFalse(state["provenance"]["separate_cli_helper_downloaded_or_executed"])
                self.assertEqual(state["provenance"]["sdk_installation"], "verified-archive-extraction-only")

    def test_preflight_reads_installed_metadata_without_executing_the_cli_stub(self):
        from controller.ci import preflight
        from controller.schema import load_json

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            state = self.state(directory)
            for path, package_id, revision in (
                ("platforms/android-37.2", "platforms;android-37.2", (1, 0, 0)),
                ("build-tools/37.0.0", "build-tools;37.0.0", (37, 0, 0)),
                ("cmdline-tools/23.0", "cmdline-tools;23.0", (23, 0, 0)),
            ):
                target = directory / "sdk" / path / "package.xml"
                target.parent.mkdir(parents=True)
                root = ET.Element("repository")
                package = ET.SubElement(root, "localPackage", {"path": package_id})
                version = ET.SubElement(package, "revision")
                for part, value in zip(("major", "minor", "micro"), revision, strict=True):
                    ET.SubElement(version, part).text = str(value)
                target.write_bytes(ET.tostring(root))
            with patch("controller.ci.execute") as execute:
                preflight(state, directory / "evidence")
            execute.assert_called_once()
            self.assertTrue(execute.call_args.args[0][1].endswith("check_environment.py"))
            self.assertFalse(load_json(directory / "evidence" / "sdk-inventory.json")["cli_bootstrap_executed"])
