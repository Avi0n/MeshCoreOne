"""Reviewed bootstrap expansion rules for the one pinned reference, never resync defaults.

The canonical manifest stores their exact expansion, not these selectors. New upstream
paths are blockers until WP-006 proposes a human-reviewed manifest amendment.
"""

from pathlib import PurePosixPath

from controller.errors import PortError

TRANSLATION_PATHS = frozenset("""
MC1/Services/MessageTranslating.swift
MC1/Services/MessageTranslationNeedsDownloadError.swift
MC1/Services/TranslationLanguageAvailability.swift
MC1/Services/TranslationLanguageResolver.swift
MC1/Services/TranslationPerformResult.swift
MC1/Services/TranslationSession+Configuration.swift
MC1/Services/TranslationSessionLauncher.swift
MC1/Services/TranslationSessionRequest.swift
MC1/Views/Chats/Components/BubbleTranslationControl.swift
MC1/Views/Chats/Components/ConversationTranslationSessionModifier.swift
MC1/Views/Chats/ViewModel/ChatViewModel+Translation.swift
MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel+Translation.swift
MC1/Views/Settings/TranslateIntoLanguageView.swift
MC1Services/Sources/MC1Services/Models/Rendering/DetectedLanguage.swift
MC1Services/Sources/MC1Services/Models/Rendering/MessageTranslationChrome.swift
MC1Services/Sources/MC1Services/Models/Rendering/TranslationTargetPreference.swift
MC1Services/Sources/MC1Services/Services/MessageLanguageDetector.swift
MC1Services/Tests/MC1ServicesTests/MessageLanguageDetectorTests.swift
MC1Services/Tests/MC1ServicesTests/MessageTranslationChromeTests.swift
MC1Services/Tests/MC1ServicesTests/TranslationTargetPreferenceTests.swift
MC1Tests/Services/TranslationLanguageResolverTests.swift
MC1Tests/Services/TranslationSessionConfigurationTests.swift
MC1Tests/Services/TranslationSessionLauncherTests.swift
MC1Tests/ViewModels/ChatViewModelTranslationTests.swift
MC1Tests/ViewModels/GatedMessageTranslator.swift
MC1Tests/ViewModels/RoomConversationViewModelTranslationTests.swift
""".split())


def groups(entries: dict[str, str]) -> dict[str, str]:
    result = {}
    for owner, names in entries.items():
        for name in names.split():
            if name in result:
                raise PortError(f"Duplicate bootstrap attribution: {name}")
            result[name] = owner
    return result


SERVICE_STEMS = groups({
    "WP-202": """
        PersistenceStore PersistenceStore+Channels PersistenceStore+Contacts
        PersistenceStore+Devices PersistenceStore+Diagnostics PersistenceStore+FailedSends
        PersistenceStore+Messages PersistenceStore+Metadata PersistenceStore+Migration
        PersistenceStore+PendingSends PersistenceStore+Rooms
    """,
    "WP-203": """
        AppBackupEnvelope AppBackupService BackupUserDefaults RestoreOutcome
        PersistenceStore+BackupBatchInsert PersistenceStore+BackupExport
        PersistenceStore+BackupHelpers PersistenceStore+BackupImport
    """,
    "WP-204": "AppStorageKey SceneStorageKey KeyGenerationService KeychainService",
    "WP-206": """
        AccessorySetupKitDiscoveryCriteria AccessorySetupKitLogFormatter AccessorySetupKitService
        AccessorySetupKitServicing AccessorySetupPairingService BluetoothScanPairingService
        DevicePairingDelegate DevicePairingError DevicePairingFactory DevicePairingService
    """,
    "WP-208": """
        AckCodeBuilder BLETransportOpenedSignal ChatSendQueueService MessagePollingService
        MessageService MessageService+ACK MessageService+SendChannel MessageService+SendDM
        MessageService+SendHelpers MessageServiceConfig MessageServiceError MessageStatusEvent
        PendingAck SendQueue
    """,
    "WP-209": """
        AdvertLocationPolicy AdvertisementEvent AdvertisementService AdvertisementService+DeltaSync
        ChannelFloodScopeResolver ChannelService ContactCleanupCoordinator ContactCleanupHandling
        ContactService ContactServiceEvent ResolvedFloodScope
    """,
    "WP-210": """
        BinaryProtocolService CLIResponse KeepAliveRetryPolicy LoginResult LoginTimeoutConfig
        NodeConfigImportPlanner NodeConfigService NodeSettingsResponseParser NodeSnapshotService
        RemoteCLICommandRewriter RemoteNodeEvent RemoteNodeService RemoteNodeService+CLI
        RemoteNodeService+Login RemoteNodeService+PathRecovery RemoteNodeService+Reconnection
        RemoteNodeService+Telemetry RemoteOperationTimeoutPolicy RepeaterAdminService
        RoomAdminService RoomServerEvent RoomServerService TelemetryModes
    """,
    "WP-211": """
        DeviceGPSState DeviceService FirmwareDeviceErrorCode RadioOptions RadioPresets
        RegionDiscoveryService RegionalAreas SettingsEvent SettingsService SettingsService+Verified
    """,
    "WP-212": "CommandAuditLogger DebugLogBuffer PersistentLogger RxLogService RxLogService+RegionResolution",
    "WP-213": """
        ChatCoordinator ChatCoordinator+Mutations ChatCoordinator+Rebuild ChatCoordinator+Reload
        ChatCoordinatorRegistry ChatTimelineWriter DraftStore MessageFragmentBuilder MessageLRUCache
    """,
    "WP-215": "NotificationActionHandler NotificationService",
    "WP-216": """
        HeardRepeatEvent HeardRepeatsService InboundHopAdoption MeshCoreOpenReactionParser
        MessageDTO+ReactionVisibility ReactionParser ReactionService
    """,
    "WP-218": "InlineImageDimensionsStore",
})

APP_SERVICE_STEMS = groups({
    "WP-212": "LogExportService",
    "WP-217": "DemoInlineImageSeeder",
    "WP-218": """
        DecodedPreviewCache ElevationService Geocoder ImageHeaderDecoder ImageURLDetector
        InlineImageCache InlineImagePrefetcher LinkPreviewCache LinkPreviewCaching
        LinkPreviewService LinkPreviewService+Scrape LocationService RedirectSafetyDelegate RegionResolver
    """,
    "WP-303": "AppStateProviderImpl",
    "WP-312": "MapSnapshotStore OfflineMapService",
    "WP-401": "NotificationStringProviderImpl",
})

UTILITY_STEMS = groups({
    "WP-201": "DeviceIdentity PersistenceKeys Sequence+IndexByID VContactIdentity",
    "WP-208": "ChannelMessageFormat ChannelRXCorrelation DeduplicationKey",
    "WP-209": "ContactResult ContactShareUtilities HashtagUtilities",
    "WP-212": "LogRedaction",
    "WP-213": "MentionUtilities",
    "WP-310": "ChannelJoinFloodScopeApplier",
    "WP-311": "PingHelper QRCodeGenerator",
    "WP-314": "RepeaterResolver",
    "WP-217": "DemoModeManager",
    "WP-207": "EventBroadcaster TimeoutUtility AsyncSemaphore",
    "WP-218": "ImageURLClassifier ImageByteCost MalwareDomainFilter URLSafetyChecker",
    "WP-316": "FirmwareSuggestedTimeout",
    "WP-405": "MeshCoreURLParser MeshCoreURIQuery",
})

TEST_STEMS = groups({
    "WP-101": "DataExtensionsTests DecodePathLenTests MeshTransportDefaultsTests",
    "WP-102": "ChannelCryptoTests DirectMessageCryptoTests Ed25519ToX25519Tests",
    "WP-103": """
        AckParsingTests ContactNameDecodingTests FloodScopeMappingTests LoginSuccessParserTests
        RegionTests RxLogParserTests RxLogTypesTests TransportCodeRegionResolverTests
        DeviceInfoParsingTests DiscoverResponseParsingTests NewResponseParsingTests
        PathDiscoveryParsingTests ProtocolBugFixTests PythonReferenceTests RawDataParsingTests
        TelemetryParsingTests TraceDataParsingTests V115ParsingTests
    """,
    "WP-104": "FactoryResetTests NewCommandsTests UpdateContactTests V112ProtocolTests V115CommandsTests RoundTripTests",
    "WP-105": "LPPPythonReferenceTests",
    "WP-106": "EventDispatcherDropTests EventDispatcherFilteredSubscriptionTests EventFilterAnyAcknowledgementTests EventFilterFactoryTests MeshEventErrorCodeTests",
    "WP-107": """
        AutoContactRefreshTests AutoMessageFetchTests BinaryRequestTimeoutTests ConnectionStateTests
        GetMessageCoalescingTests GetMessageSerializationTests GetMessageTimeoutTests
        MeshCoreSessionCommandCorrelationTests MeshCoreSessionGetChannelsTests
        OtherParamsSerializationTests V115GetMessageTests V115SessionMethodsTests
        MeshCoreSessionStopTests
    """,
    "WP-108": "WiFiFrameCodecTests WiFiTransportTests",
    "WP-201": """
        ChannelFloodScopeTests ChannelRegionScopeTests ContactDTOPathTests DeviceDTOClientRepeatTests
        DeviceIdentityTests DevicePublicKeyDeduplicationTests FailedSendConversationKeysTests
        FirmwareVersionComparisonTests MessageDTOPathTests NodeLocationFixTests ProtocolLimitsTests
        RegionScopeSemanticsTests VContactIdentityTests ConnectionMethodTests
    """,
    "WP-202": """
        BlockedChannelSenderPersistenceTests BlockedSenderMessageDeletionTests
        ChannelFloodScopeMigrationTests DiscoveredNodeUnsignedFetchTests IdentityReconciliationIntegrationTests
        PendingSendPersistenceTests RadioIDMigrationTests RepeaterUnreadMigrationTests
        SortDateBackfillMigrationTests SortDateResetMigrationTests
        PersistenceStoreBatchSyncTests PersistenceStoreMessageWindowTests PersistenceStoreTests
    """,
    "WP-203": "AppBackupEnvelopeTests AppBackupServiceTests BackupIntegrationTests BackupUserDefaultsCoverageTests",
    "WP-204": "DevicePreferenceStoreTests KeyGenerationServiceTests PersistenceKeysThemeTests",
    "WP-205": """
        BLEPhaseTests BLEStateMachineAutoReconnectRetryTests BLEStateMachineBondSuspectRecoveryTests
        BLEStateMachineDisconnectionMappingTests BLEStateMachineEmptyGATTHoldTests
        BLEStateMachineFringeEncryptionGraceTests BLEStateMachineRestorationAndTeardownTests
        BLEStateMachineTests BLEStateMachineWriteWithoutResponseTests BondShieldRefreshTests
    """,
    "WP-206": """
        AccessorySetupKitDiscoveryCriteriaTests BluetoothScanPairingServiceTests
        BondLossPairingRecoveryTests ConnectionManagerAuthFailureRoutingTests
        ConnectionManagerBLEHealthTests ConnectionManagerBLEScanningTests ConnectionManagerPairingTests
        ConnectionManagerPromotionTests PairingCancellationTests PairingRaceIntegrationTests
        PairingStrandedAssociationTests PairingWhileConnectedTests
    """,
    "WP-207": """
        BLEReconnectionCoordinatorTests ConnectRadioIDResolutionTests ConnectionIntentTests
        ConnectionManagerAutoReconnectEntryTests ConnectionManagerCircuitBreakerTests
        ConnectionManagerDisconnectDiagnosticsTests ConnectionManagerReconnectAbandonmentTests
        ConnectionManagerRetryBudgetTests ConnectionManagerSessionTests DeviceConnectionStateTests
        EventBroadcasterTests LastConnectionStoreTests ReconnectPolicyTests
        ReconnectRebuildLifecycleTests
    """,
    "WP-208": """
        AckCodeBuilderTests BLETransportOpenedSignalTests ChannelRXCorrelationTests
        ChatSendQueueServiceAttemptCountTests ChatSendQueueServiceTests DeduplicationKeyTests
        MessageDeduplicationTests MessageServiceACKTests MessageServiceConfigTests
        MessageServiceListenerIntegrationTests MessageServiceSendDMBookkeepingTests
        MessageServiceSendTests MessageServiceTests
    """,
    "WP-209": """
        AdvertisementServiceTests ChannelFloodScopeResolverTests ChannelServicePipelineTests
        ChannelServiceTests ContactServiceSyncTests ContactServiceTests ContactShareUtilitiesTests
        HashtagUtilitiesTests
    """,
    "WP-210": """
        CLIResponseTests LoginTimeoutConfigTests NodeConfigImportPlannerTests NodeConfigServiceTests
        NodeConfigTests NodeSettingsResponseParserTests NodeSnapshotServiceTests
        RemoteCLICommandRewriterTests RemoteNodeCLICorrelationTests RemoteNodeKeepAliveTests
        RemoteNodeLoginHealTests RemoteNodePathRecoveryTests RemoteNodeTeardownTests
    """,
    "WP-211": """
        ContactOCVTests KnownRegionTests OCVPresetTests RadioPresetRecommendationTests
        RegionSelectionTests RegionalAreasTests RepeatPresetTests SettingsServiceApplyPresetTests
        SettingsServiceClockTests SettingsServiceDefaultFloodScopeTests
        SettingsServiceEventStreamTests SettingsServiceLocationTests
    """,
    "WP-212": """
        DebugLogBufferTests DebugLogRetentionPruneTests PersistentLoggerTests RFCalculatorTests
        RFPathAnalysisCharacterizationTests RxLogServiceAdvertHopTests RxLogServiceRegionReprocessTests
        RxLogServiceReprocessTests SegmentAnalysisTests
    """,
    "WP-213": """
        ChatCoordinatorRegistryOfflineTests ChatCoordinatorRegistryTests ChatCoordinatorTests
        ChatTimelineWriterTests DraftStoreTests EnvInputsThemeTokenTests InlineImageDimensionsStoreTests
        MessageLRUCacheTests ChatRenderStateTests MessageFootprintHashTests
        MessageFragmentBuilderFixtures MessageFragmentBuilderOffMainTests MessageFragmentBuilderTests
    """,
    "WP-214": """
        ConnectionManagerResyncLoopTests DevicePlatformSyncThrottlingTests SyncCoordinatorChannelSkipTests
        SyncCoordinatorDataEventTests SyncCoordinatorMessageHandlerTests SyncCoordinatorTests
        SyncCoordinatorTimestampTests
    """,
    "WP-215": "NotificationActionHandlerTests NotificationServiceTests NotificationStringProviderTests",
    "WP-216": "HeardRepeatsServiceTests MeshCoreOpenReactionParserTests ReactionParserTests ReactionServiceTests",
    "WP-217": "DemoModeManagerTests SimulatorSeedTests",
    "WP-218": """
        DecodedPreviewCacheTests ElevationServiceTests ImageHeaderDecoderTests ImageURLDetectorTests
        InlineImageCacheTests InlineImagePrefetcherTests LinkPreviewCacheTests LinkPreviewPreferencesTests
        LinkPreviewServiceTests MalwareDomainFilterTests RegionResolverTests URLSafetyCheckerTests
    """,
    "WP-301": """
        AppColorSchemePreferenceTests AppStateThemeWiringTests AppThemeEnvironmentTests
        IdentityGamutTests MessageTextThemeTests ThemeContrastTests ThemeLocalizedNameTests
        ThemeRegistryTests ThemeServiceTests ThemeStructureTests
    """,
    "WP-302": "NavigationCoordinatorTests NavigationStateTests SidebarNavigationLayoutTests",
    "WP-303": """
        AppStateEnvironmentDefaultTests AppStateRegionTests AuthenticationFailureGatingTests
        BatteryMonitoringTests ChannelSlotOccupantChangedTests ChatCoordinatorRegistrySurvivalTests
        ChatPrewarmRefresherTests ChatTimelineFreshnessTests ChatTimelinePrimerTests
        ConnectionManagerDeleteDeviceTests ConnectionUIStateTests DisconnectedPillTests
        FreshPairingFailureRoutingTests LifecycleTransitionTests MessageEventStreamTests
        SavedDeviceConnectFailureRoutingTests ServiceContainerWiringTests StatusPillStateTests
        SystemPairingSetupAppStateTests DevicePlatformTests
    """,
    "WP-304": """
        AvatarCropGeometryTests BatteryInfoDisplayTests BatteryPercentageCalculationTests
        ErrorDispatchCoverageTests ErrorLocalizationTests ErrorUserFacingMessageTests
        MC1ServicesTests RSSIScanTrackerTests RSSITuningTests RelativeTimestampTextTests
        SyncingPillViewTests ThemedSurfaceRowFillTests WiFiHostValidationTests
    """,
    "WP-305": "OnboardingStateTests",
    "WP-306": """
        ChatConversationTypeTests ChatLinkRouterTests ChatViewModelConversationTests
        ContactMatchRowTests ConversationFilteringTests ConversationListScrollPerfHarnessTests
        SenderContactMatcherTests
    """,
    "WP-307": """
        BubbleGestureTests ChatConversationViewTests ChatInitialScrollPolicyTests ChatKeyboardLiftTests
        ChatOpenAtDividerCompositionTests ChatReconnectPopulateTests ChatTiledViewScrollRequestTests
        ChatTimelineClobberRegressionTests ChatTimelineTests ChatViewModelAdmissionTests
        ChatViewModelAppendRaceTests ChatViewModelCoordinatorSharingTests ChatViewModelDeleteSequencingTests
        ChatViewModelDraftRestoreTests ChatViewModelEventGuardTests ChatViewModelFailedSendTests
        ChatViewModelPaginationTests ChatViewModelPreviewSeedTests ChatViewModelReloadSerializationTests
        ChatViewModelTests FragmentLayoutTests IncomingAvatarClusterTests IncomingAvatarFlightTests
        IncomingAvatarIdentityTests IncomingAvatarJPEGStoreTests MessageBubbleConfigurationTests
        MessageBubblePredicateTests MessageBubbleViewTests MessageStatusTextTests PendingSendEnvelopeTests
        RoomMessageBubbleA11yLabelTests TiledViewInitialScrollTargetTests UnifiedMessageBubbleA11yLabelTests
    """,
    "WP-308": """
        ChatCoordinateDetectorTests ChatInputBarThemedChromeTests ChatShareMenuTests
        MentionInsertionTests MentionUtilitiesTests MessageLinkAccessibilityTests MessageLinkTokenizerTests
        MessageTextContactShareTests MessageTextTests TiledViewInputBarChromeTests
    """,
    "WP-309": """
        ChatViewModelReactionIndexingTests MessageActionAvailabilityTests MessagePathArrivalTests
        MessagePathDetailSelectionTests MessagePathFormatterTests MessagePathPreviewMapTests
        MessagePathPreviewSnapshotTests MessagePathViewModelTests ReactionDetailsSelectionTests RepeatRowViewTests
    """,
    "WP-310": "ChannelInfoRegionQueryTargetsTests RegionNameValidatorTests RoomConversationViewModelOrderingTests",
    "WP-311": """
        ContactShareContentTests ContactURIActivityItemTests ContactsViewModelDeleteTests
        ContactsViewModelTests DiscoveryViewModelTests PathManagementViewModelDiscoveryTests
        PathManagementViewModelEditingTests ScanContactQRFeedbackTests
    """,
    "WP-312": """
        LocationPathMapBuilderTests MapAppearanceTests MapCameraStoreTests MapFilterMigrationTests
        MapFilterStateTests MapNameLabelVisibilityTests MapOrnamentLayoutTests MapPointClusteringTests
        MapSnapshotStoreTests MapViewModelDiscoveredTests MapViewModelTests PinSpriteRendererSnapshotTests
        TracePathMapFilterTests
    """,
    "WP-313": """
        ChannelGroupTests LocationReportFormatTests NeighborSNRMapBuilderTests NodeContactInfoSectionTests
        NodeLocationCaptureTests NodeSettingsLateRecoveryTests NodeSettingsViewModelValidationTests
        RemoteNodeModelTests RemoteNodeStatusHandlerSurvivalTests RepeaterRegionEntryTests
        RepeaterSettingsViewModelRegionTests RepeaterStatusViewModelTests TelemetryHistoryOverviewViewModelTests
        TelemetryRowLabelTests
    """,
    "WP-314": "BatchTraceTests RepeaterResolverTests TracePathListenerTests TracePathViewModelTests",
    "WP-315": "ChartCoordinateSpaceTests FresnelZoneRendererTests LineOfSightViewModelTests",
    "WP-316": """
        CLICompletionEngineTests CLILocalCommandParserTests CLIToolViewModelLocalCommandsTests
        CLIToolViewModelTests NoiseFloorViewModelTests NodeCLIViewModelTests RxLogViewModelTests
    """,
    "WP-317": "DangerZoneViewModelForgetTests DeviceSelectionSheetTests PresetLocationPolicyTests",
    "WP-318": """
        AppBackupViewModelTests AppearanceSelectionTests ImportSuccessDroppedFooterTests
        NodeConfigImportViewModelTests WhatsNewCatalogTests WhatsNewStateTests WhatsNewVersionTests
    """,
    "WP-402": "LiveActivityManagerTests",
    "WP-404": """
        EntityIdentityTests IntentBridgeTests IntentErrorLocalizationTests IntentMetadataLocalizationTests
        OpenRadioStatusIntentTests SendAdvertIntentTests SendMessageIntentTests StatusQueryIntentTests
    """,
    "WP-405": "HashtagChannelNavigationTests MentionDeeplinkSupportTests MeshCoreURLParserTests PendingExternalURLTests",
    "WP-005": "AirtimePercentLabelTests RegionalSubdivisionLocalizationTests",
})

TEST_HELPERS = {
    "AppBackupEnvelope+Testing": "WP-203",
    "ImportResult+Testing": "WP-203",
    "ConnectionManager+Testing": "WP-207",
    "MessageService+Testing": "WP-208",
    "PersistenceStore+TestFetch": "WP-202",
    "PersistenceStore+Testing": "WP-202",
    "ChatViewModelDependencies+Testing": "WP-307",
    "MessageBubbleTestData": "WP-307",
    "IsolatedIncomingAvatarJPEGStoreTrait": "WP-307",
    "MockConfigurationSession": "WP-316",
    "PythonReferenceBytes": "WP-004",
    "MockAccessorySetupKitService": "WP-206",
    "ASAccessory+Testing": "WP-206",
    "MockAppStateProvider": "WP-201",
    "MockBLEStateMachine": "WP-205",
    "MockChannelService": "WP-209",
    "MockContactService": "WP-209",
    "MockMeshCoreSession": "WP-107",
    "MockMeshTransport": "WP-205",
    "MockMessagePollingService": "WP-208",
    "MockPersistenceStore": "WP-202",
    "BlockedChannelSenderDTO+Testing": "WP-201",
    "ChannelDTO+Testing": "WP-201",
    "ContactDTO+Testing": "WP-201",
    "DeviceDTO+Testing": "WP-201",
    "MessageDTO+Testing": "WP-201",
    "MessageRepeatDTO+Testing": "WP-201",
    "NodeStatusSnapshotDTO+Testing": "WP-201",
    "ReactionDTO+Testing": "WP-201",
    "RemoteNodeSessionDTO+Testing": "WP-201",
    "RoomMessageDTO+Testing": "WP-201",
    "SavedTracePathDTO+Testing": "WP-201",
}

BILLING_STEMS = {
    "StoreCatalog", "StoreLoadState", "StorePurchaseOutcome", "StoreService", "StoreServiceError",
    "StoreServiceError+UserFacingMessage", "PendingPurchase", "StoreState",
    "ContributionRow", "ThemeBundleCard", "PurchaseThankYouSheet", "ContributionsSection",
    "PendingPurchaseBanner", "RefundLinkSection", "ThemesPurchaseSection",
    "StoreCatalogTests", "StoreServiceErrorTests", "RefundLinkSectionTests",
    "StoreEntitlementFoldTests", "StoreKitTestAvailability", "StoreServiceTests",
    "StoreStateTests", "StoreKitTestPurchase",
}


def exclusion(path: str):
    name = PurePosixPath(path).name
    stem = PurePosixPath(path).stem
    if path in TRANSLATION_PATHS:
        return (
            "removed-translation", "WP-000",
            "USER-APPROVED scope removal: PORTING_PLAN.md section 2.2; exact original translation-only source/test/helper, not Apple glue or a port.",
            "No message translation/provider/model/UI implementation or future build/release requirement. Retain original inventory/cases/blobs; preserve original message text and mixed messaging/localization/backup/rendering behavior. Re-admission requires a new user request and scope/admission decision.",
        )
    if stem in BILLING_STEMS or name.endswith(".storekit"):
        return (
            "removed-billing", "WP-318",
            "Approved plan sections 2, 3.3 and 9 remove StoreKit, purchases, refunds and entitlements.",
            "No billing implementation; all ten themes unlocked. Retain non-purchase support links and license notices.",
        )
    if "/Resources/Generated/" in path:
        return (
            "generated-swift", "WP-005",
            "Approved plan section 4.2 distinguishes generated Swift output from authoritative localization inputs.",
            "Regenerate Android strings/plurals from all app/widget inputs; never hand-port generated L10n.swift.",
        )
    if (
        name == "Package.swift" or path.startswith(("fastlane/", "ci/"))
        or path in (".swiftformat", ".swiftlint.yml", "project.yml", "Makefile", "MeshCore/.gitignore")
        or path.startswith(".github/workflows/") or path == ".github/FUNDING.yml"
        or name in ("Info.plist", "Info-Debug.plist", "PrivacyInfo.xcprivacy")
        or stem == "SidebarBackgroundExtension" or stem == "MacOSPairingRecoveryTests"
        or path.endswith("/.gitkeep")
    ):
        adaptation = "WP-002"
        if path.startswith(".github/workflows/") or path in (".swiftformat", ".swiftlint.yml"):
            adaptation = "WP-003"
        elif path.startswith(("fastlane/", "ci/")):
            adaptation = "WP-506"
        elif path.startswith("MC1Widgets/"):
            adaptation = "WP-403"
        elif path.startswith("MC1Tests/"):
            adaptation = "WP-206" if "MacOS" in path else "WP-004"
        elif name in ("Info.plist", "Info-Debug.plist", "PrivacyInfo.xcprivacy"):
            adaptation = "WP-407"
        elif stem == "SidebarBackgroundExtension":
            adaptation = "WP-302"
        return (
            "apple-only-glue", adaptation,
            "Approved plan sections 3.3, 4.2 and 9 permit reviewed native adaptation of Apple build/platform glue, not loss of features.",
            "Do not copy Apple-specific glue. Extract its applicable behavior/permissions/notices and verify the native Android counterpart at the adaptation WP.",
        )
    return None


def kind(path: str) -> str:
    if path.endswith(".swift"):
        if "/Resources/Generated/" in path:
            return "generated"
        if PurePosixPath(path).name == "Package.swift":
            return "apple-glue"
        if path.startswith("MC1Tests/") or "/Tests/" in path:
            if any(segment in path for segment in ("/Helpers/", "/Mocks/", "/Fixtures/", "TestSupport/")):
                return "support"
            return "test"
        return "production"
    if path in ("LICENSE", "MeshCore/LICENSE") or path.startswith("MC1/Settings.bundle/"):
        return "license"
    if path.startswith(("MC1/Resources/", "MC1Widgets/Resources/", "AppIcon.icon/")):
        return "resource"
    return "reference"


def production_owner(path: str) -> str:
    if path in TRANSLATION_PATHS:
        raise PortError(f"User-excluded translation source has no active producer: {path}")
    stem = PurePosixPath(path).stem
    if path.startswith("MeshCore/Sources/"):
        if "/LPP/" in path:
            return "WP-105"
        if "/Events/" in path:
            return "WP-106"
        if "/Models/" in path:
            return "WP-101"
        if "/Session/" in path:
            return "WP-101" if stem in ("SessionConfiguration", "OtherParamsConfig") else "WP-107"
        if "/Protocols/" in path:
            return "WP-107"
        if "/Transport/" in path:
            return "WP-101" if stem == "MeshTransport" else "WP-108"
        if "/Protocol/" in path:
            if stem in ("ChannelCrypto", "DirectMessageCrypto", "Ed25519ToX25519"):
                return "WP-102"
            if stem == "PacketBuilder":
                return "WP-104"
            if stem in ("DataExtensions", "ErrorCode", "PacketCodes", "PacketSize", "PathEncoding"):
                return "WP-101"
            return "WP-103"
    if path.startswith("MC1Services/Sources/"):
        if "/Services/" in path:
            if stem not in SERVICE_STEMS:
                raise PortError(f"Unreviewed service source: {path}")
            return SERVICE_STEMS[stem]
        if "/Connection/" in path:
            if stem in ("ConnectionManager+Pairing", "ConnectionManager+WiFi", "ConnectionManager+BLE"):
                return "WP-206"
            if stem in ("DevicePlatform", "TransportType", "RemoveUnfavoritedResult"):
                return "WP-201"
            return "WP-207"
        if "/Transport/" in path:
            return "WP-207" if stem == "ReconnectPolicy" else "WP-205"
        if "/Sync/" in path:
            return "WP-214"
        if "/Simulator/" in path:
            return "WP-217"
        if "/RF/" in path:
            return "WP-212"
        if "/Models/Rendering/" in path:
            return "WP-213"
        if "/Models/" in path or "/DTOs/" in path:
            return "WP-201"
        if "/Protocols/Persistence/" in path or stem == "PersistenceStoreProtocol":
            return "WP-202"
        if "/Protocols/" in path:
            if stem == "NotificationStringProvider":
                return "WP-215"
            if stem == "RepeaterResolvable":
                return "WP-209"
            return "WP-201"
        if "/Utilities/" in path:
            if stem not in UTILITY_STEMS:
                raise PortError(f"Unreviewed service helper: {path}")
            return UTILITY_STEMS[stem]
        if "/Extensions/" in path:
            return {
                "Bundle+App": "WP-318", "CLLocationCoordinate2D+ValidFix": "WP-218",
                "Character+EmojiDetection": "WP-213", "Data+Extensions": "WP-201",
                "LPPDataPoint+Display": "WP-313", "LPPSensorType+LocaleUnits": "WP-313",
                "Locale+POSIX": "WP-201", "StatusResponse+Compatibility": "WP-210",
                "String+StableUUID": "WP-201", "TelemetryResponse+DataPoints": "WP-210",
            }[stem]
        if "/Errors/" in path:
            return {
                "AppBackupError": "WP-203", "BLEError": "WP-205", "ConnectionError": "WP-207",
                "MeshCoreError+LocalizedError": "WP-304", "PairingError": "WP-206",
                "ProtocolError": "WP-107", "RemoteNodeError": "WP-210",
                "SettingsServiceError": "WP-211", "WiFiTransportError+LocalizedError": "WP-108",
            }[stem]
        if stem == "ServiceContainer":
            return "WP-303"
        if stem == "MC1Services":
            return "WP-201"
    if path.startswith("Shared/"):
        return "WP-404" if stem == "OpenRadioStatusIntent" else "WP-402"
    if path.startswith("MC1Widgets/"):
        return "WP-402" if stem in ("MeshStatusLiveActivity", "LockScreenView") else "WP-403"
    if path.startswith("MC1/"):
        if "/Services/" in path:
            if stem not in APP_SERVICE_STEMS:
                raise PortError(f"Unreviewed app service: {path}")
            return APP_SERVICE_STEMS[stem]
        if "/Intents/" in path:
            return "WP-404"
        if "/Theme/" in path:
            return "WP-301"
        if "/Tips/" in path:
            return "WP-304"
        if "/Utilities/" in path:
            if stem not in UTILITY_STEMS:
                raise PortError(f"Unreviewed app helper: {path}")
            return UTILITY_STEMS[stem]
        if "/State/" in path:
            return {
                "AppTab": "WP-302", "NavigationCoordinator": "WP-302",
                "LiveActivityManager": "WP-402", "MapFocusRequest": "WP-312",
                "PendingExternalURL": "WP-405", "OnboardingState": "WP-305",
                "RestoreState": "WP-318", "WhatsNewState": "WP-318",
                "SystemPairedAccessory": "WP-206", "SystemPairingSetupPrompt": "WP-206",
            }.get(stem, "WP-303")
        if "/Models/WhatsNew/" in path:
            return "WP-318"
        if "/Models/" in path:
            return {
                "DevicePreferenceStore": "WP-204", "LinkPreviewPreferences": "WP-218",
                "ChatFilter": "WP-306", "Conversation+Filtering": "WP-306", "Conversation": "WP-306",
            }[stem]
        if "/Extensions/Errors/" in path:
            return "WP-304"
        if "/Extensions/" in path:
            if stem in ("Color+Hex", "SNRQuality+Color"):
                return "WP-301"
            if stem.startswith(("CLLocationCoordinate2D+",)) or stem.endswith("+Coordinate"):
                return "WP-312"
            if stem.startswith("LPPSensorType+"):
                return "WP-313"
            return "WP-304"
        if stem == "MC1App":
            return "WP-303"
        if stem == "ContentView" or "/Sidebar/" in path or stem in ("AppSidebar", "MainSidebarView"):
            return "WP-302"
        if "/Views/Appearance/" in path or "/Views/Support/" in path or "/Views/WhatsNew/" in path:
            return "WP-318"
        if "/Views/Onboarding/" in path:
            return "WP-305"
        if "/Views/Contacts/" in path or "/Views/PathEditing/" in path:
            return "WP-311"
        if "/Views/Map/" in path:
            return "WP-312"
        if "/Views/RemoteNodes/" in path:
            if stem.startswith(("RoomConversation", "RoomMessage", "RoomTiled")):
                return "WP-310"
            if stem.startswith("NodeCLI"):
                return "WP-316"
            return "WP-313"
        if "/Views/Tools/" in path:
            if "/CLI/" in path or stem.startswith(("NoiseFloor", "RxLog")):
                return "WP-316"
            return "WP-315" if "/LineOfSight/" in path else "WP-314"
        if "/Views/Settings/" in path:
            if stem in ("MapsSettingsView", "OfflineMapSettingsView"):
                return "WP-312"
            if any(word in stem for word in ("Backup", "Import", "Export", "About", "Appearance", "Feedback", "ActivityView")):
                return "WP-318"
            return "WP-317"
        if "/Views/Components/" in path:
            return "WP-304"
        if "/Views/Chats/" in path:
            if stem in ("HashtagDeeplinkSupport", "MentionDeeplinkSupport"):
                return "WP-405"
            if "/Room/" in path or stem in ("CreatePrivateChannelView", "ScanChannelQRView"):
                return "WP-310"
            if "/Sheets/" in path:
                if any(s in stem for s in ("BlockSender", "ConversationInfo")):
                    return "WP-309"
                return "WP-310"
            if "/Reactions/" in path:
                return "WP-308" if stem.startswith(("Emoji", "RecentEmojis")) else "WP-309"
            if "/Linkify/" in path or "/Mentions/" in path:
                return "WP-308"
            if "/Navigation/" in path:
                return "WP-306"
            if "/Timeline/" in path or "/ViewModel/" in path:
                return "WP-307"
            if any(s in stem for s in ("MessagePath", "PathHop", "RepeatDetails", "RepeatRow", "BubbleActions", "BubbleLongPress", "BubbleSecondaryClick")):
                return "WP-309"
            if any(s in stem for s in (
                "InputBar", "Compose", "Composer", "MessageText", "LinkPreview", "InlineImage",
                "FullScreenImage", "AnimatedGIF", "Mention", "MalwareWarning", "RichPreview",
                "TapToLoad", "PreviewSkeleton", "DecodedPreview", "ChatCoordinate", "ChatShare",
            )):
                return "WP-308"
            if "/Components/" in path or stem in (
                "ChatConversationView", "ChatConversationMessagesContent", "ChatPopulateMode", "FailedSendIndicator"
            ):
                return "WP-307"
            return "WP-306"
    raise PortError(f"Unreviewed production source: {path}")


def ownership(path: str, source_index: dict[str, set[str]]) -> tuple[str, list[str]]:
    stem = PurePosixPath(path).stem
    category = kind(path)
    references = set()
    if category == "production":
        owner = production_owner(path)
        if owner == "WP-303":
            references.add("WP-207")
        if owner == "WP-307" and stem.startswith("ChatViewModel"):
            references.update(("WP-306", "WP-308", "WP-309", "WP-310"))
        if "/Models/" in path:
            references.update(("WP-202", "WP-203"))
        if "/Errors/" in path:
            references.add("WP-304")
        if owner == "WP-212" and "/RF/" in path:
            references.add("WP-315")
        references.add("WP-501")
    elif category in ("test", "support"):
        if stem in TEST_HELPERS:
            owner = TEST_HELPERS[stem]
        elif category == "support" or stem.startswith(("Mock", "TestClock", "TestHelpers", "TestPolling")):
            owner = "WP-004"
        elif stem in TEST_STEMS:
            owner = TEST_STEMS[stem]
        else:
            matches = [
                (len(source), owners) for source, owners in source_index.items()
                if len(source) >= 5 and stem.startswith(source)
            ]
            if not matches:
                raise PortError(f"Unreviewed original test ownership: {path}")
            longest = max(length for length, _ in matches)
            owners = set().union(*(owners for length, owners in matches if length == longest))
            if len(owners) != 1:
                raise PortError(f"Ambiguous original test ownership: {path}: {sorted(owners)}")
            owner = owners.pop()
        references.add("WP-501")
        if category == "support" and owner == "WP-201":
            references.update(("WP-202", "WP-203", "WP-208", "WP-209", "WP-210", "WP-213", "WP-216"))
        if category == "support" and owner == "WP-004":
            references.update(("WP-107", "WP-109", "WP-207", "WP-208", "WP-213", "WP-303", "WP-307"))
        if stem == "PythonReferenceBytes":
            references.update(f"WP-{n}" for n in range(101, 110))
        if path.startswith("MeshCore/Tests/"):
            references.add("WP-109")
        if path.startswith("MC1Tests/Views/") or path.startswith("MC1Tests/ViewModels/"):
            references.add("WP-502")
        if "A11y" in stem or "Contrast" in stem or "Accessibility" in stem:
            references.add("WP-503")
        if "PerfHarness" in stem:
            references.add("WP-504")
    elif category == "license":
        owner = "WP-318"
        references.update(("WP-001", "WP-301", "WP-506"))
        if "urlhaus" in path:
            references.add("WP-218")
        if path == "MeshCore/LICENSE":
            references.update(f"WP-{n}" for n in range(101, 110))
    elif category == "resource":
        if "/Localization/" in path or path.startswith("MC1Widgets/Resources/") or path.endswith(".xcstrings"):
            owner = "WP-005"
            references.add("WP-403" if path.startswith("MC1Widgets/") else "WP-407")
            if path.endswith(".xcstrings"):
                references.add("WP-404")
            table = PurePosixPath(path).stem
            references.update({
                "Chats": ("WP-306", "WP-307", "WP-308", "WP-309", "WP-310"),
                "Contacts": ("WP-311",), "Map": ("WP-312",), "Onboarding": ("WP-305",),
                "RemoteNodes": ("WP-313", "WP-310"), "Settings": ("WP-317", "WP-318"),
                "Tools": ("WP-314", "WP-315", "WP-316"), "WhatsNew": ("WP-318",),
                "Localizable": ("WP-301", "WP-302", "WP-304", "WP-401"),
            }.get(table, ()))
        elif "/Styles/" in path:
            owner = "WP-312"
            references.update(("WP-314", "WP-315"))
        elif "urlhaus" in path:
            owner = "WP-218"
            references.add("WP-318")
        elif "/Assets.xcassets/" in path or path.startswith("AppIcon.icon/"):
            owner = "WP-301"
            references.update(("WP-407", "WP-503"))
        else:
            raise PortError(f"Unreviewed user-facing resource: {path}")
    else:
        if path.startswith(".github/"):
            owner = "WP-000"
        elif path in ("TRANSLATIONS.md", "swiftgen.yml"):
            owner = "WP-005"
        elif path == ".gitignore":
            owner = "WP-002"
        elif "Reactions" in path:
            owner = "WP-216"
        elif path.startswith("docs/guides/"):
            owner = {
                "BLE_Transport": "WP-205", "WiFi_Transport": "WP-108", "Diagnostics": "WP-212",
                "Messaging": "WP-208", "Sync": "WP-214", "iPad_Layout": "WP-302",
            }[stem]
        elif path.startswith("MeshCore/"):
            owner = "WP-109"
        elif path in ("docs/Architecture.md", "docs/Glossary.md"):
            owner = "WP-001"
        elif path in ("docs/Testing.md",):
            owner = "WP-004"
        elif path in ("docs/User_Guide.md", "docs/api/MC1.md", "docs/api/MC1Services.md"):
            owner = "WP-501"
        else:
            owner = "WP-506"
    references.discard(owner)
    return owner, sorted(references)
