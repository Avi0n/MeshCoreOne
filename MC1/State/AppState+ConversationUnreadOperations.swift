import Foundation
import MC1Services

extension AppState {
  @MainActor
  struct ConversationUnreadOperations {
    let read: @MainActor (PersistenceStore, UUID) async -> Int
    let publish: @MainActor (LiveActivityManager, Int) async -> Void

    static let live = Self(
      read: { store, radioID in
        let counts = await (try? store.getTotalUnreadCounts(radioID: radioID))
          ?? (contacts: 0, channels: 0, rooms: 0)
        return counts.contacts + counts.channels + counts.rooms
      },
      publish: { await $0.handleUnreadCountChanged(unreadCount: $1) }
    )
  }
}
