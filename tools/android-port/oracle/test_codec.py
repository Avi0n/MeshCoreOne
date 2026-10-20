"""AndroidOnly: WP-004 Reference-fragment/provenance and codec-evidence contract tests."""

import copy
import contextlib
import gzip
import io
import json
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

from oracle.codec_harness import (
    CODEC_TYPES, EXPECTED_CASES, MAC_ENVIRONMENT, codec_fragment,
    decode_reference_compression, execute, mac_environment, phase, run, stage, type_definition,
    validate_swift_report, verify_output,
)
from oracle.reference import FrozenReference, OracleError, REPO, SOURCE_SHA, json_bytes, sha256
from oracle.swift import Syntax, declarations


class CodecFragmentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.reference = FrozenReference()

    def test_staged_source_contains_all_real_dto_stored_fields(self):
        for path, names in CODEC_TYPES.items():
            text = self.reference.read_many([path])[path]
            syntax = Syntax(text)
            members = declarations(syntax)
            for name in names:
                original = type_definition(syntax, name)
                expected = [
                    member.name for member in members if member.parent is not None
                    and member.parent.keyword == original.keyword
                    and member.kind in ("let", "var") and member.body is None
                    and "static" not in {token.text for token in syntax.tokens[member.begin:member.keyword]}
                ]
                content, provenance = codec_fragment(syntax, name)
                self.assertEqual(provenance["stored_properties"], expected)
                self.assertEqual(provenance["fragment_sha256"], sha256(content.encode()))
                self.assertTrue(expected)

    def test_custom_codable_methods_are_copied_not_reimplemented(self):
        for path, name in (
            ("MC1Services/Sources/MC1Services/Models/Message.swift", "MessageDTO"),
            ("MC1Services/Sources/MC1Services/Models/Channel.swift", "ChannelDTO"),
            ("MC1Services/Sources/MC1Services/Models/RoomMessage.swift", "RoomMessageDTO"),
        ):
            text = self.reference.read_many([path])[path]
            syntax = Syntax(text)
            content, provenance = codec_fragment(syntax, name)
            original = type_definition(syntax, name)
            methods = [member for member in declarations(syntax) if member.parent is not None
                       and member.parent.keyword == original.keyword and (
                           (member.kind == "enum" and member.name == "CodingKeys")
                           or (member.kind == "init" and syntax.raw(*member.parameters).startswith("from decoder:"))
                           or (member.kind == "func" and member.name == "encode")
                       )]
            self.assertTrue(methods)
            for member in methods:
                self.assertIn(syntax.raw(member.begin, member.end), content)
            self.assertTrue(any(item["purpose"].startswith("non-codec") for item in provenance["omitted"]))

    def test_only_non_codable_platform_conformance_is_omitted(self):
        path = "MC1Services/Sources/MC1Services/Models/Contact.swift"
        text = self.reference.read_many([path])[path]
        content, provenance = codec_fragment(Syntax(text), "ContactDTO")
        self.assertIn("Codable", content)
        self.assertNotIn("RepeaterResolvable", content)
        self.assertEqual(len(provenance["adaptations"]), 1)
        self.assertNotIn("CLLocationCoordinate2D", content)
        self.assertNotIn("init(from contact:", content)

    def test_unknown_missing_or_empty_codec_type_fails(self):
        for text, name in (("struct Other { let id: Int }", "Missing"),
                           ("struct Empty { var computed: Int { 1 } }", "Empty")):
            with self.subTest(name=name), self.assertRaises(OracleError):
                codec_fragment(Syntax(text), name)

    def test_stage_copies_exact_envelope_dates_limits_and_license_notices(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / "stage"
            source_map = stage(self.reference, output)
            self.assertEqual(source_map["source_sha"], SOURCE_SHA)
            self.assertTrue((output / "GPL-3.0.txt").is_file())
            self.assertTrue((output / "MeshCore-MIT.txt").is_file())
            envelope = next(item for item in source_map["sources"] if item["path"].endswith("/AppBackupEnvelope.swift"))
            data = (output / envelope["staged_file"]).read_text(encoding="utf-8")
            original = self.reference.read_many([envelope["path"]])[envelope["path"]]
            self.assertTrue(data.endswith(original))
            self.assertIn(".secondsSince1970", data)
            self.assertIn("50 * 1_048_576", data)
            self.assertIn("512 * 1_048_576", data)
            self.assertEqual(envelope["staged_sha256"], sha256((output / envelope["staged_file"]).read_bytes()))

    def test_stage_has_actual_source_helpers_and_independent_crypto_functions(self):
        with tempfile.TemporaryDirectory() as root:
            source_map = stage(self.reference, Path(root) / "stage")
            crypto = next(item for item in source_map["sources"] if item["path"].endswith("/ChannelCryptoTests.swift"))
            self.assertEqual({item["name"] for item in crypto["fragments"]},
                             {"testSecret", "encryptAES128ECB", "computeMAC", "createEncryptedPayload"})
            paths = {item["path"] for item in source_map["sources"]}
            self.assertIn("MC1Services/Tests/MC1ServicesTests/Helpers/AppBackupEnvelope+Testing.swift", paths)
            self.assertIn("MeshCore/Sources/MeshCore/Models/ContactTypes.swift", paths)

    def test_staging_does_not_execute_any_candidate_or_swift_program(self):
        import subprocess
        actual_run = subprocess.run

        def read_only_git(command, **kwargs):
            self.assertEqual(command[0], "git", "Staging must not execute Swift or candidate Kotlin")
            return actual_run(command, **kwargs)

        with tempfile.TemporaryDirectory() as root, patch("subprocess.run", side_effect=read_only_git):
            source_map = stage(self.reference, Path(root) / "stage")
            self.assertTrue(source_map["sources"])

    def test_existing_stage_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(OracleError, "new isolated"):
                stage(self.reference, Path(root))


class CodecEvidenceTests(unittest.TestCase):
    def test_command_timeout_reports_phase_and_bounded_context_without_becoming_success(self):
        def timeout(command, **kwargs):
            self.assertEqual(kwargs["timeout"], 300)
            kwargs["stdout"].write("earlier" * 3000 + "\nlast compiler diagnostic")
            kwargs["stdout"].flush()
            raise subprocess.TimeoutExpired(command, 300)

        with tempfile.TemporaryDirectory() as root, \
                patch("oracle.codec_harness.subprocess.run", side_effect=timeout), \
                contextlib.redirect_stdout(io.StringIO()) as progress, \
                contextlib.redirect_stderr(io.StringIO()) as diagnostics:
            with self.assertRaises(subprocess.TimeoutExpired):
                execute(["fixture-swiftc"], Path(root) / "compile.log", {})
        self.assertIn("phase=compile state=start", progress.getvalue())
        self.assertIn("phase=compile state=failed elapsed_seconds=", progress.getvalue())
        self.assertNotIn("state=success", progress.getvalue())
        self.assertIn("timeout_seconds=300", diagnostics.getvalue())
        self.assertTrue(diagnostics.getvalue().rstrip().endswith("last compiler diagnostic"))
        self.assertLess(len(diagnostics.getvalue().encode()), 8200)

    def test_command_failure_and_success_keep_original_outcomes_with_phase_timing(self):
        for code in (0, 17):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as root, \
                    patch("oracle.codec_harness.subprocess.run",
                          return_value=subprocess.CompletedProcess(["fixture-swiftc"], code)), \
                    contextlib.redirect_stdout(io.StringIO()) as progress:
                if code:
                    with self.assertRaisesRegex(OracleError, r"failed \(17\)"):
                        execute(["fixture-swiftc"], Path(root) / "execution.log", {})
                else:
                    execute(["fixture-swiftc"], Path(root) / "execution.log", {})
                self.assertIn("phase=execution state=start", progress.getvalue())
                self.assertIn(f"state={'failed' if code else 'success'} elapsed_seconds=", progress.getvalue())

    def test_staging_exception_emits_failure_not_completion(self):
        with contextlib.redirect_stdout(io.StringIO()) as progress, self.assertRaises(OracleError):
            with phase("source-staging"):
                raise OracleError("fixture source failure")
        self.assertIn("phase=source-staging state=failed", progress.getvalue())
        self.assertNotIn("state=success", progress.getvalue())

    def test_real_committed_swift_outputs_match_actual_run_and_artifact_digests(self):
        fixture = REPO / "android" / "core" / "testing" / "fixtures" / "reference-codec"
        proof = json.loads((fixture / "codec-evidence.json").read_text(encoding="utf-8"))
        source_map = json.loads((fixture / "source-map.json").read_text(encoding="utf-8"))
        self.assertEqual(proof["identity"]["binding"]["head_sha"], "6e22d4768e80af943887fe4e9f48c12074f4f89f")
        self.assertEqual(proof["identity"]["run_id"], 37167976130)
        self.assertEqual(proof["identity"]["run_attempt"], 1)
        self.assertEqual(proof["source_map_sha256"], sha256(json_bytes(source_map)))
        reference = FrozenReference()
        for source in source_map["sources"]:
            self.assertEqual(source["blob_sha"], reference.inventory[source["path"]]["blob_sha"])
        for artifact in proof["artifacts"]:
            data = (fixture / artifact["path"]).read_bytes()
            if artifact["path"].endswith(".json"):
                data = data.replace(b"\r\n", b"\n")
            self.assertEqual(len(data), artifact["size"])
            self.assertEqual(sha256(data), artifact["sha256"])
        verified = verify_output(fixture, source_map)
        self.assertEqual(verified["swift"]["passed"], 28)
        self.assertEqual(verified["swift"]["assertions"], 66)
        self.assertEqual(verified["compression"]["observed_container"], "raw-deflate")
        self.assertTrue(verified["decoded_semantics_equal"])

    def test_actual_independent_swift_crypto_outputs_are_not_candidate_roundtrips(self):
        path = REPO / "android" / "core" / "testing" / "fixtures" / "reference-codec" / "channel-crypto-oracle.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(value["source_sha"], SOURCE_SHA)
        self.assertIn("no candidate Kotlin", value["origin"])
        self.assertEqual({item["id"] for item in value["vectors"]}, {"normal", "high-bit-utf8"})
        self.assertTrue(all(item["byte_count"] == len(bytes.fromhex(item["packet_hex"])) for item in value["vectors"]))

    def test_windows_or_linux_cannot_claim_actual_swift_execution(self):
        with patch("oracle.codec_harness.platform.system", return_value="Windows"):
            with self.assertRaisesRegex(OracleError, "isolated macOS"):
                run(Path("unused"))

    def test_mac_compiler_environment_has_no_agent_or_github_credentials(self):
        inherited = {key: "allowed" for key in MAC_ENVIRONMENT}
        inherited.update({"GH_TOKEN": "private", "GITHUB_TOKEN": "private", "COPILOT_TOKEN": "private",
                          "SSH_AUTH_SOCK": "private", "SOME_API_KEY": "private"})
        self.assertEqual(set(mac_environment(inherited)), MAC_ENVIRONMENT)
        self.assertTrue(all(value == "allowed" for value in mac_environment(inherited).values()))

    def test_rfc1950_zlib_and_raw_deflate_are_observed_explicitly(self):
        value = b'{"controlled":"reference"}'
        data, observation = decode_reference_compression(zlib.compress(value))
        self.assertEqual(data, value)
        self.assertEqual(observation["observed_container"], "rfc1950-zlib")
        self.assertEqual(observation["format_probes"]["raw-deflate"], "zlib-format-error")
        compressor = zlib.compressobj(wbits=-zlib.MAX_WBITS)
        raw = compressor.compress(value) + compressor.flush()
        data, observation = decode_reference_compression(raw)
        self.assertEqual(data, value)
        self.assertEqual(observation["observed_container"], "raw-deflate")

    def test_gzip_garbage_truncated_and_trailing_streams_are_not_success(self):
        valid = zlib.compress(b'{"reference":true}')
        for data in (gzip.compress(b'{"reference":true}'), b"garbage", valid[:-2], valid + b"extra"):
            with self.subTest(data=data[:8]), self.assertRaises(OracleError):
                decode_reference_compression(data)

    def test_expansion_limit_is_checked_during_inflate(self):
        compressed = zlib.compress(bytes(2048))
        with self.assertRaisesRegex(OracleError, "expanded-size"):
            decode_reference_compression(compressed, max_expanded=1024)
        decoded, _ = decode_reference_compression(zlib.compress(bytes(1024)), max_expanded=1024)
        self.assertEqual(len(decoded), 1024)

    def test_zero_invalid_expansion_bounds_fail(self):
        for bound in (0, -1, False):
            with self.subTest(bound=bound), self.assertRaises(OracleError):
                decode_reference_compression(zlib.compress(b"test"), max_expanded=bound)

    def test_actual_case_contract_has_nonzero_codec_and_crypto_assertions(self):
        self.assertEqual(len(EXPECTED_CASES), 28)
        self.assertIn("compressed-size-cap", EXPECTED_CASES)
        self.assertIn("expanded-size-cap", EXPECTED_CASES)
        self.assertIn("channel-crypto-high-bit-utf8", EXPECTED_CASES)
        self.assertIn("source-version-zero", EXPECTED_CASES)

    def test_swift_report_contract_requires_every_actual_case_and_assertion(self):
        unit_report = {
            "source_sha": SOURCE_SHA, "tests": [{"name": name, "assertions": 1} for name in sorted(EXPECTED_CASES)],
            "discovered": 28, "passed": 28, "failed": 0, "skipped": 0, "assertions": 28,
        }
        self.assertEqual(validate_swift_report(unit_report), unit_report)
        for mutation in ("missing", "duplicate", "zero", "skip", "failed", "count", "source", "bool"):
            report = copy.deepcopy(unit_report)
            if mutation == "missing":
                report["tests"].pop()
            elif mutation == "duplicate":
                report["tests"].append(copy.deepcopy(report["tests"][0]))
            elif mutation == "zero":
                report["tests"][0]["assertions"] = 0
            elif mutation == "source":
                report["source_sha"] = "f" * 40
            elif mutation == "bool":
                report["failed"] = False
            else:
                report[{"skip": "skipped", "failed": "failed", "count": "assertions"}[mutation]] += 1
            with self.subTest(mutation=mutation), self.assertRaises(OracleError):
                validate_swift_report(report)
