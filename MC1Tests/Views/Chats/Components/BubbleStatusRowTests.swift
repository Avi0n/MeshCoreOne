import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("BubbleStatusRow status text")
struct BubbleStatusRowTests {

    private func makeItem(
        status: MessageStatus,
        isChannelMessage: Bool,
        heardRepeats: Int = 0,
        sendCount: Int = 1
    ) -> MessageItem {
        MessageItem(
            id: UUID(),
            envelope: MessageEnvelope(
                messageID: UUID(),
                isOutgoing: true,
                senderName: "me",
                senderResolution: NodeNameResolution(displayName: "me", matchKind: .exact),
                status: status,
                date: Date(timeIntervalSince1970: 1_700_000_000),
                hasFailed: status == .failed,
                containsSelfMention: false,
                mentionSeen: false
            ),
            content: [],
            footer: MessageFooter(
                showHop: false,
                hopCount: 0,
                formattedPath: nil,
                regionToShow: nil,
                sendTimeToShow: nil,
                sendTimeWasCorrected: false,
                showStatusRow: true,
                status: status,
                isChannelMessage: isChannelMessage,
                heardRepeats: heardRepeats,
                retryAttempt: 0,
                maxRetryAttempts: 0,
                sendCount: sendCount
            ),
            grouping: GroupingFlags(
                showTimestamp: false,
                showDirectionGap: false,
                showSenderName: false,
                showNewMessagesDivider: false
            ),
            shouldRequestPreviewFetch: false
        )
    }

    // A DM `.sent` only means the radio queued the packet; it must read as
    // in-progress so the user never sees a settled "Sent" that later fails.
    @Test("DM .sent renders as Sending")
    func dmSentRendersSending() {
        let text = BubbleStatusRow.statusText(for: makeItem(status: .sent, isChannelMessage: false))
        #expect(text == L10n.Chats.Chats.Message.Status.sending)
    }

    // A channel broadcast has no ACK, so `.sent` is its terminal success state.
    @Test("Channel .sent renders as Sent")
    func channelSentRendersSent() {
        let text = BubbleStatusRow.statusText(for: makeItem(status: .sent, isChannelMessage: true))
        #expect(text == L10n.Chats.Chats.Message.Status.sent)
    }

    @Test("DM .delivered still renders as Delivered")
    func dmDeliveredRendersDelivered() {
        let text = BubbleStatusRow.statusText(for: makeItem(status: .delivered, isChannelMessage: false))
        #expect(text == L10n.Chats.Chats.Message.Status.delivered)
    }

    @Test("DM .failed still renders as Failed")
    func dmFailedRendersFailed() {
        let text = BubbleStatusRow.statusText(for: makeItem(status: .failed, isChannelMessage: false))
        #expect(text == L10n.Chats.Chats.Message.Status.failed)
    }
}
