"""AndroidOnly: WP-003 Verified first-party archives; no shared/global installation."""

import copy
import hashlib
import io
import os
import re
import stat
import tarfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

from .ci_environment import host_name, toolchain_lock, write_json
from .errors import PortError

MAX_METADATA_BYTES = 8 * 1024 * 1024
MAX_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024


def platform_drift_details(archive, data: bytes):
    complete = archive.find("complete")
    if complete is None:
        raise PortError("SDK platform drift diagnostic: missing complete archive")
    name = complete.findtext("url", "")
    size_text = complete.findtext("size", "")
    sha1 = complete.findtext("checksum", "")
    if not re.fullmatch(r"platform-37\.2_r[0-9]+\.zip", name) or not re.fullmatch(r"[0-9]{1,9}", size_text):
        raise PortError("SDK platform drift diagnostic: unexpected archive name or size")
    size = int(size_text)
    if size < 1 or size > 128 * 1024 * 1024 or not re.fullmatch(r"[0-9a-f]{40}", sha1):
        raise PortError("SDK platform drift diagnostic: invalid archive size or SHA-1")
    url = "https://dl.google.com/android/repository/" + name
    digest, supplementary, count = hashlib.sha256(), hashlib.sha1(), 0
    request = urllib.request.Request(url, headers={"User-Agent": "MeshCoreOne-isolated-CI"})
    with urllib.request.urlopen(request, timeout=90) as response:
        while chunk := response.read(1024 * 1024):
            count += len(chunk)
            if count > size:
                raise PortError("SDK platform drift diagnostic: archive exceeds published size")
            digest.update(chunk)
            supplementary.update(chunk)
    if count != size or supplementary.hexdigest() != sha1:
        raise PortError("SDK platform drift diagnostic: publisher size/SHA-1 mismatch")
    return (
        f"publisher archive {url!r}, size {size}, sha1 {sha1}, sha256 {digest.hexdigest()}, "
        f"metadata sha256 {hashlib.sha256(data).hexdigest()}"
    )


def safe_member(name: str):
    path = PurePosixPath(name)
    if not name or "\\" in name or path.is_absolute() or ".." in path.parts or ":" in name:
        raise PortError("Unsafe tool archive member")
    return path


def extract_archive(path: Path, destination: Path):
    destination.mkdir(parents=True, exist_ok=False)
    if path.name.endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            if sum(item.file_size for item in archive.infolist()) > MAX_EXPANDED_BYTES:
                raise PortError("Tool archive expanded-size limit exceeded")
            for item in archive.infolist():
                safe_member(item.filename)
                mode = item.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise PortError("ZIP tool archive contains a symlink")
                target = Path(archive.extract(item, destination))
                if os.name != "nt" and not item.is_dir() and mode & 0o777:
                    target.chmod(mode & 0o777)
    else:
        with tarfile.open(path, "r:gz") as archive:
            members = archive.getmembers()
            if sum(item.size for item in members) > MAX_EXPANDED_BYTES:
                raise PortError("Tool archive expanded-size limit exceeded")
            for item in members:
                safe_member(item.name)
                if item.issym() or item.islnk():
                    resolved = (destination / item.name).parent / item.linkname
                    if not resolved.resolve().is_relative_to(destination.resolve()):
                        raise PortError("Tool archive link escapes its private directory")
                elif not item.isfile() and not item.isdir():
                    raise PortError("Unsupported tool archive entry")
            archive.extractall(destination, filter="data")
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir():
        raise PortError("Tool archive must contain exactly one publisher root")
    return roots[0]


def download_archive(record: dict, destination: Path):
    sha256, sha1, count = hashlib.sha256(), hashlib.sha1(), 0
    request = urllib.request.Request(record["url"], headers={"User-Agent": "MeshCoreOne-isolated-CI"})
    with urllib.request.urlopen(request, timeout=90) as response, destination.open("xb") as output:
        while chunk := response.read(1024 * 1024):
            count += len(chunk)
            if count > record["size"]:
                raise PortError("Publisher archive exceeds its pinned size")
            sha256.update(chunk)
            sha1.update(chunk)
            output.write(chunk)
    if count != record["size"] or sha256.hexdigest() != record["sha256"]:
        raise PortError("Publisher archive size/SHA-256 mismatch; installation refused")
    if record["sha1"] is not None and sha1.hexdigest() != record["sha1"]:
        raise PortError("SDK archive differs from Google's supplementary repository checksum")


def sdk_metadata(lock: dict, selected: list[dict], data: bytes):
    if len(data) > MAX_METADATA_BYTES or b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise PortError("Oversized/unsafe SDK repository metadata")
    root = ET.fromstring(data)
    license_node = root.find(f"license[@id='{lock['sdk_license_id']}']")
    if license_node is None or not license_node.text:
        raise PortError("Missing selected SDK license")
    license_bytes = license_node.text.strip().encode("utf-8")
    if hashlib.sha256(license_bytes).hexdigest() != lock["sdk_license_sha256"]:
        raise PortError("SDK terms changed; explicit human provisioning review required")
    for record in selected:
        if record["id"] == "jdk":
            continue
        packages = root.findall(f"remotePackage[@path='{record['id']}']")
        if len(packages) != 1:
            raise PortError(f"Missing/duplicate exact SDK package {record['id']!r}: found {len(packages)}")
        package = packages[0]
        revision = ".".join(package.findtext(f"revision/{part}", "0") for part in ("major", "minor", "micro"))
        all_archives = package.findall("archives/archive")
        archives = [
            a for a in all_archives
            if a.findtext("host-os", "all") == record["host"]
        ]
        if revision != record["revision"] or len(archives) != 1:
            hosts = [a.findtext("host-os", "all")[:80] for a in all_archives[:16]]
            details = ""
            if record["id"] == "platforms;android-37.2" and revision != record["revision"] and len(archives) == 1:
                details = "; " + platform_drift_details(archives[0], data)
            raise PortError(
                f"SDK revision/host changed from the protected archive lock for {record['id']!r}: "
                f"expected revision {record['revision']!r}, host {record['host']!r}; "
                f"observed revision {revision[:120]!r}, hosts {hosts!r} "
                f"({len(all_archives)} total archives, {len(archives)} matching host){details}"
            )
        complete = archives[0].find("complete")
        if (
            complete is None or complete.findtext("url") != record["url"].rsplit("/", 1)[1]
            or complete.findtext("size") != str(record["size"])
            or complete.findtext("checksum") != record["sha1"]
        ):
            raise PortError("SDK repository/archive provenance mismatch")
    return root


def local_package_xml(root, data: bytes, package_id: str):
    namespaces = {value[0]: value[1] for _, value in ET.iterparse(io.BytesIO(data), events=("start-ns",))}
    repository = ET.Element(root.tag)
    root_namespace = root.tag.split("}", 1)[0].removeprefix("{")
    for prefix, uri in namespaces.items():
        if prefix:
            ET.register_namespace(prefix, uri)
            if prefix != "xsi" and uri != root_namespace:
                repository.set("xmlns:" + prefix, uri)
    package = copy.deepcopy(root.find(f"remotePackage[@path='{package_id}']"))
    package.tag = "localPackage"
    for tag in ("archives", "channelRef"):
        child = package.find(tag)
        if child is not None:
            package.remove(child)
    repository.append(copy.deepcopy(root.find("license[@id='android-sdk-license']")))
    repository.append(package)
    return ET.tostring(repository, encoding="utf-8", xml_declaration=True)


def provision(root: Path, *, accept_sdk_license=False):
    if not accept_sdk_license:
        raise PortError("Explicit --accept-sdk-license is required for this isolated SDK installation")
    if not root.is_absolute() or root.exists():
        raise PortError("Provisioning requires a new explicit absolute ephemeral directory")
    lock, host = toolchain_lock(), host_name()
    selected = [a for a in lock["archives"] if a["host"] in ("all", host)]
    with urllib.request.urlopen(lock["sdk_metadata_url"], timeout=60) as response:
        data = response.read(MAX_METADATA_BYTES + 1)
    metadata = sdk_metadata(lock, selected, data)
    root.mkdir(parents=True, exist_ok=False)
    downloads, sdk = root / "downloads", root / "sdk"
    downloads.mkdir()
    sdk.mkdir()
    java_home = None
    destinations = {
        "platforms;android-37.2": sdk / "platforms" / "android-37.2",
        "build-tools;37.0.0": sdk / "build-tools" / "37.0.0",
        "cmdline-tools;23.0": sdk / "cmdline-tools" / "23.0",
    }
    for index, record in enumerate(selected):
        print(f"Verifying {record['id']} ({host})", flush=True)
        archive = downloads / record["url"].rsplit("/", 1)[1]
        download_archive(record, archive)
        extracted = extract_archive(archive, root / f"extract-{index}")
        if record["id"] == "jdk":
            target = root / "jdk"
            extracted.rename(target)
            java_home = target
        else:
            target = destinations[record["id"]]
            target.parent.mkdir(parents=True, exist_ok=True)
            extracted.rename(target)
            (target / "package.xml").write_bytes(local_package_xml(metadata, data, record["id"]))
        archive.unlink()
        (root / f"extract-{index}").rmdir()
    if java_home is None:
        raise PortError("Pinned JDK was not installed")
    licenses = sdk / "licenses"
    licenses.mkdir()
    (licenses / lock["sdk_license_id"]).write_text(lock["sdk_license_sha1"] + "\n", encoding="ascii")
    state = {
        "schema_version": 1, "host": host, "java_home": str(java_home),
        "android_home": str(sdk), "private_root": str(root / "private"),
        "provenance": {
            "archives": selected, "sdk_metadata_sha256": hashlib.sha256(data).hexdigest(),
            "sdk_license_sha256": lock["sdk_license_sha256"],
            "sdk_installation": "verified-archive-extraction-only",
            "cli_bootstrap_executed": False,
            "separate_cli_helper_downloaded_or_executed": False,
            "scope": "explicit isolated SDK provisioning; not linked-dependency/legal or device approval",
        },
    }
    write_json(root / "environment.json", state)
    return state
