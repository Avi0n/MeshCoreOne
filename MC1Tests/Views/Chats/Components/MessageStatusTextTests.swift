@testable import MC1
@testable import MC1Services
import Testing

@Suite("Message status text")
struct MessageStatusTextTests {
  private func makeFooter(
    status: MessageStatus,
    isChannelMessage: Bool,
    retryAttempt: Int = 0,
    maxRetryAttempts: Int = 0
  ) -> MessageFooter {
    MessageFooter(
      showHop: false,
      hopCount: 0,
      formattedPath: nil,
      regionToShow: nil,
      sendTimeToShow: nil,
      sendTimeWasCorrected: false,
      showStatusRow: true,
      status: status,
      isChannelMessage: isChannelMessage,
      heardRepeats: 0,
      retryAttempt: retryAttempt,
      maxRetryAttempts: maxRetryAttempts,
      sendCount: 1
    )
  }

  /// A DM `.sent` only means the radio queued the packet; it must read as
  /// in-progress so the user never sees a settled "Sent" that later fails.
  @Test
  func `DM .sent renders as Sending`() {
    let text = MessageStatusText.text(for: makeFooter(status: .sent, isChannelMessage: false))
    #expect(text == L10n.Chats.Chats.Message.Status.sending)
  }

  /// A channel broadcast has no ACK, so `.sent` is its terminal success state.
  @Test
  func `Channel .sent renders as Sent`() {
    let text = MessageStatusText.text(for: makeFooter(status: .sent, isChannelMessage: true))
    #expect(text == L10n.Chats.Chats.Message.Status.sent)
  }

  @Test
  func `DM .delivered still renders as Delivered`() {
    let text = MessageStatusText.text(for: makeFooter(status: .delivered, isChannelMessage: false))
    #expect(text == L10n.Chats.Chats.Message.Status.delivered)
  }

  @Test
  func `DM .failed still renders as Failed`() {
    let text = MessageStatusText.text(for: makeFooter(status: .failed, isChannelMessage: false))
    #expect(text == L10n.Chats.Chats.Message.Status.failed)
  }

  @Test(arguments: [
    (0, 7, 2, 8),
    (6, 7, 8, 8),
    (1, 2, 3, 3),
  ])
  func `DM retry progress includes the initial send in both counts`(
    retryAttempt: Int,
    maxRetryAttempts: Int,
    attempt: Int,
    total: Int
  ) {
    let footer = makeFooter(
      status: .retrying,
      isChannelMessage: false,
      retryAttempt: retryAttempt,
      maxRetryAttempts: maxRetryAttempts
    )
    #expect(MessageStatusText.text(for: footer) == L10n.Chats.Chats.Message.Status.retryingAttempt(attempt, total))
  }
}
