"""AndroidOnly: WP-503 Consumer-only protocol doubles; no producer/backend/native verification."""

from contextlib import ExitStack, contextmanager, nullcontext, redirect_stderr, redirect_stdout
from dataclasses import replace
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, call, patch
import zipfile

import disposable_runner as runner
from local.debug_packaging import verify_pair
from shared_device_runner import APP, TEST, SCHEMA, sha256
from test_language_binding import source, transcript

AUTHORITY = "d" * 40


class BootOfflineModel(subprocess.CalledProcessError):
    """Only the trusted producer classifies actual offline diagnostics."""


class LedgerModel:
    def __init__(self, path, repository):
        self.path, self.repository = path, repository
        self.legacy = {"owner": "retained-66bd-model", "payload": b"unchanged", "acquired": 473}
        self.events = []


class GuestModel:
    """Opaque producer protocol model, accepted only by the test's patched trust loader."""

    def __init__(self, ledger, request, parent):
        self.ledger, self.request = ledger, request
        self.repository, self.owner, self.revision = ledger.repository, request.operation_owner, request.binding.source_revision
        self.root = parent / "model-private"
        self.root.mkdir()
        self.token = "c" * 32
        self.identity_binding = {
            "session_id": request.binding.canonical_session, "attempt": request.binding.canonical_session,
            "trusted_revision": AUTHORITY, "reservation_revision": request.binding.reservation_revision,
        }
        self.phase, self.held = "prepared", True
        self.resources = ["disposable-native-execution", "gradle-execution"]
        self.packages = {}
        self.settings = {
            ("system", "font_scale"): "1.0", ("secure", "show_ime_with_hard_keyboard"): "0",
            **{("global", key): "1" for key in (
                "animator_duration_scale", "window_animation_scale", "transition_animation_scale")},
        }
        self.display = {"size": None, "density": None}
        self.instrumentation_code = 0
        self.method_code = 0
        self.instrumentation_error = self.dispose_error = self.install_error = None
        self.current_user = "0"
        self.boot = "1"
        self.cpus = "4"

    def assert_held(self, ledger, owner):
        if ledger is not self.ledger or owner != self.owner or not self.held or self.resources != [
                "disposable-native-execution", "gradle-execution"]:
            raise runner.PortError("Model receipt identity/held resources changed")

    def start(self, ledger, *, emulator, adb, image, cpu_cores=None):
        self.assert_held(ledger, self.owner)
        assert self.phase == "prepared" and "package" in ledger.events
        self.requested_cpu_cores = cpu_cores
        ledger.events.append("start")
        self.server_port, self.port = 5011, 5590
        self.phase = "running"

    def adb(self, ledger, arguments, *, timeout=60):
        self.assert_held(ledger, self.owner)
        assert self.phase == "running"
        assert isinstance(arguments, list) and 0 < timeout <= 180
        assert arguments[0] in ("wait-for-device", "get-state", "shell", "install", "logcat")
        ledger.events.append(tuple(arguments))
        output = b""
        if arguments[0] == "wait-for-device":
            assert arguments == ["wait-for-device"]
            output = b""
        elif arguments[0] == "get-state":
            output = b"device"
        elif arguments[0] == "install":
            package = TEST if Path(arguments[-1]).name == "current-tests.apk" else APP
            self.packages[package] = sha256(Path(arguments[-1]))
            if self.install_error:
                self.phase = "uncertain"
                raise self.install_error
            output = b"Success"
        elif arguments[0] == "logcat":
            output = b"WP503_CONSUMER_MODEL_ONLY\n"
        elif arguments[:3] == ["shell", "am", "instrument"]:
            assert arguments[3:5] == ["--user", "0"]
            assert arguments[arguments.index("class") + 1] == ",".join(self.request.identities)
            assert arguments[arguments.index("expectedApi") + 1] == str(self.request.api)
            assert timeout == runner.INSTRUMENTATION_TIMEOUT
            output = transcript(self.method_code, family=self.request.binding.family).encode()
            if self.instrumentation_error:
                self.phase = "uncertain"
                raise self.instrumentation_error
            if self.instrumentation_code:
                self.phase = "uncertain"
                raise subprocess.CalledProcessError(self.instrumentation_code, arguments, output=output)
        else:
            command = " ".join(arguments[1:])
            if command == "am get-current-user":
                output = self.current_user.encode()
            elif command.startswith("pm list packages "):
                output = "\n".join("package:" + p for p in sorted(self.packages)
                                   if p.startswith(arguments[-1])).encode()
            elif command.startswith("pm path --user 0 "):
                output = ("package:/data/app/" + arguments[-1] + "/base.apk").encode()
            elif command.startswith("sha256sum "):
                package = command.split("/")[3]
                output = (self.packages[package] + "  model-path").encode()
            elif command.startswith("getprop "):
                values = {
                    "ro.build.version.sdk": str(self.request.api), "ro.build.version.release": "model-release",
                    "sys.boot_completed": self.boot, "init.svc.bootanim": "stopped",
                    "ro.build.fingerprint": "consumer-model-not-native",
                }
                output = values[command.removeprefix("getprop ")].encode()
            elif command == "getconf _NPROCESSORS_ONLN":
                output = self.cpus.encode()
            elif command.startswith("settings --user 0 "):
                words = command.removeprefix("settings --user 0 ").split()
                key = tuple(words[1:3])
                if words[0] == "get":
                    output = self.settings[key].encode()
                elif words[0] == "put":
                    self.settings[key] = words[3]
                else:
                    raise AssertionError(command)
            elif command.startswith("wm "):
                words = command.split()
                if len(words) == 3:
                    self.display[words[1]] = words[2]
                else:
                    key = words[1]
                    physical = "1080x1920" if key == "size" else "420"
                    output = (f"Physical {key}: {physical}" +
                              (f"\nOverride {key}: {self.display[key]}" if self.display[key] else "")).encode()
            elif command == "dumpsys activity | grep -F mGlobalConfiguration":
                output = ("mGlobalConfiguration: {" + self.settings[("system", "font_scale")] + " model}").encode()
            elif command == "dumpsys activity":
                output = b""
            elif command.startswith("run-as " + APP + " --user 0 "):
                output = b"WP503_NO_LANGUAGE_PREFS"
            elif command == "cmd locale get-app-locales " + APP + " --user 0":
                output = ("Locales for " + APP + " for user 0 are []").encode()
            else:
                raise AssertionError(command)
        return subprocess.CompletedProcess(arguments, 0, stdout=output, stderr=b"")

    @contextmanager
    def boot_readiness(self, ledger, *, deadline):
        self.assert_held(ledger, self.owner)
        assert self.phase == "running"
        self.boot_deadline = deadline
        try:
            yield
        finally:
            self.boot_deadline = None

    def reattach_boot(self, ledger, *, timeout):
        self.assert_held(ledger, self.owner)
        assert self.boot_deadline is not None
        ledger.events.append("reattach")
        return self.adb(ledger, ["wait-for-device"], timeout=timeout)

    def dispose(self, ledger):
        ledger.events.append("dispose")
        self.assert_held(ledger, self.owner)
        if self.dispose_error:
            raise self.dispose_error
        self.phase, self.held = "disposed", False
        self.resources.clear()

    def verify_disposed(self):
        assert self.phase == "disposed" and not self.held and not self.resources


class DisposableConsumerTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.root = Path(self.stack.enter_context(tempfile.TemporaryDirectory())).resolve()
        sdk = self.root / "sdk"
        suffix = ".exe" if os.name == "nt" else ""
        adb, emulator = sdk / "platform-tools" / ("adb" + suffix), sdk / "emulator" / ("emulator" + suffix)
        for path in (adb, emulator):
            path.parent.mkdir(parents=True)
            path.touch()
        image = sdk / "system-images" / "model"
        image.mkdir(parents=True)
        (image / "source.properties").write_text("AndroidVersion.ApiLevel=37\nSystemImage.Abi=x86_64\n")
        debug, parent = self.root / "debug", self.root / "private"
        debug.mkdir()
        parent.mkdir()
        (debug / "debug.keystore").touch()
        state = self.root / "state.json"
        state.write_text(json.dumps({"android_home": str(sdk)}))
        self.config = runner.DisposableConfig(parent, state, debug, self.root / "output", emulator, adb, image)
        self.request = runner.request_for(source("nodes"), 37)
        self.policy = {"paused": False, "repository": self.request.binding.repository}
        self.ledger = LedgerModel(self.root / "canonical.sqlite", self.request.binding.repository)
        self.guest = None
        self.alter_guest = lambda guest: None
        self.factory = Mock(side_effect=self.allocate)
        self.package = Mock(side_effect=self.packaging)
        self.module = SimpleNamespace(PrivateGuest=GuestModel, trusted_guest=self.factory,
                                      BootReadinessOffline=BootOfflineModel)
        self.packager = SimpleNamespace(assemble_debug_pair=self.package, verify_pair=verify_pair)
        self.real_loader = runner.trusted_producer
        self.loader_patch = patch.object(
            runner, "trusted_producer", return_value=(AUTHORITY, self.policy, self.module, self.packager))
        self.loader = self.stack.enter_context(self.loader_patch)
        self.verify = self.stack.enter_context(patch.object(runner, "verify_current_request", return_value=self.policy))
        self.stack.enter_context(patch.object(runner, "candidate_environment", return_value={}))
        self.stack.enter_context(patch.object(runner, "load_manifest", return_value=Mock()))
        self.stack.enter_context(patch.object(runner, "Ledger", LedgerModel))
        self.stack.enter_context(patch.object(runner, "shared_ledger", return_value=self.ledger.path))
        self.apks = {}
        for label in ("app", "tests"):
            path = self.root / (label + ".apk")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"consumer-model-" + label.encode() + SCHEMA)
            self.apks[label] = path
        self.legacy = self.ledger.legacy.copy()
        self.addCleanup(lambda: self.assertEqual(self.legacy, self.ledger.legacy))

    def allocate(self, repo, parent, **kwargs):
        self.assertEqual(runner.ROOT, repo)
        self.assertEqual(self.config.parent, parent)
        self.assertEqual({
            "owner": self.request.operation_owner, "revision": self.request.binding.source_revision,
            "session_id": self.request.binding.canonical_session, "attempt": self.request.binding.canonical_session,
        }, kwargs)
        self.guest = GuestModel(self.ledger, self.request, parent)
        self.alter_guest(self.guest)
        return self.ledger, self.guest

    def packaging(self, **kwargs):
        self.assertIs(self.guest, kwargs["guest"])
        self.assertIs(self.ledger, kwargs["ledger"])
        self.assertIsNone(kwargs["installed_apk"])
        self.assertEqual(self.request.operation_owner, kwargs["owner"])
        self.guest.assert_held(self.ledger, kwargs["owner"])
        self.assertEqual("prepared", self.guest.phase)
        self.ledger.events.append("package")
        return {
            "owner": kwargs["owner"], "source_revision": kwargs["revision"], "installed_snapshot": None,
            "android_source_tree_sha256": self.request.binding.android_source_tree_sha256,
            "manifest_sha256": self.request.binding.manifest_sha256,
            "policy_revision": self.request.binding.policy_revision,
            "disposable_guest": {"root": str(self.guest.root), "token": self.guest.token, "revision": self.guest.revision},
            **{label: {"path": str(self.apks[label]), "sha256": sha256(self.apks[label]),
                       "package": TEST if label == "tests" else APP, "certificate_sha256": "c" * 64}
               for label in ("app", "tests")},
        }

    def execute(self):
        return runner.execute_disposable(self.request, self.config)

    def operations(self):
        return json.loads((self.config.output / "operations.json").read_text())

    def assert_disposed(self):
        self.assertFalse(self.guest.held)
        self.assertEqual("disposed", self.guest.phase)
        self.assertEqual([], self.guest.resources)

    @contextmanager
    def trust_source_model(self, policy):
        root = self.root / "trust-source-model"
        for path in runner.PRODUCER_INPUTS:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"unit-only source-identity model; not producer code")
        self.loader.side_effect = self.real_loader
        with patch.object(runner, "ROOT", root), patch.object(runner, "git", return_value=AUTHORITY), \
                patch.object(runner, "trusted_execution_policy", return_value=policy), \
                patch.object(runner, "tree", return_value=dict(runner.PRODUCER_INPUTS)), \
                patch.object(runner, "object_bytes", return_value=b"unit-only source-identity model; not producer code"), \
                patch.object(runner, "load_manifest", return_value=SimpleNamespace(
                    wp=lambda _: {"write_paths": ["android/accessibility-tests/"]})), \
                patch.object(runner, "CapabilityEngine") as engine, patch.object(runner, "import_module") as importing:
            yield engine, importing

    def test_real_missing_main_producer_fails_before_import_allocation_or_config_validation(self):
        self.loader.side_effect = self.real_loader
        with patch.object(runner, "git", return_value=AUTHORITY), \
                patch.object(runner, "trusted_execution_policy", return_value=self.policy), \
                patch.object(runner, "tree", return_value={}), patch.object(runner, "import_module") as importing:
            with self.assertRaisesRegex(runner.PortError, "missing.*trusted-main"):
                self.execute()
            importing.assert_not_called()
        self.factory.assert_not_called()
        self.package.assert_not_called()
        self.assertFalse(self.config.output.exists())

    def test_candidate_producer_blob_is_not_authority_even_if_the_module_would_be_available(self):
        self.loader.side_effect = self.real_loader
        entries = dict(runner.PRODUCER_INPUTS)
        entries[runner.GUEST_ADAPTER] = "0" * 40
        with patch.object(runner, "git", return_value=AUTHORITY), \
                patch.object(runner, "trusted_execution_policy", return_value=self.policy), \
                patch.object(runner, "tree", return_value=entries), patch.object(runner, "import_module") as importing:
            with self.assertRaisesRegex(runner.PortError, "differs.*trusted-main"):
                self.execute()
            importing.assert_not_called()
        self.factory.assert_not_called()

    def test_missing_or_foreign_checkout_bytes_fail_before_import_and_allocation(self):
        self.loader.side_effect = self.real_loader
        path = self.root / runner.GUEST_ADAPTER
        for contents in (None, b"foreign source bytes"):
            if contents is not None:
                path.parent.mkdir(parents=True)
                path.write_bytes(contents)
            with self.subTest(contents=contents), patch.object(runner, "ROOT", self.root), \
                    patch.object(runner, "git", return_value=AUTHORITY), \
                    patch.object(runner, "trusted_execution_policy", return_value=self.policy), \
                    patch.object(runner, "tree", return_value=dict(runner.PRODUCER_INPUTS)), \
                    patch.object(runner, "object_bytes", return_value=b"reviewed source bytes"), \
                    patch.object(runner, "import_module") as importing:
                with self.assertRaisesRegex(runner.PortError, "actual trusted-main checkout"):
                    self.execute()
                importing.assert_not_called()
        self.factory.assert_not_called()

    def test_trusted_pause_and_missing_resource_reject_before_import_or_factory(self):
        for paused, resources in ((True, ["disposable-native-execution"]), (False, [])):
            with self.subTest(paused=paused), self.trust_source_model({
                    **self.policy, "paused": paused,
                    "reservation_policy": {"hard_lock_resources": resources}}) as (_, importing):
                with self.assertRaisesRegex(runner.PortError, "not admitted disposable"):
                    self.execute()
                importing.assert_not_called()
        self.factory.assert_not_called()

    def test_missing_trusted_capability_cannot_be_replaced_by_a_test_receipt(self):
        with self.trust_source_model(self.policy) as (engine, importing):
            engine.return_value.admit.side_effect = runner.PortError("missing trusted capability")
            with self.assertRaisesRegex(runner.PortError, "missing trusted capability"):
                self.execute()
            importing.assert_not_called()
        self.factory.assert_not_called()

    def test_foreign_import_origin_is_rejected_even_after_matching_source_model_and_policy(self):
        policy = {**self.policy, "reservation_policy": {"hard_lock_resources": ["disposable-native-execution"]}}
        with self.trust_source_model(policy) as (_, importing):
            importing.return_value = SimpleNamespace(__file__=str(self.root / "foreign.py"))
            with self.assertRaisesRegex(runner.PortError, "foreign producer runtime"):
                self.execute()
        self.factory.assert_not_called()

    def test_fixed_cold_controls_use_one_original_instrumentation_and_no_activity_or_warmup_model(self):
        for name in ("platform-service-control", "platform-callback-control"):
            with self.subTest(family=name):
                self.config = replace(self.config, parent=self.root / ("private-" + name),
                                      output=self.root / name, cpu_cores=4)
                self.config.parent.mkdir()
                self.request = runner.request_for(source(name), 37)
                self.ledger.events.clear()
                result = self.execute()
                commands = [event for event in self.ledger.events if isinstance(event, tuple)]
                instruments = [event for event in commands if event[:3] == ("shell", "am", "instrument")]
                self.assertEqual(1, len(instruments))
                self.assertEqual(",".join(self.request.identities), instruments[0][instruments[0].index("class") + 1])
                self.assertEqual("37", instruments[0][instruments[0].index("expectedApi") + 1])
                self.assertFalse(any(event[:3] == ("shell", "am", "start") for event in commands))
                self.assertEqual(list(self.request.identities), list(result["original_identities"]))
                self.assertEqual((1, 1, 1, 1), tuple(result["cohort"][key] for key in (
                    "discovered", "started", "matching_outcomes", "passed")))
                self.assertFalse(result["acceptance"])
                self.assertEqual(0, result["host_instrumentation_process_exit"])
                self.assertTrue(result["disposal_verified"])
                self.assertEqual(4, self.guest.requested_cpu_cores)
                self.assert_disposed()

    def test_fixed_cold_control_failures_keep_original_outcome_raw_host_exit_and_independent_disposal_model(self):
        self.alter_guest = lambda guest: setattr(guest, "method_code", -2)
        for name in ("platform-service-control", "platform-callback-control"):
            with self.subTest(family=name):
                self.config = replace(self.config, parent=self.root / ("private-" + name), output=self.root / name)
                self.config.parent.mkdir()
                self.request = runner.request_for(source(name), 37)
                with self.assertRaisesRegex(RuntimeError, "Normal all-positive"):
                    self.execute()
                actual = self.operations()
                self.assertEqual(0, actual["host_instrumentation_process_exit"])
                self.assertEqual((1, 1, 1, 0, 1, 0, 0), tuple(actual["cohort"][key] for key in (
                    "discovered", "started", "matching_outcomes", "passed", "failed", "errors", "skipped")))
                self.assertEqual(transcript(-2, family=name).encode(),
                                 (self.config.output / ("device-" + name + "37.txt")).read_bytes())
                self.assertTrue(actual["disposal_verified"])
                self.assertFalse(actual["acceptance"])
                self.assert_disposed()

    def test_stale_request_stops_before_factory_and_package(self):
        self.verify.side_effect = runner.PortError("stale request")
        with self.assertRaisesRegex(runner.PortError, "stale request"):
            self.execute()
        self.factory.assert_not_called()
        self.package.assert_not_called()

    def test_wrong_image_api_stops_before_factory(self):
        for api in ("31", "37.2", "37.00", "37.0invalid", ""):
            with self.subTest(api=api):
                (self.config.image / "source.properties").write_text(f"AndroidVersion.ApiLevel={api}\n")
                with self.assertRaisesRegex(runner.PortError, "image API"):
                    self.execute()
        self.factory.assert_not_called()

    def test_existing_output_cannot_be_adopted_or_overwritten(self):
        self.config.output.mkdir()
        with self.assertRaisesRegex(runner.PortError, "new external output"):
            self.execute()
        self.factory.assert_not_called()

    def test_raced_output_creation_preserves_foreign_receipt_and_disposes_only_the_owned_guest(self):
        sentinel = b"foreign operations.json: \x00\xff unchanged"

        def race(guest):
            self.config.output.mkdir()
            (self.config.output / "operations.json").write_bytes(sentinel)

        self.alter_guest = race
        with patch.object(runner, "save_operations", wraps=runner.save_operations) as saving:
            with self.assertRaises(FileExistsError) as raised:
                self.execute()
            saving.assert_not_called()
        self.assertEqual(str(self.config.output), raised.exception.filename)
        self.assertEqual(sentinel, (self.config.output / "operations.json").read_bytes())
        self.assertEqual(["operations.json"], sorted(p.name for p in self.config.output.iterdir()))
        self.package.assert_not_called()
        self.assertEqual(["dispose"], self.ledger.events)
        self.assert_disposed()

    def test_raced_output_and_disposal_failure_preserve_both_errors_without_foreign_writes(self):
        sentinel = b"foreign receipt must survive cleanup failure"
        cleanup = runner.PortError("model owned disposal failed")

        def race(guest):
            self.config.output.mkdir()
            (self.config.output / "operations.json").write_bytes(sentinel)
            guest.dispose_error = cleanup

        self.alter_guest = race
        with patch.object(runner, "save_operations", wraps=runner.save_operations) as saving:
            with self.assertRaises(BaseExceptionGroup) as raised:
                self.execute()
            saving.assert_not_called()
        self.assertEqual(2, len(raised.exception.exceptions))
        self.assertIsInstance(raised.exception.exceptions[0], FileExistsError)
        self.assertIs(cleanup, raised.exception.exceptions[1])
        self.assertEqual(sentinel, (self.config.output / "operations.json").read_bytes())
        self.package.assert_not_called()
        self.assertEqual(["dispose"], self.ledger.events)
        self.assertTrue(self.guest.held)

    def test_command_line_cannot_choose_a_historical_endpoint_or_alternate_ledger(self):
        args = ["--head", "0" * 40, "--api", "37", "--family", "nodes", "--execute"]
        for key in ("parent", "state", "debug_home", "output", "emulator", "adb", "image"):
            args += ["--" + key.replace("_", "-"), str(getattr(self.config, key))]
        for option in ("--serial", "--host", "--port", "--ledger", "--disposable", "--provider"):
            with self.subTest(option=option), redirect_stderr(io.StringIO()), \
                    patch.object(runner, "current_binding") as binding:
                with self.assertRaises(SystemExit) as raised:
                    runner.main([*args, option, "historical-or-foreign"])
                self.assertEqual(2, raised.exception.code)
                binding.assert_not_called()
        self.factory.assert_not_called()

    def test_command_line_cpu_request_is_optional_and_only_accepts_four(self):
        args = ["--head", "0" * 40, "--api", "37", "--family", "nodes", "--execute"]
        for key in ("parent", "state", "debug_home", "output", "emulator", "adb", "image"):
            args += ["--" + key.replace("_", "-"), str(getattr(self.config, key))]
        for options, expected in (([], None), (["--cpu-cores", "4"], 4)):
            with self.subTest(options=options), redirect_stdout(io.StringIO()), \
                    patch.object(runner, "current_binding", return_value=self.request.binding), \
                    patch.object(runner, "request_for", return_value=self.request), \
                    patch.object(runner, "execute_disposable", return_value={"acceptance": False}) as execute:
                self.assertEqual(0, runner.main([*args, *options]))
                self.assertEqual(expected, execute.call_args.args[1].cpu_cores)
        for value in ("0", "2", "3", "5", "4.0", "true", "None"):
            with self.subTest(value=value), redirect_stderr(io.StringIO()), \
                    patch.object(runner, "current_binding") as binding:
                with self.assertRaises(SystemExit) as raised:
                    runner.main([*args, "--cpu-cores", value])
                self.assertEqual(2, raised.exception.code)
                binding.assert_not_called()
        self.factory.assert_not_called()

    def test_invalid_cpu_request_fails_before_factory_or_packaging(self):
        for value in (True, False, 0, 2, 3, 5, 4.0, "4", [], {}, float("nan"), float("inf")):
            with self.subTest(value=value):
                self.config = replace(self.config, cpu_cores=value)
                with self.assertRaisesRegex(runner.PortError, "CPU selection"):
                    self.execute()
        self.factory.assert_not_called()
        self.package.assert_not_called()
        self.assertFalse(self.config.output.exists())

    def test_default_cpu_preserves_producer_default_and_actual_two_cpu_observation(self):
        self.alter_guest = lambda guest: setattr(guest, "cpus", "2")
        result = self.execute()
        self.assertIsNone(self.guest.requested_cpu_cores)
        self.assertIsNone(result["requested_cpu_cores"])
        self.assertEqual("2", result["initial_private_profile"]["cpus"])
        self.assertEqual("2", result["profile_before"]["cpus"])
        self.assertEqual(7, result["cohort"]["passed"])
        self.assert_disposed()

    def test_explicit_four_cpu_request_reaches_start_without_changing_original_nodes_selection(self):
        self.config = replace(self.config, cpu_cores=4)
        result = self.execute()
        self.assertEqual(4, self.guest.requested_cpu_cores)
        self.assertEqual(4, result["requested_cpu_cores"])
        self.assertEqual("4", result["initial_private_profile"]["cpus"])
        self.assertEqual("4", result["profile_before"]["cpus"])
        self.assertEqual(self.request.identities, result["original_identities"])
        self.assertEqual(7, result["cohort"]["passed"])
        self.assertFalse(result["acceptance"])
        self.assertEqual(180, runner.INSTRUMENTATION_TIMEOUT)
        self.assert_disposed()

    def test_requested_four_rejects_actual_two_before_profile_mutation_install_or_instrumentation(self):
        self.config = replace(self.config, cpu_cores=4)
        self.alter_guest = lambda guest: setattr(guest, "cpus", "2")
        with self.assertRaisesRegex(RuntimeError, "CPU count differs"):
            self.execute()
        result = self.operations()
        self.assertEqual(4, result["requested_cpu_cores"])
        self.assertEqual("2", result["initial_private_profile"]["cpus"])
        self.assertEqual([], result["profile_setup_attempts"])
        self.assertEqual([], result["mutation_attempts"])
        self.assertNotIn("cohort", result)
        self.assertIsNone(result["host_instrumentation_process_exit"])
        self.assertFalse(result["acceptance"])
        self.assertTrue(result["disposal_verified"])
        self.assert_disposed()

    def test_wrong_concrete_guest_or_ledger_cannot_reach_build_start_or_dispose(self):
        forged = Mock(spec=GuestModel)
        self.factory.side_effect = None
        for ledger, guest in ((self.ledger, forged), (Mock(spec=LedgerModel), forged)):
            with self.subTest(ledger=type(ledger)):
                self.factory.return_value = ledger, guest
                with self.assertRaisesRegex(runner.PortError, "actual producer PrivateGuest"):
                    self.execute()
                guest.dispose.assert_not_called()
        self.package.assert_not_called()

    def test_guest_subclass_is_not_an_admitted_actual_producer_instance(self):
        class GuestSubclass(GuestModel):
            pass

        def subclass(*args, **kwargs):
            guest = GuestSubclass(self.ledger, self.request, self.config.parent)
            return self.ledger, guest

        self.factory.side_effect = subclass
        with self.assertRaisesRegex(runner.PortError, "actual producer PrivateGuest"):
            self.execute()
        self.package.assert_not_called()
        self.assertEqual([], self.ledger.events)

    def test_foreign_owner_source_session_revision_root_and_ledger_are_rejected_before_forward_work(self):
        changes = (
            lambda g: setattr(g, "owner", "foreign"),
            lambda g: setattr(g, "revision", "0" * 40),
            lambda g: g.identity_binding.update(session_id="foreign"),
            lambda g: g.identity_binding.update(reservation_revision=0),
            lambda g: g.identity_binding.update(trusted_revision="0" * 40),
            lambda g: setattr(g, "root", self.root / "not-the-private-parent"),
        )
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                self.alter_guest = change
                with self.assertRaisesRegex(runner.PortError, "foreign/stale"):
                    self.execute()
                self.assertNotIn("dispose", self.ledger.events)
                if (self.config.parent / "model-private").is_dir():
                    (self.config.parent / "model-private").rmdir()
        self.package.assert_not_called()
        self.assertFalse(self.config.output.exists())

    def test_success_uses_exact_original_nodes_selection_and_owned_disposal_not_restoration(self):
        (self.config.image / "source.properties").write_text(
            "AndroidVersion.ApiLevel=37.0\nSystemImage.Abi=x86_64\n")
        result = self.execute()
        self.assertEqual(7, result["cohort"]["passed"])
        self.assertEqual([APP, TEST], result["mutation_attempts"])
        self.assertTrue(result["disposal_verified"])
        self.assertFalse(result["acceptance"])
        self.assertEqual(0, result["host_instrumentation_process_exit"])
        self.assertEqual("fresh-private-guest-disposal-not-shared-state-restoration", result["preservation_mode"])
        self.assertNotIn("original_profile_restored", result)
        self.assertNotIn("fixture_preferences_and_window_restoration_verified", result)
        self.assertLess(self.ledger.events.index("package"), self.ledger.events.index("start"))
        self.assertEqual("dispose", self.ledger.events[-1])
        self.assert_disposed()

    def test_all_fixed_original_families_and_language_api31_use_the_same_adapter_without_expansion(self):
        for family, api, count in (
                ("language", 31, 2), ("language", 37, 2), ("comparator", 37, 11), ("traz", 37, 2),
                ("diagnostics", 37, 5), ("nodes", 37, 7), ("los", 37, 1), ("cached-chat", 37, 1)):
            with self.subTest(family=family, api=api):
                parent = self.root / (family + str(api))
                parent.mkdir()
                self.config = replace(self.config, parent=parent, output=self.root / (family + str(api) + "-output"))
                (self.config.image / "source.properties").write_text(
                    f"AndroidVersion.ApiLevel = {api}\nSystemImage.Abi=x86_64\n")
                self.request = runner.request_for(source(family), api)
                result = self.execute()
                self.assertEqual(count, result["cohort"]["passed"])
                self.assertEqual(self.request.identities, result["original_identities"])
                self.assert_disposed()

    def test_actual_user0_and_inherited_effective_profile_guards_apply_to_the_private_device(self):
        self.alter_guest = lambda guest: setattr(guest, "current_user", "10")
        with self.assertRaisesRegex(RuntimeError, "actual owner user0"):
            self.execute()
        self.assertEqual([], self.operations()["mutation_attempts"])
        self.assert_disposed()

    def test_raw_argv_cannot_bypass_the_owned_adb_boundary(self):
        self.execute()
        self.guest.phase, self.guest.held = "running", True
        self.guest.resources = ["disposable-native-execution", "gradle-execution"]
        device = runner.PrivateDevice(self.guest, self.ledger, self.request, self.config.parent)
        with self.assertRaisesRegex(runner.PortError, "never raw subprocess"):
            device.command("shell", "am", "instrument")

    def test_packaging_descendant_guard_preserves_primary_unknown_exit_and_independent_disposal(self):
        failure = runner.PortError("Packaging descendants remain; retain Gradle and private guest ownership")

        def fail(**kwargs):
            self.packaging(**kwargs)
            self.guest.phase = "uncertain"
            raise failure

        self.package.side_effect = fail
        with self.assertRaises(runner.PortError) as caught:
            self.execute()
        self.assertIs(failure, caught.exception)
        self.assertEqual(["package", "dispose"], self.ledger.events)
        saved = self.operations()
        self.assertEqual(repr(failure), saved["execution_error"])
        self.assertIsNone(saved["host_instrumentation_process_exit"])
        self.assertNotIn("packaging", saved)
        self.assertNotIn("cohort", saved)
        for field in ("boot_observations", "profile_setup_attempts", "mutation_attempts"):
            self.assertEqual([], saved[field])
        self.assertFalse(saved["acceptance"])
        self.assertTrue(saved["disposal_verified"])
        self.assert_disposed()

    def test_wrong_packaging_guest_or_source_stops_before_boot_and_always_disposes(self):
        original = self.packaging

        def wrong(**kwargs):
            value = original(**kwargs)
            value["disposable_guest"]["token"] = "foreign"
            return value

        self.package.side_effect = wrong
        with self.assertRaisesRegex(RuntimeError, "actual fresh private guest"):
            self.execute()
        self.assertNotIn("start", self.ledger.events)
        self.assert_disposed()

    def test_mismatched_app_test_certificates_fail_before_start(self):
        original = self.packaging

        def wrong(**kwargs):
            value = original(**kwargs)
            value["tests"]["certificate_sha256"] = "0" * 64
            return value

        self.package.side_effect = wrong
        with self.assertRaisesRegex(runner.PortError, "matching public certificates"):
            self.execute()
        self.assertNotIn("start", self.ledger.events)
        self.assert_disposed()

    def test_stale_candidate_detected_after_packaging_stops_before_boot(self):
        self.verify.side_effect = [self.policy, runner.PortError("candidate changed")]
        with self.assertRaisesRegex(runner.PortError, "candidate changed"):
            self.execute()
        self.assertNotIn("start", self.ledger.events)
        self.assert_disposed()

    def test_packaging_source_mismatch_stops_before_boot(self):
        original = self.packaging

        def wrong(**kwargs):
            value = original(**kwargs)
            value["source_revision"] = "0" * 40
            return value

        self.package.side_effect = wrong
        with self.assertRaisesRegex(RuntimeError, "exact source/owner/policy"):
            self.execute()
        self.assertNotIn("start", self.ledger.events)
        self.assert_disposed()

    def test_host_zero_failed_method_proof_persists_exit_and_real_negative_counts_before_disposal(self):
        self.alter_guest = lambda guest: setattr(guest, "method_code", -2)
        parse = runner.parse_outcomes

        def observed(raw, identities):
            self.assertEqual(0, self.operations()["host_instrumentation_process_exit"])
            return parse(raw, identities)

        with patch.object(runner, "parse_outcomes", side_effect=observed):
            with self.assertRaisesRegex(RuntimeError, "all-positive"):
                self.execute()
        result = self.operations()
        self.assertEqual((0, 7), (result["host_instrumentation_process_exit"], result["cohort"]["failed"]))
        self.assertTrue(result["disposal_verified"])
        self.assertFalse(result["acceptance"])
        self.assert_disposed()

    def test_nonzero_actual_host_exit_keeps_raw_output_and_stops_before_parsing_or_further_adb(self):
        self.alter_guest = lambda guest: setattr(guest, "instrumentation_code", 7)
        with patch.object(runner, "parse_outcomes") as parsing:
            with self.assertRaises(subprocess.CalledProcessError):
                self.execute()
            parsing.assert_not_called()
        result = self.operations()
        self.assertEqual(7, result["host_instrumentation_process_exit"])
        self.assertTrue((self.config.output / "device-nodes37.txt").read_bytes())
        self.assertTrue(result["disposal_verified"])
        self.assert_disposed()

    def test_nonzero_host_failure_retains_exact_stdout_and_stderr_before_rejecting_proof(self):
        stdout, stderr = b"partial native stdout\x00\xff", b"native failure stderr\r\n\xfe"
        failure = subprocess.CalledProcessError(7, ["model-instrumentation"], output=stdout, stderr=stderr)
        self.alter_guest = lambda guest: setattr(guest, "instrumentation_error", failure)
        with patch.object(runner, "parse_outcomes") as parsing:
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                self.execute()
            parsing.assert_not_called()
        self.assertIs(failure, raised.exception)
        self.assertEqual(stdout, (self.config.output / "device-nodes37.txt").read_bytes())
        self.assertEqual(stderr, (self.config.output / "device-nodes37-stderr.txt").read_bytes())
        result = self.operations()
        self.assertEqual(7, result["host_instrumentation_process_exit"])
        self.assertNotIn("cohort", result)
        self.assertFalse(result["acceptance"])
        self.assertTrue(result["disposal_verified"])
        self.assert_disposed()

    def test_pre_instrumentation_user_probe_exit_is_not_the_host_instrumentation_exit(self):
        old_adb = GuestModel.adb
        checks = 0

        def current(request):
            nonlocal checks
            checks += 1
            return self.policy

        def guard_failure(guest, ledger, arguments, **kwargs):
            if checks == 4 and arguments == ["shell", "am", "get-current-user"]:
                guest.phase = "uncertain"
                raise subprocess.CalledProcessError(
                    9, ["model-user-proof"], output=b"not instrumentation", stderr=b"not instrumentation stderr")
            return old_adb(guest, ledger, arguments, **kwargs)

        self.verify.side_effect = current
        with patch.object(GuestModel, "adb", guard_failure):
            with self.assertRaises(subprocess.CalledProcessError):
                self.execute()
        self.assertIsNone(self.operations()["host_instrumentation_process_exit"])
        self.assertFalse((self.config.output / "device-nodes37.txt").exists())
        self.assertFalse((self.config.output / "device-nodes37-stderr.txt").exists())
        self.assert_disposed()

    def test_timeout_keeps_unknown_exit_and_still_disposes_through_the_producer(self):
        self.alter_guest = lambda guest: setattr(
            guest, "instrumentation_error", subprocess.TimeoutExpired(["model-owned-instrumentation"], 180))
        with self.assertRaises(subprocess.TimeoutExpired):
            self.execute()
        self.assertIsNone(self.operations()["host_instrumentation_process_exit"])
        self.assertTrue(self.operations()["disposal_verified"])
        self.assertFalse((self.config.output / "device-nodes37.txt").exists())
        self.assertFalse((self.config.output / "device-nodes37-stderr.txt").exists())
        self.assert_disposed()

    def test_timeout_retains_available_partial_stdout_and_stderr_with_unknown_host_exit(self):
        stdout, stderr = b"partial timeout stdout\x00\xff", b"partial timeout stderr\r\n\xfe"
        failure = subprocess.TimeoutExpired(["model-instrumentation"], 180, output=stdout, stderr=stderr)
        self.alter_guest = lambda guest: setattr(guest, "instrumentation_error", failure)
        with patch.object(runner, "parse_outcomes") as parsing:
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                self.execute()
            parsing.assert_not_called()
        self.assertIs(failure, raised.exception)
        self.assertEqual(stdout, (self.config.output / "device-nodes37.txt").read_bytes())
        self.assertEqual(stderr, (self.config.output / "device-nodes37-stderr.txt").read_bytes())
        result = self.operations()
        self.assertIsNone(result["host_instrumentation_process_exit"])
        self.assertNotIn("cohort", result)
        self.assertFalse(result["acceptance"])
        self.assertTrue(result["disposal_verified"])
        self.assert_disposed()

    def test_timeout_stderr_only_does_not_invent_stdout_or_an_exit(self):
        stderr = b"stderr without stdout\x00\xff"
        failure = subprocess.TimeoutExpired(["model-instrumentation"], 180, stderr=stderr)
        self.alter_guest = lambda guest: setattr(guest, "instrumentation_error", failure)
        with self.assertRaises(subprocess.TimeoutExpired) as raised:
            self.execute()
        self.assertIs(failure, raised.exception)
        self.assertIsNone(self.operations()["host_instrumentation_process_exit"])
        self.assertFalse((self.config.output / "device-nodes37.txt").exists())
        self.assertEqual(stderr, (self.config.output / "device-nodes37-stderr.txt").read_bytes())
        self.assertNotIn("cohort", self.operations())
        self.assert_disposed()

    def test_timeout_receipt_failure_does_not_lose_either_stream_or_primary_exception(self):
        stdout, stderr = b"partial timeout stdout", b"partial timeout stderr"
        failure = subprocess.TimeoutExpired(["model-instrumentation"], 180, output=stdout, stderr=stderr)
        self.alter_guest = lambda guest: setattr(guest, "instrumentation_error", failure)
        save = runner.save_operations

        def fail_uncertain_receipt(path, result):
            if self.guest.phase == "uncertain":
                raise OSError("model timeout receipt failed")
            save(path, result)

        with patch.object(runner, "save_operations", side_effect=fail_uncertain_receipt), \
                patch.object(runner, "parse_outcomes") as parsing:
            with self.assertRaises(BaseExceptionGroup) as raised:
                self.execute()
            parsing.assert_not_called()
        inner = raised.exception.exceptions[0]
        self.assertIsInstance(inner, BaseExceptionGroup)
        self.assertIs(failure, inner.exceptions[0])
        self.assertIn("model timeout receipt failed", str(inner.exceptions[1]))
        self.assertEqual(stdout, (self.config.output / "device-nodes37.txt").read_bytes())
        self.assertEqual(stderr, (self.config.output / "device-nodes37-stderr.txt").read_bytes())
        self.assertIsNone(self.operations()["host_instrumentation_process_exit"])
        self.assert_disposed()

    def test_nonzero_stdout_retention_failure_preserves_stderr_and_primary_exception(self):
        stdout, stderr = b"failed native stdout", b"retained native stderr"
        failure = subprocess.CalledProcessError(7, ["model-instrumentation"], output=stdout, stderr=stderr)
        self.alter_guest = lambda guest: setattr(guest, "instrumentation_error", failure)
        write_bytes = Path.write_bytes

        def fail_stdout(path, value):
            if path == self.config.output / "device-nodes37.txt":
                raise OSError("model stdout retention failed")
            return write_bytes(path, value)

        with patch.object(Path, "write_bytes", fail_stdout):
            with self.assertRaises(BaseExceptionGroup) as raised:
                self.execute()
        self.assertIs(failure, raised.exception.exceptions[0])
        self.assertIn("model stdout retention failed", str(raised.exception.exceptions[1]))
        self.assertEqual(stderr, (self.config.output / "device-nodes37-stderr.txt").read_bytes())
        self.assertEqual(7, self.operations()["host_instrumentation_process_exit"])
        self.assertNotIn("cohort", self.operations())
        self.assert_disposed()

    def test_missing_or_malformed_terminal_proof_fails_without_erasing_actual_host_exit(self):
        old_adb = GuestModel.adb

        def malformed(guest, ledger, arguments, **kwargs):
            actual = old_adb(guest, ledger, arguments, **kwargs)
            if arguments[:3] == ["shell", "am", "instrument"]:
                actual.stdout = b"INSTRUMENTATION_CODE: -1\n"
            return actual

        with patch.object(GuestModel, "adb", malformed):
            with self.assertRaises(RuntimeError):
                self.execute()
        self.assertEqual(0, self.operations()["host_instrumentation_process_exit"])
        self.assert_disposed()

    def test_host_exit_receipt_failure_keeps_in_memory_exit_and_independent_disposal(self):
        save = runner.save_operations
        observed = []

        def fail_exit(path, result):
            if result["host_instrumentation_process_exit"] is not None:
                observed.append(result["host_instrumentation_process_exit"])
                raise OSError("model host exit write failed")
            save(path, result)

        with patch.object(runner, "save_operations", side_effect=fail_exit), \
                patch.object(runner, "parse_outcomes") as parsing:
            with self.assertRaises(BaseExceptionGroup):
                self.execute()
            parsing.assert_not_called()
        self.assertEqual([0, 0, 0], observed)
        self.assert_disposed()

    def test_genuine_freshness_rejection_precedes_profile_or_install_mutations(self):
        self.alter_guest = lambda guest: guest.packages.update({APP: "0" * 64})
        with self.assertRaisesRegex(RuntimeError, "not genuinely fresh"):
            self.execute()
        result = self.operations()
        self.assertEqual([], result["mutation_attempts"])
        self.assertEqual([], result["profile_setup_attempts"])
        self.assert_disposed()

    def test_wrong_canonical_ledger_is_not_cleaned_up_or_used(self):
        original = self.allocate

        def foreign(*args, **kwargs):
            ledger, guest = original(*args, **kwargs)
            ledger.path = self.root / "foreign.sqlite"
            return ledger, guest

        self.factory.side_effect = foreign
        with self.assertRaisesRegex(runner.PortError, "foreign/stale"):
            self.execute()
        self.package.assert_not_called()
        self.assertNotIn("dispose", self.ledger.events)

    def test_removed_held_receipt_stops_before_build_but_independently_attempts_disposal(self):
        self.alter_guest = lambda guest: setattr(guest, "held", False)
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.package.assert_not_called()
        self.assertIn("dispose", self.ledger.events)

    def test_install_failure_does_not_skip_disposal_or_claim_original_state_recovered(self):
        self.alter_guest = lambda guest: setattr(
            guest, "install_error", subprocess.CalledProcessError(1, ["model-owned-install"]))
        with self.assertRaises(subprocess.CalledProcessError):
            self.execute()
        self.assertEqual([APP], self.operations()["mutation_attempts"])
        self.assert_disposed()

    def test_receipt_failures_cannot_prevent_independent_owned_disposal(self):
        with patch.object(runner, "save_operations", side_effect=OSError("model disk full")):
            with self.assertRaises(BaseExceptionGroup) as raised:
                self.execute()
        self.assertIn("model disk full", str(raised.exception.exceptions))
        self.assert_disposed()
        self.package.assert_not_called()

    def test_disposal_failure_is_explicit_and_cannot_report_released_or_success(self):
        self.alter_guest = lambda guest: setattr(guest, "dispose_error", runner.PortError("model cleanup unproven"))
        with self.assertRaises(BaseExceptionGroup):
            self.execute()
        self.assertFalse(self.operations()["disposal_verified"])
        self.assertIn("model cleanup unproven", self.operations()["disposal_error"])
        self.assertTrue(self.guest.held)
        self.assertEqual(["disposable-native-execution", "gradle-execution"], self.guest.resources)

    def test_primary_host_failure_and_cleanup_failure_are_both_retained(self):
        def fail(guest):
            guest.instrumentation_code = 7
            guest.dispose_error = runner.PortError("model disposal unknown")

        self.alter_guest = fail
        with self.assertRaises(BaseExceptionGroup) as raised:
            self.execute()
        self.assertIsInstance(raised.exception.exceptions[0], subprocess.CalledProcessError)
        self.assertEqual(7, self.operations()["host_instrumentation_process_exit"])
        self.assertIn("model disposal unknown", str(raised.exception.exceptions))
        self.assertTrue(self.guest.held)

    def test_host_failure_and_receipt_failure_both_survive_and_raw_bytes_are_retained(self):
        self.alter_guest = lambda guest: setattr(guest, "instrumentation_code", 7)
        save = runner.save_operations

        def fail_exit(path, result):
            if result["host_instrumentation_process_exit"] is not None:
                raise OSError("model host receipt failed")
            save(path, result)

        with patch.object(runner, "save_operations", side_effect=fail_exit):
            with self.assertRaises(BaseExceptionGroup) as raised:
                self.execute()
        inner = raised.exception.exceptions[0]
        self.assertIsInstance(inner, BaseExceptionGroup)
        self.assertIsInstance(inner.exceptions[0], subprocess.CalledProcessError)
        self.assertEqual(7, inner.exceptions[0].returncode)
        self.assertEqual(transcript(family="nodes").encode(),
                         (self.config.output / "device-nodes37.txt").read_bytes())
        self.assert_disposed()

    def test_cancellation_preserves_primary_signal_and_disposes(self):
        self.package.side_effect = KeyboardInterrupt("model cancellation")
        with self.assertRaises(KeyboardInterrupt):
            self.execute()
        self.assert_disposed()

    def test_boot_deadline_and_unrecognized_observation_stop_before_installation(self):
        self.alter_guest = lambda guest: setattr(guest, "boot", "not-a-boot-state")
        with self.assertRaisesRegex(RuntimeError, "Unrecognized private boot"):
            self.execute()
        self.assertEqual([], self.operations()["mutation_attempts"])
        self.assert_disposed()

    def test_transport_registration_precedes_framework_probes_with_the_same_boot_deadline(self):
        guest, ledger, observed = Mock(), object(), []
        guest.boot_readiness.return_value = nullcontext()
        guest.adb.side_effect = [
            subprocess.CompletedProcess(["wait-for-device"], 0, stdout=b"", stderr=b""),
            subprocess.CompletedProcess(["getprop"], 0, stdout=b"1", stderr=b""),
            subprocess.CompletedProcess(["getprop"], 0, stdout=b"stopped", stderr=b""),
        ]
        with patch.object(runner.time, "monotonic", side_effect=[10, 11, 115, 116, 117]):
            runner.wait_boot(guest, ledger, observed.append, offline_error=BootOfflineModel)
        self.assertEqual([
            call(ledger, ["wait-for-device"], timeout=119),
            call(ledger, ["shell", "getprop", "sys.boot_completed"], timeout=15),
            call(ledger, ["shell", "getprop", "init.svc.bootanim"], timeout=14),
        ], guest.adb.call_args_list)
        self.assertEqual([{"sys.boot_completed": "1", "init.svc.bootanim": "stopped"}], observed)

    def test_scoped_offline_retains_actual_streams_and_requires_fresh_both_properties(self):
        for output, stderr in (
            (b"adb.exe: device offline\r\n", None),
            (b"adb.exe: device offline\n", None),
            (b"", b"adb: device offline\n"),
        ):
            with self.subTest(output=output, stderr=stderr):
                guest, ledger, observed = Mock(), object(), []
                guest.boot_readiness.return_value = nullcontext()
                completed = lambda value: subprocess.CompletedProcess(["model"], 0, stdout=value, stderr=b"")
                failure = BootOfflineModel(1, ["model-getprop"], output=output, stderr=stderr)
                guest.adb.side_effect = [
                    completed(b""), completed(b"1\n"), failure,
                    completed(b"0\n"), completed(b"stopped\n"),
                    completed(b"1\n"), completed(b"stopped\n"),
                ]
                guest.reattach_boot.return_value = completed(b"")
                with patch.object(runner.time, "monotonic", side_effect=[10, 11, 12, 13, 14, 115, 116, 117, 118, 119, 120]), \
                        patch.object(runner.time, "sleep") as sleeping:
                    runner.wait_boot(guest, ledger, observed.append, offline_error=BootOfflineModel)
                guest.boot_readiness.assert_called_once_with(ledger, deadline=130)
                guest.reattach_boot.assert_called_once_with(ledger, timeout=116)
                self.assertEqual([
                    call(ledger, ["wait-for-device"], timeout=119),
                    call(ledger, ["shell", "getprop", "sys.boot_completed"], timeout=30),
                    call(ledger, ["shell", "getprop", "init.svc.bootanim"], timeout=30),
                    call(ledger, ["shell", "getprop", "sys.boot_completed"], timeout=15),
                    call(ledger, ["shell", "getprop", "init.svc.bootanim"], timeout=14),
                    call(ledger, ["shell", "getprop", "sys.boot_completed"], timeout=12),
                    call(ledger, ["shell", "getprop", "init.svc.bootanim"], timeout=11),
                ], guest.adb.call_args_list)
                self.assertEqual({
                    "failure": "BootReadinessOffline", "property": "init.svc.bootanim", "returncode": 1,
                    "partial_properties": {"sys.boot_completed": "1"},
                    "output": output.decode(), "stderr": None if stderr is None else stderr.decode(),
                }, observed[0])
                self.assertEqual([
                    {"sys.boot_completed": "0", "init.svc.bootanim": "stopped"},
                    {"sys.boot_completed": "1", "init.svc.bootanim": "stopped"},
                ], observed[1:])
                sleeping.assert_called_once_with(0.1)

    def test_repeated_offline_exhausts_original_deadline_before_profile_install_or_instrumentation(self):
        original_adb, clock = GuestModel.adb, [0]

        def offline(guest, ledger, arguments, **kwargs):
            if arguments == ["shell", "getprop", "init.svc.bootanim"]:
                ledger.events.append(tuple(arguments))
                clock[0] = 40 if clock[0] == 0 else 120
                raise BootOfflineModel(1, arguments, output=b"adb.exe: device offline\n")
            return original_adb(guest, ledger, arguments, **kwargs)

        with patch.object(GuestModel, "adb", offline), patch.object(runner.time, "monotonic", side_effect=lambda: clock[0]):
            with self.assertRaisesRegex(RuntimeError, "120s deadline"):
                self.execute()
        result = self.operations()
        self.assertEqual(2, len(result["boot_observations"]))
        self.assertTrue(all(value["failure"] == "BootReadinessOffline" for value in result["boot_observations"]))
        self.assertEqual(1, self.ledger.events.count("reattach"))
        self.assertEqual([], result["profile_setup_attempts"])
        self.assertEqual([], result["mutation_attempts"])
        self.assertNotIn("initial_private_profile", result)
        self.assertNotIn("cohort", result)
        self.assertIsNone(result["host_instrumentation_process_exit"])
        self.assertTrue(result["disposal_verified"])
        self.assert_disposed()

    def test_unclassified_boot_failures_and_malformed_offline_bytes_are_not_reattached(self):
        original_adb = GuestModel.adb
        for index, failure in enumerate((
            subprocess.CalledProcessError(1, ["model"], output=b"adb.exe: device offline\n"),
            subprocess.CalledProcessError(1, ["model"], output=b"unknown failure"),
            subprocess.TimeoutExpired(["model"], 30),
            OSError("model read failure"),
            BootOfflineModel(1, ["model"], output=b"\xff"),
            BootOfflineModel(1, ["model"], output="not bytes"),
        )):
            with self.subTest(failure=repr(failure)):
                parent = self.root / ("boot-error-" + str(index))
                parent.mkdir()
                self.config = replace(self.config, parent=parent, output=self.root / ("boot-error-output-" + str(index)))
                self.ledger.events.clear()

                def fail_property(guest, ledger, arguments, **kwargs):
                    if arguments == ["shell", "getprop", "init.svc.bootanim"]:
                        raise failure
                    return original_adb(guest, ledger, arguments, **kwargs)

                expected = UnicodeDecodeError if index == 4 else RuntimeError if index == 5 else type(failure)
                with patch.object(GuestModel, "adb", fail_property):
                    with self.assertRaises(expected):
                        self.execute()
                result = self.operations()
                self.assertNotIn("reattach", self.ledger.events)
                self.assertEqual([], result["profile_setup_attempts"])
                self.assertEqual([], result["mutation_attempts"])
                self.assertNotIn("initial_private_profile", result)
                self.assertNotIn("cohort", result)
                self.assertIsNone(result["host_instrumentation_process_exit"])
                self.assertTrue(result["disposal_verified"])
                self.assert_disposed()

    def test_offline_observation_write_failure_prevents_reattachment_and_independently_disposes(self):
        original_adb, original_save, written = GuestModel.adb, runner.save_operations, []

        def fail_property(guest, ledger, arguments, **kwargs):
            if arguments == ["shell", "getprop", "sys.boot_completed"]:
                raise BootOfflineModel(1, arguments, output=b"adb.exe: device offline\n")
            return original_adb(guest, ledger, arguments, **kwargs)

        def fail_once(path, result):
            if result["boot_observations"] and not written:
                written.append(True)
                raise OSError("model offline evidence write failure")
            original_save(path, result)

        with patch.object(GuestModel, "adb", fail_property), patch.object(runner, "save_operations", side_effect=fail_once):
            with self.assertRaisesRegex(OSError, "offline evidence"):
                self.execute()
        self.assertNotIn("reattach", self.ledger.events)
        self.assertEqual({}, self.operations()["boot_observations"][0]["partial_properties"])
        self.assertTrue(self.operations()["disposal_verified"])
        self.assert_disposed()

    def test_reattachment_errors_and_malformed_results_remain_failures(self):
        for result in (
            subprocess.CalledProcessError(1, ["model-reattach"], output=b"offline again"),
            subprocess.TimeoutExpired(["model-reattach"], 10),
            subprocess.CompletedProcess(["model-reattach"], 1, stdout=b"failed"),
            subprocess.CompletedProcess(["model-reattach"], 0, stdout="not bytes"),
        ):
            with self.subTest(result=repr(result)):
                guest, ledger, observed = Mock(), object(), []
                guest.boot_readiness.return_value = nullcontext()
                guest.adb.side_effect = [
                    subprocess.CompletedProcess(["model"], 0, stdout=b""),
                    BootOfflineModel(1, ["model-getprop"], output=b"adb.exe: device offline\n"),
                ]
                if isinstance(result, BaseException):
                    guest.reattach_boot.side_effect = result
                else:
                    guest.reattach_boot.return_value = result
                expected = type(result) if isinstance(result, BaseException) else (
                    subprocess.CalledProcessError if result.returncode else RuntimeError)
                with self.assertRaises(expected):
                    runner.wait_boot(guest, ledger, observed.append, offline_error=BootOfflineModel)
                self.assertEqual(2, guest.adb.call_count)
                self.assertEqual(1, len(observed))
                self.assertEqual("BootReadinessOffline", observed[0]["failure"])

    def test_invalid_first_property_is_not_hidden_by_a_later_offline_diagnostic(self):
        for value, expected in ((b"unrecognized", RuntimeError), (b"\xff", UnicodeDecodeError)):
            with self.subTest(value=value):
                guest, ledger, observed = Mock(), object(), []
                guest.boot_readiness.return_value = nullcontext()
                guest.adb.side_effect = [
                    subprocess.CompletedProcess(["model"], 0, stdout=b""),
                    subprocess.CompletedProcess(["model"], 0, stdout=value),
                    BootOfflineModel(1, ["model-getprop"], output=b"adb.exe: device offline\n"),
                ]
                with self.assertRaises(expected):
                    runner.wait_boot(guest, ledger, observed.append, offline_error=BootOfflineModel)
                self.assertEqual(2, guest.adb.call_count)
                guest.reattach_boot.assert_not_called()
                self.assertEqual([], observed)

    def test_transport_wait_failures_keep_unknown_instrumentation_exit_and_independent_disposal(self):
        old_adb = GuestModel.adb
        for index, failure in enumerate((
                subprocess.CalledProcessError(1, ["model-wait-for-device"], output=b"not registered"),
                subprocess.TimeoutExpired(["model-wait-for-device"], 120, output=b"partial transport"))):
            with self.subTest(failure=type(failure).__name__):
                parent = self.root / ("transport-failure-" + str(index))
                parent.mkdir()
                self.config = replace(self.config, parent=parent, output=self.root / ("transport-output-" + str(index)))
                self.ledger.events.clear()

                def fail_wait(guest, ledger, arguments, **kwargs):
                    if arguments == ["wait-for-device"]:
                        self.assertGreater(kwargs["timeout"], 0)
                        self.assertLessEqual(kwargs["timeout"], 120)
                        ledger.events.append(tuple(arguments))
                        guest.phase = "uncertain"
                        raise failure
                    return old_adb(guest, ledger, arguments, **kwargs)

                with patch.object(GuestModel, "adb", fail_wait):
                    with self.assertRaises(type(failure)) as raised:
                        self.execute()
                self.assertIs(failure, raised.exception)
                result = self.operations()
                self.assertEqual(["package", "start", ("wait-for-device",), "dispose"], self.ledger.events)
                self.assertEqual([], result["boot_observations"])
                self.assertEqual([], result["profile_setup_attempts"])
                self.assertEqual([], result["mutation_attempts"])
                self.assertIsNone(result["host_instrumentation_process_exit"])
                self.assertNotIn("cohort", result)
                self.assertFalse(result["acceptance"])
                self.assertTrue(result["disposal_verified"])
                self.assertFalse((self.config.output / "device-nodes37.txt").exists())
                self.assertFalse((self.config.output / "device-nodes37-stderr.txt").exists())
                self.assert_disposed()

    def test_boot_deadline_is_bounded_without_sleep_or_retry_after_expiry(self):
        with patch.object(runner.time, "monotonic", side_effect=[0, 121]), \
                patch.object(runner.time, "sleep") as sleeping:
            with self.assertRaisesRegex(RuntimeError, "120s deadline"):
                self.execute()
            sleeping.assert_not_called()
        self.assertEqual([], self.operations()["mutation_attempts"])
        self.assert_disposed()


if __name__ == "__main__":
    unittest.main()
