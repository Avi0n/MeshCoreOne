import Foundation
import MC1Services
import SwiftUI

extension ChatViewModel {
  /// Fold a `MessageEvent` from `MessageEventStream` into view-model state.
  /// Called on the main actor from a SwiftUI `.task` consumer in
  /// `ChatConversationView`. The exhaustive switch is deliberate — a new
  /// `MessageEvent` case becomes a compile error rather than a silent skip.
  ///
  /// The function is `async` so the incoming-message admission path can
  /// await its prefetch race inline. The event stream is the canonical
  /// ordering source for received messages; admitting incoming bubbles
  /// via a detached `Task { ... }` would let a fast plain-text message
  /// overtake a slow URL-bearing one and reorder the timeline.
  func handle(_ event: MessageEvent) async {
    switch event {
    case let .directMessageReceived(message, contact):
      guard let current = currentContact, current.id == contact.id else { return }
      await admitIncomingMessage(message, isChannelMessage: false)
      recordIncomingMentionIfNeeded(message)

    case let .channelMessageReceived(message, channelIndex):
      guard let channel = currentChannel,
            channel.index == channelIndex,
            message.radioID == channel.radioID else { return }
      await admitIncomingMessage(message, isChannelMessage: true)
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
      await markFailedSendSeenIfCurrent(messageID: messageID)

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

  /// Local enqueue/retry threw before the send queue could emit `.messageFailed`.
  /// Writes `.failed` (which resets `failureSeen`) then marks the open thread
  /// seen so the list badge does not reappear on pop-back.
  func recordLocalEnqueueFailure(messageID: UUID, error: Error) async {
    logger.error("enqueue failed for messageID=\(messageID, privacy: .public): \(String(describing: error))")
    _ = try? await dataStore?.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
    timeline.applyStatusUpdate(messageID: messageID, status: .failed)
    sendErrorMessage = Self.copyForEnqueueFailure(error)
    await markFailedSendSeenIfCurrent(messageID: messageID)
  }

  /// A fail that lands while this thread is open is already on screen; mark it
  /// seen so the list badge does not reappear on pop-back.
  private func markFailedSendSeenIfCurrent(messageID: UUID) async {
    guard let dataStore, let message = try? await dataStore.fetchMessage(id: messageID) else {
      return
    }
    do {
      if let contact = currentContact, message.contactID == contact.id {
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
