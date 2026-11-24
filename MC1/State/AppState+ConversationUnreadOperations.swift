import Foundation
import MC1Services

extension AppState {
  @MainActor
  struct ConversationUnreadOperations {
    let shouldPublish: @MainActor (LiveActivityManager) -> Bool
    let read: @MainActor (PersistenceStore, UUID) async -> Int
    let publish: @MainActor (LiveActivityManager, Int) async -> Void

    static var live: Self {
      Self(
        shouldPublish: { @MainActor manager in manager.hasActiveActivity },
        read: { @MainActor (store: PersistenceStore, radioID: UUID) async -> Int in
          let counts = await (try? store.getTotalUnreadCounts(radioID: radioID))
            ?? (contacts: 0, channels: 0, rooms: 0)
          return counts.contacts + counts.channels + counts.rooms
        },
        publish: { @MainActor manager, unreadCount in
          await manager.handleUnreadCountChanged(unreadCount: unreadCount)
        }
      )
    }
  }
}
