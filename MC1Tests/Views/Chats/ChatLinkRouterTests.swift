import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("ChatLinkRouter Tests")
@MainActor
struct ChatLinkRouterTests {
  private func makeAppState() -> AppState {
    AppState()
  }

  @Test
  func `Returns false for plain https URLs (caller falls through to systemAction)`() throws {
    let appState = makeAppState()
    let url = try #require(URL(string: "https://apple.com"))
    let result = ChatLinkRouter.route(url, appState: appState)
    #expect(result == false)
  }

  @Test
  func `Returns false for mailto URLs (caller falls through to systemAction)`() throws {
    let appState = makeAppState()
    let url = try #require(URL(string: "mailto:test@example.com"))
    let result = ChatLinkRouter.route(url, appState: appState)
    #expect(result == false)
  }

  @Test
  func `Returns true for meshcoreone hashtag URLs and stages a pending hashtag`() async throws {
    let appState = makeAppState()
    let url = try #require(URL(string: "meshcoreone://hashtag/general"))
    let result = ChatLinkRouter.route(url, appState: appState)
    #expect(result == true)
    try await Task.sleep(for: .milliseconds(50))
    #expect(appState.navigation.pendingHashtag?.id == "#general")
  }

  @Test
  func `Returns true for meshcore map URLs and stages map focus immediately`() throws {
    let appState = makeAppState()
    let url = try #require(URL(string: "meshcore://map?lat=37.7749&lon=-122.4194"))
    let result = ChatLinkRouter.route(url, appState: appState)
    #expect(result == true)
    #expect(appState.navigation.pendingMapFocus != nil)
  }

  @Test
  func `Returns true for malformed hashtag URLs without crashing`() async throws {
    let appState = makeAppState()
    let url = try #require(URL(string: "meshcoreone://hashtag"))
    let result = ChatLinkRouter.route(url, appState: appState)
    #expect(result == true)
    try await Task.sleep(for: .milliseconds(50))
    #expect(appState.navigation.pendingHashtag == nil)
  }
}
