import Testing
import Foundation
@testable import MC1
@testable import MC1Services

/// Reproduces the stale-read crash on `appendMessageIfNew` where the guard
/// read `renderState.itemIndexByID` — a snapshot that lags `messages` by one
/// off-main `buildItems()` cycle. When an event fires between mutating
/// `messages` and the build's main-actor apply step, the guard sees an empty
/// index, appends the same message again, and the next batch build's
/// `Dictionary(uniqueKeysWithValues:)` traps on a duplicate key.
@Suite("ChatViewModel append-race guard")
@MainActor
struct ChatViewModelAppendRaceTests {

    @Test("appendMessageIfNew skips a message already present in messages even if renderState is empty")
    func appendSkipsWhenMessagesByIDAlreadyHolds() {
        let viewModel = ChatViewModel()

        let message = MessageDTO(
            id: UUID(),
            radioID: UUID(),
            contactID: UUID(),
            channelIndex: nil,
            text: "hello",
            timestamp: 1_000,
            createdAt: Date(timeIntervalSince1970: 1_000),
            direction: .outgoing,
            status: .sent,
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

        viewModel.messages = [message]
        viewModel.messagesByID = [message.id: message]
        viewModel.renderState = .empty

        viewModel.appendMessageIfNew(message)

        #expect(viewModel.messages.count == 1,
                "appendMessageIfNew must not duplicate when messagesByID already holds the id, even if renderState lags")
        #expect(viewModel.messagesByID.count == 1)
    }
}
