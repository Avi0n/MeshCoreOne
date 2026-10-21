"""AndroidOnly: WP-003 Explicit ephemeral inputs and the unchanged scaffold allowlist."""

import hashlib
import json
import os
import re
from pathlib import Path

from .errors import PortError
from .schema import fields, load_json

REPO = Path(__file__).resolve().parents[3]
LOCK = Path(__file__).with_name("toolchain-pins.json")


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def file_sha256(path: Path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def host_name():
    if os.name == "nt":
        return "windows"
    if __import__("platform").system() == "Linux" and __import__("platform").machine() == "x86_64":
        return "linux"
    raise PortError("Only isolated Windows x64 and Linux x64 build hosts are supported")


def toolchain_lock():
    value = load_json(LOCK)
    fields(value, {
        "schema_version", "python", "jdk", "gradle", "gradle_distribution_sha256",
        "gradle_wrapper_sha256", "sdk_metadata_url", "sdk_metadata_observed_sha256",
        "sdk_license_id", "sdk_license_sha1", "sdk_license_sha256", "archives",
    }, label="CI toolchain lock")
    if value["schema_version"] != 1 or value["python"] != "3.12.4" or value["jdk"] != "21.0.12.1+1":
        raise PortError("Unsupported CI toolchain tuple")
    seen = set()
    for archive in value["archives"]:
        fields(archive, {"id", "host", "url", "size", "sha256", "sha1", "revision"}, label="tool archive")
        key = (archive["id"], archive["host"])
        if key in seen or archive["host"] not in ("all", "linux", "windows"):
            raise PortError("Duplicate/unsupported host archive")
        seen.add(key)
        if not re.fullmatch(r"[0-9a-f]{64}", archive["sha256"]) or type(archive["size"]) is not int or archive["size"] < 1:
            raise PortError("Missing immutable archive checksum/size")
        if not archive["url"].startswith((
            "https://dl.google.com/android/repository/",
            "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/",
        )):
            raise PortError("Tool archive is not from the pinned first-party publisher")
    for host in ("windows", "linux"):
        selected = [a for a in value["archives"] if a["host"] in (host, "all")]
        if {a["id"] for a in selected} != {"jdk", "platforms;android-37.2", "build-tools;37.0.0", "cmdline-tools;23.0"}:
            raise PortError("Incomplete exact-host SDK/JDK archive set")
    return value


def candidate_environment(state: dict, inherited=None, *, standalone=False, local=False,
                          full_assembly=False):
    fields(state, {"schema_version", "host", "java_home", "android_home", "private_root", "provenance"}, label="build inputs")
    if state["schema_version"] != 1 or state["host"] != host_name():
        raise PortError("Build-input schema/host mismatch")
    for key in ("java_home", "android_home", "private_root"):
        if not isinstance(state[key], str) or not Path(state[key]).is_absolute():
            raise PortError("Build inputs require explicit absolute per-process directories")
    declaration = load_json(REPO / "android" / "scaffold" / "environment-allowlist.json")
    allowed = {name.casefold() for name in declaration["allowed_names"]}
    inherited = os.environ if inherited is None else inherited
    environment = {
        name: value for name, value in inherited.items()
        if name.casefold() in allowed and name.casefold() not in {
            "java_opts", "gradle_opts", "java_home", "android_home", "android_sdk_root",
            "gradle_user_home", "android_user_home",
        }
    }
    private = Path(state["private_root"])
    gradle_home = Path(inherited.get("GRADLE_USER_HOME", private / "gradle-root"))
    if not gradle_home.is_absolute():
        raise PortError("Gradle user home must be absolute")
    environment.update({
        "JAVA_HOME": state["java_home"],
        "ANDROID_HOME": state["android_home"],
        "ANDROID_SDK_ROOT": state["android_home"],
        "GRADLE_USER_HOME": str(gradle_home),
        "ANDROID_USER_HOME": str(private / "android-user"),
        "PYTHONDONTWRITEBYTECODE": "1",
        "JAVA_OPTS": "-Xms32m -Xmx128m -Dfile.encoding=UTF-8",
    })
    jvm = "-Xms64m -Xmx2048m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
    if local:
        environment["JAVA_OPTS"] += " -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=32m"
        heap = 2048 if full_assembly else 768
        jvm = f"-Xms64m -Xmx{heap}m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=96m -Dfile.encoding=UTF-8"
    environment["GRADLE_OPTS"] = f'-Dorg.gradle.jvmargs="{jvm}"'
    if any(name.casefold() not in allowed for name in environment):
        raise PortError("CI launcher attempted to extend the parent environment allowlist")
    return environment


def verify_wrapper():
    lock = toolchain_lock()
    wrapper = REPO / "android" / "gradle" / "wrapper"
    properties = (wrapper / "gradle-wrapper.properties").read_text(encoding="utf-8")
    if f"distributionSha256Sum={lock['gradle_distribution_sha256']}" not in properties:
        raise PortError("Gradle distribution checksum differs from the approved tuple")
    if file_sha256(wrapper / "gradle-wrapper.jar") != lock["gradle_wrapper_sha256"]:
        raise PortError("Gradle wrapper JAR differs from the publisher pin")
    if not re.search(r"distributionUrl=.*gradle-9\.8\.0-bin\.zip", properties):
        raise PortError("Unexpected Gradle distribution")
