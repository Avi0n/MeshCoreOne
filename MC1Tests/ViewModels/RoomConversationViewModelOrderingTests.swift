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

  @Test
  func `same-prefix burst books name on first and avatar on last`() {
    let alice = Data([0xAA])
    let messages = [
      roomMessage(ts: 100, prefix: alice, name: "Alice"),
      roomMessage(ts: 160, prefix: alice, name: "Alice"),
      roomMessage(ts: 220, prefix: alice, name: "Alice")
    ]

    let bookends = RoomConversationViewModel.incomingBookends(in: messages)

    #expect(bookends.nameIDs == [messages[0].id])
    #expect(bookends.avatarIDs == [messages[2].id])
  }

  @Test
  func `different prefixes are two clusters even when display names match`() {
    let messages = [
      roomMessage(ts: 100, prefix: Data([0xAA]), name: "Alice"),
      roomMessage(ts: 160, prefix: Data([0xBB]), name: "Alice")
    ]

    let bookends = RoomConversationViewModel.incomingBookends(in: messages)

    #expect(bookends.nameIDs == [messages[0].id, messages[1].id])
    #expect(bookends.avatarIDs == [messages[0].id, messages[1].id])
  }

  @Test
  func `self messages never show a name or avatar`() {
    let messages = [
      roomMessage(ts: 100, prefix: Data([0xAA]), name: "Me", isFromSelf: true),
      roomMessage(ts: 160, prefix: Data([0xAA]), name: "Me", isFromSelf: true)
    ]

    let bookends = RoomConversationViewModel.incomingBookends(in: messages)

    #expect(bookends.nameIDs.isEmpty)
    #expect(bookends.avatarIDs.isEmpty)
  }

  @Test
  func `follow-up flips previous row Hashable identity so the tiled cell reconfigures`() {
    let prefix = Data([0xAA])
    let first = roomMessage(ts: 100, prefix: prefix, name: "Alice")
    let second = roomMessage(ts: 160, prefix: prefix, name: "Alice")

    let before = RoomConversationViewModel.tiledRows(in: [first])
    let after = RoomConversationViewModel.tiledRows(in: [first, second])

    #expect(before[0].id == first.id)
    #expect(before[0].showAvatar == true)
    #expect(after[0].id == first.id)
    #expect(after[0].showAvatar == false)
    #expect(after[1].showAvatar == true)
    #expect(before[0] != after[0])
  }

  @Test
  func `mid-insert flips the next row timestamp flag`() {
    let prefix = Data([0xAA])
    let first = roomMessage(ts: 100, prefix: prefix, name: "Alice")
    let later = roomMessage(ts: 500, prefix: prefix, name: "Alice")
    let mid = roomMessage(ts: 250, prefix: prefix, name: "Alice")

    let before = RoomConversationViewModel.tiledRows(in: [first, later])
    #expect(before[1].showTimestamp == true)

    let after = RoomConversationViewModel.tiledRows(in: [first, mid, later])
    #expect(after[2].id == later.id)
    #expect(after[2].showTimestamp == false)
    #expect(before[1] != after[2])
  }

  @Test
  func `300s same-prefix is one cluster; 301s is two`() {
    let prefix = Data([0xAA])
    let a = roomMessage(ts: 1000, prefix: prefix, name: "Alice")
    let at300 = roomMessage(ts: 1300, prefix: prefix, name: "Alice")
    let at301 = roomMessage(ts: 1301, prefix: prefix, name: "Alice")

    let clustered = RoomConversationViewModel.tiledRows(in: [a, at300])
    #expect(clustered[0].showSenderName == true)
    #expect(clustered[0].showAvatar == false)
    #expect(clustered[1].showSenderName == false)
    #expect(clustered[1].showAvatar == true)

    let split = RoomConversationViewModel.tiledRows(in: [a, at301])
    #expect(split[0].showAvatar == true)
    #expect(split[1].showSenderName == true)
    #expect(split[1].showAvatar == true)
  }

  @Test
  func `self message breaks an incoming prefix cluster`() {
    let prefix = Data([0xAA])
    let first = roomMessage(ts: 100, prefix: prefix, name: "Alice")
    let me = roomMessage(ts: 160, prefix: prefix, name: "Alice", isFromSelf: true)
    let third = roomMessage(ts: 220, prefix: prefix, name: "Alice")

    let rows = RoomConversationViewModel.tiledRows(in: [first, me, third])
    #expect(rows[0].showSenderName == true)
    #expect(rows[0].showAvatar == true)
    #expect(rows[1].showSenderName == false)
    #expect(rows[1].showAvatar == false)
    #expect(rows[2].showSenderName == true)
    #expect(rows[2].showAvatar == true)
  }

  private func roomMessage(
    ts: UInt32,
    prefix: Data,
    name: String,
    isFromSelf: Bool = false
  ) -> RoomMessageDTO {
    RoomMessageDTO(
      sessionID: sessionID,
      authorKeyPrefix: prefix,
      authorName: name,
      text: "msg",
      timestamp: ts,
      isFromSelf: isFromSelf
    )
  }
}
