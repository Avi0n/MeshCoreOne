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
    maxContacts: UInt16 = 100,
    lastContactSync: UInt32 = 0
  ) async throws -> PersistenceStore {
    try await PersistenceStore.createTestDataStore(
      radioID: radioID,
      maxChannels: maxChannels,
      maxContacts: maxContacts,
      lastContactSync: lastContactSync
    )
  }

  private func contactFrame(
    key: Data,
    name: String,
    flags: UInt8 = 0,
    lastModified: UInt32 = 1_700_000_100
  ) -> ContactFrame {
    ContactFrame(
      publicKey: key,
      type: .chat,
      flags: flags,
      outPathLength: 0,
      outPath: Data(),
      name: name,
      lastAdvertTimestamp: 1_700_000_000,
      latitude: 0,
      longitude: 0,
      lastModified: lastModified
    )
  }

  private func meshContact(
    key: Data,
    name: String,
    flags: ContactFlags = ContactFlags(rawValue: 0),
    lastModified: Date = Date(timeIntervalSince1970: 1_800_000_000)
  ) -> MeshContact {
    MeshContact(
      id: key.hexString,
      publicKey: key,
      type: .chat,
      flags: flags,
      outPathLength: 0,
      outPath: Data(),
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
      latitude: 0,
      longitude: 0,
      lastModified: lastModified
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

    // The device filter is strictly greater-than, so the window is rewound one
    // second to include contacts modified in the watermark second itself.
    let since = invocations[0].since
    let expectedDate = Date(timeIntervalSince1970: Double(lastSyncTimestamp) - 1)

    // Use try #require to safely unwrap and produce a clear failure message
    let actualSince = try #require(since, "Should pass lastContactSync as since parameter")
    #expect(actualSince == expectedDate, "Since date should include the watermark second")
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

  // MARK: - Advert Contact Delta Sync

  @Test
  @MainActor
  func `performAdvertContactSync writes watermark and uses since filter`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let watermark: UInt32 = 1_704_067_200
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: watermark
    )

    let newWatermark = UInt32(Date().timeIntervalSince1970) + 60
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 3, lastSyncTimestamp: newWatermark, isIncremental: true)
    ))

    let outcome = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(outcome == .synced)

    let invocations = await mockContactService.syncContactsInvocations
    #expect(invocations.count == 1)
    let since = try #require(invocations[0].since)
    #expect(
      since == Date(timeIntervalSince1970: Double(watermark) - 1),
      "The device filter is strictly greater-than, so the window must include the watermark second"
    )

    let device = try #require(await dataStore.fetchDevice(radioID: testDeviceID))
    #expect(device.lastContactSync == newWatermark)
  }

  @Test
  @MainActor
  func `performAdvertContactSync fullRefetch uses epoch zero and skips pruning`() async throws {
    let coordinator = SyncCoordinator()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )

    let keptKey = Data(repeating: 0x11, count: 32)
    let orphanKey = Data(repeating: 0x99, count: 32)
    _ = try await dataStore.saveContact(
      radioID: testDeviceID,
      from: ContactFrame(
        publicKey: keptKey,
        type: .chat,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        name: "Kept",
        lastAdvertTimestamp: 1_700_000_000,
        latitude: 0,
        longitude: 0,
        lastModified: 1_700_000_100
      )
    )
    _ = try await dataStore.saveContact(
      radioID: testDeviceID,
      from: ContactFrame(
        publicKey: orphanKey,
        type: .chat,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        name: "Orphan",
        lastAdvertTimestamp: 1_700_000_000,
        latitude: 0,
        longitude: 0,
        lastModified: 1_700_000_100
      )
    )

    let session = MockMeshCoreSession()
    // Device returns only the kept contact — orphan would be pruned on since == nil.
    await session.setStubbedContacts([
      MeshContact(
        id: keptKey.hexString,
        publicKey: keptKey,
        type: .chat,
        flags: ContactFlags(rawValue: 0),
        outPathLength: 0,
        outPath: Data(),
        advertisedName: "Kept",
        lastAdvertisement: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 0,
        longitude: 0,
        lastModified: Date(timeIntervalSince1970: 1_800_000_000)
      )
    ])
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    let outcome = await coordinator.performAdvertContactSync(
      fullRefetch: true,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: contactService
    )
    #expect(outcome == .synced)

    let sinceArgs = await session.getContactsInvocations
    #expect(sinceArgs.count == 1)
    #expect(sinceArgs[0] == Date(timeIntervalSince1970: 0))

    // Prune-free: local-only orphan survives epoch-0 full refetch.
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: orphanKey) != nil)

    // Control: since == nil prunes the orphan.
    _ = try await contactService.syncContacts(radioID: testDeviceID, since: nil)
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: orphanKey) == nil)
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: keptKey) != nil)
  }

  @Test
  @MainActor
  func `performAdvertContactSync returns busy when manual contact sync is active`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(
        contactsReceived: 1,
        lastSyncTimestamp: UInt32(Date().timeIntervalSince1970),
        isIncremental: true
      )
    ))

    await coordinator.setManualContactSyncActive(true)
    let busy = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(busy == .busy)
    #expect(
      await mockContactService.syncContactsInvocations.isEmpty,
      "manual refresh must block advert delta from reaching the radio"
    )

    await coordinator.setManualContactSyncActive(false)
    let after = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(after == .synced)
    #expect(await mockContactService.syncContactsInvocations.count == 1)
  }

  @Test
  @MainActor
  func `far-future watermark recovers with one full refetch then incremental`() async throws {
    // After RTC correction, residual far-future lastmods can leave a permanent high
    // watermark. Invalid stamp → prune-free epoch-0 once, then store the new max.
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let referenceNow = Date()
    let phoneNow = UInt32(referenceNow.timeIntervalSince1970)
    let farFuture = phoneNow &+ UInt32(30 * 24 * 60 * 60)
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: farFuture
    )
    // Full recovery returns a plausible max lastmod (radio clock now pinned to phone).
    let recoveredWatermark = phoneNow &+ 30
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 4, lastSyncTimestamp: recoveredWatermark, isIncremental: true)
    ))

    let first = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(first == .synced)

    let afterFirst = await mockContactService.syncContactsInvocations
    #expect(afterFirst.count == 1)
    #expect(
      afterFirst[0].since == Date(timeIntervalSince1970: 0),
      "advert invalid recovery must use prune-free epoch-0, not since=nil"
    )
    var device = try #require(await dataStore.fetchDevice(radioID: testDeviceID))
    #expect(device.lastContactSync == recoveredWatermark)

    // Second round: recovered watermark is plausible → incremental.
    let nextWatermark = recoveredWatermark &+ 10
    await mockContactService.reset()
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 1, lastSyncTimestamp: nextWatermark, isIncremental: true)
    ))

    let second = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(second == .synced)

    let afterSecond = await mockContactService.syncContactsInvocations
    #expect(afterSecond.count == 1)
    let secondSince = try #require(afterSecond[0].since)
    #expect(
      secondSince == Date(timeIntervalSince1970: Double(recoveredWatermark) - 1),
      "after recovery the next round must be incremental, not another full fetch"
    )
    device = try #require(await dataStore.fetchDevice(radioID: testDeviceID))
    #expect(device.lastContactSync == nextWatermark)
  }

  @Test
  @MainActor
  func `far-future watermark recovery retries after a failed recovery fetch`() async throws {
    // A recovery full fetch that throws must not spend the one-shot latch: the
    // next round must retry the prune-free epoch-0 recovery, not fall back to the
    // invalid stored watermark.
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let phoneNow = UInt32(Date().timeIntervalSince1970)
    let farFuture = phoneNow &+ UInt32(30 * 24 * 60 * 60)
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: farFuture
    )

    // Round 1: recovery fetch throws.
    await mockContactService.setStubbedSyncContactsResult(
      .failure(SyncCoordinatorError.syncFailed("boom"))
    )
    let first = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(first == .failed)
    let afterFirst = await mockContactService.syncContactsInvocations
    #expect(afterFirst.count == 1)
    #expect(
      afterFirst[0].since == Date(timeIntervalSince1970: 0),
      "failed round must still attempt prune-free epoch-0 recovery"
    )

    // Round 2: recovery fetch succeeds. Because round 1 failed, the latch is not
    // spent, so this round must retry epoch-0 recovery — not the stored watermark.
    let recoveredWatermark = phoneNow &+ 30
    await mockContactService.reset()
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 2, lastSyncTimestamp: recoveredWatermark, isIncremental: true)
    ))
    let second = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(second == .synced)
    let afterSecond = await mockContactService.syncContactsInvocations
    #expect(afterSecond.count == 1)
    #expect(
      afterSecond[0].since == Date(timeIntervalSince1970: 0),
      "a failed recovery must not spend the one-shot; retry epoch-0, not the stored far-future watermark"
    )
    let device = try #require(await dataStore.fetchDevice(radioID: testDeviceID))
    #expect(device.lastContactSync == recoveredWatermark)
  }

  @Test
  @MainActor
  func `advert invalid watermark recovery is prune-free and keeps local-only contacts`() async throws {
    // since=nil would delete local rows absent from the device response. Advert
    // recovery must use epoch-0 so a background round never prunes.
    let coordinator = SyncCoordinator()
    let testDeviceID = UUID()
    let phoneNow = UInt32(Date().timeIntervalSince1970)
    let farFuture = phoneNow &+ UInt32(30 * 24 * 60 * 60)
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: farFuture
    )

    let keptKey = Data(repeating: 0x21, count: 32)
    let orphanKey = Data(repeating: 0xA9, count: 32)
    _ = try await dataStore.saveContact(
      radioID: testDeviceID,
      from: ContactFrame(
        publicKey: keptKey,
        type: .chat,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        name: "Kept",
        lastAdvertTimestamp: phoneNow,
        latitude: 0,
        longitude: 0,
        lastModified: phoneNow
      )
    )
    _ = try await dataStore.saveContact(
      radioID: testDeviceID,
      from: ContactFrame(
        publicKey: orphanKey,
        type: .chat,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        name: "Orphan",
        lastAdvertTimestamp: phoneNow,
        latitude: 0,
        longitude: 0,
        lastModified: phoneNow
      )
    )

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([
      MeshContact(
        id: keptKey.hexString,
        publicKey: keptKey,
        type: .chat,
        flags: ContactFlags(rawValue: 0),
        outPathLength: 0,
        outPath: Data(),
        advertisedName: "Kept",
        lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(phoneNow)),
        latitude: 0,
        longitude: 0,
        lastModified: Date(timeIntervalSince1970: TimeInterval(phoneNow &+ 10))
      )
    ])
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    let outcome = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: contactService
    )
    #expect(outcome == .synced)

    let sinceArgs = await session.getContactsInvocations
    #expect(sinceArgs.count == 1)
    #expect(
      sinceArgs[0] == Date(timeIntervalSince1970: 0),
      "invalid advert recovery must full-fetch with prune-free epoch-0"
    )
    #expect(
      try await dataStore.fetchContact(radioID: testDeviceID, publicKey: orphanKey) != nil,
      "local-only contact must survive advert invalid-watermark recovery"
    )
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: keptKey) != nil)
  }

  @Test
  @MainActor
  func `advert invalid watermark recovery full-fetches at most once per coordinator`() async throws {
    // Residual far-future lastmods keep max(lastmod) implausible. Without a latch
    // every advert debounce re-streams the whole table. One recovery, then stored stamp.
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let phoneNow = UInt32(Date().timeIntervalSince1970)
    let farFuture = phoneNow &+ UInt32(30 * 24 * 60 * 60)
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: farFuture
    )
    // Write-back stays implausible — the pathological residual-lastmod case.
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 10, lastSyncTimestamp: farFuture, isIncremental: true)
    ))

    let first = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(first == .synced)
    let afterFirst = await mockContactService.syncContactsInvocations
    #expect(afterFirst.count == 1)
    #expect(afterFirst[0].since == Date(timeIntervalSince1970: 0), "first round recovers with full fetch")

    await mockContactService.reset()
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 0, lastSyncTimestamp: farFuture, isIncremental: true)
    ))

    let second = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(second == .synced)
    let afterSecond = await mockContactService.syncContactsInvocations
    #expect(afterSecond.count == 1)
    let secondSince = try #require(afterSecond[0].since)
    #expect(
      secondSince == Date(timeIntervalSince1970: Double(farFuture) - 1),
      "second round must use the stored stamp, not another full fetch"
    )
    #expect(secondSince != Date(timeIntervalSince1970: 0))
  }

  @Test
  @MainActor
  func `watermark a few minutes ahead of phone stays incremental`() async throws {
    // Radio may lead the phone by minutes before time sync settles. That is not
    // an invalid watermark and must not trigger a full recovery fetch.
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let phoneNow = UInt32(Date().timeIntervalSince1970)
    let minutesLead: UInt32 = 5 * 60
    let radioLead = phoneNow &+ minutesLead
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: radioLead
    )
    let newWatermark = radioLead &+ 15
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 1, lastSyncTimestamp: newWatermark, isIncremental: true)
    ))

    let outcome = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(outcome == .synced)

    let invocations = await mockContactService.syncContactsInvocations
    #expect(invocations.count == 1)
    let since = try #require(
      invocations[0].since,
      "minute-scale lead must stay incremental (since non-nil), not a full recovery"
    )
    #expect(since == Date(timeIntervalSince1970: Double(radioLead) - 1))

    let device = try #require(await dataStore.fetchDevice(radioID: testDeviceID))
    #expect(device.lastContactSync == newWatermark)
  }

  @Test
  func `contactWatermarkUse marks far-future invalid and minute lead incremental`() {
    let reference = Date(timeIntervalSince1970: 1_800_000_000)
    let refSeconds = UInt32(reference.timeIntervalSince1970)
    let minuteLead = refSeconds &+ (5 * 60)
    let farFuture = refSeconds &+ UInt32(30 * 24 * 60 * 60)

    #expect(SyncCoordinator.contactWatermarkUse(fromLastContactSync: nil, referenceNow: reference) == .none)
    #expect(SyncCoordinator.contactWatermarkUse(fromLastContactSync: 0, referenceNow: reference) == .none)
    #expect(
      SyncCoordinator.contactWatermarkUse(fromLastContactSync: minuteLead, referenceNow: reference)
        == .incremental(minuteLead)
    )
    #expect(
      SyncCoordinator.contactWatermarkUse(fromLastContactSync: farFuture, referenceNow: reference)
        == .invalid(stored: farFuture)
    )
  }

  @MainActor
  @Test(arguments: [false, true])
  func `performAdvertContactSync with zero watermark does not sync`(fullRefetch: Bool) async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 0
    )

    let newWatermark: UInt32 = 1_800_000_000
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 2, lastSyncTimestamp: newWatermark, isIncremental: true)
    ))

    let outcome = await coordinator.performAdvertContactSync(
      fullRefetch: fullRefetch,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(outcome == .notReady)

    #expect(
      await mockContactService.syncContactsInvocations.isEmpty,
      "No advert sync may run before the first pruning full sync succeeds"
    )

    let device = try #require(await dataStore.fetchDevice(radioID: testDeviceID))
    #expect(
      device.lastContactSync == 0,
      "Writing a watermark here would suppress the one-time pruning full sync forever"
    )
  }

  @Test
  @MainActor
  func `Advert sync runs after a full sync that found no contacts`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 0
    )

    // An empty radio has no contact to stamp, so the zero sentinel survives the full sync.
    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 0, lastSyncTimestamp: 0, isIncremental: false)
    ))

    _ = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )

    let device = try #require(await dataStore.fetchDevice(radioID: testDeviceID))
    #expect(device.lastContactSync == 0)

    let outcome = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )
    #expect(outcome == .synced, "The first auto-added contact must not wait for another full sync")

    let invocations = await mockContactService.syncContactsInvocations
    #expect(invocations.count == 2)
    #expect(
      invocations[1].since == Date(timeIntervalSince1970: 0),
      "With no watermark to resume from, the delta round fetches prune-free from epoch zero"
    )
  }

  @Test
  @MainActor
  func `Full sync still prunes after an advert sync attempt with no watermark`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 0
    )

    await mockContactService.setStubbedSyncContactsResult(.success(
      ContactSyncResult(contactsReceived: 2, lastSyncTimestamp: 1_800_000_000, isIncremental: true)
    ))

    _ = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService
    )

    _ = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )

    let invocations = await mockContactService.syncContactsInvocations
    #expect(invocations.count == 1)
    #expect(
      invocations[0].since == nil,
      "The first full sync after a failed connect sync must still prune"
    )
  }

  @Test
  @MainActor
  func `performAdvertContactSync returns false while sync claimed`() async throws {
    let coordinator = SyncCoordinator()
    let delayingContactService = DelayingContactService()
    let mockChannelService = MockChannelService()
    let mockMessagePollingService = MockMessagePollingService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )

    let startedTracker = CallTracker()
    await coordinator.setSyncActivityCallbacks(
      onStarted: { startedTracker.markCalled() },
      onEnded: { _ in },
      onPhaseChanged: { _ in }
    )

    let firstSyncTask = Task {
      try await coordinator.performFullSync(
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: delayingContactService,
        channelService: mockChannelService,
        messagePollingService: mockMessagePollingService
      )
    }

    try await waitUntil("First sync should have started") {
      startedTracker.callCount >= 1
    }

    let outcome = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: MockContactService()
    )
    #expect(outcome == .busy, "A collision with another sync is not a failed exchange")

    await delayingContactService.completeSync()
    firstSyncTask.cancel()
  }

  @Test
  @MainActor
  func `Channel retry waits for an active advert contact sync instead of skipping`() async throws {
    let coordinator = SyncCoordinator()
    let gatedContactService = GatedContactService()
    let mockChannelService = MockChannelService()
    let testDeviceID = UUID()
    let retryIndices: [UInt8] = [3, 5]
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )

    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: gatedContactService
      )
    }
    await gatedContactService.waitForSyncStart()

    let retryFinished = CallTracker()
    let retryTask = Task {
      let result = await coordinator.retryChannels(
        radioID: testDeviceID,
        channelService: mockChannelService,
        indices: retryIndices
      )
      retryFinished.markCalled()
      return result
    }

    try await Task.sleep(for: .milliseconds(200))
    #expect(
      !retryFinished.wasCalled,
      "Channel retry must wait while an advert contact sync holds the claim"
    )
    #expect(await mockChannelService.retryInvocations.isEmpty)

    await gatedContactService.release()

    #expect(await advertTask.value == .synced, "Advert contact sync should complete once released")
    let result = await retryTask.value
    #expect(result.errors.isEmpty, "Channel retry must run for real after the advert sync releases")
    #expect(await mockChannelService.retryInvocations.map(\.indices) == [retryIndices])
  }

  @Test
  @MainActor
  func `claimManualContactSync is atomic with the wait so advert cannot claim in the gap`() async throws {
    // Separate wait and setManual hops leave a gap where a delta can pass both
    // guards. One method waits then sets manual without yielding.
    let coordinator = SyncCoordinator()
    let gatedContactService = GatedContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )

    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: gatedContactService
      )
    }
    await gatedContactService.waitForSyncStart()

    let claimTask = Task {
      try await coordinator.claimManualContactSync()
    }

    try await Task.sleep(for: .milliseconds(100))
    // Still waiting on the advert claim — must not have set manual yet in a way
    // that would be racy; release advert so the claim can complete.
    await gatedContactService.release()
    #expect(await advertTask.value == .synced)
    try await claimTask.value

    let racingAdvert = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: MockContactService()
    )
    #expect(
      racingAdvert == .busy,
      "After claimManualContactSync returns, manual flag must already be set"
    )

    await coordinator.setManualContactSyncActive(false)
  }

  @Test
  @MainActor
  func `waitForAdvertContactSync throws when cancelled`() async throws {
    let coordinator = SyncCoordinator()
    let gatedContactService = GatedContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )

    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: gatedContactService
      )
    }
    await gatedContactService.waitForSyncStart()

    let waitTask = Task {
      try await coordinator.waitForAdvertContactSync()
    }
    try await Task.sleep(for: .milliseconds(50))
    waitTask.cancel()

    var threwCancellation = false
    do {
      try await waitTask.value
    } catch is CancellationError {
      threwCancellation = true
    } catch {
      Issue.record("Expected CancellationError, got \(error)")
    }
    #expect(threwCancellation, "Cancelled wait must throw CancellationError, not hang")

    await gatedContactService.release()
    _ = await advertTask.value
  }

  @Test
  @MainActor
  func `waitForAdvertContactSync throws when the bound is reached`() async throws {
    let coordinator = SyncCoordinator()
    let gatedContactService = GatedContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )

    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: gatedContactService
      )
    }
    await gatedContactService.waitForSyncStart()

    var timedOut = false
    do {
      try await coordinator.waitForAdvertContactSync(timeout: .milliseconds(40))
    } catch let error as SyncCoordinatorError {
      if case let .syncFailed(message) = error {
        timedOut = message == SyncCoordinator.advertContactSyncWaitTimedOutMessage
      }
    }
    #expect(timedOut, "Wait must surface a timeout error rather than hang or silent-skip")

    await gatedContactService.release()
    _ = await advertTask.value
  }

  @Test
  @MainActor
  func `onDisconnected resumes advert claim waiters`() async throws {
    let coordinator = SyncCoordinator()
    let gatedContactService = GatedContactService()
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      lastContactSync: 1_704_067_200
    )
    let mockTransport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: mockTransport)
    let services = try await ServiceContainer.forTesting(session: session)

    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: testDeviceID,
        dataStore: dataStore,
        contactService: gatedContactService
      )
    }
    await gatedContactService.waitForSyncStart()

    let waitTask = Task {
      try await coordinator.waitForAdvertContactSync(timeout: .seconds(5))
    }
    try await Task.sleep(for: .milliseconds(50))

    await coordinator.onDisconnected(notificationService: services.notificationService)

    // Waiter must resume when the connection drops, not sit until the advert defer.
    try await waitTask.value

    await gatedContactService.release()
    _ = await advertTask.value
  }

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

  // MARK: - Capacity-capped contact staleness at connect

  @Test
  @MainActor
  func `At-capacity connect forces full contact sync and prunes the missing non-favourite`() async throws {
    // Valid watermark would normally drive incremental sync, which never prunes.
    // localCount == maxContacts forces a full (pruning) fetch instead.
    let coordinator = SyncCoordinator()
    let testDeviceID = UUID()
    let watermark: UInt32 = 1_704_067_200
    let maxContacts: UInt16 = 3
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: maxContacts,
      lastContactSync: watermark
    )

    let keptA = Data(repeating: 0x11, count: 32)
    let keptB = Data(repeating: 0x22, count: 32)
    let evicted = Data(repeating: 0x99, count: 32)
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: keptA, name: "KeptA"))
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: keptB, name: "KeptB"))
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: evicted, name: "Evicted"))

    let session = MockMeshCoreSession()
    // Radio table is full and missing the evicted key (disconnected eviction).
    await session.setStubbedContacts([
      meshContact(key: keptA, name: "KeptA"),
      meshContact(key: keptB, name: "KeptB"),
      meshContact(key: Data(repeating: 0x33, count: 32), name: "Newcomer")
    ])
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: contactService,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )

    let sinceArgs = await session.getContactsInvocations
    #expect(sinceArgs.count == 1)
    #expect(sinceArgs[0] == nil, "At-capacity connect must force since == nil (full prune path)")
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: evicted) == nil)
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: keptA) != nil)
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: keptB) != nil)
  }

  @Test
  @MainActor
  func `Below-capacity connect keeps incremental contact sync and does not prune`() async throws {
    let coordinator = SyncCoordinator()
    let testDeviceID = UUID()
    let watermark: UInt32 = 1_704_067_200
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 100,
      lastContactSync: watermark
    )

    let kept = Data(repeating: 0x11, count: 32)
    let orphan = Data(repeating: 0x99, count: 32)
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: kept, name: "Kept"))
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: orphan, name: "Orphan"))

    let session = MockMeshCoreSession()
    // Incremental batch omits the orphan; below capacity must not prune it.
    await session.setStubbedContacts([
      meshContact(key: kept, name: "Kept")
    ])
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: contactService,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )

    let sinceArgs = await session.getContactsInvocations
    #expect(sinceArgs.count == 1)
    let since = try #require(sinceArgs[0], "Below-capacity connect must pass a non-nil since")
    #expect(since == Date(timeIntervalSince1970: Double(watermark) - 1))
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: orphan) != nil)
  }

  @Test
  @MainActor
  func `maxContacts zero does not force capacity full contact sync`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let watermark: UInt32 = 1_704_067_200
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 0,
      lastContactSync: watermark
    )
    // Even with local contacts present, maxContacts == 0 must not force full.
    _ = try await dataStore.saveContact(
      radioID: testDeviceID,
      from: contactFrame(key: Data(repeating: 0x11, count: 32), name: "Any")
    )

    _ = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: mockContactService,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )

    let invocations = await mockContactService.syncContactsInvocations
    #expect(invocations.count == 1)
    #expect(invocations[0].since != nil, "maxContacts == 0 must not force since == nil")
  }

  @Test
  @MainActor
  func `Contact count read failure falls back to incremental and keeps connection usable`() async throws {
    let coordinator = SyncCoordinator()
    let mockContactService = MockContactService()
    let testDeviceID = UUID()
    let watermark: UInt32 = 1_704_067_200
    let store = MockPersistenceStore()
    try await store.saveDevice(
      DeviceDTO.testDevice(
        id: testDeviceID,
        radioID: testDeviceID,
        maxContacts: 3,
        lastContactSync: watermark
      )
    )
    // Count would be at capacity if the read succeeded; force the read to fail.
    for byte: UInt8 in [0x11, 0x22, 0x33] {
      try await store.saveContact(
        ContactDTO(
          id: UUID(),
          radioID: testDeviceID,
          publicKey: Data(repeating: byte, count: 32),
          name: "C\(byte)",
          typeRawValue: ContactType.chat.rawValue,
          flags: 0,
          outPathLength: 0,
          outPath: Data(),
          lastAdvertTimestamp: 0,
          latitude: 0,
          longitude: 0,
          lastModified: 0,
          lastHeardTimestamp: nil,
          nickname: nil,
          isBlocked: false,
          isMuted: false,
          isFavorite: false,
          lastMessageDate: nil,
          unreadCount: 0
        )
      )
    }
    await store.setStubbedFetchContactPublicKeysError(
      NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "count read failed"])
    )

    let result = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: store,
      contactService: mockContactService,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )

    #expect(result.isConnectionUsable)
    let invocations = await mockContactService.syncContactsInvocations
    #expect(invocations.count == 1)
    #expect(
      invocations[0].since != nil,
      "Count-read failure must degrade to incremental (today's behavior)"
    )
  }

  @Test
  @MainActor
  func `Full sync prune skips a truncated stream the ratio floor would have pruned`() async throws {
    // Device reports 4 contacts but the stream ends after 3 (favourite dropped).
    // received < reportedTotal, so the prune skips and every local row survives.
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 4,
      lastContactSync: 1_704_067_200
    )

    let favouriteKey = Data(repeating: 0xAA, count: 32)
    let otherKeys = [
      Data(repeating: 0x11, count: 32),
      Data(repeating: 0x22, count: 32),
      Data(repeating: 0x33, count: 32)
    ]
    let (favouriteID, _) = try await dataStore.saveContact(
      radioID: testDeviceID,
      from: contactFrame(key: favouriteKey, name: "Favourite", flags: ContactFlags.favorite.rawValue)
    )
    // ContactFrame maps flags bit 0 to isFavorite; keep a message under the favourite.
    let favourite = try #require(await dataStore.fetchContact(id: favouriteID))
    #expect(favourite.isFavorite)
    try await dataStore.saveMessage(MessageDTO(from: Message(
      radioID: testDeviceID,
      contactID: favouriteID,
      text: "keep me",
      timestamp: 1_700_000_000
    )))
    for (index, key) in otherKeys.enumerated() {
      _ = try await dataStore.saveContact(
        radioID: testDeviceID,
        from: contactFrame(key: key, name: "Other\(index)")
      )
    }

    let session = MockMeshCoreSession()
    // Truncated: 3 of the 4 the device reports (the favourite frame was dropped).
    await session.setStubbedContacts(otherKeys.enumerated().map { index, key in
      meshContact(key: key, name: "Other\(index)")
    })
    await session.setStubbedReportedTotal(4)
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await contactService.syncContacts(radioID: testDeviceID, since: nil)

    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: favouriteKey) != nil)
    let messages = try await dataStore.fetchMessages(contactID: favouriteID, limit: 10, offset: 0)
    #expect(messages.count == 1)
    #expect(messages.first?.text == "keep me")
    for key in otherKeys {
      #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: key) != nil)
    }
  }

  @Test
  @MainActor
  func `Full sync prune removes a favourite absent from a complete device set`() async throws {
    // Device returns a complete list that omits one favourite the user deleted on
    // the radio. received (3) == reported (3), so the snapshot is complete and the
    // prune runs; the favourite has no favourite-only exemption.
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 4,
      lastContactSync: 1_704_067_200
    )

    let favouriteKey = Data(repeating: 0xAA, count: 32)
    let keptKeys = [
      Data(repeating: 0x11, count: 32),
      Data(repeating: 0x22, count: 32),
      Data(repeating: 0x33, count: 32)
    ]
    _ = try await dataStore.saveContact(
      radioID: testDeviceID,
      from: contactFrame(key: favouriteKey, name: "Favourite", flags: ContactFlags.favorite.rawValue)
    )
    for (index, key) in keptKeys.enumerated() {
      _ = try await dataStore.saveContact(
        radioID: testDeviceID,
        from: contactFrame(key: key, name: "Kept\(index)")
      )
    }

    let session = MockMeshCoreSession()
    await session.setStubbedContacts(keptKeys.enumerated().map { index, key in
      meshContact(key: key, name: "Kept\(index)")
    })
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await contactService.syncContacts(radioID: testDeviceID, since: nil)

    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: favouriteKey) == nil)
    for key in keptKeys {
      #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: key) != nil)
    }
  }

  @Test
  @MainActor
  func `Full sync prune removes orphans when the device genuinely shrank below half`() async throws {
    // Complete device reply with one contact: received == reportedTotal, so the
    // three stale local rows are pruned.
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 4,
      lastContactSync: 1_704_067_200
    )

    let survivorKey = Data(repeating: 0x11, count: 32)
    let staleKeys = [
      Data(repeating: 0xAA, count: 32),
      Data(repeating: 0x22, count: 32),
      Data(repeating: 0x33, count: 32)
    ]
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: survivorKey, name: "Survivor"))
    for (index, key) in staleKeys.enumerated() {
      _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: key, name: "Stale\(index)"))
    }

    let session = MockMeshCoreSession()
    await session.setStubbedContacts([meshContact(key: survivorKey, name: "Survivor")])
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await contactService.syncContacts(radioID: testDeviceID, since: nil)

    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: survivorKey) != nil)
    for key in staleKeys {
      #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: key) == nil)
    }
  }

  @Test
  @MainActor
  func `Full sync prune preserves the local V-contact omitted from a complete stream`() async throws {
    // ZephCore omits the V-contact while clock-deferred, so a complete stream can
    // exclude it. It must survive the prune; a genuine orphan alongside it must not.
    let testDeviceID = UUID()
    let selfPublicKey = Data(repeating: 0x01, count: 32) // DeviceDTO.testDevice default
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 4,
      lastContactSync: 1_704_067_200
    )

    let vContactKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: selfPublicKey))
    let realKeys = [Data(repeating: 0x11, count: 32), Data(repeating: 0x22, count: 32)]
    let orphanKey = Data(repeating: 0x99, count: 32)
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: vContactKey, name: "V-Contact"))
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: orphanKey, name: "Orphan"))
    for (index, key) in realKeys.enumerated() {
      _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: key, name: "Real\(index)"))
    }

    let session = MockMeshCoreSession()
    // Complete stream of the two real contacts; V-contact and orphan both absent.
    await session.setStubbedContacts(realKeys.enumerated().map { index, key in
      meshContact(key: key, name: "Real\(index)")
    })
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await contactService.syncContacts(radioID: testDeviceID, since: nil)

    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: vContactKey) != nil)
    #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: orphanKey) == nil)
    for key in realKeys {
      #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: key) != nil)
    }
  }

  @Test
  @MainActor
  func `V-contact in the last slot keeps the connect incremental, not at capacity`() async throws {
    // maxContacts - 1 real contacts plus the local V-contact fill every slot, but
    // the V-contact is virtual. Excluding it keeps the radio below capacity, so a
    // valid watermark still drives an incremental (non-pruning) sync.
    let coordinator = SyncCoordinator()
    let testDeviceID = UUID()
    let selfPublicKey = Data(repeating: 0x01, count: 32) // DeviceDTO.testDevice default
    let watermark: UInt32 = 1_704_067_200
    let maxContacts: UInt16 = 3
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: maxContacts,
      lastContactSync: watermark
    )

    let vContactKey = try #require(VContactIdentity.publicKey(forSelfPublicKey: selfPublicKey))
    let realKeys = [Data(repeating: 0x11, count: 32), Data(repeating: 0x22, count: 32)]
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: vContactKey, name: "V-Contact"))
    for (index, key) in realKeys.enumerated() {
      _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: key, name: "Real\(index)"))
    }

    let session = MockMeshCoreSession()
    await session.setStubbedContacts(realKeys.enumerated().map { index, key in
      meshContact(key: key, name: "Real\(index)")
    })
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await coordinator.performFullSync(
      radioID: testDeviceID,
      dataStore: dataStore,
      contactService: contactService,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )

    let sinceArgs = await session.getContactsInvocations
    #expect(sinceArgs.count == 1)
    #expect(sinceArgs[0] != nil, "V-contact in the last slot must not trip at-capacity; sync stays incremental")
  }

  @Test
  @MainActor
  func `Full sync prune skips when the device sent no contact total`() async throws {
    // A reply with no contactsStart header leaves the total unknown, so the
    // snapshot cannot be proven complete. The prune must skip even though the
    // received set would otherwise look like the whole table.
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 4,
      lastContactSync: 1_704_067_200
    )

    let realKeys = [Data(repeating: 0x11, count: 32), Data(repeating: 0x22, count: 32)]
    let orphanKey = Data(repeating: 0x99, count: 32)
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: orphanKey, name: "Orphan"))
    for (index, key) in realKeys.enumerated() {
      _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: key, name: "Real\(index)"))
    }

    let session = MockMeshCoreSession()
    await session.setStubbedContacts(realKeys.enumerated().map { index, key in
      meshContact(key: key, name: "Real\(index)")
    })
    await session.setStubbedReportsNoTotal(true)
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await contactService.syncContacts(radioID: testDeviceID, since: nil)

    #expect(
      try await dataStore.fetchContact(radioID: testDeviceID, publicKey: orphanKey) != nil,
      "Prune must skip when the device reports no total, so the orphan survives"
    )
    for key in realKeys {
      #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: key) != nil)
    }
  }

  @Test
  @MainActor
  func `Full sync prune skips when the self public key is the wrong length`() async throws {
    // A device row with a malformed (non-32-byte) self key cannot identify the
    // V-contact, so the prune must skip rather than risk deleting it. A local
    // orphan survives even though the device set is complete.
    let testDeviceID = UUID()
    let dataStore = try await createTestDataStore(
      radioID: testDeviceID,
      maxContacts: 4,
      lastContactSync: 1_704_067_200
    )
    try await dataStore.saveDevice(DeviceDTO.testDevice(
      id: testDeviceID,
      radioID: testDeviceID,
      publicKey: Data(repeating: 0x01, count: 8),
      maxContacts: 4,
      lastContactSync: 1_704_067_200
    ))

    let realKeys = [Data(repeating: 0x11, count: 32), Data(repeating: 0x22, count: 32)]
    let orphanKey = Data(repeating: 0x99, count: 32)
    _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: orphanKey, name: "Orphan"))
    for (index, key) in realKeys.enumerated() {
      _ = try await dataStore.saveContact(radioID: testDeviceID, from: contactFrame(key: key, name: "Real\(index)"))
    }

    let session = MockMeshCoreSession()
    // Complete stream of the two real contacts; received == reportedTotal.
    await session.setStubbedContacts(realKeys.enumerated().map { index, key in
      meshContact(key: key, name: "Real\(index)")
    })
    let contactService = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    _ = try await contactService.syncContacts(radioID: testDeviceID, since: nil)

    #expect(
      try await dataStore.fetchContact(radioID: testDeviceID, publicKey: orphanKey) != nil,
      "Prune must skip when the self key is malformed, so the orphan survives"
    )
    for key in realKeys {
      #expect(try await dataStore.fetchContact(radioID: testDeviceID, publicKey: key) != nil)
    }
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

/// Mock contact service that suspends inside `syncContacts` until the test releases it.
/// Lets a test hold an advert delta sync open while another sync path runs.
private actor GatedContactService: ContactServiceProtocol {
  private var hasStarted = false
  private var startWaiters: [CheckedContinuation<Void, Never>] = []
  private var gate: CheckedContinuation<Void, Never>?
  private var isReleased = false

  /// Waits until `syncContacts` has entered and is holding at the gate.
  func waitForSyncStart() async {
    if hasStarted { return }
    await withCheckedContinuation { startWaiters.append($0) }
  }

  /// Lets the held `syncContacts` call return.
  func release() {
    isReleased = true
    gate?.resume()
    gate = nil
  }

  func syncContacts(radioID _: UUID, since _: Date?) async throws -> ContactSyncResult {
    hasStarted = true
    while !startWaiters.isEmpty {
      startWaiters.removeFirst().resume()
    }
    if !isReleased {
      await withCheckedContinuation { gate = $0 }
    }
    return ContactSyncResult(contactsReceived: 0, lastSyncTimestamp: 0, isIncremental: true)
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
