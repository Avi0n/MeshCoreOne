// SyncCoordinatorChannelSkipTests.swift
import Testing
import Foundation
import MeshCore
import MeshCoreTestSupport
@testable import MC1Services

@Suite("SyncCoordinator Channel Skip Tests")
struct SyncCoordinatorChannelSkipTests {

    private func createTestDataStore(
        deviceID: UUID,
        maxChannels: UInt8 = 8,
        lastContactSync: UInt32 = 0
    ) async throws -> PersistenceStore {
        try await PersistenceStore.createTestDataStore(
            deviceID: deviceID,
            maxChannels: maxChannels,
            lastContactSync: lastContactSync
        )
    }

    // MARK: - Channel Skip Logic

    @Test("Channels skipped when lastCleanChannelSync is recent and skip window > 0")
    @MainActor
    func channelsSkippedWhenRecentCleanSync() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: Date())
        )

        let channelInvocations = await mockChannelService.syncChannelsInvocations
        #expect(channelInvocations.isEmpty, "Channel sync should be skipped when clean sync completed recently")
    }

    @Test("Channels sync when lastCleanChannelSync is nil")
    @MainActor
    func channelsSyncWhenNoLastCleanSync() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30))
        )

        let channelInvocations = await mockChannelService.syncChannelsInvocations
        #expect(channelInvocations.count == 1, "Channel sync should run when lastCleanChannelSync is nil")
    }

    @Test("Channels sync when lastCleanChannelSync is expired (outside window)")
    @MainActor
    func channelsSyncWhenExpired() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        let expiredDate = Date().addingTimeInterval(-60)  // 60s ago, outside 30s window

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: expiredDate)
        )

        let channelInvocations = await mockChannelService.syncChannelsInvocations
        #expect(channelInvocations.count == 1, "Channel sync should run when lastCleanChannelSync is expired")
    }

    @Test("forceFullSync bypasses channel skip")
    @MainActor
    func forceFullSyncBypassesSkip() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            forceFullSync: true,
            channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: Date())
        )

        let channelInvocations = await mockChannelService.syncChannelsInvocations
        #expect(channelInvocations.count == 1, "Channel sync should run when forceFullSync is true")
    }

    @Test("Zero skip window disables skip")
    @MainActor
    func zeroSkipWindowDisablesSkip() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            channelSyncConfig: ChannelSyncConfig(lastCleanChannelSync: Date())
        )

        let channelInvocations = await mockChannelService.syncChannelsInvocations
        #expect(channelInvocations.count == 1, "Channel sync should run when skip window is zero")
    }

    // MARK: - Clean Channel Callback

    @Test("Callback fires on clean channel phase (zero errors)")
    @MainActor
    func callbackFiresOnCleanPhase() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        // Channel sync returns success (no errors)
        await mockChannelService.setStubbedSyncChannelsResult(.success(
            ChannelSyncResult(channelsSynced: 8, errors: [])
        ))

        let callbackTracker = CallTracker()
        await coordinator.setCleanChannelSyncCallback { deviceID in
            #expect(deviceID == testDeviceID)
            callbackTracker.markCalled()
        }

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService
        )

        #expect(callbackTracker.wasCalled, "onCleanChannelSync should fire when channel phase is clean")
    }

    @Test("Callback fires when initial sync fails but retry recovers")
    @MainActor
    func callbackFiresWhenRetryRecovers() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        // Initial sync has errors, but retry succeeds
        let errors = [ChannelSyncError(index: 2, errorType: .timeout, description: "timeout")]
        await mockChannelService.setStubbedSyncChannelsResult(.success(
            ChannelSyncResult(channelsSynced: 7, errors: errors)
        ))
        await mockChannelService.setStubbedRetryResult(.success(
            ChannelSyncResult(channelsSynced: 1, errors: [])
        ))

        let callbackTracker = CallTracker()
        await coordinator.setCleanChannelSyncCallback { _ in
            callbackTracker.markCalled()
        }

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService
        )

        #expect(callbackTracker.wasCalled, "onCleanChannelSync should fire when retry recovers all errors")
    }

    @Test("Callback does not fire when channel sync has errors after retries")
    @MainActor
    func callbackDoesNotFireWithErrors() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        // Initial sync has errors, retry also has errors
        let errors = [ChannelSyncError(index: 2, errorType: .timeout, description: "timeout")]
        await mockChannelService.setStubbedSyncChannelsResult(.success(
            ChannelSyncResult(channelsSynced: 7, errors: errors)
        ))
        let retryErrors = [ChannelSyncError(index: 2, errorType: .timeout, description: "still failing")]
        await mockChannelService.setStubbedRetryResult(.success(
            ChannelSyncResult(channelsSynced: 0, errors: retryErrors)
        ))

        let callbackTracker = CallTracker()
        await coordinator.setCleanChannelSyncCallback { _ in
            callbackTracker.markCalled()
        }

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService
        )

        #expect(!callbackTracker.wasCalled, "onCleanChannelSync should not fire when errors remain after retry")
    }

    @Test("Callback does not fire with mixed retryable and non-retryable errors even when retry succeeds")
    @MainActor
    func callbackDoesNotFireWithMixedErrors() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        // Initial sync: one non-retryable deviceError + one retryable timeout
        let errors = [
            ChannelSyncError(index: 5, errorType: .deviceError(code: 3), description: "device error"),
            ChannelSyncError(index: 10, errorType: .timeout, description: "timeout"),
        ]
        await mockChannelService.setStubbedSyncChannelsResult(.success(
            ChannelSyncResult(channelsSynced: 6, errors: errors)
        ))
        // Retry succeeds for the retryable timeout (index 10), but deviceError (index 5) was never retried
        await mockChannelService.setStubbedRetryResult(.success(
            ChannelSyncResult(channelsSynced: 1, errors: [])
        ))

        let callbackTracker = CallTracker()
        await coordinator.setCleanChannelSyncCallback { _ in
            callbackTracker.markCalled()
        }

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService
        )

        #expect(!callbackTracker.wasCalled, "onCleanChannelSync must not fire when non-retryable errors remain unresolved")
    }

    @Test("Callback does not fire when channels are skipped")
    @MainActor
    func callbackDoesNotFireWhenSkipped() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        let callbackTracker = CallTracker()
        await coordinator.setCleanChannelSyncCallback { _ in
            callbackTracker.markCalled()
        }

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: Date())
        )

        #expect(!callbackTracker.wasCalled, "onCleanChannelSync should not fire when channels are skipped")
    }

    @Test("Callback does not fire when initial sync is clean but channels skipped in background")
    @MainActor
    func callbackDoesNotFireInBackground() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let mockAppStateProvider = MockAppStateProvider(isInForeground: false)
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        let callbackTracker = CallTracker()
        await coordinator.setCleanChannelSyncCallback { _ in
            callbackTracker.markCalled()
        }

        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            appStateProvider: mockAppStateProvider
        )

        #expect(!callbackTracker.wasCalled, "onCleanChannelSync should not fire when channels are skipped in background")
    }

    // MARK: - Post-sync diagnostics still run when channels skipped

    @Test("Post-sync diagnostics still run when channels are skipped")
    @MainActor
    func diagnosticsRunWhenChannelsSkipped() async throws {
        let coordinator = SyncCoordinator()
        let mockContactService = MockContactService()
        let mockChannelService = MockChannelService()
        let mockMessagePollingService = MockMessagePollingService()
        let testDeviceID = UUID()
        let dataStore = try await createTestDataStore(deviceID: testDeviceID)

        // Skip channels (recent clean sync) -- performFullSync should still complete
        // because logPostSyncChannelDiagnostics and refreshRxLogChannels read from the
        // database (not the mock), so they execute regardless of whether channels were skipped.
        try await coordinator.performFullSync(
            deviceID: testDeviceID,
            dataStore: dataStore,
            contactService: mockContactService,
            channelService: mockChannelService,
            messagePollingService: mockMessagePollingService,
            channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: Date())
        )

        let channelInvocations = await mockChannelService.syncChannelsInvocations
        #expect(channelInvocations.isEmpty, "Channel sync should be skipped")

        // Sync should complete successfully (state == .synced) even with skipped channels
        #expect(coordinator.state == .synced, "Sync should complete successfully when channels are skipped")
    }

}

// MARK: - Mock Helper Extensions

extension MockChannelService {
    func setStubbedSyncChannelsResult(_ result: Result<ChannelSyncResult, Error>) {
        stubbedSyncChannelsResult = result
    }

    func setStubbedRetryResult(_ result: Result<ChannelSyncResult, Error>) {
        stubbedRetryResult = result
    }
}
