"""AndroidOnly: WP-503 Immutable current-source native family binding, never live host authority."""

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
from types import MappingProxyType
import uuid

from shared_device_runner import parse_outcomes

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "android-port"))

from controller.errors import PortError
from controller.gates import policy_revision
from controller.ledger import Ledger
from controller.model import load_manifest
from controller.schema import load_json
from local.debug_packaging import ADAPTER, OPERATION, source_identity
from local.reserve import shared_ledger
from controller.capabilities import CapabilityEngine

CANONICAL = "1666502a-0420-46ef-930f-e78651ba8b9f"
CURRENT_APP_SESSION = "df52b3d0-81d4-49fb-b88f-a5926ed31fa5"
CURRENT_CLI_SESSION = "b531305a-0513-4731-896f-2b470f8879cd"
PUBLIC_REF = "refs/heads/cbattlegear-issue-136-wp-503-accessibility-audit-807238"
MAIN_REF = "refs/heads/main"
PRODUCER = "59a47ac40bd2f84e7188c87a5521af9d0f16e75e"
PRODUCTION = "android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppSettingsContent.kt"
PRODUCTION_BLOB = "421fc446443a5f301085507603701d7da04d40ef"
FIXTURE = "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeAppLanguageAccessibilityTest.kt"
FIXTURE_BLOB = "99175354ba29a9230b7a2b03a7c17ea4153dd394"
CLASS = "com.meshcoreone.android.accessibility.NativeAppLanguageAccessibilityTest"
METHODS = (
    "actualLanguageChoicesExposeTheirOwnNameAndSelectedStateWithContainedFont200Actions",
    "actualNamedChineseChoiceAndSystemResetPreserveLanguageDetailAcrossNativeRecreation",
)
IDENTITIES = tuple(CLASS + "#" + method for method in METHODS)

@dataclass(frozen=True)
class FamilySpec:
    name: str
    apis: tuple[int, ...]
    klass: str
    methods: tuple[str, ...]
    producer: str
    production: str
    production_blob: str
    fixture: str
    fixture_blob: str
    source_pins: tuple[tuple[str, str, str], ...] = ()
    fixture_methods: tuple[str, ...] = ()
    original_fixture_blob: str | None = None

    @property
    def identities(self):
        return tuple(self.klass + "#" + method for method in self.methods)


DIAGNOSTICS = FamilySpec(
    "diagnostics", (37,),
    "com.meshcoreone.android.accessibility.NativeDiagnosticsFlowAccessibilityTest",
    (
        "noiseFloorHasDataBoundSummaryAndVisibleNumericAlternativesAtFont200",
        "shorterWindowRevealsEveryNumericAlternativeByActualNativeScrollAtFont200",
        "disconnectedNoiseFloorExplainsUnavailableDataWithoutAnEmptyGraph",
        "disconnectedCliExplainsUnavailableCommandsInsteadOfAnActiveTerminal",
        "disconnectedRxLogRetainsTextualStatusRecoveryAndReachableFilterSelection",
    ),
    "ff42476614d07588eb17f1df58b4f9e0e479b3ff",
    "android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics",
    "c9f4bfeb12dd02729dbf080e6bb5e513d170e73b",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeDiagnosticsFlowAccessibilityTest.kt",
    "e31edcf50c08532465ad3254919ff13eefbd8d7f",
    (
        ("android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics",
         "8a8c6348af1c98b3f11f21769800308cf302b8f9", "c9f4bfeb12dd02729dbf080e6bb5e513d170e73b"),
        ("android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/DiagnosticsAccessibilityAudit.kt",
         "ff42476614d07588eb17f1df58b4f9e0e479b3ff", "3b81cb7808bebee6bcbb2e15eadd5cb7a77e58b5"),
        ("android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityAssertions.kt",
         "f7474bb1bd06c6df93a71c8664e9dacade7c8749", "efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c"),
        ("android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/DeviceAccessibilityHarness.kt",
         "7f24b57c1ab06437435b8938d166a5254fec0894", "8768ee9c2b25fc1a7d43d0442980ff4deabb6d81"),
    ),
)
NODES = FamilySpec(
    "nodes", (37,),
    "com.meshcoreone.android.accessibility.NativeNodesFlowAccessibilityTest",
    (
        "offlineChatRepeaterAndRoomKeepTransportDisabledAndSavedHistoryRoutingReachable",
        "offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable",
        "actualSearchCrossesSelectedSegmentsAndNativeNameSortKeepsScaledResultsReachable",
        "offlineCachedNodeRetainsReadableNameTextualStatusAndNativeRowRouting",
        "offlineSyncShowsLocalizedRecoveryDialogWithReachableScaledTextAndDismissAction",
        "actualDetailKeepsALongCjkHeadingAndNicknameCancellationReachableAtFont200",
        "offlineClearMessagesRequiresConfirmationAndExplainsUnavailableServicesWithoutDismissingDetail",
    ),
    "9101b45f3939bdb82af5c93cc77149c47bf34996",
    "android/feature/nodes/src/main",
    "d478d0ecc746c76e384dbdd62c3b17990a760601",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeNodesFlowAccessibilityTest.kt",
    "3f50d6c80dab380e0e7883016295a16510604921",
    (
        ("android/feature/nodes/src/main",
         "fd9fa8e16c8a74043387010a192c3a9e36abd783", "d478d0ecc746c76e384dbdd62c3b17990a760601"),
        ("android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NodesAccessibilityAudit.kt",
         "1440142b54e2ea0f02499f50b91eb3d3af98df6a", "469f756f012b334636ed7245cc23963b3ecef29a"),
        ("android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/OfflineNodesAudit.kt",
         "41bdbfac45b4d3690f29a5363da02047642e2716", "5fd9340c847780b0a4cb1df4b0fc4dd7fae36f45"),
        ("android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityAssertions.kt",
         "f7474bb1bd06c6df93a71c8664e9dacade7c8749", "efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c"),
        ("android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/DeviceAccessibilityHarness.kt",
         "7f24b57c1ab06437435b8938d166a5254fec0894", "8768ee9c2b25fc1a7d43d0442980ff4deabb6d81"),
    ),
)
COMPARATOR = FamilySpec(
    "comparator", (37,),
    "com.meshcoreone.android.accessibility.NativeLocalizedCompareParityTest",
    (
        "asciiCaseMatchesSwiftEquality",
        "sharpSMatchesSwiftEquality",
        "canonicalAccentMatchesSwiftEquality",
        "fullWidthMatchesSwiftEquality",
        "nodeNameCaseMatchesSwiftEquality",
        "digraphCaseMatchesSwiftEquality",
        "ligatureMatchesSwiftEquality",
        "accentRemainsUnequalAsInSwift",
        "dotlessIRemainsUnequalAsInSwift",
        "dottedIRemainsUnequalAsInSwift",
        "equalNamesKeepTheirInputOrderWithTheProductionComparator",
    ),
    "5bbef0b2f29b9a96dd88b5e5a5d4584a6739c8c5",
    "android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text",
    "b1fde2e38d54bb945364bbf27abd3c0c66f012b8",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLocalizedCompareParityTest.kt",
    "79e7e12b950f1459f006bc72942539ecf07375e0",
    (
        ("android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text",
         "8a8c6348af1c98b3f11f21769800308cf302b8f9", "b1fde2e38d54bb945364bbf27abd3c0c66f012b8"),
        ("android/feature/tools/src/test/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text/SwiftTextParityTest.kt",
         "8a8c6348af1c98b3f11f21769800308cf302b8f9", "58af4467a85422c8d53cee7001a159cf56f31642"),
        ("docs/android/evidence/WP-316/oracle.swift.txt",
         "d4cc34496decfa3b57c588cf208a9dc160ddf317", "bbccc686e1414561fa7f9f7d8e11cd1846e4fcf5"),
        ("docs/android/evidence/WP-316/oracle-output.txt",
         "d4cc34496decfa3b57c588cf208a9dc160ddf317", "0064656ced61ca93fc541c3e3b070c3f2076263d"),
    ),
)
TRAZ = FamilySpec(
    "traz", (37,),
    "com.meshcoreone.android.accessibility.NativeLocalizedCompareLocalePreservationTest",
    (
        "turkishProducerCasesPreserveLocaleTailoringOnAndroid",
        "azeriProducerCasesPreserveLocaleTailoringOnAndroid",
    ),
    "6c81bad084f27c1a6501e1296eec93f160fb517e",
    "android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text",
    "b1fde2e38d54bb945364bbf27abd3c0c66f012b8",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLocalizedCompareLocalePreservationTest.kt",
    "41a1e4046f96aeb0c725a1344e7f420dfd142be4",
    (
        ("android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text",
         "8a8c6348af1c98b3f11f21769800308cf302b8f9", "b1fde2e38d54bb945364bbf27abd3c0c66f012b8"),
        ("android/feature/tools/src/test/kotlin/com/meshcoreone/android/feature/tools/diagnostics/text/SwiftTextParityTest.kt",
         "8a8c6348af1c98b3f11f21769800308cf302b8f9", "58af4467a85422c8d53cee7001a159cf56f31642"),
    ),
)
LOS = FamilySpec(
    "los", (37,),
    "com.meshcoreone.android.accessibility.NativeLineOfSightAccessibilityTest",
    ("savedRepeatersExposeNamedNativeActionsForNonGestureEndpointSelectionAtFont200",),
    "3f39a777376e322e2bc533f1d1caf92a87a44ed8",
    "android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/los",
    "46d415e10269ea22e520cbf35f82467c778956a8",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLineOfSightAccessibilityTest.kt",
    "6032d1d18a977e0d2cac3e666a1fd45f39e24468",
    (
        ("android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/los",
         "c6cdac39e89081647ea6475e6bcae800f2531109", "46d415e10269ea22e520cbf35f82467c778956a8"),
        ("android/app/src/test/kotlin/com/meshcoreone/android/app/los/SavedEndpointSemanticsTest.kt",
         "c6cdac39e89081647ea6475e6bcae800f2531109", "e6bac5fc92755ca3a104928e6969586af3c2709b"),
        ("android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityAssertions.kt",
         "f7474bb1bd06c6df93a71c8664e9dacade7c8749", "efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c"),
        ("android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/DeviceAccessibilityHarness.kt",
         "7f24b57c1ab06437435b8938d166a5254fec0894", "8768ee9c2b25fc1a7d43d0442980ff4deabb6d81"),
    ),
    fixture_methods=(
        "frameworkNativeAccessibilityTreeFindsAndInvokesTheActualNamedButton",
        "savedRepeatersExposeNamedNativeActionsForNonGestureEndpointSelectionAtFont200",
    ),
    original_fixture_blob="f8c2cab5dc964548e938f1e023f72912874fc2a8",
)
CACHED_CHAT = FamilySpec(
    "cached-chat", (37,),
    "com.meshcoreone.android.accessibility.NativeLauncherFocusAccessibilityTest",
    ("realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200",),
    "e8fa6b2994177e09931925800ed7696222a65af7",
    "android/feature/chats/src/main",
    "c466bf76ceadddb370ed9803be6d9f403c0275b5",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeLauncherFocusAccessibilityTest.kt",
    "698d649b230b7d7945a5d901d37f748427508986",
    (
        ("android/feature/chats/src/main",
         "31557a16ca4492bbd669a7ad0358e702c5c54031", "c466bf76ceadddb370ed9803be6d9f403c0275b5"),
        ("android/feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/timeline",
         "2396ac64f9d3bcee7fa3a7e4f33e87956508d31d", "331c0835addbb7102db51ea502233ca02134fd84"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt",
         "31557a16ca4492bbd669a7ad0358e702c5c54031", "ee5c5e112dcf041d8dbbd1471e2ad01d755b6bf8"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt",
         "514c451653531da9e3cc47139a9e4e44fb9d6cf0", "11d18815632db5298a0552636ac86a7c73a657d4"),
        ("android/app/src/test/kotlin/com/meshcoreone/android/app/platform/NotificationDraftRestorationTest.kt",
         "03306ff125655f977d88c5b517ad4cab26d9e771", "f076dceef89d8f14efe9d3dad6af260e90133d98"),
        ("android/app/src/test/kotlin/com/meshcoreone/android/app/platform/ChatTimelineRestorationTest.kt",
         "4b6629ac2bf215577ec1522530a77de61ed577fe", "187e1a7e6fcd43c2494e0b036706d5f25cd9015f"),
        ("android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering",
         "70a48774cbd2eb9f4280f917c0cd126e881de679", "0171a46786d18130414490b0e919752e5315f6a2"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatConversationScreen.kt",
         "e9f0febc7e53b09adb00ac56928e7073366023f6", "59bb797248d7ccf247dd69c3ee93fd8af5412ff9"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NativeNavigationShell.kt",
         "80ab59b14e23092080063664b234bb2a3166063d", "c9a881fcdbf605784cbf2593d77c808b41c75083"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NavigationCoordinator.kt",
         "80ab59b14e23092080063664b234bb2a3166063d", "26181519d9a0f8cb690e1ae1b38c08f2e05c45b9"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NavigationState.kt",
         "80ab59b14e23092080063664b234bb2a3166063d", "f6b37d7f1cdef4b49378703ae5ced2d9ea607a7b"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/NavigationSavedState.kt",
         "446607773ae4be3780f9029c0730e5e531eef945", "d72e7573e37b5c9491aacd5e60a5f7b97643b5aa"),
        ("android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppMessageContent.kt",
         "514c451653531da9e3cc47139a9e4e44fb9d6cf0", "dc2633bc24ee95addc79c3a9869257934cf402a1"),
        ("android/accessibility-tests/src/shared/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityAssertions.kt",
         "f7474bb1bd06c6df93a71c8664e9dacade7c8749", "efc83d617bd7fe50fb6e5350e6a507aaf6f7bc1c"),
        ("android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/DeviceAccessibilityHarness.kt",
         "7f24b57c1ab06437435b8938d166a5254fec0894", "8768ee9c2b25fc1a7d43d0442980ff4deabb6d81"),
        ("android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityServiceSnapshot.kt",
         "cc360e7bd8797bdb0847faadec5187862c093f8e", "3dee4f40c5aec6fb0adb1bb406a233fb223b43a4"),
    ),
    fixture_methods=(
        "actualSubcompositionQueuesEffectsAndDetectsInlineSchedulingCounterexample",
        "realCachedChatImeKeepsNativeComposerVisibleAndBackPreservesDraft",
        "realCachedChatDraftAndScrolledMessageSurviveCurrentWindowRecreationAtFont200",
        "actualThemeProviderObservesDefaultAndReducedSystemMotionWithoutAnOverride",
        "realLauncherDefaultAndReducedMotionKeepTabsAndNativeDetailBackReachable",
        "realWindowResizeKeepsNamedRailActionsAndSettingsDetailBackStackReachable",
        "realAppearancePersistsAllUnlockedThemesAndSchemesWithNativeFont200Actions",
        "frameworkControlCanAcquireRealNativeAccessibilityFocus",
        "realDisconnectedLauncherKeepsFiveTabsSelectedAndPlatformFocusReachable",
        "controlledPrimaryAndLateRestorationPreserveBothActualFailures",
    ),
)
COLD_PLATFORM_SOURCE_PINS = (
    ("android/app/src/main",
     "31557a16ca4492bbd669a7ad0358e702c5c54031", "d093f4fc5e59ce51919217fcce87b50f103ac453"),
    ("android/app/build.gradle.kts",
     "ee1104b097899ab08f3f82227d8cde354bc92602", "a0752382e0c641e417b6c92297bc5018f0ace2b0"),
    ("android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativeAccessibilityServiceSnapshot.kt",
     "cc360e7bd8797bdb0847faadec5187862c093f8e", "3dee4f40c5aec6fb0adb1bb406a233fb223b43a4"),
)
PLATFORM_SERVICE_CONTROL = FamilySpec(
    "platform-service-control", (37,),
    "com.meshcoreone.android.accessibility.NativePlatformServiceControlTest",
    ("actualServiceStateActivatesAndRestoresWithin1000msWithoutActivityOrComposeRule",),
    "d977bce7658a83c5df7d7dcc88b20eeaaeecf85d",
    "android/app/src/main",
    "d093f4fc5e59ce51919217fcce87b50f103ac453",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativePlatformServiceControlTest.kt",
    "14bb0ea56959ea52e4b5d65d232f9a8c98a9b6ca",
    COLD_PLATFORM_SOURCE_PINS,
)
PLATFORM_CALLBACK_CONTROL = FamilySpec(
    "platform-callback-control", (37,),
    "com.meshcoreone.android.accessibility.NativePlatformCallbackDeliveryTest",
    ("actualServiceStateRetains1000msWithPublicCallbackAndMainLooperObservations",),
    "d977bce7658a83c5df7d7dcc88b20eeaaeecf85d",
    "android/app/src/main",
    "d093f4fc5e59ce51919217fcce87b50f103ac453",
    "android/accessibility-tests/src/androidTest/kotlin/com/meshcoreone/android/accessibility/NativePlatformCallbackDeliveryTest.kt",
    "5f5913207d6ccc23121bc20e99159062e8622ae6",
    COLD_PLATFORM_SOURCE_PINS,
)
FAMILIES = MappingProxyType({
    "language": FamilySpec("language", (31, 37), CLASS, METHODS, PRODUCER,
                           PRODUCTION, PRODUCTION_BLOB, FIXTURE, FIXTURE_BLOB),
    "diagnostics": DIAGNOSTICS,
    "nodes": NODES,
    "comparator": COMPARATOR,
    "traz": TRAZ,
    "los": LOS,
    "cached-chat": CACHED_CHAT,
    "platform-service-control": PLATFORM_SERVICE_CONTROL,
    "platform-callback-control": PLATFORM_CALLBACK_CONTROL,
})


def family_spec(name):
    if not isinstance(name, str) or name not in FAMILIES:
        raise PortError("Only an immutable fixed family is admitted: " + ", ".join(FAMILIES))
    return FAMILIES[name]


def git(*arguments):
    return subprocess.check_output(
        ["git", "-C", str(ROOT), *arguments], text=True, encoding="utf-8", errors="strict",
    ).strip()


@dataclass(frozen=True)
class LanguageBinding:
    repository: str
    source_revision: str
    android_tree: str
    android_source_tree_sha256: str
    manifest_sha256: str
    policy_revision: str
    canonical_session: str
    replacement_app_session: str
    replacement_cli_session: str
    reservation_revision: int
    producer_revision: str = PRODUCER
    production_blob: str = PRODUCTION_BLOB
    fixture_blob: str = FIXTURE_BLOB
    family: str = "language"


def prepare_binding(revision, claim, *, family="language"):
    spec = family_spec(family)
    if (
        not isinstance(revision, str) or re.fullmatch(r"[0-9a-f]{40}", revision) is None
        or claim["wp"] != "WP-503"
        or claim["attempt"] != claim["identity"]["session_id"] or claim["attempt"] != CANONICAL
        or type(claim["revision"]) is not int or claim["revision"] < 1
    ):
        raise PortError("Language binding requires the actual canonical WP503 claim and exact current revision")
    source = source_identity(ROOT, revision)
    if git("merge-base", "--is-ancestor", spec.producer, revision):
        raise PortError("Unexpected producer ancestry output")
    if (
        git("rev-parse", revision + ":" + spec.production) != spec.production_blob
        or (family == "language" and git("rev-parse", PRODUCER + ":" + spec.production) != spec.production_blob)
        or git("rev-parse", revision + ":" + spec.fixture) != spec.fixture_blob
    ):
        raise PortError("Exact producer repair or strict current fixture binding drifted")
    if family != "language":
        pins = ((spec.fixture, spec.producer, spec.original_fixture_blob or spec.fixture_blob), *spec.source_pins)
        for path, producer, blob in pins:
            git("merge-base", "--is-ancestor", producer, revision)
            current_blob = spec.fixture_blob if path == spec.fixture else blob
            if (git("rev-parse", revision + ":" + path) != current_blob
                    or git("rev-parse", producer + ":" + path) != blob):
                raise PortError("Exact " + spec.name + " fixture/helper/production provenance drifted: " + path)
    methods = tuple(re.findall(r"@Test fun (\w+)\(", git("show", revision + ":" + spec.fixture)))
    if (
        methods != (spec.fixture_methods or spec.methods)
        or not spec.methods or len(spec.methods) != len(set(spec.methods))
        or tuple(method for method in methods if method in spec.methods) != spec.methods
    ):
        raise PortError({
            "language": "Language cohort must retain exactly the two original method identities",
            "diagnostics": "Diagnostics cohort must retain exactly the five original method identities",
            "nodes": "Nodes cohort must retain exactly the seven original method identities",
            "comparator": "Comparator cohort must retain exactly the eleven original method identities",
            "traz": "Supplemental tr/az cohort must retain exactly the two original method identities",
            "los": "LoS cohort must retain the one original selected method and full two-method fixture inventory",
            "cached-chat": "Cached-chat cohort must retain the one original selected method and full ten-method fixture inventory",
            "platform-service-control": "Platform service control must retain exactly the one original zero-Activity method",
            "platform-callback-control": "Platform callback control must retain exactly the one original zero-Activity method",
        }[family])
    manifest = load_manifest(ROOT)
    policy = load_json(ROOT / "docs" / "android" / "automation-policy.json")
    CapabilityEngine(policy).admit("WP-503", manifest.wp("WP-503")["write_paths"], ADAPTER, OPERATION)
    return LanguageBinding(
        policy["repository"], revision, git("rev-parse", revision + ":android"), source,
        manifest.sha256, policy_revision(manifest, policy), CANONICAL,
        CURRENT_APP_SESSION, CURRENT_CLI_SESSION, claim["revision"],
        spec.producer, spec.production_blob, spec.fixture_blob, family,
    )


@dataclass(frozen=True)
class LanguageRequest:
    binding: LanguageBinding
    api: int
    operation_owner: str

    def __post_init__(self):
        spec = family_spec(self.binding.family)
        if type(self.api) is not int or self.api not in spec.apis:
            raise PortError("API is not admitted for the immutable " + spec.name + " family")
        if (self.binding.producer_revision, self.binding.production_blob, self.binding.fixture_blob) != (
            spec.producer, spec.production_blob, spec.fixture_blob,
        ):
            raise PortError("Request family differs from its immutable producer/fixture binding")
        prefix = self.binding.canonical_session + ":" + spec.name + ":"
        if (
            self.binding.canonical_session != CANONICAL
            or self.binding.replacement_app_session != CURRENT_APP_SESSION
            or self.binding.replacement_cli_session != CURRENT_CLI_SESSION
            or not self.operation_owner.startswith(prefix)
            or re.fullmatch(r"[0-9a-f]{32}", self.operation_owner[len(prefix):]) is None
        ):
            raise PortError("Language operation must retain canonical identity and truthful current replacement attribution")

    @property
    def identities(self):
        return family_spec(self.binding.family).identities


def request_for(binding, api):
    return LanguageRequest(binding, api, CANONICAL + ":" + family_spec(binding.family).name + ":" + uuid.uuid4().hex)


def current_binding(revision, *, family="language"):
    policy = load_json(ROOT / "docs" / "android" / "automation-policy.json")
    claim = Ledger(shared_ledger(ROOT), policy["repository"]).get("WP-503")
    if claim is None:
        raise PortError("Missing actual canonical WP503 reservation")
    return prepare_binding(revision, claim, family=family)


def verify_current_request(request):
    actual = (current_binding(request.binding.source_revision) if request.binding.family == "language"
              else current_binding(request.binding.source_revision, family=request.binding.family))
    if actual != request.binding:
        raise PortError("Source, policy, current owner or reservation revision changed before execution")
    published = git("ls-remote", "origin", PUBLIC_REF).split()
    if published != [request.binding.source_revision, PUBLIC_REF]:
        raise PortError("Native request is not the exact currently published PR205 input")
    return trusted_execution_policy()


def trusted_execution_policy():
    trusted = git("rev-parse", "refs/remotes/origin/main")
    if git("ls-remote", "origin", MAIN_REF).split() != [trusted, MAIN_REF]:
        raise PortError("Trusted main is stale; fetch current main before native readiness/execution")
    try:
        git("diff", "--exit-code", "--quiet", trusted, "--",
            "docs/android/automation-policy.json", "docs/android/port-manifest.json")
    except subprocess.CalledProcessError as error:
        raise PortError("Candidate/worktree policy or manifest differs from trusted current main") from error
    return json.loads(git("show", trusted + ":docs/android/automation-policy.json"))


def account_outcomes(transcript, request, *, process_exit, forced_termination):
    if type(process_exit) is not int or process_exit != 0 or forced_termination is not False:
        raise PortError("Normal unforced selected-family instrumentation completion is unverified")
    result = parse_outcomes(transcript, list(request.identities))
    if result["passed"] != len(request.identities) or any(result[key] for key in ("failed", "errors", "skipped")):
        raise PortError("Selected cohort is not all-positive; retain uncertainty and original negative outcomes")
    return {
        **result, "api": request.api, "source_revision": request.binding.source_revision,
        "family": request.binding.family,
        "native_lane_operation_owner": request.operation_owner,
        "scope": "transcript accounting only; real admitted execution/provenance remains required",
        "acceptance": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--head", required=True)
    parser.add_argument("--family", choices=tuple(FAMILIES), default="language")
    args = parser.parse_args(argv)
    try:
        binding = (current_binding(args.head) if args.family == "language"
                   else current_binding(args.head, family=args.family))
        requests = [request_for(binding, api) for api in family_spec(binding.family).apis]
        print(json.dumps({
            "binding": asdict(binding), "requests": [
                {**asdict(request), "identities": request.identities} for request in requests
            ],
            "authority": False, "native_starts": 0, "apks_built": False,
        }, indent=2))
        return 0
    except (PortError, OSError, subprocess.SubprocessError, KeyError, ValueError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
