"""AndroidOnly: WP-503 Current-source language audit using the existing local process/device lifecycle."""

import argparse
from dataclasses import asdict, dataclass
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import xml.etree.ElementTree as ET

from language_binding import ROOT, current_binding, request_for, verify_current_request
from shared_device_runner import (
    APP, TEST, Device, execute_selection, execution_lane, has_original_schema,
    install_pair, require, restore_packages, sha256,
)
from controller.ci_environment import candidate_environment
from controller.errors import PortError
from controller.ledger import Ledger
from controller.model import load_manifest
from controller.schema import load_json
from local.debug_packaging import assemble_debug_pair
from local.reserve import shared_ledger


@dataclass(frozen=True)
class RunConfig:
    adb: Path
    host: str
    port: int
    serial: str
    state: Path
    debug_home: Path
    output: Path

    @property
    def endpoint(self):
        return f"{self.host}:{self.port}/{self.serial}"

    def validate(self):
        for name in ("adb", "state", "debug_home", "output"):
            if not isinstance(getattr(self, name), Path):
                raise PortError("Missing explicit current process configuration: " + name)
        try:
            local = ipaddress.ip_address(self.host).is_loopback
        except ValueError as error:
            raise PortError("Use an explicit existing loopback ADB endpoint") from error
        if not local or type(self.port) is not int or not 1 <= self.port <= 65535:
            raise PortError("Invalid local ADB endpoint")
        if not isinstance(self.serial, str) or re.fullmatch(r"emulator-\d+", self.serial) is None:
            raise PortError("This bounded runner requires an explicit current emulator transport, not a physical device")
        state = load_json(self.state)
        candidate_environment(state, local=True)
        expected_adb = Path(state["android_home"]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if not self.adb.is_absolute() or self.adb.is_symlink() or not self.adb.is_file() or self.adb.resolve() != expected_adb.resolve():
            raise PortError("ADB must be the existing executable in the declared SDK, not an arbitrary command")
        if (
            not self.debug_home.is_absolute() or not self.debug_home.is_dir()
            or self.debug_home.is_symlink()
            or not (self.debug_home / "debug.keystore").is_file()
            or (self.debug_home / "debug.keystore").is_symlink()
        ):
            raise PortError("Existing default local debug identity metadata is required; never create or copy a key")
        if (
            not self.output.is_absolute() or self.output.exists()
            or not self.output.parent.is_dir()
            or self.output.resolve().is_relative_to(ROOT.resolve())
            or any(parent.is_symlink() for parent in self.output.parents)
        ):
            raise PortError("Use a new private output directory outside the checkout; never overwrite an old run")
        return state


class ProcessDevice(Device):
    def __init__(self, config, api):
        self.config, self.api = config, api

    def command(self, *arguments):
        # Connecting first must not turn an absent server into an implicit ADB launch.
        with socket.create_connection((self.config.host, self.config.port), timeout=2):
            pass
        return [
            str(self.config.adb), "-H", self.config.host, "-P", str(self.config.port),
            "-s", self.config.serial, *arguments,
        ]

    def app_language(self):
        # Read only this debug app's locale preference, never keys or a private-data archive.
        stored = self.shell(
            "run-as " + APP + " sh -c 'test -r . && test -x . || exit 1; "
            "if [ -d shared_prefs ]; then test -r shared_prefs && test -x shared_prefs || exit 1; "
            "elif [ -e shared_prefs ]; then exit 1; else echo WP503_NO_LANGUAGE_PREFS; exit; fi; "
            "if [ -f shared_prefs/app_language.xml ]; then cat shared_prefs/app_language.xml; "
            "elif [ -e shared_prefs/app_language.xml ]; then exit 1; else echo WP503_NO_LANGUAGE_PREFS; fi'"
        )
        language, migrated = "", False
        if stored != "WP503_NO_LANGUAGE_PREFS":
            try:
                root = ET.fromstring(stored)
            except ET.ParseError as error:
                raise RuntimeError("Actual app_language preference XML is malformed") from error
            require(root.tag == "map", "Actual app_language preferences have an unknown shape")
            for name, kind in (("language", "string"), ("native_migrated", "boolean")):
                values = [item for item in root if item.get("name") == name]
                require(len(values) <= 1 and all(item.tag == kind for item in values),
                        "Actual app_language preference type/identity is ambiguous")
                if values and name == "language":
                    language = values[0].text or ""
                if values and name == "native_migrated":
                    require(values[0].get("value") in ("true", "false"), "Malformed native locale migration state")
                    migrated = values[0].get("value") == "true"
        if self.api >= 33:
            observed = self.run("shell", "cmd", "locale", "get-app-locales", APP, "--user", "current")
            match = re.fullmatch(r"Locales for " + re.escape(APP) + r" for user \d+ are \[([^\]\n]*)\]", observed)
            require(match is not None, "Actual LocaleManager shell observation is unrecognized")
            require(migrated or not language, "Pending stored app locale migration would violate the System-language fixture precondition")
            language = match[1]
        require(not language, "Actual app locale is not System; do not reset a user's language for this fixture")
        return language

    def profile(self):
        require(self.run("get-state") == "device", "Actual ADB transport is not authenticated/ready")
        result = {
            "sdk": self.shell("getprop ro.build.version.sdk"),
            "release": self.shell("getprop ro.build.version.release"),
            "boot": self.shell("getprop sys.boot_completed"),
            "bootanim": self.shell("getprop init.svc.bootanim"),
            "cpus": self.shell("getconf _NPROCESSORS_ONLN"),
            "font": self.shell("settings get system font_scale"),
            "ime": self.shell("settings get secure show_ime_with_hard_keyboard"),
            "size": self.shell("wm size"), "density": self.shell("wm density"),
            "fingerprint": self.shell("getprop ro.build.fingerprint"),
        }
        require(result["sdk"] == str(self.api), "Actual device API does not match the selected language cohort")
        require(result["boot"] == "1" and result["bootanim"] == "stopped", "Actual device boot is incomplete")
        require(result["cpus"].isdigit() and int(result["cpus"]) > 0 and result["fingerprint"],
                "Actual runtime identity/CPU observation is missing")
        require(float(result["font"]) == 2 and result["ime"] == "0", "Actual font200/IME profile is not present")
        for key, label, expected in (("size", "size", "360x900"), ("density", "density", "160")):
            values = re.findall(r"(?:Physical|Override) " + label + r": ([^\n]+)", result[key])
            require(values and values[-1] == expected, "Actual compact " + label + " profile is not present")
        for key in ("animator_duration_scale", "window_animation_scale", "transition_animation_scale"):
            result[key] = self.shell("settings get global " + key)
            require(float(result[key]) == 0, "Actual reduced-motion profile is not present: " + key)
        require(not self.active_instrumentations(), "Another instrumentation consumer is active")
        result["app_language"] = self.app_language()
        return result


def readiness(request, config, ledger):
    verify_current_request(request)
    policy = load_json(ROOT / "docs/android/automation-policy.json")
    if type(policy.get("paused")) is not bool:
        raise PortError("Malformed actual pause state")
    if policy["paused"]:
        raise PortError("Native operation paused: docs/android/automation-policy.json paused=true; no lane or device command started")
    if ledger.repository != request.binding.repository:
        raise PortError("Actual shared ledger belongs to a different repository")
    for lock in ledger.hard_lock_records():
        if lock["resource"] in ("native-execution", "gradle-execution"):
            raise PortError(f"Actual {lock['resource']} lane is held by {lock['owner']}; do not replace or release it")
    state = config.validate()
    return policy, state


def retained_pair(packaging, directory, request):
    require(
        packaging["source_revision"] == request.binding.source_revision
        and packaging["android_source_tree_sha256"] == request.binding.android_source_tree_sha256
        and packaging["owner"] == request.operation_owner
        and packaging["manifest_sha256"] == request.binding.manifest_sha256
        and packaging["policy_revision"] == request.binding.policy_revision,
        "Actual debug packaging differs from this exact source/owner/policy binding",
    )
    paths = {}
    for label in ("app", "tests"):
        original = Path(packaging[label]["path"])
        target = directory / ("current-" + label + ".apk")
        require(not target.exists() and sha256(original) == packaging[label]["sha256"],
                "Actual packaged APK changed before retention")
        shutil.copyfile(original, target)
        require(sha256(target) == sha256(original) == packaging[label]["sha256"], "Packaged APK changed during retention")
        paths["test" if label == "tests" else label] = target
    return paths


def execute_language(request, config, ledger, *, device=None):
    policy, state = readiness(request, config, ledger)
    device = ProcessDevice(config, request.api) if device is None else device
    manifest = load_manifest(ROOT)
    with execution_lane(
        ledger, device, lock_owner=request.operation_owner,
        canonical=request.binding.canonical_session, endpoint=config.endpoint,
        purpose=f"language-api{request.api}-two-original-methods", source=request.binding.source_revision,
    ):
        profile = device.profile()
        device.original_runtime_profile = profile
        before = device.installed(APP)
        require(before is not None, "Existing user target must be preserved; no fresh install/data fallback")
        require(device.installed(TEST) is None, "Existing test package must not be overwritten")
        device.original_target_digest = before["sha256"]
        config.output.mkdir()
        backup = config.output / "original-target.apk"
        device.run("pull", before["path"], str(backup), timeout=120)
        require(sha256(backup) == before["sha256"], "Actual original target APK preservation failed")
        packaging = assemble_debug_pair(
            manifest=manifest, policy=policy, ledger=ledger, owner=device.native_lane_owner,
            revision=request.binding.source_revision, state=state, debug_home=config.debug_home,
            installed_apk=backup, output=config.output / "packaging",
        )
        paths = retained_pair(packaging, config.output, request)
        require(has_original_schema(backup) and has_original_schema(paths["app"]),
                "Actual preserved/current target Room schema compatibility is unverified")
        verify_current_request(request)
        require(device.profile() == profile and device.installed(APP) == before and device.installed(TEST) is None,
                "Actual original profile/packages changed during packaging")
        result = {
            "binding": asdict(request.binding), "api": request.api, "identities": request.identities,
            "native_lane_operation_owner": device.native_lane_owner, "endpoint": config.endpoint,
            "profile_before": profile, "installed_before": before, "packaging": packaging,
            "mutation_attempts": [], "acceptance": False,
        }
        operations = config.output / "operations.json"

        def save():
            operations.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

        def record_attempt(package):
            result["mutation_attempts"].append(package)
            save()

        save()
        try:
            install_pair(device, paths, record_attempt)
            verify_current_request(request)
            result["cohort"] = execute_selection(
                device, config.output, "language", profile,
                packaging["app"]["sha256"], packaging["tests"]["sha256"],
                expected=list(request.identities), api=request.api,
                source=request.binding.source_revision, android_tree=request.binding.android_tree,
                canonical=request.binding.canonical_session, endpoint=config.endpoint,
                stem_name=f"device-language{request.api}", event_prefix="WP503_LANGUAGE",
            )
        finally:
            primary = sys.exception()
            if primary is not None:
                result["execution_error"] = repr(primary)
            errors = []
            try:
                states, failures = restore_packages(
                    device, backup, before["sha256"],
                    packaging["app"]["sha256"], packaging["tests"]["sha256"],
                )
                result["cleanup"] = states
                errors.extend(failures)
            except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
                errors.append(failure)
            try:
                require(device.profile() == profile, "Original runtime profile was not restored")
            except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as failure:
                errors.append(failure)
            if getattr(device, "application_state_uncertain", False):
                result["application_state"] = "UNCERTAIN_NATIVE_LANE_RETAINED"
                result["fixture_recovery_receipt"] = str(device.fixture_recovery_path)
            try:
                save()
            except OSError as failure:
                errors.append(failure)
            if errors:
                raise BaseExceptionGroup("Language execution/cleanup failed", ([primary] if primary else []) + errors)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--head", required=True)
    parser.add_argument("--api", required=True, type=int, choices=(31, 37))
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--readiness", action="store_true")
    modes.add_argument("--execute", action="store_true")
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--serial")
    parser.add_argument("--state", type=Path)
    parser.add_argument("--debug-home", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        request = request_for(current_binding(args.head), args.api)
        config = RunConfig(args.adb, args.host, args.port, args.serial, args.state, args.debug_home, args.output)
        ledger = Ledger(shared_ledger(ROOT), request.binding.repository)
        if args.execute:
            result = execute_language(request, config, ledger)
        else:
            readiness(request, config, ledger)
            result = {
                "binding": asdict(request.binding), "api": request.api, "identities": request.identities,
                "native_lane_operation_owner": request.operation_owner,
                "host_source_configuration_checked": True,
                "device_authentication_profile_and_public_certificates": "checked inside held lane on execution, not yet observed",
                "native_starts": 0, "acceptance": False,
            }
        print(json.dumps(result, indent=2))
        return 0
    except (PortError, OSError, RuntimeError, ValueError, KeyError, TypeError, subprocess.SubprocessError, ExceptionGroup) as error:
        def report(failure):
            if isinstance(failure, BaseExceptionGroup):
                for nested in failure.exceptions:
                    report(nested)
            else:
                print(f"BLOCKED: {type(failure).__name__}: {failure}", file=sys.stderr)
                if isinstance(failure, subprocess.CalledProcessError) and failure.stderr:
                    detail = failure.stderr
                    print(detail.decode("utf-8", errors="replace") if isinstance(detail, bytes) else detail, file=sys.stderr)
        report(error)
        return 2


if __name__ == "__main__":
    sys.exit(main())
