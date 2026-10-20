"""AndroidOnly: WP-004 Compile real pinned Swift codec fragments on isolated macOS."""

import argparse
from contextlib import contextmanager
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError
from oracle.identity import evidence_identity
from oracle.reference import (
    FrozenReference, OracleError, REPO, SOURCE_SHA, exact_fields, json_bytes,
    repo_path, sha256, write_or_check,
)
from oracle.swift import Syntax, canonical, declarations

SERVICE_ROOT = "MC1Services/Sources/MC1Services/"
HELPER_ROOT = "MC1Services/Tests/MC1ServicesTests/Helpers/"
WHOLE_FILES = (
    SERVICE_ROOT + "Services/AppBackupEnvelope.swift",
    SERVICE_ROOT + "Errors/AppBackupError.swift",
    SERVICE_ROOT + "Extensions/Data+Extensions.swift",
    SERVICE_ROOT + "Models/ConnectionMethod.swift",
    SERVICE_ROOT + "Models/RegionSelection.swift",
    SERVICE_ROOT + "Models/ChannelFloodScope.swift",
    SERVICE_ROOT + "Models/NotificationLevel.swift",
    "MeshCore/Sources/MeshCore/Protocol/ChannelCrypto.swift",
)
CODEC_TYPES = {
    SERVICE_ROOT + "Models/Device.swift": ("DeviceDTO",),
    SERVICE_ROOT + "Models/Contact.swift": ("ContactDTO",),
    SERVICE_ROOT + "Models/Channel.swift": ("ChannelDTO",),
    SERVICE_ROOT + "Models/Message.swift": ("MessageDTO",),
    SERVICE_ROOT + "Models/MessageRepeat.swift": ("MessageRepeatDTO",),
    SERVICE_ROOT + "Models/Reaction.swift": ("ReactionDTO",),
    SERVICE_ROOT + "Models/RoomMessage.swift": ("RoomMessageDTO",),
    SERVICE_ROOT + "Models/RemoteNodeSession.swift": ("RemoteNodeSessionDTO",),
    SERVICE_ROOT + "Models/SavedTracePath.swift": ("SavedTracePathDTO", "TracePathRunDTO"),
    SERVICE_ROOT + "Models/BlockedChannelSender.swift": ("BlockedChannelSenderDTO",),
    SERVICE_ROOT + "Models/NodeStatusSnapshot.swift": ("NodeStatusSnapshotDTO",),
    SERVICE_ROOT + "Models/DiscoveredNode.swift": ("DiscoveredNodeDTO",),
    SERVICE_ROOT + "Services/BackupUserDefaults.swift": ("BackupUserDefaults",),
}
EXACT_TYPES = {
    SERVICE_ROOT + "Models/Message.swift": ("MessageStatus", "MessageDirection"),
    SERVICE_ROOT + "Models/ProtocolTypes.swift": ("TextType", "RemoteNodeRole", "RoomPermissionLevel"),
    SERVICE_ROOT + "Models/NodeStatusSnapshot.swift": ("NeighborSnapshotEntry", "TelemetrySnapshotEntry"),
    "MeshCore/Sources/MeshCore/Models/ContactTypes.swift": ("ContactType",),
    "MeshCore/Sources/MeshCore/Protocol/RxLogTypes.swift": ("RouteType",),
}
HELPERS = (
    "AppBackupEnvelope+Testing.swift", "DeviceDTO+Testing.swift", "ContactDTO+Testing.swift",
    "ChannelDTO+Testing.swift", "MessageDTO+Testing.swift", "MessageRepeatDTO+Testing.swift",
    "RoomMessageDTO+Testing.swift", "SavedTracePathDTO+Testing.swift", "NodeStatusSnapshotDTO+Testing.swift",
)
CRYPTO_TEST_PATH = "MeshCore/Tests/MeshCoreTests/ChannelCryptoTests.swift"
SOURCE_TEST_PATH = "MC1Services/Tests/MC1ServicesTests/AppBackupEnvelopeTests.swift"
EXPECTED_CASES = {
    "version-and-unix-date", "all-arrays-and-manifest", "binary-and-uuid",
    "raw-and-associated-enums", "fractional-date-precision", "compressed-reference-decode",
    "legacy-discovered-defaults", "legacy-message-defaults", "legacy-channel-specific",
    "legacy-channel-inherit", "legacy-room-default", "invalid-compressed-input",
    "truncated-compression", "compressed-size-cap", "expanded-size-cap",
    "future-version-rejected", "manifest-mismatch", "required-field-missing",
    "invalid-base64", "invalid-uuid", "unknown-raw-enum", "optional-null",
    "source-version-zero", "channel-crypto-normal", "channel-crypto-high-bit-utf8",
    "channel-crypto-corrupted-mac", "channel-crypto-wrong-key", "channel-crypto-truncated",
}
MAC_ENVIRONMENT = {"PATH", "HOME", "TMPDIR", "DEVELOPER_DIR", "SDKROOT", "LANG", "LC_ALL"}


def type_definition(syntax, name):
    found = [member for member in declarations(syntax)
             if member.kind in ("struct", "enum", "class") and member.name == name and not member.scope]
    if len(found) != 1:
        raise OracleError(f"Missing/ambiguous production codec type: {name}")
    return found[0]


def fragment_record(syntax, member, purpose):
    text = syntax.raw(member.begin, member.end)
    return {"name": member.name, "kind": member.kind, "line": syntax.tokens[member.keyword].line,
            "end_line": syntax.tokens[member.end - 1].line, "sha256": sha256(text.encode("utf-8")),
            "purpose": purpose}


def codec_fragment(syntax, name):
    declaration = type_definition(syntax, name)
    children = [member for member in declarations(syntax) if member.parent is not None
                and member.parent.keyword == declaration.keyword]
    selected, omitted, fields = [], [], []
    for member in children:
        modifiers = {token.text for token in syntax.tokens[member.begin:member.keyword]}
        stored = member.kind in ("var", "let") and member.body is None and "static" not in modifiers
        decoder = member.kind == "init" and canonical(syntax.tokens[slice(*member.parameters)]).startswith("from decoder :")
        value_init = member.kind == "init" and not canonical(syntax.tokens[slice(*member.parameters)]).startswith("from ")
        encoder = member.kind == "func" and member.name == "encode"
        keys = member.kind == "enum" and member.name == "CodingKeys"
        if stored or decoder or value_init or encoder or keys:
            selected.append(member)
            if stored:
                fields.append(member.name)
        else:
            omitted.append(fragment_record(syntax, member, "non-codec behavior/SwiftData model conversion"))
    if not fields or len(fields) != len(set(fields)):
        raise OracleError(f"Missing/duplicate production stored properties: {name}")
    opening = declaration.body[0] - 1
    header = syntax.raw(declaration.begin, opening + 1)
    adaptations = []
    if ", RepeaterResolvable" in header:
        header = header.replace(", RepeaterResolvable", "")
        adaptations.append("Omit non-Codable RepeaterResolvable conformance and its computed UI/path members.")
    content = header + "\n" + "\n\n".join(syntax.raw(member.begin, member.end) for member in selected) + "\n}\n"
    return content, {
        "type": name, "stored_properties": fields,
        "retained": [fragment_record(syntax, member, "exact production stored property/Codable/value initializer") for member in selected],
        "omitted": omitted, "adaptations": adaptations,
        "fragment_sha256": sha256(content.encode("utf-8")),
    }


def stage(reference: FrozenReference, output: Path):
    if output.exists():
        raise OracleError("Oracle staging requires a new isolated directory")
    paths = set(WHOLE_FILES) | set(CODEC_TYPES) | set(EXACT_TYPES)
    paths |= {HELPER_ROOT + name for name in HELPERS}
    paths |= {CRYPTO_TEST_PATH, SOURCE_TEST_PATH, "LICENSE", "MeshCore/LICENSE"}
    sources = reference.read_many(paths)
    output.mkdir(parents=True)
    records = []
    for ordinal, path in enumerate(sorted(paths)):
        if path in ("LICENSE", "MeshCore/LICENSE", SOURCE_TEST_PATH):
            continue
        text, fragments = sources[path], []
        if path in WHOLE_FILES:
            content = text
            fragments = [{"purpose": "unchanged full production source", "sha256": sha256(text.encode("utf-8"))}]
        else:
            syntax = Syntax(text)
            pieces = []
            for name in CODEC_TYPES.get(path, ()):
                content, selection = codec_fragment(syntax, name)
                pieces.append(content)
                fragments.append(selection)
            for name in EXACT_TYPES.get(path, ()):
                member = type_definition(syntax, name)
                pieces.append(syntax.raw(member.begin, member.end))
                fragments.append(fragment_record(syntax, member, "unchanged production enum/value type"))
            if path == SERVICE_ROOT + "Models/RoomMessage.swift":
                members = [member for member in declarations(syntax) if member.kind == "func" and member.name == "generateDeduplicationKey"]
                if len(members) != 1:
                    raise OracleError("Missing production room-message key function")
                pieces.append("enum RoomMessage {\n" + syntax.raw(members[0].begin, members[0].end) + "\n}")
                fragments.append(fragment_record(syntax, members[0], "exact pure function in a namespace, not a SwiftData model"))
            if path.startswith(HELPER_ROOT):
                extensions = [member for member in declarations(syntax) if member.kind == "extension" and not member.scope]
                if not extensions:
                    raise OracleError("Missing pinned envelope/DTO test helper")
                pieces.extend(syntax.raw(member.begin, member.end) for member in extensions)
                fragments.extend(fragment_record(syntax, member, "unchanged helper; only module imports omitted") for member in extensions)
            if path == CRYPTO_TEST_PATH:
                members = [member for member in declarations(syntax) if member.scope == ("ChannelCryptoTests",)
                           and member.name in ("testSecret", "encryptAES128ECB", "computeMAC", "createEncryptedPayload")]
                if {member.name for member in members} != {"testSecret", "encryptAES128ECB", "computeMAC", "createEncryptedPayload"}:
                    raise OracleError("Missing pinned independent channel-encryption helpers")
                pieces.append("struct SourceChannelOracle {\n" + "\n\n".join(syntax.raw(member.begin, member.end) for member in members) + "\n}")
                pieces.append("""
extension SourceChannelOracle {
  func packet(timestamp: UInt32, txtType: UInt8, message: String) throws -> Data {
    guard let packet = createEncryptedPayload(timestamp: timestamp, txtType: txtType, message: message, secret: testSecret) else {
      throw OracleFailure(description: "Pinned Swift encryption helper failed")
    }
    return packet
  }
  var secret: Data { testSecret }
}
""")
                fragments.extend(fragment_record(syntax, member, "unchanged Swift-test encryption oracle, independent of Kotlin") for member in members)
            if not pieces:
                raise OracleError(f"No declared source-fragment selection for {path}")
            content = "import Foundation\nimport CryptoKit\nimport CommonCrypto\n\n" + "\n\n".join(pieces) + "\n"
        name = f"reference-{ordinal:02d}.swift"
        prefix = f"// Generated by tools/android-port/oracle/codec_harness.py from {path}@{SOURCE_SHA}\n"
        raw = (prefix + content).encode("utf-8")
        write_or_check(output / name, raw, check=False)
        records.append({**reference.provenance(path, text), "staged_file": name,
                        "staged_sha256": sha256(raw), "fragments": fragments})
    for path, filename in (("LICENSE", "GPL-3.0.txt"), ("MeshCore/LICENSE", "MeshCore-MIT.txt")):
        write_or_check(output / filename, sources[path].encode("utf-8"), check=False)
    harness = Path(__file__).with_name("CodecOracle.swift")
    write_or_check(output / "CodecOracle.swift", harness.read_bytes(), check=False)
    source_map = {
        "schema_version": 1, "source_sha": SOURCE_SHA, "manifest_sha256": reference.manifest_sha256,
        "generator": "tools/android-port/oracle/codec_harness.py",
        "harness_sha256": sha256(harness.read_bytes()), "sources": records,
        "source_test_specification": reference.provenance(SOURCE_TEST_PATH, sources[SOURCE_TEST_PATH]),
        "scope": "exact production codecs and test helpers; no SwiftData database, radio graph or Android backup implementation",
    }
    write_or_check(output / "source-map.json", json_bytes(source_map), check=False)
    return source_map


def mac_environment(inherited=None):
    inherited = os.environ if inherited is None else inherited
    return {key: value for key, value in inherited.items() if key in MAC_ENVIRONMENT}


@contextmanager
def phase(name):
    started, completed = time.monotonic(), False
    print(f"Swift oracle phase={name} state=start", flush=True)
    try:
        yield
        completed = True
    finally:
        state = "success" if completed else "failed"
        print(f"Swift oracle phase={name} state={state} elapsed_seconds={time.monotonic() - started:.3f}",
              flush=True)


def diagnostic_tail(output: Path):
    with output.open("rb") as stream:
        stream.seek(max(0, output.stat().st_size - 8000))
        return stream.read(8000).decode("utf-8", errors="replace")


def execute(command, output: Path, environment):
    with phase(output.stem):
        try:
            with output.open("w", encoding="utf-8") as stream:
                result = subprocess.run(command, env=environment, stdout=stream, stderr=subprocess.STDOUT,
                                        check=False, timeout=300)
        except subprocess.TimeoutExpired:
            print(f"Swift oracle phase={output.stem} timeout_seconds=300\n{diagnostic_tail(output)}",
                  file=sys.stderr, flush=True)
            raise
        if result.returncode:
            raise OracleError(f"Actual Swift command failed ({result.returncode}): {diagnostic_tail(output)}")


def decode_reference_compression(data: bytes, *, max_expanded=536_870_912):
    if len(data) > 52_428_800 or type(max_expanded) is not int or max_expanded < 1:
        raise OracleError("Invalid/excessive reference compressed input or expansion bound")
    observations, decoded = {}, {}
    for label, bits in (("rfc1950-zlib", zlib.MAX_WBITS), ("raw-deflate", -zlib.MAX_WBITS)):
        try:
            inflater = zlib.decompressobj(bits)
            value = inflater.decompress(data, max_expanded + 1)
            if len(value) > max_expanded or inflater.unconsumed_tail:
                raise OracleError("Reference fixture exceeds the expanded-size bound")
            if not inflater.eof or inflater.unused_data:
                observations[label] = "incomplete-or-trailing-stream"
            else:
                observations[label] = "decoded"
                decoded[label] = value
        except zlib.error:
            observations[label] = "zlib-format-error"
    if len(decoded) != 1:
        raise OracleError(f"Unknown/ambiguous actual source compression container: {observations}")
    label, value = next(iter(decoded.items()))
    return value, {"observed_container": label, "format_probes": observations}


def validate_swift_report(result):
    exact_fields(result, {"source_sha", "tests", "discovered", "passed", "failed", "skipped", "assertions"}, "Swift assertion report")
    if result["source_sha"] != SOURCE_SHA or not isinstance(result["tests"], list):
        raise OracleError("Stale/malformed actual Swift report")
    cases = {}
    for test in result["tests"]:
        exact_fields(test, {"name", "assertions"}, "Swift executed case")
        if test["name"] in cases or type(test["assertions"]) is not int or test["assertions"] < 1:
            raise OracleError("Duplicate/zero-assertion Swift case")
        cases[test["name"]] = test
    if set(cases) != EXPECTED_CASES:
        raise OracleError("Missing/unknown actual Swift codec assertion cases")
    if (any(type(result[key]) is not int for key in ("discovered", "passed", "failed", "skipped", "assertions"))
            or result["discovered"] != len(cases) or result["passed"] != len(cases)
            or result["failed"] != 0 or result["skipped"] != 0
            or result["assertions"] != sum(case["assertions"] for case in cases.values())):
        raise OracleError("Swift runner count/outcome/assertion mismatch")
    return result


def verify_output(output: Path, source_map: dict):
    result = validate_swift_report(json.loads((output / "test-results.json").read_text(encoding="utf-8")))
    encoded = (output / "reference-envelope.json").read_bytes()
    compressed = (output / "reference-envelope.meshcoreone").read_bytes()
    decoded, compression = decode_reference_compression(compressed)
    envelope = json.loads(encoded)
    if json.loads(decoded) != envelope or envelope["version"] != 1 or envelope["exportDate"] != 1_700_000_500.9876542:
        raise OracleError("Actual Swift export/compression/Unix date semantics mismatch")
    arrays = ("devices", "contacts", "channels", "messages", "messageRepeats", "reactions",
              "roomMessages", "remoteNodeSessions", "savedTracePaths", "blockedChannelSenders",
              "nodeStatusSnapshots", "discoveredNodes")
    if any(len(envelope[name]) != 1 for name in arrays):
        raise OracleError("Actual Swift fixture lost a model family")
    if envelope["messages"][0]["text"] != "Hi\u4f60\U0001f600\u05e9\u05dc\u05d5\u05dd":
        raise OracleError("Actual Swift fixture changed UTF-8 text")
    crypto = json.loads((output / "channel-crypto-oracle.json").read_text(encoding="utf-8"))
    if crypto["source_sha"] != SOURCE_SHA or {item["id"] for item in crypto["vectors"]} != {"normal", "high-bit-utf8"}:
        raise OracleError("Missing/stale independently compiled crypto outputs")
    for item in crypto["vectors"]:
        raw = bytes.fromhex(item["packet_hex"])
        if len(raw) < 18 or (len(raw) - 2) % 16 or item["byte_count"] != len(raw):
            raise OracleError("Invalid compiled channel-oracle packet bytes")
    return {
        "schema_version": 1, "source_sha": SOURCE_SHA,
        "source_map_sha256": sha256(json_bytes(source_map)),
        "result": "passed", "swift": result, "compression": compression,
        "decoded_semantics_equal": True,
        "scope": "real reference-codec export/decode and independent crypto; not bidirectional Kotlin restore/database parity",
        "artifacts": [{"path": name, "size": (output / name).stat().st_size,
                       "sha256": sha256((output / name).read_bytes())} for name in (
                           "reference-envelope.json", "reference-envelope.meshcoreone",
                           "channel-crypto-oracle.json", "test-results.json")],
    }


def run(output: Path):
    if platform.system() != "Darwin":
        raise OracleError("Actual Swift codec execution requires isolated macOS; staging is not execution")
    if output.exists():
        raise OracleError("Actual codec output requires a new isolated directory")
    output.mkdir(parents=True)
    with phase("source-staging"):
        source_map = stage(FrozenReference(), output / "sources")
    environment = mac_environment()
    compiler = shutil.which("swiftc", path=environment.get("PATH"))
    if compiler is None:
        raise OracleError("Missing compatible Swift compiler")
    execute([compiler, "--version"], output / "swift-version.log", environment)
    version = (output / "swift-version.log").read_text(encoding="utf-8")
    match = re.search(r"Swift version (\d+)\.(\d+)", version)
    if match is None or tuple(map(int, match.groups())) < (6, 2):
        raise OracleError("Pinned source requires an actual Swift 6.2+ toolchain")
    binary = output / "codec-oracle"
    command = [compiler, "-swift-version", "6", "-strict-concurrency=complete", "-parse-as-library",
               *map(str, sorted((output / "sources").glob("*.swift"))), "-o", str(binary)]
    execute(command, output / "compile.log", environment)
    execute([str(binary), "test", str(output)], output / "execution.log", environment)
    with phase("output-verification"):
        evidence = verify_output(output, source_map)
    with phase("hosted-identity"):
        evidence["identity"] = evidence_identity()
    evidence["execution"] = {
        "host": "macOS", "os_version": platform.mac_ver()[0], "architecture": platform.machine(),
        "swift_version": version.strip(), "head_sha": subprocess.check_output(
            ["git", "--no-pager", "-C", str(REPO), "rev-parse", "HEAD"], text=True).strip(),
        "workflow_run_id": os.environ.get("GITHUB_RUN_ID"),
        "workflow_run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "candidate_kotlin_executed": False,
    }
    write_or_check(output / "codec-evidence.json", json_bytes(evidence), check=False)
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("stage", "run"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if not args.output.is_absolute():
            raise OracleError("An explicit absolute isolated oracle directory is required")
        result = stage(FrozenReference(), args.output) if args.command == "stage" else run(args.output)
        print(json_bytes({"result": "staged-not-executed" if args.command == "stage" else result["result"],
                          "source_sha": SOURCE_SHA, "scope": result["scope"]}).decode(), end="")
        return 0
    except (PortError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
