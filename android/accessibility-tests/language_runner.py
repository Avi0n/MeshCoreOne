"""AndroidOnly: WP-503 Current-source fixed-family audit using the existing local process/device lifecycle."""

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
import time
import xml.etree.ElementTree as ET

from language_binding import ROOT, FAMILIES, current_binding, request_for, verify_current_request
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
    SETTINGS = {
        "font": ("system", "font_scale", "2.0"),
        "ime": ("secure", "show_ime_with_hard_keyboard", "0"),
        "animator_duration_scale": ("global", "animator_duration_scale", "0"),
        "window_animation_scale": ("global", "window_animation_scale", "0"),
        "transition_animation_scale": ("global", "transition_animation_scale", "0"),
    }

    def __init__(self, config, api):
        self.config, self.api = config, api
        self.android_user = None
        self.profile_mutations = []

    def current_user(self):
        user = self.run("shell", "am", "get-current-user")
        require(re.fullmatch(r"[0-9]+", user) is not None, "Actual current Android user is unrecognized")
        require(user == "0", "This bounded audit requires the actual owner user0 before private or package access")
        return user

    def bind_user(self):
        user = self.current_user()
        require(self.android_user is None or self.android_user == user, "Current Android user changed; retain the bound lane")
        self.android_user = user
        return user

    def command(self, *arguments):
        # Connecting first must not turn an absent server into an implicit ADB launch.
        with socket.create_connection((self.config.host, self.config.port), timeout=2):
            pass
        if self.android_user is None and arguments not in (("shell", "am", "get-current-user"), ("get-state",)):
            self.bind_user()
        user = self.android_user
        if user is not None and arguments != ("shell", "am", "get-current-user"):
            require(self.current_user() == user, "Current Android user changed; no command may target another user")
            if arguments[0] in ("install", "uninstall"):
                arguments = (arguments[0], "--user", user, *arguments[1:])
            elif arguments[:3] in (("shell", "pm", "path"), ("shell", "am", "instrument")):
                arguments = (*arguments[:3], "--user", user, *arguments[3:])
            elif arguments == ("shell", "am force-stop " + APP):
                arguments = ("shell", "am", "force-stop", "--user", user, APP)
        return [
            str(self.config.adb), "-H", self.config.host, "-P", str(self.config.port),
            "-s", self.config.serial, *arguments,
        ]

    def shell(self, command):
        if self.android_user is not None:
            if command.startswith("settings "):
                command = "settings --user " + self.android_user + " " + command[len("settings "):]
            elif command.startswith("run-as " + APP + " "):
                command = "run-as " + APP + " --user " + self.android_user + " " + command[len("run-as " + APP + " "):]
        return self.run("shell", command)

    def confirm_original_absence(self, package):
        require(package in (APP, TEST), "Absence verification is restricted to the debug App/Test")
        lines = self.run("shell", "pm", "list", "packages", "--user", "all", "-u", package).splitlines()
        require(all(re.fullmatch(r"package:[A-Za-z0-9_.]+", line) for line in lines),
                "Package registration probe is unrecognized, not proven absence")
        require("package:" + package not in lines,
                "Package has an existing registration/retained data; do not treat it as a genuinely empty target")

    def confirm_unset_setting(self, namespace, key):
        lines = self.shell("settings list " + namespace).splitlines()
        require(all("=" in line and line.partition("=")[0] for line in lines),
                "Actual settings inventory is unrecognized, not proven absence")
        matches = [line for line in lines if line.partition("=")[0] == key]
        require(not matches, "Reported null is not confirmed absent (literal null or concurrent change): " + key)

    def app_language(self):
        user = self.bind_user()
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
            observed = self.run("shell", "cmd", "locale", "get-app-locales", APP, "--user", user)
            match = re.fullmatch(r"Locales for " + re.escape(APP) + " for user " + user + r" are \[([^\]\n]*)\]", observed)
            require(match is not None, "Actual LocaleManager shell observation is unrecognized")
            require(migrated or not language, "Pending stored app locale migration would violate the System-language fixture precondition")
            language = match[1]
        require(not language, "Actual app locale is not System; do not reset a user's language for this fixture")
        return language

    def capture_profile(self):
        require(self.run("get-state") == "device", "Actual ADB transport is not authenticated/ready")
        user = self.bind_user()
        result = {
            "user": user,
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
        for key, (namespace, name, _) in self.SETTINGS.items():
            if key not in result:
                result[key] = self.shell("settings get " + namespace + " " + name)
            if result[key] == "null":
                self.confirm_unset_setting(namespace, name)
        if self.api == 37:
            result["effective_font_scale"] = self.font_configuration()[1]
            require(0 < result["effective_font_scale"] < float("inf"),
                    "Actual effective font scale is not finite and positive")
        result["app_language"] = self.app_language() if self.installed(APP) is not None else None
        require(not self.active_instrumentations(), "Another instrumentation consumer is active")
        return result

    @staticmethod
    def override(profile, key):
        values = re.findall(r"Override " + key + r": ([^\r\n]+)", profile[key])
        require(len(values) <= 1, "Ambiguous actual display override: " + key)
        physical = re.findall(r"Physical " + key + r": ([^\r\n]+)", profile[key])
        require(len(physical) == 1, "Missing actual physical display observation: " + key)
        value = values[0] if values else None
        pattern = r"[1-9][0-9]*x[1-9][0-9]*" if key == "size" else r"[1-9][0-9]*"
        require(all(re.fullmatch(pattern, item) for item in physical + values), "Malformed actual display " + key)
        return value

    def profile(self):
        result = self.capture_profile()

        def require_scale(key, expected):
            try:
                observed = float(result[key])
            except ValueError as error:
                raise RuntimeError(f"Actual {key} setting is not numeric: {result[key]!r}; required {expected}") from error
            require(observed == expected, f"Actual {key} setting is {result[key]!r}; required {expected}")

        require_scale("font", 2)
        if self.api == 37:
            require(result["effective_font_scale"] == 2, "Actual effective font profile is not 200%")
        require(result["ime"] == "0", "Actual IME profile is not present")
        for key, label, expected in (("size", "size", "360x900"), ("density", "density", "160")):
            values = re.findall(r"(?:Physical|Override) " + label + r": ([^\n]+)", result[key])
            require(values and values[-1] == expected, "Actual compact " + label + " profile is not present")
        for key in ("animator_duration_scale", "window_animation_scale", "transition_animation_scale"):
            require_scale(key, 0)
        return result

    def profile_value(self, item):
        if item["kind"] == "display":
            return self.override({item["key"]: self.shell("wm " + item["key"])}, item["key"])
        value = self.shell("settings get " + item["namespace"] + " " + item["key"])
        if value == "null":
            self.confirm_unset_setting(item["namespace"], item["key"])
            return None
        return value

    def write_profile_value(self, item, value):
        if item["kind"] == "display":
            self.shell("wm " + item["key"] + " " + ("reset" if value is None else value))
        else:
            action = "delete" if value is None else "put"
            command = "settings " + action + " " + item["namespace"] + " " + item["key"]
            self.shell(command if value is None else command + " " + value)

    def font_configuration(self):
        observed = self.shell("dumpsys activity | grep -F mGlobalConfiguration")
        match = re.fullmatch(r"mGlobalConfiguration: \{([0-9]+(?:\.[0-9]+)?) [^\r\n]+\}", observed)
        require(match is not None, "Actual effective font configuration is unrecognized")
        return observed, float(match[1])

    def font_history(self):
        observed = self.shell("dumpsys settings | grep -F font_scale")
        rows, history = [], []
        for line in observed.splitlines():
            line = line.strip()
            if re.fullmatch(r"_id:[0-9]+ name:font_scale pkg:[A-Za-z0-9_.]+ value:[^\r\n]+", line):
                rows.append(line)
            else:
                match = re.fullmatch(
                    r"([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}) (update|delete) font_scale", line
                )
                require(match is not None, "Actual font-setting history is unrecognized")
                history.append((match[1], match[2]))
        require(len(rows) == 1, "Actual font-setting writer is ambiguous")
        return observed, rows[0], history

    def restore_unset_font(self, item, original, record, *, already_absent=False):
        self.profile_restoration_uncertain = True
        before, scale = self.font_configuration()
        require(scale in (1, 2), "Unexpected effective font; do not overwrite foreign configuration")
        previous = self.font_history()[2] if scale == 2 and not already_absent else None
        record({**item, "phase": "verify-already-unset-font" if already_absent else "delete-originally-unset-font",
                "effective_configuration_before": before})
        if not already_absent:
            self.write_profile_value(item, None)
        deadline = time.monotonic() + 5
        while True:
            actual = self.profile_value(item)
            configuration, current_scale = self.font_configuration()
            require(current_scale in (1, 2) and actual in (None, "1.0"),
                    "Unexpected font after owned deletion; retain the native lane")
            require(not already_absent or actual is None,
                    "Previously absent font changed without an owned deletion; do not overwrite it")
            if current_scale == 1 and (already_absent or scale == 1 or actual == "1.0"):
                break
            require(time.monotonic() < deadline, "Effective font restoration did not settle; retain the native lane")
            time.sleep(0.1)
        if actual == "1.0":
            require(scale == 2, "Uncorrelated font materialization; do not overwrite a foreign setting")
            metadata, row, history = self.font_history()
            require(re.fullmatch(
                r"_id:[0-9]+ name:font_scale pkg:android value:1\.0 default:1\.0 defaultSystemSet:true", row
            ) is not None, "Font materialization is not attributed to the Android framework")
            require(previous and len(history) > 2 and history[0][1] == "update" and history[1][1] == "delete"
                    and history[0][0] >= history[1][0] > previous[0][0]
                    and history[2:] == previous[:len(history) - 2],
                    "Font history does not correlate with this owned deletion")
            require(self.capture_profile() == {**original, "font": "1.0"},
                    "Other original state changed; do not retry font cleanup")
            record({**item, "phase": "single-framework-materialization-cleanup",
                    "framework_metadata": metadata, "effective_configuration": configuration})
            self.write_profile_value(item, None)
        for delay in (0.1, 0.2):
            time.sleep(delay)
            require(self.profile_value(item) is None and self.font_configuration()[1] == 1
                    and self.capture_profile() == original,
                    "Exact unset font/profile restoration is unresolved; retain the native lane")
        self.profile_restoration_uncertain = False

    def prepare_profile(self, original, record):
        require(self.capture_profile() == original, "Actual original profile changed before preparation")
        if self.api == 37:
            self.profile_restoration_uncertain = True
        plan = []
        for name, (namespace, key, value) in self.SETTINGS.items():
            previous = None if original[name] == "null" else original[name]
            if previous is not None:
                require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", previous) is not None,
                        "Actual profile setting is malformed: " + name)
            if previous is None or float(previous) != float(value):
                plan.append({"kind": "setting", "namespace": namespace, "key": key,
                             "original": previous, "temporary": value})
        for key, value in (("size", "360x900"), ("density", "160")):
            previous = self.override(original, key)
            effective = previous or re.search(r"Physical " + key + r": ([^\r\n]+)", original[key])[1]
            if effective != value:
                plan.append({"kind": "display", "key": key, "original": previous, "temporary": value})
        for item in plan:
            require(self.profile_value(item) == item["original"], "Actual profile changed before owned temporary setup")
            self.profile_mutations.append(item)
            record(item)
            self.write_profile_value(item, item["temporary"])

    def restore_profile(self, original, record):
        if self.api == 37:
            self.profile_restoration_uncertain = True
        errors = []

        def record_restore(item):
            try:
                record(item)
            except OSError as error:
                errors.append(error)

        for item in reversed(self.profile_mutations):
            record_restore(item)
            try:
                actual = self.profile_value(item)
                require(actual in (item["original"], item["temporary"]),
                        "Unexpected profile value; do not overwrite another consumer's setting")
                if self.api == 31 and item["key"] == "font_scale" and item["original"] is None:
                    self.restore_unset_font(item, original, record_restore, already_absent=actual is None)
                elif actual != item["original"]:
                    self.write_profile_value(item, item["original"])
            except (OSError, RuntimeError, subprocess.SubprocessError) as error:
                errors.append(error)
        try:
            if self.api == 37:
                scale = original.get("effective_font_scale")
                require(type(scale) is float and 0 < scale < float("inf"),
                        "Actual original effective font baseline is missing or invalid; retain the native lane")
            for delay in ((0, 0.1, 0.2) if self.api == 37 else (0,)):
                if delay:
                    time.sleep(delay)
                observed = self.capture_profile()
                if self.api == 37:
                    record_restore({"phase": "verify-original-effective-font", "delay_seconds": delay,
                                    "observed_profile": observed})
                require(observed == original, "Actual original runtime profile was not restored")
        except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as error:
            errors.append(error)
        if errors:
            raise ExceptionGroup("Original runtime profile restoration failed", errors)
        if self.api == 37:
            self.profile_restoration_uncertain = False


def readiness(request, config, ledger):
    policy = verify_current_request(request)
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
    """Retain the original entrypoint while sharing one lifecycle across the fixed families."""
    device = ProcessDevice(config, request.api) if device is None else device
    device.require_fixture_recovery()
    policy, state = readiness(request, config, ledger)
    manifest = load_manifest(ROOT)
    with execution_lane(
        ledger, device, lock_owner=request.operation_owner,
        canonical=request.binding.canonical_session, endpoint=config.endpoint,
        purpose=(f"language-api{request.api}-two-original-methods" if request.binding.family == "language"
                 else f"{request.binding.family}-api{request.api}-{len(request.identities)}-original-methods"),
        source=request.binding.source_revision,
    ):
        before = device.installed(APP)
        require(device.installed(TEST) is None, "Existing test package must not be overwritten")
        device.confirm_original_absence(TEST)
        device.original_test_absent = True
        if before is None:
            device.confirm_original_absence(APP)
        original_profile = device.capture_profile()
        device.original_runtime_profile = original_profile
        device.original_target_digest = before["sha256"] if before is not None else None
        device.original_target_absent = before is None
        config.output.mkdir()
        backup = None
        if before is not None:
            backup = config.output / "original-target.apk"
            device.run("pull", before["path"], str(backup), timeout=120)
            require(sha256(backup) == before["sha256"], "Actual original target APK preservation failed")
        packaging = assemble_debug_pair(
            manifest=manifest, policy=policy, ledger=ledger, owner=device.native_lane_owner,
            revision=request.binding.source_revision, state=state, debug_home=config.debug_home,
            installed_apk=backup, output=config.output / "packaging",
        )
        snapshot = packaging["installed_snapshot"]
        require(snapshot is None if before is None else
                snapshot is not None and snapshot["sha256"] == before["sha256"] and snapshot["package"] == APP,
                "Packaging original-presence snapshot differs from the actual observed target")
        paths = retained_pair(packaging, config.output, request)
        require((backup is None or has_original_schema(backup)) and has_original_schema(paths["app"]),
                "Actual preserved/current target Room schema compatibility is unverified")
        verify_current_request(request)
        require(device.capture_profile() == original_profile and device.installed(APP) == before and device.installed(TEST) is None,
                "Actual original profile/packages changed during packaging")
        device.confirm_original_absence(TEST)
        if before is None:
            device.confirm_original_absence(APP)
        result = {
            "binding": asdict(request.binding), "api": request.api, "identities": request.identities,
            "family": request.binding.family,
            "native_lane_operation_owner": device.native_lane_owner, "endpoint": config.endpoint,
            "original_profile": original_profile, "installed_before": before, "packaging": packaging,
            "preservation_mode": "originally-absent" if before is None else "originally-present",
            "mutation_attempts": [], "profile_setup_attempts": [], "profile_restoration_attempts": [],
            "acceptance": False,
        }
        operations = config.output / "operations.json"

        def save():
            operations.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

        def record_attempt(package):
            result["mutation_attempts"].append(package)
            save()

        def record_profile(item, restoring=False):
            result["profile_restoration_attempts" if restoring else "profile_setup_attempts"].append(item)
            save()

        save()
        try:
            device.prepare_profile(original_profile, record_profile)
            install_pair(device, paths, record_attempt)
            verify_current_request(request)
            profile = device.profile()
            result["profile_before"] = profile
            save()
            result["cohort"] = execute_selection(
                device, config.output, request.binding.family, profile,
                packaging["app"]["sha256"], packaging["tests"]["sha256"],
                expected=list(request.identities), api=request.api,
                source=request.binding.source_revision, android_tree=request.binding.android_tree,
                canonical=request.binding.canonical_session, endpoint=config.endpoint,
                stem_name=f"device-{request.binding.family}{request.api}",
                event_prefix="WP503_" + request.binding.family.upper(),
            )
        finally:
            primary = sys.exception()
            if primary is not None:
                result["execution_error"] = repr(primary)
            errors = []
            try:
                states, failures = restore_packages(
                    device, backup, device.original_target_digest,
                    packaging["app"]["sha256"], packaging["tests"]["sha256"],
                )
                result["cleanup"] = states
                errors.extend(failures)
                device.confirm_original_absence(TEST)
                if before is None:
                    device.confirm_original_absence(APP)
            except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
                errors.append(failure)
            try:
                device.restore_profile(original_profile, lambda item: record_profile(item, restoring=True))
                result["original_profile_restored"] = True
            except (OSError, RuntimeError, subprocess.SubprocessError, ValueError, ExceptionGroup) as failure:
                errors.append(failure)
            if getattr(device, "application_state_uncertain", False):
                result["application_state"] = "UNCERTAIN_NATIVE_LANE_RETAINED"
                result["fixture_recovery_receipt"] = str(device.fixture_recovery_path)
            if errors:
                result["cleanup_errors"] = [repr(error) for error in errors]
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
    parser.add_argument("--family", choices=tuple(FAMILIES), default="language")
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
        binding = (current_binding(args.head) if args.family == "language"
                   else current_binding(args.head, family=args.family))
        request = request_for(binding, args.api)
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
