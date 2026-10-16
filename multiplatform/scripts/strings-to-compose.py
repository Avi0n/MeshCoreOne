#!/usr/bin/env python3
"""Convert iOS .strings and .stringsdict localization into Compose Multiplatform string resources.

Source of truth is the iOS app's `.strings` / `.stringsdict` families under
`MC1/Resources/Localization/<locale>.lproj/`. This script parses them and emits
Compose resources (`shared/src/commonMain/composeResources/values[-<qualifier>]/strings.xml`).
A `.stringsdict` key replaces the always-`other` `.strings` row with a `<plurals>` block.
Generated files are checked in, mirroring how SwiftGen output is committed on the iOS side;
CI can re-run the `convertStrings` Gradle task and diff to catch drift.

Output is deterministic: families are processed in a fixed order, and keys keep their
source-file order within each family, so running twice produces byte-identical files.

--------------------------------------------------------------------------------------------
KEY-DERIVATION RULE  (Phase 6's parity check depends on this being stable)
--------------------------------------------------------------------------------------------
Compose resource names must be valid, unique, lowercase Kotlin identifiers. Each iOS key is
transformed as follows:

  1. The iOS key body is normalized:
       - dots replaced by underscores            (welcome.title      -> welcome_title)
       - camelCase boundaries split by underscore (getStarted         -> get_started)
                                                  (nodeCLIHelp        -> node_cli_help)
       - everything lowercased
       - any remaining non [a-z0-9_] replaced by underscore
       - runs of underscores collapsed, leading/trailing underscores stripped
  2. The lowercased family name (chats, contacts, localizable, map, onboarding, remotenodes,
     settings, tools, whatsnew) is prepended as `"<family>_"` UNLESS the normalized body already
     begins with that token. Most iOS keys are already namespaced by feature (e.g. "chats.foo",
     "settings.bar"), so this avoids redundant `chats_chats_...` names while still forcing a
     family prefix onto un-namespaced keys (e.g. Localizable's "common.ok" -> localizable_common_ok).
     The prefix guarantees cross-family uniqueness and that a name never equals a Kotlin keyword.

  Examples:  Onboarding "welcome.getStarted"  ->  onboarding_welcome_get_started
             Chats      "chats.blockSender"    ->  chats_block_sender   (no doubled prefix)
             Localizable "common.ok"           ->  localizable_common_ok

If two distinct iOS keys in the same family derive to the same name, the script errors out
rather than silently dropping a translation.

--------------------------------------------------------------------------------------------
FORMAT-SPECIFIER REMAP  (iOS/printf -> Java/Compose)
--------------------------------------------------------------------------------------------
Each `%[argnum$][flags][width][.precision][length]conversion` token is rewritten:

  iOS            -> Compose      note
  %@             -> %s           object -> string
  %1$@ %2$@ ...  -> %1$s %2$s    positional object -> string (index preserved)
  %d %i          -> %d           int
  %u             -> %d           unsigned int -> int (Java has no %u)
  %lld %llu %ld  -> %d           C length modifiers (l/ll/h/hh/z/j/t/q/L) are dropped
  %2$lld         -> %2$d         positional long -> int
  %f %.1f %.2f   -> %f %.1f      float; precision/width preserved as-is
  %x %X %o %e %g %c %s -> unchanged (already Java-compatible)
  %%             -> %%           literal percent, left untouched

Positional (`n$`), flags, width, and precision are always preserved; only the C length
modifier is dropped and the conversion letter is normalized.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Android/CLDR quantity order. iOS stringsdict uses the same names (zero/one/two/few/many/other).
ANDROID_QUANTITIES = ("zero", "one", "two", "few", "many", "other")
_PLURAL_VAR_RE = re.compile(r"%(\d+\$)?#@(\w+)@")

# Fixed processing order -> deterministic output.
FAMILIES = [
    "Chats",
    "Contacts",
    "Localizable",
    "Map",
    "Onboarding",
    "RemoteNodes",
    "Settings",
    "Tools",
    "WhatsNew",
]

# iOS BCP-47-ish lproj name -> Compose/Android values qualifier ("" means the default `values`).
LOCALE_QUALIFIER = {
    "en": "",
    "de": "de",
    "es": "es",
    "fr": "fr",
    "nl": "nl",
    "pl": "pl",
    "ru": "ru",
    "uk": "uk",
    "zh-Hans": "zh-rCN",
    "it": "it",
    "pt": "pt",
    "ko": "ko",
}

# ---------------------------------------------------------------------------------------------
# .strings parsing
# ---------------------------------------------------------------------------------------------

_STRINGS_ESCAPES = {
    "n": "\n",
    "t": "\t",
    "r": "\r",
    '"': '"',
    "\\": "\\",
    "0": "\0",
}


class StringsParseError(RuntimeError):
    pass


def _decode_quoted(text: str, i: int, path: Path) -> tuple[str, int]:
    """Read a double-quoted string starting at text[i] == '"'; return (value, index_after)."""
    assert text[i] == '"'
    i += 1
    out: list[str] = []
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "\\":
            if i + 1 >= n:
                raise StringsParseError(f"{path}: dangling escape at end of file")
            nxt = text[i + 1]
            if nxt in ("U", "u"):
                hex_digits = text[i + 2 : i + 6]
                if len(hex_digits) < 4 or any(c not in "0123456789abcdefABCDEF" for c in hex_digits):
                    raise StringsParseError(f"{path}: malformed unicode escape near \\{nxt}{hex_digits}")
                out.append(chr(int(hex_digits, 16)))
                i += 6
            else:
                out.append(_STRINGS_ESCAPES.get(nxt, nxt))
                i += 2
        elif ch == '"':
            return "".join(out), i + 1
        else:
            out.append(ch)
            i += 1
    raise StringsParseError(f"{path}: unterminated string literal")


def parse_strings(path: Path) -> list[tuple[str, str]]:
    """Parse a .strings file into an ordered list of (key, value) with escapes decoded."""
    text = path.read_text(encoding="utf-8")
    entries: list[tuple[str, str]] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch.isspace() or ch == ";":
            i += 1
        elif text.startswith("//", i):
            nl = text.find("\n", i)
            i = n if nl == -1 else nl + 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end == -1:
                raise StringsParseError(f"{path}: unterminated block comment")
            i = end + 2
        elif ch == '"':
            key, i = _decode_quoted(text, i, path)
            while i < n and text[i].isspace():
                i += 1
            if i >= n or text[i] != "=":
                raise StringsParseError(f"{path}: expected '=' after key {key!r}")
            i += 1
            while i < n and text[i].isspace():
                i += 1
            if i >= n or text[i] != '"':
                raise StringsParseError(f"{path}: expected quoted value after key {key!r}")
            value, i = _decode_quoted(text, i, path)
            entries.append((key, value))
        else:
            raise StringsParseError(f"{path}: unexpected character {ch!r} at offset {i}")
    return entries


class StringsdictParseError(RuntimeError):
    pass


def _plist_value(elem: ET.Element):
    if elem.tag == "dict":
        return _plist_dict(elem)
    if elem.tag == "string":
        return elem.text or ""
    if elem.tag == "integer":
        return int(elem.text or "0")
    if elem.tag == "true":
        return True
    if elem.tag == "false":
        return False
    return None


def _plist_dict(elem: ET.Element) -> dict:
    children = list(elem)
    result: dict = {}
    index = 0
    while index < len(children):
        child = children[index]
        if child.tag != "key":
            index += 1
            continue
        if index + 1 >= len(children):
            break
        result[child.text or ""] = _plist_value(children[index + 1])
        index += 2
    return result


def _plural_variable(localized_format: str, nested: dict) -> str | None:
    match = _PLURAL_VAR_RE.search(localized_format or "")
    if match:
        return match.group(2)
    for key, value in nested.items():
        if isinstance(value, dict) and value.get("NSStringFormatSpecTypeKey") == "NSStringPluralRuleType":
            return key
    return None


def parse_stringsdict(path: Path) -> dict[str, dict[str, str]]:
    """Parse a .stringsdict into ios_key -> {quantity: template}.

    Each top-level key maps onto the nested NSStringPluralRuleType variable named by
    NSStringLocalizedFormatKey (`%#@count@` / `%2$#@totalNodes@`). Quantity names match Android.
    """
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"<!DOCTYPE[^>]*>", "", text, count=1)
    root = ET.fromstring(text)
    plist_root = root.find("dict")
    if plist_root is None:
        raise StringsdictParseError(f"{path}: missing plist dict")
    top = _plist_dict(plist_root)
    out: dict[str, dict[str, str]] = {}
    for ios_key, entry in top.items():
        if not isinstance(entry, dict):
            continue
        variable = _plural_variable(str(entry.get("NSStringLocalizedFormatKey") or ""), entry)
        if variable is None:
            raise StringsdictParseError(f"{path}: {ios_key!r} has no plural variable")
        nested = entry.get(variable)
        if not isinstance(nested, dict):
            raise StringsdictParseError(f"{path}: {ios_key!r} missing {variable!r} plural dict")
        quantities = {
            name: value
            for name, value in nested.items()
            if name in ANDROID_QUANTITIES and isinstance(value, str)
        }
        if "other" not in quantities:
            raise StringsdictParseError(f"{path}: {ios_key!r} is missing the required 'other' quantity")
        out[ios_key] = quantities
    return out


def _plurals_xml(name: str, quantities: dict[str, str]) -> list[str]:
    lines = [f'    <plurals name="{name}">']
    for quantity in ANDROID_QUANTITIES:
        template = quantities.get(quantity)
        if template is None:
            continue
        lines.append(f'        <item quantity="{quantity}">{android_escape(remap_specifiers(template))}</item>')
    lines.append("    </plurals>")
    return lines


# ---------------------------------------------------------------------------------------------
# key derivation
# ---------------------------------------------------------------------------------------------

_CAMEL_1 = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_CAMEL_2 = re.compile(r"(?<=[A-Z])(?=[A-Z][a-z])")


def derive_key(family: str, ios_key: str) -> str:
    s = ios_key.replace(".", "_")
    s = _CAMEL_1.sub("_", s)
    s = _CAMEL_2.sub("_", s)
    s = s.lower()
    s = re.sub(r"[^a-z0-9_]", "_", s)
    s = re.sub(r"_+", "_", s).strip("_")
    prefix = family.lower()
    if s == prefix or s.startswith(f"{prefix}_"):
        return s
    return f"{prefix}_{s}"


# ---------------------------------------------------------------------------------------------
# format-specifier remap
# ---------------------------------------------------------------------------------------------

_SPEC_RE = re.compile(
    r"%(?:(\d+)\$)?([-+ 0#]*)(\d+)?(?:\.(\d+))?(hh|h|ll|l|L|q|j|z|t)?([@diufFeEgGxXoscaA%])"
)
_CONV_MAP = {"@": "s", "i": "d", "u": "d"}


def _remap_spec(match: re.Match[str]) -> str:
    argnum, flags, width, precision, _length, conv = match.groups()
    if conv == "%":
        return "%%"
    new_conv = _CONV_MAP.get(conv, conv)
    parts = ["%"]
    if argnum:
        parts.append(f"{argnum}$")
    parts.append(flags or "")
    if width:
        parts.append(width)
    if precision is not None:
        parts.append(f".{precision}")
    parts.append(new_conv)
    return "".join(parts)


def remap_specifiers(value: str) -> str:
    return _SPEC_RE.sub(_remap_spec, value)


# ---------------------------------------------------------------------------------------------
# Android/Compose XML escaping
# ---------------------------------------------------------------------------------------------


def android_escape(value: str) -> str:
    # Real backslashes first, so the escape backslashes added below are not doubled.
    out = value.replace("\\", "\\\\")
    out = out.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
    # Quotes stay literal: these XML files feed the Compose resources compiler, whose
    # unescaper handles \n/\t/\uXXXX but renders \' and \" verbatim (unlike AAPT).
    out = out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if out.startswith(("@", "?")):
        out = "\\" + out
    return out


def aapt_escape(value: str) -> str:
    """Escape a string for AAPT `res/values/strings.xml` (apostrophes must be quoted)."""
    out = value.replace("\\", "\\\\")
    out = out.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")
    out = out.replace("'", "\\'").replace('"', '\\"')
    out = out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if out.startswith(("@", "?")):
        out = "\\" + out
    return out


def locale_bcp47(locale: str) -> str:
    """iOS lproj / convertStrings locale name -> BCP-47 tag for `locales_config.xml`."""
    if locale == "zh-Hans":
        return "zh-CN"
    return locale


# Host `res/values` names (manifest, shortcuts, FGS) sourced from already-converted Compose keys.
# Unmapped host keys stay English in the hand-authored `androidApp/.../values/strings.xml`.
ANDROID_APP_FROM_COMPOSE = [
    ("radio_tile_label", "android_radio_tile_label"),
    ("shortcut_open_chats_short", "chats_title"),
    ("shortcut_open_map_short", "localizable_tabs_map"),
    ("shortcut_open_status_short", "tools_intent_status_short_title"),
    ("shortcut_open_status_long", "tools_intent_status_title"),
    ("shortcut_open_map_long", "chats_message_action_open_map_link"),
    ("notification_unread_count", "localizable_tabs_chats_unread_accessibility_value"),
    ("notification_state_disconnected", "localizable_common_status_disconnected"),
    ("notification_state_connecting", "localizable_common_status_connecting"),
    ("notification_state_syncing", "localizable_common_status_syncing"),
    ("notification_state_connected", "settings_device_connected"),
    ("notification_channel_connection", "settings_device_info_connection_header"),
    ("notification_channel_messages", "settings_backup_import_preview_messages"),
    ("notification_channel_mentions", "chats_notification_level_mentions"),
    ("notification_channel_low_battery", "localizable_notifications_low_battery_title"),
    ("notification_channel_reactions", "settings_notifications_reactions"),
    ("shortcut_open_chats_long", "android_shortcut_open_chats_long"),
    ("notification_packet_rate", "android_notification_packet_rate"),
    ("notification_channel_discovery", "android_notification_channel_discovery"),
    ("notification_action_speak_status", "android_notification_action_speak_status"),
]


def write_android_app_locale(android_app_res: Path, locale: str, raw_by_name: dict[str, str]) -> None:
    qualifier = LOCALE_QUALIFIER[locale]
    if not qualifier:
        return
    missing = [src for _, src in ANDROID_APP_FROM_COMPOSE if src not in raw_by_name]
    if missing:
        raise SystemExit(f"android-app string sources missing in {locale}: {', '.join(missing)}")
    out_dir = android_app_res / f"values-{qualifier}"
    out_dir.mkdir(parents=True, exist_ok=True)
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by scripts/strings-to-compose.py. Do not edit by hand. -->",
        "<resources>",
    ]
    lines.extend(
        f'    <string name="{name}">{aapt_escape(raw_by_name[src])}</string>'
        for name, src in ANDROID_APP_FROM_COMPOSE
    )
    lines.append("</resources>")
    (out_dir / "strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_locales_config(android_app_res: Path, locales: list[str]) -> None:
    xml_dir = android_app_res / "xml"
    xml_dir.mkdir(parents=True, exist_ok=True)
    ordered = [loc for loc in locales if loc == "en"] + [loc for loc in locales if loc != "en"]
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by scripts/strings-to-compose.py from the Compose locale list. Do not edit by hand. -->",
        '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">',
    ]
    lines.extend(f'    <locale android:name="{locale_bcp47(loc)}" />' for loc in ordered)
    lines.append("</locale-config>")
    (xml_dir / "locales_config.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# conversion driver
# ---------------------------------------------------------------------------------------------


def convert_locale(
    ios_root: Path,
    out_root: Path,
    locale: str,
    supplement_root: Path | None = None,
    android_app_res: Path | None = None,
) -> dict[str, int]:
    if locale not in LOCALE_QUALIFIER:
        raise SystemExit(f"unknown locale {locale!r}; known: {', '.join(LOCALE_QUALIFIER)}")
    lproj = ios_root / f"{locale}.lproj"
    if not lproj.is_dir():
        raise SystemExit(f"missing localization directory: {lproj}")

    resource_lines: list[str] = []
    raw_by_name: dict[str, str] = {}
    seen: dict[str, str] = {}
    counts: dict[str, int] = {}
    for family in FAMILIES:
        src = lproj / f"{family}.strings"
        dict_src = lproj / f"{family}.stringsdict"
        plurals = parse_stringsdict(dict_src) if dict_src.exists() else {}
        if not src.exists() and not plurals:
            counts[family] = 0
            continue
        family_count = 0
        consumed_plurals: set[str] = set()
        for ios_key, value in parse_strings(src) if src.exists() else []:
            name = derive_key(family, ios_key)
            if name in seen and seen[name] != ios_key:
                raise SystemExit(
                    f"key collision in {family}: {ios_key!r} and {seen[name]!r} both derive {name!r}"
                )
            seen[name] = ios_key
            if ios_key in plurals:
                quantities = plurals[ios_key]
                resource_lines.extend(_plurals_xml(name, quantities))
                raw_by_name[name] = remap_specifiers(quantities["other"])
                consumed_plurals.add(ios_key)
            else:
                remapped = remap_specifiers(value)
                raw_by_name[name] = remapped
                resource_lines.append(f'    <string name="{name}">{android_escape(remapped)}</string>')
            family_count += 1
        for ios_key, quantities in plurals.items():
            if ios_key in consumed_plurals:
                continue
            name = derive_key(family, ios_key)
            if name in seen and seen[name] != ios_key:
                raise SystemExit(
                    f"key collision in {family}: {ios_key!r} and {seen[name]!r} both derive {name!r}"
                )
            seen[name] = ios_key
            resource_lines.extend(_plurals_xml(name, quantities))
            raw_by_name[name] = remap_specifiers(quantities["other"])
            family_count += 1
        counts[family] = family_count

    # Android/Compose-only supplement: strings the iOS app no longer ships but the Kotlin UI
    # still needs. Keys are already final Compose resource names, so they are appended verbatim
    # (no family derivation) after the iOS families. A supplement key that collides with a
    # derived iOS name is an error, so a resurrected iOS key can't silently shadow the supplement.
    supplement_count = 0
    if supplement_root is not None:
        supp = supplement_root / f"{locale}.strings"
        if supp.exists():
            for name, value in parse_strings(supp):
                if name in seen:
                    raise SystemExit(
                        f"supplement key {name!r} collides with iOS-derived key in locale {locale}"
                    )
                seen[name] = name
                remapped = remap_specifiers(value)
                raw_by_name[name] = remapped
                resource_lines.append(f'    <string name="{name}">{android_escape(remapped)}</string>')
                supplement_count += 1
    counts["Supplement"] = supplement_count

    qualifier = LOCALE_QUALIFIER[locale]
    out_dir = out_root / (f"values-{qualifier}" if qualifier else "values")
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / "strings.xml"

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by scripts/strings-to-compose.py from MC1/Resources/Localization. Do not edit by hand. -->",
        "<resources>",
        *resource_lines,
        "</resources>",
    ]
    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")

    if android_app_res is not None:
        write_android_app_locale(android_app_res, locale, raw_by_name)

    return counts


def main() -> int:
    ap = argparse.ArgumentParser(description="Convert iOS .strings to Compose string resources.")
    ap.add_argument("--ios-root", required=True, type=Path, help="Path to <locale>.lproj parent dir.")
    ap.add_argument("--out-root", required=True, type=Path, help="composeResources output dir.")
    ap.add_argument("--locales", required=True, help="Comma-separated locale list, e.g. en or en,de,fr.")
    ap.add_argument(
        "--supplement-root",
        type=Path,
        default=None,
        help="Optional dir of <locale>.strings supplements keyed by final Compose resource name.",
    )
    ap.add_argument(
        "--android-app-res",
        type=Path,
        default=None,
        help="Optional androidApp src/main/res dir. Writes per-locale shade/shortcut strings and locales_config.xml.",
    )
    args = ap.parse_args()

    locales = [loc.strip() for loc in args.locales.split(",") if loc.strip()]
    total = 0
    for locale in locales:
        counts = convert_locale(
            args.ios_root,
            args.out_root,
            locale,
            args.supplement_root,
            args.android_app_res,
        )
        per_family = ", ".join(f"{fam}={counts[fam]}" for fam in [*FAMILIES, "Supplement"])
        n = sum(counts.values())
        total += n
        print(f"[{locale}] {n} keys  ({per_family})")
    if args.android_app_res is not None:
        write_locales_config(args.android_app_res, locales)
    print(f"done: {total} keys across {len(locales)} locale(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
