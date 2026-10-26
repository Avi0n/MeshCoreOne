"""AndroidOnly: WP-003 Measured Linux JVM slots, not additional execution authority."""

import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import sys

from .errors import PortError

WORKERS = 4
MEMORY_PER_LANE = 8 * 1024**3
RESOURCE = re.compile(r"gradle-execution:([0-9a-f]{64}):(0|[1-9][0-9]*)")


def host_identity():
    if not sys.platform.startswith("linux"):
        raise PortError("Parallel JVM lanes require the actual supported Linux host")
    try:
        identity = Path("/etc/machine-id").read_text(encoding="ascii").strip()
    except OSError as error:
        raise PortError("Cannot identify the actual Linux execution host") from error
    if re.fullmatch(r"[0-9a-f]{32}", identity) is None or identity == "0" * 32:
        raise PortError("Invalid actual Linux machine identity")
    return hashlib.sha256(identity.encode("ascii")).hexdigest()


def cgroup_directories():
    memberships = Path("/proc/self/cgroup").read_text(encoding="ascii").splitlines()
    if len(memberships) != 1 or not memberships[0].startswith("0::/"):
        raise PortError("Parallel JVM lanes require supported unified cgroup membership")
    name = memberships[0][3:]
    relative = PurePosixPath(name)
    if relative.root != "/" or ".." in relative.parts or str(relative) != name or "\\" in name:
        raise PortError("Unsupported current cgroup path")
    mounts = [
        line.split(" - ", 1)[0].split()
        for line in Path("/proc/self/mountinfo").read_text(encoding="ascii").splitlines()
        if " - cgroup2 " in line
    ]
    if len(mounts) != 1 or len(mounts[0]) < 6 or mounts[0][3:5] != ["/", "/sys/fs/cgroup"]:
        raise PortError("Unsupported unified cgroup mount; cannot prove host limits")
    root = Path("/sys/fs/cgroup")
    current = root.joinpath(*relative.parts[1:])
    if not current.is_dir():
        raise PortError("Current unified cgroup is unavailable")
    return [current, *list(current.parents)[:len(relative.parts) - 1]]


def cpu_capacity(groups=None):
    cpus = len(os.sched_getaffinity(0))
    for group in cgroup_directories() if groups is None else groups:
        quota = group / "cpu.max"
        if quota.is_file():
            values = quota.read_text(encoding="ascii").split()
            if len(values) != 2 or not values[1].isdigit() or int(values[1]) <= 0:
                raise PortError("Malformed actual CPU quota")
            if values[0] != "max":
                if not values[0].isdigit() or int(values[0]) <= 0:
                    raise PortError("Malformed actual CPU quota")
                cpus = min(cpus, int(values[0]) // int(values[1]))
    return cpus // WORKERS


def host_capacity():
    host = host_identity()
    try:
        memory = re.search(r"^MemAvailable:\s+([0-9]+) kB$", Path("/proc/meminfo").read_text(), re.MULTILINE)
        if memory is None:
            raise PortError("Missing actual available-memory measurement")
        available = int(memory[1]) * 1024
        groups = cgroup_directories()
        for group in groups:
            limit = group / "memory.max"
            if limit.is_file():
                maximum = limit.read_text(encoding="ascii").strip()
                if maximum != "max":
                    current = limit.with_name("memory.current").read_text(encoding="ascii").strip()
                    if not maximum.isdigit() or not current.isdigit():
                        raise PortError("Malformed actual memory quota")
                    available = min(available, int(maximum) - int(current))
        capacity = min(cpu_capacity(groups), available // MEMORY_PER_LANE)
    except OSError as error:
        raise PortError("Cannot measure actual JVM host capacity") from error
    if capacity < 1:
        raise PortError("Actual host lacks four CPUs and 8 GiB available per JVM lane")
    return host, capacity


def validate_binding(value):
    if (
        not isinstance(value, dict) or set(value) != {"host", "slot", "capacity"}
        or not isinstance(value["host"], str) or re.fullmatch(r"[0-9a-f]{64}", value["host"]) is None
        or type(value["slot"]) is not int or type(value["capacity"]) is not int
        or not 0 <= value["slot"] < value["capacity"]
    ):
        raise PortError("Malformed bounded JVM lane binding")
    if value["host"] != host_identity() or value["capacity"] > cpu_capacity():
        raise PortError("JVM lane differs from the actual host or CPU bound")
    return value


def lane_directory(work, binding):
    value = validate_binding(binding)
    return work / "local-checks" / "lanes" / value["host"] / str(value["slot"])
