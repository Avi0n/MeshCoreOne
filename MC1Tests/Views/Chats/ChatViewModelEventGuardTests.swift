import Testing
import Foundation
@testable import MC1
@testable import MC1Services

/// Verifies that event-stream guards read `messagesByID` rather than
/// `renderState.itemIndexByID`. The render-state index lags `messages` by
/// one off-main build cycle; if an ACK / retry / fail / heard-repeat /
/// reaction event lands during that window, reading the render-state index
/// silently drops the event. Reading `messagesByID` keeps the guard in sync
/// with the canonical message list.
@Suite("ChatViewModel event-stream guards")
@MainActor
struct ChatViewModelEventGuardTests {

    private func makeMessage(id: UUID = UUID()) -> MessageDTO {
        MessageDTO(
            id: id,
            radioID: UUID(),
            contactID: UUID(),
            channelIndex: nil,
            text: "hello",
            timestamp: 1_000,
            createdAt: Date(timeIntervalSince1970: 1_000),
            direction: .outgoing,
            status: .sending,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: nil,
            isRead: false,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
    }

    @Test("messageStatusResolved bumps reloadSignal when messagesByID holds the id and renderState lags")
    func messageStatusResolvedBumpsReloadSignalDuringBuildGap() {
        let viewModel = ChatViewModel()
        let message = makeMessage()
        viewModel.messages = [message]
        viewModel.messagesByID = [message.id: message]
        viewModel.renderState = .empty

        let before = viewModel.reloadSignal
        viewModel.handle(.messageStatusResolved(messageID: message.id))

        #expect(viewModel.reloadSignal == before &+ 1,
                "Status resolution for a known message must request a reload even while renderState lags")
    }

    @Test("messageStatusResolved skips reload for an id not in messagesByID")
    func messageStatusResolvedSkipsWhenIDIsUnknown() {
        let viewModel = ChatViewModel()
        let before = viewModel.reloadSignal

        viewModel.handle(.messageStatusResolved(messageID: UUID()))

        #expect(viewModel.reloadSignal == before)
    }
}
