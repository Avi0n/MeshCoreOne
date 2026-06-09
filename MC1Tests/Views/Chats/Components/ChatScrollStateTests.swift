import Testing
import Foundation
@testable import MC1

@Suite("ChatScrollState")
struct ChatScrollStateTests {

    @Test("dragging and applying coexist on independent axes")
    func draggingAndApplying_coexist() {
        var state: ChatScrollState = .idle
        state.enterDragging()
        state.startApplying()
        #expect(state.interaction == .dragging)
        #expect(state.apply == .applying)
    }

    @Test("intent survives across interaction transitions")
    func intentSurvivesAcrossInteractionTransitions() {
        var state: ChatScrollState = .idle
        state.startIntent(.toBottom)
        state.enterDragging()
        #expect(state.intent == .toBottom)
        state.endDragging()
        #expect(state.intent == .toBottom)
    }

    @Test("deferred scroll is consumed exactly once")
    func deferredScroll_consumedExactlyOnce() {
        var state: ChatScrollState = .idle
        state.scheduleDeferredScroll(DeferredScroll(targetMessageCount: 3, createdAt: Date()))
        let first = state.consumeDeferredScroll()
        let second = state.consumeDeferredScroll()
        #expect(first?.targetMessageCount == 3)
        #expect(second == nil)
    }
}
