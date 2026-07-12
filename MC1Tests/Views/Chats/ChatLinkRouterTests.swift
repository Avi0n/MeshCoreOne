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

  // MARK: - routeExternalOpen

  @Test
  func `routeExternalOpen stages a pending contact and switches to the Chats tab`() async throws {
    let appState = makeAppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let publicKey = String(repeating: "ab", count: ProtocolLimits.publicKeySize)
    let url = try #require(URL(string: "meshcore://contact/add?name=NGC-MB&public_key=\(publicKey)&type=1"))

    let handled = ChatLinkRouter.routeExternalOpen(url, appState: appState)

    #expect(handled)
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    try await Task.sleep(for: .milliseconds(50))
    #expect(appState.navigation.pendingContactLink != nil)
  }

  @Test
  func `routeExternalOpen stages a pending channel and switches to the Chats tab`() async throws {
    let appState = makeAppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let secret = String(repeating: "ab", count: 16) // 32 hex chars = 16-byte channel secret
    let url = try #require(URL(string: "meshcore://channel/add?name=Test&secret=\(secret)"))

    let handled = ChatLinkRouter.routeExternalOpen(url, appState: appState)

    #expect(handled)
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    try await Task.sleep(for: .milliseconds(50))
    #expect(appState.navigation.pendingChannelLink?.name == "Test")
  }

  @Test
  func `routeExternalOpen restores the previous tab for meshcoreone status URLs`() throws {
    let appState = makeAppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let url = try #require(URL(string: "meshcoreone://status"))

    let handled = ChatLinkRouter.routeExternalOpen(url, appState: appState)

    #expect(handled == false)
    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)
  }

  @Test
  func `routeExternalOpen ends on the map tab for meshcore map URLs`() throws {
    let appState = makeAppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let url = try #require(URL(string: "meshcore://map?lat=37.7749&lon=-122.4194"))

    let handled = ChatLinkRouter.routeExternalOpen(url, appState: appState)

    #expect(handled)
    #expect(appState.navigation.selectedTab == AppTab.map.rawValue)
    #expect(appState.navigation.pendingMapFocus != nil)
  }

  @Test
  func `routeExternalOpen restores the previous tab for a malformed meshcore URL`() throws {
    let appState = makeAppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let url = try #require(URL(string: "meshcore://garbage"))

    let handled = ChatLinkRouter.routeExternalOpen(url, appState: appState)

    #expect(handled == false)
    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)
  }

  @Test
  func `routeExternalOpen stages a pending hashtag and switches to the Chats tab`() async throws {
    let appState = makeAppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let url = try #require(URL(string: "meshcoreone://hashtag/general"))

    let handled = ChatLinkRouter.routeExternalOpen(url, appState: appState)

    #expect(handled)
    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    try await Task.sleep(for: .milliseconds(50))
    #expect(appState.navigation.pendingHashtag?.id == "#general")
  }
}
