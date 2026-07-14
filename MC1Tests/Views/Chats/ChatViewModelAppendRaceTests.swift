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
    viewModel.coordinator = coordinator

    let message = Self.makeMessage()

    _ = coordinator.append(message)

    viewModel.appendMessageIfNew(message)

    #expect(viewModel.messages.count == 1,
            "appendMessageIfNew must not duplicate when messagesByID already holds the id")
    #expect(viewModel.messagesByID.count == 1)
  }

  /// `ChatTiledView` distinguishes a live append (animated scroll) from a bulk
  /// catch-up reload (silent jump) by watching `liveAppendGeneration`. It must
  /// advance for a genuine append and stay put for a de-duplicated one, or the
  /// reopen catch-up would be misread as live and animate.
  @Test
  func `liveAppendGeneration advances on a new append but not a duplicate`() {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator

    let message = Self.makeMessage()
    let before = viewModel.liveAppendGeneration

    viewModel.appendMessageIfNew(message)
    #expect(viewModel.liveAppendGeneration == before + 1,
            "a genuine live append must advance the generation")

    viewModel.appendMessageIfNew(message)
    #expect(viewModel.liveAppendGeneration == before + 1,
            "a de-duplicated append must not advance the generation")
  }

  private static func makeMessage() -> MessageDTO {
    MessageDTO(
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
  }
}
