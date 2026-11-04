import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Conversation unread mutations", .timeLimit(.minutes(1)))
@MainActor
struct ConversationUnreadMutationTests {
  enum Kind: Sendable { case direct, channel, room }
  enum OwnerChange: CaseIterable { case coordinator, radio, store }
  enum MetadataFailure: Error, Equatable { case rejected }

  struct LevelChange: Sendable {
    let kind: Kind
    let from: NotificationLevel
    let to: NotificationLevel
    let expected: Int
  }

  @Test
  func `DM deletion publishes zero and preserves the contact`() async throws {
    let fixture = try await Fixture(kind: .direct)
    defer { fixture.shutdown() }
    try #require(try await fixture.store.fetchMessages(contactID: fixture.contact.id).count == 3)

    fixture.viewModel.removeConversation(fixture.conversation)
    try await fixture.viewModel.deleteDirectConversation(for: fixture.contact)
    fixture.viewModel.confirmDirectRemoval(fixture.contact)
    await fixture.viewModel.requestConversationReload()?.value
    await fixture.finishEvents()

    #expect(fixture.viewModel.allConversations.isEmpty)
    #expect(try await fixture.store.fetchMessages(contactID: fixture.contact.id).isEmpty)
    let contact = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(contact.unreadCount == 0)
    #expect(contact.lastMessageDate == nil)
    #expect(fixture.recorder.publications == [3, 0])
    #expect(fixture.recorder.badges == [0])
  }

  @Test
  func `committed unread clearing publishes when the later date write fails`() async throws {
    let fixture = try await Fixture(kind: .direct)
    defer { fixture.shutdown() }
    await fixture.store.setUpdateContactLastMessageFaultInjection { throw MetadataFailure.rejected }

    await #expect(throws: MetadataFailure.rejected) {
      try await fixture.viewModel.deleteDirectConversation(for: fixture.contact)
    }
    await fixture.finishEvents()

    let contact = try #require(try await fixture.store.fetchContact(id: fixture.contact.id))
    #expect(try await fixture.store.fetchMessages(contactID: fixture.contact.id).isEmpty)
    #expect(contact.unreadCount == 0)
    #expect(contact.lastMessageDate == fixture.contact.lastMessageDate)
    #expect(fixture.services.syncCoordinator.conversationsVersion == 1)
    #expect(fixture.recorder.publications == [3, 0])
    #expect(fixture.recorder.badges.isEmpty)
  }

  @Test(arguments: [
    LevelChange(kind: .direct, from: .all, to: .muted, expected: 0),
    LevelChange(kind: .channel, from: .all, to: .mentionsOnly, expected: 1),
    LevelChange(kind: .room, from: .muted, to: .all, expected: 3)
  ])
  func `notification levels publish persisted preference-aware totals`(change: LevelChange) async throws {
    let fixture = try await Fixture(kind: change.kind, level: change.from)
    defer { fixture.shutdown() }
    let initial = fixture.recorder.publications

    await fixture.viewModel.setNotificationLevel(fixture.conversation, level: change.to)
    await fixture.finishEvents()

    #expect(await fixture.total() == change.expected)
    #expect(fixture.viewModel.liveConversation(id: fixture.conversation.id)?.notificationLevel == change.to)
    #expect(fixture.recorder.publications == initial + [change.expected])
    #expect(fixture.recorder.badges == [change.expected])
  }

  @Test(arguments: [false, true], OwnerChange.allCases)
  func `post-save events follow the current radio and store owner`(deleting: Bool, change: OwnerChange) async throws {
    let fixture = try await Fixture(kind: .direct)
    defer { fixture.shutdown() }
    let oldServices = fixture.services
    let replacementStore = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    fixture.viewModel.conversationMutationPostSaveHook = {
      switch change {
      case .coordinator: fixture.replaceServices()
      case .radio: fixture.currentRadioID = UUID()
      case .store: fixture.currentStore = replacementStore
      }
    }

    if deleting {
      try await fixture.viewModel.deleteDirectConversation(for: fixture.contact)
    } else {
      await fixture.viewModel.setNotificationLevel(fixture.conversation, level: .muted)
    }
    await fixture.finishEvents()

    #expect(await fixture.total() == 0)
    #expect(oldServices.syncCoordinator.conversationsVersion == 0)
    #expect(fixture.recorder.publications == (change == .coordinator ? [3, 0] : [3]))
    #expect(fixture.services.syncCoordinator.conversationsVersion == (change == .coordinator ? 1 : 0))
  }

  @Test(arguments: [Kind.channel, .room])
  func `sheet cancellation after a committed setting still publishes`(kind: Kind) async throws {
    let fixture = try await Fixture(kind: kind)
    defer { fixture.shutdown() }
    var operation: Task<Void, Never>?
    fixture.viewModel.conversationMutationPostSaveHook = { operation?.cancel() }
    operation = Task { await fixture.viewModel.setNotificationLevel(fixture.conversation, level: .muted) }
    await operation?.value
    await fixture.finishEvents()

    #expect(operation?.isCancelled == true)
    #expect(await fixture.total() == 0)
    #expect(fixture.recorder.publications == [3, 0])
  }
}
