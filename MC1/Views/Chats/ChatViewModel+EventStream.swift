import Foundation
import MC1Services

/// Identifies an incoming self-mention with a per-mention sequence number so
/// `.onChange(of:)` fires for consecutive mentions of the same message.
/// `Equatable` is auto-synthesised from `UUID` + `UInt64`; adding a non-Equatable
/// field would break `.onChange` propagation.
struct MentionEvent: Equatable, Sendable {
    let messageID: UUID
    let sequence: UInt64
}

extension ChatViewModel {

    /// Fold a `MessageEvent` from `MessageEventStream` into view-model state.
    /// Called on the main actor from a SwiftUI `.task` consumer in
    /// `ChatConversationView`. The exhaustive switch is deliberate — a new
    /// `MessageEvent` case becomes a compile error rather than a silent skip.
    func handle(_ event: MessageEvent) {
        switch event {
        case .directMessageReceived(let message, let contact):
            guard let current = currentContact, current.id == contact.id else { return }
            appendMessageIfNew(message)
            recordIncomingMentionIfNeeded(message)

        case .channelMessageReceived(let message, let channelIndex):
            guard let channel = currentChannel,
                  channel.index == channelIndex,
                  message.radioID == channel.radioID else { return }
            appendMessageIfNew(message)
            recordIncomingMentionIfNeeded(message)

        case .messageStatusUpdated:
            // ackCode→messageID resolution happens outside the broadcaster,
            // so we cannot gate on timeline membership here; trigger a full
            // reload so SwiftData reports whatever state changed.
            requestReload()

        case .messageRetrying(let messageID, _, _):
            // O(1) timeline-membership check — avoids churning the data store
            // for retries on conversations the user is not currently viewing.
            guard renderState.itemIndexByID[messageID] != nil else { return }
            requestReload()

        case .messageFailed(let messageID):
            // O(1) timeline-membership check via the renderState index avoids
            // a `messages.contains(where:)` scan under bursts of fail events.
            guard renderState.itemIndexByID[messageID] != nil else { return }
            requestReload()

        case .routingChanged(let contactID, _):
            guard let current = currentContact, current.id == contactID else { return }
            requestContactRefresh()

        case .heardRepeatRecorded(let messageID, let count):
            guard renderState.itemIndexByID[messageID] != nil else { return }
            updateHeardRepeats(for: messageID, count: count)

        case .reactionReceived(let messageID, let summary):
            guard renderState.itemIndexByID[messageID] != nil else { return }
            updateReactionSummary(for: messageID, summary: summary)

        case .roomMessageReceived, .roomMessageStatusUpdated, .roomMessageFailed:
            // Room events go to RemoteNodes via MessageEventStream subscription
            // in RoomConversationView. Enumerated explicitly so adding a new
            // MessageEvent case surfaces as a non-exhaustive switch compile
            // error rather than a silent skip.
            break
        }
    }

    /// Chase-the-counter reload coalescer. While a reload is in flight,
    /// additional `.onChange` fires do not spawn new tasks; the running task
    /// re-checks `reloadSignal` after its fetch completes and loops if the
    /// counter has advanced. Worst case under a burst: one extra iteration
    /// after the burst ends.
    func coalescedReload(for conversationType: ChatConversationType) async {
        guard !reloadInFlight else { return }
        reloadInFlight = true
        defer { reloadInFlight = false }

        var lastValue: UInt64 = .max
        while reloadSignal != lastValue {
            lastValue = reloadSignal
            switch conversationType {
            case .dm(let contact):
                await loadMessages(for: contact)
            case .channel(let channel):
                await loadChannelMessages(for: channel)
            }
        }
    }

    private func requestReload() {
        reloadSignal &+= 1
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
