"""AndroidOnly: WP-003 Win32 API fixtures only; no real Job/process/emulator is created."""

from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from controller.windows_guest import WindowsJob


class WindowsGuestBoundaryTests(unittest.TestCase):
    def job_fixture(self, *, assign=True):
        job = object.__new__(WindowsJob)
        job.kernel, job.handle, job.processes = Mock(), 90, []

        def check(result):
            if not result:
                raise OSError("fixture API refusal")

        job.check = check
        job.kernel.GetCurrentProcess.return_value = 10

        def attributes(_, __, ___, size):
            size._obj.value = 256
            return 1

        def duplicate(_, source, __, target, ___, ____, _____):
            target._obj.value = source + 100
            return 1

        def create(*arguments):
            info = arguments[-1]._obj
            info.process, info.thread, info.pid, info.tid = 20, 21, 22, 23
            return 1

        def code(_, target):
            target._obj.value = 125
            return 1

        job.kernel.InitializeProcThreadAttributeList.side_effect = attributes
        job.kernel.DuplicateHandle.side_effect = duplicate
        job.kernel.UpdateProcThreadAttribute.return_value = 1
        job.kernel.CreateProcessW.side_effect = create
        job.kernel.AssignProcessToJobObject.return_value = int(assign)
        job.kernel.ResumeThread.return_value = 1
        job.kernel.TerminateProcess.return_value = 1
        job.kernel.WaitForSingleObject.return_value = 0
        job.kernel.GetExitCodeProcess.side_effect = code
        return job

    def spawn(self, job):
        with tempfile.TemporaryDirectory() as directory:
            with (Path(directory) / "owned.log").open("wb") as log, \
                    patch.dict(sys.modules, {"msvcrt": SimpleNamespace(get_osfhandle=lambda _: 50)}):
                return job.spawn(["fixture.exe", "argument with spaces"], {"PATH": "fixture"}, log, cwd=directory)

    def test_child_is_suspended_and_assigned_before_resume_with_only_allowlisted_handles(self):
        job = self.job_fixture()
        process = self.spawn(job)
        names = [call[0] for call in job.kernel.mock_calls]
        self.assertLess(names.index("CreateProcessW"), names.index("AssignProcessToJobObject"))
        self.assertLess(names.index("AssignProcessToJobObject"), names.index("ResumeThread"))
        create = job.kernel.CreateProcessW.call_args.args
        self.assertTrue(create[4])
        self.assertEqual(create[5] & 0x4, 0x4)
        self.assertEqual(create[5] & 0x80000, 0x80000)
        self.assertEqual(job.kernel.UpdateProcThreadAttribute.call_args.args[2], 0x20002)
        job.kernel.AssignProcessToJobObject.assert_called_once_with(90, 20)
        job.kernel.ResumeThread.assert_called_once_with(21)
        self.assertEqual((process.handle, process.pid), (20, 22))
        self.assertEqual(job.processes, [process])

    def test_assignment_failure_never_resumes_or_adopts_and_terminates_only_exact_suspended_child(self):
        job = self.job_fixture(assign=False)
        with self.assertRaisesRegex(OSError, "fixture API refusal"):
            self.spawn(job)
        job.kernel.ResumeThread.assert_not_called()
        job.kernel.TerminateProcess.assert_called_once_with(20, 125)
        job.kernel.TerminateJobObject.assert_not_called()
        self.assertEqual(job.processes[0].poll(), 125)

    def test_failed_creation_has_no_resume_or_assumed_owned_child(self):
        job = self.job_fixture()
        job.kernel.CreateProcessW.side_effect = None
        job.kernel.CreateProcessW.return_value = 0
        with self.assertRaises(OSError):
            self.spawn(job)
        job.kernel.ResumeThread.assert_not_called()
        job.kernel.AssignProcessToJobObject.assert_not_called()
        self.assertEqual(job.processes, [])

    def test_cleanup_observes_job_zero_and_exited_processes_not_merely_termination_return(self):
        job = self.job_fixture()
        job.kernel.TerminateJobObject.return_value = 1
        process = Mock()
        process.poll.return_value = 125
        job.processes = [process]
        job.active = Mock(side_effect=[1, 0])
        with patch("controller.windows_guest.time.sleep"):
            job.cleanup()
        job.kernel.TerminateJobObject.assert_called_once_with(90, 125)
        self.assertEqual(job.active.call_count, 2)
        process.poll.assert_called_once()

    def test_unknown_cleanup_retains_job_handles_and_never_calls_foreign_process_termination(self):
        job = self.job_fixture()
        job.kernel.TerminateJobObject.return_value = 1
        job.active = Mock(return_value=1)
        with patch("controller.windows_guest.time.monotonic", side_effect=[0, 0, 5]), \
                patch("controller.windows_guest.time.sleep"):
            from controller.errors import PortError

            with self.assertRaisesRegex(PortError, "cleanup unproven"):
                job.cleanup()
        job.kernel.CloseHandle.assert_not_called()
        job.kernel.TerminateProcess.assert_not_called()
