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

    @MainActor
    @Test func rebind_withDifferentStore_clearsExistingCoordinators() async throws {
        let containerA = try PersistenceStore.createContainer(inMemory: true)
        let containerB = try PersistenceStore.createContainer(inMemory: true)
        let storeA = PersistenceStore(modelContainer: containerA)
        let storeB = PersistenceStore(modelContainer: containerB)
        let registry = ChatCoordinatorRegistry(dataStore: storeA)
        let id = ChatConversationID.dm(radioID: UUID(), contactID: UUID())
        let first = registry.coordinator(for: id)

        registry.rebind(dataStore: storeB)
        let second = registry.coordinator(for: id)

        #expect(first !== second)
        #expect(registry.dataStore === storeB)
    }

    @Test func coordinator_exceedingCap_evictsLeastRecentlyUsed() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let registry = ChatCoordinatorRegistry(dataStore: store, capacity: 2)
        let radioID = UUID()

        let idA = ChatConversationID.dm(radioID: radioID, contactID: UUID())
        let idB = ChatConversationID.dm(radioID: radioID, contactID: UUID())
        let idC = ChatConversationID.dm(radioID: radioID, contactID: UUID())

        let firstA = registry.coordinator(for: idA)
        _ = registry.coordinator(for: idB)
        _ = registry.coordinator(for: idC)        // evicts A

        let secondA = registry.coordinator(for: idA)
        #expect(firstA !== secondA, "Evicted entry should be reconstructed")
    }

    @Test func coordinator_touchingEntry_promotesItToMostRecentlyUsed() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let registry = ChatCoordinatorRegistry(dataStore: store, capacity: 2)
        let radioID = UUID()

        let idA = ChatConversationID.dm(radioID: radioID, contactID: UUID())
        let idB = ChatConversationID.dm(radioID: radioID, contactID: UUID())
        let idC = ChatConversationID.dm(radioID: radioID, contactID: UUID())

        let firstA = registry.coordinator(for: idA)
        _ = registry.coordinator(for: idB)
        _ = registry.coordinator(for: idA)   // touch — promotes A
        _ = registry.coordinator(for: idC)   // evicts B, not A

        let secondA = registry.coordinator(for: idA)
        #expect(firstA === secondA, "Touched entry should survive eviction")
    }
}
