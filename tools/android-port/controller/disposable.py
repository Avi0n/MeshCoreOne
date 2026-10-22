"""AndroidOnly: WP-003 Private disposable AVD ownership; no retained-guest recovery."""

import ctypes
from contextlib import contextmanager
import hashlib
import json
import math
import os
import signal
from pathlib import Path
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid

from .errors import PortError
from .model import SHA

RESOURCE = "disposable-native-execution"
OPERATION = "execute-private-disposable-avd"
ADAPTER = "tools/android-port/controller/disposable.py"
PACKAGING_QUIESCENCE_SECONDS = 2


def supervisor():
    from importlib import import_module

    return import_module("local.advisory" if (Path(__file__).resolve().parents[1] / "local").is_dir() else "advisory")


class PrivateGuest:
    """Use only in a dedicated single-threaded native worker, never an interactive host process."""

    def __init__(self):
        raise PortError("Allocate a fresh guest; a disposable label is not ownership")

    @classmethod
    def allocate(cls, parent, *, repository, owner, revision, identity_binding=None):
        parent = Path(parent)
        if (
            not isinstance(owner, str) or not owner or not isinstance(repository, str) or not repository
            or not isinstance(revision, str) or SHA.fullmatch(revision) is None
            or not parent.is_absolute() or not parent.is_dir() or parent.is_symlink()
            or parent.resolve() != parent
            or os.name == "posix" and (
                parent.stat().st_uid != os.getuid() or stat.S_IMODE(parent.stat().st_mode) & 0o077
            )
        ):
            raise PortError("Private guest requires an existing canonical parent and exact repository/owner/revision")
        self = object.__new__(cls)
        self.root = Path(tempfile.mkdtemp(prefix="private-avd-", dir=parent))
        os.chmod(self.root, 0o700)
        self.token = uuid.uuid4().hex
        self.repository, self.owner, self.revision = repository, owner, revision
        self.identity_binding = json.loads(json.dumps(identity_binding))
        self.creator = os.getpid()
        self.inode = (self.root.stat().st_dev, self.root.stat().st_ino)
        self.phase = "prepared"
        self.receipt = None
        self.ledger_path = None
        self.processes = []
        self.descriptors = []
        self.gradle_receipt = None
        self.job = None
        self.windows_security = None
        self.marker = json.dumps({
            "token": self.token, "repository": repository, "owner": owner, "revision": revision,
            "creator": self.creator,
            "identity_binding": self.identity_binding,
        }, sort_keys=True)
        with (self.root / "ownership.json").open("x", encoding="utf-8") as stream:
            stream.write(self.marker)
        return self

    def verify_private(self):
        if (
            self.creator != os.getpid() or self.root.is_symlink() or not self.root.is_dir()
            or self.root.resolve() != self.root
        ):
            raise PortError("Private guest identity or data directory changed")
        info = self.root.stat()
        marker = self.root / "ownership.json"
        if (
            (info.st_dev, info.st_ino) != self.inode or not marker.is_file() or marker.is_symlink()
            or marker.read_text(encoding="utf-8") != self.marker
            or json.loads(self.marker) != {
                "token": self.token, "repository": self.repository, "owner": self.owner,
                "revision": self.revision, "creator": self.creator,
                "identity_binding": self.identity_binding,
            }
            or os.name == "posix" and (stat.S_IMODE(info.st_mode) != 0o700 or info.st_uid != os.getuid())
        ):
            raise PortError("Private guest allocation/ownership changed")
        if self.windows_security is not None:
            from .windows_guest import directory_security

            if directory_security(self.root) != self.windows_security:
                raise PortError("Private Windows guest security descriptor changed")

    def fresh_payload(self, ledger, owner):
        self.verify_private()
        if (
            self.receipt is not None or self.phase != "prepared" or owner != self.owner
            or ledger.repository != self.repository or self.ledger_path != str(ledger.path.resolve())
        ):
            raise PortError("Fresh guest ownership does not match the canonical ledger/owner")
        return {
            "schema_version": 1, "wp": "WP-503", "revision": self.revision,
            "root": str(self.root), "inode": list(self.inode), "token": self.token,
            "creator": self.creator,
            "ledger": self.ledger_path,
            "identity_binding": self.identity_binding,
        }

    def acquire(self, ledger):
        if self.receipt is not None:
            raise PortError("Private guest already acquired; no nested ownership/rewrite")
        if self.ledger_path is not None and self.ledger_path != str(ledger.path.resolve()):
            raise PortError("Private guest cannot move to an alternate ledger")
        self.ledger_path = str(ledger.path.resolve())
        now = time.time()
        self.receipt = ledger.acquire_disposable(self, self.owner, now)
        self.gradle_receipt = {
            "repository": ledger.repository, "resource": "gradle-execution", "owner": self.owner,
            "payload": json.dumps({"kind": "private-guest-host-guard", "token": self.token, "revision": self.revision}),
            "acquired": now,
        }
        return dict(self.receipt)

    def verify_lease(self, ledger, owner, row, *, disposed=False, cleanup=False):
        self.verify_private()
        if (
            owner != self.owner or ledger.repository != self.repository or self.receipt is None
            or self.receipt["owner"] != owner
            or str(ledger.path.resolve()) != self.ledger_path
            or row != self.receipt or self.phase not in (
                ("disposed",) if disposed else ("prepared", "running", "packaging", "uncertain") if cleanup
                else ("prepared", "running", "packaging")
            )
        ):
            raise PortError("Missing, foreign, stale or uncertain private guest receipt")

    def assert_held(self, ledger, owner, *, cleanup=False):
        with ledger.transaction() as connection:
            row = connection.execute(
                "SELECT * FROM hard_locks WHERE repository=? AND resource=?",
                (ledger.repository, RESOURCE),
            ).fetchone()
            self.verify_lease(ledger, owner, dict(row) if row else None, cleanup=cleanup)
            guard = connection.execute(
                "SELECT * FROM hard_locks WHERE repository=? AND resource='gradle-execution'",
                (ledger.repository,),
            ).fetchone()
            if guard is None or dict(guard) != self.gradle_receipt:
                raise PortError("Missing, foreign or stale private host/Gradle guard")

    def _prepare_supervisor(self):
        if sys.platform == "win32" and os.name == "nt":
            from .windows_guest import WindowsJob, directory_security

            if threading.active_count() != 1:
                raise PortError("Windows guest ownership requires a single-threaded worker")
            if self.windows_security is None:
                self.windows_security = directory_security(self.root, protect=True)
                directory_security(self.root / "ownership.json", protect=True)
            self.verify_private()
            if self.job is None:
                self.job = WindowsJob()
            if self.job.active():
                raise PortError("Windows packaging/guest start requires the owned job to be empty")
            return
        advisory = supervisor()

        if not sys.platform.startswith("linux") or threading.active_count() != 1:
            raise PortError("Disposable ownership requires a dedicated Linux pidfd or native Windows Job worker")
        if advisory.descendants():
            raise PortError("Disposable worker must have no pre-existing children")
        libc = ctypes.CDLL(None, use_errno=True)
        libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
        libc.prctl.restype = ctypes.c_int
        if libc.prctl(36, 1, 0, 0, 0) != 0:
            raise PortError("Cannot establish disposable owned-child subreaper")
        descriptor = advisory.pidfd_open(os.getpid())
        try:
            advisory.pidfd_signal(descriptor, 0)
        finally:
            os.close(descriptor)

    def _spawn(self, command):
        advisory = supervisor()

        # Set uncertainty BEFORE the OS call: a failed launch is not proof of absent children.
        self.phase = "uncertain"
        if self.job is not None:
            process = self.job.spawn(command, self.environment, self.log)
            self.processes.append(process)
            return process
        process = subprocess.Popen(command, env=self.environment, stdin=subprocess.DEVNULL,
                                   stdout=self.log, stderr=subprocess.STDOUT, start_new_session=True)
        self.processes.append(process)
        self.descriptors.append(advisory.pidfd_open(process.pid))
        return process

    def start(self, ledger, *, emulator, adb, image, timeout=120):
        """Use installed tools/image only. Creates no key and never opens a pre-existing AVD."""
        self.assert_held(ledger, self.owner)
        if self.phase != "prepared" or type(timeout) not in (int, float) or not 0 < timeout <= 180:
            raise PortError("Private guest start requires a fresh prepared lease and bounded deadline")
        self._prepare_supervisor()
        emulator, adb, image = Path(emulator), Path(adb), Path(image)
        if any(not p.is_absolute() or p.is_symlink() or p.resolve() != p for p in (emulator, adb, image)):
            raise PortError("Private guest tools/image must be canonical installed paths")
        if not emulator.is_file() or not adb.is_file() or not image.is_dir():
            raise PortError("Installed emulator/adb/system image unavailable; no provisioning fallback")
        userdata = image / "userdata.img"
        properties = image / "source.properties"
        if not properties.is_file() or properties.is_symlink():
            raise PortError("Installed system image lacks regular initial userdata/provenance")
        if userdata.exists() or userdata.is_symlink():
            if not userdata.is_file() or userdata.is_symlink():
                raise PortError("Installed system image lacks regular initial userdata/provenance")
            copy_userdata = True
        else:
            initial_data = image / "data"
            marker = initial_data / "empty_data_disk"
            if (
                not initial_data.is_dir() or initial_data.is_symlink()
                or initial_data.resolve() != initial_data
                or not marker.is_file() or marker.is_symlink()
                or any(p.is_symlink() or p.resolve() != p or not (p.is_file() or p.is_dir())
                       for p in initial_data.rglob("*"))
            ):
                raise PortError("Installed system image lacks regular empty-data initialization provenance")
            copy_userdata = False
        values = dict(line.split("=", 1) for line in properties.read_text(encoding="utf-8").splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
        values = {k.strip(): v.strip() for k, v in values.items()}
        abi = values.get("SystemImage.Abi")
        if abi not in ("x86_64", "arm64-v8a") or values.get("AndroidVersion.ApiLevel") not in ("31", "37", "37.0"):
            raise PortError("Disposable AVD requires installed API31/37 x86_64/arm64 image provenance")
        self.image_identity = hashlib.sha256(properties.read_bytes()).hexdigest()
        avds = self.root / "avds"
        avds.mkdir()
        data = avds / (self.token + ".avd")
        data.mkdir()
        # Modern images let the emulator initialize this absent output from data/empty_data_disk.
        if copy_userdata:
            shutil.copyfile(userdata, data / "userdata-qemu.img")
        (avds / (self.token + ".ini")).write_text(f"path={data}\n", encoding="utf-8")
        (data / "config.ini").write_text(
            f"AvdId={self.token}\navd.ini.displayname={self.token}\nabi.type={abi}\n"
            f"hw.cpu.arch={'arm64' if abi == 'arm64-v8a' else 'x86_64'}\n"
            f"image.sysdir.1={image}{os.sep}\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\n"
            "disk.dataPartition.size=2048M\n", encoding="utf-8",
        )
        self.adb_tool = adb
        self.environment = {
            k: os.environ[k] for k in (
                "PATH", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "SystemRoot", "WINDIR", "COMSPEC",
            )
            if k in os.environ
        }
        self.environment.update({
            "HOME": str(self.root), "ANDROID_USER_HOME": str(self.root),
            "USERPROFILE": str(self.root), "ANDROID_EMULATOR_HOME": str(self.root),
            "ANDROID_AVD_HOME": str(avds),
        })
        (self.root / "tmp").mkdir()
        self.environment["TMPDIR"] = str(self.root / "tmp")
        self.environment["TEMP"] = self.environment["TMPDIR"]
        self.environment["TMP"] = self.environment["TMPDIR"]
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.server_port = reservation.getsockname()[1]
        self.environment["ADB_SERVER_SOCKET"] = f"tcp:127.0.0.1:{self.server_port}"
        self.environment["ANDROID_ADB_SERVER_PORT"] = str(self.server_port)
        self.log = (self.root / "process.log").open("xb")
        deadline = time.monotonic() + timeout
        try:
            self.server = self._spawn([str(adb), "-L", f"tcp:{self.server_port}", "nodaemon", "server"])
            while not self._owns_listener(self.server, self.server_port):
                self._wait(deadline)
            # Hold both ports until launch. A collision during handoff fails identity checks, never selects another guest.
            for port in range(5554, 5682, 2):
                guards = [socket.socket(), socket.socket()]
                try:
                    for guard, number in zip(guards, (port, port + 1)):
                        guard.bind(("127.0.0.1", number))
                except OSError:
                    for guard in guards:
                        guard.close()
                    continue
                for guard in guards:
                    guard.close()
                break
            else:
                raise PortError("No isolated emulator port pair is available")
            self.port = port
            self.emulator = self._spawn([
                str(emulator), "-avd", self.token, "-datadir", str(data),
                "-data", str(data / "userdata-qemu.img"), "-ports", f"{port},{port + 1}",
                "-no-snapshot", "-no-window", "-no-audio", "-show-kernel",
                "-gpu", "swiftshader_indirect",
            ])
            while not self._owns_listener(self.emulator, port + 1):
                self._wait(deadline)
            self.phase = "running"
            self.verify_transport()
        except (OSError, subprocess.SubprocessError, PortError):
            self.phase = "uncertain"
            raise

    def _wait(self, deadline):
        if time.monotonic() >= deadline or any(p.poll() is not None for p in self.processes):
            raise PortError("Private guest startup failed/timed out; owned cleanup required")
        time.sleep(0.05)

    @staticmethod
    def _under_roots(pid, roots):
        seen = set()
        while pid > 1 and pid not in seen:
            if pid in roots:
                return roots[pid].poll() is None
            seen.add(pid)
            try:
                pid = int(Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[1])
            except FileNotFoundError:
                return False
        return False

    def _socket_inodes(self, process):
        advisory = supervisor()

        if process.poll() is not None:
            raise PortError("Owned process exited; transport identity is no longer valid")
        pending, pids = [process.pid], set()
        while pending:
            pid = pending.pop()
            if pid not in pids:
                pids.add(pid)
                pending.extend(advisory.children(pid))
        inodes = set()
        for pid in pids:
            try:
                descriptor = advisory.pidfd_open(pid)
            except ProcessLookupError:
                continue
            try:
                if not self._under_roots(pid, {process.pid: process}):
                    continue
                observed = set()
                for fd in Path(f"/proc/{pid}/fd").iterdir():
                    try:
                        target = os.readlink(fd)
                    except FileNotFoundError:
                        continue
                    if target.startswith("socket:["):
                        observed.add(target[8:-1])
                try:
                    advisory.pidfd_signal(descriptor, 0)
                except ProcessLookupError:
                    continue
                if self._under_roots(pid, {process.pid: process}):
                    inodes.update(observed)
            finally:
                os.close(descriptor)
        if process.poll() is not None:
            raise PortError("Process changed while observing owned transport")
        return inodes

    def _owns_listener(self, process, port):
        if self.job is not None:
            if process.poll() is not None:
                raise PortError("Owned Windows transport process exited")
            return self.job.owns_listener(port)
        inodes = self._socket_inodes(process)
        return any(
            fields[1].rsplit(":", 1)[1] == f"{port:04X}" and fields[3] == "0A" and fields[9] in inodes
            for line in Path("/proc/net/tcp").read_text().splitlines()[1:]
            if len(fields := line.split()) >= 10
        )

    def verify_transport(self):
        self.verify_private()
        if self.phase != "running" or not self._owns_listener(self.emulator, self.port + 1):
            raise PortError("Private emulator transport is not owned/live")
        if not self._owns_listener(self.server, self.server_port):
            raise PortError("Private adb endpoint is not owned by the launched server")

    def adb(self, ledger, arguments, *, timeout=60):
        self.assert_held(ledger, self.owner)
        self.verify_transport()
        if (
            not isinstance(arguments, list) or not arguments or not all(isinstance(a, str) for a in arguments)
            or (arguments != ["wait-for-device"]
                and arguments[0] not in ("shell", "install", "push", "pull", "get-state", "logcat"))
            or type(timeout) not in (int, float) or not 0 < timeout <= 180
        ):
            raise PortError("Use a bounded selected-guest command, not adb server/global/device selection")
        try:
            command = [str(self.adb_tool), "-s", f"emulator-{self.port}", *arguments]
            if self.job is None:
                result = subprocess.run(
                    command, env=self.environment, stdin=subprocess.DEVNULL,
                    capture_output=True, timeout=timeout, check=True,
                )
            else:
                output = self.root / ("adb-" + uuid.uuid4().hex + ".log")
                with output.open("xb") as log:
                    process = self.job.spawn(command, self.environment, log)
                    code = process.wait(timeout)
                if code:
                    raise subprocess.CalledProcessError(code, command, output=output.read_bytes())
                result = subprocess.CompletedProcess(command, code, stdout=output.read_bytes(), stderr=b"")
            self.verify_transport()
            return result
        except (OSError, subprocess.SubprocessError, PortError):
            self.phase = "uncertain"
            raise

    def dispose(self, ledger):
        """Bounded own-descendant cleanup only. No disk deletion or lease release on uncertainty."""
        self.assert_held(ledger, self.owner, cleanup=True)
        if self.processes or self.job is not None or self.phase == "uncertain":
            self.phase = "uncertain"
            self._cleanup()
        for descriptor in self.descriptors:
            os.close(descriptor)
        self.descriptors.clear()
        if hasattr(self, "log"):
            self.log.close()
        if self.job is not None:
            self.job.close()
            self.job = None
        self.phase = "disposed"
        ledger.finish_disposable(self, self.owner)

    def _cleanup(self):
        if self.job is not None:
            self.job.cleanup()
            return
        advisory = supervisor()
        pinned = {}
        try:
            # Pin only live descendants observed under OUR still-pinned launched roots.
            # Unknown adopted/orphaned children are retained, never guessed or killed.
            roots = {p.pid: p for p in self.processes if p.poll() is None}

            for process in self.processes:
                if process.poll() is not None:
                    continue
                pending = [process.pid]
                while pending:
                    pid = pending.pop()
                    if pid in pinned:
                        continue
                    try:
                        descriptor = advisory.pidfd_open(pid)
                    except ProcessLookupError:
                        continue
                    if not self._under_roots(pid, roots):
                        os.close(descriptor)
                        continue
                    pinned[pid] = descriptor
                    pending.extend(advisory.children(pid))
            for number in (signal.SIGTERM, signal.SIGKILL):
                for descriptor in pinned.values():
                    try:
                        advisory.pidfd_signal(descriptor, number)
                    except ProcessLookupError:
                        continue
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    for process in self.processes:
                        process.poll()
                    for pid in pinned:
                        try:
                            os.waitpid(pid, os.WNOHANG)
                        except ChildProcessError:
                            continue
                    if not advisory.descendants():
                        return
                    time.sleep(0.02)
            raise PortError("Private guest cleanup unproven; retain lease, root and process inputs")
        finally:
            for descriptor in pinned.values():
                os.close(descriptor)

    def run_build(self, command, *, cwd, env, stream, timeout):
        """Same owned Windows job as the guest; Linux remains the dedicated subreaper boundary."""
        if self.job is None:
            return subprocess.run(command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                  timeout=timeout, check=True)
        # The wrapper requires its project cwd; CreateProcess binds it without changing host cwd.
        process = self.job.spawn(command, env, stream, cwd=str(cwd))
        code = process.wait(timeout)
        if code:
            raise subprocess.CalledProcessError(code, command)
        return subprocess.CompletedProcess(command, code)

    def capture(self, command, environment, timeout):
        if self.job is None:
            return subprocess.check_output(command, env=environment, stderr=subprocess.STDOUT, timeout=timeout)
        output = self.root / ("command-" + uuid.uuid4().hex + ".log")
        with output.open("xb") as log:
            process = self.job.spawn(command, environment, log)
            code = process.wait(timeout)
        data = output.read_bytes()
        if code:
            raise subprocess.CalledProcessError(code, command, output=data)
        return data

    def _active_children(self):
        return self.job.active() if self.job is not None else supervisor().descendants()

    def _wait_packaging_quiescence(self):
        path = self.root / ("packaging-quiescence-" + uuid.uuid4().hex + ".json")
        report = {
            "schema_version": 1, "owner": self.owner, "revision": self.revision,
            "backend": "windows-job-accounting" if self.job is not None else "linux-subreaper",
            "budget_seconds": PACKAGING_QUIESCENCE_SECONDS, "samples": [],
            "outcome": "uncertain", "elapsed_seconds": None,
        }
        failure, started = None, None
        try:
            started = time.monotonic()
            if not math.isfinite(started):
                raise PortError("Invalid packaging quiescence clock")
            previous = 0
            while True:
                active = self._active_children()
                elapsed = time.monotonic() - started
                if not math.isfinite(elapsed) or elapsed < previous:
                    raise PortError("Invalid packaging quiescence elapsed time")
                report["elapsed_seconds"] = previous = elapsed
                if self.job is not None:
                    if type(active) is not int or active < 0:
                        raise PortError("Invalid owned Windows Job active count")
                    count, pids = active, None
                else:
                    if not isinstance(active, set) or any(type(pid) is not int or pid <= 0 for pid in active):
                        raise PortError("Invalid owned Linux descendant observation")
                    count, pids = len(active), sorted(active)
                report["samples"].append({"elapsed_seconds": elapsed, "count": count, "pids": pids})
                if elapsed >= PACKAGING_QUIESCENCE_SECONDS:
                    report["outcome"] = "expired"
                    raise PortError("Packaging descendants remain/unproven at quiescence deadline")
                if count == 0:
                    report["outcome"] = "empty"
                    break
                if self.job is None:
                    supervisor().reap()
                time.sleep(min(0.02, PACKAGING_QUIESCENCE_SECONDS - elapsed))
        except BaseException as error:
            failure = error
            report["error"] = {"type": type(error).__name__, "message": str(error)}
            try:
                if started is None or not math.isfinite(started):
                    raise PortError("Packaging quiescence start time was not observed")
                elapsed = time.monotonic() - started
                if not math.isfinite(elapsed) or elapsed < (report["elapsed_seconds"] or 0):
                    raise PortError("Invalid final packaging quiescence elapsed time")
                report["elapsed_seconds"] = elapsed
            except BaseException as observation_error:
                failure = BaseExceptionGroup("Packaging quiescence and elapsed observation failed", [
                    error, observation_error,
                ])
                report["elapsed_error"] = {
                    "type": type(observation_error).__name__, "message": str(observation_error),
                }
        try:
            with path.open("x", encoding="utf-8") as stream:
                json.dump(report, stream, indent=2, allow_nan=False)
                stream.write("\n")
        except (OSError, TypeError, ValueError) as error:
            if failure is not None:
                raise BaseExceptionGroup("Packaging quiescence and diagnostic retention failed", [failure, error])
            raise
        if failure is not None:
            failure.add_note(f"Owned packaging quiescence observations: {path}")
            raise failure

    @contextmanager
    def packaging_lane(self, ledger, owner):
        """Packaging precedes guest boot; a dedicated subreaper can observe quiescent local builds."""
        self.assert_held(ledger, owner)
        if self.phase != "prepared":
            raise PortError("Private guest packaging must precede emulator/transport launch")
        self._prepare_supervisor()
        self.phase = "packaging"
        try:
            yield
        finally:
            primary = sys.exception()
            self.phase = "uncertain"
            try:
                self._wait_packaging_quiescence()
            except BaseException as error:
                if primary is not None:
                    raise BaseExceptionGroup("Packaging command and owned quiescence failed", [primary, error]) from None
                raise
            self.phase = "prepared"

    def verify_disposed(self):
        self.verify_private()
        if (
            self.phase != "disposed" or self.descriptors or any(p.poll() is None for p in self.processes)
            or self.job is not None and self.job.active()
        ):
            raise PortError("Private guest disposal is not proven")


def trusted_guest(repo, parent, *, owner, revision, session_id, attempt):
    """Fresh native worker: immutable default-base code/admission, without replacing the retained runtime."""
    from importlib import import_module
    from .capabilities import CapabilityEngine

    if sys.platform != "win32" and not sys.platform.startswith("linux"):
        raise PortError("Disposable lifecycle requires native Windows Job or Linux pidfd ownership")
    if sys.platform.startswith("linux") and "microsoft" in Path("/proc/sys/kernel/osrelease").read_text().lower():
        raise PortError("Disposable lifecycle cannot emulate the canonical Windows ledger from WSL")
    from .ledger import Ledger
    from .model import tree
    from .staging import object_bytes

    reserve = import_module("local.reserve" if (Path(__file__).resolve().parents[1] / "local").is_dir() else "reserve")
    repo = Path(repo)
    authority, _ = reserve.trusted_default_policy(repo)
    entries = tree(repo, authority)
    source_root = Path(__file__).resolve().parents[3]
    if source_root != repo.resolve():
        raise PortError("Disposable native worker must load the exact verified source checkout, not another runtime")
    if Path(sys.modules[Ledger.__module__].__file__).resolve() != source_root / "tools/android-port/controller/ledger.py":
        raise PortError("Disposable worker loaded a different Ledger runtime")
    sources = [
        *reserve.runtime_sources().values(),
        source_root / "tools/android-port/local/debug_packaging.py",
        source_root / "tools/android-port/controller/gradle_windows.ps1",
    ]
    for source in sources:
        relative = source.resolve().relative_to(source_root).as_posix()
        if relative not in entries or source.read_bytes().replace(b"\r\n", b"\n") != object_bytes(repo, entries[relative]):
            raise PortError(f"Disposable worker code differs from immutable trusted default-base: {relative}")
    manifest, policy = reserve.trusted_capability_authority(repo, expected_revision=authority)
    admission = CapabilityEngine(policy).admit("WP-503", manifest.wp("WP-503")["write_paths"], ADAPTER, OPERATION)
    if policy.get("paused") is not False:
        raise PortError("Trusted policy pauses native execution")
    if RESOURCE not in policy["reservation_policy"]["hard_lock_resources"]:
        raise PortError("Trusted default-base has not admitted disposable native resources")
    ledger = Ledger(reserve.shared_ledger(repo), policy["repository"])
    actual = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], encoding="utf-8").strip()
    if actual != revision:
        raise PortError("Disposable guest revision differs from the actual candidate")
    incumbent = ledger.get("WP-503")
    if (
        incumbent is None or incumbent["attempt"] != attempt
        or incumbent["identity"]["session_id"] != session_id
        or incumbent["state"] not in ("running", "repair_wait", "review_wait", "blocked")
        or not isinstance(session_id, str) or not session_id
    ):
        raise PortError("Disposable guest requires the existing WP-503 session/attempt, not a new actor")
    if (
        not isinstance(incumbent["capabilities"], dict)
        or not isinstance(incumbent["reservation_operations"], list)
        or not isinstance(incumbent["capabilities"].get(admission.capability_id), list)
        or ADAPTER not in incumbent["capabilities"][admission.capability_id]
        or OPERATION not in incumbent["reservation_operations"]
    ):
        raise PortError("Existing WP-503 receipt must first evolve through trusted disposable admission")
    guest = PrivateGuest.allocate(
        parent, repository=ledger.repository, owner=owner, revision=revision,
        identity_binding={
            "session_id": session_id, "attempt": attempt, "binding": incumbent["binding"],
            "trusted_revision": authority, "reservation_revision": incumbent["revision"],
        },
    )
    guest.acquire(ledger)
    return ledger, guest
