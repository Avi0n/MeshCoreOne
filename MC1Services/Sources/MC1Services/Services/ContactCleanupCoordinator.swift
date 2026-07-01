import Foundation

/// Runs the cross-service side effects of a contact lifecycle change:
/// channel-message deletion and blocked-name cache refresh on block,
/// notification cleanup and badge updates, and remote node session removal
/// on delete. Built by `ServiceContainer` and injected into `ContactService`.
///
/// Holds the collaborating services directly, never the `ServiceContainer`,
/// so a torn-down container cannot be kept alive through this coordinator.
struct ContactCleanupCoordinator: ContactCleanupHandling {
  private let dataStore: any ContactPersisting & RoomPersisting
  private let syncCoordinator: SyncCoordinator
  private let notificationService: NotificationService
  private let remoteNodeService: RemoteNodeService

  init(
    dataStore: any ContactPersisting & RoomPersisting,
    syncCoordinator: SyncCoordinator,
    notificationService: NotificationService,
    remoteNodeService: RemoteNodeService
  ) {
    self.dataStore = dataStore
    self.syncCoordinator = syncCoordinator
    self.notificationService = notificationService
    self.remoteNodeService = remoteNodeService
  }

  func handleCleanup(contactID: UUID, reason: ContactCleanupReason, publicKey: Data) async {
    // Refresh blocked names cache and delete channel messages on block
    if reason == .blocked || reason == .unblocked {
      if let contact = try? await dataStore.fetchContact(id: contactID) {
        if reason == .blocked {
          try? await dataStore.deleteChannelMessages(
            fromSender: contact.name, radioID: contact.radioID
          )
        }
        await syncCoordinator.refreshBlockedContactsCache(
          radioID: contact.radioID, dataStore: dataStore
        )
        await syncCoordinator.notifyConversationsChanged()
      }
    }

    // Remove delivered notifications for this contact (only on block/delete)
    if reason == .blocked || reason == .deleted {
      await notificationService.removeDeliveredNotifications(forContactID: contactID)
    }

    // Update badge count
    await notificationService.updateBadgeCount()

    // Clean up any associated remote node session on delete
    if reason == .deleted {
      if let session = try? await dataStore.fetchRemoteNodeSession(publicKey: publicKey) {
        try? await remoteNodeService.removeSession(id: session.id, publicKey: publicKey)
      }
      await syncCoordinator.notifyConversationsChanged()
    }
  }
}
