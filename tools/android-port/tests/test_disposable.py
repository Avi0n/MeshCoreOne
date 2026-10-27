"""AndroidOnly: WP-003 Private disposable guests; fixtures never start Android tools."""

import copy
from contextlib import closing, contextmanager
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

from fixtures import BASE, NOW, REPO, policy
from controller.capabilities import CapabilityEngine
from controller.disposable import ADAPTER, OPERATION, RESOURCE, PrivateGuest, trusted_guest
from controller.errors import PortError
from controller.ledger import Identity, Ledger
from controller.schema import load_json
from controller.scope_amendment import (
    bubble_consumer_predecessor, disposable_predecessor, parity_support_predecessor, room_cleanup_predecessor,
    room_failed_event_predecessor, wp503_accessibility_source_binding_predecessor,
    room_failed_event_predecessor, consumer_support_predecessor, host_scoped_jvm_predecessor,
    backup_post_commit_predecessor,
    rx_projection_regression_predecessor,
    shared_location_cache_predecessor,
    reaction_wire_classification_predecessor,
    shortcut_lifecycle_test_predecessor,
)
from local import debug_packaging


class DisposableGuestTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.ledger = Ledger(self.root / "ledger.sqlite", "cbattlegear/MeshCoreOne-Android")
        self.ledger.claim(
            {"id": "WP-503", "write_paths": ["android/accessibility-tests/"]}, {"base_sha": BASE},
            "local", "original-attempt", NOW, 1800, 5, 0, 100, 5,
            initial_identity=Identity(session_id="original-session"),
            branch="original-branch", worktree=str(self.root / "original-worktree"),
            authorization_context={"authorization": "immutable historical fixture authorization"},
        )
        before_history = self.history()
        self.addCleanup(lambda: self.assertEqual(self.history(), before_history))
        self.ledger.acquire_hard_lock("native-execution", "retained-legacy", {
            "wp": "WP-503", "endpoint": "canonical:old-guest", "original": ["unchanged", 473],
        }, NOW)
        self.legacy = self.rows()
        self.status_boundary = patch.object(PrivateGuest, "_status_snapshot")
        self.status_boundary.start()
        self.addCleanup(self.status_boundary.stop)

    def rows(self):
        with closing(sqlite3.connect(self.ledger.path)) as connection:
            return connection.execute("SELECT rowid,* FROM hard_locks ORDER BY repository,resource").fetchall()

    def history(self):
        with closing(sqlite3.connect(self.ledger.path)) as connection:
            return connection.execute("SELECT rowid,* FROM leases ORDER BY repository,wp").fetchall()

    def allocate(self, owner="new-owner"):
        return PrivateGuest.allocate(
            self.root, repository=self.ledger.repository, owner=owner, revision=BASE,
        )

    def assert_legacy(self):
        self.assertEqual([row for row in self.rows() if row[2] == "native-execution"], self.legacy)

    def status_reports(self, guest):
        return [json.loads(path.read_text()) for path in sorted(guest.root.glob("owned-status-*.json"))]

    def boot_fixture(self, windows):
        from unittest.mock import Mock

        guest = self.allocate()
        guest.acquire(self.ledger)
        guest.phase, guest.boot_readiness_available = "running", True
        guest.adb_tool, guest.port, guest.server_port = self.root / "adb", 5558, 41234
        guest.environment = {"ADB_SERVER_SOCKET": "tcp:127.0.0.1:41234", "ANDROID_ADB_SERVER_PORT": "41234"}
        guest.server, guest.emulator = Mock(pid=801), Mock(pid=802)
        guest.server.poll.return_value = guest.emulator.poll.return_value = None
        if windows:
            guest.job = Mock()
            guest.job.active.return_value = 3
        return guest

    def test_scoped_exact_boot_offline_reattaches_only_selected_serial_and_rereads_both_properties(self):
        from unittest.mock import Mock
        from controller.disposable import BootReadinessOffline

        self.status_boundary.stop()
        for windows, diagnostic in (
            (False, b"adb: device offline\n"),
            (True, b"adb.exe: device offline\n"),
            (True, b"adb.exe: device offline\r\n"),
        ):
            with self.subTest(windows=windows, diagnostic=diagnostic):
                guest = self.boot_fixture(windows)
                before, commands, budgets = self.rows(), [], []
                property_commands = [["shell", "getprop", name] for name in ("sys.boot_completed", "init.svc.bootanim")]
                responses = iter([b"1\n", b"offline", b"", b"0\n", b"running\n", b"1\n", b"stopped\n"])

                def invoke(command, timeout, log=None):
                    commands.append(command)
                    budgets.append(timeout)
                    data = next(responses)
                    if windows:
                        process = Mock(pid=803)
                        process.poll.return_value = 1 if data == b"offline" else 0
                        process.wait.return_value = 1 if data == b"offline" else 0
                        log.write(diagnostic if data == b"offline" else data)
                        process.wait.side_effect = lambda passed: self.assertEqual(passed, timeout) or process.wait.return_value
                        return process
                    if data == b"offline":
                        raise subprocess.CalledProcessError(1, command, output=b"", stderr=diagnostic)
                    return subprocess.CompletedProcess(command, 0, stdout=data, stderr=b"")

                def spawn(command, environment, log):
                    return invoke(command, 17, log)

                with patch.object(guest, "_owns_listener", return_value=True), \
                        patch("controller.disposable.time.monotonic", return_value=10), \
                        patch("controller.disposable.subprocess.run",
                              side_effect=lambda command, **kwargs: invoke(command, kwargs["timeout"])):
                    if windows:
                        guest.job.spawn.side_effect = spawn
                    with guest.boot_readiness(self.ledger, deadline=130):
                        self.assertEqual(guest.adb(self.ledger, property_commands[0], timeout=17).stdout, b"1\n")
                        with self.assertRaises(BootReadinessOffline) as raised:
                            guest.adb(self.ledger, property_commands[1], timeout=17)
                        self.assertEqual(guest.phase, "boot-offline")
                        self.assertEqual(raised.exception.returncode, 1)
                        self.assertEqual(raised.exception.cmd, [str(guest.adb_tool), "-s", "emulator-5558",
                                                               *property_commands[1]])
                        self.assertEqual((raised.exception.output, raised.exception.stderr),
                                         (diagnostic, None) if windows else (b"", diagnostic))
                        if windows:
                            self.assertIn(diagnostic, [p.read_bytes() for p in guest.root.glob("adb-*.log")])
                        with self.assertRaises(PortError):
                            guest.adb(self.ledger, property_commands[0], timeout=17)
                        guest.reattach_boot(self.ledger, timeout=17)
                        self.assertEqual(guest.phase, "running")
                        # Consumer discards pre-offline boot1; both fresh values may still be unready.
                        self.assertEqual([guest.adb(self.ledger, c, timeout=17).stdout for c in property_commands],
                                         [b"0\n", b"running\n"])
                        self.assertEqual([guest.adb(self.ledger, c, timeout=17).stdout for c in property_commands],
                                         [b"1\n", b"stopped\n"])
                self.assertEqual([c[3:] for c in commands],
                                 [*property_commands, ["wait-for-device"], *property_commands, *property_commands])
                self.assertTrue(all(c[:3] == [str(guest.adb_tool), "-s", "emulator-5558"] for c in commands))
                self.assertEqual(budgets, [17] * 7)
                self.assertEqual(self.rows(), before)
                self.assertEqual(guest.phase, "running")
                self.assertIsNone(guest.boot_deadline)
                guest.job = None
                guest.phase = "uncertain"
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_boot_offline_classification_rejects_other_diagnostics_commands_and_default_calls(self):
        from unittest.mock import Mock

        for windows in (False, True):
            for case in ("default", "default-mutation", "extra", "missing-newline", "bare-cr",
                         "double-cr", "double-line", "prefix", "wrong-stream", "wrong-code",
                         "mutation", "timeout", "ownership", "linux-crlf"):
                if windows and case == "linux-crlf":
                    continue
                with self.subTest(windows=windows, case=case):
                    guest = self.boot_fixture(windows)
                    before = self.rows()
                    arguments = ["shell", "getprop", "sys.boot_completed"]
                    if case == "default-mutation":
                        arguments = ["install", "fixture.apk"]
                    command = [str(guest.adb_tool), "-s", "emulator-5558", *arguments]
                    diagnostic = b"adb.exe: device offline\n" if windows else b"adb: device offline\n"
                    if case == "extra":
                        diagnostic += b"unknown context"
                    if case == "missing-newline":
                        diagnostic = diagnostic[:-1]
                    if case == "bare-cr":
                        diagnostic = diagnostic[:-1] + b"\r"
                    if case == "double-cr":
                        diagnostic = diagnostic[:-1] + b"\r\r\n"
                    if case == "double-line":
                        diagnostic += b"\n"
                    if case == "prefix":
                        diagnostic = b" " + diagnostic
                    if case == "linux-crlf":
                        diagnostic = b"adb: device offline\r\n"
                    code = 7 if case == "wrong-code" else 1
                    output, stderr = (diagnostic, None) if windows else (b"", diagnostic)
                    if case == "wrong-stream":
                        output, stderr = (b"", diagnostic) if windows else (diagnostic, b"")
                    primary = subprocess.CalledProcessError(code, command, output=output, stderr=stderr)
                    if case == "timeout":
                        primary = subprocess.TimeoutExpired(command, 17)
                    process = Mock(pid=803)
                    process.wait.side_effect = primary if case == "timeout" else None
                    process.wait.return_value = code

                    def spawn(argv, environment, log):
                        log.write(output)
                        return process

                    if windows:
                        guest.job.spawn.side_effect = spawn
                    listeners = [True] * 20
                    if case == "ownership":
                        listeners = [True, True, True, True, False]
                    with patch.object(guest, "_owns_listener", side_effect=listeners), \
                            patch("controller.disposable.time.monotonic", return_value=10), \
                            patch("controller.disposable.subprocess.run", side_effect=primary):
                        if case in ("default", "default-mutation"):
                            with self.assertRaises(subprocess.CalledProcessError):
                                guest.adb(self.ledger, arguments, timeout=17)
                        else:
                            with self.assertRaises(BaseException) as raised:
                                with guest.boot_readiness(self.ledger, deadline=130):
                                    if case == "mutation":
                                        arguments = ["install", "fixture.apk"]
                                    guest.adb(self.ledger, arguments, timeout=17)
                            if case == "ownership":
                                self.assertIsInstance(raised.exception, BaseExceptionGroup)
                                self.assertIsInstance(raised.exception.exceptions[0], subprocess.CalledProcessError)
                                self.assertEqual(raised.exception.exceptions[0].returncode, 1)
                                self.assertEqual(raised.exception.exceptions[0].output, output)
                    self.assertEqual(guest.phase, "uncertain")
                    with self.assertRaises(PortError):
                        guest.reattach_boot(self.ledger, timeout=17)
                    self.assertEqual(self.rows(), before)
                    guest.job = None
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                    self.assert_legacy()

    def test_boot_scope_requires_new_start_exact_lease_and_finite_original_deadline(self):
        for case in ("not-new", "expired", "too-long", "nan", "foreign"):
            with self.subTest(case=case):
                guest = self.boot_fixture(False)
                before = self.rows()
                if case == "not-new":
                    guest.boot_readiness_available = False
                if case == "foreign":
                    guest.receipt = {**guest.receipt, "owner": "foreign"}
                deadline = {"expired": 10, "too-long": 131, "nan": float("nan")}.get(case, 130)
                with patch.object(guest, "_owns_listener", return_value=True), \
                        patch("controller.disposable.time.monotonic", return_value=10):
                    with self.assertRaises(PortError):
                        with guest.boot_readiness(self.ledger, deadline=deadline):
                            self.fail("Invalid readiness scope entered")
                self.assertEqual(self.rows(), before)
                if case == "foreign":
                    guest.receipt["owner"] = guest.owner
                guest.dispose(self.ledger)
                self.assert_legacy()

    def test_boot_offline_primary_survives_independent_snapshot_retention_clock_and_cancellation(self):
        from unittest.mock import Mock
        from controller.disposable import BootReadinessOffline

        original_open = Path.open
        for failure in ("snapshot", "write", "close", "clock", "cancelled"):
            with self.subTest(failure=failure):
                guest = self.boot_fixture(False)
                before = self.rows()
                arguments = ["shell", "getprop", "sys.boot_completed"]
                command = [str(guest.adb_tool), "-s", "emulator-5558", *arguments]
                primary = subprocess.CalledProcessError(1, command, output=b"", stderr=b"adb: device offline\n")
                independent = KeyboardInterrupt("diagnostic cancellation") if failure == "cancelled" else OSError(failure)
                clock, opened = [10], []

                def open_path(path, *args, **kwargs):
                    stream = original_open(path, *args, **kwargs)
                    if not path.name.startswith("transport-timing-"):
                        return stream
                    opened.append(stream)
                    wrapped = Mock(wraps=stream)

                    def write(data):
                        if json.loads(data)["stage"] == "failed" and failure in ("write", "cancelled"):
                            raise independent
                        return stream.write(data)

                    def close():
                        stream.close()
                        if failure == "close":
                            raise independent

                    wrapped.write.side_effect, wrapped.close.side_effect = write, close
                    return wrapped

                def snapshot(*args, **kwargs):
                    if failure == "snapshot":
                        raise independent
                    if failure == "clock":
                        clock[0] = float("nan")

                try:
                    with patch.object(guest, "_owns_listener", return_value=True), \
                            patch.object(guest, "_status_snapshot", side_effect=snapshot), \
                            patch("controller.disposable.time.monotonic", side_effect=lambda: clock[0]), \
                            patch.object(Path, "open", open_path), \
                            patch("controller.disposable.subprocess.run", side_effect=primary):
                        with self.assertRaises(BaseExceptionGroup) as raised:
                            with guest.boot_readiness(self.ledger, deadline=130):
                                guest.adb(self.ledger, arguments, timeout=17)
                    first = raised.exception.exceptions[0]
                    self.assertIsInstance(first, BootReadinessOffline)
                    self.assertIs(first.__cause__, primary)
                    self.assertEqual((first.returncode, first.output, first.stderr), (1, b"", b"adb: device offline\n"))
                    self.assertEqual(len(raised.exception.exceptions), 2)
                    if failure != "clock":
                        self.assertIs(raised.exception.exceptions[1], independent)
                    self.assertEqual(guest.phase, "uncertain")
                    self.assertEqual(self.rows(), before)
                    with patch.object(guest, "_cleanup", side_effect=PortError("independent disposal")):
                        with self.assertRaisesRegex(PortError, "independent disposal"):
                            guest.dispose(self.ledger)
                    self.assertEqual(self.rows(), before)
                finally:
                    for stream in opened:
                        stream.close()
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                self.assert_legacy()

    def test_repeated_offline_recovery_never_extends_deadline_and_failed_reattach_is_uncertain(self):
        from controller.disposable import BootReadinessOffline

        for failure in ("expired", "timeout", "unrelated", "stale", "listener"):
            with self.subTest(failure=failure):
                guest = self.boot_fixture(False)
                original_receipt = dict(guest.receipt)
                before, clock, calls = self.rows(), [10.0], []
                args = ["shell", "getprop", "init.svc.bootanim"]

                def run(command, **kwargs):
                    calls.append((command[3:], kwargs["timeout"]))
                    if command[3:] == args:
                        raise subprocess.CalledProcessError(1, command, output=b"", stderr=b"adb: device offline\n")
                    if len(calls) <= 2:
                        return subprocess.CompletedProcess(command, 0, stdout=b"", stderr=b"")
                    if failure == "timeout":
                        raise subprocess.TimeoutExpired(command, kwargs["timeout"])
                    raise subprocess.CalledProcessError(1, command, output=b"", stderr=b"other error")

                with patch.object(guest, "_owns_listener", return_value=True), \
                        patch("controller.disposable.time.monotonic", side_effect=lambda: clock[0]), \
                        patch("controller.disposable.subprocess.run", side_effect=run):
                    with self.assertRaises((PortError, subprocess.SubprocessError)):
                        with guest.boot_readiness(self.ledger, deadline=130):
                            for round_number in range(2):
                                with self.assertRaises(BootReadinessOffline):
                                    guest.adb(self.ledger, args, timeout=17)
                                if round_number:
                                    clock[0] = 130 if failure == "expired" else 129
                                    if failure == "stale":
                                        guest.receipt = {**guest.receipt, "owner": "foreign"}
                                    if failure == "listener":
                                        guest.emulator.poll.return_value = 7
                                if failure == "listener" and round_number:
                                    with patch.object(guest, "_owns_listener", return_value=False):
                                        guest.reattach_boot(self.ledger, timeout=17)
                                else:
                                    guest.reattach_boot(self.ledger, timeout=17)
                self.assertEqual(guest.phase, "uncertain")
                if failure in ("timeout", "unrelated"):
                    self.assertEqual(calls[-1], (["wait-for-device"], 1))
                else:
                    self.assertEqual(len(calls), 3)
                self.assertEqual(self.rows(), before)
                guest.receipt = original_receipt
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_cpu_selection_rejects_invalid_values_before_ownership_supervisor_files_or_processes(self):
        for value in (True, False, 0, -1, 1, 2, 3, 5, 8, 4.0, "4", "", [], {}, float("nan"), float("inf")):
            with self.subTest(value=value):
                guest = self.allocate()
                before = self.rows()
                files = {p.name: p.read_bytes() for p in guest.root.iterdir()}
                with patch.object(guest, "assert_held") as held, \
                        patch.object(guest, "_prepare_supervisor") as prepare, \
                        patch.object(guest, "_spawn") as spawn:
                    with self.assertRaisesRegex(PortError, "CPU selection"):
                        guest.start(self.ledger, emulator="not-installed", adb="not-installed",
                                    image="not-installed", cpu_cores=value)
                    held.assert_not_called()
                    prepare.assert_not_called()
                    spawn.assert_not_called()
                self.assertEqual({p.name: p.read_bytes() for p in guest.root.iterdir()}, files)
                self.assertEqual(self.rows(), before)
                self.assertEqual(guest.phase, "prepared")
                self.assertFalse(hasattr(guest, "requested_cpu_cores"))
                self.assert_legacy()

    def test_cpu_default_and_explicit_four_feed_actual_avd_config_and_existing_runtime_provenance(self):
        from unittest.mock import Mock

        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text("AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n")
        (image / "userdata.img").write_bytes(b"immutable initial data")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        configs = []
        for selected in ("omitted", None, 4):
            with self.subTest(selected=selected):
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                server, process = Mock(pid=701), Mock(pid=702)
                server.poll.return_value = process.poll.return_value = None

                def spawn(command):
                    if command[0] == str(adb):
                        self.assertEqual(command, [str(adb), "-L", f"tcp:{guest.server_port}", "nodaemon", "server"])
                        return server
                    data = guest.root / "avds" / (guest.token + ".avd")
                    self.assertEqual(command, [
                        str(emulator), "-avd", guest.token, "-datadir", str(data),
                        "-data", str(data / "userdata-qemu.img"), "-ports", f"{guest.port},{guest.port + 1}",
                        "-no-snapshot", "-no-window", "-no-audio", "-show-kernel", "-gpu", "swiftshader_indirect",
                    ])
                    config = (data / "config.ini").read_text()
                    expected = (
                        f"AvdId={guest.token}\navd.ini.displayname={guest.token}\nabi.type=x86_64\n"
                        "hw.cpu.arch=x86_64\n"
                        + ("hw.cpu.ncore=4\n" if selected == 4 else "")
                        + f"image.sysdir.1={image}{os.sep}\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\n"
                        "disk.dataPartition.size=2048M\n"
                    )
                    self.assertEqual(config, expected)
                    configs.append(config.replace(guest.token, "TOKEN"))
                    return process

                kwargs = {} if selected == "omitted" else {"cpu_cores": selected}
                with patch.object(guest, "_prepare_supervisor"), \
                        patch.object(guest, "_spawn", side_effect=spawn), \
                        patch.object(guest, "_owns_listener", return_value=True), \
                        patch.object(guest, "verify_transport"):
                    guest.start(self.ledger, emulator=emulator, adb=adb, image=image, timeout=120, **kwargs)
                records = self.timing_records(guest)
                self.assertTrue(all(r["requested_cpu_cores"] == (4 if selected == 4 else None) for r in records))
                self.assertTrue(all(r["timeout_seconds"] == 120 for r in records))
                self.assertEqual(guest.phase, "running")
                self.assertEqual(self.rows(), before)
                self.assertEqual((image / "userdata.img").read_bytes(), b"immutable initial data")
                self.status_boundary.stop()
                try:
                    guest.phase = "uncertain"
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                    status = self.status_reports(guest)[0]
                    self.assertEqual(status["requested_cpu_cores"], 4 if selected == 4 else None)
                finally:
                    self.status_boundary.start()
                self.assertEqual(self.rows(), self.legacy)
        self.assertEqual(configs[0], configs[1])
        self.assertEqual(configs[2].replace("hw.cpu.ncore=4\n", ""), configs[0])

    def test_status_snapshot_observes_owned_roots_before_real_windows_cleanup_termination(self):
        from unittest.mock import Mock
        from controller.windows_guest import OwnedProcess, WindowsJob

        self.status_boundary.stop()
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        guest.phase = "uncertain"
        state, calls = {"terminated": False}, []
        job = object.__new__(WindowsJob)
        job.handle, job.kernel = 99, Mock()
        job.check = lambda value: None if value else self.fail("fixture API failed")

        def query(handle, kind, buffer, size, returned):
            calls.append("accounting-after" if state["terminated"] else "accounting-before")
            buffer._obj.active = 0 if state["terminated"] else 3
            return 1

        def terminate(handle, code):
            reports = self.status_reports(guest)
            self.assertEqual(len(reports), 1)
            self.assertEqual(reports[0]["job_active_count"], 3)
            self.assertEqual([r["returncode"] for r in reports[0]["roots"]], [7, None, None])
            calls.append("terminate")
            state["terminated"] = True
            return 1

        def wait(handle, milliseconds):
            self.assertEqual(milliseconds, 0)
            calls.append("poll-after" if state["terminated"] else "poll-before")
            return 0 if handle == 301 or state["terminated"] else 0x102

        def exit_code(handle, target):
            target._obj.value = 7 if handle == 301 else 125
            return 1

        job.kernel.WaitForSingleObject.side_effect = wait
        job.kernel.GetExitCodeProcess.side_effect = exit_code
        guest.server = OwnedProcess(job, types.SimpleNamespace(process=301, pid=201))
        guest.emulator = OwnedProcess(job, types.SimpleNamespace(process=302, pid=202))
        guest.processes = job.processes = [guest.server, guest.emulator]
        guest.job = job
        job.kernel.QueryInformationJobObject.side_effect = query
        job.kernel.TerminateJobObject.side_effect = terminate
        guest.dispose(self.ledger)
        report = self.status_reports(guest)[0]
        self.assertEqual(report["stage"], "before-cleanup")
        self.assertEqual(report["roots"][0]["status"], "exited")
        self.assertEqual(report["roots"][1]["status"], "not-exited")
        self.assertEqual(report["roots"][2]["status"], "unavailable")
        self.assertIsNone(report["job_pids"])
        self.assertIn("not whole guest", report["scope"])
        self.assertLess(calls.index("accounting-before"), calls.index("terminate"))
        self.assertEqual(report["roots"][0]["returncode"], 7)
        self.assertEqual(guest.server.poll(), 7)
        self.assertEqual(guest.emulator.poll(), 125)
        self.assertEqual(guest.phase, "disposed")
        self.assertNotEqual(self.rows(), before)
        self.assertEqual(self.rows(), self.legacy)

    def test_status_failure_snapshots_preserve_timeout_nonzero_cancellation_and_linux_unavailable_membership(self):
        from unittest.mock import Mock

        self.status_boundary.stop()
        for windows in (False, True):
            for outcome in ("timeout", "nonzero", "cancelled"):
                with self.subTest(windows=windows, outcome=outcome):
                    guest = self.allocate()
                    guest.acquire(self.ledger)
                    guest.phase = "running"
                    guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
                    guest.server, guest.emulator = Mock(pid=301), Mock(pid=302)
                    guest.server.poll.return_value = None
                    guest.emulator.poll.return_value = 9
                    before = self.rows()
                    command = [str(guest.adb_tool), "-s", "emulator-5558", "wait-for-device"]
                    primary = {"timeout": subprocess.TimeoutExpired(command, 120),
                               "nonzero": subprocess.CalledProcessError(7, command),
                               "cancelled": KeyboardInterrupt("cancelled")}[outcome]
                    selected = Mock(pid=303)
                    selected.poll.return_value = 7 if outcome == "nonzero" else None
                    selected.wait.side_effect = None if outcome == "nonzero" else primary
                    selected.wait.return_value = 7
                    if windows:
                        guest.job = Mock()
                        guest.job.spawn.return_value = selected
                        guest.job.active.return_value = 3
                    with patch.object(guest, "verify_transport"), \
                            patch("controller.disposable.subprocess.run", side_effect=primary):
                        with self.assertRaises(type(primary)) as raised:
                            guest.adb(self.ledger, ["wait-for-device"], timeout=120)
                    if not windows or outcome != "nonzero":
                        self.assertIs(raised.exception, primary)
                    else:
                        self.assertEqual(raised.exception.returncode, 7)
                    report = self.status_reports(guest)[0]
                    self.assertEqual(report["stage"], "operation-failure")
                    self.assertEqual(report["job_active_count"], 3 if windows else None)
                    self.assertEqual(report["roots"][2]["pid"], 303 if windows else None)
                    self.assertEqual(report["roots"][2]["status"],
                                     ("exited" if outcome == "nonzero" else "not-exited") if windows else "unavailable")
                    self.assertEqual(report["roots"][1]["returncode"], 9)
                    self.assertEqual(report["owner"], guest.owner)
                    self.assertEqual(report["revision"], guest.revision)
                    self.assertEqual(self.rows(), before)
                    guest.job = None
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                    self.assertEqual(guest.phase, "disposed")
                    self.assert_legacy()

    def test_status_observation_retention_and_cleanup_failures_preserve_primary_and_receipts(self):
        from unittest.mock import Mock

        self.status_boundary.stop()
        for failure in ("poll", "job", "clock", "write"):
            with self.subTest(failure=failure):
                guest = self.allocate()
                guest.acquire(self.ledger)
                guest.phase = "running"
                guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
                guest.server = Mock(pid=401)
                guest.server.poll.return_value = None
                guest.job = Mock()
                guest.job.active.return_value = 2
                process = Mock(pid=402)
                process.poll.return_value = None
                primary = subprocess.TimeoutExpired(["fixture"], 120)
                process.wait.side_effect = primary
                guest.job.spawn.return_value = process
                before = self.rows()
                if failure == "poll":
                    guest.server.poll.side_effect = OSError("owned handle query failed")
                if failure == "job":
                    guest.job.active.side_effect = OSError("Job accounting failed")
                original_open = Path.open

                def open_path(path, *args, **kwargs):
                    if failure == "write" and path.name.startswith("owned-status-"):
                        raise OSError("status retention failed")
                    return original_open(path, *args, **kwargs)

                original_time = __import__("time").time_ns
                ticks = [0]

                def utc():
                    ticks[0] += 1
                    if failure == "clock" and ticks[0] in (4, 5, 6, 7):
                        raise OSError("status clock unavailable")
                    return original_time()

                with patch.object(guest, "verify_transport"), patch.object(Path, "open", open_path), \
                        patch("controller.disposable.time.time_ns", side_effect=utc):
                    with self.assertRaises(ExceptionGroup) as raised:
                        guest.adb(self.ledger, ["wait-for-device"], timeout=120)
                self.assertIs(raised.exception.exceptions[0], primary)
                self.assertEqual(len(raised.exception.exceptions), 2)
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                with patch.object(guest, "_cleanup", side_effect=PortError("independent cleanup failed")), \
                        patch.object(Path, "open", open_path):
                    with self.assertRaises(BaseExceptionGroup if failure != "clock" else PortError) as cleanup:
                        guest.dispose(self.ledger)
                self.assertEqual(self.rows(), before)
                self.assertEqual(guest.phase, "uncertain")
                if failure != "write":
                    self.assertTrue(next(r for r in self.status_reports(guest)
                                         if r["stage"] == "operation-failure")["errors"])
                guest.server.poll.side_effect = None
                guest.job = None
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_status_snapshot_failure_never_replaces_return7_or_cancellation(self):
        from unittest.mock import Mock

        self.status_boundary.stop()
        for cancelled in (False, True):
            with self.subTest(cancelled=cancelled):
                guest = self.allocate()
                guest.acquire(self.ledger)
                guest.phase = "running"
                guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
                guest.job = Mock()
                guest.job.active.side_effect = OSError("independent status accounting failure")
                process = Mock(pid=501)
                process.poll.return_value = None if cancelled else 7
                primary = KeyboardInterrupt("actual cancellation")
                process.wait.side_effect = primary if cancelled else None
                process.wait.return_value = 7
                before = self.rows()

                def spawn(command, environment, log):
                    log.write(b"retained raw exit bytes")
                    return process

                guest.job.spawn.side_effect = spawn
                with patch.object(guest, "verify_transport"):
                    with self.assertRaises(BaseExceptionGroup) as raised:
                        guest.adb(self.ledger, ["wait-for-device"], timeout=120)
                first, snapshot = raised.exception.exceptions
                if cancelled:
                    self.assertIs(first, primary)
                else:
                    self.assertIsInstance(first, subprocess.CalledProcessError)
                    self.assertEqual(first.returncode, 7)
                    self.assertEqual(first.output, b"retained raw exit bytes")
                self.assertIsInstance(snapshot, ExceptionGroup)
                process.wait.assert_called_once_with(120)
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                guest.job = None
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assertEqual(next(guest.root.glob("adb-*.log")).read_bytes(), b"retained raw exit bytes")
                self.assert_legacy()

    def test_actual_fresh_directories_are_distinct_and_never_adopt_existing_data(self):
        first, second = self.allocate(), self.allocate()
        self.assertNotEqual(first.root, second.root)
        self.assertNotEqual(first.token, second.token)
        self.assertNotEqual(first.inode, second.inode)
        self.assertEqual(first.phase, "prepared")
        first.verify_private()
        with self.assertRaises(PortError):
            PrivateGuest()
        with self.assertRaises(PortError):
            self.ledger.acquire_hard_lock(RESOURCE, "label-only", {"disposable": True}, NOW)
        with self.assertRaises(PortError):
            self.ledger.acquire_disposable({"disposable": True}, "label-only", NOW)
        self.assertEqual(self.rows(), self.legacy)

    def test_legacy_coexists_byte_for_byte_and_prelaunch_disposal_does_not_remove_data(self):
        guest = self.allocate()
        receipt = guest.acquire(self.ledger)
        self.assertEqual(receipt["owner"], "new-owner")
        self.assert_legacy()
        guest.dispose(self.ledger)
        self.assertEqual(self.rows(), self.legacy)
        self.assertTrue((guest.root / "ownership.json").is_file())
        self.assertEqual(guest.phase, "disposed")
        with self.assertRaises(PortError):
            guest.acquire(self.ledger)

    def test_actual_main_legacy_ledger_sees_the_real_global_host_guard_and_cannot_enter_gradle(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        code = subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c:tools/android-port/controller/ledger.py",
        ])
        module = types.ModuleType("controller.disposable_legacy_fixture")
        module.__package__ = "controller"
        with patch.dict(sys.modules, {module.__name__: module}):
            exec(compile(code, "immutable-legacy-ledger.py", "exec"), module.__dict__)
            old = module.Ledger(self.ledger.path, self.ledger.repository)
            with self.assertRaises(PortError):
                old.acquire_hard_lock("gradle-execution", "retained-legacy", {"old": "build"}, NOW)
            with self.assertRaises(PortError):
                old.release_hard_lock("gradle-execution", "retained-legacy")
        self.assertEqual(self.rows(), before)
        self.assert_legacy()

    def test_two_party_atomic_guest_acquisition_has_exactly_one_winner(self):
        guests = [self.allocate("first"), self.allocate("second")]
        barrier, results = threading.Barrier(2), []

        def contender(index):
            # Allocation is tied to process, not thread; only pure Ledger arbitration occurs here.
            barrier.wait(timeout=5)
            try:
                guests[index].acquire(Ledger(self.ledger.path, self.ledger.repository))
                results.append(("won", index))
            except PortError:
                results.append(("blocked", index))

        threads = [threading.Thread(target=contender, args=(i,)) for i in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())
        self.assertEqual(sorted(r[0] for r in results), ["blocked", "won"])
        self.assert_legacy()
        winner = next(i for result, i in results if result == "won")
        guests[winner].dispose(self.ledger)
        guests[1 - winner].acquire(self.ledger)
        guests[1 - winner].dispose(self.ledger)
        self.assertEqual(self.rows(), self.legacy)

    def test_two_party_host_gradle_guest_race_is_atomically_exclusive(self):
        self.ledger.release_hard_lock("native-execution", "retained-legacy")
        guest = self.allocate()
        barrier, results = threading.Barrier(2), []

        def contender(kind):
            ledger = Ledger(self.ledger.path, self.ledger.repository)
            barrier.wait(timeout=5)
            try:
                if kind == "guest":
                    guest.acquire(ledger)
                else:
                    ledger.acquire_hard_lock("gradle-execution", "advisory", {"real": "fixture"}, NOW)
                results.append((kind, True))
            except PortError:
                results.append((kind, False))

        threads = [threading.Thread(target=contender, args=(kind,)) for kind in ("guest", "gradle")]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)
            self.assertFalse(thread.is_alive())
        self.assertEqual(sorted(won for _, won in results), [False, True])
        winner = next(kind for kind, won in results if won)
        self.assertEqual(len(self.rows()), 2 if winner == "guest" else 1)
        if winner == "guest":
            guest.dispose(self.ledger)
        else:
            self.ledger.release_hard_lock("gradle-execution", "advisory")
        self.assertEqual(self.rows(), [])

    def test_host_gradle_runtime_and_global_native_are_excluded_both_directions(self):
        guest = self.allocate()
        # Remove the fixture legacy row ONLY through its exact owner's ordinary fixture API.
        self.ledger.release_hard_lock("native-execution", "retained-legacy")
        for resource in ("gradle-execution", "installed-git-common-runtime"):
            with self.subTest(resource=resource):
                self.ledger.acquire_hard_lock(resource, "foreign", {}, NOW)
                before = self.rows()
                with self.assertRaises(PortError):
                    guest.acquire(self.ledger)
                self.assertEqual(self.rows(), before)
                self.ledger.release_hard_lock(resource, "foreign")
        guest.acquire(self.ledger)
        before = self.rows()
        for resource in ("gradle-execution", "native-execution", "installed-git-common-runtime"):
            for owner in ("foreign", guest.owner):
                with self.subTest(resource=resource, owner=owner), self.assertRaises(PortError):
                    self.ledger.acquire_hard_lock(resource, owner, {}, NOW)
                self.assertEqual(self.rows(), before)
        with self.ledger.hard_lock("physical-device", "unrelated", {}, NOW):
            self.assertEqual(len(self.rows()), 3)
        guest.dispose(self.ledger)

    def test_exact_guest_gradle_nesting_preserves_all_legacy_and_guest_bytes(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        with patch.object(guest, "_prepare_supervisor"), patch("controller.disposable.supervisor") as backend:
            backend.return_value.descendants.return_value = set()
            with guest.packaging_lane(self.ledger, guest.owner):
                self.assertEqual(guest.phase, "packaging")
                self.assertEqual(self.rows(), before)
        for owner in ("foreign", guest.owner, "retained-legacy"):
            with self.assertRaises(PortError):
                self.ledger.acquire_hard_lock("gradle-execution", owner, {"build": True}, NOW)
        with self.assertRaises(PortError):
            self.allocate("foreign").acquire(self.ledger)
        with self.assertRaises(PortError):
            self.ledger.finish_disposable(guest, guest.owner)
        with self.assertRaises(PortError):
            self.ledger.release_hard_lock("gradle-execution", guest.owner)
        self.assertEqual(self.rows(), before)
        self.assert_legacy()
        guest.dispose(self.ledger)

    def test_foreign_stale_wrong_repository_and_tampered_allocation_do_not_release(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        for change in ("owner", "receipt", "repository", "marker"):
            with self.subTest(change=change):
                original_owner, original_receipt = guest.owner, copy.deepcopy(guest.receipt)
                marker = (guest.root / "ownership.json").read_bytes()
                foreign = Ledger(self.ledger.path, "other/repository") if change == "repository" else self.ledger
                if change == "owner":
                    guest.owner = "foreign"
                elif change == "receipt":
                    guest.receipt["acquired"] += 1
                elif change == "marker":
                    (guest.root / "ownership.json").write_text("changed", encoding="utf-8")
                with self.assertRaises(PortError):
                    guest.dispose(foreign)
                self.assertEqual(self.rows(), before)
                guest.owner, guest.receipt = original_owner, original_receipt
                (guest.root / "ownership.json").write_bytes(marker)
        with self.assertRaises(PortError):
            self.ledger.release_hard_lock(RESOURCE, guest.owner)
        guest.dispose(self.ledger)

    def test_failed_or_unknown_process_cleanup_retains_entire_lease_and_data(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        guest.phase = "uncertain"
        with patch.object(guest, "_cleanup", side_effect=PortError("unreaped child")) as cleanup:
            with self.assertRaisesRegex(PortError, "unreaped"):
                guest.dispose(self.ledger)
            cleanup.assert_called_once()
        self.assertEqual(self.rows(), before)
        self.assertTrue(guest.root.is_dir())
        with self.assertRaises(PortError):
            guest.assert_held(self.ledger, guest.owner)
        self.assert_legacy()

    def test_packaging_guard_accepts_only_exact_prepared_guest_not_a_label_or_other_owner(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        debug_packaging.native_lane(self.ledger, guest.owner, guest=guest)
        for bad in ({}, "disposable", self.allocate("foreign")):
            with self.subTest(bad=type(bad).__name__), self.assertRaises(PortError):
                debug_packaging.native_lane(self.ledger, guest.owner, guest=bad)
        with self.assertRaises(PortError):
            debug_packaging.native_lane(self.ledger, "foreign", guest=guest)
        guest.phase = "running"
        with self.assertRaises(PortError):
            debug_packaging.native_lane(self.ledger, guest.owner, guest=guest)
        self.assert_legacy()

    def test_packaging_quiescent_nonzero_borrows_outer_guard_until_proven_disposal(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        with patch.object(guest, "_prepare_supervisor"), patch("controller.disposable.supervisor") as backend:
            backend.return_value.descendants.return_value = set()
            with self.assertRaisesRegex(PortError, "actual failed verification"):
                with guest.packaging_lane(self.ledger, guest.owner):
                    raise PortError("actual failed verification")
        self.assertEqual(self.rows(), before)
        guest.dispose(self.ledger)
        self.assertEqual(self.rows(), self.legacy)

    def test_packaging_persistent_children_retain_gradle_and_guest_until_observed_cleanup(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        with patch.object(guest, "_prepare_supervisor"), patch("controller.disposable.supervisor") as backend:
            backend.return_value.descendants.return_value = {987654}
            with self.assertRaisesRegex(PortError, "descendants remain"):
                with guest.packaging_lane(self.ledger, guest.owner):
                    pass
        before = self.rows()
        self.assertEqual(guest.phase, "uncertain")
        with patch.object(guest, "_cleanup", side_effect=PortError("not reaped")):
            with self.assertRaises(PortError):
                guest.dispose(self.ledger)
        self.assertEqual(self.rows(), before)
        self.assert_legacy()

    def packaging_report(self, guest):
        paths = list(guest.root.glob("packaging-quiescence-*.json"))
        self.assertEqual(len(paths), 1)
        return json.loads(paths[0].read_text())

    def test_packaging_initially_empty_and_delayed_natural_empty_on_both_ownership_backends(self):
        from unittest.mock import Mock
        from controller.windows_guest import WindowsJob

        for windows in (False, True):
            for delayed in (False, True):
                with self.subTest(windows=windows, delayed=delayed):
                    guest = self.allocate()
                    guest.acquire(self.ledger)
                    before = self.rows()
                    counts = iter([7, 2, 0] if delayed else [0])
                    clock = [0.0]
                    backend = Mock()
                    backend.descendants.side_effect = lambda: set(range(100, 100 + next(counts)))
                    if windows:
                        guest.job = Mock()
                        guest.job.active.side_effect = lambda: WindowsJob.active(guest.job)

                        def query(_, kind, buffer, size, returned):
                            self.assertEqual(kind, 1)
                            buffer._obj.active = next(counts)
                            return 1

                        guest.job.kernel.QueryInformationJobObject.side_effect = query

                    def sleep(seconds):
                        self.assertGreater(seconds, 0)
                        self.assertLessEqual(seconds, 0.02)
                        clock[0] += seconds

                    with patch.object(guest, "_prepare_supervisor"), \
                            patch("controller.disposable.supervisor", return_value=backend), \
                            patch("controller.disposable.time.monotonic", side_effect=lambda: clock[0]), \
                            patch("controller.disposable.time.sleep", side_effect=sleep):
                        with guest.packaging_lane(self.ledger, guest.owner):
                            self.assertEqual(guest.phase, "packaging")
                    report = self.packaging_report(guest)
                    self.assertEqual(report["outcome"], "empty")
                    self.assertEqual([s["count"] for s in report["samples"]], [7, 2, 0] if delayed else [0])
                    self.assertEqual(report["elapsed_seconds"], 0.04 if delayed else 0)
                    self.assertEqual(report["samples"][0]["pids"],
                                     None if windows else list(range(100, 107)) if delayed else [])
                    self.assertEqual(report["owner"], guest.owner)
                    self.assertEqual(report["revision"], guest.revision)
                    self.assertEqual(guest.phase, "prepared")
                    self.assertEqual(self.rows(), before)
                    if windows:
                        guest.job.cleanup.assert_not_called()
                        guest.job.kernel.TerminateJobObject.assert_not_called()
                        guest.job.kernel.TerminateProcess.assert_not_called()
                    else:
                        self.assertEqual(backend.reap.call_count, 2 if delayed else 0)
                        backend.signal_owned.assert_not_called()
                    guest.job = None
                    guest.dispose(self.ledger)
                    self.assert_legacy()

    def test_packaging_persistent_deadline_records_actual_counts_pids_elapsed_and_retains_receipts(self):
        from unittest.mock import Mock

        for windows in (False, True):
            with self.subTest(windows=windows):
                guest = self.allocate()
                guest.acquire(self.ledger)
                before, clock = self.rows(), [0.0]
                backend = Mock()
                backend.descendants.return_value = {101, 102, 103}
                if windows:
                    guest.job = Mock()
                    guest.job.active.return_value = 3

                def sleep(seconds):
                    clock[0] += seconds

                with patch.object(guest, "_prepare_supervisor"), \
                        patch("controller.disposable.supervisor", return_value=backend), \
                        patch("controller.disposable.time.monotonic", side_effect=lambda: clock[0]), \
                        patch("controller.disposable.time.sleep", side_effect=sleep):
                    with self.assertRaisesRegex(PortError, "quiescence deadline") as raised:
                        with guest.packaging_lane(self.ledger, guest.owner):
                            pass
                report = self.packaging_report(guest)
                self.assertEqual(report["outcome"], "expired")
                self.assertEqual(report["elapsed_seconds"], report["budget_seconds"])
                self.assertTrue(all(s["count"] == 3 for s in report["samples"]))
                self.assertEqual(report["samples"][-1]["pids"], None if windows else [101, 102, 103])
                self.assertIn("packaging-quiescence-", raised.exception.__notes__[0])
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                with patch.object(guest, "_cleanup", side_effect=PortError("independent cleanup failed")):
                    with self.assertRaisesRegex(PortError, "independent cleanup"):
                        guest.dispose(self.ledger)
                self.assertEqual(self.rows(), before)
                if windows:
                    guest.job.cleanup.assert_not_called()
                backend.signal_owned.assert_not_called()
                guest.job = None
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_packaging_query_failure_and_primary_failure_both_survive_with_uncertainty(self):
        from unittest.mock import Mock

        for primary in (None, PortError("actual build failed"), KeyboardInterrupt("cancelled build")):
            with self.subTest(primary=type(primary).__name__):
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                backend = Mock()
                query_error = OSError("owned descendant query unavailable")
                backend.descendants.side_effect = query_error
                with patch.object(guest, "_prepare_supervisor"), \
                        patch("controller.disposable.supervisor", return_value=backend):
                    with self.assertRaises(OSError if primary is None else BaseExceptionGroup) as raised:
                        with guest.packaging_lane(self.ledger, guest.owner):
                            if primary is not None:
                                raise primary
                if primary is None:
                    self.assertIs(raised.exception, query_error)
                else:
                    self.assertEqual(raised.exception.exceptions, (primary, query_error))
                report = self.packaging_report(guest)
                self.assertEqual(report["outcome"], "uncertain")
                self.assertEqual(report["samples"], [])
                self.assertEqual(report["error"]["type"], "OSError")
                self.assertGreaterEqual(report["elapsed_seconds"], 0)
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_packaging_invalid_windows_observations_and_failed_diagnostic_write_fail_closed(self):
        from unittest.mock import Mock

        for observation in (False, -1, "0", OSError("Job accounting query failed")):
            with self.subTest(observation=observation):
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                guest.job = Mock()
                if isinstance(observation, OSError):
                    guest.job.active.side_effect = observation
                else:
                    guest.job.active.return_value = observation
                with patch.object(guest, "_prepare_supervisor"):
                    with self.assertRaises((PortError, OSError)):
                        with guest.packaging_lane(self.ledger, guest.owner):
                            pass
                self.assertEqual(self.packaging_report(guest)["outcome"], "uncertain")
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                guest.job.cleanup.assert_not_called()
                guest.job = None
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)

        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        primary = PortError("actual capture failed")
        original_open = Path.open

        def open_path(path, *args, **kwargs):
            if path.name.startswith("packaging-quiescence-"):
                raise OSError("diagnostic retention failed")
            return original_open(path, *args, **kwargs)

        with patch.object(guest, "_prepare_supervisor"), \
                patch.object(guest, "_active_children", return_value=set()), \
                patch.object(Path, "open", open_path):
            with self.assertRaises(BaseExceptionGroup) as raised:
                with guest.packaging_lane(self.ledger, guest.owner):
                    raise primary
        self.assertIs(raised.exception.exceptions[0], primary)
        self.assertRegex(str(raised.exception.exceptions[1]), "diagnostic retention failed")
        self.assertEqual(guest.phase, "uncertain")
        self.assertEqual(self.rows(), before)
        with patch.object(guest, "_cleanup"):
            guest.dispose(self.ledger)
        self.assert_legacy()

    def test_packaging_elapsed_observation_failure_never_accepts_even_empty_children(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        with patch.object(guest, "_prepare_supervisor"), \
                patch.object(guest, "_active_children", return_value=set()), \
                patch("controller.disposable.time.monotonic", side_effect=[10, 9, 11]):
            with self.assertRaisesRegex(PortError, "elapsed time"):
                with guest.packaging_lane(self.ledger, guest.owner):
                    pass
        report = self.packaging_report(guest)
        self.assertEqual(report["outcome"], "uncertain")
        self.assertEqual(report["elapsed_seconds"], 1)
        self.assertEqual(guest.phase, "uncertain")
        self.assertEqual(self.rows(), before)
        with patch.object(guest, "_cleanup"):
            guest.dispose(self.ledger)
        self.assert_legacy()

    def test_packaging_late_empty_and_primary_command_failure_preserve_expiry(self):
        for primary in (None, subprocess.CalledProcessError(1, ["fixture-build"])):
            with self.subTest(primary=type(primary).__name__):
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                with patch.object(guest, "_prepare_supervisor"), \
                        patch.object(guest, "_active_children", return_value=set()), \
                        patch("controller.disposable.time.monotonic", side_effect=[0, 2, 2]):
                    with self.assertRaises(PortError if primary is None else ExceptionGroup) as raised:
                        with guest.packaging_lane(self.ledger, guest.owner):
                            if primary is not None:
                                raise primary
                if primary is not None:
                    self.assertIs(raised.exception.exceptions[0], primary)
                    self.assertRegex(str(raised.exception.exceptions[1]), "quiescence deadline")
                self.assertEqual(self.packaging_report(guest)["outcome"], "expired")
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_packaging_query_and_elapsed_and_retention_failures_are_not_lost(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        primary, query = PortError("actual capture failed"), OSError("owned query failed")
        original_open = Path.open

        def open_path(path, *args, **kwargs):
            if path.name.startswith("packaging-quiescence-"):
                raise OSError("diagnostic write failed")
            return original_open(path, *args, **kwargs)

        with patch.object(guest, "_prepare_supervisor"), \
                patch.object(guest, "_active_children", side_effect=query), \
                patch("controller.disposable.time.monotonic", side_effect=[0, OSError("clock failed")]), \
                patch.object(Path, "open", open_path):
            with self.assertRaises(ExceptionGroup) as raised:
                with guest.packaging_lane(self.ledger, guest.owner):
                    raise primary
        self.assertIs(raised.exception.exceptions[0], primary)
        diagnostic = raised.exception.exceptions[1]
        self.assertRegex(str(diagnostic.exceptions[1]), "diagnostic write failed")
        self.assertIs(diagnostic.exceptions[0].exceptions[0], query)
        self.assertRegex(str(diagnostic.exceptions[0].exceptions[1]), "clock failed")
        self.assertEqual(guest.phase, "uncertain")
        self.assertEqual(self.rows(), before)
        with patch.object(guest, "_cleanup"):
            guest.dispose(self.ledger)
        self.assert_legacy()

    def test_failed_owned_job_handle_cleanup_retains_both_receipts_without_claiming_disposal(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        with patch("controller.disposable.supervisor"):
            from unittest.mock import Mock

            guest.job = Mock()
            guest.job.close.side_effect = OSError("fixture handle cleanup not acknowledged")
            with self.assertRaisesRegex(OSError, "not acknowledged"):
                guest.dispose(self.ledger)
        self.assertEqual(guest.phase, "uncertain")
        self.assertEqual(self.rows(), before)
        self.assert_legacy()

    def test_no_transport_command_before_running_owned_guest(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        with patch("controller.disposable.subprocess.run") as run:
            with self.assertRaises(PortError):
                guest.adb(self.ledger, ["get-state"])
            run.assert_not_called()
        self.assert_legacy()

    def timing_records(self, guest):
        paths = list(guest.root.glob("transport-timing-*.jsonl"))
        self.assertEqual(len(paths), 1)
        return [json.loads(line) for line in paths[0].read_text().splitlines()]

    def test_selected_command_timing_retains_real_budgets_context_and_honest_pid_availability(self):
        from unittest.mock import Mock

        for windows in (False, True):
            for outcome in ("success", "timeout", "failure", "cancelled"):
                with self.subTest(windows=windows, outcome=outcome):
                    guest = self.allocate()
                    guest.acquire(self.ledger)
                    before = self.rows()
                    guest.phase = "running"
                    guest.adb_tool, guest.port, guest.server_port = self.root / "adb", 5558, 41234
                    guest.environment = {
                        "ADB_SERVER_SOCKET": "tcp:127.0.0.1:41234", "ANDROID_ADB_SERVER_PORT": "41234",
                        "DO_NOT_LOG": "fixture-secret",
                    }
                    command = [str(guest.adb_tool), "-s", "emulator-5558", "wait-for-device"]
                    primary = {
                        "success": None, "timeout": subprocess.TimeoutExpired(command, 120),
                        "failure": subprocess.CalledProcessError(1, command), "cancelled": KeyboardInterrupt(),
                    }[outcome]
                    process, job = Mock(pid=12345), Mock()
                    process.wait.side_effect = primary
                    process.wait.return_value = 0

                    def spawn(argv, environment, log):
                        self.assertEqual(argv, command)
                        self.assertIs(environment, guest.environment)
                        log.write(b"retained raw adb bytes")
                        return process

                    job.spawn.side_effect = spawn
                    if windows:
                        guest.job = job
                    with patch.object(guest, "verify_transport"), \
                            patch("controller.disposable.subprocess.run",
                                  return_value=subprocess.CompletedProcess(command, 0, stdout=b"raw", stderr=b""),
                                  side_effect=primary) as run:
                        if primary is None:
                            guest.adb(self.ledger, ["wait-for-device"], timeout=120)
                        else:
                            with self.assertRaises(type(primary)) as raised:
                                guest.adb(self.ledger, ["wait-for-device"], timeout=120)
                            self.assertIs(raised.exception, primary)
                    if windows:
                        process.wait.assert_called_once_with(120)
                        run.assert_not_called()
                    else:
                        self.assertEqual(run.call_args.kwargs["timeout"], 120)
                        self.assertIs(run.call_args.kwargs["env"], guest.environment)
                    records = self.timing_records(guest)
                    stages = [r["stage"] for r in records]
                    self.assertEqual(stages[-1], {
                        "success": "returned", "timeout": "timeout", "failure": "failed", "cancelled": "cancelled",
                    }[outcome])
                    wait = next(r for r in records if r["stage"] == "wait-begin")
                    self.assertEqual(wait["budget_reference_monotonic_seconds"],
                                     wait["host_monotonic_seconds"] + 120)
                    self.assertIsNone(wait["deadline_monotonic_seconds"])
                    self.assertEqual(wait["pid"], 12345 if windows else None)
                    self.assertEqual(wait["raw_log"], records[-1]["raw_log"])
                    if windows:
                        self.assertTrue(wait["raw_log"].startswith("adb-"))
                    else:
                        self.assertIsNone(wait["raw_log"])
                    for record in records:
                        self.assertEqual(record["timeout_seconds"], 120)
                        self.assertEqual(record["private_endpoint"], "127.0.0.1:41234")
                        self.assertEqual(record["selected_serial"], "emulator-5558")
                        self.assertEqual(record["owner"], guest.owner)
                        self.assertEqual(record["revision"], guest.revision)
                        self.assertEqual(record["backend"], "windows-job" if windows else "linux-subprocess")
                        self.assertTrue(record["host_utc"].endswith("+00:00"))
                        self.assertGreater(record["host_unix_nanoseconds"], 0)
                        self.assertEqual(record["clock_observation_order"], ["time.time_ns", "time.monotonic"])
                    self.assertNotIn("fixture-secret", json.dumps(records))
                    self.assertEqual(self.rows(), before)
                    self.assertEqual(guest.phase, "running" if primary is None else "uncertain")
                    if windows:
                        raw = next(guest.root.glob("adb-*.log"))
                        self.assertEqual(raw.read_bytes(), b"retained raw adb bytes")
                    guest.job = None
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                    self.assertEqual(self.rows(), self.legacy)
                    self.assertEqual(self.timing_records(guest), records)
                    self.assert_legacy()

    def test_startup_timing_preserves_launch_listener_deadline_and_raw_log_on_success_timeout_failure(self):
        from unittest.mock import Mock

        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text("AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n")
        (image / "userdata.img").write_bytes(b"original image")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        for windows in (False, True):
            for outcome in ("success", "timeout", "failure"):
                with self.subTest(windows=windows, outcome=outcome):
                    guest = self.allocate()
                    guest.acquire(self.ledger)
                    before, clock = self.rows(), [10.0]
                    if windows:
                        guest.job = Mock()
                    processes = [Mock(pid=2345), Mock(pid=3456)]
                    for process in processes:
                        process.poll.return_value = None
                    primary = OSError("fixture launch failed")
                    launched = []

                    def spawn(command):
                        launched.append(command)
                        guest.log.write(b"retained raw kernel bytes\n")
                        guest.log.flush()
                        if outcome == "failure":
                            raise primary
                        return processes[len(launched) - 1]

                    def sleep(seconds):
                        self.assertEqual(seconds, 0.05)
                        clock[0] += 120

                    with patch.object(guest, "_prepare_supervisor"), \
                            patch.object(guest, "_spawn", side_effect=spawn), \
                            patch.object(guest, "_owns_listener", return_value=outcome != "timeout"), \
                            patch.object(guest, "verify_transport"), \
                            patch("controller.disposable.time.monotonic", side_effect=lambda: clock[0]), \
                            patch("controller.disposable.time.sleep", side_effect=sleep):
                        if outcome == "success":
                            guest.start(self.ledger, emulator=emulator, adb=adb, image=image, timeout=120)
                        elif outcome == "failure":
                            with self.assertRaises(OSError) as raised:
                                guest.start(self.ledger, emulator=emulator, adb=adb, image=image, timeout=120)
                            self.assertIs(raised.exception, primary)
                        else:
                            with self.assertRaisesRegex(PortError, "startup failed/timed out"):
                                guest.start(self.ledger, emulator=emulator, adb=adb, image=image, timeout=120)
                    records = self.timing_records(guest)
                    self.assertEqual(records[0]["operation"], "startup")
                    self.assertTrue(all(r["raw_log"] == "process.log" for r in records))
                    launch = next(r for r in records if r["stage"] == "server-launch-begin")
                    self.assertEqual(launch["deadline_monotonic_seconds"], 130)
                    self.assertIsNone(launch["pid"])
                    self.assertEqual(records[-1]["stage"], "returned" if outcome == "success" else "failed")
                    self.assertEqual(records[-1]["deadline_monotonic_seconds"], 130)
                    if outcome == "timeout":
                        self.assertEqual(records[-1]["host_monotonic_seconds"], 130)
                        self.assertEqual(records[-1]["error_type"], "PortError")
                    if outcome == "success":
                        self.assertEqual(len(launched), 2)
                        self.assertIn("-show-kernel", launched[1])
                        self.assertNotIn("-no-boot-anim", launched[1])
                        emu_launch = next(r for r in records if r["stage"] == "emulator-launch-begin")
                        self.assertIsNone(emu_launch["pid"])
                        self.assertEqual(records[-1]["pid"], 3456)
                    self.assertEqual((guest.root / "process.log").read_bytes(), b"retained raw kernel bytes\n" *
                                     (2 if outcome == "success" else 1))
                    self.assertEqual(self.rows(), before)
                    guest.job = None
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                    self.assertEqual(self.timing_records(guest), records)
                    self.assert_legacy()

    def test_transport_timing_invalid_or_failed_clocks_prevent_command_and_retain_ownership(self):
        for clock, value in (
            ("time_ns", 0), ("time_ns", float("nan")), ("time_ns", OSError("UTC unavailable")),
            ("monotonic", float("nan")), ("monotonic", float("inf")),
            ("monotonic", -1), ("monotonic", OSError("monotonic unavailable")),
        ):
            with self.subTest(clock=clock, value=value):
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                guest.phase = "running"
                guest.adb_tool, guest.port = self.root / "adb", 5558
                guest.environment = {}
                options = {"side_effect": value} if isinstance(value, BaseException) else {"return_value": value}
                with patch.object(guest, "verify_transport"), \
                        patch("controller.disposable.time." + clock, **options), \
                        patch("controller.disposable.subprocess.run") as run:
                    with self.assertRaises(BaseExceptionGroup):
                        guest.adb(self.ledger, ["get-state"], timeout=17)
                    run.assert_not_called()
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_transport_primary_and_independent_timing_retention_clock_or_close_errors_survive(self):
        from unittest.mock import Mock

        original_open = Path.open
        for primary in (subprocess.TimeoutExpired(["fixture"], 19), KeyboardInterrupt("fixture cancellation")):
            for failure in ("write", "flush", "clock", "close"):
                with self.subTest(primary=type(primary).__name__, failure=failure):
                    guest = self.allocate()
                    guest.acquire(self.ledger)
                    guest.phase = "running"
                    guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
                    before = self.rows()
                    opened = []

                    def open_path(path, *args, **kwargs):
                        stream = original_open(path, *args, **kwargs)
                        if not path.name.startswith("transport-timing-"):
                            return stream
                        wrapped = Mock(wraps=stream)
                        if failure == "write":
                            calls = [0]

                            def write(data):
                                calls[0] += 1
                                if calls[0] == 3:
                                    raise OSError("timing final write failed")
                                return stream.write(data)

                            wrapped.write.side_effect = write
                        if failure == "flush":
                            calls = [0]

                            def flush():
                                calls[0] += 1
                                if calls[0] == 3:
                                    raise OSError("timing final flush failed")
                                return stream.flush()

                            wrapped.flush.side_effect = flush
                        if failure == "close":
                            def close():
                                stream.close()
                                raise OSError("timing close failed")
                            wrapped.close.side_effect = close
                        opened.append(stream)
                        return wrapped

                    ticks = iter([10, 11, 9] if failure == "clock" else [10, 11, 12])
                    try:
                        with patch.object(guest, "verify_transport"), \
                                patch.object(Path, "open", open_path), \
                                patch("controller.disposable.time.monotonic", side_effect=lambda: next(ticks)), \
                                patch("controller.disposable.subprocess.run", side_effect=primary) as run:
                            with self.assertRaises(BaseExceptionGroup) as raised:
                                guest.adb(self.ledger, ["shell", "DO_NOT_LOG_PAYLOAD"], timeout=19)
                        self.assertIs(raised.exception.exceptions[0], primary)
                        self.assertEqual(len(raised.exception.exceptions), 2)
                        self.assertEqual(run.call_args.kwargs["timeout"], 19)
                        self.assertEqual(guest.phase, "uncertain")
                        self.assertEqual(self.rows(), before)
                        self.assertNotIn("DO_NOT_LOG_PAYLOAD", json.dumps(self.timing_records(guest)))
                        with patch.object(guest, "_cleanup", side_effect=PortError("independent disposal failed")):
                            with self.assertRaisesRegex(PortError, "independent disposal"):
                                guest.dispose(self.ledger)
                        self.assertEqual(self.rows(), before)
                    finally:
                        for stream in opened:
                            stream.close()
                        with patch.object(guest, "_cleanup"):
                            guest.dispose(self.ledger)
                    self.assert_legacy()

    def test_startup_timing_retention_failure_preserves_primary_cancellation_and_raw_log(self):
        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text("AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n")
        (image / "userdata.img").write_bytes(b"source data")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        primary = KeyboardInterrupt("owned launch cancelled")
        original_dumps = json.dumps

        def dumps(record, **kwargs):
            if isinstance(record, dict) and record.get("stage") == "cancelled":
                raise OSError("startup diagnostic retention failed")
            return original_dumps(record, **kwargs)

        def spawn(command):
            guest.log.write(b"raw startup output")
            guest.log.flush()
            raise primary

        with patch.object(guest, "_prepare_supervisor"), \
                patch.object(guest, "_spawn", side_effect=spawn), \
                patch("controller.disposable.json.dumps", side_effect=dumps):
            with self.assertRaises(BaseExceptionGroup) as raised:
                guest.start(self.ledger, emulator=emulator, adb=adb, image=image, timeout=120)
        self.assertIs(raised.exception.exceptions[0], primary)
        self.assertRegex(str(raised.exception.exceptions[1]), "startup diagnostic retention")
        self.assertEqual(guest.phase, "uncertain")
        self.assertEqual(self.rows(), before)
        self.assertEqual((guest.root / "process.log").read_bytes(), b"raw startup output")
        self.assertEqual((image / "userdata.img").read_bytes(), b"source data")
        records = self.timing_records(guest)
        self.assertEqual(records[-1]["stage"], "server-launch-begin")
        self.assertIsNone(records[-1]["pid"])
        with patch.object(guest, "_cleanup"):
            guest.dispose(self.ledger)
        self.assertEqual((guest.root / "process.log").read_bytes(), b"raw startup output")
        self.assert_legacy()

    def test_windows_returned_nonzero_exit_survives_wait_returned_diagnostic_failures(self):
        from unittest.mock import Mock

        original_open = Path.open
        for failure in ("none", "write", "flush", "clock", "cancelled"):
            with self.subTest(failure=failure):
                guest = self.allocate()
                guest.acquire(self.ledger)
                guest.phase = "running"
                guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
                guest.server_port = 41234
                before = self.rows()
                command = [str(guest.adb_tool), "-s", "emulator-5558", "wait-for-device"]
                process, job = Mock(pid=7123), Mock()
                process.wait.return_value = 7
                guest.job = job
                primary_diagnostic = KeyboardInterrupt("wait-returned cancelled") if failure == "cancelled" \
                    else OSError("wait-returned diagnostic " + failure + " failed")
                clock = [10.0]
                returned = [False]
                opened = []

                def spawn(argv, environment, log):
                    self.assertEqual(argv, command)
                    self.assertIs(environment, guest.environment)
                    log.write(b"actual-model exit7 raw bytes\n")
                    return process

                def wait(timeout):
                    self.assertEqual(timeout, 120)
                    returned[0] = True
                    return 7

                def monotonic():
                    if failure == "clock" and returned[0]:
                        returned[0] = False
                        raise primary_diagnostic
                    clock[0] += 0.01
                    return clock[0]

                def open_path(path, *args, **kwargs):
                    stream = original_open(path, *args, **kwargs)
                    if not path.name.startswith("transport-timing-"):
                        return stream
                    wrapped = Mock(wraps=stream)
                    waiting_flush = [False]

                    def write(data):
                        record = json.loads(data)
                        waiting_flush[0] = record["stage"] == "wait-returned"
                        if waiting_flush[0] and failure in ("write", "cancelled"):
                            raise primary_diagnostic
                        return stream.write(data)

                    def flush():
                        if waiting_flush[0] and failure == "flush":
                            waiting_flush[0] = False
                            raise primary_diagnostic
                        return stream.flush()

                    wrapped.write.side_effect, wrapped.flush.side_effect = write, flush
                    opened.append(stream)
                    return wrapped

                job.spawn.side_effect, process.wait.side_effect = spawn, wait
                try:
                    with patch.object(guest, "verify_transport"), \
                            patch.object(Path, "open", open_path), \
                            patch("controller.disposable.time.monotonic", side_effect=monotonic):
                        with self.assertRaises(subprocess.CalledProcessError if failure == "none"
                                               else BaseExceptionGroup) as raised:
                            guest.adb(self.ledger, ["wait-for-device"], timeout=120)
                    if failure == "none":
                        primary = raised.exception
                    else:
                        self.assertEqual(len(raised.exception.exceptions), 2)
                        primary = raised.exception.exceptions[0]
                        self.assertIs(raised.exception.exceptions[1], primary_diagnostic)
                    self.assertIsInstance(primary, subprocess.CalledProcessError)
                    self.assertEqual(primary.returncode, 7)
                    self.assertEqual(primary.cmd, command)
                    self.assertEqual(primary.output, b"actual-model exit7 raw bytes\n")
                    process.wait.assert_called_once_with(120)
                    job.spawn.assert_called_once()
                    self.assertEqual(guest.phase, "uncertain")
                    self.assertEqual(self.rows(), before)
                    raw = next(guest.root.glob("adb-*.log"))
                    self.assertEqual(raw.read_bytes(), primary.output)
                    with patch.object(guest, "_cleanup", side_effect=PortError("independent disposal failed")):
                        with self.assertRaisesRegex(PortError, "independent disposal failed"):
                            guest.dispose(self.ledger)
                    self.assertEqual(self.rows(), before)
                    job.cleanup.assert_not_called()
                    self.assert_legacy()
                finally:
                    for stream in opened:
                        stream.close()
                    guest.job = None
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                self.assertEqual(raw.read_bytes(), b"actual-model exit7 raw bytes\n")

    def test_timing_receipt_creation_failure_never_launches_or_accepts_selected_command(self):
        original_open = Path.open
        guest = self.allocate()
        guest.acquire(self.ledger)
        guest.phase = "running"
        guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
        before = self.rows()

        def open_path(path, *args, **kwargs):
            if path.name.startswith("transport-timing-"):
                raise OSError("timing receipt creation failed")
            return original_open(path, *args, **kwargs)

        with patch.object(guest, "verify_transport"), \
                patch.object(Path, "open", open_path), \
                patch("controller.disposable.subprocess.run") as run:
            with self.assertRaisesRegex(OSError, "timing receipt creation"):
                guest.adb(self.ledger, ["get-state"], timeout=120)
            run.assert_not_called()
        self.assertEqual(guest.phase, "uncertain")
        self.assertEqual(self.rows(), before)
        with patch.object(guest, "_cleanup"):
            guest.dispose(self.ledger)
        self.assert_legacy()

    def test_windows_returned_nonzero_and_raw_read_failure_preserve_all_independent_errors(self):
        from unittest.mock import Mock

        original_read = Path.read_bytes
        for diagnostic_failed in (False, True):
            with self.subTest(diagnostic_failed=diagnostic_failed):
                guest = self.allocate()
                guest.acquire(self.ledger)
                guest.phase = "running"
                guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
                guest.job = Mock()
                process = Mock(pid=7123)
                process.wait.return_value = 7
                before = self.rows()
                diagnostic, read_error = OSError("post-wait observation failed"), OSError("raw read failed")

                def spawn(argv, environment, log):
                    log.write(b"retained exit7 bytes")
                    return process

                def read(path):
                    if path.name.startswith("adb-"):
                        raise read_error
                    return original_read(path)

                original_timing = guest._timing

                @contextmanager
                def timing(*args, **kwargs):
                    with original_timing(*args, **kwargs) as observe:
                        def record(stage, **fields):
                            if diagnostic_failed and stage == "wait-returned":
                                raise diagnostic
                            return observe(stage, **fields)
                        yield record

                guest.job.spawn.side_effect = spawn
                with patch.object(guest, "verify_transport"), patch.object(guest, "_timing", timing), \
                        patch.object(Path, "read_bytes", read):
                    with self.assertRaises(ExceptionGroup) as raised:
                        guest.adb(self.ledger, ["wait-for-device"], timeout=120)
                failures = raised.exception.exceptions
                self.assertIsInstance(failures[0], subprocess.CalledProcessError)
                self.assertEqual(failures[0].returncode, 7)
                self.assertIsNone(failures[0].output)
                self.assertEqual(failures[1:], (diagnostic, read_error) if diagnostic_failed else (read_error,))
                self.assertEqual(guest.phase, "uncertain")
                self.assertEqual(self.rows(), before)
                process.wait.assert_called_once_with(120)
                self.assertEqual(next(guest.root.glob("adb-*.log")).read_bytes(), b"retained exit7 bytes")
                guest.job = None
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assert_legacy()

    def test_exact_selected_wait_uses_private_endpoint_serial_and_finite_timeout_on_both_backends(self):
        from unittest.mock import Mock

        guest = self.allocate()
        guest.acquire(self.ledger)
        guest.phase = "running"
        guest.adb_tool, guest.port = self.root / "adb", 5558
        guest.environment = {"ADB_SERVER_SOCKET": "tcp:127.0.0.1:45321"}
        expected = [str(guest.adb_tool), "-s", "emulator-5558", "wait-for-device"]
        with patch.object(guest, "verify_transport") as verify, \
                patch("controller.disposable.subprocess.run", return_value=subprocess.CompletedProcess(expected, 0)) as run:
            guest.adb(self.ledger, ["wait-for-device"], timeout=19.5)
            run.assert_called_once_with(expected, env=guest.environment, stdin=subprocess.DEVNULL,
                                        capture_output=True, timeout=19.5, check=True)
            self.assertEqual(verify.call_count, 2)
        job, process = Mock(), Mock()
        guest.job = job
        job.spawn.return_value = process
        process.wait.return_value = 0
        with patch.object(guest, "verify_transport") as verify:
            result = guest.adb(self.ledger, ["wait-for-device"], timeout=7.25)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(job.spawn.call_args.args[:2], (expected, guest.environment))
            process.wait.assert_called_once_with(7.25)
            self.assertEqual(verify.call_count, 2)
        self.assertEqual(guest.phase, "running")
        guest.job = None
        guest.dispose(self.ledger)
        self.assert_legacy()

    def test_wait_rejects_variants_extra_selection_and_unbounded_timeouts_before_execution(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        guest.phase = "running"
        before = self.rows()
        requests = [
            (["wait-for-device", "shell"], 10), (["wait-for-device", "-s", "foreign"], 10),
            (["wait-for-any-device"], 10), (["wait-for-usb-device"], 10),
            (["-s", "foreign", "wait-for-device"], 10), (["start-server"], 10),
            *[(["wait-for-device"], value) for value in (0, -1, 181, float("inf"), float("nan"), True, None)],
        ]
        with patch.object(guest, "verify_transport"), patch("controller.disposable.subprocess.run") as run:
            for arguments, timeout in requests:
                with self.subTest(arguments=arguments, timeout=timeout), self.assertRaises(PortError):
                    guest.adb(self.ledger, arguments, timeout=timeout)
            run.assert_not_called()
        self.assertEqual(self.rows(), before)
        guest.dispose(self.ledger)

    def test_wait_nonzero_timeout_or_lost_owned_transport_retains_uncertainty_on_both_backends(self):
        from unittest.mock import Mock

        for windows in (False, True):
            for failure in ("nonzero", "timeout", "transport"):
                with self.subTest(windows=windows, failure=failure):
                    guest = self.allocate()
                    guest.acquire(self.ledger)
                    guest.phase = "running"
                    guest.adb_tool, guest.port, guest.environment = self.root / "adb", 5558, {}
                    before = self.rows()
                    command = [str(guest.adb_tool), "-s", "emulator-5558", "wait-for-device"]
                    job, process = Mock(), Mock()
                    if windows:
                        guest.job = job
                        job.spawn.return_value = process
                        process.wait.return_value = 1 if failure == "nonzero" else 0
                        if failure == "timeout":
                            process.wait.side_effect = subprocess.TimeoutExpired(command, 2)
                    error = (subprocess.CalledProcessError(1, command) if failure == "nonzero"
                             else subprocess.TimeoutExpired(command, 2))
                    with patch.object(guest, "verify_transport", side_effect=(
                        [None, PortError("owned transport lost")] if failure == "transport" else None
                    )), patch("controller.disposable.subprocess.run",
                              side_effect=error if failure != "transport" else None,
                              return_value=subprocess.CompletedProcess(command, 0)):
                        with self.assertRaises(PortError if failure == "transport" else subprocess.SubprocessError):
                            guest.adb(self.ledger, ["wait-for-device"], timeout=2)
                    self.assertEqual(guest.phase, "uncertain")
                    self.assertEqual(self.rows(), before)
                    with patch.object(guest, "_cleanup", side_effect=PortError("cleanup unproven")):
                        with self.assertRaisesRegex(PortError, "cleanup unproven"):
                            guest.dispose(self.ledger)
                    self.assertEqual(self.rows(), before)
                    guest.job = None
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)
                    self.assert_legacy()

    def test_active_packaging_prevents_guest_boot_before_any_process_or_supervisor_call(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        guest.phase = "packaging"
        before = self.rows()
        with patch.object(guest, "_prepare_supervisor") as prepare, \
                patch("controller.disposable.subprocess.Popen") as launch:
            with self.assertRaisesRegex(PortError, "fresh prepared"):
                guest.start(self.ledger, emulator=self.root / "unused", adb=self.root / "unused", image=self.root)
            prepare.assert_not_called()
            launch.assert_not_called()
        self.assertEqual(self.rows(), before)
        self.assert_legacy()

    def test_unsupported_host_refuses_before_any_emulator_or_adb_launch(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        with patch("controller.disposable.sys.platform", "darwin"), \
                patch("controller.disposable.subprocess.Popen") as launch:
            with self.assertRaisesRegex(PortError, "Linux pidfd"):
                guest.start(self.ledger, emulator=self.root / "emulator", adb=self.root / "adb", image=self.root)
            launch.assert_not_called()
        self.assertEqual(self.rows(), before)
        guest.dispose(self.ledger)

    def test_installed_base_api_metadata_reaches_private_data_copy_without_process_execution(self):
        image = self.root / "image"
        image.mkdir()
        (image / "userdata.img").write_bytes(b"fixture initial userdata")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        for level in ("31", "37", "37.0"):
            with self.subTest(level=level):
                (image / "source.properties").write_text(
                    f"AndroidVersion.ApiLevel={level}\nSystemImage.Abi=x86_64\nPkg.Revision=6\n",
                    encoding="utf-8",
                )
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                with patch.object(guest, "_prepare_supervisor"), \
                        patch("controller.disposable.shutil.copyfile", side_effect=OSError("fixture stops before data copy")) as copyfile, \
                        patch.object(guest, "_spawn") as spawn:
                    with self.assertRaisesRegex(OSError, "fixture stops before data copy"):
                        guest.start(self.ledger, emulator=emulator, adb=adb, image=image)
                    copyfile.assert_called_once()
                    self.assertEqual(copyfile.call_args.args[0], image / "userdata.img")
                    spawn.assert_not_called()
                self.assertEqual(self.rows(), before)
                guest.dispose(self.ledger)
                self.assert_legacy()

    def test_minor_malformed_unselected_api_and_invalid_abi_fail_before_data_copy_or_process(self):
        image = self.root / "image"
        image.mkdir()
        (image / "userdata.img").write_bytes(b"fixture initial userdata")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        for level, abi in [
            *[(level, "x86_64") for level in
              ("37.2", "37.1", "37.00", "37.0.0", "37.", "037", "+37", "3.7e1", "NaN", "", "32", "38", "31.2")],
            ("37.0", "x86"), ("37.0", ""), ("37.0", "unknown"),
        ]:
            with self.subTest(level=level, abi=abi):
                (image / "source.properties").write_text(
                    f"AndroidVersion.ApiLevel={level}\nSystemImage.Abi={abi}\n", encoding="utf-8",
                )
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                with patch.object(guest, "_prepare_supervisor"), \
                        patch("controller.disposable.shutil.copyfile") as copyfile, \
                        patch.object(guest, "_spawn") as spawn:
                    with self.assertRaisesRegex(PortError, "image provenance"):
                        guest.start(self.ledger, emulator=emulator, adb=adb, image=image)
                    copyfile.assert_not_called()
                    spawn.assert_not_called()
                self.assertFalse((guest.root / "avds").exists())
                self.assertEqual(self.rows(), before)
                guest.dispose(self.ledger)
                self.assert_legacy()

    def test_modern_empty_data_and_legacy_image_prepare_only_fresh_private_outputs_and_dispose(self):
        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text(
            "AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\nPkg.Revision=6\n", encoding="utf-8",
        )
        initial = image / "data"
        initial.mkdir()
        marker = initial / "empty_data_disk"
        marker.write_text(
            "This special file tells the host side to keep the data partition empty.\n\n"
            "See b/326479561 for more details.\n", encoding="utf-8",
        )
        (initial / "misc").mkdir()
        (initial / "local.prop").write_bytes(b"fixture immutable package defaults")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        source_bytes = {p: p.read_bytes() for p in image.rglob("*") if p.is_file()}
        for legacy in (False, True):
            with self.subTest(legacy=legacy):
                if legacy:
                    (image / "userdata.img").write_bytes(b"legacy initial image")
                guest = self.allocate()
                guest.acquire(self.ledger)
                with patch.object(guest, "_prepare_supervisor"), \
                        patch("controller.disposable.time.monotonic", side_effect=RuntimeError("fixture before launch")), \
                        patch.object(guest, "_spawn") as spawn:
                    with self.assertRaisesRegex(RuntimeError, "fixture before launch"):
                        guest.start(self.ledger, emulator=emulator, adb=adb, image=image)
                    spawn.assert_not_called()
                data = guest.root / "avds" / (guest.token + ".avd")
                output = data / "userdata-qemu.img"
                self.assertEqual(output.exists(), legacy)
                if legacy:
                    self.assertEqual(output.read_bytes(), b"legacy initial image")
                self.assertIn(f"image.sysdir.1={image}{os.sep}", (data / "config.ini").read_text())
                self.assertEqual({p: p.read_bytes() for p in source_bytes}, source_bytes)
                self.assertEqual(guest.phase, "uncertain")
                with patch.object(guest, "_cleanup"):
                    guest.dispose(self.ledger)
                self.assertEqual(guest.phase, "disposed")
                self.assertEqual(self.rows(), self.legacy)
                self.assert_legacy()

    def test_modern_layout_missing_or_nonregular_marker_and_preexisting_avd_fail_closed(self):
        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text(
            "AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n", encoding="utf-8",
        )
        initial = image / "data"
        initial.mkdir()
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        marker = initial / "empty_data_disk"
        for mode in ("missing", "directory", "existing-avd", "invalid-userdata"):
            with self.subTest(mode=mode):
                if mode == "directory":
                    marker.mkdir()
                elif mode == "existing-avd":
                    marker.rmdir()
                    marker.write_bytes(b"package marker")
                elif mode == "invalid-userdata":
                    (image / "userdata.img").mkdir()
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                if mode == "existing-avd":
                    (guest.root / "avds").mkdir()
                    (guest.root / "avds" / "retained.txt").write_bytes(b"never reuse")
                with patch.object(guest, "_prepare_supervisor"), patch.object(guest, "_spawn") as spawn:
                    with self.assertRaises(FileExistsError if mode == "existing-avd" else PortError):
                        guest.start(self.ledger, emulator=emulator, adb=adb, image=image)
                    spawn.assert_not_called()
                self.assertEqual(self.rows(), before)
                if mode == "existing-avd":
                    self.assertEqual((guest.root / "avds" / "retained.txt").read_bytes(), b"never reuse")
                guest.dispose(self.ledger)
                self.assert_legacy()

    def test_private_adb_launch_uses_loopback_default_listener_and_explicit_client_endpoint(self):
        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text(
            "AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n", encoding="utf-8",
        )
        (image / "userdata.img").write_bytes(b"fixture initial data")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()

        def inspect_launch(command):
            self.assertEqual(command, [str(adb), "-L", f"tcp:{guest.server_port}", "nodaemon", "server"])
            self.assertNotIn("-a", command)
            self.assertEqual(guest.environment["ADB_SERVER_SOCKET"], f"tcp:127.0.0.1:{guest.server_port}")
            self.assertEqual(guest.environment["ANDROID_ADB_SERVER_PORT"], str(guest.server_port))
            raise RuntimeError("fixture stops before any process")

        with patch.object(guest, "_prepare_supervisor"), \
                patch.object(guest, "_spawn", side_effect=inspect_launch) as spawn, \
                patch.object(guest, "_cleanup"):
            try:
                with self.assertRaisesRegex(RuntimeError, "fixture stops before any process"):
                    guest.start(self.ledger, emulator=emulator, adb=adb, image=image)
                spawn.assert_called_once()
                self.assertEqual(self.rows(), before)
            finally:
                guest.dispose(self.ledger)
        self.assert_legacy()

    def test_new_guest_launch_preserves_boot_animation_for_strict_observed_boot_profile(self):
        from unittest.mock import Mock

        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text(
            "AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n", encoding="utf-8",
        )
        (image / "userdata.img").write_bytes(b"fixture immutable initial data")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        server = Mock()

        def launch(command):
            if command[0] == str(adb):
                self.assertEqual(command, [str(adb), "-L", f"tcp:{guest.server_port}", "nodaemon", "server"])
                return server
            data = guest.root / "avds" / (guest.token + ".avd")
            self.assertEqual(command, [
                str(emulator), "-avd", guest.token, "-datadir", str(data),
                "-data", str(data / "userdata-qemu.img"), "-ports", f"{guest.port},{guest.port + 1}",
                "-no-snapshot", "-no-window", "-no-audio", "-show-kernel", "-gpu", "swiftshader_indirect",
            ])
            config = (data / "config.ini").read_text()
            self.assertIn("hw.cpu.arch=x86_64\n", config)
            self.assertIn("disk.dataPartition.size=2048M\n", config)
            self.assertNotIn("hw.cpu.ncore=", config)
            self.assertNotIn("boot", config.lower())
            raise RuntimeError("fixture stops before emulator spawn")

        with patch.object(guest, "_prepare_supervisor"), \
                patch.object(guest, "_owns_listener", return_value=True) as owns, \
                patch.object(guest, "_spawn", side_effect=launch) as spawn, \
                patch.object(guest, "_cleanup"):
            try:
                with self.assertRaisesRegex(RuntimeError, "fixture stops before emulator spawn"):
                    guest.start(self.ledger, emulator=emulator, adb=adb, image=image, timeout=120)
                self.assertEqual(spawn.call_count, 2)
                owns.assert_called_once_with(server, guest.server_port)
                self.assertEqual(self.rows(), before)
            finally:
                guest.dispose(self.ledger)
        self.assert_legacy()

    def test_kernel_console_uses_existing_owned_log_and_retains_it_on_uncertain_cleanup(self):
        from unittest.mock import Mock

        for windows in (False, True):
            with self.subTest(windows=windows):
                guest = self.allocate()
                guest.acquire(self.ledger)
                before = self.rows()
                path = guest.root / "process.log"
                guest.log = path.open("xb")
                guest.environment = {"fixture": "private"}
                command = ["fixture-emulator", "-show-kernel"]
                output = b"fixture kernel console bytes, not native boot evidence\n"
                process, backend = Mock(), Mock()
                process.pid = 123
                if not windows:
                    backend.pidfd_open.return_value = os.open(os.devnull, os.O_RDONLY)
                job = Mock() if windows else None
                guest.job = job

                def launch(*args, **kwargs):
                    if windows:
                        self.assertEqual(args, (command, guest.environment, guest.log))
                        self.assertEqual(kwargs, {})
                    else:
                        self.assertEqual(args, (command,))
                        self.assertEqual(kwargs, {
                            "env": guest.environment, "stdin": subprocess.DEVNULL,
                            "stdout": guest.log, "stderr": subprocess.STDOUT, "start_new_session": True,
                        })
                    guest.log.write(output)
                    guest.log.flush()
                    return process

                try:
                    with patch("controller.disposable.supervisor", return_value=backend), \
                            patch("controller.disposable.subprocess.Popen", side_effect=launch) as popen:
                        if windows:
                            job.spawn.side_effect = launch
                        self.assertIs(guest._spawn(command), process)
                        self.assertEqual(guest.phase, "uncertain")
                        self.assertEqual(popen.call_count, 0 if windows else 1)
                    with patch.object(guest, "_cleanup", side_effect=PortError("fixture unproven cleanup")):
                        with self.assertRaisesRegex(PortError, "unproven cleanup"):
                            guest.dispose(self.ledger)
                    self.assertEqual(path.read_bytes(), output)
                    self.assertEqual(self.rows(), before)
                    self.assert_legacy()
                finally:
                    guest.log.close()
                    with patch.object(guest, "_cleanup"):
                        guest.dispose(self.ledger)

    def test_exited_private_windows_adb_never_launches_emulator_and_retains_uncertain_ownership(self):
        from unittest.mock import Mock

        image = self.root / "image"
        image.mkdir()
        (image / "source.properties").write_text(
            "AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n", encoding="utf-8",
        )
        (image / "userdata.img").write_bytes(b"fixture initial data")
        emulator, adb = self.root / "emulator", self.root / "adb"
        emulator.touch()
        adb.touch()
        guest = self.allocate()
        guest.acquire(self.ledger)
        before = self.rows()
        job, process = Mock(), Mock()
        process.poll.return_value = 1

        def prepare():
            guest.job = job

        with patch.object(guest, "_prepare_supervisor", side_effect=prepare), \
                patch.object(guest, "_spawn", return_value=process) as spawn:
            with self.assertRaisesRegex(PortError, "Owned Windows transport process exited"):
                guest.start(self.ledger, emulator=emulator, adb=adb, image=image)
            self.addCleanup(guest.log.close)
            spawn.assert_called_once_with([str(adb), "-L", f"tcp:{guest.server_port}", "nodaemon", "server"])
            job.owns_listener.assert_not_called()
        self.assertEqual(guest.phase, "uncertain")
        with patch.object(guest, "_cleanup", side_effect=PortError("fixture cleanup unproven")):
            with self.assertRaisesRegex(PortError, "cleanup unproven"):
                guest.dispose(self.ledger)
        self.assertEqual(self.rows(), before)
        self.assertTrue(guest.root.exists())
        self.assert_legacy()

    def test_unsupported_live_entrance_refuses_before_ledger_runtime_or_allocation(self):
        with patch("controller.disposable.sys.platform", "darwin"), \
                patch.object(PrivateGuest, "allocate") as allocate, \
                patch("controller.disposable.subprocess.check_output") as process:
            with self.assertRaisesRegex(PortError, "native Windows"):
                trusted_guest(REPO, self.root, owner="owner", revision=BASE,
                                session_id="session", attempt="attempt")
            allocate.assert_not_called()
            process.assert_not_called()
        self.assertEqual(self.rows(), self.legacy)

    def test_transport_identity_failure_prevents_any_adb_command_and_preserves_lease(self):
        guest = self.allocate()
        guest.acquire(self.ledger)
        guest.phase = "running"
        before = self.rows()
        with patch.object(guest, "_owns_listener", return_value=False), \
                patch("controller.disposable.subprocess.run") as run:
            guest.emulator, guest.port = object(), 5554
            with self.assertRaisesRegex(PortError, "not owned/live"):
                guest.adb(self.ledger, ["get-state"])
            run.assert_not_called()
        self.assertEqual(self.rows(), before)
        self.assert_legacy()

    def test_resource_and_capability_inverse_restore_entire_actual_ad40165_policy(self):
        current = load_json(REPO / "docs/android/automation-policy.json")
        previous = json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "ad40165c93d1bc21e073f91102971719b078ad02:docs/android/automation-policy.json",
        ]))
        before = copy.deepcopy(current)
        self.assertEqual(disposable_predecessor(current), previous)
        self.assertEqual(current, before)
        self.assertEqual(disposable_predecessor(previous), previous)
        text = (REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8")
        start = text.index('      {\n        "id": "wp404-shortcut-lifecycle-consumer-test",')
        end = text.index('      {\n        "id": "wp216-reaction-wire-classification",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), shortcut_lifecycle_test_predecessor(current))
        self.assertEqual(json.loads(text), json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "61fc048e1780173e2603013a8b6d0233d0d63c3b:docs/android/automation-policy.json",
        ])))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "61fc048e1780173e2603013a8b6d0233d0d63c3b:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp216-reaction-wire-classification",')
        end = text.index('      {\n        "id": "wp404-shared-location-producer-cache",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), reaction_wire_classification_predecessor(current))
        self.assertEqual(json.loads(text), json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "de20d1a6f3ff97068076204504ca7c854044bb77:docs/android/automation-policy.json",
        ])))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "de20d1a6f3ff97068076204504ca7c854044bb77:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp404-shared-location-producer-cache",')
        end = text.index('      {\n        "id": "wp201-rx-projection-source-regressions",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), shared_location_cache_predecessor(current))
        self.assertEqual(json.loads(text), json.loads(subprocess.check_output([
            "git", "-C", str(REPO), "show",
            "d2b649421ae7f31b2ef94b7dee20894abe658d22:docs/android/automation-policy.json",
        ])))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "d2b649421ae7f31b2ef94b7dee20894abe658d22:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp201-rx-projection-source-regressions",')
        end = text.index('      {\n        "id": "wp203-backup-post-commit-delivery",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), rx_projection_regression_predecessor(current))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "3498dead5e5cad13d71936d95ddd6860b7217c8c:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp203-backup-post-commit-delivery",')
        end = text.index('      {\n        "id": "persistence-schemas",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), backup_post_commit_predecessor(current))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "610f606ba:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp003-host-scoped-jvm-parallelism",')
        end = text.index('      {\n        "id": "disposable-native-guest",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), host_scoped_jvm_predecessor(current))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "a8c624bcb095f81a30e5fee671099fa519f608b7:docs/android/automation-policy.json",
        ]).strip())
        start = text.index(',\n      {\n        "id": "wp503-accessibility-source-binding",')
        end = text.index("\n    ]", start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), wp503_accessibility_source_binding_predecessor(current))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "f2f481d2e7c2a9029808943529c98c98ef879299:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp405-uri-region-normalization",')
        end = text.index('      {\n        "id": "wp-owned",', start)
        text = text[:start] + text[end:]
        self.assertEqual(
            json.loads(text),
            consumer_support_predecessor(wp503_accessibility_source_binding_predecessor(current)),
        )
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "263d30332940f09082709b9d38ee8877dfb9fedd:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp502-room-failed-event-consumer",')
        end = text.index('      {\n        "id": "dependency-resolution-metadata",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), room_failed_event_predecessor(current))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "6a5492b6814db737a8caab898fb8f0b72e798191:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp501-bubble-consumer-support",')
        end = text.index('      {\n        "id": "app-build-launcher",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), bubble_consumer_predecessor(current))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "31557a16ca4492bbd669a7ad0358e702c5c54031:docs/android/automation-policy.json",
        ]).strip())
        start = text.index('      {\n        "id": "wp303-room-cleanup-companion",')
        end = text.index('      {\n        "id": "traceability-validation",', start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), room_cleanup_predecessor(current))
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "a5dd0fc6c521d09d59d28ca75169e4bdbd56a6cd:docs/android/automation-policy.json",
        ]).strip())
        start = text.index(',\n      {\n        "id": "repeat-row-fallback-affordance",')
        end = text.index("\n    ]", start)
        text = text[:start] + text[end:]
        self.assertEqual(json.loads(text), parity_support_predecessor(current))
        start = text.index('      {\n        "id": "disposable-native-guest",')
        end = text.index('      {\n        "id": "wp-owned",', start)
        reversed_text = (text[:start] + text[end:]).replace(
            '      "repository-settings",\n      "disposable-native-execution"',
            '      "repository-settings"',
        )
        self.assertEqual(subprocess.check_output([
            "git", "-C", str(REPO), "hash-object", "--path=docs/android/automation-policy.json", "--stdin",
        ], input=reversed_text.encode()).strip(), subprocess.check_output([
            "git", "-C", str(REPO), "rev-parse",
            "ad40165c93d1bc21e073f91102971719b078ad02:docs/android/automation-policy.json",
        ]).strip())
        for kind in ("resource", "duplicate", "operation", "wp", "path"):
            changed = copy.deepcopy(current)
            rule = next(r for r in changed["reservation_policy"]["capabilities"]
                        if r["id"] == "disposable-native-guest")
            if kind == "resource":
                changed["reservation_policy"]["hard_lock_resources"].remove(RESOURCE)
            elif kind == "duplicate":
                changed["reservation_policy"]["capabilities"].append(copy.deepcopy(rule))
            elif kind == "operation":
                rule["operations"].append("modify")
            elif kind == "wp":
                rule["work_packages"].append("WP-502")
            else:
                rule["path_rules"].append("android/**")
            with self.subTest(kind=kind), self.assertRaises(PortError):
                disposable_predecessor(changed)

    def test_capability_has_exact_wp_path_operation_and_candidate_is_not_live_authority(self):
        engine = CapabilityEngine(policy())
        self.assertEqual(engine.admit("WP-503", [], ADAPTER, OPERATION).capability_id, "disposable-native-guest")
        previous = disposable_predecessor(policy())
        with self.assertRaises(PortError):
            CapabilityEngine(previous).admit("WP-503", [], ADAPTER, OPERATION)
        for wp, path, operation in (
            ("WP-502", ADAPTER, OPERATION), ("WP-503", ADAPTER, "modify"),
            ("WP-503", "tools/android-port/local/debug_packaging.py", OPERATION),
            ("WP-503", "android/app/fixture.kt", OPERATION),
        ):
            with self.subTest(wp=wp, path=path, operation=operation), self.assertRaises(PortError):
                engine.admit(wp, [], path, operation)

    def test_actual_owned_process_disposal_in_isolated_linux_worker_or_prelaunch_rejection(self):
        if not sys.platform.startswith("linux"):
            guest = self.allocate()
            with patch("controller.disposable.sys.platform", "darwin"), self.assertRaisesRegex(PortError, "Linux pidfd"):
                guest._prepare_supervisor()
            return
        script = r'''
import os, pathlib, sys, tempfile
from controller.disposable import PrivateGuest
from controller.ledger import Ledger
with tempfile.TemporaryDirectory() as directory:
    root=pathlib.Path(directory).resolve()
    ledger=Ledger(root/"ledger.sqlite","fixture/repo")
    ledger.acquire_hard_lock("native-execution","legacy",{"old":"untouched"},123)
    before=ledger.hard_lock_records()
    guest=PrivateGuest.allocate(root,repository="fixture/repo",owner="fixture",revision="1"*40)
    guest.acquire(ledger)
    guest._prepare_supervisor()
    guest.environment=dict(os.environ)
    guest.log=(guest.root/"process.log").open("xb")
    process=guest._spawn([sys.executable,"-c","import time; time.sleep(60)"])
    guest.dispose(ledger)
    assert process.poll() is not None
    assert ledger.hard_lock_records()==before
    assert guest.phase=="disposed" and guest.root.exists()
'''
        environment = {**os.environ, "PYTHONPATH": str(REPO / "tools/android-port")}
        subprocess.run([sys.executable, "-c", script], env=environment, check=True, timeout=15,
                       capture_output=True)
