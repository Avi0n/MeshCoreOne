import Foundation
@testable import MC1
import Testing

@Suite("ChatOpenAnchorState")
@MainActor
struct ChatOpenAnchorStateTests {
  private static let unreadCount = 3

  private enum FetchError: Error {
    case failed
  }

  @Test
  func `a baked divider presents before and after Populate settles`() {
    let divider = UUID()
    var state = ChatOpenAnchorState(unreadCount: Self.unreadCount)

    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: true) == .present(target: divider))
    state.completePopulate(.loaded)
    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: true) == .present(target: divider))
  }

  @Test
  func `an unbaked divider withholds before and after Populate settles`() {
    let divider = UUID()
    var state = ChatOpenAnchorState(unreadCount: Self.unreadCount)

    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: false) == .withhold)
    state.completePopulate(.loaded)
    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: false) == .withhold)
  }

  @Test
  func `terminal Populate without a divider presents at the bottom`() {
    let outcomes: [ChatTimelinePopulator.Outcome] = [.loaded, .unavailable, .failed(FetchError.failed)]
    for outcome in outcomes {
      var state = ChatOpenAnchorState(unreadCount: Self.unreadCount)
      #expect(state.firstSnapshot(dividerMessageID: nil, dividerRowOnScreen: false) == .withhold)

      state.completePopulate(outcome)

      #expect(state.firstSnapshot(dividerMessageID: nil, dividerRowOnScreen: false) == .present(target: nil))
    }
  }

  @Test
  func `cancelled Populate preserves readiness before and after a terminal retry`() {
    var state = ChatOpenAnchorState(unreadCount: Self.unreadCount)
    state.completePopulate(.cancelled)
    #expect(state.firstSnapshot(dividerMessageID: nil, dividerRowOnScreen: false) == .withhold)

    state.completePopulate(.loaded)
    state.completePopulate(.cancelled)
    #expect(state.firstSnapshot(dividerMessageID: nil, dividerRowOnScreen: false) == .present(target: nil))
  }

  @Test(arguments: [false, true])
  func `consumption retires the Anchor across later Populate outcomes`(settledBeforeConsumption: Bool) {
    let divider = UUID()
    var state = ChatOpenAnchorState(unreadCount: Self.unreadCount)
    if settledBeforeConsumption {
      state.completePopulate(.loaded)
    }
    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: true) == .present(target: divider))

    state.consumeAnchor()
    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: true) == .present(target: nil))
    state.completePopulate(.cancelled)
    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: true) == .present(target: nil))
    state.completePopulate(.loaded)
    #expect(state.firstSnapshot(dividerMessageID: divider, dividerRowOnScreen: true) == .present(target: nil))
  }

  @Test
  func `no unread backlog presents immediately with no target`() {
    let state = ChatOpenAnchorState(unreadCount: 0)

    #expect(state.firstSnapshot(dividerMessageID: UUID(), dividerRowOnScreen: true) == .present(target: nil))
  }
}
