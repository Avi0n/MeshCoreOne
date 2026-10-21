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
    def test_overlong_failure_log_lines_report_incomplete_and_keep_later_identities(self):
        identity = "com.meshcoreone.android.FixtureTest > laterCase[37] FAILED"
        content = "x" * 140000 + "\n" + identity + "\nBUILD FAILED\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "overlong.log"
            log.write_text(content, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                ci.print_failed_command_log(log)
        output = diagnostics.getvalue()
        self.assertEqual(output.count("identity extraction is incomplete"), 1)
        self.assertIn(identity, output)
        self.assertTrue(output.rstrip().endswith("BUILD FAILED"))

    def test_failed_command_retains_every_gradle_identity_before_the_bounded_tail(self):
        identities = [
            f"com.meshcoreone.android.FixtureTest > originalCase{index}[{sdk}] FAILED"
            for index, sdk in enumerate((31, 37, 31, 37))
        ]
        content = "\n".join(
            f"bounded preceding context\n{identity}\n    java.lang.AssertionError: original assertion\n"
            for identity in identities
        ) + "\n800 tests completed, 4 failed\n" + ("later diagnostic output\n" * 2000) + "BUILD FAILED\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "gradle-failure.log"
            def failed(command, **kwargs):
                kwargs["stdout"].write(content)
                return ci.subprocess.CompletedProcess(command, 17)
            with patch.object(ci.subprocess, "run", side_effect=failed), \
                    patch.object(Path, "read_text", side_effect=AssertionError("Whole-log reads are forbidden")), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                with self.assertRaisesRegex(PortError, "Declared command failed with exit 17"):
                    ci.execute(["fixture-gradle"], {}, log)
            output = diagnostics.getvalue()
            for identity in identities:
                self.assertNotIn(identity, content[-12000:])
                self.assertIn(identity, output)
            self.assertIn("800 tests completed, 4 failed", output)
            self.assertIn("java.lang.AssertionError: original assertion", output)
            self.assertTrue(output.rstrip().endswith("BUILD FAILED"))

    def test_successful_command_does_not_emit_failure_diagnostics(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(ci.subprocess, "run", return_value=ci.subprocess.CompletedProcess(["fixture"], 0)), \
                patch.object(ci, "print_failed_command_log") as diagnostics, \
                contextlib.redirect_stdout(io.StringIO()):
            ci.execute(["fixture"], {}, Path(temporary) / "success.log")
            diagnostics.assert_not_called()

    def test_timeout_closes_log_and_emits_failure_identities_reports_and_bounded_tail(self):
        identity = "com.meshcoreone.android.FixtureTest > timedOutCase[31] FAILED"
        content = identity + "\n    original failure\n" + ("later progress\n" * 2000) + "last Gradle progress\n"
        failure = ci.subprocess.TimeoutExpired(["fixture-gradle"], 1800)
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "gradle-timeout.log"
            report = Path(temporary) / "junit"
            report.mkdir()
            (report / "TEST-Fixture.xml").write_text(
                '<testsuite tests="1" failures="1" errors="0" skipped="0">'
                '<testcase classname="Fixture" name="timedOutCase"><failure message="original"/>'
                '</testcase></testsuite>', encoding="utf-8",
            )
            handles = []
            def timeout(command, **kwargs):
                self.assertEqual(kwargs["timeout"], 1800)
                handles.append(kwargs["stdout"])
                kwargs["stdout"].write(content)
                raise failure
            def reports():
                self.assertTrue(handles[0].closed)
                return [report]
            with patch.object(ci.subprocess, "run", side_effect=timeout), \
                    patch.object(Path, "read_text", side_effect=AssertionError("Whole-log reads forbidden")), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                    ci.execute(["fixture-gradle"], {}, log, failure_reports=reports)
            self.assertIs(observed.exception, failure)
            self.assertTrue(handles[0].closed)
            output = diagnostics.getvalue()
            self.assertNotIn(identity, content[-12000:])
            self.assertIn(identity, output)
            self.assertIn("failed_command_report_totals", output)
            self.assertIn("timedOutCase", output)
            self.assertLess(output.index(identity), output.index("last Gradle progress"))
            self.assertTrue(output.rstrip().endswith("last Gradle progress"))
            self.assertLess(len(output), 16000)

    def test_timeout_is_not_replaced_by_failure_diagnostic_errors(self):
        for target, error in (
            ("reports", PortError("partial report inventory")),
            ("log", OSError("log unavailable")),
            ("log", RuntimeError("unexpected diagnostic failure")),
        ):
            with self.subTest(target=target, error=type(error).__name__), \
                    tempfile.TemporaryDirectory() as temporary:
                failure = ci.subprocess.TimeoutExpired(["fixture"], 1800)
                def timeout(command, **kwargs):
                    kwargs["stdout"].write("retained timeout progress\n")
                    raise failure
                with patch.object(ci.subprocess, "run", side_effect=timeout), \
                        patch.object(ci, "print_failed_command_log",
                                     side_effect=error if target == "log" else None), \
                        contextlib.redirect_stdout(io.StringIO()), \
                        contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                    def reports():
                        raise error
                    with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                        ci.execute(["fixture"], {}, Path(temporary) / "timeout.log",
                                   failure_reports=reports if target == "reports" else None)
                self.assertIs(observed.exception, failure)
                self.assertIn(str(error), diagnostics.getvalue())

    def test_timeout_is_not_replaced_by_broken_stderr_during_diagnostics(self):
        class BrokenDiagnostics(io.StringIO):
            def write(self, value):
                raise OSError("diagnostic output unavailable")

        failure = ci.subprocess.TimeoutExpired(["fixture"], 1800)
        handles = []
        def timeout(command, **kwargs):
            handles.append(kwargs["stdout"])
            kwargs["stdout"].write("actual timeout progress\n")
            raise failure
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(ci.subprocess, "run", side_effect=timeout), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(BrokenDiagnostics()):
            log = Path(temporary) / "timeout.log"
            with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                ci.execute(["fixture"], {}, log)
            self.assertIs(observed.exception, failure)
            self.assertTrue(handles[0].closed)
            self.assertEqual(log.read_text(), "actual timeout progress\n")

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
