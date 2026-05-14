import Foundation
import Testing
@testable import MC1Services

@Suite("ChatCoordinatorRegistry")
@MainActor
struct ChatCoordinatorRegistryTests {

    private func makeRegistry() throws -> ChatCoordinatorRegistry {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        return ChatCoordinatorRegistry(dataStore: dataStore)
    }

    @Test("coordinator(for:) returns the same instance on repeat calls")
    func coordinatorFor_returnsSameInstance() throws {
        let registry = try makeRegistry()
        let id = ChatConversationID.dm(radioID: UUID(), contactID: UUID())

        let first = registry.coordinator(for: id)
        let second = registry.coordinator(for: id)

        #expect(first === second)
    }

    @Test("Distinct conversation IDs yield distinct coordinators")
    func distinctIDs_yieldDistinctCoordinators() throws {
        let registry = try makeRegistry()
        let radioID = UUID()
        let dmID = ChatConversationID.dm(radioID: radioID, contactID: UUID())
        let channelID = ChatConversationID.channel(radioID: radioID, channelIndex: 0)

        let dm = registry.coordinator(for: dmID)
        let channel = registry.coordinator(for: channelID)

        #expect(dm !== channel)
    }
}
