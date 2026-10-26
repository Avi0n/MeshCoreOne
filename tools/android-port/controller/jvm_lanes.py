"""AndroidOnly: WP-003 Measured Linux JVM slots, not additional execution authority."""

import hashlib
import os
from pathlib import Path
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
    if re.fullmatch(r"[0-9a-f]{32}", identity) is None:
        raise PortError("Invalid actual Linux machine identity")
    return hashlib.sha256(identity.encode("ascii")).hexdigest()


def cpu_capacity():
    cpus = len(os.sched_getaffinity(0))
    quota = Path("/sys/fs/cgroup/cpu.max")
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
        limit = Path("/sys/fs/cgroup/memory.max")
        if limit.is_file():
            maximum = limit.read_text(encoding="ascii").strip()
            if maximum != "max":
                current = limit.with_name("memory.current").read_text(encoding="ascii").strip()
                if not maximum.isdigit() or not current.isdigit():
                    raise PortError("Malformed actual memory quota")
                available = min(available, int(maximum) - int(current))
        capacity = min(cpu_capacity(), available // MEMORY_PER_LANE)
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
