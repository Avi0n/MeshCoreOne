import Foundation

/// Owns `ChatCoordinator` instances keyed by `ChatConversationID`.
/// Owned by `AppState` and outlives connections. Multiple consumers
/// resolving the same `ChatConversationID` share one `ChatCoordinator`,
/// so canonical chat state stays unified across views.
///
/// Capped by an LRU policy (default 16 entries) so the steady-state memory
/// footprint stays bounded even on long sessions across many conversations.
/// Evicted coordinators have their in-flight builds cancelled. `clear()`
/// empties entries; the registry stays and later lookups mint fresh ones.
///
/// Intentionally not `@Observable` — views resolve one coordinator and
/// observe that coordinator's properties. The registry is a lookup table;
/// no view should observe its internal entries.
@MainActor
public final class ChatCoordinatorRegistry {
  public static let defaultCapacity = 16

  private var entries: [(id: ChatConversationID, coordinator: ChatCoordinator)] = []
  private let capacity: Int
  private let dataStore: PersistenceStore

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

  /// Returns the coordinator already tracked for `id`, or nil if none exists.
  /// A pure lookup: it neither creates an entry nor promotes LRU order, so the
  /// navigation-time prefetch can check whether a conversation is already warm
  /// without polluting the cache.
  public func existingCoordinator(for id: ChatConversationID) -> ChatCoordinator? {
    entries.first(where: { $0.id == id })?.coordinator
  }

  /// Cancel in-flight builds and drop all entries. The registry stays
  /// usable; `coordinator(for:)` mints fresh empty entries.
  public func clear() {
    for entry in entries {
      entry.coordinator.cancelInFlight()
    }
    entries.removeAll()
  }
}
