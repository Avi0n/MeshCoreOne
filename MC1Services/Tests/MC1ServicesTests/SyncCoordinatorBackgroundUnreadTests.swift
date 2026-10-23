import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@Suite("SyncCoordinator background unread counts")
@MainActor
struct SyncCoordinatorBackgroundUnreadTests {
  @Test(arguments: [false, true], [false, true])
  func `open conversation suppresses unread only while in the foreground`(
    isChannel: Bool,
    isInForeground: Bool
  ) async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let services = ServiceContainer(
      session: MeshCoreSession(transport: SimulatorMockTransport()),
      dataStore: store,
      radioID: radioID,
      appStateProvider: MockAppStateProvider(isInForeground: isInForeground)
    )
    defer { Task { await services.tearDown() } }
    // Stored unread counts are the assertion. Skip system notification delivery.
    services.notificationService.isSuppressingNotifications = true

    let expectedUnread = isInForeground ? 0 : 1
    if isChannel {
      let channel = ChannelDTO.testChannel(radioID: radioID)
      try await store.saveChannel(channel)
      services.notificationService.setActiveConversation(
        channelIndex: channel.index,
        channelRadioID: radioID
      )
      let message = MessageDTO.testChannelMessage(radioID: radioID, channelIndex: channel.index)
      try await services.syncCoordinator.updateChannelUnreadsAndNotify(
        messageDTO: message,
        channel: channel,
        channelIndex: channel.index,
        senderNodeName: nil,
        messageText: message.text,
        timestamp: message.timestamp,
        hasSelfMention: false,
        radioID: radioID,
        dependencies: services.syncDependencies
      )
      let saved = try #require(await store.fetchChannel(id: channel.id))
      #expect(saved.unreadCount == expectedUnread)
    } else {
      let contact = ContactDTO.testContact(radioID: radioID)
      try await store.saveContact(contact)
      services.notificationService.setActiveConversation(contactID: contact.id)
      let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id)
      try await services.syncCoordinator.updateDMUnreadsAndNotify(
        messageDTO: message,
        contactID: contact.id,
        contact: contact,
        messageText: message.text,
        hasSelfMention: false,
        dependencies: services.syncDependencies
      )
      let saved = try #require(await store.fetchContact(id: contact.id))
      #expect(saved.unreadCount == expectedUnread)
    }
  }
}
