"""AndroidOnly: WP-504 Bound advisory checks and reap only their own Linux descendants."""

import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

CLEANUP_SECONDS = 2


def admission_lane(admission):
    if "jvm_lane" not in admission:
        return None
    value = admission["jvm_lane"]
    if (
        not isinstance(value, dict) or set(value) != {"host", "slot", "capacity"}
        or not isinstance(value["host"], str) or re.fullmatch(r"[0-9a-f]{64}", value["host"]) is None
        or type(value["slot"]) is not int or type(value["capacity"]) is not int
        or not 0 <= value["slot"] < value["capacity"]
    ):
        raise ValueError("Malformed owned JVM host/slot binding")
    return value["host"] + "/" + str(value["slot"])


def pidfd_open(pid):
    libc = ctypes.CDLL(None, use_errno=True)
    operation = libc.pidfd_open
    operation.argtypes = [ctypes.c_int, ctypes.c_uint]
    operation.restype = ctypes.c_int
    descriptor = operation(pid, 0)
    if descriptor < 0:
        raise OSError(ctypes.get_errno(), "Cannot pin owned process identity")
    return descriptor


def pidfd_signal(descriptor, number):
    libc = ctypes.CDLL(None, use_errno=True)
    operation = libc.pidfd_send_signal
    operation.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint]
    operation.restype = ctypes.c_int
    if operation(descriptor, number, None, 0) != 0:
        raise OSError(ctypes.get_errno(), "Cannot signal owned process identity")


def children(pid):
    result = set()
    for path in Path(f"/proc/{pid}/task").glob("*/children"):
        try:
            result.update(int(value) for value in path.read_text().split())
        except FileNotFoundError:
            continue
    return result


def descendants():
    result = set()
    pending = list(children(os.getpid()))
    while pending:
        pid = pending.pop()
        if pid not in result:
            result.add(pid)
            pending.extend(children(pid))
    return result


def is_owned(pid):
    seen = set()
    while pid != os.getpid():
        if pid <= 1 or pid in seen:
            return False
        seen.add(pid)
        stat = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        pid = int(stat[1])
    return True


def signal_owned(number):
    owned = descendants()
    for pid in owned:
        try:
            descriptor = pidfd_open(pid)
        except ProcessLookupError:
            continue
        try:
            # Pin identity, then verify the complete live ancestry, not a stale PID set.
            if is_owned(pid):
                pidfd_signal(descriptor, number)
        except (FileNotFoundError, ProcessLookupError):
            pass
        finally:
            os.close(descriptor)


def reap():
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            return
        if pid == 0:
            return


def cleanup():
    signal_owned(signal.SIGTERM)
    deadline = time.monotonic() + CLEANUP_SECONDS
    while descendants() and time.monotonic() < deadline:
        reap()
        time.sleep(0.02)
    deadline = time.monotonic() + CLEANUP_SECONDS
    while descendants() and time.monotonic() < deadline:
        signal_owned(signal.SIGKILL)
        reap()
        time.sleep(0.02)
    reap()
    if descendants():
        raise RuntimeError("Owned descendants could not be reaped; shared execution ownership retained")


def supervise(command, seconds=None, *, completion=None, token=None, preserve_daemons=False):
    if not sys.platform.startswith("linux"):
        raise ValueError("Bounded advisory cleanup requires the supported Linux pidfd runner")
    libc = ctypes.CDLL(None, use_errno=True)
    libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong,
                          ctypes.c_ulong, ctypes.c_ulong]
    libc.prctl.restype = ctypes.c_int
    if libc.prctl(36, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "Cannot establish an owned-child subreaper")
    descriptor = pidfd_open(os.getpid())
    try:
        pidfd_signal(descriptor, 0)
    finally:
        os.close(descriptor)
    Path(f"/proc/{os.getpid()}/task/{os.getpid()}/children").read_text()
    descriptors = []
    for descriptor in (8, 9):
        try:
            os.fstat(descriptor)
            descriptors.append(descriptor)
        except OSError:
            continue
    process = subprocess.Popen(command, start_new_session=True, pass_fds=descriptors)
    status = None
    try:
        try:
            status = process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            print(f"UNVERIFIED: advisory fast check exceeded {seconds:g}s; cleaning owned descendants",
                  file=sys.stderr, flush=True)
            status = 124
    finally:
        if not preserve_daemons:
            # The inherited lane FD stays here until all owned work is gone.
            cleanup()
            process.wait()
    if preserve_daemons:
        reap()
        deadline = time.monotonic() + CLEANUP_SECONDS
        while descendants() and time.monotonic() < deadline:
            reap()
            time.sleep(0.02)
        if descendants():
            print("BLOCKED: manual execution or persistent children need reconciliation; "
                  "shared ownership retained (no daemon kill)", file=sys.stderr, flush=True)
            return status if status else 125
    if completion is not None:
        if not token:
            raise ValueError("Cleanup completion requires the exact invocation token")
        temporary = completion.with_suffix(".next")
        temporary.write_text(json.dumps({
            "schema_version": 1, "token": token, "status": "owned-children-reaped",
            "returncode": status,
        }) + "\n", encoding="utf-8")
        os.replace(temporary, completion)
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float)
    parser.add_argument("--completion", type=Path)
    parser.add_argument("--token")
    parser.add_argument("--preserve-daemons", action="store_true")
    parser.add_argument("--admission", type=Path)
    parser.add_argument("--commit")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if (args.seconds is not None and args.seconds <= 0
            or not args.command or args.command[0] != "--"
            or (args.completion is None) != (args.token is None)):
        parser.error("A positive deadline and an explicit command are required")
    if args.completion is not None:
        if args.admission is None or not args.commit or args.admission.is_symlink():
            parser.error("Shared execution admission is required")
        admission = json.loads(args.admission.read_text(encoding="utf-8"))
        if (
            set(admission) != {"schema_version", "interlock_version", "owner", "token", "commit", "tree"}
            | ({"jvm_lane"} if "jvm_lane" in admission else set())
            or admission["schema_version"] != 1 or admission["interlock_version"] != 2
            or admission["token"] != args.token or admission["owner"] != f"local-check:{args.token}"
            or admission["commit"] != args.commit
        ):
            parser.error("Shared execution admission differs from this exact invocation")
        try:
            admission_lane(admission)
        except ValueError as error:
            parser.error(str(error))
        # Internal shell modes require this actual inherited handoff, not a mode-name bypass.
        descriptor = os.open(args.admission, os.O_RDONLY)
        if descriptor != 8:
            os.dup2(descriptor, 8)
            os.close(descriptor)
        try:
            return supervise(args.command[1:], args.seconds, completion=args.completion, token=args.token,
                             preserve_daemons=args.preserve_daemons)
        finally:
            os.close(8)
    return supervise(args.command[1:], args.seconds, preserve_daemons=args.preserve_daemons)


if __name__ == "__main__":
    sys.exit(main())
