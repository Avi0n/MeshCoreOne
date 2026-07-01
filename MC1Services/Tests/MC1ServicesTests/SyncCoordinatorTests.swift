// SyncCoordinatorTests.swift
import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("SyncCoordinator Tests")
struct SyncCoordinatorTests {
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

  @Test
  func `SyncState cases are distinct`() {
    let idle = SyncState.idle
    let syncing = SyncState.syncing(progress: SyncProgress(phase: .contacts, current: 0, total: 0))
    let synced = SyncState.synced
    let failed = SyncState.failed(SyncCoordinatorError.notConnected)

    // Verify they're not equal
    #expect(idle != syncing)
    #expect(syncing != synced)
    #expect(synced != failed)
  }

  @Test
  func `SyncProgress initializes correctly`() {
    let progress = SyncProgress(phase: .contacts, current: 5, total: 10)
    #expect(progress.phase == .contacts)
    #expect(progress.current == 5)
    #expect(progress.total == 10)
  }

  @Test
  func `SyncPhase has all expected cases`() {
    let phases: [SyncPhase] = [.contacts, .channels, .messages]
    #expect(phases.count == 3)
  }

  @Test
  @MainActor
  func `SyncCoordinator initializes with idle state`() {
    let coordinator = SyncCoordinator()
    #expect(coordinator.state == .idle)
    #expect(coordinator.contactsVersion == 0)
    #expect(coordinator.conversationsVersion == 0)
    #expect(coordinator.lastSyncDate == nil)
  }

  @Test
  @MainActor
  func `notifyContactsChanged increments contactsVersion`() async {
    let coordinator = SyncCoordinator()
    let initialVersion = coordinator.contactsVersion

    await coordinator.notifyContactsChanged()

    #expect(coordinator.contactsVersion == initialVersion + 1)
  }

  @Test
  @MainActor
  func `notifyConversationsChanged increments conversationsVersion`() async {
    let coordinator = SyncCoordinator()
    let initialVersion = coordinator.conversationsVersion

    await coordinator.notifyConversationsChanged()

    #expect(coordinator.conversationsVersion == initialVersion + 1)
  }

  @Test
  @MainActor
  func `Multiple notifications increment correctly`() async {
    let coordinator = SyncCoordinator()

    await coordinator.notifyContactsChanged()
    await coordinator.notifyContactsChanged()
    await coordinator.notifyConversationsChanged()

    #expect(coordinator.contactsVersion == 2)
    #expect(coordinator.conversationsVersion == 1)
  }

  @Test
  @MainActor
  func `Sync activity callbacks fire during full sync`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let startedTracker = CallTracker()
    let endedTracker = CallTracker()

    await coordinator.setSyncActivityCallbacks(
      onStarted: { startedTracker.markCalled() },
      onEnded: { _ in endedTracker.markCalled() },
      onPhaseChanged: { _ in }
    )

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(startedTracker.wasCalled, "onSyncActivityStarted should have been called")
    #expect(endedTracker.wasCalled, "onSyncActivityEnded should have been called")
  }

  @Test
  @MainActor
  func `Channel phase failure is partial and keeps connection usable`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    await mockChannelService.setStubbedSyncChannelsResult(.failure(
      ChannelServiceError.circuitBreakerOpen(consecutiveFailures: 3)
    ))

    let result = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(result.contacts == .clean)
    #expect(result.channels == .partial)
    #expect(result.messages == .clean)
    #expect(result.isConnectionUsable)
    #expect(coordinator.state == .synced)
    #expect(await mockMessagePollingService.pollAllMessagesCallCount == 1)
  }

  @Test
  @MainActor
  func `Message polling failure does not fail contacts and channels`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    await mockMessagePollingService.setStubbedPollAllMessagesResult(.failure(
      SyncCoordinatorError.syncFailed("messages saturated")
    ))

    let result = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(result.contacts == .clean)
    #expect(result.channels == .clean)
    guard case let .failed(reason) = result.messages else {
      Issue.record("Expected failed message phase, got \(result.messages)")
      return
    }
    #expect(reason.localizedStandardContains("messages saturated"))
    #expect(result.isConnectionUsable)
    #expect(coordinator.state == .synced)
  }

  @Test
  @MainActor
  func `Sync activity callbacks not double called on error`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let endedTracker = CallTracker()

    await coordinator.setSyncActivityCallbacks(
      onStarted: {},
      onEnded: { _ in endedTracker.markCalled() },
      onPhaseChanged: { _ in }
    )

    // Configure mock to throw error during contacts sync
    await mockContactService.setStubbedSyncContactsResult(.failure(SyncCoordinatorError.syncFailed("Test error")))

    do {
      try await coordinator.performFullSync(
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: mockContactService,
        channelService: mockChannelService,
        messagePollingService: mockMessagePollingService
      )
      Issue.record("Should have thrown error")
    } catch {
      // Expected
    }

    #expect(endedTracker.callCount == 1, "onSyncActivityEnded should be called exactly once on error")
  }

  @Test
  @MainActor
  func `Sync activity ends before messages phase`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let orderTracker = OrderTrackingMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    await coordinator.setSyncActivityCallbacks(
      onStarted: {},
      onEnded: { _ in
        // Record when activity ended
        await orderTracker.recordActivityEnded()
      },
      onPhaseChanged: { _ in }
    )

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: orderTracker
    )

    // Verify that activity ended before message polling started
    let activityEndedBeforeMessages = await orderTracker.activityEndedBeforeMessagePoll
    #expect(activityEndedBeforeMessages, "Activity should end before message polling starts")
  }

  @Test
  @MainActor
  func `onDisconnected clears notification suppression flag`() async throws {
    let coordinator = SyncCoordinator()

    // Create a test ServiceContainer
    let mockTransport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: mockTransport)
    let services = try await ServiceContainer.forTesting(session: session)

    // Manually set suppression flag to true (simulating mid-sync state)
    services.notificationService.isSuppressingNotifications = true
    #expect(services.notificationService.isSuppressingNotifications == true)

    // Call onDisconnected
    await coordinator.onDisconnected(notificationService: services.notificationService)

    // Verify flag is cleared
    #expect(services.notificationService.isSuppressingNotifications == false)
  }

  @Test
  @MainActor
  func `onDisconnected resets sync state to idle`() async throws {
    let coordinator = SyncCoordinator()

    // Create a test ServiceContainer
    let mockTransport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: mockTransport)
    let services = try await ServiceContainer.forTesting(session: session)

    // Call onDisconnected
    await coordinator.onDisconnected(notificationService: services.notificationService)

    // Verify state is idle
    #expect(coordinator.state == .idle)
  }

  @Test
  @MainActor
  func `onDisconnected calls onSyncActivityEnded when mid-sync in contacts phase`() async throws {
    let coordinator = SyncCoordinator()
    let delayingContactService = DelayingContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    // Create a test ServiceContainer
    let mockTransport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: mockTransport)
    let services = try await ServiceContainer.forTesting(session: session)

    let startedTracker = CallTracker()
    let endedTracker = CallTracker()

    await coordinator.setSyncActivityCallbacks(
      onStarted: { startedTracker.markCalled() },
      onEnded: { _ in endedTracker.markCalled() },
      onPhaseChanged: { _ in }
    )

    // Start sync in background task - it will block during contacts phase
    let syncTask = Task {
      try await coordinator.performFullSync(
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: delayingContactService,
        channelService: mockChannelService,
        messagePollingService: mockMessagePollingService
      )
    }

    // Wait for sync to start (activity started callback)
    try await waitUntil("Sync activity should have started") {
      startedTracker.wasCalled
    }
    #expect(startedTracker.wasCalled, "Sync activity should have started")

    // Call onDisconnected while sync is in contacts phase
    await coordinator.onDisconnected(notificationService: services.notificationService)

    // Verify onSyncActivityEnded was called by onDisconnected
    #expect(endedTracker.wasCalled, "onSyncActivityEnded should be called when disconnecting mid-sync")

    // Cleanup: resume the sync so it doesn't hang
    await delayingContactService.completeSync()
    syncTask.cancel()
  }

  @Test
  @MainActor
  func `Background sync skips channel sync`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let mockAppStateProvider = MockAppStateProvider(isInForeground: false)
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      appStateProvider: mockAppStateProvider
    )

    // Channel sync should be skipped in background
    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.isEmpty, "Channel sync should be skipped when in background")

    // Contact sync should still happen
    let contactInvocations = await mockContactService.syncContactsInvocations
    #expect(contactInvocations.count == 1, "Contact sync should still run in background")
  }

  @Test
  @MainActor
  func `Foreground sync includes channel sync`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let mockAppStateProvider = MockAppStateProvider(isInForeground: true)
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      appStateProvider: mockAppStateProvider
    )

    // Channel sync should run in foreground
    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.count == 1, "Channel sync should run when in foreground")

    // Contact sync should also run
    let contactInvocations = await mockContactService.syncContactsInvocations
    #expect(contactInvocations.count == 1, "Contact sync should run in foreground")
  }

  @Test
  @MainActor
  func `performFullSync forwards the pipelined-read flag from config into channel sync`() async throws {
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
      appStateProvider: nil,
      channelSyncConfig: ChannelSyncConfig(usePipelinedChannelRead: true)
    )

    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.count == 1)
    #expect(channelInvocations.last?.usePipelinedRead == true, "Config flag should reach channel sync")
  }

  @Test
  @MainActor
  func `performFullSync defaults channel sync to the serial read path`() async throws {
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
      appStateProvider: nil
    )

    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.count == 1)
    #expect(channelInvocations.last?.usePipelinedRead == false, "Default config should use the serial path")
  }

  @Test
  @MainActor
  func `Nil appStateProvider defaults to foreground behavior`() async throws {
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
      appStateProvider: nil
    )

    // Should default to foreground (run channels)
    let channelInvocations = await mockChannelService.syncChannelsInvocations
    #expect(channelInvocations.count == 1, "Nil appStateProvider should default to foreground behavior")

    // Contact sync should also run
    let contactInvocations = await mockContactService.syncContactsInvocations
    #expect(contactInvocations.count == 1, "Contact sync should run with nil appStateProvider")
  }

  @Test
  @MainActor
  func `performFullSync ignores duplicate calls when already syncing`() async throws {
    let coordinator = SyncCoordinator()
    let delayingContactService = DelayingContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let startedTracker = CallTracker()

    await coordinator.setSyncActivityCallbacks(
      onStarted: { startedTracker.markCalled() },
      onEnded: { _ in },
      onPhaseChanged: { _ in }
    )

    // Start first sync in background - it will block during contacts phase
    let firstSyncTask = Task {
      try await coordinator.performFullSync(
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: delayingContactService,
        channelService: mockChannelService,
        messagePollingService: mockMessagePollingService
      )
    }

    // Wait for first sync to start
    try await waitUntil("First sync should have started") {
      startedTracker.callCount >= 1
    }

    // Try to start a second sync while first is still running
    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: delayingContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    // Verify onSyncActivityStarted was only called once (not twice)
    #expect(startedTracker.callCount == 1, "onSyncActivityStarted should only be called once even with duplicate performFullSync calls")

    // Cleanup
    await delayingContactService.completeSync()
    firstSyncTask.cancel()
  }

  @Test
  @MainActor
  func `Cancellation during channels phase ends sync activity once and resets state`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let delayingChannelService = DelayingChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let endedTracker = CallTracker()

    await coordinator.setSyncActivityCallbacks(
      onStarted: {},
      onEnded: { _ in endedTracker.markCalled() },
      onPhaseChanged: { _ in }
    )

    let syncTask = Task {
      try await coordinator.performFullSync(
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: mockContactService,
        channelService: delayingChannelService,
        messagePollingService: mockMessagePollingService
      )
    }

    await delayingChannelService.waitForSyncStart()
    syncTask.cancel()

    do {
      try await syncTask.value
      Issue.record("Expected cancellation")
    } catch is CancellationError {
      // Expected
    } catch {
      Issue.record("Expected CancellationError, got \(error)")
    }

    #expect(endedTracker.callCount == 1, "onSyncActivityEnded should be called exactly once on cancellation")
    #expect(coordinator.state == .idle, "Sync state should reset to idle on cancellation")
  }

  @Test
  @MainActor
  func `performFullSync clears notification suppression after poll completes`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let mockTransport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: mockTransport)
    let services = try await ServiceContainer.forTesting(session: session)

    // Simulate suppression being active (as it would be during a real sync)
    services.notificationService.isSuppressingNotifications = true
    #expect(services.notificationService.isSuppressingNotifications == true)

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      notificationService: services.notificationService
    )

    // Suppression should be cleared after pollAllMessages() completes
    #expect(services.notificationService.isSuppressingNotifications == false)
  }

  @Test
  @MainActor
  func `Contact sync passes lastContactSync timestamp from device`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()

    // Create device with a lastContactSync timestamp
    let lastSyncTimestamp: UInt32 = 1_704_067_200 // 2024-01-01 00:00:00 UTC
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: lastSyncTimestamp
    )

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService,
      appStateProvider: nil
    )

    let invocations = await mockContactService.syncContactsInvocations
    #expect(invocations.count == 1)

    // Verify the since parameter was passed
    let since = invocations[0].since
    let expectedDate = Date(timeIntervalSince1970: Double(lastSyncTimestamp))

    // Use try #require to safely unwrap and produce a clear failure message
    let actualSince = try #require(since, "Should pass lastContactSync as since parameter")
    #expect(actualSince == expectedDate, "Since date should match device lastContactSync")
  }

  // MARK: - Succeeded Parameter Tests

  @Test
  @MainActor
  func `Successful sync passes succeeded: true to onEnded callback`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let succeededValues = ValueTracker<Bool>()

    await coordinator.setSyncActivityCallbacks(
      onStarted: {},
      onEnded: { succeeded in succeededValues.record(succeeded) },
      onPhaseChanged: { _ in }
    )

    try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: mockChannelService,
      messagePollingService: mockMessagePollingService
    )

    #expect(succeededValues.values == [true], "Successful sync should pass succeeded: true")
  }

  @Test
  @MainActor
  func `Failed sync passes succeeded: false to onEnded callback`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(radioID: testDeviceID)

    let succeededValues = ValueTracker<Bool>()

    await coordinator.setSyncActivityCallbacks(
      onStarted: {},
      onEnded: { succeeded in succeededValues.record(succeeded) },
      onPhaseChanged: { _ in }
    )

    await mockContactService.setStubbedSyncContactsResult(.failure(SyncCoordinatorError.syncFailed("Test error")))

    do {
      try await coordinator.performFullSync(
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: mockContactService,
        channelService: mockChannelService,
        messagePollingService: mockMessagePollingService
      )
      Issue.record("Should have thrown error")
    } catch {
      // Expected
    }

    #expect(succeededValues.values == [false], "Failed sync should pass succeeded: false")
  }

  // MARK: - Resync Activity Bracket Tests

  @Test
  @MainActor
  func `beginResyncActivity and endResyncActivity fire the correct callbacks`() async {
    let coordinator = SyncCoordinator()

    let startedTracker = CallTracker()
    let succeededValues = ValueTracker<Bool>()

    await coordinator.setSyncActivityCallbacks(
      onStarted: { startedTracker.markCalled() },
      onEnded: { succeeded in succeededValues.record(succeeded) },
      onPhaseChanged: { _ in }
    )

    await coordinator.beginResyncActivity()
    #expect(startedTracker.callCount == 1, "beginResyncActivity should fire onStarted")

    await coordinator.endResyncActivity(succeeded: true)
    #expect(succeededValues.values == [true], "endResyncActivity(succeeded: true) should pass true")

    // Call again with false to verify the value is forwarded
    await coordinator.beginResyncActivity()
    await coordinator.endResyncActivity(succeeded: false)
    #expect(succeededValues.values == [true, false], "endResyncActivity(succeeded: false) should pass false")
  }

  @Test
  @MainActor
  func `Disconnect during resync does not double-end the resync bracket`() async throws {
    let coordinator = SyncCoordinator()

    let mockTransport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: mockTransport)
    let services = try await ServiceContainer.forTesting(session: session)

    let succeededValues = ValueTracker<Bool>()

    await coordinator.setSyncActivityCallbacks(
      onStarted: {},
      onEnded: { succeeded in succeededValues.record(succeeded) },
      onPhaseChanged: { _ in }
    )

    // Simulate resync bracket open
    await coordinator.beginResyncActivity()

    // Disconnect while resync bracket is open
    await coordinator.onDisconnected(notificationService: services.notificationService)

    // onDisconnected calls endSyncActivityOnce, which is for the initial sync bracket,
    // not the resync bracket. Since no initial sync was started, hasEndedSyncActivity
    // is already true and endSyncActivityOnce should be a no-op.
    #expect(succeededValues.values.isEmpty, "onDisconnected should not end the resync bracket")
  }
}

// MARK: - Test Helpers

/// Thread-safe value recorder for verifying callback arguments in tests.
final class ValueTracker<T: Sendable>: @unchecked Sendable {
  private var _values: [T] = []
  private let lock = NSLock()

  var values: [T] {
    lock.lock()
    defer { lock.unlock() }
    return _values
  }

  func record(_ value: T) {
    lock.lock()
    defer { lock.unlock() }
    _values.append(value)
  }
}

/// Actor to safely track callback invocations from concurrent closures
/// Mock that tracks the order of activity ended callback vs message polling
actor OrderTrackingMessagePollingService: MessagePollingServiceProtocol {
  private var activityEndedTime: Date?
  private var messagePollTime: Date?

  /// Records when the activity ended callback was invoked
  func recordActivityEnded() {
    activityEndedTime = Date()
  }

  /// Whether activity ended before message polling started
  var activityEndedBeforeMessagePoll: Bool {
    guard let ended = activityEndedTime, let poll = messagePollTime else {
      return false
    }
    return ended < poll
  }

  // MARK: - MessagePollingServiceProtocol

  func pollAllMessages() async throws -> Int {
    messagePollTime = Date()
    return 0
  }

  func waitForPendingHandlers(timeout: Duration) async -> Bool {
    true
  }

  func startAutoFetch(radioID: UUID) async {}

  func pauseAutoFetch() async {}

  func resumeAutoFetch() async {}

  func setContactMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?, DeliveryContext) async -> Void) {}

  func setChannelMessageHandler(_ handler: @escaping @Sendable (ChannelMessage, ChannelDTO?, DeliveryContext) async -> Void) {}

  func setSignedMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void) {}

  func setCLIMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void) {}
}

/// Mock contact service that delays and signals when sync has started
actor DelayingContactService: ContactServiceProtocol {
  private var continuation: CheckedContinuation<Void, Never>?

  /// Wait to be signaled that contacts sync has started
  func waitForSyncStart() async {
    await withCheckedContinuation { continuation in
      self.continuation = continuation
    }
  }

  /// Allow the sync to complete
  func completeSync() {
    continuation?.resume()
    continuation = nil
  }

  func syncContacts(radioID: UUID, since: Date?) async throws -> ContactSyncResult {
    // Signal that sync has started, then wait to be resumed
    await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
      Task {
        // Store the continuation so completeSync can resume it
        self.continuation?.resume()
        self.continuation = cont
      }
    }
    return ContactSyncResult(contactsReceived: 0, lastSyncTimestamp: 0, isIncremental: false)
  }
}

/// Mock channel service that blocks in syncChannels until cancelled.
actor DelayingChannelService: ChannelServiceProtocol {
  private var hasStarted = false
  private var startWaiters: [CheckedContinuation<Void, Never>] = []

  func waitForSyncStart() async {
    if hasStarted { return }
    await withCheckedContinuation { continuation in
      startWaiters.append(continuation)
    }
  }

  func syncChannels(radioID: UUID, maxChannels: UInt8, usePipelinedRead: Bool) async throws -> ChannelSyncResult {
    hasStarted = true
    while !startWaiters.isEmpty {
      startWaiters.removeFirst().resume()
    }

    while true {
      try Task.checkCancellation()
      try await Task.sleep(for: .milliseconds(50))
    }
  }

  func retryFailedChannels(radioID: UUID, indices: [UInt8]) async throws -> ChannelSyncResult {
    ChannelSyncResult(channelsSynced: 0, errors: [])
  }
}
