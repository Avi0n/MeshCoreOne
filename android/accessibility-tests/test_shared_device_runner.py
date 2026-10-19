"""AndroidOnly: WP-503 Fail-closed discovery for the missing-only shared-device adapter."""
import unittest

from shared_device_runner import parse_outcomes, selected_methods


def record(identity, status, count=1):
    klass, method = identity.split("#")
    return (f"INSTRUMENTATION_STATUS: class={klass}\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS: numtests={count}\n"
            f"INSTRUMENTATION_STATUS_CODE: {status}\n")


class SharedDeviceDiscoveryTest(unittest.TestCase):
    def test_exact_missing_selector_families(self):
        from shared_device_runner import COHORTS
        self.assertEqual([11, 2, 5, 7, 1, 1], [len(selected_methods(name)) for name in COHORTS])

    def test_positive_matching_discovery(self):
        identity = "example.Test#original"
        result = parse_outcomes(record(identity, 1) + record(identity, 0), [identity])
        self.assertEqual((1, 1, 1, 0, 0, 0), tuple(result[k] for k in
                         ("discovered", "started", "passed", "failed", "errors", "skipped")))

    def test_zero_duplicate_wrong_missing_and_unknown_outcomes_fail(self):
        identity = "example.Test#original"
        cases = ("", record(identity, 1), record(identity, 1) * 2 + record(identity, 0),
                 record(identity, 1) + record("example.Test#other", 0),
                 record(identity, 1) + record(identity, 99))
        for text in cases:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                parse_outcomes(text, [identity])

    def test_failure_error_and_skip_are_not_passes(self):
        for code, field in ((-2, "failed"), (-1, "errors"), (-3, "skipped"), (-4, "skipped")):
            identity = "example.Test#original"
            result = parse_outcomes(record(identity, 1) + record(identity, code), [identity])
            self.assertEqual(0, result["passed"])
            self.assertEqual(1, result[field])


if __name__ == "__main__":
    unittest.main()
