import Foundation
import MC1Services

/// Immutable, Sendable snapshot of the chat timeline as the view sees it.
/// Built by ChatViewModel on every load or mutation. Views read through
/// ChatViewModel accessors that forward to the current renderState.
struct ChatRenderState: Sendable, Equatable {
    let items: [MessageDisplayItem]
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
        items: [MessageDisplayItem]? = nil,
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
}
