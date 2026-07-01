import Foundation
import os

/// Executes the multi-service transactions behind notification actions:
/// quick reply, mark-as-read, and reaction notifications. Owned by
/// `ServiceContainer`; `AppState` installs thin forwarders on
/// `NotificationService` that delegate here and injects the two
/// app-layer inputs (connection readiness, local node name) via
/// `configure(isConnectionReady:localNodeName:)`.
@MainActor
public final class NotificationActionHandler {
  private static let logger = Logger(subsystem: "com.mc1", category: "NotificationActionHandler")

  // MARK: - Reaction Preview Truncation

  nonisolated static let reactionPreviewMaxLength = 50
  nonisolated static let reactionPreviewKeepLength = 47
  nonisolated static let reactionPreviewEllipsis = "..."

  /// Truncates a reacted-to message for display in the notification body.
  nonisolated static func reactionPreview(for text: String) -> String {
    text.count > reactionPreviewMaxLength
      ? String(text.prefix(reactionPreviewKeepLength)) + reactionPreviewEllipsis
      : text
  }

  // MARK: - Dependencies

  private let dataStore: any PersistenceStoreProtocol
  private let messageService: MessageService
  private let notificationService: NotificationService
  private let roomServerService: RoomServerService
  private let syncCoordinator: SyncCoordinator

  /// Whether the connection is ready for sends. Injected as a closure so
  /// every call reads the live app-facing connection state.
  private var isConnectionReady: @MainActor () -> Bool = { false }

  /// The connected device's node name, used to suppress self-reaction
  /// notifications. Nil means `configure` has not yet been called; a
  /// non-nil closure that returns nil means configured but the device name
  /// is not yet known. Injected as a closure because identity reconciliation
  /// can refresh the connected device at runtime.
  private var localNodeName: (@MainActor () -> String?)?

  public init(
    dataStore: any PersistenceStoreProtocol,
    messageService: MessageService,
    notificationService: NotificationService,
    roomServerService: RoomServerService,
    syncCoordinator: SyncCoordinator
  ) {
    self.dataStore = dataStore
    self.messageService = messageService
    self.notificationService = notificationService
    self.roomServerService = roomServerService
    self.syncCoordinator = syncCoordinator
  }

  /// Injects the app-layer inputs. Idempotent; re-run per connection when
  /// notification handling is configured.
  public func configure(
    isConnectionReady: @escaping @MainActor () -> Bool,
    localNodeName: @escaping @MainActor () -> String?
  ) {
    self.isConnectionReady = isConnectionReady
    self.localNodeName = localNodeName
  }

  /// Whether `configure` has been called. Used to distinguish the pre-wiring
  /// window from the steady-state where node name may legitimately be nil.
  var isConfigured: Bool {
    localNodeName != nil
  }

  // MARK: - Quick Reply

  public func handleQuickReply(contactID: UUID, text: String) async {
    guard let contact = try? await dataStore.fetchContact(id: contactID) else { return }

    if isConnectionReady() {
      do {
        _ = try await messageService.sendDirectMessage(text: text, to: contact)

        // Clear unread state - user replied so they've seen the chat
        try? await dataStore.clearUnreadCount(contactID: contactID)
        await notificationService.removeDeliveredNotifications(forContactID: contactID)
        await notificationService.updateBadgeCount()
        syncCoordinator.notifyConversationsChanged()
        return
      } catch {
        // Fall through to draft handling
      }
    }

    notificationService.saveDraft(for: contactID, text: text)
    await notificationService.postQuickReplyFailedNotification(
      contactName: contact.displayName,
      contactID: contactID
    )
  }

  public func handleChannelQuickReply(radioID: UUID, channelIndex: UInt8, text: String) async {
    // Fetch channel for display name in failure notification
    let channel = try? await dataStore.fetchChannel(radioID: radioID, index: channelIndex)
    let channelName = channelDisplayName(name: channel?.name, index: channelIndex)

    guard isConnectionReady() else {
      await notificationService.postChannelQuickReplyFailedNotification(
        channelName: channelName,
        radioID: radioID,
        channelIndex: channelIndex
      )
      return
    }

    do {
      _ = try await messageService.sendChannelMessage(
        text: text,
        channelIndex: channelIndex,
        radioID: radioID
      )

      // Clear unread state - user replied so they've seen the channel
      try? await dataStore.clearChannelUnreadCount(radioID: radioID, index: channelIndex)
      await notificationService.removeDeliveredNotifications(
        forChannelIndex: channelIndex,
        radioID: radioID
      )
      await notificationService.updateBadgeCount()
      syncCoordinator.notifyConversationsChanged()
    } catch {
      await notificationService.postChannelQuickReplyFailedNotification(
        channelName: channelName,
        radioID: radioID,
        channelIndex: channelIndex
      )
    }
  }

  /// Resolves a channel's display name, preferring the stored name, then
  /// the localized fallback, then a last-resort English literal.
  func channelDisplayName(name: String?, index: UInt8) -> String {
    name
      ?? notificationService.strings?.defaultChannelName(index: Int(index))
      ?? "Channel \(index)"
  }

  // MARK: - Mark as Read

  public func handleMarkAsRead(contactID: UUID, messageID: UUID) async {
    do {
      try await dataStore.markMessageAsRead(id: messageID)
      try await dataStore.clearUnreadCount(contactID: contactID)
      notificationService.removeDeliveredNotification(messageID: messageID)
      await notificationService.updateBadgeCount()
      syncCoordinator.notifyConversationsChanged()
    } catch {
      // Silently ignore
    }
  }

  public func handleChannelMarkAsRead(radioID: UUID, channelIndex: UInt8, messageID: UUID) async {
    do {
      try await dataStore.markMessageAsRead(id: messageID)
      try await dataStore.clearChannelUnreadCount(radioID: radioID, index: channelIndex)
      notificationService.removeDeliveredNotification(messageID: messageID)
      await notificationService.updateBadgeCount()
      syncCoordinator.notifyConversationsChanged()
    } catch {
      // Silently ignore
    }
  }

  public func handleRoomMarkAsRead(sessionID: UUID, messageID: UUID) async {
    do {
      try await roomServerService.markAsRead(sessionID: sessionID)
      notificationService.removeDeliveredNotification(messageID: messageID)
      await notificationService.updateBadgeCount()
      syncCoordinator.notifyConversationsChanged()
    } catch {
      // Silently ignore
    }
  }

  // MARK: - Reactions

  /// Handle posting a notification when someone reacts to the user's message
  public func handleReactionNotification(messageID: UUID) async {
    // Suppress the notification entirely when configure() has not yet been
    // called. Posting during that window risks notifying the user about their
    // own reaction; missing a stranger's reaction for a moment is harmless.
    guard let localNodeNameClosure = localNodeName else {
      Self.logger.debug("Reaction notification suppressed: handler not yet configured")
      return
    }

    // Fetch the message to check if it's outgoing
    guard let message = try? await dataStore.fetchMessage(id: messageID),
          message.direction == .outgoing else {
      return
    }

    // Fetch the latest reaction for this message
    guard let reactions = try? await dataStore.fetchReactions(for: messageID, limit: 1),
          let latestReaction = reactions.first else {
      return
    }

    // Check if this is a self-reaction (user reacting to their own message)
    if let nodeName = localNodeNameClosure(),
       latestReaction.senderName == nodeName {
      return
    }

    // Check mute status based on message type
    let isMuted: Bool
    if let contactID = message.contactID {
      let contact = try? await dataStore.fetchContact(id: contactID)
      isMuted = contact?.isMuted ?? false
    } else if let channelIndex = message.channelIndex {
      let channel = try? await dataStore.fetchChannel(radioID: message.radioID, index: channelIndex)
      isMuted = channel?.isMuted ?? false
    } else {
      isMuted = false
    }

    guard !isMuted else { return }

    let truncatedPreview = Self.reactionPreview(for: message.text)
    let body = notificationService.strings?.reactionNotificationBody(
      emoji: latestReaction.emoji,
      messagePreview: truncatedPreview
    ) ?? "Reacted \(latestReaction.emoji) to your message: \"\(truncatedPreview)\""

    // Post the notification
    await notificationService.postReactionNotification(
      reactorName: latestReaction.senderName,
      body: body,
      messageID: messageID,
      contactID: message.contactID,
      channelIndex: message.channelIndex,
      radioID: message.channelIndex != nil ? message.radioID : nil
    )
  }
}
