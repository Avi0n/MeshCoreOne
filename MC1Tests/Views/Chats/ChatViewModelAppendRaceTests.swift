import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Guards `appendMessageIfNew` against a stale-read crash. The dedup
/// guard reads through the coordinator's `messagesByID`, which is updated
/// synchronously with `messages`, so an event arriving during an off-main
/// `buildItems()` cycle still matches. Reading `renderState.itemIndexByID`
/// instead would lag by one cycle and silently append a duplicate, which
/// then traps the next batch build on a unique-keys dictionary build.
@Suite("ChatViewModel append-race guard")
@MainActor
struct ChatViewModelAppendRaceTests {
  @Test
  func `appendMessageIfNew skips a message already present in messagesByID`() {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)

    let message = MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: UUID(),
      channelIndex: nil,
      text: "hello",
      timestamp: 1000,
      createdAt: Date(timeIntervalSince1970: 1000),
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

    _ = coordinator.append(message)

    viewModel.appendMessageIfNew(message)

    #expect(viewModel.messages.count == 1,
            "appendMessageIfNew must not duplicate when messagesByID already holds the id")
    #expect(viewModel.messagesByID.count == 1)
  }
}
