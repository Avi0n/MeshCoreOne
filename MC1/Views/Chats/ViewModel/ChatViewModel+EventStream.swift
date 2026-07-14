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
        timelineWriter?.applyStatusUpdate(
          messageID: messageID,
          status: status,
          roundTripTime: roundTripTime
        )
      }

    case let .messageRetrying(messageID, _, _):
      // Payload-bearing variant routed straight to the reload chokepoint;
      // not coalescer-eligible because attempt/maxAttempts are per-event.
      timelineWriter?.enqueueReload(messageID: messageID)

    case let .messageResent(messageID),
         let .messageFailed(messageID):
      timelineWriter?.enqueueReload(messageID: messageID)

    case let .heardRepeatRecorded(messageID, _),
         let .reactionReceived(messageID, _):
      timelineWriter?.enqueueReload(messageID: messageID)

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

  private func recordIncomingMentionIfNeeded(_ message: MessageDTO) {
    guard message.containsSelfMention else { return }
    mentionSequence &+= 1
    lastIncomingMention = MentionEvent(messageID: message.id, sequence: mentionSequence)
  }
}
