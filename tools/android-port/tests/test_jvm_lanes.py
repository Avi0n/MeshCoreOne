"""AndroidOnly: WP-003 Actual temporary-ledger concurrency, never live execution."""

from concurrent.futures import ThreadPoolExecutor
import copy
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from controller.errors import PortError
from controller import jvm_lanes
from controller.ledger import Ledger


class JvmLaneTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="jvm-lane-fixture-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.ledger = Ledger(self.root / "shared.sqlite3", "fixture/repository")
        self.other = Ledger(self.ledger.path, self.ledger.repository)
        self.host = "a" * 64
        self.payload = {"commit": "b" * 40, "tree": "c" * 40, "token": "actual-fixture"}

    def acquire(self, owner, *, host=None, capacity=3, ledger=None):
        with patch.object(jvm_lanes, "host_capacity", return_value=(host or self.host, capacity)):
            return (ledger or self.ledger).acquire_jvm_lane(owner, self.payload, 123.0)

    def test_same_host_capacity_is_atomic_across_real_connections_and_refills(self):
        barrier = threading.Barrier(6)

        def compete(index):
            ledger = Ledger(self.ledger.path, self.ledger.repository)
            barrier.wait(timeout=10)
            try:
                return ledger.acquire_jvm_lane(f"fixture-{index}", self.payload, 123.0)
            except PortError as error:
                self.assertIn("capacity", str(error))
                return None

        with patch.object(jvm_lanes, "host_capacity", return_value=(self.host, 3)):
            with ThreadPoolExecutor(max_workers=6) as workers:
                records = list(workers.map(compete, range(6)))
        held = [record for record in records if record is not None]
        self.assertEqual(len(held), 3)
        self.assertEqual({record["payload"]["jvm_lane"]["slot"] for record in held}, {0, 1, 2})
        self.assertEqual(len(self.other.hard_lock_records()), 3)
        with self.assertRaises(PortError):
            self.other.release_hard_lock(held[0]["resource"], "foreign")
        self.other.release_hard_lock(held[0]["resource"], held[0]["owner"])
        refill = self.acquire("ready-refill", ledger=self.other)
        self.assertEqual(refill["resource"], held[0]["resource"])
        self.assertEqual(len(self.ledger.hard_lock_records()), 3)

    def test_real_distinct_hosts_share_one_ledger_without_sharing_slot_names(self):
        first = self.acquire("first-host", capacity=1)
        second = self.acquire("second-host", host="d" * 64, capacity=1, ledger=self.other)
        self.assertNotEqual(first["resource"], second["resource"])
        self.assertEqual(len(self.ledger.hard_lock_records()), 2)
        with self.assertRaisesRegex(PortError, "capacity"):
            self.acquire("first-host-overflow", capacity=1)

    def test_every_legacy_execution_and_install_fence_is_two_sided(self):
        for resource in ("native-execution", "gradle-execution", "installed-git-common-runtime"):
            with self.subTest(resource=resource):
                self.ledger.acquire_hard_lock(resource, "retained-owner", {"original": [1, 2]}, 1.0)
                original = copy.deepcopy(self.ledger.hard_lock_records())
                with self.assertRaisesRegex(PortError, "existing external resource"):
                    self.acquire("new-jvm-owner")
                self.assertEqual(original, self.ledger.hard_lock_records())
                self.ledger.release_hard_lock(resource, "retained-owner")
                lane = self.acquire("jvm-owner")
                with self.assertRaisesRegex(PortError, "collision"):
                    self.other.acquire_hard_lock(resource, "jvm-owner", {}, 2.0)
                self.ledger.release_hard_lock(lane["resource"], lane["owner"])

    def test_arbitrary_dynamic_resource_cannot_bypass_admission(self):
        with self.assertRaisesRegex(PortError, "Unsupported"):
            self.ledger.acquire_hard_lock(f"gradle-execution:{self.host}:999", "owner", {}, 1.0)
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_native_serial_and_install_races_never_cross_a_jvm_owner(self):
        for resource in ("native-execution", "gradle-execution", "installed-git-common-runtime"):
            barrier = threading.Barrier(2)

            def compete(jvm):
                barrier.wait(timeout=10)
                try:
                    if jvm:
                        return self.ledger.acquire_jvm_lane("jvm", self.payload, 1.0)
                    return self.other.acquire_hard_lock(resource, "external", {}, 1.0)
                except PortError:
                    return None

            with self.subTest(resource=resource), \
                    patch.object(jvm_lanes, "host_capacity", return_value=(self.host, 3)):
                with ThreadPoolExecutor(max_workers=2) as workers:
                    results = list(workers.map(compete, (True, False)))
                self.assertEqual(sum(result is not None for result in results), 1)
                self.assertEqual(len(self.ledger.hard_lock_records()), 1)
                winner = next(result for result in results if result is not None)
                self.ledger.release_hard_lock(winner["resource"], winner["owner"])

    def test_same_owner_cannot_reenter_or_claim_a_second_host_with_stale_receipt(self):
        first = self.acquire("owner")
        for host in (self.host, "d" * 64):
            with self.assertRaisesRegex(PortError, "retained receipt"):
                self.acquire("owner", host=host)
        self.assertEqual(self.ledger.hard_lock_records()[0]["payload"], first["payload"])

    def test_pressure_blocks_new_work_without_releasing_existing_owners(self):
        self.acquire("first")
        self.acquire("second")
        original = self.ledger.hard_lock_records()
        with self.assertRaisesRegex(PortError, "capacity"):
            self.acquire("third", capacity=1)
        self.assertEqual(original, self.ledger.hard_lock_records())

    def test_failed_measurement_and_forged_binding_leave_no_execution_record(self):
        with patch.object(jvm_lanes, "host_capacity", side_effect=PortError("unknown actual host")):
            with self.assertRaisesRegex(PortError, "unknown actual host"):
                self.ledger.acquire_jvm_lane("owner", self.payload, 1.0)
        with self.assertRaisesRegex(PortError, "fresh owned"):
            self.ledger.acquire_jvm_lane("owner", {**self.payload, "jvm_lane": {}}, 1.0)
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_binding_is_host_checked_and_each_slot_has_an_isolated_root(self):
        with patch.object(jvm_lanes, "host_identity", return_value=self.host), \
                patch.object(jvm_lanes, "cpu_capacity", return_value=3):
            first = {"host": self.host, "slot": 0, "capacity": 3}
            second = {**first, "slot": 1}
            self.assertNotEqual(
                jvm_lanes.lane_directory(self.root, first), jvm_lanes.lane_directory(self.root, second),
            )
            for invalid in (
                {**first, "host": "d" * 64}, {**first, "slot": -1},
                {**first, "slot": True}, {**first, "slot": 3},
                {**first, "capacity": 4}, {**first, "host": "../escape"},
                {**first, "unknown": 1},
            ):
                with self.subTest(binding=invalid), self.assertRaises(PortError):
                    jvm_lanes.validate_binding(invalid)


class HostMeasurementTests(unittest.TestCase):
    def measured(self, files, *, cpus=12):
        def read(path, *args, **kwargs):
            return files[str(path).replace("\\", "/")]

        with patch.object(jvm_lanes.sys, "platform", "linux"), \
                patch.object(jvm_lanes.os, "sched_getaffinity", return_value=set(range(cpus)), create=True), \
                patch.object(Path, "read_text", autospec=True, side_effect=read), \
                patch.object(Path, "is_file", autospec=True,
                             side_effect=lambda path: str(path).replace("\\", "/") in files):
            return jvm_lanes.host_capacity()

    def files(self):
        return {
            "/etc/machine-id": "a" * 32, "/proc/meminfo": f"MemAvailable: {44 * 1024**2} kB\n",
            "/sys/fs/cgroup/cpu.max": "max 100000",
            "/sys/fs/cgroup/memory.max": "max",
        }

    def test_actual_twelve_cpu_host_supports_three_not_a_permanent_two_slot_cap(self):
        host, capacity = self.measured(self.files())
        self.assertRegex(host, r"^[0-9a-f]{64}$")
        self.assertEqual(capacity, 3)
        self.assertEqual(self.measured(self.files(), cpus=20)[1], 5)

    def test_actual_memory_cpu_and_cgroup_bounds_limit_slots(self):
        files = self.files()
        files["/proc/meminfo"] = f"MemAvailable: {8 * 1024**2} kB\n"
        self.assertEqual(self.measured(files)[1], 1)
        files = self.files()
        files["/sys/fs/cgroup/cpu.max"] = "800000 100000"
        self.assertEqual(self.measured(files)[1], 2)
        files["/sys/fs/cgroup/memory.max"] = str(20 * 1024**3)
        files["/sys/fs/cgroup/memory.current"] = str(12 * 1024**3)
        self.assertEqual(self.measured(files)[1], 1)

    def test_unknown_identity_malformed_metrics_and_insufficient_capacity_fail(self):
        for path, value in (
            ("/etc/machine-id", "caller-nonce"), ("/proc/meminfo", "missing"),
            ("/sys/fs/cgroup/cpu.max", "invalid quota"),
            ("/proc/meminfo", "MemAvailable: 1 kB\n"),
        ):
            files = self.files()
            files[path] = value
            with self.subTest(path=path), self.assertRaises(PortError):
                self.measured(files)
        with patch.object(jvm_lanes.sys, "platform", "win32"):
            with self.assertRaisesRegex(PortError, "actual supported Linux"):
                jvm_lanes.host_identity()
