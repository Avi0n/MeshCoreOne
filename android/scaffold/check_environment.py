"""Android-only WP-002 worker preflight; no installation, live readiness receipt or activation."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def allowed_environment_names():
    declaration = json.loads(Path(__file__).with_name("environment-allowlist.json").read_text(encoding="utf-8"))
    names = declaration.get("allowed_names")
    if declaration.get("schema_version") != 1 or not isinstance(names, list) or not names:
        raise ValueError("Malformed candidate environment allowlist")
    if any(not isinstance(name, str) or not name for name in names):
        raise ValueError("Invalid candidate environment allowlist entry")
    lowered = {name.casefold() for name in names}
    if len(lowered) != len(names):
        raise ValueError("Duplicate candidate environment allowlist entry")
    return lowered


def check_environment():
    allowed = allowed_environment_names()
    inherited = sorted(name for name, value in os.environ.items() if value and name.casefold() not in allowed)
    if inherited:
        raise ValueError("Candidate build inherited non-allowlisted variables: " + ", ".join(inherited))
    java_home = os.environ.get("JAVA_HOME")
    sdk_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not java_home or not sdk_home:
        raise ValueError("JAVA_HOME and ANDROID_HOME/ANDROID_SDK_ROOT are required")
    if not os.environ.get("GRADLE_USER_HOME") or not os.environ.get("ANDROID_USER_HOME"):
        raise ValueError("Explicit private GRADLE_USER_HOME and ANDROID_USER_HOME caches are required")
    if os.environ.get("ANDROID_HOME") and os.environ.get("ANDROID_SDK_ROOT"):
        if Path(os.environ["ANDROID_HOME"]).resolve() != Path(os.environ["ANDROID_SDK_ROOT"]).resolve():
            raise ValueError("ANDROID_HOME and ANDROID_SDK_ROOT disagree")
    java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    result = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True, timeout=30)
    version = result.stdout + result.stderr
    if not re.search(r'version "21\.0\.12\.1"', version):
        raise ValueError("Build JDK must be the declared 21.0.12.1 candidate")
    sdk = Path(sdk_home)
    for relative, package, revision in (
        ("platforms/android-37.2", "platforms;android-37.2", (2, 0, 0)),
        ("build-tools/37.0.0", "build-tools;37.0.0", (37, 0, 0)),
    ):
        metadata = ET.parse(sdk / relative / "package.xml").getroot()
        local = metadata.find("localPackage")
        if local is None or local.attrib.get("path") != package:
            raise ValueError(f"Missing/mismatched installed SDK package: {package}")
        found = tuple(int(local.findtext(f"revision/{part}", "0")) for part in ("major", "minor", "micro"))
        if found != revision:
            raise ValueError(f"SDK package {package} has unexpected revision {found}")
    if not (sdk / "platforms" / "android-37.2" / "android.jar").is_file():
        raise ValueError("Selected compile platform android.jar is absent")
    if not (sdk / "licenses" / "android-sdk-license").is_file():
        raise ValueError("SDK license acceptance record is absent; a human must accept the applicable terms")
    return {
        "scope": "local build inputs only; not dependency, human-gate, Linux or device acceptance",
        "java": "21.0.12.1",
        "compile_sdk": "37.2",
        "build_tools": "37.0.0",
        "candidate_environment_isolated": True,
        "cache_locations_explicit": True,
    }


if __name__ == "__main__":
    try:
        print(json.dumps(check_environment(), indent=2))
    except (OSError, ValueError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        sys.exit(2)
