import Foundation
import MC1Services
import SwiftUI

extension ChatViewModel {
  /// Fold a `MessageEvent` from `MessageEventStream` into view-model state.
  /// Called on the main actor from a SwiftUI `.task` consumer in
  /// `ChatConversationView`. The exhaustive switch is deliberate — a new
  /// `MessageEvent` case becomes a compile error rather than a silent skip.
  ///
  /// Incoming messages are inserted in event order before their previews load.
  func handle(_ event: MessageEvent) async {
    switch event {
    case let .directMessageReceived(message, contact, scope):
      guard let current = currentContact, current.id == contact.id else { return }
      guard let dataStore,
            await (try? dataStore.currentDirectMessageContact(contact, scope: scope)) != nil,
            scope.isCurrent, currentContact?.id == contact.id else { return }
      admitIncomingMessage(message, isChannelMessage: false)
      recordIncomingMentionIfNeeded(message)

    case let .channelMessageReceived(message, channelIndex, scope):
      guard let channel = currentChannel,
            channel.index == channelIndex,
            message.radioID == channel.radioID,
            scope.isCurrent else { return }
      admitIncomingMessage(message, isChannelMessage: true)
      recordIncomingMentionIfNeeded(message)

    case let .messageStatusResolved(messageID, status, roundTripTime):
      // Status-only resolution: apply in place so the bubble's status
      // footer crossfades from "Sent" to "Delivered" rather than
      // restarting on a fresh item identity. No DB fetch — the
      // dispatcher writes the DB row before firing this case.
      withAnimation {
        timeline.applyStatusUpdate(
          messageID: messageID,
          status: status,
          roundTripTime: roundTripTime
        )
      }

    case let .messageRetrying(messageID, _, _):
      // Payload-bearing variant routed straight to the reload chokepoint;
      // not coalescer-eligible because attempt/maxAttempts are per-event.
      timeline.enqueueReload(messageID: messageID)

    case let .messageResent(messageID):
      timeline.enqueueReload(messageID: messageID)

    case let .messageFailed(messageID):
      timeline.enqueueReload(messageID: messageID)
      await handleMessageFailure(messageID: messageID)

    case let .heardRepeatRecorded(messageID, _),
         let .reactionReceived(messageID, _):
      timeline.enqueueReload(messageID: messageID)

    case let .messagesRegionUpdated(messageIDs):
      // Region reprocess rewrote dual fields; re-fetch to re-bake region chips.
      timeline.enqueueReload(updatedMessageIDs: Set(messageIDs))

    case let .routingChanged(contactID, _):
      guard let current = currentContact, current.id == contactID else { return }
      requestContactRefresh()

    case .roomMessageReceived, .roomMessageStatusUpdated, .roomMessageFailed:
      // Room events go to RemoteNodes via MessageEventStream subscription
      // in RoomConversationView. Enumerated explicitly so adding a new
      // MessageEvent case surfaces as a non-exhaustive switch compile
      // error rather than a silent skip.
      break
    }
  }

  private func requestContactRefresh() {
    contactRefreshSignal &+= 1
  }

  private func handleMessageFailure(messageID: UUID) async {
    let writer = timelineWriter
    let coordinator = self.coordinator
    guard let dataStore, let message = try? await dataStore.fetchMessage(id: messageID) else {
      return
    }

    let isReaction = message.isOutgoing && (message.contactID != nil
      ? ReactionParser.parseDM(message.text) != nil
      : ReactionParser.parse(message.text) != nil)
    if isReaction {
      let isCurrentConversation = currentContact.map {
        message.radioID == $0.radioID && message.contactID == $0.id
      } == true || currentChannel.map {
        message.radioID == $0.radioID && message.channelIndex == $0.index
      } == true
      guard !Task.isCancelled, let writer, writer === timelineWriter, writer.isCurrent,
            message.status == .failed, isCurrentConversation else { return }
      if messagesByID[messageID] == nil {
        guard let conversation = timeline.conversation else { return }
        let outcome = await timeline.open(
          conversation, reactions: nil, populateMode: .refreshWindow, includingSortDate: message.sortDate
        )
        guard !Task.isCancelled, writer === timelineWriter, writer.isCurrent else { return }
        guard case .loaded = outcome else {
          sendErrorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
          return
        }
        await coordinator?.buildItemsTask?.value
        guard !Task.isCancelled, writer === timelineWriter, writer.isCurrent else { return }
      }
      guard messagesByID[messageID]?.status == .failed else { return }
      sendErrorMessage = L10n.Chats.Chats.Alert.UnableToSend.message
    }

    await markFailedSendSeenIfCurrent(message: message)
  }

  /// Persists local enqueue failures before presentation because the queue emits no failure event.
  func recordLocalEnqueueFailure(messageID: UUID, error: Error) async {
    logger.error("enqueue failed for messageID=\(messageID, privacy: .public): \(String(describing: error))")
    _ = try? await dataStore?.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
    timeline.applyStatusUpdate(messageID: messageID, status: .failed)
    await handleMessageFailure(messageID: messageID)
    sendErrorMessage = Self.copyForEnqueueFailure(error)
  }

  /// Marks Failed-send seen in the open Conversation so its list badge stays cleared.
  private func markFailedSendSeenIfCurrent(message: MessageDTO) async {
    guard let dataStore else { return }
    do {
      if let contact = currentContact,
         message.radioID == contact.radioID, message.contactID == contact.id {
        try await dataStore.markFailedSendsSeen(contactID: contact.id)
        syncCoordinator?.notifyConversationsChanged()
      } else if let channel = currentChannel,
                message.channelIndex == channel.index,
                message.radioID == channel.radioID {
        try await dataStore.markFailedSendsSeen(
          radioID: channel.radioID,
          channelIndex: channel.index
        )
        syncCoordinator?.notifyConversationsChanged()
      }
    } catch {
      logger.warning("Failed to mark in-thread failed send seen: \(error.localizedDescription)")
    }
  }

  private func recordIncomingMentionIfNeeded(_ message: MessageDTO) {
    guard message.containsSelfMention else { return }
    mentionSequence &+= 1
    lastIncomingMention = MentionEvent(messageID: message.id, sequence: mentionSequence)
  }
}
