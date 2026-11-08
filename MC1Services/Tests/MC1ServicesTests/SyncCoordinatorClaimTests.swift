import Foundation
@testable import MC1Services
import Testing

@Suite("Sync coordinator claim ownership")
struct SyncCoordinatorClaimTests {
  private static let contactWatermark: UInt32 = 1_704_067_200
  private static let waiterTimeout: Duration = .milliseconds(500)

  @Test
  @MainActor
  func `Ordinary sync completion preserves manual advert inhibition`() async throws {
    let coordinator = SyncCoordinator()
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(
      radioID: radioID,
      lastContactSync: Self.contactWatermark
    )
    let contacts = MockContactService()
    let advertContacts = MockContactService()
    try await coordinator.claimManualContactSync()

    let result = try await coordinator.performFullSync(
      radioID: radioID,
      dataStore: dataStore,
      contactService: contacts,
      channelService: MockChannelService(),
      messagePollingService: MockMessagePollingService()
    )
    #expect(result.contacts == .clean)
    #expect(await contacts.syncContactsInvocations.count == 1)

    let inhibited = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: radioID,
      dataStore: dataStore,
      contactService: advertContacts
    )
    #expect(inhibited == .busy)
    #expect(await advertContacts.syncContactsInvocations.isEmpty)

    await coordinator.releaseManualContactSync()
    let released = await coordinator.performAdvertContactSync(
      fullRefetch: false,
      radioID: radioID,
      dataStore: dataStore,
      contactService: advertContacts
    )
    #expect(released == .synced)
    #expect(await advertContacts.syncContactsInvocations.count == 1)
  }

  @Test(arguments: [false, true])
  @MainActor
  func `An interrupted advert waiter leaves another sync waiting`(cancel: Bool) async throws {
    let coordinator = SyncCoordinator()
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(
      radioID: radioID,
      lastContactSync: Self.contactWatermark
    )
    let advertContacts = GatedContactService()
    let contacts = MockContactService()
    let channels = MockChannelService()
    let messages = MockMessagePollingService()
    defer { Task { await advertContacts.release() } }

    let advertTask = Task {
      await coordinator.performAdvertContactSync(
        fullRefetch: false,
        radioID: radioID,
        dataStore: dataStore,
        contactService: advertContacts
      )
    }
    await advertContacts.waitForSyncStart()

    let fullSyncTask = Task {
      try await coordinator.performFullSync(
        radioID: radioID,
        dataStore: dataStore,
        contactService: contacts,
        channelService: channels,
        messagePollingService: messages
      )
    }
    defer { fullSyncTask.cancel() }
    try await waitUntil { await coordinator.advertSyncWaiters.count == 1 }

    let interruptedTask = Task {
      try await coordinator.waitForAdvertContactSync(
        timeout: cancel ? nil : Self.waiterTimeout
      )
    }
    defer { interruptedTask.cancel() }
    try await waitUntil { await coordinator.advertSyncWaiters.count == 2 }
    if cancel { interruptedTask.cancel() }

    do {
      try await interruptedTask.value
      Issue.record("The interrupted waiter must throw")
    } catch is CancellationError {
      #expect(cancel)
    } catch let error as SyncCoordinatorError {
      #expect(!cancel)
      guard case let .syncFailed(message) = error else {
        Issue.record("Expected the advert wait timeout, got \(error)")
        return
      }
      #expect(message == SyncCoordinator.advertContactSyncWaitTimedOutMessage)
    }

    #expect(await coordinator.advertSyncWaiters.count == 1)
    #expect(await contacts.syncContactsInvocations.isEmpty)
    #expect(await channels.syncChannelsInvocations.isEmpty)
    #expect(await messages.pollAllMessagesCallCount == 0)

    await advertContacts.release()
    #expect(await advertTask.value == .synced)
    let result = try await fullSyncTask.value
    #expect(result.contacts == .clean)
    #expect(result.channels == .clean)
    #expect(result.messages == .clean)
    #expect(await contacts.syncContactsInvocations.count == 1)
    #expect(await channels.syncChannelsInvocations.count == 1)
    #expect(await messages.pollAllMessagesCallCount == 1)
    #expect(await coordinator.advertSyncWaiters.isEmpty)
  }
}
