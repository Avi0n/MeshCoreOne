#!/usr/bin/env python3
"""Mechanical iOS-vs-Kotlin parity gates.

Fails when:

  * generated Compose or translated Android host strings/plurals differ from their sources
  * a backup DTO constructor field has no Room column (unless listed as an intentional omit)
  * a Swift Codable backup DTO field has no Kotlin BackupDtos field
  * the JNI envelope (Swift encoder / Kotlin decoder / goldens / session methods) drifts

Copy of the convertStrings idea: one script, deterministic, runnable from make/CI.
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = Path(__file__).resolve().parent
IOS_STRINGS = REPO / "MC1/Resources/Localization"
COMPOSE_VALUES = REPO / "multiplatform/shared/src/commonMain/composeResources/values/strings.xml"
SUPPLEMENT_ROOT = REPO / "multiplatform/localization-supplement"
ANDROID_APP_RES = REPO / "multiplatform/androidApp/src/main/res"
BACKUP_DTOS = REPO / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/backup/BackupDtos.kt"
ENTITIES_DIR = REPO / "multiplatform/shared/src/commonMain/kotlin/one/meshcore/shared/persistence"
SWIFT_MODELS = REPO / "MC1Services/Sources/MC1Services/Models"

# DTO constructor field -> entity constructor field when names differ (enum raw columns, etc.).
FIELD_ALIASES = {
    ("ChannelDto", "notificationLevel"): "notificationLevelRawValue",
    ("MessageDto", "direction"): "directionRawValue",
    ("MessageDto", "status"): "statusRawValue",
    ("MessageDto", "textType"): "textTypeRawValue",
    ("MessageDto", "routeType"): "routeTypeRawValue",
    ("MessageDto", "sortDateRaw"): "sortDate",
    ("RemoteNodeSessionDto", "role"): "roleRawValue",
    ("RemoteNodeSessionDto", "permissionLevel"): "permissionLevelRawValue",
    ("RemoteNodeSessionDto", "notificationLevel"): "notificationLevelRawValue",
    ("DiscoveredNodeDto", "lastHeard"): "lastHeardEpochSeconds",
}

# Constructor fields that are not Room columns on purpose. Each omit must stay listed
# with a reason; dropping a key from the Kotlin encoder instead also needs a test.
INTENTIONAL_OMITS = {
    ("SavedTracePathDto", "runs"): "child TracePathRunEntity rows, not a column on saved_trace_path",
}

DTO_ENTITY = {
    "DeviceDto": "DeviceEntity",
    "ContactDto": "ContactEntity",
    "ChannelDto": "ChannelEntity",
    "MessageDto": "MessageEntity",
    "MessageRepeatDto": "MessageRepeatEntity",
    "ReactionDto": "ReactionEntity",
    "RoomMessageDto": "RoomMessageEntity",
    "RemoteNodeSessionDto": "RemoteNodeSessionEntity",
    "SavedTracePathDto": "SavedTracePathEntity",
    "TracePathRunDto": "TracePathRunEntity",
    "BlockedChannelSenderDto": "BlockedChannelSenderEntity",
    "NodeStatusSnapshotDto": "NodeStatusSnapshotEntity",
    "DiscoveredNodeDto": "DiscoveredNodeEntity",
}

VAL_RE = re.compile(r"\bval\s+(\w+)\s*:")
SWIFT_PROP_RE = re.compile(r"^\s*public (?:let|var) (\w+)\s*:")
SWIFT_STRUCT_RE = re.compile(r"public struct (\w+DTO)")

# Swift Codable JSON key -> Kotlin constructor property when they differ.
SWIFT_TO_KOTLIN_FIELD = {
    ("MessageDTO", "sortDate"): "sortDateRaw",
}

DIAGNOSTIC_LIMIT = 10


def _load_strings_module():
    spec = importlib.util.spec_from_file_location(
        "strings_to_compose", SCRIPTS / "strings-to-compose.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _resource_values(path: Path) -> dict[str, tuple]:
    root = ET.parse(path).getroot()
    if root.tag != "resources":
        raise ValueError("expected a resources root")
    values: dict[str, tuple] = {}
    for child in root:
        name = child.attrib.get("name")
        if child.tag not in ("string", "plurals") or not name:
            raise ValueError(f"invalid resource: {child.tag}")
        if name in values:
            raise ValueError(f"duplicate resource: {name}")
        attributes = tuple(sorted(child.attrib.items()))
        if child.tag == "string":
            if len(child):
                raise ValueError(f"unexpected nested content in string {name}")
            values[name] = (child.tag, attributes, child.text or "")
        else:
            quantities: set[str] = set()
            items: list[tuple] = []
            for item in child:
                quantity = item.attrib.get("quantity")
                if item.tag != "item" or not quantity or len(item):
                    raise ValueError(f"invalid plural item in {name}")
                if quantity in quantities:
                    raise ValueError(f"duplicate plural quantity: {name}.{quantity}")
                quantities.add(quantity)
                items.append((tuple(sorted(item.attrib.items())), item.text or ""))
            values[name] = (child.tag, attributes, tuple(sorted(items)))
    return values


def _compare_resources(expected: Path, actual: Path) -> list[str]:
    label = actual.relative_to(REPO)
    if not actual.is_file():
        return [f"{label} missing"]
    try:
        expected_values = _resource_values(expected)
    except (OSError, ET.ParseError, ValueError) as error:
        return [f"generated resources for {label}: {error}"]
    try:
        actual_values = _resource_values(actual)
    except (OSError, ET.ParseError, ValueError) as error:
        return [f"{label}: {error}"]
    errors: list[str] = []
    differences = {
        "missing": expected_values.keys() - actual_values.keys(),
        "unexpected": actual_values.keys() - expected_values.keys(),
        "changed": {name for name in expected_values.keys() & actual_values.keys()
                    if expected_values[name] != actual_values[name]},
    }
    for kind, names in differences.items():
        if names:
            preview = ", ".join(sorted(names)[:DIAGNOSTIC_LIMIT])
            extra = f", … {len(names) - DIAGNOSTIC_LIMIT} more" if len(names) > DIAGNOSTIC_LIMIT else ""
            errors.append(f"{label}: {kind} resources ({len(names)}): {preview}{extra}")
    return errors


def check_strings() -> list[str]:
    stc = _load_strings_module()
    if not IOS_STRINGS.is_dir():
        return [f"iOS strings root missing: {IOS_STRINGS}"]
    errors: list[str] = []
    temp_root = REPO / "tmp"
    temp_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="check-parity-", dir=temp_root) as directory:
        generated = Path(directory)
        for locale, qualifier in stc.LOCALE_QUALIFIER.items():
            required = [IOS_STRINGS / f"{locale}.lproj/{family}.strings" for family in stc.FAMILIES]
            required.append(SUPPLEMENT_ROOT / f"{locale}.strings")
            missing = [path for path in required if not path.is_file()]
            if missing:
                errors.extend(f"localization source missing: {path.relative_to(REPO)}" for path in missing)
                continue
            try:
                stc.convert_locale(IOS_STRINGS, generated / "compose", locale,
                                   SUPPLEMENT_ROOT, generated / "host")
            except (OSError, ET.ParseError, ValueError,
                    stc.StringsParseError, stc.StringsdictParseError, SystemExit) as error:
                errors.append(f"localization source {locale}: {error}")
                continue
            values_dir = f"values-{qualifier}" if qualifier else "values"
            relative = Path(values_dir) / "strings.xml"
            errors.extend(_compare_resources(generated / "compose" / relative,
                                             COMPOSE_VALUES.parent.parent / relative))
            if qualifier:
                errors.extend(_compare_resources(generated / "host" / relative, ANDROID_APP_RES / relative))
    try:
        compose_names = set(_resource_values(COMPOSE_VALUES))
    except (OSError, ET.ParseError, ValueError):
        compose_names = None
    errors.extend(check_locale_config(stc, compose_names))
    if errors:
        errors.append("Regenerate localization resources with: cd multiplatform && ./gradlew :shared:convertStrings")
    return errors


ANDROID_MANIFEST = REPO / "multiplatform/androidApp/src/main/AndroidManifest.xml"
LOCALES_CONFIG = REPO / "multiplatform/androidApp/src/main/res/xml/locales_config.xml"


def check_locale_config(stc, compose_names: set[str] | None) -> list[str]:
    errors: list[str] = []
    if not ANDROID_MANIFEST.is_file():
        return [f"Android manifest missing: {ANDROID_MANIFEST}"]
    manifest = ANDROID_MANIFEST.read_text()
    if 'android:localeConfig="@xml/locales_config"' not in manifest:
        errors.append("AndroidManifest.xml is missing android:localeConfig=@xml/locales_config")
    if not LOCALES_CONFIG.is_file():
        errors.append(f"locales_config.xml missing: {LOCALES_CONFIG}")
        return errors
    listed: set[str] = set()
    ns_name = "{http://schemas.android.com/apk/res/android}name"
    try:
        config = ET.parse(LOCALES_CONFIG).getroot()
    except (OSError, ET.ParseError, ValueError) as error:
        errors.append(f"{LOCALES_CONFIG.relative_to(REPO)}: {error}")
        return errors
    for child in config:
        name = child.attrib.get(ns_name) or child.attrib.get("android:name") or child.attrib.get("name")
        if child.tag.endswith("locale") and name:
            listed.add(name)
    expected = {stc.locale_bcp47(loc) for loc in stc.LOCALE_QUALIFIER}
    if listed != expected:
        errors.append(
            f"locales_config.xml {sorted(listed)} does not match Compose locales {sorted(expected)}"
        )
    if compose_names is not None:
        for _android_name, compose_name in stc.ANDROID_APP_FROM_COMPOSE:
            if compose_name not in compose_names:
                errors.append(f"android-app string source missing from Compose: {compose_name}")
    return errors


def _primary_vals(source: str, class_name: str) -> list[str] | None:
    token = f"data class {class_name}"
    start = source.find(token)
    if start < 0:
        return None
    open_paren = source.find("(", start + len(token))
    if open_paren < 0:
        return None
    depth = 0
    for index in range(open_paren, len(source)):
        char = source[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return VAL_RE.findall(source[open_paren : index + 1])
    return None


def _entity_source() -> str:
    parts = [path.read_text() for path in sorted(ENTITIES_DIR.glob("*Entity.kt"))]
    return "\n".join(parts)


def check_backup_room() -> list[str]:
    errors: list[str] = []
    dto_src = BACKUP_DTOS.read_text()
    entity_src = _entity_source()
    for dto_name, entity_name in DTO_ENTITY.items():
        dto_fields = _primary_vals(dto_src, dto_name)
        entity_fields = _primary_vals(entity_src, entity_name)
        if dto_fields is None:
            errors.append(f"could not parse {dto_name} in BackupDtos.kt")
            continue
        if entity_fields is None:
            errors.append(f"could not parse {entity_name} in persistence/")
            continue
        entity_set = set(entity_fields)
        for field in dto_fields:
            if (dto_name, field) in INTENTIONAL_OMITS:
                continue
            mapped = FIELD_ALIASES.get((dto_name, field), field)
            if mapped not in entity_set:
                errors.append(
                    f"{dto_name}.{field} has no {entity_name}.{mapped} column "
                    "(add the Room column in the same change, or list an intentional omit with a test)"
                )
        for (omit_dto, omit_field), _reason in INTENTIONAL_OMITS.items():
            if omit_dto == dto_name and omit_field not in dto_fields:
                errors.append(f"intentional omit {omit_dto}.{omit_field} is not a {dto_name} constructor field")
    # TracePathRun rows are keyed to the parent saved path; that column is Room-only.
    run_fields = _primary_vals(entity_src, "TracePathRunEntity") or []
    if "savedPathID" not in run_fields:
        errors.append("TracePathRunEntity.savedPathID missing (parent link for omitted SavedTracePathDto.runs)")
    return errors


def swift_dto_kotlin_name(swift_name: str) -> str:
    if swift_name.endswith("DTO"):
        return swift_name[:-3] + "Dto"
    return swift_name


def swift_dto_stored_fields(source: str, struct_name: str) -> list[str] | None:
    token = f"public struct {struct_name}"
    start = source.find(token)
    if start < 0:
        return None
    brace = source.find("{", start)
    if brace < 0:
        return None
    depth = 0
    end = None
    for index in range(brace, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = index
                break
    if end is None:
        return None
    lines = source[brace + 1 : end].splitlines()
    fields: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        match = SWIFT_PROP_RE.match(line)
        if match:
            after = line.split(":", 1)[1]
            computed = "{" in after
            if not computed:
                look = index + 1
                while look < len(lines) and not lines[look].strip():
                    look += 1
                if look < len(lines) and lines[look].strip().startswith("{"):
                    computed = True
            if not computed:
                fields.append(match.group(1))
        index += 1
    return fields


def check_swift_dto() -> list[str]:
    errors: list[str] = []
    paths = sorted(SWIFT_MODELS.glob("*.swift"))
    if not paths:
        return [f"Swift model sources missing or empty: {SWIFT_MODELS}"]
    dto_src = BACKUP_DTOS.read_text()
    for path in paths:
        text = path.read_text()
        for match in SWIFT_STRUCT_RE.finditer(text):
            swift_name = match.group(1)
            header = text[match.start() : match.start() + 240]
            if "Codable" not in header:
                continue
            fields = swift_dto_stored_fields(text, swift_name)
            if fields is None:
                errors.append(f"could not parse {swift_name} in {path.relative_to(REPO)}")
                continue
            kotlin_name = swift_dto_kotlin_name(swift_name)
            kotlin_fields = _primary_vals(dto_src, kotlin_name)
            if kotlin_fields is None:
                errors.append(f"{swift_name} has no {kotlin_name} in BackupDtos.kt")
                continue
            kotlin_set = set(kotlin_fields)
            for field in fields:
                mapped = SWIFT_TO_KOTLIN_FIELD.get((swift_name, field), field)
                if mapped not in kotlin_set:
                    errors.append(
                        f"{swift_name}.{field} has no {kotlin_name}.{mapped} "
                        "(add the Kotlin DTO field in the same change)"
                    )
    return errors


def check_bridge() -> list[str]:
    spec = importlib.util.spec_from_file_location(
        "generate_bridge_envelope", SCRIPTS / "generate-bridge-envelope.py"
    )
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    schema = gen.schema_from_encoders()
    return gen.check(schema)


def main(argv: list[str] | None = None) -> int:
    checks = {
        "strings": check_strings,
        "backup-room": check_backup_room,
        "swift-dto": check_swift_dto,
        "bridge": check_bridge,
    }
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "checks",
        nargs="*",
        default=list(checks),
        metavar="CHECK",
        help=f"checks to run (default: all): {', '.join(checks)}",
    )
    args = parser.parse_args(argv)
    unknown = [name for name in args.checks if name not in checks]
    if unknown:
        parser.error(f"unknown checks: {', '.join(unknown)} (choose from {', '.join(checks)})")
    errors: list[str] = []
    for name, check in checks.items():
        if name in args.checks:
            try:
                errors.extend(check())
            except (OSError, ET.ParseError, ValueError) as error:
                errors.append(f"{name}: {error}")
    if errors:
        for err in errors:
            print(err, file=sys.stderr)
        return 1
    print("parity ok: " + ", ".join(args.checks))
    return 0


if __name__ == "__main__":
    sys.exit(main())
