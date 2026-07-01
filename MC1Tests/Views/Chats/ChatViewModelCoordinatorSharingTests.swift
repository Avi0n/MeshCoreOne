import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

/// Verifies that two `ChatViewModel`s pointing at the same conversation
/// share one `ChatCoordinator` instance via the registry. Closes the
/// iPad split-view duplicate-state hole the coordinator refactor is
/// designed to prevent: an update applied through one view model must
/// be visible to the other immediately, with no per-view duplication.
@Suite("ChatViewModel coordinator sharing")
@MainActor
struct ChatViewModelCoordinatorSharingTests {
  private func makeRegistry() throws -> ChatCoordinatorRegistry {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    return ChatCoordinatorRegistry(dataStore: dataStore)
  }

  @Test
  func `Two ChatViewModels on the same conversation share one ChatCoordinator`() throws {
    let registry = try makeRegistry()
    let radioID = UUID()
    let contactID = UUID()
    let id = ChatConversationID.dm(radioID: radioID, contactID: contactID)

    let viewModelA = ChatViewModel()
    let viewModelB = ChatViewModel()
    viewModelA.coordinator = registry.coordinator(for: id)
    viewModelB.coordinator = registry.coordinator(for: id)

    let message = makeDirectMessage(radioID: radioID, contactID: contactID)
    _ = viewModelA.coordinator?.append(message)

    #expect(viewModelB.messages.count == 1)
    #expect(viewModelB.messagesByID[message.id] == message)
  }

  private func makeDirectMessage(radioID: UUID, contactID: UUID) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: nil,
      text: "shared",
      timestamp: 1000,
      createdAt: Date(timeIntervalSince1970: 1000),
      direction: .outgoing,
      status: .sent,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  @Test
  func `Distinct conversations get distinct coordinators`() throws {
    let registry = try makeRegistry()
    let radioID = UUID()
    let contactA = UUID()
    let contactB = UUID()

    let viewModelA = ChatViewModel()
    let viewModelB = ChatViewModel()
    viewModelA.coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactA))
    viewModelB.coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactB))

    let messageForA = makeDirectMessage(radioID: radioID, contactID: contactA)
    _ = viewModelA.coordinator?.append(messageForA)

    #expect(viewModelA.messages.count == 1)
    #expect(viewModelB.messages.isEmpty)
  }
}
