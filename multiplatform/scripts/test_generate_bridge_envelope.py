"""Unit tests for JNI envelope session-command extraction.

Run: python3 -m unittest discover -s multiplatform/scripts
"""

import importlib.util
import re
import tempfile
import unittest
from unittest import mock
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "generate_bridge_envelope", Path(__file__).with_name("generate-bridge-envelope.py")
)
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)

SNIPPET = """
public extension MeshCoreSession {
  func sendMessage(to destination: Data, text: String) async throws -> MessageSentInfo {
    fatalError()
  }

  func getContactByName(_ name: String) -> MeshContact? {
    nil
  }

  func performStatusRequest(from publicKey: Data) async throws -> StatusResponse {
    fatalError()
  }

  func setTuning(rxDelay: UInt32, af: UInt32) async throws {
  }

  func start() async throws {
  }
}
"""


class AsyncThrowsExtractionTests(unittest.TestCase):
    def test_kotlin_bridge_calls_include_host_cancellation(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = {name: Path(directory) / f"{name}.kt"
                     for name in ("SESSION_KT", "SESSION_COMMANDS_KT", "SESSION_HOST_KT")}
            paths["SESSION_KT"].write_text("class MeshCoreSession")
            paths["SESSION_COMMANDS_KT"].write_text("host.bridge.sendChannelMessageWithScope(id)")
            paths["SESSION_HOST_KT"].write_text("bridge.cancelRequest(requestID)")
            with mock.patch.multiple(gen, **paths):
                self.assertEqual(gen._kotlin_bridge_calls(), {"sendChannelMessageWithScope", "cancelRequest"})
                paths["SESSION_HOST_KT"].write_text("")
                self.assertEqual(gen._kotlin_bridge_calls(), {"sendChannelMessageWithScope"})

    def test_generated_decode_uses_ktlint_when_layout(self):
        schema = {
            "events": [{"kind": "sampleEvent", "form": "object", "type": "MeshEvent.Sample"}],
            "results": [{"kind": "sampleResult", "form": "object", "type": "CommandResult.Sample"}],
        }
        output = gen.render_decode(schema)
        self.assertIn(
            "internal fun decodeGeneratedMeshEvent(kind: String, payload: JsonObject): MeshEvent = when (kind) {",
            output,
        )
        self.assertIn('    "sampleEvent" -> MeshEvent.Sample', output)

    def test_large_decode_tables_split_under_the_complexity_cap(self):
        event_count = 53
        result_count = 29
        events = [
            {"kind": f"event{index:02d}", "form": "object", "type": "MeshEvent.Sample"}
            for index in range(event_count)
        ]
        results = [
            {"kind": f"result{index:02d}", "form": "object", "type": "CommandResult.Sample"}
            for index in range(result_count)
        ]
        output = gen.render_decode({"events": events, "results": results})
        whens = re.findall(r"when \(kind\) \{.*?\n\}", output, re.S)
        self.assertGreaterEqual(len(whens), 2)
        for body in whens:
            arms = len(re.findall(r'^\s+"[^"]+"\s*->', body, re.M))
            elses = len(re.findall(r"^\s+else ->", body, re.M))
            self.assertLessEqual(arms, gen.MAX_DECODE_ARMS)
            self.assertLessEqual(1 + arms + elses, 15)
        self.assertEqual(output.count("\ninternal fun "), 2)
        self.assertIn('throw BridgeDecodingException("unknown MeshEvent kind: $kind")', output)
        self.assertIn('throw BridgeDecodingException("unknown CommandResult kind: $kind")', output)
        self.assertIn('"event00" -> MeshEvent.Sample', output)
        self.assertIn('"event52" -> MeshEvent.Sample', output)
        self.assertIn('"result28" -> CommandResult.Sample', output)
        with tempfile.NamedTemporaryFile("w", suffix=".kt", delete=False) as handle:
            handle.write(output)
            path = Path(handle.name)
        try:
            self.assertEqual(
                gen._kinds_from_kotlin_when(path, "decodeGeneratedMeshEvent"),
                {entry["kind"] for entry in events},
            )
            self.assertEqual(
                gen._kinds_from_kotlin_when(path, "decodeGeneratedCommandResult"),
                {entry["kind"] for entry in results},
            )
        finally:
            path.unlink()

    def test_extracts_async_throws_and_skips_sync_helpers(self):
        names = gen.async_throws_session_funcs(SNIPPET)
        self.assertEqual(names, {"sendMessage", "performStatusRequest", "setTuning", "start"})

    def test_radio_commands_drop_internals_and_map_start(self):
        names = gen.radio_session_commands(SNIPPET)
        self.assertEqual(names, {"sendMessage", "setTuning", "start"})
