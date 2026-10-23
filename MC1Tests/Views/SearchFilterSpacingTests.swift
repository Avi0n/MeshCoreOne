@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

/// Compares the production Chats, Nodes, and Discover headers under the same search placement.
@Suite("Search filter spacing", .serialized, .timeLimit(.minutes(3)))
@MainActor
struct SearchFilterSpacingTests {
  private enum Layout {
    static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
    static let settle = Duration.milliseconds(800)
  }

  private struct Sample {
    let name: String
    let chats: CGFloat
    let nodes: CGFloat
    let discover: CGFloat
    let scale: CGFloat
  }

  @Test
  func `search to filter gap matches across chats nodes and discover`() async throws {
    let loading = try await sample("loading", loaded: false, focus: false, rows: false)
    let populated = try await sample("populated", loaded: true, focus: false, rows: true)
    let focused = try await sample("focused empty", loaded: true, focus: true, rows: true)
    let samples = [loading, populated, focused]
    let report = samples.map { sample in
      "\(sample.name) chats=\(sample.chats) nodes=\(sample.nodes) discover=\(sample.discover) scale=\(sample.scale)"
    }.joined(separator: "\n")
    for sample in samples {
      let tolerance = 1 / max(sample.scale, 1)
      #expect(abs(sample.chats - sample.nodes) <= tolerance, "\(sample.name) \(report)")
      #expect(abs(sample.chats - sample.discover) <= tolerance, "\(sample.name) \(report)")
    }
  }

  private func sample(
    _ name: String,
    loaded: Bool,
    focus: Bool,
    rows: Bool
  ) async throws -> Sample {
    let chats = try await chatsGap(loaded: loaded, focus: focus, rows: rows)
    let nodes = try await nodesGap(loaded: loaded, focus: focus, rows: rows)
    let discover = try await discoverGap(loaded: loaded, focus: focus)
    return Sample(name: name, chats: chats.gap, nodes: nodes.gap, discover: discover.gap, scale: chats.scale)
  }

  private func chatsGap(loaded: Bool, focus: Bool, rows: Bool) async throws -> Gap {
    let host = try await mountChats(loaded: loaded, rows: rows)
    defer { host.tearDown() }
    try await settle(host.window)
    if focus {
      searchField(in: host.window)?.becomeFirstResponder()
      try await settle(host.window)
    }
    return try measuredGap(in: host.window, titles: chatTitles)
  }

  private func nodesGap(loaded: Bool, focus: Bool, rows: Bool) async throws -> Gap {
    let host = try await mountNodes(loaded: loaded, rows: rows)
    defer { host.tearDown() }
    try await settle(host.window)
    if focus {
      searchField(in: host.window)?.becomeFirstResponder()
      try await settle(host.window)
    }
    return try measuredGap(in: host.window, titles: nodeTitles)
  }

  private func discoverGap(loaded: Bool, focus: Bool) async throws -> Gap {
    let host = try await mountDiscover(loaded: loaded)
    defer { host.tearDown() }
    try await settle(host.window)
    if focus {
      searchField(in: host.window)?.becomeFirstResponder()
      try await settle(host.window)
    }
    return try measuredGap(in: host.window, titles: discoverTitles)
  }

  private func measuredGap(in window: UIWindow, titles: Set<String>) throws -> Gap {
    window.layoutIfNeeded()
    let search = try #require(searchFieldFrame(in: window), "Missing search field")
    let pills = pillFrames(in: window, titles: titles)
    let top = try #require(pills.map(\.minY).min(), "Missing filter pills among \(describe(accessNodes(in: window)))")
    return Gap(gap: top - search.maxY, scale: window.screen.scale)
  }

  private func mountChats(loaded: Bool, rows: Bool) async throws -> ChatsSpacingHost {
    let viewModel = ChatViewModel()
    viewModel.hasLoadedOnce = loaded
    let others = rows ? [Conversation.direct(makeContact(name: "Ada"))] : []
    let controller = UIHostingController(
      rootView: ChatsSpacingRoot(
        viewModel: viewModel,
        appState: AppState(),
        hasLoadedOnce: loaded,
        others: others
      )
    )
    let window = try await makeWindow(controller)
    return ChatsSpacingHost(window: window)
  }

  private func mountNodes(loaded: Bool, rows: Bool) async throws -> NodesSpacingHost {
    let viewModel = ContactsViewModel()
    viewModel.hasLoadedOnce = loaded
    viewModel.contacts = rows ? [makeContact(name: "Ada")] : []
    let state = ContactsSidebarState(viewModel: viewModel)
    state.searchText = ""
    let contacts = rows ? viewModel.contacts : []
    let controller = UIHostingController(
      rootView: NodesSpacingRoot(state: state, contacts: contacts, appState: AppState())
    )
    let window = try await makeWindow(controller)
    return NodesSpacingHost(window: window, state: state)
  }

  private func mountDiscover(loaded: Bool) async throws -> DiscoverSpacingHost {
    let state = DiscoveryState()
    state.viewModel.hasLoadedOnce = loaded
    let controller = UIHostingController(
      rootView: NavigationStack {
        DiscoveryView(state: state, rendererRevision: 0)
      }
      .environment(\.appState, AppState())
      .environment(\.appTheme, .default)
    )
    let window = try await makeWindow(controller)
    return DiscoverSpacingHost(window: window, state: state)
  }

  private func makeWindow(_ controller: UIViewController) async throws -> UIWindow {
    var scene: UIWindowScene?
    try await waitUntil(timeout: .seconds(5), "No window scene") {
      scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
      return scene != nil
    }
    let resolved = try #require(scene)
    let window = UIWindow(windowScene: resolved)
    window.frame = Layout.viewport
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return window
  }

  private func settle(_ window: UIWindow) async throws {
    window.layoutIfNeeded()
    try await Task.sleep(for: Layout.settle)
    window.layoutIfNeeded()
  }

  private var chatTitles: Set<String> {
    Set(ChatFilter.allCases.map(\.localizedName))
  }

  private var nodeTitles: Set<String> {
    Set(NodeSegment.allCases.map(\.localizedTitle))
  }

  private var discoverTitles: Set<String> {
    Set(DiscoverSegment.allCases.map(\.localizedTitle))
  }

  private func makeContact(name: String) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 4, count: ProtocolLimits.publicKeySize),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: Date(),
      unreadCount: 0
    )
  }
}

private struct Gap {
  let gap: CGFloat
  let scale: CGFloat
}

private struct ChatsSpacingRoot: View {
  let viewModel: ChatViewModel
  let appState: AppState
  let hasLoadedOnce: Bool
  let others: [Conversation]
  @State private var selectedFilter: ChatFilter = .all
  @State private var searchText = ""
  @State private var showingNewChat = false
  @State private var showingChannelOptions = false

  var body: some View {
    NavigationStack {
      ChatsSplitSidebarContent(
        viewModel: viewModel,
        filteredPinned: [],
        filteredOthers: others,
        emptyStateMessage: (
          L10n.Chats.Chats.EmptyState.NoConversations.title,
          L10n.Chats.Chats.EmptyState.NoConversations.description,
          "message"
        ),
        hasLoadedOnce: hasLoadedOnce,
        selectedRoute: nil,
        selectedFilter: $selectedFilter,
        searchText: $searchText,
        showingNewChat: $showingNewChat,
        showingChannelOptions: $showingChannelOptions,
        onSelect: { _ in },
        onDeleteConversation: { _ in },
        onAnnounceOfflineStateIfNeeded: {}
      )
    }
    .environment(\.appState, appState)
    .environment(\.appTheme, .default)
  }
}

private struct NodesSpacingRoot: View {
  let state: ContactsSidebarState
  let contacts: [ContactDTO]
  let appState: AppState
  @State private var sortOrder: NodeSortOrder = .name

  var body: some View {
    NavigationStack {
      ContactsSidebarContent(
        state: state,
        filteredContacts: contacts,
        timestampsByID: [:],
        searchPrompt: L10n.Contacts.Contacts.List.searchPrompt,
        selectedContact: nil,
        sortOrder: $sortOrder,
        blockedContactsRoute: nil,
        rendererRevision: state.rendererRevision,
        onSelect: { _ in }
      )
    }
    .environment(\.appState, appState)
    .environment(\.appTheme, .default)
  }
}

@MainActor
private final class ChatsSpacingHost {
  let window: UIWindow

  init(window: UIWindow) {
    self.window = window
  }

  func tearDown() {
    window.rootViewController = nil
    window.isHidden = true
    window.windowScene = nil
  }
}

@MainActor
private final class NodesSpacingHost {
  let window: UIWindow
  let state: ContactsSidebarState

  init(window: UIWindow, state: ContactsSidebarState) {
    self.window = window
    self.state = state
  }

  func tearDown() {
    state.end()
    window.rootViewController = nil
    window.isHidden = true
    window.windowScene = nil
  }
}

@MainActor
private final class DiscoverSpacingHost {
  let window: UIWindow
  let state: DiscoveryState

  init(window: UIWindow, state: DiscoveryState) {
    self.window = window
    self.state = state
  }

  func tearDown() {
    state.end()
    window.rootViewController = nil
    window.isHidden = true
    window.windowScene = nil
  }
}

private struct AccessNode {
  let label: String
  let frame: CGRect
  let isElement: Bool
}

@MainActor
private func accessNodes(in root: NSObject) -> [AccessNode] {
  var nodes: [AccessNode] = []
  var seen = Set<ObjectIdentifier>()
  func visit(_ object: NSObject) {
    guard seen.insert(ObjectIdentifier(object)).inserted else { return }
    if let view = object as? UIView, view.accessibilityElementsHidden || view.isHidden { return }
    let rawCount = object.accessibilityElementCount()
    let childCount = rawCount == NSNotFound ? 0 : max(rawCount, 0)
    let label = object.accessibilityLabel ?? ""
    if object.isAccessibilityElement || (!label.isEmpty && childCount > 0) {
      nodes.append(AccessNode(label: label, frame: object.accessibilityFrame, isElement: object.isAccessibilityElement))
    }
    if childCount > 0 {
      for index in 0..<childCount {
        guard let child = object.accessibilityElement(at: index) as? NSObject, child !== object else { continue }
        visit(child)
      }
      return
    }
    if let elements = object.accessibilityElements, !elements.isEmpty {
      for case let child as NSObject in elements where child !== object {
        visit(child)
      }
      return
    }
    guard let view = object as? UIView else { return }
    for subview in view.subviews where subview.alpha > 0 {
      visit(subview)
    }
  }
  visit(root)
  return nodes
}

@MainActor
private func pillFrames(in window: UIWindow, titles: Set<String>) -> [CGRect] {
  accessNodes(in: window).filter { titles.contains($0.label) && $0.isElement }.map(\.frame)
}

@MainActor
private func searchField(in view: UIView) -> UITextField? {
  if let field = view as? UITextField, field.bounds.width > 40, field.bounds.height > 10 { return field }
  for subview in view.subviews {
    if let field = searchField(in: subview) { return field }
  }
  return nil
}

@MainActor
private func searchFieldFrame(in window: UIWindow) -> CGRect? {
  guard let field = searchField(in: window) else { return nil }
  return field.convert(field.bounds, to: nil)
}

@MainActor
private func describe(_ nodes: [AccessNode]) -> String {
  nodes.map { "[\($0.label) \($0.frame)]" }.joined(separator: " ")
}
