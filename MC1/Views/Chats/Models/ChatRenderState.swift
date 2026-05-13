import Foundation
import MC1Services

/// Immutable, Sendable snapshot of the chat timeline as the view sees it.
/// Built by ChatViewModel on every load or mutation. Views read through
/// ChatViewModel accessors that forward to the current renderState.
struct ChatRenderState: Sendable, Equatable {
    let items: [MessageItem]
    let itemIndexByID: [UUID: Int]
    let hasMoreMessages: Bool
    let isLoadingOlder: Bool
    let totalFetchedCount: Int

    static let empty = ChatRenderState(
        items: [],
        itemIndexByID: [:],
        hasMoreMessages: true,
        isLoadingOlder: false,
        totalFetchedCount: 0
    )

    /// Returns a new render state with the supplied fields overridden.
    /// Use at every mutation site to enforce "rebuild, never mutate in place."
    func with(
        items: [MessageItem]? = nil,
        itemIndexByID: [UUID: Int]? = nil,
        hasMoreMessages: Bool? = nil,
        isLoadingOlder: Bool? = nil,
        totalFetchedCount: Int? = nil
    ) -> ChatRenderState {
        ChatRenderState(
            items: items ?? self.items,
            itemIndexByID: itemIndexByID ?? self.itemIndexByID,
            hasMoreMessages: hasMoreMessages ?? self.hasMoreMessages,
            isLoadingOlder: isLoadingOlder ?? self.isLoadingOlder,
            totalFetchedCount: totalFetchedCount ?? self.totalFetchedCount
        )
    }

    /// Replace a single item by message ID. No-op if the ID is not present.
    /// Eliminates the 16-field copy boilerplate at single-row mutation sites.
    func updatingItem(id: UUID, _ transform: (MessageItem) -> MessageItem) -> ChatRenderState {
        guard let index = itemIndexByID[id] else { return self }
        var newItems = items
        newItems[index] = transform(items[index])
        return self.with(items: newItems)
    }

    /// Remove a single item by message ID. Rebuilds `itemIndexByID` since
    /// indices shift after removal. No-op if the ID is not present.
    func removingItem(id: UUID) -> ChatRenderState {
        guard itemIndexByID[id] != nil else { return self }
        var newItems = items
        newItems.removeAll { $0.id == id }
        let newIndex = Dictionary(uniqueKeysWithValues:
            newItems.enumerated().map { ($0.element.id, $0.offset) })
        return self.with(items: newItems, itemIndexByID: newIndex)
    }

    /// Append a single item and update `itemIndexByID`. Caller is responsible
    /// for ensuring the ID is not already present (typically via an upstream
    /// `itemIndexByID[id] == nil` guard at the append site).
    func appendingItem(_ item: MessageItem, totalFetchedDelta: Int = 1) -> ChatRenderState {
        var newItems = items
        newItems.append(item)
        var newIndex = itemIndexByID
        newIndex[item.id] = newItems.count - 1
        return self.with(
            items: newItems,
            itemIndexByID: newIndex,
            totalFetchedCount: totalFetchedCount + totalFetchedDelta
        )
    }
}
