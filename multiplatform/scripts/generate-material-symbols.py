#!/usr/bin/env python3
"""Download Material Symbols Android XML and emit Kotlin ImageVector accessors.

The Compose Multiplatform `materialIconsExtended` artifact is frozen at Material
Icons 1.7.3. App chrome now uses Material Symbols (FILL 0 outlined / FILL 1 filled)
from Google Fonts, compiled to ImageVector so AppIcons can stay a non-composable
facade.

Usage (from multiplatform/):
    python3 scripts/generate-material-symbols.py
"""

from __future__ import annotations

import re
import ssl
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = (
    ROOT
    / "shared/src/commonMain/kotlin/one/meshcore/shared/ui/icons/symbols/MaterialSymbols.kt"
)
BASE = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined"
USER_AGENT = "MeshCoreOne-material-symbols-generator/1.0"

# (Kotlin property, Google symbol name, force auto-mirror)
FILLED: list[tuple[str, str, bool]] = [
    ("AccessTime", "access_time", False),
    ("AccountCircle", "account_circle", False),
    ("Add", "add", False),
    ("AddCircle", "add_circle", False),
    ("AlternateEmail", "alternate_email", False),
    ("Archive", "archive", False),
    ("BatteryFull", "battery_full", False),
    ("BatteryStd", "battery_std", False),
    ("Block", "block", False),
    ("Bluetooth", "bluetooth", False),
    ("Bolt", "bolt", False),
    ("Bookmark", "bookmark", False),
    ("Brightness6", "brightness_6", False),
    ("BugReport", "bug_report", False),
    ("Build", "build", False),
    ("Business", "business", False),
    ("CalendarMonth", "calendar_month", False),
    ("CellTower", "cell_tower", False),
    ("Check", "check", False),
    ("CheckCircle", "check_circle", False),
    ("ChevronRight", "chevron_right", False),
    ("Circle", "circle", False),
    ("Clear", "clear", False),
    ("Close", "close", False),
    ("CloudDownload", "cloud_download", False),
    ("Code", "code", False),
    ("ContentCopy", "content_copy", False),
    ("ContentPaste", "content_paste", False),
    ("Delete", "delete", False),
    ("DoNotTouch", "do_not_touch", False),
    ("Edit", "edit", False),
    ("Error", "error", False),
    ("ExpandMore", "expand_more", False),
    ("Favorite", "favorite", False),
    ("Feedback", "feedback", False),
    ("FilterList", "filter_list", False),
    ("Forum", "forum", False),
    ("FormatQuote", "format_quote", False),
    ("FrontHand", "front_hand", False),
    ("GraphicEq", "graphic_eq", False),
    ("Group", "group", False),
    ("GroupOff", "group_off", False),
    ("Groups", "groups", False),
    ("HighlightOff", "highlight_off", False),
    ("History", "history", False),
    ("Home", "home", False),
    ("Info", "info", False),
    ("Key", "key", False),
    ("KeyboardArrowDown", "keyboard_arrow_down", False),
    ("KeyboardArrowUp", "keyboard_arrow_up", False),
    ("KeyboardHide", "keyboard_hide", False),
    ("Layers", "layers", False),
    ("Link", "link", False),
    ("LinkOff", "link_off", False),
    ("LocationOn", "location_on", False),
    ("Lock", "lock", False),
    ("LockOpen", "lock_open", False),
    ("LooksOne", "looks_one", False),
    ("Map", "map", False),
    ("MeetingRoom", "meeting_room", False),
    ("Memory", "memory", False),
    ("MoreVert", "more_vert", False),
    ("MyLocation", "my_location", False),
    ("NoAccounts", "no_accounts", False),
    ("Notifications", "notifications", False),
    ("NotificationsOff", "notifications_off", False),
    ("Palette", "palette", False),
    ("Pause", "pause", False),
    ("Person", "person", False),
    ("PersonAdd", "person_add", False),
    ("PersonOff", "person_off", False),
    ("Place", "place", False),
    ("PlayArrow", "play_arrow", False),
    ("Public", "public", False),
    ("QrCodeScanner", "qr_code_scanner", False),
    ("RecordVoiceOver", "record_voice_over", False),
    ("Refresh", "refresh", False),
    ("Remove", "remove", False),
    ("Repeat", "repeat", False),
    ("Route", "route", False),
    ("Save", "save", False),
    ("Search", "search", False),
    ("Sensors", "sensors", False),
    ("SentimentSatisfied", "sentiment_satisfied", False),
    ("Settings", "settings", False),
    ("Share", "share", False),
    ("Shield", "shield", False),
    ("Smartphone", "smartphone", False),
    ("Star", "star", False),
    ("StopCircle", "stop_circle", False),
    ("Storage", "storage", False),
    ("Straighten", "straighten", False),
    ("Sync", "sync", False),
    ("Tag", "tag", False),
    ("Terminal", "terminal", False),
    ("Timeline", "timeline", False),
    ("Translate", "translate", False),
    ("Visibility", "visibility", False),
    ("VisibilityOff", "visibility_off", False),
    ("Warning", "warning", False),
    ("Wifi", "wifi", False),
    ("WifiTethering", "wifi_tethering", False),
]

FILLED_AUTO: list[tuple[str, str, bool]] = [
    ("ArrowBack", "arrow_back", True),
    ("Chat", "chat", True),
    ("KeyboardArrowLeft", "keyboard_arrow_left", True),
    ("KeyboardArrowRight", "keyboard_arrow_right", True),
    ("KeyboardTab", "keyboard_tab", True),
    ("ListAlt", "list_alt", True),
    ("Redo", "redo", True),
    ("Reply", "reply", True),
    ("Send", "send", True),
    ("ShowChart", "show_chart", True),
    ("Sort", "sort", True),
]

OUTLINED: list[tuple[str, str, bool]] = [
    ("AddCircle", "add_circle", False),
    ("Build", "build", False),
    ("CellTower", "cell_tower", False),
    ("Map", "map", False),
    ("Settings", "settings", False),
    ("Star", "star", False),
]

OUTLINED_AUTO: list[tuple[str, str, bool]] = [
    ("Chat", "chat", True),
]

PATH_ATTR = re.compile(r'android:pathData="([^"]+)"')
VIEWPORT_ATTR = re.compile(r'android:viewportWidth="([^"]+)"')
AUTO_MIRROR_ATTR = re.compile(r'android:autoMirrored="true"')


@dataclass(frozen=True)
class SymbolXml:
    path_data: str
    viewport: float
    auto_mirror: bool


def fetch(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    context = ssl.create_default_context()
    with urllib.request.urlopen(request, context=context, timeout=30) as response:
        return response.read().decode("utf-8")


def download(symbol: str, filled: bool) -> SymbolXml:
    variant = "fill1" if filled else "default"
    url = f"{BASE}/{symbol}/{variant}/24px.xml"
    try:
        xml = fetch(url)
    except urllib.error.HTTPError as error:
        raise SystemExit(f"failed to download {url}: HTTP {error.code}") from error
    paths = PATH_ATTR.findall(xml)
    if not paths:
        raise SystemExit(f"no pathData in {url}")
    viewport_match = VIEWPORT_ATTR.search(xml)
    viewport = float(viewport_match.group(1)) if viewport_match else 960.0
    return SymbolXml(
        path_data="".join(paths),
        viewport=viewport,
        auto_mirror=bool(AUTO_MIRROR_ATTR.search(xml)),
    )


def kotlin_string(value: str, continuation_indent: str) -> str:
    width = 90
    chunks = [value[i : i + width] for i in range(0, len(value), width)]
    quoted = [f'"{chunk}"' for chunk in chunks]
    if len(quoted) == 1:
        return quoted[0]
    return (" +\n" + continuation_indent).join(quoted)


def emit_property(
    kotlin_name: str,
    xml: SymbolXml,
    cache_name: str,
    force_auto: bool,
    indent: str,
) -> str:
    auto = "true" if (xml.auto_mirror or force_auto) else "false"
    viewport_lit = (
        f"{int(xml.viewport)}f" if float(xml.viewport).is_integer() else f"{xml.viewport}f"
    )
    pad = indent + "    "
    arg = pad + "    "
    path = kotlin_string(xml.path_data, arg)
    return (
        f"{pad}val {kotlin_name}: ImageVector\n"
        f"{pad}    get() = materialSymbol(\n"
        f"{arg}name = \"{cache_name}\",\n"
        f"{arg}pathData = {path},\n"
        f"{arg}viewport = {viewport_lit},\n"
        f"{arg}autoMirror = {auto},\n"
        f"{pad}    )\n"
    )


def emit_object(
    title: str,
    entries: list[tuple[str, str, bool]],
    filled: bool,
    indent: str,
    cache_prefix: str,
) -> str:
    prefix = f"{cache_prefix}{title}"
    lines = [f"{indent}object {title} {{"]
    for kotlin_name, symbol, force_auto in entries:
        xml = download(symbol, filled=filled)
        cache_name = f"{prefix}.{kotlin_name}"
        print(f"  {cache_name} <- {symbol} ({'fill1' if filled else 'outlined'})", flush=True)
        lines.append(emit_property(kotlin_name, xml, cache_name, force_auto, indent))
    lines.append(f"{indent}}}")
    return "\n".join(lines)


HEADER = '''package one.meshcore.shared.ui.icons.symbols

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material Symbols (Outlined, optical size 24) compiled from Google Fonts Android XML.
 *
 * Filled glyphs use FILL=1; outlined glyphs use FILL=0. Regenerated by
 * `python3 scripts/generate-material-symbols.py`. Do not edit by hand.
 */
internal object MaterialSymbols {
'''

FOOTER = '''
}

private val symbolCache = mutableMapOf<String, ImageVector>()

private fun materialSymbol(
    name: String,
    pathData: String,
    viewport: Float,
    autoMirror: Boolean,
): ImageVector {
    symbolCache[name]?.let { return it }
    val vector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
        autoMirror = autoMirror,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
    symbolCache[name] = vector
    return vector
}
'''


def main() -> int:
    print("Downloading Material Symbols XML…", flush=True)
    filled_body = emit_object(
        "Filled", FILLED, filled=True, indent="    ", cache_prefix="",
    )
    outlined_body = emit_object(
        "Outlined", OUTLINED, filled=False, indent="    ", cache_prefix="",
    )
    auto_filled = emit_object(
        "Filled",
        FILLED_AUTO,
        filled=True,
        indent="        ",
        cache_prefix="AutoMirrored.",
    )
    auto_outlined = emit_object(
        "Outlined",
        OUTLINED_AUTO,
        filled=False,
        indent="        ",
        cache_prefix="AutoMirrored.",
    )
    body = "\n".join(
        [
            HEADER.rstrip(),
            filled_body,
            "",
            outlined_body,
            "",
            "    object AutoMirrored {",
            auto_filled,
            "",
            auto_outlined,
            "    }",
            FOOTER.lstrip("\n"),
        ]
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(body, encoding="utf-8")
    print(f"Wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
