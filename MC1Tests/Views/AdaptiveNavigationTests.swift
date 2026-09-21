@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Adaptive navigation", .serialized)
@MainActor
struct AdaptiveNavigationTests {
  private enum Layout {
    static let regularSize = CGSize(width: 1024, height: 768)
    static let compactSize = CGSize(width: 390, height: 844)
    static let waitTimeout: TimeInterval = 6
    static let runLoopSlice: TimeInterval = 0.05
    static let settle: TimeInterval = 0.35
  }

  // MARK: - Presentation recipe

  @Test
  func `collapsed compact detail hides tabs`() {
    let visibility = ChatsSplitPresentation.tabBarVisibility(
      sizeClass: .compact,
      preferredColumn: .detail,
      hasSelection: true
    )
    #expect(visibility == .hidden)
  }

  @Test
  func `compact sidebar leaves the tab bar automatic`() {
    let visibility = ChatsSplitPresentation.tabBarVisibility(
      sizeClass: .compact,
      preferredColumn: .sidebar,
      hasSelection: false
    )
    #expect(visibility == .automatic)
  }

  @Test
  func `regular width leaves the tab bar automatic with a selection`() {
    let visibility = ChatsSplitPresentation.tabBarVisibility(
      sizeClass: .regular,
      preferredColumn: .detail,
      hasSelection: true
    )
    #expect(visibility == .automatic)
  }

  @Test
  func `compact sidebar preferred column clears the root`() {
    let action = ChatsSplitPresentation.preferredColumnAction(
      preferredColumn: .sidebar,
      sizeClass: .compact,
      nestedPathIsEmpty: true,
      hasSelection: true
    )
    #expect(action == .clearRootSelection)
  }

  @Test
  func `nested path keeps the root when compact preferred column is sidebar`() {
    let action = ChatsSplitPresentation.preferredColumnAction(
      preferredColumn: .sidebar,
      sizeClass: .compact,
      nestedPathIsEmpty: false,
      hasSelection: true
    )
    #expect(action == .none)
  }

  @Test
  func `regular preferred sidebar does not overlay the selected detail`() {
    let action = ChatsSplitPresentation.preferredColumnAction(
      preferredColumn: .sidebar,
      sizeClass: .regular,
      nestedPathIsEmpty: true,
      hasSelection: true
    )
    #expect(action == .none)
  }

  @Test
  func `expanding compact to regular tiles list and detail`() {
    let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
      from: .compact,
      to: .regular,
      hasSelection: true
    )
    #expect(presentation.columnVisibility == .all)
    #expect(presentation.preferredColumn == .sidebar)
  }

  @Test
  func `collapsing regular to compact with a selection shows the detail`() {
    let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
      from: .regular,
      to: .compact,
      hasSelection: true
    )
    #expect(presentation.preferredColumn == .detail)
  }

  // MARK: - Radio-scoped auth

  @Test
  func `room auth is kept only for the connected radio that opened it`() {
    let radioID = UUID()
    #expect(
      ChatsRadioScopedSheets.shouldKeepRoomAuth(
        sessionRadioID: radioID,
        currentRadioID: radioID,
        hasConnectedDevice: true
      )
    )
    #expect(
      !ChatsRadioScopedSheets.shouldKeepRoomAuth(
        sessionRadioID: radioID,
        currentRadioID: radioID,
        hasConnectedDevice: false
      )
    )
    #expect(
      !ChatsRadioScopedSheets.shouldKeepRoomAuth(
        sessionRadioID: radioID,
        currentRadioID: UUID(),
        hasConnectedDevice: true
      )
    )
  }

  // MARK: - Scroll request delivery

  @Test
  func `scroll target is preserved until the conversation can honor it`() {
    let appState = AppState()
    let contact = makeContact()
    let messageID = UUID()
    appState.navigation.navigateToChat(with: contact, scrollToMessageID: messageID)

    let skipped = ChatScrollRequestDelivery.takeIfHonorable(
      from: appState.navigation,
      kind: .direct,
      conversationID: contact.id,
      canHonor: false
    )
    #expect(skipped == nil)
    #expect(appState.navigation.pendingScrollToMessageID == messageID)

    let taken = ChatScrollRequestDelivery.takeIfHonorable(
      from: appState.navigation,
      kind: .direct,
      conversationID: contact.id,
      canHonor: true
    )
    #expect(taken == messageID)
    #expect(appState.navigation.pendingScrollTarget == nil)
  }

  // MARK: - Hosted ChatsView

  @Test(arguments: [ChatRoute.Kind.direct, .channel, .room])
  func `hosted chats split back from collapsed root clears the route`(
    _ kind: ChatRoute.Kind
  ) throws {
    let appState = AppState()
    let route = makeRoute(kind: kind)
    appState.navigation.chatsSelectedRoute = route
    appState.navigation.selectedTab = AppTab.chats.rawValue

    let host = try mount(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == route },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test(arguments: [ChatRoute.Kind.direct, .channel, .room])
  func `hosted chats split keeps the route across compact and regular`(
    _ kind: ChatRoute.Kind
  ) throws {
    let appState = AppState()
    let route = makeRoute(kind: kind)
    appState.navigation.chatsSelectedRoute = route
    appState.navigation.selectedTab = AppTab.chats.rawValue

    let host = try mount(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.chatsSelectedRoute == route)

    resize(host, to: Layout.compactSize, regularWidth: false)
    #expect(appState.navigation.chatsSelectedRoute == route)

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == nil },
      "\(describe(host, appState: appState))"
    )

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.chatsSelectedRoute == nil)
  }

  @Test
  func `programmatic chat navigation from another tab selects the route`() throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    let contact = makeContact()

    let host = try mount(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToChat(with: contact)
    settle(host.window)

    #expect(appState.navigation.selectedTab == AppTab.chats.rawValue)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    try #require(
      waitUntil { appState.navigation.chatsSelectedRoute == .direct(contact) },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `explicit root navigation bumps generation without changing identity`() {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.navigateToChat(with: contact)
    let generation = appState.navigation.chatsRootNavigationGeneration
    appState.navigation.navigateToChat(with: contact)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.chatsRootNavigationGeneration == generation + 1)
  }

  // MARK: - Hosted Nodes

  @Test
  func `hosted nodes split back from collapsed contact clears the selection`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.selectedContact?.id == contact.id },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedContact == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hosted nodes split back from collapsed discovery clears discovery`() throws {
    let appState = AppState()
    appState.navigation.nodesShowingDiscovery = true
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.nodesShowingDiscovery },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { !appState.navigation.nodesShowingDiscovery },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hosted nodes split keeps the contact across compact and regular`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedContact?.id == contact.id)

    resize(host, to: Layout.compactSize, regularWidth: false)
    #expect(appState.navigation.selectedContact?.id == contact.id)

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedContact == nil },
      "\(describe(host, appState: appState))"
    )

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedContact == nil)
  }

  @Test
  func `hosted nodes nested back keeps the selected contact`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.regularSize, regularWidth: true)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.selectedContact?.id == contact.id },
      "\(describe(host, appState: appState))"
    )
    try #require(
      pushNestedPage(in: host.window),
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedContact?.id == contact.id },
      "\(describe(host, appState: appState))"
    )
    #expect(appState.navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `confirmed list removal of the selected contact clears the root`() throws {
    let appState = AppState()
    let contact = makeContact()
    let other = makeContact(name: "Sam")
    appState.navigation.selectedContact = contact
    let viewModel = ContactsViewModel()
    viewModel.contacts = [contact, other]

    let host = try mountNodesContent(appState: appState, viewModel: viewModel)
    defer { dismount(host) }

    viewModel.pendingRemovalIDs.insert(contact.id)
    try #require(
      waitUntil { appState.navigation.selectedContact == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hiding a contact with a filter does not clear the selection`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    let viewModel = ContactsViewModel()
    viewModel.contacts = [contact]

    let host = try mountNodesContent(appState: appState, viewModel: viewModel)
    defer { dismount(host) }

    viewModel.contacts = []
    settle(host.window)
    #expect(appState.navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `programmatic contact navigation from another tab selects the contact`() throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.chats.rawValue
    let contact = makeContact()

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToContactDetail(contact)
    settle(host.window)

    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)
    #expect(appState.navigation.selectedContact?.id == contact.id)
    #expect(appState.navigation.nodesShowingDiscovery == false)
  }

  @Test
  func `full radio invalidation while nodes is inactive does not restore the contact`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTab = AppTab.nodes.rawValue

    let host = try mountNodes(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.selectedTab = AppTab.chats.rawValue
    settle(host.window)
    appState.navigation.clearPerRadioSelection()
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    settle(host.window)

    #expect(appState.navigation.selectedContact == nil)
    #expect(appState.navigation.nodesShowingDiscovery == false)
  }

  // MARK: - Hosted Settings

  @Test
  func `hosted settings split back from collapsed page clears the selection`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .language
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.selectedSetting == .language },
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedSetting == nil },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `hosted settings split keeps the page across compact and regular`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .notifications
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedSetting == .notifications)

    resize(host, to: Layout.compactSize, regularWidth: false)
    #expect(appState.navigation.selectedSetting == .notifications)

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedSetting == nil },
      "\(describe(host, appState: appState))"
    )

    resize(host, to: Layout.regularSize, regularWidth: true)
    #expect(appState.navigation.selectedSetting == nil)
  }

  @Test
  func `hosted settings nested back keeps the selected page`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .chats
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.regularSize, regularWidth: true)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.navigation.selectedSetting == .chats },
      "\(describe(host, appState: appState))"
    )
    try #require(
      pushNestedPage(in: host.window),
      "\(describe(host, appState: appState))"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedSetting == .chats },
      "\(describe(host, appState: appState))"
    )
  }

  @Test
  func `programmatic settings navigation replaces an open page`() throws {
    let appState = AppState()
    appState.navigation.selectedSetting = .chats
    appState.navigation.selectedTab = AppTab.settings.rawValue

    let host = try mountSettings(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.navigateToSetting(.language)
    settle(host.window)

    #expect(appState.navigation.selectedTab == AppTab.settings.rawValue)
    #expect(appState.navigation.selectedSetting == .language)
  }

  // MARK: - Root tab host

  @Test
  func `app tabs keep chats nodes map tools settings order`() {
    #expect(AppTab.chats.rawValue == 0)
    #expect(AppTab.nodes.rawValue == 1)
    #expect(AppTab.map.rawValue == 2)
    #expect(AppTab.tools.rawValue == 3)
    #expect(AppTab.settings.rawValue == 4)
  }

  @Test(arguments: [false, true])
  func `hosted tab root shows five native tabs`(regularWidth: Bool) throws {
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.chats.rawValue
    let size = regularWidth ? Layout.regularSize : Layout.compactSize
    let host = try mountTabs(appState: appState, size: size, regularWidth: regularWidth)
    defer { dismount(host) }

    try #require(
      waitUntil { tabItemCount(in: host.window) == 5 },
      "\(describe(host, appState: appState)) tabs=\(tabItemCount(in: host.window))"
    )
  }

  @Test
  func `hosted tab root retains a chat route across tab switches`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.navigateToChat(with: contact)

    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.navigation.selectedTab = AppTab.nodes.rawValue
    settle(host.window)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedTab == AppTab.nodes.rawValue)

    appState.navigation.selectedTab = AppTab.chats.rawValue
    settle(host.window)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
  }

  @Test
  func `hosted tab root disconnect clears radio tool and device settings`() throws {
    let appState = AppState()
    let contact = makeContact()
    appState.navigation.chatsSelectedRoute = .direct(contact)
    appState.navigation.selectedContact = contact
    appState.navigation.selectedTool = .tracePath
    appState.navigation.selectedSetting = .radio
    appState.connectionManager.setTestState(connectedDevice: makeDevice())

    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { appState.connectedDevice != nil },
      "\(describe(host, appState: appState))"
    )

    appState.connectionManager.setTestState(connectedDevice: .some(nil))
    try #require(
      waitUntil {
        appState.navigation.selectedTool == nil
          && appState.navigation.selectedSetting == nil
      },
      "\(describe(host, appState: appState))"
    )
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
    #expect(appState.navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `hosted tab root keeps routes when the connected radio changes`() throws {
    let appState = AppState()
    appState.navigation.selectedTool = .tracePath
    appState.navigation.selectedSetting = .radio
    appState.connectionManager.setTestState(connectedDevice: makeDevice())

    let host = try mountTabs(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    appState.connectionManager.setTestState(connectedDevice: makeDevice())
    settle(host.window)

    #expect(appState.navigation.selectedTool == .tracePath)
    #expect(appState.navigation.selectedSetting == .radio)
  }

  @Test
  func `hosted tools stack follows selectedTool and survives a tab switch`() throws {
    // One light destination. The path binding is the same for every tool, and
    // the map-backed tools are what get the full suite killed.
    let tool = ToolSelection.rxLog
    let appState = AppState()
    appState.navigation.selectedTab = AppTab.tools.rawValue
    appState.navigation.selectedTool = tool

    let host = try mountTools(appState: appState, size: Layout.compactSize, regularWidth: false)
    defer { dismount(host) }

    try #require(
      waitUntil { toolWorkspaceIsVisible(tool, in: host.window) },
      "\(describe(host, appState: appState)) tool=\(tool.title)"
    )

    appState.navigation.selectedTab = AppTab.chats.rawValue
    settle(host.window)
    #expect(appState.navigation.selectedTool == tool)

    appState.navigation.selectedTab = AppTab.tools.rawValue
    settle(host.window)
    try #require(
      waitUntil { toolWorkspaceIsVisible(tool, in: host.window) },
      "\(describe(host, appState: appState)) tool=\(tool.title)"
    )

    let didBack = performNativeBack(in: host.window)
    #expect(didBack, "\(describe(host, appState: appState))")
    try #require(
      waitUntil { appState.navigation.selectedTool == nil },
      "\(describe(host, appState: appState))"
    )
  }

  // MARK: - Hosting

  private struct Host {
    let window: UIWindow
    let controller: UIViewController
  }

  private struct ChatsAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          ChatsView()
        }
        Tab("Nodes", systemImage: "flipphone", value: AppTab.nodes.rawValue) {
          Text("Nodes")
        }
      }
      .environment(\.appState, appState)
    }
  }

  private struct NodesAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          ChatsView()
        }
        Tab("Nodes", systemImage: "flipphone", value: AppTab.nodes.rawValue) {
          ContactsListView()
        }
      }
      .environment(\.appState, appState)
    }
  }

  private struct SettingsAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          ChatsView()
        }
        Tab("Settings", systemImage: "gear", value: AppTab.settings.rawValue) {
          SettingsView()
        }
      }
      .environment(\.appState, appState)
    }
  }

  private struct NodesContentHost: View {
    let appState: AppState
    let viewModel: ContactsViewModel

    var body: some View {
      NavigationStack {
        ContactsContentColumn(viewModel: viewModel)
      }
      .environment(\.appState, appState)
    }
  }

  private struct MainTabHost: View {
    let appState: AppState

    var body: some View {
      MainTabView()
        .environment(\.appState, appState)
    }
  }

  private struct ToolsAdaptiveHost: View {
    let appState: AppState

    var body: some View {
      @Bindable var navigation = appState.navigation
      TabView(selection: $navigation.selectedTab) {
        Tab("Chats", systemImage: "message.fill", value: AppTab.chats.rawValue) {
          Text("Chats")
        }
        Tab("Tools", systemImage: "wrench.and.screwdriver", value: AppTab.tools.rawValue) {
          ToolsView()
        }
      }
      .environment(\.appState, appState)
    }
  }

  private func mount(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(ChatsAdaptiveHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountNodes(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(NodesAdaptiveHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountSettings(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(SettingsAdaptiveHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountNodesContent(
    appState: AppState,
    viewModel: ContactsViewModel
  ) throws -> Host {
    try mount(
      NodesContentHost(appState: appState, viewModel: viewModel),
      size: Layout.compactSize,
      regularWidth: false
    )
  }

  private func mountTabs(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(MainTabHost(appState: appState), size: size, regularWidth: regularWidth)
  }

  private func mountTools(
    appState: AppState,
    size: CGSize,
    regularWidth: Bool
  ) throws -> Host {
    try mount(
      ToolsAdaptiveHost(appState: appState),
      size: size,
      regularWidth: regularWidth,
      attachesToScene: false
    )
  }

  private func mount(
    _ rootView: some View,
    size: CGSize,
    regularWidth: Bool,
    attachesToScene: Bool = true
  ) throws -> Host {
    let controller = UIHostingController(rootView: rootView)
    let window = attachesToScene
      ? makeWindow(size: size)
      : UIWindow(frame: CGRect(origin: .zero, size: size))
    window.rootViewController = controller
    applySizeClass(controller, regularWidth: regularWidth)
    window.frame = CGRect(origin: .zero, size: size)
    controller.view.frame = window.bounds
    if attachesToScene {
      window.makeKeyAndVisible()
    } else {
      window.isHidden = false
    }
    window.layoutIfNeeded()
    settle(window)
    return Host(window: window, controller: controller)
  }

  private func makeWindow(size: CGSize) -> UIWindow {
    let frame = CGRect(origin: .zero, size: size)
    if let scene = UIApplication.shared.connectedScenes
      .compactMap({ $0 as? UIWindowScene })
      .first {
      let window = UIWindow(windowScene: scene)
      window.frame = frame
      return window
    }
    return UIWindow(frame: frame)
  }

  private func applySizeClass(_ controller: UIViewController, regularWidth: Bool) {
    controller.traitOverrides.horizontalSizeClass = regularWidth ? .regular : .compact
    controller.traitOverrides.verticalSizeClass = .regular
  }

  private func resize(_ host: Host, to size: CGSize, regularWidth: Bool) {
    host.window.frame = CGRect(origin: .zero, size: size)
    applySizeClass(host.controller, regularWidth: regularWidth)
    host.window.layoutIfNeeded()
    settle(host.window)
  }

  private func dismount(_ host: Host) {
    host.window.rootViewController = nil
    host.window.isHidden = true
    host.window.windowScene = nil
    spin(Layout.runLoopSlice)
  }

  private func settle(_ window: UIWindow) {
    window.layoutIfNeeded()
    spin(Layout.settle)
  }

  private func spin(_ duration: TimeInterval) {
    RunLoop.main.run(until: Date(timeIntervalSinceNow: duration))
  }

  private func waitUntil(
    timeout: TimeInterval = Layout.waitTimeout,
    _ condition: () -> Bool
  ) -> Bool {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      if condition() { return true }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
    }
    return condition()
  }

  private func pushNestedPage(in window: UIWindow) -> Bool {
    let navs = navigationControllers(from: window.rootViewController)
    // Regular split: the detail column sits further trailing than the list.
    let nav = navs.max { lhs, rhs in
      lhs.view.convert(lhs.view.bounds, to: window).minX
        < rhs.view.convert(rhs.view.bounds, to: window).minX
    }
    guard let nav else { return false }
    let extra = UIViewController()
    extra.navigationItem.title = "Nested"
    nav.pushViewController(extra, animated: false)
    window.layoutIfNeeded()
    return nav.viewControllers.count > 1
  }

  private func performNativeBack(in window: UIWindow) -> Bool {
    let navs = navigationControllers(from: window.rootViewController)
    if let nav = navs.filter({ $0.viewControllers.count > 1 })
      .max(by: { $0.viewControllers.count < $1.viewControllers.count }) {
      nav.popViewController(animated: false)
      window.layoutIfNeeded()
      return true
    }
    if let nav = navs.first(where: { $0.navigationBar.backItem != nil }) {
      nav.popViewController(animated: false)
      window.layoutIfNeeded()
      return true
    }
    // iOS 18 compact NavigationSplitView often has no extra stack item; Back
    // is the split showing its primary column.
    if let split = splitViewControllers(from: window.rootViewController).last {
      split.show(.primary)
      window.layoutIfNeeded()
      return true
    }
    return false
  }

  private func navigationControllers(from root: UIViewController?) -> [UINavigationController] {
    viewControllers(from: root, as: UINavigationController.self)
  }

  private func splitViewControllers(from root: UIViewController?) -> [UISplitViewController] {
    viewControllers(from: root, as: UISplitViewController.self)
  }

  private func viewControllers<T: UIViewController>(from root: UIViewController?, as type: T.Type) -> [T] {
    guard let root else { return [] }
    var result: [T] = []
    var seen = Set<ObjectIdentifier>()
    func walk(_ viewController: UIViewController) {
      let id = ObjectIdentifier(viewController)
      guard seen.insert(id).inserted else { return }
      if let typed = viewController as? T {
        result.append(typed)
      }
      if let nav = viewController as? UINavigationController {
        nav.viewControllers.forEach(walk)
      }
      viewController.children.forEach(walk)
    }
    walk(root)
    return result
  }

  private func describe(_ host: Host, appState: AppState) -> String {
    let navs = navigationControllers(from: host.controller)
    let titles = navs.map { nav in
      nav.viewControllers.map { $0.navigationItem.title ?? String(describing: type(of: $0)) }
    }
    return """
    route=\(String(describing: appState.navigation.chatsSelectedRoute?.kind)) \
    contact=\(String(describing: appState.navigation.selectedContact?.id)) \
    discovery=\(appState.navigation.nodesShowingDiscovery) \
    setting=\(String(describing: appState.navigation.selectedSetting)) \
    tab=\(appState.navigation.selectedTab) \
    navs=\(titles) window=\(host.window.bounds)
    """
  }

  private func makeRoute(kind: ChatRoute.Kind) -> ChatRoute {
    switch kind {
    case .direct:
      .direct(makeContact())
    case .channel:
      .channel(makeChannel())
    case .room:
      .room(makeRoom())
    }
  }

  private func makeContact(name: String = "Alex") -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAB, count: ProtocolLimits.publicKeySize),
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
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func makeChannel() -> ChannelDTO {
    ChannelDTO(
      id: UUID(),
      radioID: UUID(),
      index: 1,
      name: "General",
      secret: Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func makeRoom() -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xCC, count: ProtocolLimits.publicKeySize),
      name: "Ops",
      role: .roomServer,
      isConnected: true
    )
  }

  private func makeDevice() -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      publicKey: Data(repeating: 0xBB, count: ProtocolLimits.publicKeySize),
      nodeName: "Relay",
      firmwareVersion: 1,
      firmwareVersionString: "1.12.0",
      manufacturerName: "Test",
      buildDate: "2025-01-01",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  private func tabItemCount(in window: UIWindow) -> Int {
    let barCounts = tabBars(from: window).compactMap(\.items).map(\.count)
    if let count = barCounts.max(), count > 0 {
      return count
    }
    let titles = [
      L10n.Localizable.Tabs.chats,
      L10n.Localizable.Tabs.nodes,
      L10n.Localizable.Tabs.map,
      L10n.Localizable.Tabs.tools,
      L10n.Localizable.Tabs.settings,
    ]
    return titles.filter { containsText($0, in: window) }.count
  }

  private func tabBars(from window: UIWindow) -> [UITabBar] {
    var bars: [UITabBar] = []
    func walk(_ view: UIView) {
      if let bar = view as? UITabBar {
        bars.append(bar)
      }
      view.subviews.forEach(walk)
    }
    walk(window)
    return bars
  }

  /// Trace Path's bar shows the List/Map picker, so visibility uses `Contacts.Trace.title`.
  /// Line of Sight has no navigation title; its layout probe is the workspace signal.
  private func toolWorkspaceIsVisible(_ tool: ToolSelection, in window: UIWindow) -> Bool {
    switch tool {
    case .lineOfSight:
      containsIdentifier(LineOfSightLayoutMode.mapWithSheet.accessibilityIdentifier, in: window)
        || containsIdentifier(LineOfSightLayoutMode.paired.accessibilityIdentifier, in: window)
    default:
      navigationStackContains(workspaceNavigationTitle(for: tool), in: window)
    }
  }

  private func workspaceNavigationTitle(for tool: ToolSelection) -> String {
    switch tool {
    case .tracePath: L10n.Contacts.Contacts.Trace.title
    default: tool.title
    }
  }

  private func containsIdentifier(_ identifier: String, in view: UIView) -> Bool {
    if view.accessibilityIdentifier == identifier { return true }
    return view.subviews.contains { containsIdentifier(identifier, in: $0) }
  }

  private func navigationStackContains(_ title: String, in window: UIWindow) -> Bool {
    navigationControllers(from: window.rootViewController).contains { nav in
      nav.viewControllers.contains { $0.navigationItem.title == title }
    }
  }

  private func containsText(_ text: String, in view: UIView) -> Bool {
    if view.accessibilityLabel == text { return true }
    if let label = view as? UILabel, label.text == text { return true }
    if let button = view as? UIButton, button.currentTitle == text { return true }
    if let item = (view as? UITabBar)?.items?.contains(where: { $0.title == text }) {
      if item { return true }
    }
    return view.subviews.contains { containsText(text, in: $0) }
  }
}
