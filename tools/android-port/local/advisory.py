"""AndroidOnly: WP-504 Bound advisory checks and reap only their own Linux descendants."""

import argparse
import ctypes
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

CLEANUP_SECONDS = 2


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
    while descendants():
        signal_owned(signal.SIGKILL)
        reap()
        time.sleep(0.02)
    reap()


def supervise(command, seconds):
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
    process = subprocess.Popen(command, start_new_session=True, pass_fds=(9,))
    try:
        try:
            return process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            print(f"UNVERIFIED: advisory fast check exceeded {seconds:g}s; cleaning owned descendants",
                  file=sys.stderr, flush=True)
            return 124
    finally:
        # The inherited lane FD stays in this supervisor until all owned work is gone.
        cleanup()
        process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.seconds <= 0 or not args.command or args.command[0] != "--":
        parser.error("A positive deadline and an explicit command are required")
    return supervise(args.command[1:], args.seconds)


if __name__ == "__main__":
    sys.exit(main())
