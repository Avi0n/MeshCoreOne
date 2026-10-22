"""AndroidOnly: WP-003 Complete, nonzero scaffold reports and exact-run aggregation."""

import csv
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

from .ci_environment import file_sha256
from .apk_alignment import elf_load_alignment
from .errors import PortError
from .schema import fields

SUITES = {
    "build-logic": ("build-logic/convention/build/test-results/test", 31),
    "contracts": ("core/contracts/build/test-results/test", 4),
    "app": ("app/build/test-results/testDebugUnitTest", 10),
    "room-verification": ("scaffold/room-verification/build/test-results/testDebugUnitTest", 2),
}
LINT_TARGETS = (
    "app", "benchmark", "scaffold/room-verification",
    *("core/" + name for name in (
        "database", "datastore", "data", "ble", "connectivity",
        "designsystem", "ui", "maps", "l10n", "testing",
    )),
    *("feature/" + name for name in ("onboarding", "chats", "nodes", "remotenodes", "map", "tools", "settings")),
    *("platform/" + name for name in ("notifications", "widgets", "shortcuts", "translation")),
)
PYTHON_MINIMUMS = {"scaffold": 15}
WP501_ACCEPTANCE_CASES = {
    ("com.meshcoreone.android.parity.MessageBackupAcceptanceTest", name)
    for name in (
        "cjkOriginalTextSurvivesBackupAndMountedTimeline",
        "rtlOriginalTextSurvivesBackupAndMountedTimeline",
        "zwjOriginalTextSurvivesBackupAndMountedTimeline",
        "decomposedOriginalTextSurvivesBackupAndMountedTimeline",
        "composedOriginalTextSurvivesBackupAndMountedTimeline",
        "whitespaceAndQuoteOriginalTextSurvivesBackupAndMountedTimeline",
        "swiftProducedFixtureReachesTheOfflineTimelineWithOriginalBytes",
        "corruptedManifestCannotMutateTheProcessStore",
        "cancelledCodecClosesInputAndDoesNotBecomeInvalidFileSuccess",
        "redactedRadioDefaultsUsePinnedValuesRatherThanTheCandidateAsOracle",
    )
} | {
    ("com.meshcoreone.android.parity.ContentCancellationAcceptanceTest",
     "callerTimeoutRemainsCancellationAndDoesNotPoisonInlineRetry"),
    ("com.meshcoreone.android.parity.DeviceRepeatAcceptanceTest",
     "sourcePreRepeatParametersSurviveSelfInfoWithoutMutatingTheOriginal"),
    ("com.meshcoreone.android.parity.DiscoveryStoreAcceptanceTest",
     "deletingDiscoveredNodeUpdatesRealRoomAndMountedFilterWithoutTouchingAnotherRadio"),
    ("com.meshcoreone.android.parity.ContactShareAcceptanceTest",
     "originalContactShareFieldsRoundTripWithoutWhitespace"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "fullExportRestoresDistinctEmptyRoomAndOfflineConsumer"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "channelRepeatExportRestoresFreshParentAndCascadeWithoutDeletingSource"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "original32ByteSecretAndFloodScopesSurviveDistinctStoreExportImport"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "original32ByteStaleSecretCannotOverwriteReconfiguredLiveChannel"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "original32ByteDraftSlotsReportOnlyFreshAndRelocatedOccupancy"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "deviceOnlyDeleteExportsAllSevenOrphanKindsIntoDistinctEmptyStore"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "originalDiscoveredFrameAndTargetedHopWriterRestoreIntoDistinctEmptyRoom"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "originalRegionAndOrderedMatchesUseLiveWritersAndDistinctDestinationStores"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "originalSavedMessageMetadataSurvivesDistinctRoomExportImport"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "originalSamePathHearingsKeepDistinctIdsAcrossFreshRestoreAndMerge"),
    ("com.meshcoreone.android.parity.FreshStoreBackupAcceptanceTest",
     "originalIntentionalSendsKeepBothActionIdsInFreshRoomAndOfflineTimeline"),
    ("com.meshcoreone.android.parity.ApplicationBytesAcceptanceTest",
     "originalApplicationHexFamiliesUseProductionFormattingAndParsing"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalCommandsWaitForDelayedAcknowledgement"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalDeviceErrorsRemainTypedAfterDelayedResponse"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "noResponseRetainsSourceTenSecondTimeout"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalWindowHoldsForFiftyMillisecondsAndRefillsWithoutMissingSlots"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalCancelledPipelineKeepsTheSuccessorsIndexAndFreshName"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalChannelOrderingMissingAndSerialFamiliesKeepTheirExactInputs"),
    ("com.meshcoreone.android.parity.ContactPacketAcceptanceTest",
     "originalContactPacketFamiliesKeepTheirDistinctInputs"),
    ("com.meshcoreone.android.parity.ContactPacketAcceptanceTest",
     "originalPublicFlagsSeamSaturatesAndWaitsForAcknowledgement"),
    ("com.meshcoreone.android.parity.ProtocolRoundTripAcceptanceTest",
     "originalBoundaryFramesRetainExactSourceInputs"),
    ("com.meshcoreone.android.parity.MessageKeyAcceptanceTest",
     "originalPrefixParameterFamilyRetainsAllFourRows"),
    ("com.meshcoreone.android.parity.EventDeliveryAcceptanceTest",
     "publicSessionFiltersBeforeCollectionAndEndsOnStop"),
    ("com.meshcoreone.android.parity.EventDeliveryAcceptanceTest",
     "publicSessionRetainsAcknowledgementAfterFiveHundredNonmatchingEvents"),
    ("com.meshcoreone.android.parity.EventDeliveryAcceptanceTest",
     "originalLoginFiltersRetainCompleteSixBytePrefixes"),
    ("com.meshcoreone.android.parity.EventDeliveryAcceptanceTest",
     "originalErrorFrameReachesTheTypedFirmwareAccessor"),
    ("com.meshcoreone.android.parity.WiFiConnectionAcceptanceTest",
     "originalTargetAndPendingDelayCancelWithinThreeSecondsWithoutExternalNetwork"),
    ("com.meshcoreone.android.parity.WiFiConnectionAcceptanceTest",
     "originalFirstAcceptCompletesWithinTwoSecondsWithoutDuplicateConnection"),
    ("com.meshcoreone.android.parity.WiFiConnectionAcceptanceTest",
     "originalPeerCloseAcceptHandlerAndStreamEachCompleteWithinTwoSeconds"),
    ("com.meshcoreone.android.parity.SessionLifecycleAcceptanceTest",
     "originalStartupStatesRetainTheExactHandshakeInputs"),
    ("com.meshcoreone.android.parity.SessionLifecycleAcceptanceTest",
     "originalFiftyMillisecondFailureKeepsIntermediateStatesAndWritesAgain"),
    ("com.meshcoreone.android.parity.SessionLifecycleAcceptanceTest",
     "originalInvalidInputsDoNotWriteOrConnect"),
    ("com.meshcoreone.android.parity.SessionLifecycleAcceptanceTest",
     "originalUnstartedStopRetainsTheDisconnectOwnershipFlag"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalMessagePollWaitsFiftyMillisecondsBehindTheCommandAndItsError"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalDatagramFrameReachesThePublicMessageResultAfterItsWrite"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalAutomaticPollsRetainTheirSelfInfoAndClearStartupHistory"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalCoalescedOutcomesKeepFollowerWaitsAndCancellationBudget"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalTimeoutOverridesUseRealDeadlinesAndSourceInputs"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalContactInvalidationsRetainSourceSelfInfoHistoryAndFiftyMillisecondObservation"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalConcurrentSettingsWaitForEachFreshSelfInfoBeforeTheNextWrite"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalStartupIgnoresErrorNinetyNineBeforeItsExactSelfInfo"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalSimpleCorrelationKeepsFiftyMillisecondWaitPayloadSevenAndErrorFortyTwo"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalResponsePredicatesRetainTheirKeysIndexesAndLiteralPackets"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalContactStreamsKeepCadenceDeclaredTotalsAndInactivityInputs"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalPrivateKeyImportRetainsExpandedLengthPayloadTimeoutAndFreshIdentity"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalDisabledAndPrivateKeyErrorEightKeepTheirRequestSpecificOutcomes"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalBinaryPreTagErrorsAndDedicatedRoomStatusUseExactSourceFrames"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalBinaryCadenceLatestTagAndOverallBudgetKeepSourceIntervals"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalTextAndKeepAliveErrorsRetainHelloKeysAndTypedCodes"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalCrossRequestOwnershipKeepsFiftyMillisecondsAndDistinctErrorsAndTags"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalCancelledOwnershipDrainsBatteryAndNeverWritesParkedAdvertisement"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalRegionRequestsKeepSourceHandshakeTagsRoutesAndTypedFailures"),
    ("com.meshcoreone.android.parity.SessionResponseAcceptanceTest",
     "originalStatusErrorAndUnstartedShortNeighbourKeyKeepSourceInputs"),
    ("com.meshcoreone.android.parity.BlePacingAcceptanceTest",
     "configuredEsp32LinkPacesAcknowledgedWritesForSixtyMilliseconds"),
    ("com.meshcoreone.android.parity.BlePacingAcceptanceTest",
     "configuredNrf52LinkPacesAcknowledgedWritesForTwentyFiveMilliseconds"),
    ("com.meshcoreone.android.parity.BlePacingAcceptanceTest",
     "configuredUnknownBluetoothLinkKeepsConservativeSixtyMillisecondPacing"),
    ("com.meshcoreone.android.parity.IncomingReactionAcceptanceTest",
     "mountedPocketChannelReactionSurvivesArrivalBeforeItsTarget"),
    ("com.meshcoreone.android.parity.IncomingReactionAcceptanceTest",
     "mountedPocketDirectReactionSurvivesArrivalBeforeItsTarget"),
    ("com.meshcoreone.android.parity.IncomingReactionAcceptanceTest",
     "mountedMeshCoreOpenV3ChannelReactionReachesTheActualStoredTarget"),
    ("com.meshcoreone.android.parity.IncomingReactionAcceptanceTest",
     "mountedMeshCoreOpenV3DirectReactionReachesTheActualStoredTarget"),
    ("com.meshcoreone.android.parity.IncomingReactionAcceptanceTest",
     "mountedMeshCoreOpenV1ChannelReactionReachesTheActualStoredTarget"),
    ("com.meshcoreone.android.parity.IncomingReactionAcceptanceTest",
     "mountedMeshCoreOpenV1DirectReactionReachesTheActualStoredTarget"),
    ("com.meshcoreone.android.parity.HeardRepeatStoreAcceptanceTest",
     "exactTextDisambiguatesStoredOutgoingRowsWithTheSameChannelAndTimestamp"),
    ("com.meshcoreone.android.parity.CliCompletionUnicodeAcceptanceTest",
     "learnedNodePrefixRetainsTheIndependentSwiftCanonicalMatch"),
    ("com.meshcoreone.android.parity.CliCompletionUnicodeAcceptanceTest",
     "learnedCustomKeyPrefixRetainsTheIndependentSwiftCanonicalMatch"),
    ("com.meshcoreone.android.parity.CliCompletionUnicodeAcceptanceTest",
     "actualTerminalGhostRetainsTheCanonicalPrefixAndOriginalInput"),
    ("com.meshcoreone.android.parity.CliCompletionUnicodeAcceptanceTest",
     "actualTerminalTabAppliesTheVerbatimCanonicalPrefixMatch"),
    ("com.meshcoreone.android.parity.ConversationDeletionAcceptanceTest",
     "actualAppDeletionRecomputesRecencyFromRemainingDatabaseHistory"),
    ("com.meshcoreone.android.parity.ConversationDeletionAcceptanceTest",
     "actualAppDeletionClearsTheLastMessageDateAndRemovesTheEmptyConversation"),
}
APK_FIELDS = {
    "scope", "artifact", "sha256", "size_bytes", "package", "min_sdk", "target_sdk",
    "permissions", "pinned_notices", "verification_fixture_packaged", "native_libraries",
    "native_16kb_compatibility_verified", "static_16kb_alignment_verified",
    "elf_pt_load_alignment", "physical_device_or_native_runtime_verified",
}


def read_xml(path: Path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 8 * 1024 * 1024:
        raise PortError(f"Missing/unsafe/oversized mandatory XML report: {path.name}")
    data = path.read_bytes()
    if len(data) > 8 * 1024 * 1024 or b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise PortError("Oversized/unsafe evidence XML")
    try:
        return ET.fromstring(data)
    except ET.ParseError as error:
        raise PortError(f"Malformed evidence XML: {path.name}") from error


def counts(value: dict, *, minimum=1):
    fields(value, {"discovered", "run", "passed", "failed", "errors", "skipped"}, label="test discovery")
    if any(type(number) is not int or number < 0 for number in value.values()):
        raise PortError("Malformed test-discovery counts")
    if (
        value["discovered"] < minimum or value["run"] != value["discovered"]
        or value["passed"] != value["discovered"]
        or value["failed"] or value["errors"] or value["skipped"]
    ):
        raise PortError("Missing, zero, reduced, failed or skipped mandatory assertions")
    return value


def suite_counts(directory: Path, minimum: int):
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        raise PortError(f"Missing mandatory test reports: {directory}")
    total = 0
    identities = set()
    for path in files:
        suite = read_xml(path)
        if suite.tag != "testsuite":
            raise PortError("Malformed JUnit suite")
        actual = suite.findall("testcase")
        try:
            declared = {name: int(suite.attrib[name]) for name in ("tests", "failures", "errors", "skipped")}
        except (KeyError, ValueError) as error:
            raise PortError("Missing/malformed JUnit counts") from error
        if declared != {"tests": len(actual), "failures": 0, "errors": 0, "skipped": 0} or not actual:
            raise PortError("Zero, failed, skipped or inconsistent JUnit suite")
        for case in actual:
            identity = (case.get("classname"), case.get("name"))
            if not all(identity) or identity in identities:
                raise PortError("Missing/duplicate actual test-case identity")
            identities.add(identity)
            if any(case.find(outcome) is not None for outcome in ("failure", "error", "skipped")):
                raise PortError("Actual JUnit case failed/skipped despite claimed counts")
        total += len(actual)
    return counts({
        "discovered": total, "run": total, "passed": total,
        "failed": 0, "errors": 0, "skipped": 0,
    }, minimum=minimum)


def compare_discovery_tsv(report: Path, result: dict):
    if not report.is_file() or report.is_symlink() or report.stat().st_size > 65535:
        raise PortError("Missing/unsafe/oversized scaffold discovery TSV")
    with report.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames != ["suite", "discovered", "passed", "failed", "errors", "skipped"]:
            raise PortError("Malformed scaffold discovery output contract")
        rows = list(reader)
    if len(rows) != len(SUITES) or {r["suite"] for r in rows} != set(SUITES):
        raise PortError("Missing/duplicate mandatory scaffold suites")
    for row in rows:
        if set(row) != set(reader.fieldnames) or any(value is None for value in row.values()):
            raise PortError("Malformed scaffold discovery TSV row")
        try:
            actual = {name: int(row[name]) for name in ("discovered", "passed", "failed", "errors", "skipped")}
        except (ValueError, TypeError, KeyError) as error:
            raise PortError("Malformed scaffold discovery TSV counts") from error
        if actual != {name: result[row["suite"]][name] for name in actual}:
            raise PortError("Scaffold discovery TSV disagrees with actual JUnit cases")


def suite_case_ids(directory: Path):
    suite_counts(directory, 1)
    return sorted(
        ({"class": case.get("classname"), "method": case.get("name")}
         for path in directory.glob("TEST-*.xml") for case in read_xml(path).findall("testcase")),
        key=lambda case: (case["class"], case["method"]),
    )


def failed_case_report(directories):
    totals = {"discovered": 0, "passed": 0, "failed": 0, "errors": 0, "skipped": 0}
    identities, failures = set(), []
    for directory in directories:
        files = sorted(directory.glob("TEST-*.xml"))
        if not files:
            raise PortError("Failed-command mandatory JUnit reports are missing: " + str(directory))
        for path in files:
            suite = read_xml(path)
            cases = suite.findall("testcase")
            try:
                declared = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            except (KeyError, ValueError) as error:
                raise PortError("Failed-command JUnit counts are malformed") from error
            observed = dict.fromkeys(("failures", "errors", "skipped"), 0)
            if suite.tag != "testsuite" or not cases or declared["tests"] != len(cases):
                raise PortError("Failed-command JUnit reports are malformed/zero-discovery")
            for case in cases:
                identity = (case.get("classname"), case.get("name"))
                key = (str(directory), *identity)
                if not all(identity) or key in identities:
                    raise PortError("Failed-command JUnit method identity is missing/duplicate")
                identities.add(key)
                outcomes = [tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None]
                if len(outcomes) > 1:
                    raise PortError("Failed-command JUnit outcomes are ambiguous")
                outcome = outcomes[0] if outcomes else "passed"
                totals["discovered"] += 1
                totals[{"failure": "failed", "error": "errors"}.get(outcome, outcome)] += 1
                if outcomes:
                    observed[{"failure": "failures", "error": "errors", "skipped": "skipped"}[outcome]] += 1
                if outcome in ("failure", "error"):
                    failures.append({"report": str(path), "package": suite.get("package") or identity[0].rpartition(".")[0],
                                     "class": identity[0], "test": identity[1], "outcome": outcome})
            if any(declared[name] != observed[name] for name in observed):
                raise PortError("Failed-command JUnit declared/actual outcomes disagree")
    if not totals["discovered"]:
        raise PortError("Failed-command JUnit report set has zero discovery")
    return {"kind": "failed-command-available-junit-reports", "counts": totals, "failures": failures}


def collect_suites(android: Path):
    result = {name: suite_counts(android / path, minimum) for name, (path, minimum) in SUITES.items()}
    require_case_identities(android / SUITES["app"][0], WP501_ACCEPTANCE_CASES)
    compare_discovery_tsv(android / "build" / "reports" / "scaffold" / "test-discovery.tsv", result)
    return result


def require_case_identities(directory: Path, expected: set[tuple[str, str]]):
    if not expected:
        raise PortError("Zero required behavioral test identities")
    result = suite_counts(directory, len(expected))
    observed = {
        (case.get("classname"), case.get("name"))
        for path in directory.glob("TEST-*.xml") for case in read_xml(path).findall("testcase")
    }
    if not expected <= observed:
        raise PortError(f"Missing WP-501 behavioral tests: {sorted(expected - observed)}")
    return result


def lint_evidence(report: Path):
    root = read_xml(report)
    if (
        root.tag != "issues" or set(root.attrib) != {"format", "by"}
        or root.get("format") != "6" or not root.get("by", "").startswith("lint ")
        or any(child.tag != "issue" for child in root)
    ):
        raise PortError("Malformed Android Lint XML")
    issues = root.findall("issue")
    if any(issue.get("severity") in ("Error", "Fatal") for issue in issues):
        raise PortError("Android lint contains actual errors")
    for index, issue in enumerate(issues, start=1):
        if issue.get("severity") not in ("Warning", "Information", "Hint"):
            raise PortError(
                f"Unknown/missing lint severity in {report}, issue {index}: {issue.get('severity')!r}"
            )
        if not issue.get("id") or not issue.get("message"):
            raise PortError(f"Missing lint issue id/message in {report}, issue {index}")
    return {"sha256": file_sha256(report), "warnings": len(issues)}


def collect_lint(android: Path):
    reports = {}
    for target in LINT_TARGETS:
        report = android / target / "build" / "reports" / "lint-results-debug.xml"
        reports[target] = lint_evidence(report)
    return reports


def lint_bundle_path(target: str):
    return "lint/" + target.replace("/", "--") + ".xml"


def report_tsv(path: Path, columns: list[str]):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 8 * 1024 * 1024:
        raise PortError("Missing/unsafe/oversized mandatory TSV report")
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames != columns:
            raise PortError("Malformed mandatory TSV header")
        rows = list(reader)
    if not rows or len(rows) > 100000:
        raise PortError("Empty/oversized mandatory TSV report")
    for row in rows:
        if set(row) != set(columns) or any(not isinstance(v, str) or not v.strip() for v in row.values()):
            raise PortError("Malformed mandatory TSV row")
    if len({tuple(row[column] for column in columns) for row in rows}) != len(rows):
        raise PortError("Duplicate mandatory TSV row")
    return rows


def validate_graph_runtime(root: Path):
    graph = report_tsv(root / "module-graph.tsv", ["consumer", "producer", "configuration"])
    module = re.compile(r":[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)*")
    if any(module.fullmatch(row["consumer"]) is None or module.fullmatch(row["producer"]) is None
           or any(character.isspace() for character in row["configuration"]) for row in graph):
        raise PortError("Malformed actual module-graph identifiers")
    runtime = report_tsv(root / "runtime-dependencies.tsv", [
        "artifact", "declared_license", "license_url", "license_pom", "license_pom_sha256", "legal_gate",
    ])
    provenance = {
        (row["artifact"], row["declared_license"], row["license_url"], row["license_pom"])
        for row in runtime
    }
    if len(provenance) != len(runtime):
        raise PortError("Duplicate runtime license provenance")
    for row in runtime:
        if (
            row["artifact"].count(":") != 2 or row["license_pom"].count(":") != 2
            or re.fullmatch(r"[0-9a-f]{64}", row["license_pom_sha256"]) is None
            or not row["license_url"].startswith(("https://", "http://"))
            or row["legal_gate"] != "human-review-pending"
        ):
            raise PortError("Malformed runtime license provenance or invented legal approval")


# Exact merged debug-APK permissions (WP-206 connectivity, WP-303 onboarding,
# WP-315 camera tools, plus the scaffold receiver permission).
EXPECTED_APK_PERMISSIONS = [
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.CAMERA",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_LOCAL_NETWORK",
    "com.meshcoreone.android.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
]


def validate_apk_inspection(value: dict, root: Path):
    fields(value, APK_FIELDS, label="actual APK inspection")
    if (
        value["artifact"] != "android/app/build/outputs/apk/debug/app-debug.apk"
        or value["package"] != "com.meshcoreone.android.debug"
        or type(value["min_sdk"]) is not int or value["min_sdk"] != 31
        or type(value["target_sdk"]) is not int or value["target_sdk"] != 37
        or value["verification_fixture_packaged"] is not False
        or value["static_16kb_alignment_verified"] is not True
        or value["native_16kb_compatibility_verified"] is not False
        or value["physical_device_or_native_runtime_verified"] is not False
        or value["permissions"] != EXPECTED_APK_PERMISSIONS
        or value["pinned_notices"] != ["assets/licenses/GPL-3.0.txt", "assets/licenses/MeshCore-MIT.txt", "assets/licenses/Apache-2.0.txt"]
        or not isinstance(value["scope"], str) or not value["scope"].strip()
        or type(value["size_bytes"]) is not int or value["size_bytes"] < 1
        or not isinstance(value["sha256"], str) or re.fullmatch(r"[0-9a-f]{64}", value["sha256"]) is None
    ):
        raise PortError("Missing/mismatched typed debug APK inspection")
    apk = root / "debug.apk"
    if apk.stat().st_size != value["size_bytes"] or file_sha256(apk) != value["sha256"]:
        raise PortError("Inspected APK size/digest disagrees with the uploaded bytes")
    try:
        with zipfile.ZipFile(apk) as archive:
            members = archive.infolist()
            if (
                len(members) > 50000 or len({m.filename for m in members}) != len(members)
                or sum(m.file_size for m in members) > 512 * 1024 * 1024
                or any(m.file_size > 128 * 1024 * 1024 for m in members)
                or archive.testzip() is not None
            ):
                raise PortError("APK archive is malformed/oversized/duplicated")
            names = archive.namelist()
            if "AndroidManifest.xml" not in names or "classes.dex" not in names:
                raise PortError("Actual APK manifest/dex is missing")
            if any(name not in names or not archive.read(name) for name in value["pinned_notices"]):
                raise PortError("Actual APK notices are missing")
            for member in members:
                if re.fullmatch(r"classes\d*\.dex", member.filename):
                    data = archive.read(member)
                    if any(prefix in data for prefix in (
                        b"com/meshcoreone/android/scaffold/roomverification",
                        b"com/meshcoreone/android/core/testing",
                    )):
                        raise PortError("Test fixture/helper is packaged despite claimed absence")
            libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
            actual = {name: elf_load_alignment(archive.read(name)) for name in libraries}
    except zipfile.BadZipFile as error:
        raise PortError("Uploaded debug APK is not a valid ZIP archive") from error
    if value["native_libraries"] != libraries or value["elf_pt_load_alignment"] != actual:
        raise PortError("Actual APK native libraries/alignment disagree with inspection")
