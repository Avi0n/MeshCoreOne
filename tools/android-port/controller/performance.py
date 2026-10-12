"""AndroidOnly: WP-504 Build/discovery checks; never a measured-budget or device acceptance receipt."""

import re
import subprocess
from pathlib import Path

from .apk_alignment import inspect_alignment
from .ci_evidence import suite_counts
from .errors import PortError

FIXTURE = "com.meshcoreone.android.benchmarktarget.WorkloadActivity"


def verify_manifest(badging: str, tree: str, *, benchmark: bool, profile_source: bool = False,
                    application_profile_input: bool = False):
    package = "com.meshcoreone.android" + (".benchmark" if benchmark else "")
    if not re.search(r"^package: name='" + re.escape(package) + r"'", badging, re.MULTILINE):
        raise PortError("WP-504 APK package mismatch")
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.MULTILINE)
    if minimum is None or target is None or (int(minimum.group(1)), int(target.group(1))) != (31, 37):
        raise PortError("WP-504 APK SDK bounds mismatch")
    if re.search(r"android:debuggable[^=\n]*=.*0xffffffff", tree):
        raise PortError("WP-504 target is debuggable, not a release measurement")
    if benchmark:
        if FIXTURE not in tree:
            raise PortError("Missing executable benchmark fixture activity")
        if not re.search(r"android:testOnly[^=\n]*=.*0xffffffff", tree):
            raise PortError("Benchmark APK is not marked test-only")
        profileable = re.search(r"E: profileable.*?(?=\n\s*E:|\Z)", tree, re.DOTALL)
        if not profileable or not re.search(r"android:shell[^=\n]*=.*0xffffffff", profileable.group()):
            raise PortError("Benchmark target is not shell-profileable")
        metadata = re.findall(r"E: meta-data.*?(?=\n\s*E:|\Z)", tree, re.DOTALL)
        for name, enabled in (("wp504.profileSource", profile_source),
                              ("wp504.applicationProfileInput", application_profile_input)):
            identities = [element for element in metadata if f'"{name}"' in element]
            expected = "0xffffffff" if enabled else "0x0"
            if len(identities) != 1 or not re.search(
                    r"android:value[^=\n]*=.*\(type 0x12\)" + expected + r"(?:\s|$)", identities[0]):
                raise PortError(f"Missing/wrong typed variant identity: {name}")
    elif FIXTURE in tree or "E: profileable" in tree or "wp504." in tree:
        raise PortError("Test fixture/profileable overlay leaked into release")
    elif re.search(r"android:testOnly[^=\n]*=.*0xffffffff", tree):
        raise PortError("Ordinary release must not be test-only")
    return {"package": package, "min_sdk": 31, "target_sdk": 37, "debuggable": False}


def verify_build(repo: Path, sdk: Path, environment: dict):
    android = repo / "android"
    units = suite_counts(android / "benchmark/build/test-results/testDebugUnitTest", 8)
    windows = __import__("os").name == "nt"
    aapt = sdk / "build-tools" / "37.0.0" / ("aapt2.exe" if windows else "aapt2")
    artifacts = {}
    for variant in ("benchmark", "profile", "release"):
        directory = android / "app/build/outputs/apk" / variant
        apks = list(directory.glob("*.apk"))
        if len(apks) != 1:
            raise PortError(f"Missing/ambiguous {variant} APK")
        if variant != "profile":
            mapping = android / "app/build/outputs/mapping" / variant / "mapping.txt"
            if not mapping.is_file() or mapping.stat().st_size == 0:
                raise PortError(f"Missing actual R8 {variant} mapping")
            mapping_text = mapping.read_text(encoding="utf-8")
            if "# compiler: R8" not in mapping_text:
                raise PortError(f"{variant} mapping was not produced by R8")
            if variant == "release" and "com.meshcoreone.android.benchmark" in mapping_text:
                raise PortError("Workload code leaked into ordinary release R8 inputs")
        def dump(*arguments):
            return subprocess.check_output([str(aapt), "dump", *arguments], env=environment, text=True, timeout=60)
        manifest = verify_manifest(
            dump("badging", str(apks[0])),
            dump("xmltree", str(apks[0]), "--file", "AndroidManifest.xml"),
            benchmark=variant != "release",
            profile_source=variant == "profile",
            application_profile_input=(android / "app/src/main/baseline-prof.txt").is_file(),
        )
        alignment = inspect_alignment(apks[0], sdk, environment, windows=windows)
        if not any(name.endswith("/libmaplibre.so") for name in alignment["elf_pt_load_alignment"]):
            raise PortError(f"No actual MapLibre ELF in {variant} APK")
        artifacts[variant] = {**manifest, **alignment}
    producers = list((android / "benchmark/build/outputs/apk/androidTest/debug").glob("*.apk"))
    if len(producers) != 1:
        raise PortError("Missing/ambiguous compiled instrumentation producer")
    producer_alignment = inspect_alignment(producers[0], sdk, environment, windows=windows)
    if not producer_alignment["elf_pt_load_alignment"]:
        raise PortError("Missing actual native instrumentation libraries")
    return {
        "scope": "WP-504 build infrastructure only",
        "dataset_assertions": units,
        "artifacts": artifacts,
        "instrumentation_producer_compiled": True,
        "instrumentation_producer_alignment": producer_alignment,
        "performance_measured": False,
        "baseline_profiles_collected": False,
        "device_battery_anr_or_strictmode_verified": False,
    }
