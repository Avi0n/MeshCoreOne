@testable import MC1
import SwiftUI
import Testing
import UIKit

/// Hosts the production conversation list under the same title and search placement as Chats.
@Suite("Chats search filter", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct ChatsSearchFilterTests {
  private enum Layout {
    static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)
    static let settle = Duration.milliseconds(600)
  }

  @Test
  func `focused empty search keeps category filters enabled`() async throws {
    let probe = ChatsFilterProbe(selectedFilter: .channels, focusSearch: true)
    let host = try mount(probe)
    defer { host.tearDown() }
    try await settle(host)
    let field = try #require(searchField(in: host.window), "Search field was not in the navigation drawer")
    #expect(field.isFirstResponder)
    let options = try filterOptions(in: host.window)
    #expect(options.count == ChatFilter.allCases.count)
    #expect(options.allSatisfy { !$0.traits.contains(.notEnabled) })
    #expect(probe.selectedFilter == ChatFilter.channels)
  }

  @Test
  func `nonempty search with no matches offers no clear filter action`() async throws {
    let probe = ChatsFilterProbe(searchText: "zzzz", selectedFilter: ChatFilter.channels)
    let host = try mount(probe)
    defer { host.tearDown() }
    try await settle(host)
    let labels = accessNodes(in: host.window).map(\.label)
    #expect(!labels.contains(L10n.Chats.Chats.EmptyState.NoChannels.title))
    #expect(!labels.contains(L10n.Chats.Chats.Filter.clear))
    #expect(labels.contains { $0.localizedStandardContains("zzzz") || $0 == "No Results" })
  }

  @Test
  func `empty category keeps its clear filter action`() async throws {
    let probe = ChatsFilterProbe(selectedFilter: ChatFilter.channels)
    let host = try mount(probe)
    defer { host.tearDown() }
    try await settle(host)
    let labels = accessNodes(in: host.window).map(\.label)
    #expect(labels.contains(L10n.Chats.Chats.EmptyState.NoChannels.title))
    #expect(labels.contains(L10n.Chats.Chats.Filter.clear))
  }

  @Test
  func `nonempty query disables filters without changing the selection`() async throws {
    let probe = ChatsFilterProbe(searchText: "Ada", selectedFilter: ChatFilter.channels)
    let host = try mount(probe)
    defer { host.tearDown() }
    try await settle(host)
    let options = try filterOptions(in: host.window)
    #expect(options.allSatisfy { $0.traits.contains(.notEnabled) })
    let directMessages = try #require(options.first { $0.label == ChatFilter.directMessages.localizedName })
    _ = directMessages.object.accessibilityActivate()
    try await settle(host)
    #expect(probe.selectedFilter == ChatFilter.channels)
  }

  @Test
  func `clearing a focused query enables the retained category immediately`() async throws {
    let probe = ChatsFilterProbe(searchText: "Ada", selectedFilter: ChatFilter.channels, focusSearch: true)
    let host = try mount(probe)
    defer { host.tearDown() }
    try await settle(host)
    #expect(try filterOptions(in: host.window).allSatisfy { $0.traits.contains(.notEnabled) })
    probe.searchText = ""
    try await settle(host)
    let field = try #require(searchField(in: host.window))
    #expect(field.isFirstResponder)
    #expect(try filterOptions(in: host.window).allSatisfy { !$0.traits.contains(.notEnabled) })
    #expect(probe.selectedFilter == ChatFilter.channels)
  }

  @Test
  func `loading to loaded keeps the query selection and filter header`() async throws {
    let viewModel = ChatViewModel()
    let probe = ChatsFilterProbe(
      searchText: "",
      selectedFilter: ChatFilter.directMessages,
      hasLoadedOnce: false
    )
    let host = try mount(probe, viewModel: viewModel)
    defer { host.tearDown() }
    try await settle(host)
    let loadingTop = try filterTop(in: host.window)
    let generation = viewModel.snapshotGeneration
    probe.hasLoadedOnce = true
    try await settle(host)
    #expect(probe.searchText.isEmpty)
    #expect(probe.selectedFilter == ChatFilter.directMessages)
    let loadedTop = try filterTop(in: host.window)
    #expect(abs(loadingTop - loadedTop) <= 1 / host.window.screen.scale)
    let all = try #require(try filterOptions(in: host.window).first { $0.label == ChatFilter.all.localizedName })
    #expect(all.object.accessibilityActivate())
    try await settle(host)
    #expect(probe.selectedFilter == ChatFilter.all)
    #expect(viewModel.snapshotGeneration == generation)
    #expect(viewModel.reloadTask == nil)
  }

  private func filterTop(in window: UIWindow) throws -> CGFloat {
    let tops = try filterOptions(in: window).map(\.frame.minY)
    return try #require(tops.min())
  }

  private func mount(_ probe: ChatsFilterProbe, viewModel: ChatViewModel? = nil) throws -> ChatsFilterWindowHost {
    let viewModel = viewModel ?? ChatViewModel()
    viewModel.hasLoadedOnce = probe.hasLoadedOnce
    let controller = UIHostingController(
      rootView: ChatsFilterHost(probe: probe, viewModel: viewModel, appState: AppState())
    )
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let window = UIWindow(windowScene: scene)
    window.frame = Layout.viewport
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return ChatsFilterWindowHost(window: window)
  }

  private func settle(_ host: ChatsFilterWindowHost) async throws {
    host.window.layoutIfNeeded()
    try await Task.sleep(for: Layout.settle)
    host.window.layoutIfNeeded()
  }

  private func filterOptions(in window: UIWindow) throws -> [AccessNode] {
    let titles = Set(ChatFilter.allCases.map(\.localizedName))
    let options = accessNodes(in: window).filter { titles.contains($0.label) && $0.isElement }
    if options.count != ChatFilter.allCases.count {
      Issue.record("Filter options were \(describe(accessNodes(in: window)))")
    }
    return options
  }
}

@Observable
@MainActor
private final class ChatsFilterProbe {
  var searchText: String
  var selectedFilter: ChatFilter
  var focusSearch: Bool
  var hasLoadedOnce: Bool
  var conversations: [Conversation]

  init(
    searchText: String = "",
    selectedFilter: ChatFilter = .all,
    focusSearch: Bool = false,
    hasLoadedOnce: Bool = true,
    conversations: [Conversation] = []
  ) {
    self.searchText = searchText
    self.selectedFilter = selectedFilter
    self.focusSearch = focusSearch
    self.hasLoadedOnce = hasLoadedOnce
    self.conversations = conversations
  }
}

private struct ChatsFilterHost: View {
  @Bindable var probe: ChatsFilterProbe
  @FocusState private var searchFocused: Bool
  let viewModel: ChatViewModel
  let appState: AppState

  var body: some View {
    NavigationStack {
      ConversationListContent(
        viewModel: viewModel,
        pinnedConversations: [],
        otherConversations: probe.conversations.filtered(by: probe.selectedFilter, searchText: probe.searchText),
        selectedFilter: $probe.selectedFilter,
        searchText: probe.searchText,
        hasLoadedOnce: probe.hasLoadedOnce,
        emptyStateMessage: emptyMessage(probe.selectedFilter),
        selectedRoute: nil,
        onSelect: { _ in },
        onDeleteConversation: { _ in }
      )
      .navigationTitle(L10n.Chats.Chats.title)
      .searchable(
        text: $probe.searchText,
        placement: .navigationBarDrawer(displayMode: .always),
        prompt: L10n.Chats.Chats.Search.placeholder
      )
      .searchFocused($searchFocused)
    }
    .environment(\.appState, appState)
    .environment(\.appTheme, .default)
    .onAppear { searchFocused = probe.focusSearch }
    .onChange(of: probe.focusSearch) { _, focused in
      searchFocused = focused
    }
  }

  private func emptyMessage(_ filter: ChatFilter) -> (title: String, description: String, systemImage: String) {
    switch filter {
    case .all:
      (L10n.Chats.Chats.EmptyState.NoConversations.title, L10n.Chats.Chats.EmptyState.NoConversations.description, "message")
    case .unread:
      (L10n.Chats.Chats.EmptyState.NoUnread.title, L10n.Chats.Chats.EmptyState.NoUnread.description, "checkmark.circle")
    case .directMessages:
      (
        L10n.Chats.Chats.EmptyState.NoDirectMessages.title,
        L10n.Chats.Chats.EmptyState.NoDirectMessages.description,
        "person"
      )
    case .channels:
      (L10n.Chats.Chats.EmptyState.NoChannels.title, L10n.Chats.Chats.EmptyState.NoChannels.description, "number")
    case .rooms:
      (L10n.Chats.Chats.EmptyState.NoRooms.title, L10n.Chats.Chats.EmptyState.NoRooms.description, "door.left.hand.open")
    }
  }
}

@MainActor
private final class ChatsFilterWindowHost {
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

private struct AccessNode {
  let object: NSObject
  let label: String
  let traits: UIAccessibilityTraits
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
      nodes.append(AccessNode(
        object: object,
        label: label,
        traits: object.accessibilityTraits,
        frame: object.accessibilityFrame,
        isElement: object.isAccessibilityElement
      ))
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
private func searchField(in view: UIView) -> UITextField? {
  if let field = view as? UITextField, field.bounds.width > 1 { return field }
  for subview in view.subviews {
    if let field = searchField(in: subview) { return field }
  }
  return nil
}

@MainActor
private func describe(_ nodes: [AccessNode]) -> String {
  nodes.map { "[\($0.label) traits=\($0.traits.rawValue)]" }.joined(separator: " ")
}
