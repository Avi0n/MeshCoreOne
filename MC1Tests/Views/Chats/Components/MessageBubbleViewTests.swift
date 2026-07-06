import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Tests that `MessageBubbleView`'s cell-content closure resolves the message
/// for its stored item through `ChatViewModel.message(for:)` and that retry
/// state surfaces through `item.envelope` / `item.footer`.
@Suite("MessageBubbleView wiring")
@MainActor
struct MessageBubbleViewTests {
  private static let radioID = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
  private static let contactID = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!
  private static let referenceDate = Date(timeIntervalSince1970: 1_700_000_000)

  @Test
  func `DM bubble path: viewModel resolves the message for its stored item`() async throws {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator
    let message = makeMessage(text: "hello dm")
    coordinator.replaceAll([message])
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    let item = try #require(viewModel.items.first)
    #expect(item.id == message.id)

    let resolved = viewModel.message(for: item)
    #expect(resolved?.id == message.id)
    #expect(item.envelope.isOutgoing == true)
    #expect(item.envelope.status == .sent)
  }

  @Test
  func `Retry/failed bubble: envelope and footer capture retry state`() async throws {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.coordinator = coordinator
    let message = makeMessage(
      text: "retry me",
      status: .failed,
      retryAttempt: 2,
      maxRetryAttempts: 3
    )
    coordinator.replaceAll([message])
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    let item = try #require(viewModel.items.first)
    #expect(viewModel.message(for: item) != nil)
    #expect(item.envelope.status == .failed)
    #expect(item.envelope.hasFailed == true)
    #expect(item.footer.retryAttempt == 2)
    #expect(item.footer.maxRetryAttempts == 3)
  }

  // MARK: - Helpers

  private func makeMessage(
    id: UUID = UUID(),
    text: String,
    status: MessageStatus = .sent,
    retryAttempt: Int = 0,
    maxRetryAttempts: Int = 0
  ) -> MessageDTO {
    MessageDTO(
      id: id,
      radioID: Self.radioID,
      contactID: Self.contactID,
      channelIndex: nil,
      text: text,
      timestamp: UInt32(Self.referenceDate.timeIntervalSince1970),
      createdAt: Self.referenceDate,
      direction: .outgoing,
      status: status,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: retryAttempt,
      maxRetryAttempts: maxRetryAttempts
    )
  }
}
