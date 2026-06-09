import Foundation

/// Owns `ChatCoordinator` instances keyed by `ChatConversationID`.
/// Owned by `AppState`; tears down on disconnect/radio-switch and rebinds
/// its dataStore when services arrive. Multiple consumers resolving the
/// same `ChatConversationID` share one `ChatCoordinator`, so canonical
/// chat state stays unified across views.
///
/// Capped by an LRU policy (default 16 entries) so the steady-state memory
/// footprint stays bounded even on long sessions across many conversations.
/// Evicted coordinators have their in-flight builds cancelled.
///
/// Intentionally not `@Observable` — views resolve one coordinator and
/// observe that coordinator's properties. The registry is a lookup table;
/// no view should observe its internal entries.
@MainActor
public final class ChatCoordinatorRegistry {

    public static let defaultCapacity = 16

    private var entries: [(id: ChatConversationID, coordinator: ChatCoordinator)] = []
    private let capacity: Int
    private(set) var dataStore: PersistenceStore

    public init(
        dataStore: PersistenceStore,
        capacity: Int = ChatCoordinatorRegistry.defaultCapacity
    ) {
        self.dataStore = dataStore
        self.capacity = capacity
    }

    /// Returns the coordinator for the given conversation, creating one on
    /// first request. Repeat reads promote the entry to most-recently-used.
    /// Two view models pointing at the same conversation share one coordinator.
    public func coordinator(for id: ChatConversationID) -> ChatCoordinator {
        if let index = entries.firstIndex(where: { $0.id == id }) {
            let entry = entries.remove(at: index)
            entries.append(entry)
            return entry.coordinator
        }
        let coordinator = ChatCoordinator(
            conversationID: id,
            dataStore: dataStore
        )
        entries.append((id, coordinator))
        while entries.count > capacity {
            let evicted = entries.removeFirst()
            evicted.coordinator.cancelInFlight()
        }
        return coordinator
    }

    public func rebind(dataStore: PersistenceStore) {
        tearDown()
        self.dataStore = dataStore
    }

    /// Cancel in-flight builds and drain Tasks on every coordinator and
    /// drop all entries.
    public func tearDown() {
        for entry in entries {
            entry.coordinator.cancelInFlight()
        }
        entries.removeAll()
    }
}
