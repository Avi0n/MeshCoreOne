#!/usr/bin/env python3
"""Generate and check the frozen JNI JSON envelope.

The wire format is the v1 JSON object already documented in MeshCoreBridge/ENCODING.md
and frozen under fixtures/bridge/. This script does not invent a binary encoding.

Source of truth for *kinds* is the Swift producer (MeshEventEncoder / CommandResultEncoder).
Running --write emits:

  fixtures/bridge/schema.json
  multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/GeneratedBridgeKinds.kt
  multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/GeneratedBridgeDecode.kt

The Swift encoder stays the producer of record. The schema records each kind's Kotlin
decode recipe (payload / wrap / object / custom) extracted from the existing reader so
`--write` can emit the Kotlin `when` tables. Payload goldens stay reviewed JSON; this
script only creates missing empty-payload `{}` goldens.

--check fails when the Swift encoder, Kotlin decoder, goldens, or generated files drift.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ENCODER_EVENTS = REPO / "MeshCoreBridge/Sources/MeshCoreBridge/Encoding/MeshEventEncoder.swift"
ENCODER_RESULTS = REPO / "MeshCoreBridge/Sources/MeshCoreBridge/Encoding/CommandResultEncoder.swift"
KOTLIN_EVENTS = REPO / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/MeshEvent.kt"
KOTLIN_RESULTS = REPO / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/CommandResult.kt"
SCHEMA_PATH = REPO / "fixtures/bridge/schema.json"
GENERATED_KT = (
    REPO
    / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/GeneratedBridgeKinds.kt"
)
GENERATED_DECODE = (
    REPO
    / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/GeneratedBridgeDecode.kt"
)
MESH_SESSION_KT = REPO / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/MeshSession.kt"
EVENTS_DIR = REPO / "fixtures/bridge/events"
RESULTS_DIR = REPO / "fixtures/bridge/results"
BRIDGE_SWIFT = REPO / "MeshCoreBridge/Sources/MeshCoreBridge/MeshCoreBridge.swift"
SESSION_KT = REPO / "multiplatform/shared/src/androidMain/kotlin/one/meshcore/shared/meshcore/MeshCoreSession.kt"
SESSION_COMMANDS_KT = (
    REPO / "multiplatform/shared/src/androidMain/kotlin/one/meshcore/shared/meshcore/MeshCoreSessionCommands.kt"
)
SESSION_HOST_KT = (
    REPO / "multiplatform/shared/src/androidMain/kotlin/one/meshcore/shared/meshcore/MeshCoreSessionHost.kt"
)
MESH_SESSION_COMMANDS_KT = (
    REPO / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/meshcore/MeshSessionCommands.kt"
)
SESSION_SWIFT_DIR = REPO / "MeshCore/Sources/MeshCore/Session"

ENVELOPE_KIND_RE = re.compile(r'envelope\(\s*"([^"]+)"')
OK_KIND_RE = re.compile(r'envelope\(kind:\s*"([^"]+)"')
WHEN_KIND_RE = re.compile(r'^\s*"([^"]+)"\s*->', re.MULTILINE)
DECODE_FUN_RE = re.compile(
    r"fun decode(?:MeshEvent|CommandResult)\(jsonText: String\).*?^}",
    re.MULTILINE | re.DOTALL,
)
PUBLIC_FUNC_RE = re.compile(r"public func ([A-Za-z0-9_]+)\(")
BRIDGE_CALL_RE = re.compile(r"bridge\.([A-Za-z0-9_]+)\(")

# Transport hooks and internals are not session commands.
BRIDGE_NON_COMMANDS = {
    "reportConnected",
    "reportConnectFailed",
    "reportDisconnected",
    "receiveBytes",
}

SESSION_FUNC_RE = re.compile(r"(?:public )?func ([A-Za-z0-9_]+)\(")

# In-memory helpers, waiters, test hooks, and wrappers covered by another JNI command.
SESSION_INTERNALS = {
    "events",
    "eventsTracked",
    "finishEvents",
    "waitForEvent",
    "waitForInterval",
    "dispatchForTesting",
    "installSelfInfoForTest",
    "getContactByName",
    "getContactByKeyPrefix",
    "popPendingContact",
    "flushPendingContacts",
    "setAutoUpdateContacts",
    "ensureContacts",
    "fetchContacts",
    "trackContactChanges",
    "requestAutoContactRefresh",
    "autoMessageFetchLoop",
    "requestAutoMessageDrain",
    "receiveLoop",
    "handleReceivedData",
    "updateConnectionState",
    "removeConnectionStateContinuation",
    "sendSimpleCommand",
    "sendStatusRequest",
    "sendTelemetryRequest",
    "applySuggestedTimeoutMs",
    "requireFullPublicKey",
    "resumeWaitersWithFloor",
    "sendMessageWithRetry",
    "sendAppStart",
    "refreshSelfInfo",
    "signStart",
    "signData",
    "signFinish",
    "getContactsReportingTotal",
    "setTelemetryModeBase",
    "setTelemetryModeLocation",
    "setTelemetryModeEnvironment",
    "setManualAddContacts",
    "setMultiAcks",
    "setAdvertisementLocationPolicy",
}

SESSION_INTERNAL_PREFIXES = ("perform", "run", "apply", "mutate", "current")

# Radio commands with no JNI entry on purpose. A new async-throws command without a
# bridge method or an omit here fails check-parity.
INTENTIONAL_SESSION_OMITS = {
    "changeContactPath": "Android writes paths through addContactEncoded",
    "updateContact": "Android writes whole contact records through addContactEncoded",
    "sendChannelData": "channel datagrams are not on the Android host",
    "sendControlData": "control frames are not on the Android host",
    "setTuning": "rxDelay/af tuning is not on the Android host",
}

# Bridge method name -> Kotlin/Swift session name when they differ.
BRIDGE_TO_SESSION = {
    "startSession": "start",
    "stopSession": "stop",
    "addContactEncoded": "addContact",
}


ARM_RE = re.compile(r'^\s+"([^"]+)"\s*->\s*(.+?)\s*$', re.MULTILINE)
# Twelve kind arms plus else stay under cyclomatic complexity 15. Smaller slices would
# push this one generated file over its function cap.
MAX_DECODE_ARMS = 12
PAYLOAD_RE = re.compile(r"^payload\.decode<(.+)>\(\)$")
WRAP_RE = re.compile(r"^(.+)\(payload\.decode\(\)\)$")
CUSTOM_GET_MESSAGE = "CommandResult.MessageResult(decodeGetMessage(payload))"
EMPTY_PAYLOAD_FORMS = {"object"}


def _classify_arm(expr: str) -> dict:
    expr = expr.rstrip(",")
    payload = PAYLOAD_RE.match(expr)
    if payload:
        return {"form": "payload", "type": payload.group(1)}
    wrap = WRAP_RE.match(expr)
    if wrap:
        return {"form": "wrap", "type": wrap.group(1)}
    if expr == CUSTOM_GET_MESSAGE:
        return {"form": "custom", "expr": expr}
    if re.match(r"^[A-Za-z0-9_.]+$", expr):
        return {"form": "object", "type": expr}
    return {"form": "custom", "expr": expr}


def _recipes_from_kotlin(path: Path) -> dict[str, dict]:
    text = path.read_text()
    match = DECODE_FUN_RE.search(text)
    body = match.group(0) if match else text
    recipes: dict[str, dict] = {}
    for kind, expr in ARM_RE.findall(body):
        if kind == "else" or "unknown" in expr:
            continue
        classified = _classify_arm(expr)
        classified["kind"] = kind
        recipes[kind] = classified
    return recipes


def _recipe_kinds(entries: list) -> list[str]:
    kinds = []
    for entry in entries:
        if isinstance(entry, str):
            kinds.append(entry)
        else:
            kinds.append(entry["kind"])
    return kinds


def _arm_expr(recipe: dict) -> str:
    form = recipe["form"]
    if form == "payload":
        return f'payload.decodeBridge<{recipe["type"]}>()'
    if form == "wrap":
        return f'{recipe["type"]}(payload.decodeBridge())'
    if form == "object":
        return recipe["type"]
    return recipe["expr"]


def _recipe_arms(entries: list) -> list[str]:
    arms = []
    for entry in entries:
        recipe = {"kind": entry, "form": "object", "type": "Unit"} if isinstance(entry, str) else entry
        arms.append(f'    "{recipe["kind"]}" -> {_arm_expr(recipe)}')
    return arms


def _single_when(fn: str, ret: str, entries: list) -> str:
    inner = "\n".join(_recipe_arms(entries))
    return (
        f"internal fun {fn}(kind: String, payload: JsonObject): {ret} = when (kind) {{\n"
        f"{inner}\n"
        f'    else -> throw BridgeDecodingException("unknown {ret} kind: $kind")\n'
        f"}}\n"
    )


def _split_when(fn: str, ret: str, entries: list) -> str:
    chunks = [entries[index : index + MAX_DECODE_ARMS] for index in range(0, len(entries), MAX_DECODE_ARMS)]
    parts = []
    for index, chunk in enumerate(chunks):
        inner = "\n".join(_recipe_arms(chunk))
        parts.append(
            f"private fun {fn}Part{index}(kind: String, payload: JsonObject): {ret}? = when (kind) {{\n"
            f"{inner}\n"
            f"    else -> null\n"
            f"}}\n"
        )
    chain = "\n        ?: ".join(f"{fn}Part{index}(kind, payload)" for index in range(len(chunks)))
    router = (
        f"internal fun {fn}(kind: String, payload: JsonObject): {ret} =\n"
        f"    {chain}\n"
        f'        ?: throw BridgeDecodingException("unknown {ret} kind: $kind")\n'
    )
    return router + "\n" + "\n".join(parts)


def render_decode(schema: dict) -> str:
    def table(fn: str, ret: str, entries: list) -> str:
        if len(entries) <= MAX_DECODE_ARMS:
            return _single_when(fn, ret, entries)
        return _split_when(fn, ret, entries)

    return (
        "package one.meshcore.shared.meshcore\n"
        "\n"
        "import kotlinx.serialization.json.JsonObject\n"
        "\n"
        "/** Generated by multiplatform/scripts/generate-bridge-envelope.py. Do not edit. */\n"
        "\n"
        + table("decodeGeneratedMeshEvent", "MeshEvent", schema["events"])
        + "\n"
        + table("decodeGeneratedCommandResult", "CommandResult", schema["results"])
    )


def _kinds_from_swift_encoder(path: Path) -> list[str]:
    text = path.read_text()
    kinds = ENVELOPE_KIND_RE.findall(text)
    kinds += OK_KIND_RE.findall(text)
    # Preserve first-seen order, then sort for a stable schema.
    seen: dict[str, None] = {}
    for kind in kinds:
        seen.setdefault(kind, None)
    return sorted(seen)


def _kinds_from_kotlin_when(path: Path, fn_name: str | None = None) -> set[str]:
    text = path.read_text()
    if fn_name:
        start = text.find(f"fun {fn_name}(")
        if start < 0:
            return set()
        rest = text[start:]
        next_fn = rest.find("\ninternal fun ", 1)
        body = rest if next_fn < 0 else rest[:next_fn]
        return set(WHEN_KIND_RE.findall(body))
    match = DECODE_FUN_RE.search(text)
    body = match.group(0) if match else text
    return set(WHEN_KIND_RE.findall(body))


def _fixture_stems(directory: Path) -> set[str]:
    return {p.stem for p in directory.glob("*.json")}


def _merge_recipes(kinds: list[str], *sources: dict[str, dict]) -> list[dict]:
    merged: dict[str, dict] = {}
    for source in sources:
        merged.update(source)
    recipes = []
    for kind in kinds:
        recipe = dict(merged[kind]) if kind in merged else {"form": "missing"}
        recipe["kind"] = kind
        recipes.append(recipe)
    return recipes


def _schema_file_recipes(key: str) -> dict[str, dict]:
    if not SCHEMA_PATH.exists():
        return {}
    raw = json.loads(SCHEMA_PATH.read_text())
    recipes = {}
    for entry in raw.get(key, []):
        if isinstance(entry, dict) and "kind" in entry:
            recipes[entry["kind"]] = entry
    return recipes


def schema_from_encoders() -> dict:
    event_kinds = _kinds_from_swift_encoder(ENCODER_EVENTS)
    result_kinds = _kinds_from_swift_encoder(ENCODER_RESULTS)
    if "ok" not in result_kinds:
        result_kinds = sorted(set(result_kinds) | {"ok"})
    generated_recipes = _recipes_from_kotlin(GENERATED_DECODE) if GENERATED_DECODE.exists() else {}
    events = _merge_recipes(
        event_kinds,
        _recipes_from_kotlin(KOTLIN_EVENTS),
        generated_recipes,
        _schema_file_recipes("events"),
    )
    results = _merge_recipes(
        result_kinds,
        _recipes_from_kotlin(KOTLIN_RESULTS),
        generated_recipes,
        _schema_file_recipes("results"),
    )
    return {
        "version": 1,
        "format": "json-envelope-v1",
        "events": events,
        "results": results,
    }


def render_kotlin(schema: dict) -> str:
    def block(name: str, entries: list) -> str:
        values = _recipe_kinds(entries)
        inner = ",\n".join(f'        "{v}"' for v in values)
        return f"    val {name}: Set<String> = setOf(\n{inner},\n    )"

    return (
        "package one.meshcore.shared.meshcore\n"
        "\n"
        "/** Generated by multiplatform/scripts/generate-bridge-envelope.py. Do not edit. */\n"
        "internal object GeneratedBridgeKinds {\n"
        f"{block('events', schema['events'])}\n"
        "\n"
        f"{block('results', schema['results'])}\n"
        "}\n"
    )


def _write_empty_object_goldens(entries: list, directory: Path, label: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        if not isinstance(entry, dict) or entry.get("form") != "object":
            continue
        path = directory / f'{entry["kind"]}.json'
        if path.exists():
            continue
        envelope = {"kind": entry["kind"], "payload": {}, "v": 1}
        path.write_text(json.dumps(envelope, separators=(",", ":"), sort_keys=True) + "\n")
        print(f"wrote empty {label} golden {path.relative_to(REPO)}")


def write_outputs(schema: dict) -> None:
    SCHEMA_PATH.parent.mkdir(parents=True, exist_ok=True)
    SCHEMA_PATH.write_text(json.dumps(schema, indent=2) + "\n")
    GENERATED_KT.parent.mkdir(parents=True, exist_ok=True)
    GENERATED_KT.write_text(render_kotlin(schema))
    GENERATED_DECODE.parent.mkdir(parents=True, exist_ok=True)
    GENERATED_DECODE.write_text(render_decode(schema))
    _write_empty_object_goldens(schema["events"], EVENTS_DIR, "event")
    _write_empty_object_goldens(schema["results"], RESULTS_DIR, "result")


def _fail(errors: list[str]) -> int:
    if not errors:
        return 0
    for err in errors:
        print(err, file=sys.stderr)
    return 1


def check(schema: dict) -> list[str]:
    errors: list[str] = []
    generated_events = (
        _kinds_from_kotlin_when(GENERATED_DECODE, "decodeGeneratedMeshEvent")
        if GENERATED_DECODE.exists()
        else set()
    )
    generated_results = (
        _kinds_from_kotlin_when(GENERATED_DECODE, "decodeGeneratedCommandResult")
        if GENERATED_DECODE.exists()
        else set()
    )
    kotlin_events = _kinds_from_kotlin_when(KOTLIN_EVENTS) | generated_events
    kotlin_results = _kinds_from_kotlin_when(KOTLIN_RESULTS) | generated_results
    event_fixtures = _fixture_stems(EVENTS_DIR)
    result_fixtures = _fixture_stems(RESULTS_DIR)
    expected_events = set(_recipe_kinds(schema["events"]))
    expected_results = set(_recipe_kinds(schema["results"]))
    missing_recipes = [
        entry["kind"]
        for entry in schema["events"] + schema["results"]
        if isinstance(entry, dict) and entry.get("form") == "missing"
    ]
    if missing_recipes:
        errors.append(
            "schema missing decode recipes (add form/type then --write): " + ", ".join(missing_recipes)
        )

    def report(label: str, missing: set[str], extra: set[str]) -> None:
        if missing:
            errors.append(f"{label} missing: {', '.join(sorted(missing))}")
        if extra:
            errors.append(f"{label} extra: {', '.join(sorted(extra))}")

    report("Kotlin event decoder", expected_events - kotlin_events, kotlin_events - expected_events)
    report("Kotlin result decoder", expected_results - kotlin_results, kotlin_results - expected_results)
    report("event goldens", expected_events - event_fixtures, event_fixtures - expected_events)
    report("result goldens", expected_results - result_fixtures, result_fixtures - expected_results)

    if SCHEMA_PATH.exists():
        on_disk = json.loads(SCHEMA_PATH.read_text())
        if on_disk != schema:
            errors.append(f"{SCHEMA_PATH.relative_to(REPO)} is stale; run generate-bridge-envelope.py --write")
    else:
        errors.append(f"{SCHEMA_PATH.relative_to(REPO)} is missing; run generate-bridge-envelope.py --write")

    if GENERATED_KT.exists():
        if GENERATED_KT.read_text() != render_kotlin(schema):
            errors.append(f"{GENERATED_KT.relative_to(REPO)} is stale; run generate-bridge-envelope.py --write")
    else:
        errors.append(f"{GENERATED_KT.relative_to(REPO)} is missing; run generate-bridge-envelope.py --write")

    if GENERATED_DECODE.exists():
        if GENERATED_DECODE.read_text() != render_decode(schema):
            errors.append(f"{GENERATED_DECODE.relative_to(REPO)} is stale; run generate-bridge-envelope.py --write")
    else:
        errors.append(f"{GENERATED_DECODE.relative_to(REPO)} is missing; run generate-bridge-envelope.py --write")

    errors.extend(check_session_bridge())
    return errors


def _bridge_commands() -> set[str]:
    names = set(PUBLIC_FUNC_RE.findall(BRIDGE_SWIFT.read_text()))
    return names - BRIDGE_NON_COMMANDS


def _kotlin_bridge_calls() -> set[str]:
    text = "\n".join(path.read_text() for path in (SESSION_KT, SESSION_COMMANDS_KT, SESSION_HOST_KT))
    return set(BRIDGE_CALL_RE.findall(text))


def async_throws_session_funcs(text: str) -> set[str]:
    names: set[str] = set()
    for match in SESSION_FUNC_RE.finditer(text):
        cursor = match.end() - 1
        depth = 0
        while cursor < len(text):
            char = text[cursor]
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    cursor += 1
                    break
            cursor += 1
        brace = text.find("{", cursor)
        if brace < 0:
            continue
        signature = text[cursor:brace]
        if "async" in signature and "throws" in signature:
            names.add(match.group(1))
    return names


def radio_session_commands(text: str) -> set[str]:
    names: set[str] = set()
    for name in async_throws_session_funcs(text):
        if name in SESSION_INTERNALS:
            continue
        if name.startswith(SESSION_INTERNAL_PREFIXES):
            continue
        names.add(name)
    return names


def check_session_bridge() -> list[str]:
    errors: list[str] = []
    bridge = _bridge_commands()
    kotlin_calls = _kotlin_bridge_calls()
    missing_kt = sorted(bridge - kotlin_calls)
    if missing_kt:
        errors.append(
            "MeshCoreBridge commands with no Kotlin session call: " + ", ".join(missing_kt)
        )

    collected: set[str] = set()
    for path in SESSION_SWIFT_DIR.glob("MeshCoreSession*.swift"):
        collected |= radio_session_commands(path.read_text())
    stale_omits = sorted(name for name in INTENTIONAL_SESSION_OMITS if name not in collected)
    if stale_omits:
        errors.append(
            "intentional session omit is not an async-throws MeshCoreSession command: "
            + ", ".join(stale_omits)
        )
    swift_cmds = collected - set(INTENTIONAL_SESSION_OMITS)
    # A Swift radio command should have a bridge entry under the same name or the mapped name.
    inverse = {v: k for k, v in BRIDGE_TO_SESSION.items()}
    missing_bridge: list[str] = []
    for name in sorted(swift_cmds):
        bridge_name = inverse.get(name, name)
        if bridge_name not in bridge and name not in bridge:
            missing_bridge.append(name)
    if missing_bridge:
        errors.append(
            "MeshCoreSession Swift commands with no MeshCoreBridge entry: " + ", ".join(missing_bridge)
        )

    session_text = MESH_SESSION_KT.read_text() + "\n" + MESH_SESSION_COMMANDS_KT.read_text()
    session_iface = set(re.findall(r"(?:suspend )?fun ([A-Za-z0-9_]+)\(", session_text))
    session_iface -= {"close", "getContactsReportingTotal", "getSelfInfo"}
    missing_iface = []
    for name in sorted(session_iface):
        bridge_name = inverse.get(name, name)
        if bridge_name not in bridge and name not in bridge:
            # default-body hooks that fakes implement locally
            if name in {"deliverReceived", "reportConnected", "reportConnectFailed", "reportDisconnected"}:
                continue
            missing_iface.append(name)
    if missing_iface:
        errors.append("MeshSession methods with no MeshCoreBridge entry: " + ", ".join(missing_iface))
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Regenerate schema.json and GeneratedBridgeKinds.kt")
    parser.add_argument("--check", action="store_true", help="Fail on encoder/decoder/golden/session drift")
    args = parser.parse_args(argv)
    if not args.write and not args.check:
        args.check = True

    schema = schema_from_encoders()
    if args.write:
        write_outputs(schema)
        print(f"wrote {SCHEMA_PATH.relative_to(REPO)}")
        print(f"wrote {GENERATED_KT.relative_to(REPO)}")
        print(f"wrote {GENERATED_DECODE.relative_to(REPO)}")
        print(f"events={len(schema['events'])} results={len(schema['results'])}")
    if args.check:
        if args.write:
            schema = schema_from_encoders()
        errors = check(schema)
        if errors:
            return _fail(errors)
        print(
            f"bridge envelope ok: {len(schema['events'])} events, "
            f"{len(schema['results'])} results, session/bridge mapped"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
