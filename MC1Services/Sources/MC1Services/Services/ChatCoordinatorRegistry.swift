import Foundation

/// Owns `ChatCoordinator` instances keyed by `ChatConversationID`.
/// Lives on `ServiceContainer`; tears down when the container is destroyed.
/// Multiple consumers resolving the same `ChatConversationID` share one
/// `ChatCoordinator`, so canonical chat state stays unified across views.
///
/// Intentionally not `@Observable` — views resolve one coordinator and
/// observe that coordinator's properties. The registry is a lookup table;
/// no view should observe its internal `coordinators` dictionary.
@MainActor
public final class ChatCoordinatorRegistry {

    private var coordinators: [ChatConversationID: ChatCoordinator] = [:]

    private let dataStore: PersistenceStore

    init(dataStore: PersistenceStore) {
        self.dataStore = dataStore
    }

    /// Returns the coordinator for the given conversation, creating one on
    /// first request. Two view models pointing at the same conversation share
    /// one coordinator.
    public func coordinator(for id: ChatConversationID) -> ChatCoordinator {
        if let existing = coordinators[id] { return existing }
        let new = ChatCoordinator(
            conversationID: id,
            dataStore: dataStore
        )
        coordinators[id] = new
        return new
    }

    /// Cancel in-flight builds and drain Tasks on every coordinator and
    /// drop all entries.
    ///
    /// Called from `ServiceContainer.tearDown()` so off-main `rebuildItems`,
    /// `coalescedReload`, and `hardReset` Tasks running on a coordinator
    /// from the prior connection do not finish against a stale `dataStore`
    /// reference after the container is released.
    func tearDown() {
        for coordinator in coordinators.values {
            coordinator.cancelInFlight()
        }
        coordinators.removeAll()
    }
}
