// SyncCoordinatorChannelSkipTests.swift
import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("SyncCoordinator Channel Skip Tests")
struct SyncCoordinatorChannelSkipTests {
  private func createTestDataStore(
    radioID: UUID,
    maxChannels: UInt8 = 8,
    lastContactSync: UInt32 = 0
  ) async throws -> PersistenceStore {
    try await PersistenceStore.createTestDataStore(
      radioID: radioID,
      maxChannels: maxChannels,
      lastContactSync: lastContactSync
    )
  }

  // MARK: - Channel Skip Logic

  @Test
  @MainActor
  func `Channels skipped when lastCleanChannelSync is recent and skip window > 0`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: Date())
    )

    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.isEmpty, "Channel sync should be skipped when clean sync completed recently")
  }

  @Test
  @MainActor
  func `Channels sync when lastCleanChannelSync is nil`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30))
    )

    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.count == 1, "Channel sync should run when lastCleanChannelSync is nil")
  }

  @Test
  @MainActor
  func `Channels skipped when last attempted channel sync is recent`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      channelSyncConfig: ChannelSyncConfig(
        channelSyncSkipWindow: .seconds(30),
        lastAttemptedChannelSync: Date()
      )
    )

    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.isEmpty, "Channel sync should be skipped after a recent partial attempt")
  }

  @Test
  @MainActor
  func `Channels sync when lastCleanChannelSync is expired (outside window)`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let expiredDate = Date().addingTimeInterval(-60) // 60s ago, outside 30s window

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: expiredDate)
    )

    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.count == 1, "Channel sync should run when lastCleanChannelSync is expired")
  }

  @Test
  @MainActor
  func `forceFullSync bypasses channel skip`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
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

  @Test
  @MainActor
  func `Zero skip window disables skip`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
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

  @Test
  @MainActor
  func `Callback fires on clean channel phase (zero errors)`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    // Channel sync returns success (no errors)
    await mockChannelService.setStubbedSyncChannelsResult(.success(
      ChannelSyncResult(channelsSynced: 8, errors: [])
    ))

    let callbackTracker = CallTracker()
    await coordinator.setCleanChannelSyncCallback { radioID in
      #expect(radioID == testDeviceID)
      callbackTracker.markCalled()
    }

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(callbackTracker.wasCalled, "onCleanChannelSync should fire when channel phase is clean")
  }

  @Test
  @MainActor
  func `Callback fires when initial sync fails but retry recovers`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

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
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(callbackTracker.wasCalled, "onCleanChannelSync should fire when retry recovers all errors")
  }

  @Test
  @MainActor
  func `Callback does not fire when channel sync has errors after retries`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

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
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(!callbackTracker.wasCalled, "onCleanChannelSync should not fire when errors remain after retry")
  }

  @Test
  @MainActor
  func `Callback does not fire with mixed retryable and non-retryable errors even when retry succeeds`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

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
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(!callbackTracker.wasCalled, "onCleanChannelSync must not fire when non-retryable errors remain unresolved")
  }

  @Test
  @MainActor
  func `Callback does not fire when channels are skipped`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let callbackTracker = CallTracker()
    await coordinator.setCleanChannelSyncCallback { _ in
      callbackTracker.markCalled()
    }

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      channelSyncConfig: ChannelSyncConfig(channelSyncSkipWindow: .seconds(30), lastCleanChannelSync: Date())
    )

    #expect(!callbackTracker.wasCalled, "onCleanChannelSync should not fire when channels are skipped")
  }

  @Test
  @MainActor
  func `Callback does not fire when initial sync is clean but channels skipped in background`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let mockAppStateProvider = MockAppStateProvider(isInForeground: false)
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let callbackTracker = CallTracker()
    await coordinator.setCleanChannelSyncCallback { _ in
      callbackTracker.markCalled()
    }

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      appStateProvider: mockAppStateProvider
    )

    #expect(!callbackTracker.wasCalled, "onCleanChannelSync should not fire when channels are skipped in background")
  }

  // MARK: - Post-sync diagnostics still run when channels skipped

  @Test
  @MainActor
  func `Post-sync diagnostics still run when channels are skipped`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    // Skip channels (recent clean sync) -- performFullSync should still complete
    // because logPostSyncChannelDiagnostics and refreshRxLogChannels read from the
    // database (not the mock), so they execute regardless of whether channels were skipped.
    try await coordinator.performFullSync(
      radioID: testDeviceID,
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
