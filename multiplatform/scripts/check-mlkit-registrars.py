#!/usr/bin/env python3
"""Check reflective ML Kit registrar constructors in a non-debuggable APK."""

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_ATTRIBUTE = "{http://schemas.android.com/apk/res/android}"
DISCOVERY_SERVICE = "com.google.mlkit.common.internal.MlKitComponentDiscoveryService"
REGISTRAR_INTERFACE = "com.google.firebase.components.ComponentRegistrar"
REGISTRAR_PREFIX = "com.google.firebase.components:"
REQUIRED_REGISTRARS = {
    "com.google.mlkit.vision.barcode.internal.BarcodeRegistrar",
    "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
}
TOOL_TIMEOUT_SECONDS = 30


def analyze(tool: str, apk: Path, *arguments: str) -> str:
    result = subprocess.run(
        [tool, *arguments, str(apk)],
        capture_output=True,
        text=True,
        check=True,
        timeout=TOOL_TIMEOUT_SECONDS,
    )
    return result.stdout


def check_registrars(tool: str, apk: Path) -> dict:
    manifest = ET.fromstring(analyze(tool, apk, "manifest", "print"))
    application = manifest.find("application")
    if application is None:
        raise ValueError("APK has no application manifest entry")
    if application.get(ANDROID_ATTRIBUTE + "debuggable", "false") != "false":
        raise ValueError("Use a non-debuggable release APK")

    registrars = set()
    for service in application.findall("service"):
        if service.get(ANDROID_ATTRIBUTE + "name") != DISCOVERY_SERVICE:
            continue
        for metadata in service.findall("meta-data"):
            name = metadata.get(ANDROID_ATTRIBUTE + "name", "")
            if metadata.get(ANDROID_ATTRIBUTE + "value") == REGISTRAR_INTERFACE:
                if name.startswith(REGISTRAR_PREFIX + "com.google.mlkit."):
                    registrars.add(name.removeprefix(REGISTRAR_PREFIX))

    results = []
    for registrar in sorted(registrars):
        code = analyze(tool, apk, "dex", "code", "--class", registrar)
        declaration = re.search(r"^\.class\s+([^\n]*?)\s+L[^\n;]+;", code, re.MULTILINE)
        constructor = re.search(r"^\.method\s+([^\n]*?)\s+<init>\(\)V\s*$", code, re.MULTILINE)
        public_class = declaration is not None and "public" in declaration.group(1).split()
        public_constructor = constructor is not None and {
            "public", "constructor",
        }.issubset(constructor.group(1).split())
        results.append({
            "class": registrar,
            "publicClass": public_class,
            "publicNoArgConstructor": public_constructor,
        })
    missing = sorted(REQUIRED_REGISTRARS - registrars)
    return {
        "apk": str(apk),
        "missingRegistration": missing,
        "registrars": results,
        "passed": not missing and all(
            result["publicClass"] and result["publicNoArgConstructor"] for result in results
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    args = parser.parse_args()
    try:
        result = check_registrars(args.apkanalyzer, args.apk)
    except (OSError, ValueError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"Registrar check failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
