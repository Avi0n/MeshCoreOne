import Foundation
import Testing
@testable import MC1
@testable import MC1Services

/// `IntentBridge` is the stable holder that survives the before-first-unlock
/// `AppState` swap in `MC1App`. These tests pin its adopt semantics so a future
/// refactor that adds another `AppState` creation path without calling `adopt`
/// is caught: the bridge must always point at the most recently adopted state.
@MainActor
struct IntentBridgeTests {

    @Test func adoptStoresTheAdoptedAppState() {
        let bridge = IntentBridge()
        #expect(bridge.appState == nil)

        let appState = AppState()
        bridge.adopt(appState)

        #expect(bridge.appState === appState)
    }

    @Test func reAdoptReplacesThePreviousAppState() {
        let bridge = IntentBridge()
        let first = AppState()
        let second = AppState()

        bridge.adopt(first)
        bridge.adopt(second)

        #expect(bridge.appState === second)
        #expect(bridge.appState !== first)
    }
}
