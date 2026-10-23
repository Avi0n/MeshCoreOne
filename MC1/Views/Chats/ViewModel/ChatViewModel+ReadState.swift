import Foundation
import MC1Services

extension ChatViewModel {
  private enum ReadStateConversationKind: Hashable {
    case direct, channel, room
  }

  private struct ReadStateOperationKey: Hashable {
    let storeID: ObjectIdentifier
    let radioID: UUID
    let kind: ReadStateConversationKind
    let rowID: UUID
  }

  private struct ReadStateTask {
    let id: UUID
    let task: Task<Void, Never>
  }

  // Windows share persistence, so command order must not depend on a view model instance.
  private static var latestReadStateOperations: [ReadStateOperationKey: UUID] = [:]
  private static var pendingReadStateTasks: [ReadStateOperationKey: ReadStateTask] = [:]

  func setReadState(_ conversation: Conversation, isRead: Bool) async {
    let radioID = switch conversation {
    case let .direct(contact): contact.radioID
    case let .channel(channel): channel.radioID
    case let .room(session): session.radioID
    }
    let capturedStore = dataStore
    let ownsList: @MainActor () -> Bool = { [weak self] in
      guard let self else { return false }
      return currentRadioIDProvider() == radioID && dataStore === capturedStore
    }
    guard let capturedStore else {
      if !Task.isCancelled, ownsList() { errorMessage = L10n.Chats.Chats.Error.readStateSaveFailed }
      return
    }
    let kind: ReadStateConversationKind = switch conversation {
    case .direct: .direct
    case .channel: .channel
    case .room: .room
    }
    let key = ReadStateOperationKey(
      storeID: ObjectIdentifier(capturedStore), radioID: radioID, kind: kind, rowID: conversation.id
    )
    let operationID = UUID()
    Self.latestReadStateOperations[key] = operationID
    defer {
      if Self.latestReadStateOperations[key] == operationID { Self.latestReadStateOperations[key] = nil }
      if Self.pendingReadStateTasks[key]?.id == operationID { Self.pendingReadStateTasks[key] = nil }
    }

    let identifiers: [String] = if isRead {
      switch conversation {
      case let .direct(contact):
        await NotificationService.deliveredNotificationIdentifiers(
          forContactID: contact.id, systemOperations: notificationSystemOperations
        )
      case let .channel(channel):
        await NotificationService.deliveredNotificationIdentifiers(
          forChannelIndex: channel.index, radioID: radioID, systemOperations: notificationSystemOperations
        )
      case let .room(session):
        await NotificationService.deliveredNotificationIdentifiers(
          forRoomSessionID: session.id, systemOperations: notificationSystemOperations
        )
      }
    } else {
      []
    }

    guard Self.latestReadStateOperations[key] == operationID else { return }
    let predecessor = Self.pendingReadStateTasks[key]?.task
    let task = Task { @MainActor in
      #if DEBUG
        await readStateBeforePredecessorInterleaveHook?()
      #endif
      // Even superseded tasks wait, so successors cannot bypass an admitted predecessor.
      await predecessor?.value
      guard Self.latestReadStateOperations[key] == operationID else { return }

      do {
        switch conversation {
        case let .direct(contact):
          try await capturedStore.setContactReadState(contact.id, isRead: isRead)
        case let .channel(channel):
          try await capturedStore.setChannelReadState(channel.id, isRead: isRead)
        case let .room(session):
          try await capturedStore.setRoomReadState(session.id, isRead: isRead)
        }
      } catch {
        logger.error("Failed to save conversation read state: \(error)")
        if !Task.isCancelled, ownsList(), Self.latestReadStateOperations[key] == operationID {
          errorMessage = L10n.Chats.Chats.Error.readStateSaveFailed
        }
        return
      }

      if !identifiers.isEmpty {
        notificationSystemOperations.removeDeliveredNotifications(identifiers)
      }
      guard !Task.isCancelled, ownsList() else { return }
      syncCoordinator?.notifyConversationsChanged()
      let reload = requestConversationReload()
      await withTaskCancellationHandler {
        await reload?.value
      } onCancel: {
        reload?.cancel()
      }
      guard !Task.isCancelled, ownsList() else { return }
      await refreshReadStateBadge(dataStore: capturedStore, radioID: radioID)
    }
    Self.pendingReadStateTasks[key] = ReadStateTask(id: operationID, task: task)
    await withTaskCancellationHandler {
      await task.value
    } onCancel: {
      task.cancel()
    }
  }

  func refreshReadStateBadge(dataStore: DataStore, radioID: UUID) async {
    let capturedService = notificationService
    let shouldPublish: @MainActor () -> Bool = { [weak self] in
      guard let self else { return false }
      return currentRadioIDProvider() == radioID
        && self.dataStore === dataStore && notificationService === capturedService
    }
    guard !Task.isCancelled, shouldPublish() else { return }
    if let capturedService {
      await capturedService.updateBadgeCount(shouldPublish: shouldPublish)
      return
    }
    do {
      let counts = try await dataStore.getTotalUnreadCounts(radioID: radioID)
      #if DEBUG
        await readStateBadgeFetchInterleaveHook?()
      #endif
      guard !Task.isCancelled, shouldPublish() else { return }
      await NotificationService.updateAppIconBadge(
        unreadCounts: counts, notificationDefaults: notificationDefaults, systemOperations: notificationSystemOperations
      )
    } catch {
      logger.warning("Failed to refresh unread badge: \(error)")
    }
  }
}
