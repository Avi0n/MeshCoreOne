"""AndroidOnly: WP-003 Standard WP-503 debug packaging, never key management or installation."""

import hashlib
import os
from pathlib import Path
import re
import subprocess
import time

from controller.capabilities import CapabilityEngine
from controller.ci import preflight
from controller.ci_environment import REPO, candidate_environment, toolchain_lock, verify_wrapper, write_json
from controller.errors import PortError
from controller.gates import policy_revision
from controller.model import SHA, git, tree
from controller.schema import digest

ADAPTER = "tools/android-port/local/debug_packaging.py"
OPERATION = "build-debug-test-apks-with-existing-local-identity"
TASKS = (":app:assembleDebug", ":app:assembleDebugAndroidTest")
PACKAGES = ("com.meshcoreone.android.debug", "com.meshcoreone.android.debug.test")


def sha256(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def source_identity(repo, revision):
    if (
        not isinstance(revision, str) or SHA.fullmatch(revision) is None
        or git(repo, "rev-parse", "HEAD").decode().strip() != revision
        or git(repo, "status", "--porcelain", "--untracked-files=all", "--", "android").strip()
    ):
        raise PortError("Debug packaging requires exact unchanged committed Android production and fixtures")
    return digest({path: blob for path, blob in tree(repo, revision).items() if path.startswith("android/")})


def native_lane(ledger, owner, *, gradle_free=True):
    if not isinstance(owner, str) or not owner:
        raise PortError("Debug packaging requires the existing native execution owner")
    locks = ledger.hard_lock_records()
    if not any(
        lock["resource"] == "native-execution" and lock["owner"] == owner
        and lock["payload"].get("wp") == "WP-503" for lock in locks
    ):
        raise PortError("Debug packaging requires the caller's held native-execution lane")
    if gradle_free and any(lock["resource"] == "gradle-execution" for lock in locks):
        raise PortError("Debug packaging waits for the existing exclusive Gradle lane")


def apk_identity(apk, tools, environment):
    apk = Path(apk)
    if not apk.is_file() or apk.is_symlink():
        raise PortError("Actual regular debug APK is missing")
    try:
        certs = subprocess.check_output(
            [str(Path(environment["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")),
             "-Xmx128m", "-Dfile.encoding=UTF-8", "-jar", str(tools / "lib/apksigner.jar"),
             "verify", "--print-certs", str(apk)],
            env=environment, encoding="utf-8", errors="strict", stderr=subprocess.STDOUT, timeout=120,
        )
        badging = subprocess.check_output(
            [str(tools / ("aapt2.exe" if os.name == "nt" else "aapt2")), "dump", "badging", str(apk)],
            env=environment, encoding="utf-8", errors="strict", stderr=subprocess.STDOUT, timeout=120,
        )
    except (OSError, subprocess.SubprocessError, UnicodeError) as error:
        raise PortError("Public debug APK signature/package verification failed") from error
    certificates = [
        re.fullmatch(r"(?:Signer #\d+ |V2 Signer: )certificate SHA-256 digest: ([0-9a-fA-F]{64})", line)
        for line in certs.splitlines() if "certificate SHA-256 digest:" in line
    ]
    packages = re.findall(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if len(certificates) != 1 or certificates[0] is None or len(packages) != 1:
        raise PortError("Debug APK needs one unambiguous verified signer and package")
    return {"path": str(apk.resolve()), "sha256": sha256(apk),
            "certificate_sha256": certificates[0].group(1).lower(), "package": packages[0]}


def verify_pair(installed, app, tests):
    if (
        (app["package"], tests["package"]) != PACKAGES
        or app["certificate_sha256"] != tests["certificate_sha256"]
    ):
        raise PortError("New DEBUG App/Test must have exact packages and matching public certificates")
    if installed is not None and (
        installed["package"] != PACKAGES[0]
        or app["certificate_sha256"] != installed["certificate_sha256"]
    ):
        raise PortError("New DEBUG App/Test public certificates and packages must match the preserved installed identity")


def assemble_debug_pair(*, manifest, policy, ledger, owner, revision, state,
                        debug_home, installed_apk, output, wp_id="WP-503"):
    """Called within WP-503's guarded native lane; returns packaging evidence, not test acceptance."""
    repo, output, debug_home = REPO.resolve(), Path(output), Path(debug_home)
    if wp_id != "WP-503" or ledger.repository != policy["repository"]:
        raise PortError("Standard debug packaging is restricted to the actual WP-503 repository/owner")
    CapabilityEngine(policy).admit(wp_id, manifest.wp(wp_id)["write_paths"], ADAPTER, OPERATION)
    if (
        not output.is_absolute() or output.resolve().is_relative_to(repo) or output.is_symlink()
        or output.exists() and any(output.iterdir())
        or not debug_home.is_absolute() or not debug_home.is_dir() or debug_home.is_symlink()
        or not (debug_home / "debug.keystore").is_file() or (debug_home / "debug.keystore").is_symlink()
    ):
        raise PortError("Use a new private output and the existing default local debug identity; no key creation/copy")
    native_lane(ledger, owner)
    source = source_identity(repo, revision)
    verify_wrapper()
    environment = candidate_environment(state, local=True, full_assembly=True)
    environment["ANDROID_USER_HOME"] = str(debug_home)
    versions = {item["id"].split(";", 1)[1] for item in toolchain_lock()["archives"]
                if item["id"].startswith("build-tools;")}
    if len(versions) != 1:
        raise PortError("Debug APK verification requires one pinned build-tools revision")
    tools = Path(state["android_home"]) / "build-tools" / versions.pop()
    output.mkdir(parents=True, exist_ok=True)
    installed = None if installed_apk is None else apk_identity(installed_apk, tools, environment)
    if installed is not None and installed["package"] != PACKAGES[0]:
        raise PortError("Preserved installed APK is not the debug application")
    command = [str(repo / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")),
               *TASKS, "--dependency-verification", "strict", "--no-daemon", "--max-workers=1", "--console=plain"]
    if os.name == "nt":
        import shutil

        shell = shutil.which("pwsh", path=environment.get("PATH"))
        if shell is None:
            raise PortError("Windows debug packaging requires the existing PowerShell 7 wrapper launcher")
        invocation = output / "debug-invocation.json"
        write_json(invocation, {"wrapper": command[0], "project": str(repo / "android"), "arguments": command[1:]})
        command = [shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
                   str(repo / "tools/android-port/controller/gradle_windows.ps1"), "-InvocationFile", str(invocation)]
    with ledger.hard_lock("gradle-execution", owner, {"wp": wp_id, "revision": revision, "tasks": list(TASKS)}, time.time()):
        preflight(state, output / "readiness", local=True)
        with (output / "debug-build.log").open("wb") as stream:
            try:
                subprocess.run(command, cwd=repo / "android", env=environment,
                               stdout=stream, stderr=subprocess.STDOUT, timeout=1800, check=True)
            except (OSError, subprocess.SubprocessError) as error:
                raise PortError("Standard DEBUG assembly failed; retain actual build log, never install fallback APKs") from error
        app = apk_identity(repo / "android/app/build/outputs/apk/debug/app-debug.apk", tools, environment)
        tests = apk_identity(repo / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", tools, environment)
        verify_pair(installed, app, tests)
        if source_identity(repo, revision) != source or (
            installed is not None and apk_identity(installed_apk, tools, environment) != installed
        ):
            raise PortError("Android source/fixtures or preserved installed APK changed during packaging")
    native_lane(ledger, owner, gradle_free=False)
    result = {
        "schema_version": 1, "kind": "standard-debug-packaging", "work_package": wp_id,
        "owner": owner, "repository": policy["repository"], "source_revision": revision,
        "manifest_sha256": manifest.sha256, "policy_revision": policy_revision(manifest, policy),
        "android_source_tree_sha256": source, "tasks": list(TASKS),
        "toolchain": toolchain_lock(), "installed_snapshot": installed, "app": app, "tests": tests,
        "preservation_mode": "originally-absent" if installed is None else "originally-present",
        "build_log_sha256": sha256(output / "debug-build.log"),
        "tests_started": 0, "installed": False, "acceptance": False,
    }
    write_json(output / "debug-packaging.json", result)
    return result
