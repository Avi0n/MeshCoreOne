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
    def test_timeout_retains_fixed_observations_before_tail_without_inventing_method_completion(self):
        markers = ["WP501_PATH_RECENCY|TASK|:app:compileDebugUnitTestKotlin|END"]
        for (tag, name), methods in ci.WP501_OBSERVATION_METHODS.items():
            markers.extend(f"{tag}|TEST|{name}|{method}|START" for method in methods)
        content = "\n".join(markers) + "\n" + ("later unrelated output\n" * 2000) + "last actual progress\n"
        failure = ci.subprocess.TimeoutExpired(["fixture-gradle"], 1800)
        with tempfile.TemporaryDirectory() as temporary:
            def timeout(command, **kwargs):
                self.assertEqual(kwargs["timeout"], 1800)
                kwargs["stdout"].write(content)
                raise failure
            with patch.object(ci.subprocess, "run", side_effect=timeout), \
                    patch.object(Path, "read_text", side_effect=AssertionError("Whole-log reads forbidden")), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                    ci.execute(["fixture-gradle"], {}, Path(temporary) / "timeout.log")
            self.assertIs(observed.exception, failure)
            output = diagnostics.getvalue()
            for marker in markers:
                self.assertNotIn(marker, content[-12000:])
                self.assertEqual(output.count(marker), 1)
            self.assertNotIn("|END|", output)
            self.assertIn("truncated=False|not-test-evidence", output)
            self.assertTrue(output.rstrip().endswith("last actual progress"))

    def test_observation_replay_rejects_nonwhitelisted_names_phases_payloads_and_overlong_lines(self):
        (tag, name), methods = next(iter(ci.WP501_OBSERVATION_METHODS.items()))
        start = f"{tag}|TEST|{name}|{methods[0]}|START"
        invalid = (
            start.replace(name, "com.meshcoreone.android.UnrelatedTest"),
            start.replace(methods[0], "unknownMethod"),
            start.replace(methods[0], methods[0] + "[37]"),
            start + "|PRIVATE_PAYLOAD",
            f"{tag}|PHASE|{name}|{methods[0]}|unknown-phase|END",
            start.replace("|START", "|END|MADE_UP_RESULT"),
            start + "PRIVATE_PAYLOAD" + "x" * 70000,
        )
        content = "\n".join(invalid) + "\n" + ("later unrelated output\n" * 2000) + "last actual progress\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "invalid-observations.log"
            log.write_text(content, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                ci.print_failed_command_log(log)
        output = diagnostics.getvalue()
        self.assertNotIn("WP501_", output)
        self.assertNotIn("PRIVATE_PAYLOAD", output)
        self.assertEqual(output.count("identity extraction is incomplete"), 1)
        self.assertTrue(output.rstrip().endswith("last actual progress"))

    def test_observation_replay_is_bounded_and_reports_truncation_in_original_order(self):
        markers = sorted(ci.WP501_OBSERVATION_MARKERS)[:ci.WP501_OBSERVATION_LIMIT + 1]
        self.assertEqual(len(markers), ci.WP501_OBSERVATION_LIMIT + 1)
        content = "\n".join(markers) + "\n" + ("later unrelated output\n" * 2000) + "last actual progress\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "bounded-observations.log"
            log.write_text(content, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                ci.print_failed_command_log(log)
        output = diagnostics.getvalue()
        self.assertNotIn(markers[0], output)
        observed = output.splitlines()
        self.assertEqual(observed[1:ci.WP501_OBSERVATION_LIMIT + 1], markers[1:])
        self.assertIn("retained=128|seen=129|truncated=True|not-test-evidence", output)
        self.assertTrue(output.rstrip().endswith("last actual progress"))

    def test_observation_whitelist_matches_app_rollback_names_and_existing_phase_protocol(self):
        source = (ci.REPO / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        rollback = source.split("val rollbackTestNames = setOf(", 1)[1].split(").flatMap", 1)[0]
        names = tuple(line.strip().strip('",') for line in rollback.splitlines() if line.strip())
        key = ("WP501_ADVERT_ROLLBACK", "com.meshcoreone.android.parity.AdvertisementRollbackOriginalPredicateTest")
        self.assertEqual(names, ci.WP501_OBSERVATION_METHODS[key])
        phases = source.split("val pathRecencyPhases = setOf(", 1)[1].split(")", 1)[0]
        self.assertEqual(tuple(ci.re.findall(r'"([^"]+)"', phases)), ci.WP501_PATH_RECENCY_PHASES)
        for method in names:
            for suffix in ("", "()", "[31]", "[31]()"):
                for ending in ("START", "END|SUCCESS", "END|FAILURE", "END|SKIPPED"):
                    self.assertIn(f"{key[0]}|TEST|{key[1]}|{method}{suffix}|{ending}", ci.WP501_OBSERVATION_MARKERS)

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

    def test_selected_gradle_batch_budget_reaches_executor_and_preserves_timeout_diagnostics(self):
        real_execute = ci.execute
        for outcome in ("success", "timeout"):
            with self.subTest(outcome=outcome), self.candidate(PORTED) as (commands, printed, output, state):
                graph_fixture = commands.side_effect
                failure = ci.subprocess.TimeoutExpired(["fixture-gradle"], 3600)

                def execute(command, environment, log, **kwargs):
                    graph_fixture(command, environment, log, **kwargs)
                    return real_execute(command, environment, log, **kwargs)

                def run(command, **kwargs):
                    if command[0].endswith("gradlew"):
                        self.assertEqual(kwargs["timeout"], 3600)
                        kwargs["stdout"].write("actual selected batch progress\n")
                        if outcome == "timeout":
                            raise failure
                    else:
                        self.assertEqual(kwargs["timeout"], 60)
                    return ci.subprocess.CompletedProcess(command, 0)

                commands.side_effect = execute
                with patch.object(ci.subprocess, "run", side_effect=run), \
                        contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                    if outcome == "timeout":
                        with self.assertRaises(ci.subprocess.TimeoutExpired) as raised:
                            ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
                        self.assertIs(raised.exception, failure)
                        self.assertIn("actual selected batch progress", diagnostics.getvalue())
                        self.assertIn('"failed_command_report_totals"', diagnostics.getvalue())
                        self.assertIn('"discovered": 84', diagnostics.getvalue())
                        self.assertNotIn('"result": "success"', printed.getvalue())
                    else:
                        ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
                        self.assertIn('"passed": 84', printed.getvalue())
                self.assertEqual((output / "gradle-scaffold.log").read_text(), "actual selected batch progress\n")

    def test_extended_batch_budget_keeps_task_graph_guard_and_benchmark_budget(self):
        for stage in ("scaffold", "benchmark"):
            with self.subTest(stage=stage), self.candidate(PORTED) as (commands, _, output, state):
                with patch.object(ci, "validate_candidate_task_graph", side_effect=PortError("invalid actual graph")) as graph:
                    with self.assertRaisesRegex(PortError, "invalid actual graph"):
                        ci.run_stage(stage, state, output, scopes=self.protocol_selection())
                self.assertEqual(commands.call_args.kwargs["timeout"], 3600 if stage == "scaffold" else 1800)
                self.assertIn("--init-script", commands.call_args.args[0])
                graph.assert_called_once()

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
