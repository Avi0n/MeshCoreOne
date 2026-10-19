"""AndroidOnly: WP-503 Missing-only API37 audit on an already running, source-bound device."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import zipfile


ROOT = Path(__file__).resolve().parents[2]
HEAD = "3f39a777376e322e2bc533f1d1caf92a87a44ed8"
TREE = "7a9dd08e005e1e9cad79405dafecf325158d8b0c"
OWNER = "1666502a-0420-46ef-930f-e78651ba8b9f"
ADB = r"C:\Users\camer\AppData\Local\Android\Sdk\platform-tools\adb.exe"
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
    return {
        "discovered": len(starts), "started": len(starts), "matching_outcomes": len(ends),
        "passed": statuses[0], "failed": statuses[-2], "errors": statuses[-1],
        "skipped": statuses[-3] + statuses[-4], "outcomes": ends,
        "seconds": re.findall(r"Time: ([\d.]+)", text),
    }


class Device:
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
        activity_dump = self.shell("dumpsys activity")
        require("ActiveInstrumentation{" not in activity_dump, "Another actual instrumentation consumer is active")
        return values

    def install(self, path):
        require(self.run("install", "-r", str(path), timeout=120) == "Success", "APK install did not succeed")


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


def execute_cohort(device, directory, cohort, profile, app_digest, test_digest):
    require(device.profile() == profile, "Runtime profile changed between cohorts")
    require(device.installed(APP)["sha256"] == app_digest and device.installed(TEST)["sha256"] == test_digest,
            "Actual installed source-bound APKs changed")
    expected = selected_methods(cohort)
    stem = directory / ("device-shared37-" + cohort)
    require(not stem.with_suffix(".txt").exists(), "Existing result must not be replayed or overwritten")
    command = device.command("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(expected),
                             "-e", "expectedApi", "37", TEST + "/androidx.test.runner.AndroidJUnitRunner")
    # Stream only new System.out records. Never clear the shared device log buffers.
    log_command = device.command("logcat", "-T", "1", "-v", "raw", "-s", "System.out:I", "*:S")
    print("WP503_SHARED37_START|" + json.dumps({"cohort": cohort, "command": command}), flush=True)
    with Path(str(stem) + "-logcat.txt").open("xb") as logs, stem.with_suffix(".txt").open("xb") as output:
        stream = subprocess.Popen(log_command, stdout=logs, stderr=subprocess.STDOUT)
        try:
            require(stream.poll() is None, "Scoped log consumer did not start")
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            try:
                code = process.wait(timeout=600)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise TimeoutError("Original600s outer deadline exceeded; partial output retained, no retry")
            require(stream.poll() is None, "Scoped log consumer exited during instrumentation")
        finally:
            stream.terminate()
            stream.wait(timeout=15)
    raw = stem.with_suffix(".txt").read_text(encoding="utf-8")
    summary = parse_outcomes(raw, expected)
    summary.update({"cohort": cohort, "command": command, "process_exit": code})
    metrics = [line for line in Path(str(stem) + "-logcat.txt").read_bytes().splitlines()
               if line.startswith(b"WP503_")]
    Path(str(stem) + "-metrics.txt").write_bytes(b"\n".join(metrics) + b"\n")
    Path(str(stem) + ".json").write_text(json.dumps(summary, indent=2) + "\n")
    print("WP503_SHARED37_OUTCOME|" + json.dumps(summary), flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apks", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
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
    device = Device()
    with ledger.hard_lock("native-execution", OWNER, {"endpoint": "127.0.0.1:5037/" + SERIAL,
                                                    "purpose": "missing-only27", "source": HEAD}, time.time()):
        result["profile_before"] = profile = device.profile()
        result["installed_before"] = before = device.installed(APP)
        require(before is not None, "Existing user target APK needs an explicit preservation plan")
        require(device.installed(TEST) is None, "Existing user test package must not be overwritten")
        backup = args.output / "original-target.apk"
        device.run("pull", before["path"], str(backup), timeout=120)
        require(sha256(backup) == before["sha256"], "Original target APK preservation failed")
        require(has_original_schema(backup) and has_original_schema(paths["app"]),
                "Original/retained target Room schema compatibility is unverified")
        app_changed = test_changed = False
        try:
            device.install(paths["app"])
            app_changed = True
            device.install(paths["test"])
            test_changed = True
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
            cleanup_errors = []
            for label, action in (
                ("original_target_apk", lambda: device.install(backup) if app_changed else None),
                ("owned_new_test_package", lambda: require(device.run("uninstall", TEST) == "Success",
                                                          "Owned test-package cleanup failed") if test_changed else None),
                ("original_target_digest", lambda: require(device.installed(APP)["sha256"] == before["sha256"],
                                                          "Original target APK was not restored")),
                ("original_profile", lambda: require(device.profile() == profile,
                                                    "Original shared runtime profile was not restored")),
            ):
                try:
                    action()
                    result.setdefault("cleanup", {})[label] = "verified"
                except (RuntimeError, subprocess.SubprocessError, OSError) as failure:
                    cleanup_errors.append(failure)
                    result.setdefault("cleanup", {})[label] = repr(failure)
            (args.output / "operations.json").write_text(json.dumps(result, indent=2) + "\n")
            if cleanup_errors:
                raise ExceptionGroup("Shared-device cleanup failures", ([primary] if primary else []) + cleanup_errors)
    require(all(row["process_exit"] == 0 and row["passed"] == len(selections[row["cohort"]])
                for row in result["cohorts"]), "Native failures retained; no successful cohort replay")


if __name__ == "__main__":
    main()
