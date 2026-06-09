import Foundation
import Testing
@testable import MC1Services

@Suite("ChatCoordinatorRegistry Offline")
@MainActor
struct ChatCoordinatorRegistryOfflineTests {

    @Test func coordinator_againstOfflineStore_returnsCoordinatorBoundToStore() async throws {
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

    @Test func rebind_servicesArrives_replacesCoordinatorAgainstNewStore() async throws {
        let radioID = UUID()
        let contactID = UUID()
        let offlineStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
        try await offlineStore.saveContact(ContactDTO.testContact(id: contactID, radioID: radioID))
        try await offlineStore.saveMessage(MessageDTO.testDirectMessage(
            radioID: radioID,
            contactID: contactID,
            text: "offline",
            status: .delivered
        ))

        let registry = ChatCoordinatorRegistry(dataStore: offlineStore)
        let id = ChatConversationID.dm(radioID: radioID, contactID: contactID)
        let beforeRebind = registry.coordinator(for: id)

        let onlineContainer = try PersistenceStore.createContainer(inMemory: true)
        let onlineStore = PersistenceStore(modelContainer: onlineContainer)
        registry.rebind(dataStore: onlineStore)

        let afterRebind = registry.coordinator(for: id)
        #expect(beforeRebind !== afterRebind, "rebind should tear down the offline coordinator")
        #expect(afterRebind.dataStore === onlineStore, "fresh coordinator binds to new store")
    }
}
