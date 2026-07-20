import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// The rendered room timeline (`RoomConversationViewModel.messages`) must stay
/// sorted by server timestamp: the bubble view walks the array in order and
/// `shouldShowTimestamp` compares adjacent-by-index messages as chronological.
/// A live message arriving older than the tail (routine on LoRa and during
/// history sync) must not break that. Ties resolve by arrival order, matching
/// the store's `[timestamp, createdAt]` sort.
@Suite("RoomConversationViewModel ordering")
@MainActor
struct RoomConversationViewModelOrderingTests {
  private let sessionID = UUID()

  private func message(id: UUID = UUID(), ts: UInt32, text: String = "msg") -> RoomMessageDTO {
    RoomMessageDTO(
      id: id,
      sessionID: sessionID,
      authorKeyPrefix: Data([0xAB, 0xCD, 0xEF, 0x01]),
      authorName: "Author",
      text: text,
      timestamp: ts
    )
  }

  /// Mirrors `handleEvent(.roomMessageReceived)`, whose optimistic
  /// `appendMessageIfNew` places the message before the debounced reload runs.
  @Test
  func `out-of-order live message inserts into the middle`() {
    let viewModel = RoomConversationViewModel()
    viewModel.messages = [message(ts: 100), message(ts: 200), message(ts: 300)]

    viewModel.appendMessageIfNew(message(ts: 150))

    #expect(viewModel.messages.map(\.timestamp) == [100, 150, 200, 300])
  }

  @Test
  func `message older than all inserts at the front`() {
    let viewModel = RoomConversationViewModel()
    viewModel.messages = [message(ts: 200), message(ts: 300)]

    viewModel.appendMessageIfNew(message(ts: 100))

    #expect(viewModel.messages.map(\.timestamp) == [100, 200, 300])
  }

  @Test
  func `newest message inserts at the tail`() {
    let viewModel = RoomConversationViewModel()
    viewModel.messages = [message(ts: 100), message(ts: 200)]

    viewModel.appendMessageIfNew(message(ts: 300))

    #expect(viewModel.messages.map(\.timestamp) == [100, 200, 300])
  }

  /// LoRa timestamps have 1-second resolution, so equal server timestamps are
  /// routine. The new arrival must land after existing same-timestamp messages,
  /// matching the store's `createdAt` tie-break.
  @Test
  func `equal-timestamp message inserts after existing same-timestamp messages`() {
    let viewModel = RoomConversationViewModel()
    let first = message(ts: 200, text: "first")
    let second = message(ts: 200, text: "second")
    viewModel.messages = [message(ts: 100), first]

    viewModel.appendMessageIfNew(second)

    #expect(viewModel.messages.map(\.id) == [viewModel.messages[0].id, first.id, second.id])
  }

  @Test
  func `duplicate id is ignored`() {
    let viewModel = RoomConversationViewModel()
    let existing = message(ts: 200)
    viewModel.messages = [message(ts: 100), existing]

    viewModel.appendMessageIfNew(message(id: existing.id, ts: 150))

    #expect(viewModel.messages.map(\.timestamp) == [100, 200])
  }
}
