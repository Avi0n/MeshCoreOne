import Foundation

/// Immutable, Sendable snapshot of the chat timeline as the view sees it.
/// Built by `ChatCoordinator` on every load or mutation. Views read through
/// `ChatViewModel` accessors that forward to the coordinator's current
/// `renderState`.
public struct ChatRenderState: Sendable, Equatable {
  /// Tri-state load phase that distinguishes "we haven't loaded yet" from
  /// "we loaded and the result was empty." Per-conversation views gate the
  /// empty-state placeholder on `phase == .loaded && items.isEmpty`, so a
  /// freshly-bound coordinator whose first fetch is still in-flight does
  /// not flash "No messages" before the awaited fetch lands.
  public enum LoadPhase: Sendable {
    case uninitialized
    case loading
    case loaded
  }

  public let items: [MessageItem]
  public let itemIndexByID: [UUID: Int]
  public let hasMoreMessages: Bool
  public let isLoadingOlder: Bool
  public let totalFetchedCount: Int
  public let phase: LoadPhase

  public init(
    items: [MessageItem],
    itemIndexByID: [UUID: Int],
    hasMoreMessages: Bool,
    isLoadingOlder: Bool,
    totalFetchedCount: Int,
    phase: LoadPhase = .uninitialized
  ) {
    self.items = items
    self.itemIndexByID = itemIndexByID
    self.hasMoreMessages = hasMoreMessages
    self.isLoadingOlder = isLoadingOlder
    self.totalFetchedCount = totalFetchedCount
    self.phase = phase
  }

  public static let empty = ChatRenderState(
    items: [],
    itemIndexByID: [:],
    hasMoreMessages: true,
    isLoadingOlder: false,
    totalFetchedCount: 0,
    phase: .uninitialized
  )

  /// Returns a new render state with the supplied fields overridden.
  /// Use at every mutation site to enforce "rebuild, never mutate in place."
  public func with(
    items: [MessageItem]? = nil,
    itemIndexByID: [UUID: Int]? = nil,
    hasMoreMessages: Bool? = nil,
    isLoadingOlder: Bool? = nil,
    totalFetchedCount: Int? = nil,
    phase: LoadPhase? = nil
  ) -> ChatRenderState {
    ChatRenderState(
      items: items ?? self.items,
      itemIndexByID: itemIndexByID ?? self.itemIndexByID,
      hasMoreMessages: hasMoreMessages ?? self.hasMoreMessages,
      isLoadingOlder: isLoadingOlder ?? self.isLoadingOlder,
      totalFetchedCount: totalFetchedCount ?? self.totalFetchedCount,
      phase: phase ?? self.phase
    )
  }

  /// Replace a single item by message ID. No-op if the ID is not present
  /// or if `transform` returns an equal item — returning `self` in those
  /// cases preserves the underlying `items` buffer so the caller's
  /// `newState != renderState` guard short-circuits on Array buffer
  /// identity instead of walking every item.
  public func updatingItem(id: UUID, _ transform: (MessageItem) -> MessageItem) -> ChatRenderState {
    guard let index = itemIndexByID[id] else { return self }
    let updated = transform(items[index])
    guard updated != items[index] else { return self }
    var newItems = items
    newItems[index] = updated
    return with(items: newItems)
  }

  /// Remove a single item by message ID. Rebuilds `itemIndexByID` since
  /// indices shift after removal. No-op if the ID is not present.
  public func removingItem(id: UUID) -> ChatRenderState {
    guard itemIndexByID[id] != nil else { return self }
    var newItems = items
    newItems.removeAll { $0.id == id }
    return with(items: newItems, itemIndexByID: newItems.indexByID())
  }

  /// Append a single item and update `itemIndexByID`. Caller is responsible
  /// for ensuring the ID is not already present (typically via an upstream
  /// `itemIndexByID[id] == nil` guard at the append site).
  public func appendingItem(_ item: MessageItem) -> ChatRenderState {
    var newItems = items
    newItems.append(item)
    var newIndex = itemIndexByID
    newIndex[item.id] = newItems.count - 1
    return with(
      items: newItems,
      itemIndexByID: newIndex,
      totalFetchedCount: totalFetchedCount + 1
    )
  }
}
