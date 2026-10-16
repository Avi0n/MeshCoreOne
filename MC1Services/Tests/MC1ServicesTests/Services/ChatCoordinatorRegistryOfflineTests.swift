import Foundation
@testable import MC1Services
import Testing

@Suite("ChatCoordinatorRegistry Offline")
@MainActor
struct ChatCoordinatorRegistryOfflineTests {
  @Test func `coordinator against offline store returns coordinator bound to store`() async throws {
    let radioID = UUID()
    let contactID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let contact = ContactDTO.testContact(id: contactID, radioID: radioID)
    try await store.saveContact(contact)
    let message = MessageDTO.testDirectMessage(
      radioID: radioID,
      contactID: contactID,
      text: "hello",
      status: .delivered
    )
    try await store.saveMessage(message)

    let registry = ChatCoordinatorRegistry(dataStore: store)
    let coordinator = registry.coordinator(for: .dm(radioID: radioID, contactID: contactID))
    let messages = try await store.fetchMessages(contactID: contactID)

    #expect(messages.count == 1)
    #expect(messages.first?.text == "hello")
    #expect(coordinator.dataStore === store)
  }
}
