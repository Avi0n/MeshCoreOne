"""AndroidOnly: WP-503 Missing-only API37 audit on an already running, source-bound device."""
import argparse
from collections import Counter
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path, PureWindowsPath
import re
import socket
import subprocess
import sys
import time
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[2]
HEAD = "3f39a777376e322e2bc533f1d1caf92a87a44ed8"
TREE = "7a9dd08e005e1e9cad79405dafecf325158d8b0c"
OWNER = "1666502a-0420-46ef-930f-e78651ba8b9f"
ADB = r"C:\Users\camer\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SIGNER = PureWindowsPath(ADB).parents[1] / "build-tools" / "37.0.0" / "apksigner.bat"
SERIAL = "emulator-5556"
APP = "com.meshcoreone.android.debug"
TEST = APP + ".test"
NAMESPACE = "com.meshcoreone.android.accessibility."
SCHEMA = b"847554c8d7ca15f1f881ba6bccce4121"
COHORTS = {
    "comparator-us": ("NativeLocalizedCompareParityTest", None, 11),
    "comparator-tr-az": ("NativeLocalizedCompareLocalePreservationTest", None, 2),
    "diagnostics": ("NativeDiagnosticsFlowAccessibilityTest", None, 5),
    "nodes": ("NativeNodesFlowAccessibilityTest", None, 7),
    "los": ("NativeLineOfSightAccessibilityTest",
            "savedRepeatersExposeNamedNativeActionsForNonGestureEndpointSelectionAtFont200", 1),
    "chat": ("NativeLauncherFocusAccessibilityTest",
             "realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200", 1),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def certificate(apk):
    output = subprocess.check_output(
        [str(SIGNER), "verify", "--print-certs", str(apk)], text=True, stderr=subprocess.STDOUT,
    )
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", output)
    require(len(digests) == 1, "Require one verified actual APK signing identity")
    return digests[0]


def install_succeeded(output):
    lines = output.splitlines()
    require(lines in (["Success"], ["Performing Streamed Install", "Success"]),
            "APK install did not succeed: " + output)


def install_pair(device, paths, record_attempt):
    device.require_fixture_recovery()
    for package, kind in ((APP, "app"), (TEST, "test")):
        record_attempt(package)
        device.install(paths[kind])


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def selected_methods(cohort):
    klass, method, count = COHORTS[cohort]
    source = "android/accessibility-tests/src/androidTest/kotlin/" + NAMESPACE.replace(".", "/") + klass + ".kt"
    body = subprocess.check_output(["git", "-C", str(ROOT), "show", HEAD + ":" + source], text=True)
    methods = re.findall(r"@Test fun (\w+)\(", body) if method is None else [method]
    require(len(methods) == len(set(methods)) == count, "Original selector count changed")
    require(all("@Test fun " + name + "(" in body for name in methods), "Original method missing")
    return [NAMESPACE + klass + "#" + name for name in methods]


def parse_outcomes(text, expected):
    text = text.replace("\r\n", "\n")
    require(not re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|INSTRUMENTATION_RESULT: (?:shortMsg|longMsg)="
                          r"|INSTRUMENTATION_STATUS: Error=|Process crashed", text),
            "Instrumentation runner failed or crashed after/before method outcomes")
    require(re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)$", text, re.M) == ["-1"] and
            text.rstrip().endswith("INSTRUMENTATION_CODE: -1") and
            "INSTRUMENTATION_RESULT: stream=" in text and re.search(r"Time: [\d.]+", text),
            "Missing or unsuccessful terminal instrumentation completion")
    starts, ends = [], []
    for match in re.finditer(r"(.*?)INSTRUMENTATION_STATUS_CODE: (-?\d+)", text, re.S):
        fields = dict(re.findall(r"^INSTRUMENTATION_STATUS: (class|test|numtests)=(.*)$", match[1], re.M))
        if "test" not in fields:
            continue
        require(int(fields["numtests"]) == len(expected), "Wrong actual discovered test count")
        identity = fields["class"] + "#" + fields["test"]
        code = int(match[2])
        if code == 1:
            starts.append(identity)
        else:
            ends.append((identity, code))
    require(len(starts) == len(set(starts)) == len(ends) == len(expected) > 0,
            "Missing, duplicate or zero actual discovery/outcomes")
    require(set(starts) == set(expected) == {identity for identity, _ in ends},
            "Actual test identities do not match the missing-only selection")
    require(len({identity for identity, _ in ends}) == len(ends), "Duplicate outcomes")
    require(all(code in (0, -1, -2, -3, -4) for _, code in ends), "Unknown AndroidJUnit status")
    statuses = Counter(code for _, code in ends)
    if statuses[0] == len(expected):
        require(re.findall(r"OK \((\d+) tests?\)", text) == [str(len(expected))],
                "Terminal JUnit success summary does not match actual selected outcomes")
    return {
        "discovered": len(starts), "started": len(starts), "matching_outcomes": len(ends),
        "passed": statuses[0], "failed": statuses[-2], "errors": statuses[-1],
        "skipped": statuses[-3] + statuses[-4], "outcomes": ends,
        "seconds": re.findall(r"Time: ([\d.]+)", text),
        "runner_completed": True, "terminal_instrumentation_code": -1,
    }


class Device:
    def require_fixture_recovery(self):
        raise RuntimeError(
            "Native mutation admission blocked: genuine pre-mutation fixture/preferences/window "
            "originals and a supported snapshot-based recovery operation are unavailable"
        )

    def command(self, *args):
        # Never let an absent historical server turn into an implicit server launch.
        with socket.create_connection(("127.0.0.1", 5037), timeout=2):
            pass
        return [ADB, "-H", "127.0.0.1", "-P", "5037", "-s", SERIAL, *args]

    def run(self, *args, timeout=30, absent=False):
        result = subprocess.run(self.command(*args), capture_output=True, timeout=timeout)
        if absent and result.returncode == 1 and not result.stdout.strip() and not result.stderr.strip():
            return ""
        result.check_returncode()
        return result.stdout.decode("utf-8").strip()

    def shell(self, command):
        return self.run("shell", command)

    def installed(self, package):
        value = self.run("shell", "pm", "path", package, absent=True)
        if not value:
            return None
        require(value.startswith("package:") and len(value.splitlines()) == 1,
                "Split or malformed installed APK requires separate preservation support")
        path = value.removeprefix("package:")
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_~+/=.\-]+/base\.apk", path), "Unexpected APK path")
        return {"path": path, "sha256": self.shell("sha256sum '" + path + "'").split()[0]}

    def profile(self):
        require(self.run("get-state") == "device", "Existing API37 transport is not authorized/device")
        values = {
            "sdk": self.shell("getprop ro.build.version.sdk"),
            "boot": self.shell("getprop sys.boot_completed"),
            "bootanim": self.shell("getprop init.svc.bootanim"),
            "cpus": self.shell("getconf _NPROCESSORS_ONLN"),
            "font": self.shell("settings get system font_scale"),
            "ime": self.shell("settings get secure show_ime_with_hard_keyboard"),
            "size": self.shell("wm size"),
            "density": self.shell("wm density"),
            "fingerprint": self.shell("getprop ro.build.fingerprint"),
            "avd": self.run("emu", "avd", "name").splitlines()[0],
        }
        require(values["sdk"] == "37" and values["boot"] == "1" and values["bootanim"] == "stopped",
                "Actual API37 boot readiness failed")
        require(values["cpus"] == "4", "Observed four-CPU profile changed; do not relabel it")
        require(float(values["font"]) == 2 and values["ime"] == "0", "Admitted font/unchanged IME profile changed")
        require(values["avd"] == "Pixel_10_Pro_XL", "Wrong existing AVD")
        require("Override size: 360x900" in values["size"] and "Override density: 160" in values["density"],
                "Original compact geometry profile is not present")
        for key in ("animator_duration_scale", "window_animation_scale", "transition_animation_scale"):
            values[key] = self.shell("settings get global " + key)
            require(float(values[key]) == 0, "Original reduced-motion profile is not present: " + key)
        require(not self.active_instrumentations(), "Another actual instrumentation consumer is active")
        return values

    def install(self, path):
        install_succeeded(self.run("install", "-r", str(path), timeout=120))

    def active_instrumentations(self):
        output = self.shell("dumpsys activity")
        entries = re.findall(r"ActiveInstrumentation\{[^\n]*\}", output)
        require(entries or not re.search(r"active instrumentation:", output, re.I),
                "Unrecognized active instrumentation inventory; do not restore APK or release lane")
        return entries

    def mark_application_state_uncertain(self, reason):
        self.application_state_uncertain = True
        self.fixture_completion_untrusted = True
        context = getattr(self, "fixture_recovery_context", None)
        require(context is not None, "No bound fixture recovery receipt; retain lane without stopping guest")
        context["status"] = "UNCERTAIN_APPLICATION_STATE_NATIVE_LANE_RETAINED"
        context.setdefault("reasons", []).append(reason)
        context["fixture_preferences_and_window_restoration_verified"] = False
        self.fixture_recovery_path.write_text(json.dumps(context, indent=2) + "\n")

    def finish_owned_instrumentation(self, app_digest, test_digest):
        active = self.active_instrumentations()
        if not active:
            return "verified-quiescent"
        component = TEST + "/androidx.test.runner.AndroidJUnitRunner"
        require(all(re.search(r"\s" + re.escape(component) + r"(?:\s|\})", entry) for entry in active),
                "Foreign instrumentation observed; no unrelated guest process will be stopped")
        require(self.installed(APP)["sha256"] == app_digest and self.installed(TEST)["sha256"] == test_digest,
                "Active guest no longer matches this lane's exact owned App/Test APKs")
        errors = []
        try:
            self.mark_application_state_uncertain(
                "Owned guest must be force-stopped; Kotlin fixture finally may not execute")
        except (RuntimeError, OSError) as receipt_failure:
            errors.append(receipt_failure)
        # The lane was empty before our invocation; stop only its verified instrumented target.
        try:
            self.shell("am force-stop " + APP)
            deadline = time.monotonic() + 10
            while self.active_instrumentations():
                require(time.monotonic() < deadline, "Owned guest instrumentation did not terminate; retain native lane")
                time.sleep(0.1)
        except (RuntimeError, subprocess.SubprocessError, OSError) as stop_failure:
            errors.append(stop_failure)
        if errors:
            raise ExceptionGroup("Owned guest cleanup/uncertainty receipt failures", errors)
        return "owned-guest-finished-and-verified-quiescent"


@contextmanager
def native_lane(ledger, device):
    lock_owner = OWNER + ":shared37:" + uuid.uuid4().hex
    with execution_lane(
        ledger, device, lock_owner=lock_owner, canonical=OWNER,
        endpoint="127.0.0.1:5037/" + SERIAL, purpose="missing-only27", source=HEAD,
    ):
        yield


@contextmanager
def execution_lane(ledger, device, *, lock_owner, canonical, endpoint, purpose, source):
    device.native_lane_owner = lock_owner
    ledger.acquire_hard_lock("native-execution", lock_owner, {
        "wp": "WP-503", "canonical_session_id": canonical, "endpoint": endpoint,
        "purpose": purpose, "source": source,
    }, time.time())
    try:
        yield
    finally:
        primary = sys.exception()
        try:
            require(not device.active_instrumentations(),
                    "Guest instrumentation unresolved: native lane retained, not released")
            require(not getattr(device, "application_state_uncertain", False),
                    "Fixture/application state unresolved after incomplete or forced termination: "
                    "retain native lane until exact fixture/preferences/window recovery is independently verified")
            require(not getattr(device, "profile_restoration_uncertain", False),
                    "Original effective runtime profile unresolved: native lane retained")
            original = getattr(device, "original_target_digest", None)
            if original is not None:
                actual = device.installed(APP)
                require(actual is not None and actual["sha256"] == original and device.installed(TEST) is None,
                        "Preserved package state unresolved: native lane retained, not released")
            if getattr(device, "original_target_absent", False):
                require(device.installed(APP) is None and device.installed(TEST) is None,
                        "Originally absent packages were not restored to absence: native lane retained")
                device.confirm_original_absence(APP)
            if getattr(device, "original_test_absent", False):
                device.confirm_original_absence(TEST)
            profile = getattr(device, "original_runtime_profile", None)
            if profile is not None:
                observer = getattr(device, "capture_profile", device.profile)
                require(observer() == profile, "Original runtime profile unresolved: native lane retained")
        except (RuntimeError, subprocess.SubprocessError, OSError, ValueError) as cleanup:
            raise BaseExceptionGroup("Native lane retained pending verified guest cleanup",
                                     ([primary] if primary else []) + [cleanup])
        ledger.release_hard_lock("native-execution", lock_owner)


def restore_packages(device, backup, original_digest, app_digest, test_digest):
    require(not device.active_instrumentations(), "Never replace APKs while guest instrumentation is active")
    states, errors = {}, []
    require((backup is None) == (original_digest is None), "Original package absence/preservation is ambiguous")
    absent = original_digest is None

    def verify_original_target():
        actual = device.installed(APP)
        require(actual is None if absent else actual is not None and actual["sha256"] == original_digest,
                "Original target absence was not restored" if absent else "Original target APK was not restored")

    for label, action in (
        ("original_target_apk", lambda: remove_owned_package(device, APP, app_digest) if absent
         else restore_target(device, backup, original_digest, app_digest)),
        ("owned_new_test_package", lambda: remove_owned_test(device, test_digest)),
        ("original_target_absence" if absent else "original_target_digest", verify_original_target),
    ):
        try:
            action()
            states[label] = "verified"
        except (RuntimeError, subprocess.SubprocessError, OSError) as failure:
            states[label] = repr(failure)
            errors.append(failure)
    return states, errors


def restore_target(device, backup, original_digest, app_digest):
    actual = device.installed(APP)
    if actual is not None and actual["sha256"] == original_digest:
        return
    require(actual is None or actual["sha256"] == app_digest,
            "Unexpected target identity: do not overwrite another consumer's APK")
    device.install(backup)


def remove_owned_test(device, test_digest):
    remove_owned_package(device, TEST, test_digest)


def remove_owned_package(device, package, expected_digest):
    require(package in (APP, TEST), "Cleanup is restricted to the owned debug App/Test packages")
    actual = device.installed(package)
    if actual is None:
        return
    require(actual["sha256"] == expected_digest, "Unexpected package identity: do not remove another consumer's package")
    require(device.run("uninstall", package) == "Success", "Owned newly introduced package cleanup failed")


def verify_binding(apk_directory):
    evidence = ROOT / "docs/android/evidence/WP-503"
    record = json.loads((evidence / "device-losendpoints-traversal-integration.json").read_text())
    binding = record["apk_binding"]
    require(binding["head"] == HEAD and binding["android_tree"] == TREE == git("rev-parse", HEAD + ":android"),
            "Retained final-composite source/APK binding changed")
    subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", HEAD, "HEAD"], check=True)
    fixtures = {**record["preserved_fixture_blobs"], **record["audit_fixture_blobs"]}
    for path, blob in fixtures.items():
        require(git("rev-parse", HEAD + ":" + path) == blob, "Immutable fixture mismatch: " + path)
    products = {}
    for name in ("device-reviewedrepairs-integration.json", "device-finalnodes-executed-integration.json"):
        previous = json.loads((evidence / name).read_text())
        products.update({item["path"]: item for item in previous["exact_producer_files"]})
    # The final LoS handoff supersedes only the two earlier LoS producer blobs.
    products.update({item["path"]: item for item in record["source_import"]["exact_producer_files"]})
    for path, item in products.items():
        require(git("rev-parse", HEAD + ":" + path) ==
                git("rev-parse", item["producer_head"] + ":" + path) == item["producer_blob"],
                "Exact final producer continuity failed: " + path)
    helper = "android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/DiagnosticsAccessibilityAudit.kt"
    original = json.loads((evidence / "device-reviewedrepairs-integration.json").read_text())
    require(git("rev-parse", HEAD + ":" + helper) ==
            git("rev-parse", original["executed_combined_head"] + ":" + helper),
            "Original shared diagnostics assertions changed")
    fixtures[helper] = git("rev-parse", HEAD + ":" + helper)
    paths = {}
    for name, metadata in binding["apks"].items():
        path = apk_directory / name
        require(path.stat().st_size == metadata["size"] and sha256(path) == metadata["sha256"],
                "Retained APK byte binding failed: " + name)
        paths["test" if name.endswith("-test.apk") else "app"] = path
    return binding, fixtures, paths, list(products.values())


def has_original_schema(apk):
    with zipfile.ZipFile(apk) as archive:
        return any(SCHEMA in archive.read(name) for name in archive.namelist() if name.endswith(".dex"))


def finish_log_consumer(stream):
    errors = []
    try:
        stream.terminate()
    except BaseException as failure:
        errors.append(failure)
    try:
        stream.wait(timeout=15)
    except BaseException as failure:
        errors.append(failure)
        for action in (stream.kill, lambda: stream.wait(timeout=15)):
            try:
                action()
            except BaseException as cleanup:
                errors.append(cleanup)
    if errors:
        raise BaseExceptionGroup("Bounded log-consumer cleanup failed", errors)


def execute_cohort(device, directory, cohort, profile, app_digest, test_digest):
    require(not getattr(device, "application_state_uncertain", False),
            "Prior fixture state is uncertain; no subsequent cohort may start")
    return execute_selection(
        device, directory, cohort, profile, app_digest, test_digest,
        expected=selected_methods(cohort), api=37, source=HEAD, android_tree=TREE,
        canonical=OWNER, endpoint="127.0.0.1:5037/" + SERIAL,
        stem_name="device-shared37-" + cohort, event_prefix="WP503_SHARED37",
    )


def execute_selection(device, directory, cohort, profile, app_digest, test_digest, *,
                      expected, api, source, android_tree, canonical, endpoint,
                      stem_name, event_prefix):
    device.require_fixture_recovery()
    require(not getattr(device, "application_state_uncertain", False),
            "Prior fixture state is uncertain; no subsequent cohort may start")
    require(device.profile() == profile, "Runtime profile changed between cohorts")
    require(device.installed(APP)["sha256"] == app_digest and device.installed(TEST)["sha256"] == test_digest,
            "Actual installed source-bound APKs changed")
    require(expected and len(expected) == len(set(expected)), "Require positive distinct selected identities")
    stem = directory / stem_name
    require(not stem.with_suffix(".txt").exists(), "Existing result must not be replayed or overwritten")
    device.fixture_recovery_path = Path(str(stem) + "-recovery.json")
    require(not device.fixture_recovery_path.exists(), "Existing fixture recovery receipt must not be overwritten")
    device.fixture_recovery_context = {
        "canonical_session_id": canonical, "source_head": source, "android_tree": android_tree,
        "native_lane_operation_owner": getattr(device, "native_lane_owner", None),
        "cohort": cohort, "original_identities": expected,
        "endpoint": endpoint, "api": api,
        "source_bound_app_sha256": app_digest, "source_bound_test_sha256": test_digest,
        "profile_before": profile,
        "original_target_sha256": getattr(device, "original_target_digest", None),
        "original_fixture_rows_and_preferences_snapshot_available": False,
        "status": "UNCERTAIN_APPLICATION_STATE_NATIVE_LANE_RETAINED",
        "fixture_preferences_and_window_restoration_verified": False,
        "required_recovery": (
            "Retain native lane and uncertainty receipt. Independently verify exact owned fixture rows, "
            "saved preferences/draft/onboarding and original window restoration through a supported "
            "fixture-scoped recovery operation with genuine original snapshots; never wipe data or "
            "overwrite/delete foreign records. APK restoration is not application-state restoration."
        ),
    }
    command = device.command("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(expected),
                             "-e", "expectedApi", str(api), TEST + "/androidx.test.runner.AndroidJUnitRunner")
    # Stream only new System.out records. Never clear the shared device log buffers.
    log_command = device.command("logcat", "-T", "1", "-v", "raw", "-s", "System.out:I", "*:S")
    print(event_prefix + "_START|" + json.dumps({"cohort": cohort, "command": command}), flush=True)
    with Path(str(stem) + "-logcat.txt").open("xb") as logs, stem.with_suffix(".txt").open("xb") as output:
        stream = subprocess.Popen(log_command, stdout=logs, stderr=subprocess.STDOUT)
        try:
            require(stream.poll() is None, "Scoped log consumer did not start")
            device.application_state_uncertain = True
            device.fixture_completion_untrusted = False
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            try:
                code = process.wait(timeout=600)
            except BaseException as failure:
                # Cancellation must clean up our host process too, without hiding the original signal.
                failures = [TimeoutError("Original600s outer deadline exceeded; partial output retained, no retry")
                            if isinstance(failure, subprocess.TimeoutExpired) else failure]
                for action in (
                    lambda: device.mark_application_state_uncertain(
                        "Host wait failed; guest completion and Kotlin fixture finally are unverified"),
                    process.kill,
                    lambda: process.wait(timeout=15),
                ):
                    try:
                        action()
                    except BaseException as cleanup_failure:
                        failures.append(cleanup_failure)
                if len(failures) > 1:
                    raise BaseExceptionGroup("Native wait and host/receipt cleanup failures", failures)
                raise failures[0]
            device.fixture_recovery_context["host_instrumentation_process_exit"] = code
            device.fixture_recovery_path.write_text(json.dumps(device.fixture_recovery_context, indent=2) + "\n")
            require(stream.poll() is None, "Scoped log consumer exited during instrumentation")
        finally:
            primary = sys.exception()
            errors = []
            for action in (
                lambda: finish_log_consumer(stream),
                lambda: device.finish_owned_instrumentation(app_digest, test_digest),
            ):
                try:
                    action()
                except BaseException as cleanup:
                    errors.append(cleanup)
            if primary is not None or errors:
                try:
                    device.mark_application_state_uncertain(
                        "Instrumentation/cleanup did not complete normally: " + str(primary or errors))
                except (RuntimeError, OSError) as receipt_failure:
                    errors.append(receipt_failure)
            if errors:
                raise BaseExceptionGroup("Instrumentation/log-consumer cleanup failed",
                                         ([primary] if primary else []) + errors)
    try:
        raw = stem.with_suffix(".txt").read_text(encoding="utf-8")
        summary = parse_outcomes(raw, expected)
        require(code == 0 and summary["passed"] == len(expected) and
                not device.fixture_completion_untrusted,
                "Normal all-positive fixture completion is unverified; retain native lane")
        summary.update({"cohort": cohort, "command": command, "process_exit": code})
        metrics = [line for line in Path(str(stem) + "-logcat.txt").read_bytes().splitlines()
                   if line.startswith(b"WP503_")]
        Path(str(stem) + "-metrics.txt").write_bytes(b"\n".join(metrics) + b"\n")
        Path(str(stem) + ".json").write_text(json.dumps(summary, indent=2) + "\n")
        device.fixture_recovery_context.update({
            "status": "VERIFIED_NORMAL_ALL_POSITIVE_COMPLETION",
            "fixture_preferences_and_window_restoration_verified": True,
        })
        device.fixture_recovery_path.write_text(json.dumps(device.fixture_recovery_context, indent=2) + "\n")
    except (OSError, RuntimeError, KeyError, ValueError) as failure:
        try:
            device.mark_application_state_uncertain(
                "Native transcript/completion proof failed; fixture finally is unverified: " + str(failure))
        except (RuntimeError, OSError) as receipt_failure:
            raise ExceptionGroup("Native proof and uncertainty receipt failures", [failure, receipt_failure])
        raise
    device.application_state_uncertain = False
    print(event_prefix + "_OUTCOME|" + json.dumps(summary), flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apks", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if args.execute:
        device = Device()
        device.require_fixture_recovery()
    binding, fixtures, paths, products = verify_binding(args.apks)
    selections = {name: selected_methods(name) for name in COHORTS}
    require(sum(map(len, selections.values())) == 27, "Only original missing27 are authorized")
    if not args.execute:
        print(json.dumps({"binding": binding, "fixtures": fixtures, "products": products,
                          "selections": selections}, indent=2))
        return
    args.output.mkdir(parents=True, exist_ok=True)
    require(not (args.output / "operations.json").exists(), "Existing execution cannot be replayed")
    sys.path.insert(0, str(ROOT / "tools/android-port"))
    from controller.ledger import Ledger
    from local.reserve import shared_ledger
    ledger = Ledger(shared_ledger(ROOT), "cbattlegear/MeshCoreOne-Android")
    claim = next(row for row in ledger.records() if row["wp"] == "WP-503")
    require(claim["attempt"] == claim["identity"]["session_id"] == OWNER and claim["revision"] == 8,
            "Actual same-attempt runner reservation changed")
    result = {"source_binding": binding, "fixtures": fixtures, "products": products, "selections": selections,
              "canonical_session_id": OWNER, "actual_cli_alias": "d2980f28-620c-408b-9947-067e2da8eedf",
              "reservation_revision": 8, "cohorts": [], "published_head_results_transfer": False,
              "runner_sha256": sha256(Path(__file__)), "audit_publication_parent": git("rev-parse", "HEAD"),
              "profile_adaptation": "actual4CPU/IME0; no assertion/deadline/settings/source change"}
    with native_lane(ledger, device):
        result["native_lane_operation_owner"] = device.native_lane_owner
        result["profile_before"] = profile = device.profile()
        result["installed_before"] = before = device.installed(APP)
        require(before is not None, "Existing user target APK needs an explicit preservation plan")
        require(device.installed(TEST) is None, "Existing user test package must not be overwritten")
        device.original_target_digest = before["sha256"]
        backup = args.output / "original-target.apk"
        device.run("pull", before["path"], str(backup), timeout=120)
        require(sha256(backup) == before["sha256"], "Original target APK preservation failed")
        require(has_original_schema(backup) and has_original_schema(paths["app"]),
                "Original/retained target Room schema compatibility is unverified")
        result["public_debug_certificates"] = signatures = {
            name: certificate(apk) for name, apk in
            (("original_target", backup), ("retained_app", paths["app"]), ("retained_test", paths["test"]))
        }
        (args.output / "operations.json").write_text(json.dumps(result, indent=2) + "\n")
        require(len(set(signatures.values())) == 1,
                "Existing target and retained App/Test debug identities differ: " + repr(signatures) +
                "; no uninstall, key access, re-signing or security bypass is admitted")
        app_digest = binding["apks"][paths["app"].name]["sha256"]
        test_digest = binding["apks"][paths["test"].name]["sha256"]
        result["mutation_attempts"] = []

        def record_attempt(package):
            result["mutation_attempts"].append(package)
            (args.output / "operations.json").write_text(json.dumps(result, indent=2) + "\n")

        try:
            install_pair(device, paths, record_attempt)
            require(device.installed(APP)["sha256"] == binding["apks"][paths["app"].name]["sha256"],
                    "Actual installed target APK differs from retained source binding")
            for cohort in COHORTS:
                outcome = execute_cohort(
                    device, args.output, cohort, profile,
                    binding["apks"][paths["app"].name]["sha256"], binding["apks"][paths["test"].name]["sha256"],
                )
                result["cohorts"].append(outcome)
                (args.output / "operations.json").write_text(json.dumps(result, indent=2) + "\n")
        finally:
            # Preserve the primary failure as well as any cleanup error.
            primary = sys.exception()
            if primary is not None:
                result["execution_error"] = repr(primary)
            if getattr(device, "application_state_uncertain", False):
                result["application_state"] = "UNCERTAIN_NATIVE_LANE_RETAINED"
                result["fixture_recovery_receipt"] = str(device.fixture_recovery_path)
            cleanup_errors = []
            try:
                result["guest_cleanup"] = device.finish_owned_instrumentation(app_digest, test_digest)
                states, errors = restore_packages(device, backup, before["sha256"], app_digest, test_digest)
                result["cleanup"] = states
                cleanup_errors.extend(errors)
                require(device.profile() == profile, "Original shared runtime profile was not restored")
                result["cleanup"]["original_profile"] = "verified"
            except (RuntimeError, subprocess.SubprocessError, OSError, ExceptionGroup) as failure:
                cleanup_errors.append(failure)
                result.setdefault("cleanup", {})["guest_or_profile"] = repr(failure)
            (args.output / "operations.json").write_text(json.dumps(result, indent=2) + "\n")
            if cleanup_errors:
                raise BaseExceptionGroup("Shared-device cleanup failures", ([primary] if primary else []) + cleanup_errors)
    require(all(row["process_exit"] == 0 and row["passed"] == len(selections[row["cohort"]])
                for row in result["cohorts"]), "Native failures retained; no successful cohort replay")


if __name__ == "__main__":
    main()
