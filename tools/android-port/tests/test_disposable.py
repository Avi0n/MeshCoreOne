"""AndroidOnly: WP-003 Private disposable guests; fixtures never start Android tools."""

import copy
from contextlib import closing
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
from controller.scope_amendment import disposable_predecessor, parity_support_predecessor
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
                patch.object(guest, "_spawn", side_effect=inspect_launch) as spawn:
            try:
                with self.assertRaisesRegex(RuntimeError, "fixture stops before any process"):
                    guest.start(self.ledger, emulator=emulator, adb=adb, image=image)
                spawn.assert_called_once()
                self.assertEqual(self.rows(), before)
            finally:
                guest.dispose(self.ledger)
        self.assert_legacy()

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
            rule = changed["reservation_policy"]["capabilities"][0]
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
