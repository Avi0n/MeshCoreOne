import Testing
import Foundation
import SwiftUI
import MC1Services
@testable import MC1

@Suite("ChatLinkRouter Tests")
@MainActor
struct ChatLinkRouterTests {

    private func makeAppState() -> AppState {
        AppState()
    }

    @Test("Returns false for plain https URLs (caller falls through to systemAction)")
    func httpsPassesThrough() {
        let appState = makeAppState()
        let url = URL(string: "https://apple.com")!
        let result = ChatLinkRouter.route(url, appState: appState)
        #expect(result == false)
    }

    @Test("Returns false for mailto URLs (caller falls through to systemAction)")
    func mailtoPassesThrough() {
        let appState = makeAppState()
        let url = URL(string: "mailto:test@example.com")!
        let result = ChatLinkRouter.route(url, appState: appState)
        #expect(result == false)
    }

    @Test("Returns true for meshcoreone hashtag URLs and stages a pending hashtag")
    func hashtagURLHandled() async throws {
        let appState = makeAppState()
        let url = URL(string: "meshcoreone://hashtag/general")!
        let result = ChatLinkRouter.route(url, appState: appState)
        #expect(result == true)
        try await Task.sleep(for: .milliseconds(50))
        #expect(appState.navigation.pendingHashtag?.id == "#general")
    }

    @Test("Returns true for meshcore map URLs and stages map focus immediately")
    func meshCoreMapHandled() {
        let appState = makeAppState()
        let url = URL(string: "meshcore://map?lat=37.7749&lon=-122.4194")!
        let result = ChatLinkRouter.route(url, appState: appState)
        #expect(result == true)
        #expect(appState.navigation.pendingMapFocus != nil)
    }

    @Test("Returns true for malformed hashtag URLs without crashing")
    func malformedHashtagHandled() async throws {
        let appState = makeAppState()
        let url = URL(string: "meshcoreone://hashtag")!
        let result = ChatLinkRouter.route(url, appState: appState)
        #expect(result == true)
        try await Task.sleep(for: .milliseconds(50))
        #expect(appState.navigation.pendingHashtag == nil)
    }
}
