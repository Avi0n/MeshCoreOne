"""AndroidOnly: WP-003 Suspended-child Job ownership; never adopt an existing process."""

import ctypes
from ctypes import wintypes as w
import os
import subprocess
import time

from .errors import PortError


def directory_security(path, *, protect=False):
    """A fresh directory gets a protected current-user/SYSTEM DACL; later reads bind exact bytes."""
    advapi = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetCurrentProcess.restype = w.HANDLE
    kernel.CloseHandle.argtypes = [w.HANDLE]
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    advapi.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, ctypes.POINTER(w.HANDLE)]
    advapi.GetTokenInformation.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p,
                                           w.DWORD, ctypes.POINTER(w.DWORD)]
    advapi.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(w.LPWSTR)]
    advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [
        w.LPCWSTR, w.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p,
    ]
    advapi.SetFileSecurityW.argtypes = [w.LPCWSTR, w.DWORD, ctypes.c_void_p]
    advapi.GetFileSecurityW.argtypes = [
        w.LPCWSTR, w.DWORD, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD),
    ]
    if protect:
        token = w.HANDLE()
        WindowsJob.check(advapi.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)))
        try:
            size = w.DWORD()
            advapi.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))
            if not size.value:
                raise ctypes.WinError(ctypes.get_last_error())
            info = ctypes.create_string_buffer(size.value)
            WindowsJob.check(advapi.GetTokenInformation(token, 1, info, size, ctypes.byref(size)))
            sid = ctypes.cast(info, ctypes.POINTER(ctypes.c_void_p))[0]
            text = w.LPWSTR()
            WindowsJob.check(advapi.ConvertSidToStringSidW(sid, ctypes.byref(text)))
            descriptor = ctypes.c_void_p()
            try:
                WindowsJob.check(advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    f"D:P(A;OICI;FA;;;{text.value})(A;OICI;FA;;;SY)", 1, ctypes.byref(descriptor), None,
                ))
                WindowsJob.check(advapi.SetFileSecurityW(str(path), 4 | 0x80000000, descriptor))
            finally:
                if descriptor:
                    kernel.LocalFree(descriptor)
                kernel.LocalFree(text)
        finally:
            kernel.CloseHandle(token)
    size = w.DWORD()
    advapi.GetFileSecurityW(str(path), 1 | 4, None, 0, ctypes.byref(size))
    if not size.value:
        raise ctypes.WinError(ctypes.get_last_error())
    value = ctypes.create_string_buffer(size.value)
    WindowsJob.check(advapi.GetFileSecurityW(str(path), 1 | 4, value, size, ctypes.byref(size)))
    return value.raw[:size.value]


class Startup(ctypes.Structure):
    _fields_ = [
        ("cb", w.DWORD), ("reserved", w.LPWSTR), ("desktop", w.LPWSTR), ("title", w.LPWSTR),
        ("x", w.DWORD), ("y", w.DWORD), ("xsize", w.DWORD), ("ysize", w.DWORD),
        ("xchars", w.DWORD), ("ychars", w.DWORD), ("fill", w.DWORD), ("flags", w.DWORD),
        ("show", w.WORD), ("reserved2size", w.WORD), ("reserved2", ctypes.POINTER(ctypes.c_byte)),
        ("stdin", w.HANDLE), ("stdout", w.HANDLE), ("stderr", w.HANDLE),
    ]


class ProcessInfo(ctypes.Structure):
    _fields_ = [("process", w.HANDLE), ("thread", w.HANDLE), ("pid", w.DWORD), ("tid", w.DWORD)]


class StartupEx(ctypes.Structure):
    _fields_ = [("startup", Startup), ("attributes", ctypes.c_void_p)]


class Limits(ctypes.Structure):
    _fields_ = [
        ("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64), ("flags", w.DWORD),
        ("minimum_working_set", ctypes.c_size_t), ("maximum_working_set", ctypes.c_size_t),
        ("active_limit", w.DWORD), ("affinity", ctypes.c_size_t),
        ("priority", w.DWORD), ("scheduling", w.DWORD),
    ]


class ExtendedLimits(ctypes.Structure):
    _fields_ = [
        ("basic", Limits), ("io", ctypes.c_uint64 * 6),
        ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
        ("peak_process_memory", ctypes.c_size_t), ("peak_job_memory", ctypes.c_size_t),
    ]


class Accounting(ctypes.Structure):
    _fields_ = [
        ("user", ctypes.c_int64), ("kernel", ctypes.c_int64),
        ("period_user", ctypes.c_int64), ("period_kernel", ctypes.c_int64),
        ("faults", w.DWORD), ("total", w.DWORD), ("active", w.DWORD), ("terminated", w.DWORD),
    ]


class TcpRow(ctypes.Structure):
    _fields_ = [(name, w.DWORD) for name in ("state", "address", "port", "remote", "remote_port", "pid")]


class OwnedProcess:
    def __init__(self, job, info):
        self.job, self.handle, self.pid = job, info.process, info.pid
        self.returncode = None

    def poll(self):
        if self.returncode is not None:
            return self.returncode
        result = self.job.kernel.WaitForSingleObject(self.handle, 0)
        if result == 0x102:
            return None
        if result != 0:
            raise ctypes.WinError(ctypes.get_last_error())
        code = w.DWORD()
        self.job.check(self.job.kernel.GetExitCodeProcess(self.handle, ctypes.byref(code)))
        self.returncode = code.value
        return self.returncode

    def wait(self, timeout):
        if not 0 < timeout <= 1800:
            raise PortError("Owned process wait requires a bounded deadline")
        result = self.job.kernel.WaitForSingleObject(self.handle, int(timeout * 1000))
        if result == 0x102:
            raise subprocess.TimeoutExpired(["owned-process", str(self.pid)], timeout)
        return self.poll()


class WindowsJob:
    """An unnamed, non-breakaway job. Every child is assigned while its only thread is suspended."""

    def __init__(self):
        if os.name != "nt":
            raise PortError("Windows process jobs require the native Windows host")
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        signatures = {
            "CreateJobObjectW": ([ctypes.c_void_p, w.LPCWSTR], w.HANDLE),
            "SetInformationJobObject": ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD], w.BOOL),
            "QueryInformationJobObject": ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.c_void_p], w.BOOL),
            "AssignProcessToJobObject": ([w.HANDLE, w.HANDLE], w.BOOL),
            "IsProcessInJob": ([w.HANDLE, w.HANDLE, ctypes.POINTER(w.BOOL)], w.BOOL),
            "OpenProcess": ([w.DWORD, w.BOOL, w.DWORD], w.HANDLE),
            "CreateProcessW": ([w.LPCWSTR, w.LPWSTR, ctypes.c_void_p, ctypes.c_void_p, w.BOOL,
                                w.DWORD, ctypes.c_void_p, w.LPCWSTR,
                                ctypes.POINTER(Startup), ctypes.POINTER(ProcessInfo)], w.BOOL),
            "ResumeThread": ([w.HANDLE], w.DWORD),
            "TerminateJobObject": ([w.HANDLE, w.UINT], w.BOOL),
            "TerminateProcess": ([w.HANDLE, w.UINT], w.BOOL),
            "WaitForSingleObject": ([w.HANDLE, w.DWORD], w.DWORD),
            "GetExitCodeProcess": ([w.HANDLE, ctypes.POINTER(w.DWORD)], w.BOOL),
            "CloseHandle": ([w.HANDLE], w.BOOL),
            "GetCurrentProcess": ([], w.HANDLE),
            "DuplicateHandle": ([w.HANDLE, w.HANDLE, w.HANDLE, ctypes.POINTER(w.HANDLE),
                                  w.DWORD, w.BOOL, w.DWORD], w.BOOL),
            "InitializeProcThreadAttributeList": ([ctypes.c_void_p, w.DWORD, w.DWORD,
                                                   ctypes.POINTER(ctypes.c_size_t)], w.BOOL),
            "UpdateProcThreadAttribute": ([ctypes.c_void_p, w.DWORD, ctypes.c_size_t, ctypes.c_void_p,
                                           ctypes.c_size_t, ctypes.c_void_p, ctypes.c_void_p], w.BOOL),
            "DeleteProcThreadAttributeList": ([ctypes.c_void_p], None),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(self.kernel, name)
            function.argtypes, function.restype = arguments, result
        self.handle = self.kernel.CreateJobObjectW(None, None)
        self.check(self.handle)
        self.processes = []
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000  # KILL_ON_JOB_CLOSE; no breakaway flags.
        try:
            self.check(self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
        except OSError:
            self.kernel.CloseHandle(self.handle)
            raise

    @staticmethod
    def check(result):
        if not result:
            raise ctypes.WinError(ctypes.get_last_error())

    def spawn(self, command, environment, log, *, cwd=None):
        import msvcrt

        startup, info = StartupEx(), ProcessInfo()
        startup.startup.cb = ctypes.sizeof(startup)
        block = ctypes.create_unicode_buffer(
            "\0".join(f"{key}={value}" for key, value in sorted(environment.items(), key=lambda p: p[0].upper())) + "\0\0",
        )
        size = ctypes.c_size_t()
        self.kernel.InitializeProcThreadAttributeList(None, 1, 0, ctypes.byref(size))
        if not size.value:
            raise ctypes.WinError(ctypes.get_last_error())
        attributes = ctypes.create_string_buffer(size.value)
        self.check(self.kernel.InitializeProcThreadAttributeList(attributes, 1, 0, ctypes.byref(size)))
        startup.attributes = ctypes.cast(attributes, ctypes.c_void_p)
        duplicates = []
        try:
            with open(os.devnull, "rb") as null:
                current = self.kernel.GetCurrentProcess()
                for stream in (null, log):
                    handle = w.HANDLE()
                    self.check(self.kernel.DuplicateHandle(
                        current, msvcrt.get_osfhandle(stream.fileno()), current, ctypes.byref(handle), 0, True, 2,
                    ))
                    duplicates.append(handle)
                handles = (w.HANDLE * 2)(*duplicates)
                self.check(self.kernel.UpdateProcThreadAttribute(
                    attributes, 0, 0x20002, handles, ctypes.sizeof(handles), None, None,
                ))
                startup.startup.flags = 0x100
                startup.startup.stdin, startup.startup.stdout, startup.startup.stderr = (
                    duplicates[0], duplicates[1], duplicates[1],
                )
                # Explicit handle allowlist; never inherit unrelated host handles.
                self.check(self.kernel.CreateProcessW(
                    str(command[0]), ctypes.create_unicode_buffer(subprocess.list2cmdline(command)),
                    None, None, True, 0x4 | 0x400 | 0x8000000 | 0x80000, block, cwd,
                    ctypes.cast(ctypes.byref(startup), ctypes.POINTER(Startup)), ctypes.byref(info),
                ))
        finally:
            for handle in duplicates:
                self.kernel.CloseHandle(handle)
            self.kernel.DeleteProcThreadAttributeList(attributes)
        process = OwnedProcess(self, info)
        self.processes.append(process)
        try:
            self.check(self.kernel.AssignProcessToJobObject(self.handle, info.process))
            if self.kernel.ResumeThread(info.thread) == 0xFFFFFFFF:
                raise ctypes.WinError(ctypes.get_last_error())
        except OSError:
            # Exact suspended child handle only; never run an uncontained fallback.
            self.check(self.kernel.TerminateProcess(info.process, 125))
            process.wait(2)
            raise
        finally:
            self.kernel.CloseHandle(info.thread)
        return process

    def active(self):
        accounting = Accounting()
        self.check(self.kernel.QueryInformationJobObject(
            self.handle, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None,
        ))
        return accounting.active

    def owns_pid(self, pid):
        handle = self.kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            member = w.BOOL()
            self.check(self.kernel.IsProcessInJob(handle, self.handle, ctypes.byref(member)))
            return bool(member.value)
        finally:
            self.kernel.CloseHandle(handle)

    def owns_listener(self, port):
        import socket

        ip = ctypes.WinDLL("iphlpapi", use_last_error=True)
        query = ip.GetExtendedTcpTable
        query.argtypes = [ctypes.c_void_p, ctypes.POINTER(w.DWORD), w.BOOL, w.ULONG, ctypes.c_int, w.ULONG]
        query.restype = w.DWORD
        size = w.DWORD()
        if query(None, ctypes.byref(size), False, 2, 3, 0) != 122:
            raise PortError("Cannot size Windows owned TCP listener table")
        for _ in range(3):
            buffer = ctypes.create_string_buffer(size.value)
            result = query(buffer, ctypes.byref(size), False, 2, 3, 0)
            if result == 122:
                continue
            if result != 0:
                raise PortError(f"Cannot observe Windows owned TCP listeners: {result}")
            count = ctypes.cast(buffer, ctypes.POINTER(w.DWORD))[0]
            if ctypes.sizeof(w.DWORD) + count * ctypes.sizeof(TcpRow) > size.value:
                raise PortError("Malformed Windows TCP ownership table")
            rows = (TcpRow * count).from_buffer(buffer, ctypes.sizeof(w.DWORD))
            return any(
                row.state == 2 and socket.ntohs(row.port & 0xFFFF) == port and self.owns_pid(row.pid)
                for row in rows
            )
        raise PortError("Windows TCP ownership changed during bounded observation")

    def cleanup(self):
        self.check(self.kernel.TerminateJobObject(self.handle, 125))
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            if self.active() == 0 and all(p.poll() is not None for p in self.processes):
                return
            time.sleep(0.02)
        raise PortError("Windows owned job cleanup unproven; retain guest/Gradle receipts")

    def close(self):
        if self.active() != 0:
            raise PortError("Cannot close a live Windows guest job")
        for process in self.processes:
            self.check(self.kernel.CloseHandle(process.handle))
        self.processes.clear()
        self.check(self.kernel.CloseHandle(self.handle))
