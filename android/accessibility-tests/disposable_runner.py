"""AndroidOnly: WP-503 Consumer of the trusted WP-003 private-guest lifecycle, never shared-guest recovery."""

import argparse
from dataclasses import asdict, dataclass
from importlib import import_module
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback
from types import MappingProxyType

from language_binding import (
    FAMILIES, ROOT, current_binding, git, request_for, trusted_execution_policy,
    verify_current_request,
)
from language_runner import ProcessDevice, retained_pair
from shared_device_runner import APP, TEST, has_original_schema, install_pair, parse_outcomes, require
from controller.capabilities import CapabilityEngine
from controller.ci_environment import candidate_environment
from controller.errors import PortError
from controller.ledger import Ledger
from controller.model import load_manifest, tree
from controller.schema import load_json
from controller.staging import object_bytes
from local.reserve import shared_ledger

PRODUCER = "96642cf3c90effe4f2c90ef4202ca9d5d7764075"
PRODUCER_INPUTS = MappingProxyType({
    "tools/android-port/controller/disposable.py": "9e3aec3a58ca9f2246d09b0744346d020a6d1a9f",
    "tools/android-port/controller/windows_guest.py": "621612bb67e16432692e07a5a1b1056426747777",
    "tools/android-port/controller/ledger.py": "6048cf50b275b85c9028781fd80a473ef06ca6f2",
    "tools/android-port/local/debug_packaging.py": "7f8e5e9769c0f8d70a42678738280bf7209c17e9",
})
GUEST_ADAPTER = "tools/android-port/controller/disposable.py"
GUEST_OPERATION = "execute-private-disposable-avd"
INSTRUMENTATION_TIMEOUT = 180


def trusted_producer():
    """Inspect trusted-main objects before importing anything from the new producer."""
    authority = git("rev-parse", "refs/remotes/origin/main")
    policy = trusted_execution_policy()
    require(git("rev-parse", "refs/remotes/origin/main") == authority, "Trusted main changed")
    entries = tree(ROOT, authority)
    for path, blob in PRODUCER_INPUTS.items():
        if entries.get(path) != blob:
            raise PortError("Disposable producer is missing or differs from the reviewed trusted-main contract: " + path)
        actual = ROOT / path
        if (
            not actual.is_file() or actual.is_symlink()
            or actual.read_bytes().replace(b"\r\n", b"\n") != object_bytes(ROOT, blob)
        ):
            raise PortError("Disposable runtime is not the actual trusted-main checkout: " + path)
    manifest = load_manifest(ROOT)
    CapabilityEngine(policy).admit("WP-503", manifest.wp("WP-503")["write_paths"], GUEST_ADAPTER, GUEST_OPERATION)
    if policy.get("paused") is not False or "disposable-native-execution" not in policy["reservation_policy"]["hard_lock_resources"]:
        raise PortError("Trusted main has not admitted disposable execution")
    guest_module = import_module("controller.disposable")
    packaging = import_module("local.debug_packaging")
    for module, path in (
        (guest_module, GUEST_ADAPTER),
        (packaging, "tools/android-port/local/debug_packaging.py"),
        (sys.modules[Ledger.__module__], "tools/android-port/controller/ledger.py"),
    ):
        if Path(module.__file__).resolve() != (ROOT / path).resolve():
            raise PortError("Disposable consumer loaded a foreign producer runtime: " + path)
    return authority, policy, guest_module, packaging


@dataclass(frozen=True)
class DisposableConfig:
    parent: Path
    state: Path
    debug_home: Path
    output: Path
    emulator: Path
    adb: Path
    image: Path

    def validate(self, api):
        for value in asdict(self).values():
            if not isinstance(value, Path) or not value.is_absolute() or any(p.is_symlink() for p in (value, *value.parents)):
                raise PortError("Disposable inputs require explicit absolute nonsymlink paths")
        state = load_json(self.state)
        candidate_environment(state, local=True)
        sdk = Path(state["android_home"])
        suffix = ".exe" if os.name == "nt" else ""
        for actual, expected in (
            (self.adb, sdk / "platform-tools" / ("adb" + suffix)),
            (self.emulator, sdk / "emulator" / ("emulator" + suffix)),
        ):
            if not actual.is_file() or actual.resolve() != expected.resolve():
                raise PortError("Use only the declared installed SDK emulator/ADB")
        if (
            not self.parent.is_dir() or self.parent.resolve() != self.parent
            or self.parent.is_relative_to(ROOT.resolve())
            or self.output.exists() or not self.output.parent.is_dir()
            or self.output.resolve().is_relative_to(ROOT.resolve())
            or not self.debug_home.is_dir() or not (self.debug_home / "debug.keystore").is_file()
            or (self.debug_home / "debug.keystore").is_symlink()
            or not self.image.is_dir() or not self.image.resolve().is_relative_to((sdk / "system-images").resolve())
        ):
            raise PortError("Use a new external output, canonical private parent, existing debug identity and installed image")
        properties = self.image / "source.properties"
        if not properties.is_file() or properties.is_symlink():
            raise PortError("Installed image provenance is missing")
        values = dict(line.split("=", 1) for line in properties.read_text(encoding="utf-8").splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
        values = {key.strip(): value.strip() for key, value in values.items()}
        if values.get("AndroidVersion.ApiLevel", "").strip() not in (str(api), f"{api}.0"):
            raise PortError("Installed image API differs from the immutable selected family request")
        return state


def verify_owned_guest(guest, ledger, request, parent, authority, guest_type, *, phase):
    if type(guest) is not guest_type or type(ledger) is not Ledger:
        raise PortError("Require the actual producer PrivateGuest and canonical Ledger, not a receipt/label/subclass")
    identity = guest.identity_binding
    if (
        ledger.repository != request.binding.repository or ledger.path.resolve() != shared_ledger(ROOT).resolve()
        or guest.repository != ledger.repository or guest.owner != request.operation_owner
        or guest.revision != request.binding.source_revision or guest.phase != phase
        or not isinstance(guest.root, Path) or guest.root.parent != parent
        or not isinstance(identity, dict)
        or identity.get("session_id") != request.binding.canonical_session
        or identity.get("attempt") != request.binding.canonical_session
        or identity.get("trusted_revision") != authority
        or type(identity.get("reservation_revision")) is not int
        or identity["reservation_revision"] != request.binding.reservation_revision
    ):
        raise PortError("Disposable guest has a foreign/stale source, owner, session, reservation, root or ledger")


class PrivateDevice(ProcessDevice):
    def __init__(self, guest, ledger, request, parent):
        authority, _, module, _ = trusted_producer()
        verify_owned_guest(guest, ledger, request, parent, authority, module.PrivateGuest, phase="running")
        super().__init__(None, request.api)
        self.guest, self.ledger, self.request = guest, ledger, request
        self.guest_type = module.PrivateGuest
        self.native_lane_owner = request.operation_owner

    def require_fixture_recovery(self):
        if type(self.guest) is not self.guest_type:
            raise PortError("Private guest concrete identity changed")
        require(self.guest.owner == self.request.operation_owner and self.guest.revision == self.request.binding.source_revision,
                "Private guest owner/source changed")
        self.guest.assert_held(self.ledger, self.request.operation_owner)

    def command(self, *arguments):
        raise PortError("Private commands must use the producer's owned guest.adb boundary, never raw subprocess argv")

    def invoke(self, *arguments, timeout=30, on_exit=None):
        require(arguments and all(isinstance(value, str) for value in arguments), "Require explicit private command arguments")
        require(on_exit is None or arguments[:3] == ("shell", "am", "instrument"),
                "Only the actual instrumentation command can record its host exit")
        self.require_fixture_recovery()
        if self.android_user is None and arguments not in (("shell", "am", "get-current-user"), ("get-state",)):
            self.bind_user()
        user = self.android_user
        if user is not None and arguments != ("shell", "am", "get-current-user"):
            require(self.current_user() == user, "Private guest current user changed")
            if arguments[0] == "install":
                arguments = (arguments[0], "--user", user, *arguments[1:])
            elif arguments[:3] in (("shell", "pm", "path"), ("shell", "am", "instrument")):
                arguments = (*arguments[:3], "--user", user, *arguments[3:])
        try:
            result = self.guest.adb(self.ledger, list(arguments), timeout=timeout)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as failure:
            if on_exit is not None:
                code = failure.returncode if isinstance(failure, subprocess.CalledProcessError) else None
                on_exit(code, failure.output, failure.stderr, failure)
            raise
        require(type(result.returncode) is int and isinstance(result.stdout, bytes), "Malformed actual guest command result")
        if on_exit is not None:
            on_exit(result.returncode, result.stdout, result.stderr)
        result.check_returncode()
        return result

    def run(self, *arguments, timeout=30, absent=False):
        return self.invoke(*arguments, timeout=timeout).stdout.decode("utf-8", errors="strict").strip()

    def installed(self, package):
        require(package in (APP, TEST), "Private package observations are restricted to the debug App/Test")
        lines = self.run("shell", "pm", "list", "packages", "--user", "all", "-u", package).splitlines()
        require(all(re.fullmatch(r"package:[A-Za-z0-9_.]+", line) for line in lines), "Unrecognized private package inventory")
        if "package:" + package not in lines:
            return None
        return super().installed(package)


def wait_boot(guest, ledger, save_observation):
    deadline = time.monotonic() + 120
    remaining = deadline - time.monotonic()
    require(remaining > 0, "Private guest framework boot exceeded its120s deadline")
    transport = guest.adb(ledger, ["wait-for-device"], timeout=remaining)
    require(type(transport.returncode) is int and isinstance(transport.stdout, bytes),
            "Malformed actual private transport wait result")
    transport.check_returncode()
    while True:
        values = {}
        for name in ("sys.boot_completed", "init.svc.bootanim"):
            remaining = deadline - time.monotonic()
            require(remaining > 0, "Private guest framework boot exceeded its120s deadline")
            observed = guest.adb(ledger, ["shell", "getprop", name], timeout=min(30, remaining))
            require(type(observed.returncode) is int and isinstance(observed.stdout, bytes),
                    "Malformed actual private boot command result")
            observed.check_returncode()
            values[name] = observed.stdout.decode("utf-8", errors="strict").strip()
        save_observation(values)
        require(values["sys.boot_completed"] in ("", "0", "1")
                and values["init.svc.bootanim"] in ("", "running", "stopped"), "Unrecognized private boot observation")
        if values == {"sys.boot_completed": "1", "init.svc.bootanim": "stopped"}:
            require(time.monotonic() <= deadline, "Private guest boot observation exceeded its deadline")
            return
        require(time.monotonic() < deadline, "Private guest framework boot exceeded its120s deadline")
        time.sleep(0.1)


def save_operations(path, result):
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def instrument(device, request, output, result, save):
    raw_path = output / f"device-{request.binding.family}{request.api}.txt"
    stderr_path = output / f"device-{request.binding.family}{request.api}-stderr.txt"
    require(not raw_path.exists() and not stderr_path.exists(), "Never overwrite an existing native transcript")

    def retain_exit(code, raw, stderr, primary=None):
        result["host_instrumentation_process_exit"] = code
        errors = []
        try:
            save()
        except OSError as failure:
            errors.append(failure)
        for path, value in ((raw_path, raw), (stderr_path, stderr)):
            if value is None:
                continue
            if not isinstance(value, bytes):
                errors.append(PortError("Actual instrumentation stream is not bytes: " + str(path)))
                continue
            try:
                path.write_bytes(value)
            except OSError as failure:
                errors.append(failure)
        if errors:
            raise BaseExceptionGroup("Actual host exit/transcript retention failed", ([primary] if primary else []) + errors)

    actual = device.invoke(
        "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(request.identities),
        "-e", "expectedApi", str(request.api), TEST + "/androidx.test.runner.AndroidJUnitRunner",
        timeout=INSTRUMENTATION_TIMEOUT, on_exit=retain_exit,
    )
    summary = parse_outcomes(actual.stdout.decode("utf-8", errors="strict"), list(request.identities))
    result["cohort"] = summary
    save()
    logs = device.invoke("logcat", "-d", "-v", "raw", "-s", "System.out:I", "*:S", timeout=60).stdout
    (output / f"device-{request.binding.family}{request.api}-logcat.txt").write_bytes(logs)
    require(actual.returncode == 0 and summary["passed"] == len(request.identities),
            "Normal all-positive disposable instrumentation completion is unverified")


def execute_disposable(request, config):
    authority, trusted_policy, module, packaging_module = trusted_producer()
    policy = verify_current_request(request)
    require(policy == trusted_policy, "Disposable trusted policy changed before allocation")
    state = config.validate(request.api)
    ledger, guest = module.trusted_guest(
        ROOT, config.parent, owner=request.operation_owner, revision=request.binding.source_revision,
        session_id=request.binding.canonical_session, attempt=request.binding.canonical_session,
    )
    owned, output_owned = False, False
    result = {
        "binding": asdict(request.binding), "api": request.api, "family": request.binding.family,
        "original_identities": request.identities, "reviewed_producer": PRODUCER, "trusted_revision": authority,
        "preservation_mode": "fresh-private-guest-disposal-not-shared-state-restoration",
        "host_instrumentation_process_exit": None, "disposal_verified": False, "acceptance": False,
        "mutation_attempts": [], "profile_setup_attempts": [], "boot_observations": [],
    }
    operations = config.output / "operations.json"

    def save():
        require(output_owned, "Receipt writes require this attempt's exclusively created output directory")
        save_operations(operations, result)

    def record(key, value):
        result[key].append(value)
        save()

    try:
        verify_owned_guest(guest, ledger, request, config.parent, authority, module.PrivateGuest, phase="prepared")
        owned = True
        guest.assert_held(ledger, request.operation_owner)
        config.output.mkdir()
        output_owned = True
        result["disposable_guest"] = {"root": str(guest.root), "token": guest.token, "owner": guest.owner}
        save()
        packaging = packaging_module.assemble_debug_pair(
            manifest=load_manifest(ROOT), policy=policy, ledger=ledger, owner=request.operation_owner,
            revision=request.binding.source_revision, state=state, debug_home=config.debug_home,
            installed_apk=None, output=config.output / "packaging", guest=guest,
        )
        require(packaging["installed_snapshot"] is None and packaging["disposable_guest"] == {
            "root": str(guest.root), "token": guest.token, "revision": guest.revision,
        }, "Packaging does not bind this actual fresh private guest")
        packaging_module.verify_pair(None, packaging["app"], packaging["tests"])
        paths = retained_pair(packaging, config.output, request)
        require(has_original_schema(paths["app"]), "Actual source-built target schema is unverified")
        result["packaging"] = packaging
        save()
        verify_current_request(request)
        guest.start(ledger, emulator=config.emulator, adb=config.adb, image=config.image)
        wait_boot(guest, ledger, lambda value: record("boot_observations", value))
        device = PrivateDevice(guest, ledger, request, config.parent)
        result["endpoint"] = f"127.0.0.1:{guest.server_port}/emulator-{guest.port}"
        require(device.installed(APP) is None and device.installed(TEST) is None, "Private guest target was not genuinely fresh")
        device.confirm_original_absence(APP)
        device.confirm_original_absence(TEST)
        initial_profile = device.capture_profile()
        result["initial_private_profile"] = initial_profile
        device.prepare_profile(initial_profile, lambda value: record("profile_setup_attempts", value))
        verify_current_request(request)
        install_pair(device, paths, lambda package: record("mutation_attempts", package))
        result["profile_before"] = device.profile()
        require(device.installed(APP)["sha256"] == packaging["app"]["sha256"]
                and device.installed(TEST)["sha256"] == packaging["tests"]["sha256"], "Private installed App/Test source changed")
        verify_current_request(request)
        save()
        instrument(device, request, config.output, result, save)
    finally:
        primary, errors = sys.exception(), []
        if primary is not None:
            result["execution_error"] = repr(primary)
        if owned:
            try:
                if output_owned:
                    save()
            except OSError as failure:
                errors.append(failure)
            try:
                guest.dispose(ledger)
                guest.verify_disposed()
                result["disposal_verified"] = True
            except BaseException as failure:
                result["disposal_error"] = repr(failure)
                errors.append(failure)
            try:
                if output_owned:
                    save()
            except OSError as failure:
                errors.append(failure)
        if errors:
            raise BaseExceptionGroup("Disposable execution/disposal evidence failed", ([primary] if primary else []) + errors)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--head", required=True)
    parser.add_argument("--api", required=True, type=int, choices=(31, 37))
    parser.add_argument("--family", choices=tuple(FAMILIES), required=True)
    parser.add_argument("--execute", action="store_true", required=True)
    for name in ("parent", "state", "debug-home", "output", "emulator", "adb", "image"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        request = request_for(current_binding(args.head, family=args.family), args.api)
        config = DisposableConfig(args.parent, args.state, args.debug_home, args.output, args.emulator, args.adb, args.image)
        print(json.dumps(execute_disposable(request, config), indent=2))
        return 0
    except (PortError, OSError, RuntimeError, ValueError, KeyError, TypeError, subprocess.SubprocessError, ExceptionGroup) as failure:
        print(f"BLOCKED: {type(failure).__name__}: {failure}", file=sys.stderr)
        traceback.print_exception(failure)
        return 2


if __name__ == "__main__":
    sys.exit(main())
