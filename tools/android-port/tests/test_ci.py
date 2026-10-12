"""AndroidOnly: WP-504 Real source-disposition guards without widening scoped Gradle work."""

import contextlib
from dataclasses import replace
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fixtures import base_manifest
from controller import ci
from controller.errors import PortError
from oracle.workflow_scope import classify

SOURCE = "android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parity/TcpLifecycleParityTest.kt"
PIN = "db14559b39d32322b06477c6ae676112f583db50"
PORTED = (
    f"// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiTransportTests.swift@{PIN}\n"
    f"// PortedFrom: MeshCore/Sources/MeshCore/Transport/WiFiFrameCodec.swift@{PIN}\n"
    f"// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketParser.swift@{PIN}\n"
)
MIXED = PORTED + (
    "// AndroidOnly: WP-109 Public localhost transport/parser/dispatcher regressions "
    "with source two-second phase deadlines.\n"
)


class SourceDispositionTests(unittest.TestCase):
    def test_shared_candidate_executor_change_requires_broad_verification(self):
        self.assertTrue(all(classify(["tools/android-port/controller/ci.py"]).values()))

    @contextlib.contextmanager
    def candidate(self, headers, *, tests=84):
        with tempfile.TemporaryDirectory(prefix="meshcore-source-ci-") as temporary:
            root = Path(temporary)
            source = root / SOURCE
            source.parent.mkdir(parents=True)
            source.write_text(headers + "package fixture\n", encoding="utf-8")
            reports = root / "android/core/protocol/build/test-results/test"
            reports.mkdir(parents=True)
            cases = "".join(f'<testcase classname="Fixture" name="case{index}"/>' for index in range(tests))
            (reports / "TEST-Fixture.xml").write_text(
                f'<testsuite tests="{tests}" failures="0" errors="0" skipped="0">{cases}</testsuite>',
                encoding="utf-8",
            )
            manifest = replace(base_manifest(), repo=root)
            output = root / "output"

            def execute(command, environment, log, **kwargs):
                if log.name.startswith("gradle-"):
                    graph = Path(environment["ANDROID_CI_TASK_GRAPH"])
                    graph.parent.mkdir(parents=True, exist_ok=True)
                    graph.write_text(json.dumps({
                        "schema_version": 1,
                        "tasks": [{"path": ":core:protocol:test", "type": "fixture", "command": []}],
                    }), encoding="utf-8")

            with (
                patch.object(ci, "REPO", root),
                patch.object(ci, "load_manifest", return_value=manifest),
                patch.object(ci.platform, "python_version", return_value="3.12.4"),
                patch.object(ci, "candidate_environment", return_value={}),
                patch.object(ci, "verify_wrapper"),
                patch.object(ci, "execute", side_effect=execute) as commands,
                patch.object(ci, "collect_suites", side_effect=AssertionError("Unselected scaffold suites")),
                patch.object(ci, "check_module_tests", side_effect=AssertionError("Unselected Android module tests")),
                patch.object(ci, "collect_lint", side_effect=AssertionError("Unselected scaffold lint")),
                contextlib.redirect_stdout(io.StringIO()) as printed,
            ):
                yield commands, printed, output, {"host": "linux", "private_root": str(root / "private")}

    def protocol_selection(self):
        selection = classify([SOURCE])
        self.assertEqual([name for name, selected in selection.items() if selected], ["protocol"])
        return [name for name, selected in selection.items() if selected]

    def test_protocol_only_actual_mixed_declarations_fail_before_any_build_command(self):
        # Actual declarations from rejected 6bb88ab; the validator itself is never mocked.
        for stage in ("scaffold", "protocol"):
            with self.subTest(stage=stage), self.candidate(MIXED) as (commands, printed, output, state):
                with self.assertRaisesRegex(PortError, "Conflicting traceability dispositions"):
                    ci.run_stage(stage, state, output, scopes=self.protocol_selection())
                commands.assert_not_called()
                self.assertNotIn('"result": "success"', printed.getvalue())

    def test_corrected_multi_source_candidate_keeps_protocol_only_gradle_and_discovery(self):
        with self.candidate(PORTED) as (commands, printed, output, state):
            ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
            self.assertEqual(commands.call_count, 2)
            gradle = commands.call_args_list[1].args[0]
            self.assertEqual(gradle[3], ":core:protocol:test")
            self.assertNotIn("verifyScaffoldTests", gradle)
            self.assertNotIn(":app:assembleDebug", gradle)
            self.assertIn("--dependency-verification", gradle)
            summary, report = printed.getvalue().split("\n", 1)
            self.assertEqual(summary, "Verified Kotlin source dispositions and pinned provenance: 1 files")
            result = json.loads(report)
            self.assertEqual(result["scopes"], ["protocol"])
            self.assertEqual(result["protocol_suite"]["discovered"], 84)
            self.assertEqual(result["protocol_suite"]["passed"], 84)

    def test_pinned_provenance_is_enforced_for_protocol_only_scope(self):
        for headers in (PORTED.replace(PIN, "0" * 40), PORTED.replace("PacketParser.swift", "Unknown.swift")):
            with self.subTest(headers=headers), self.candidate(headers) as (commands, _, output, state):
                with self.assertRaisesRegex(PortError, "Unknown/stale source provenance"):
                    ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
                commands.assert_not_called()

    def test_missing_disposition_is_rejected_before_scoped_execution(self):
        with self.candidate("") as (commands, _, output, state):
            with self.assertRaisesRegex(PortError, "Missing traceability header"):
                ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
            commands.assert_not_called()

    def test_valid_disposition_does_not_allow_zero_protocol_assertions(self):
        with self.candidate(PORTED, tests=0) as (_, printed, output, state):
            with self.assertRaisesRegex(PortError, "Zero, failed, skipped or inconsistent JUnit suite"):
                ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
            self.assertNotIn('"result": "success"', printed.getvalue())

    def test_prepare_also_cannot_bypass_the_source_guard(self):
        with self.candidate(MIXED) as (commands, _, output, state):
            with self.assertRaisesRegex(PortError, "Conflicting traceability dispositions"):
                ci.run_stage("prepare", state, output)
            commands.assert_not_called()


if __name__ == "__main__":
    unittest.main()
