import Foundation
@testable import MC1
import Testing

@Suite("ChatScrollState")
struct ChatScrollStateTests {
  @Test
  func `dragging and applying coexist on independent axes`() {
    var state: ChatScrollState = .idle
    state.enterDragging()
    state.startApplying()
    #expect(state.interaction == .dragging)
    #expect(state.apply == .applying)
  }

  @Test
  func `intent survives across interaction transitions`() {
    var state: ChatScrollState = .idle
    state.startIntent(.toBottom)
    state.enterDragging()
    #expect(state.intent == .toBottom)
    state.endDragging()
    #expect(state.intent == .toBottom)
  }

  @Test
  func `deferred scroll is consumed exactly once`() {
    var state: ChatScrollState = .idle
    state.scheduleDeferredScroll(DeferredScroll(targetMessageCount: 3))
    let first = state.consumeDeferredScroll()
    let second = state.consumeDeferredScroll()
    #expect(first?.targetMessageCount == 3)
    #expect(second == nil)
  }
}
