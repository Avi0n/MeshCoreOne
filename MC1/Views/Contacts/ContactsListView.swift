import MC1Services
import SwiftUI

/// Screen lifetimes stay above the native navigation containers so resizing only replaces renderers.
struct ContactsListView: View {
  @Environment(\.appState) private var appState
  @Environment(\.horizontalSizeClass) private var sizeClass

  @State private var routes = NodesNavigationState()
  @State private var sidebar = ContactsSidebarState()
  @State private var blocked: BlockedContactsState?
  @State private var contact: ContactDetailState?
  @State private var discovery: DiscoveryState?
  @State private var history: TelemetryHistoryOverviewState?
  @State private var map: NodeLocationMapState?
  @State private var rootRevision: Int?
  @State private var blockedRevision: Int?
  @State private var historyRoute: ContactRoute.TelemetryHistory?
  @State private var columnVisibility = NavigationSplitViewVisibility.all

  private var navigation: NavigationCoordinator {
    appState.navigation
  }

  private var detailPath: [NodesRoute] {
    routes.detailPath(in: navigation)
  }

  private var tabBarVisibility: Visibility {
    sizeClass == .compact ? .visible : .automatic
  }

  var body: some View {
    navigationContent
      .sectionSplitChrome(tabBarVisibility: tabBarVisibility)
      .modifier(ContactsSidebarPresenter(state: sidebar))
      .modifier(BlockedContactsPresenter(state: blocked))
      .modifier(DiscoveryPresenter(state: discovery))
      .modifier(ContactDetailPresenter(state: contact))
      .onChange(of: sizeClass, initial: true) { _, value in
        routes.changePresentation(to: value == .regular ? .regular : .compact) { revision in
          sidebar.prepareRenderer(revision: revision)
          blocked?.prepareRenderer(revision: revision)
          discovery?.prepareRenderer(revision: revision)
          contact?.prepareRenderer(revision: revision)
          history?.prepareRenderer(revision: revision)
          map?.prepareRenderer(revision: revision)
        }
        if routes.presentation == .regular { columnVisibility = .all }
      }
      .onChange(of: NodesNavigationState.stamp(in: navigation), initial: true) { _, _ in
        reconcileScreens()
      }
      .onChange(of: navigation.selectedContact) { _, value in
        if let value { contact?.updateContact(value) }
      }
      .onChange(of: routes.sidebarRevision, initial: true) { _, _ in
        reconcileScreens()
      }
      .onChange(of: detailPath, initial: true) { _, _ in
        reconcileScreens()
      }
      .onChange(of: appState.servicesVersion) { _, _ in
        configureContact()
        history?.configure(appState: appState)
        updateContactActivity()
      }
      .onChange(of: navigation.selectedTab) { _, _ in updateContactActivity() }
      .onAppear { updateContactActivity() }
      .onDisappear { contact?.setActive(false, appState: appState) }
  }

  @ViewBuilder
  private var navigationContent: some View {
    switch routes.presentation {
    case .compact:
      NavigationStack(path: routes.compactBinding(in: navigation)) {
        sidebarContent
          .navigationDestination(for: NodesRoute.self) { destination($0) }
      }
    case .regular:
      NavigationSplitView(columnVisibility: $columnVisibility) {
        NavigationStack(path: routes.sidebarBinding()) {
          sidebarContent
            .navigationDestination(for: NodesRoute.self) { destination($0) }
        }
      } detail: {
        NavigationStack(path: routes.detailBinding(in: navigation)) {
          rootContent
            .id(navigation.nodesPresentationRevision)
            .sectionSplitColumnChrome()
            .navigationDestination(for: NodesRoute.self) { destination($0) }
        }
      }
      .navigationSplitViewStyle(.balanced)
    case nil:
      ProgressView()
    }
  }

  private var sidebarContent: some View {
    ContactsContentColumn(
      state: sidebar,
      blockedContactsRoute: .blockedContacts,
      rendererRevision: routes.layoutRevision
    )
    .sectionSplitColumnChrome()
  }

  @ViewBuilder
  private var rootContent: some View {
    if let root = NodesNavigationState.root(in: navigation) {
      destination(root)
    } else {
      ContentUnavailableView(L10n.Contacts.Contacts.List.selectNode, systemImage: "flipphone")
    }
  }

  private func destination(_ route: NodesRoute) -> some View {
    Group {
      switch route {
      case .blockedContacts:
        if let blocked { BlockedContactsView(state: blocked, rendererRevision: routes.layoutRevision) }
      case let .contact(id, revision):
        if navigation.selectedContact?.id == id, navigation.nodesPresentationRevision == revision,
           rootRevision == revision, let contact, contact.currentContact.id == id {
          ContactDetailContent(
            state: contact, historyRoute: NodesRoute.savedHistory, rendererRevision: routes.layoutRevision
          )
        }
      case let .discovery(revision):
        if navigation.nodesShowingDiscovery, navigation.nodesPresentationRevision == revision,
           rootRevision == revision, let discovery {
          DiscoveryView(state: discovery, rendererRevision: routes.layoutRevision)
        }
      case let .savedHistory(route):
        if rootRevision == navigation.nodesPresentationRevision, historyRoute == route, let history {
          TelemetryHistoryOverviewView(state: history, rendererRevision: routes.layoutRevision) { snapshots, selectedID in
            openMap(snapshots: snapshots, selectedID: selectedID, from: history)
          }
          .navigationBarTitleDisplayMode(.inline)
        }
      case let .locationMap(presentationID):
        if rootRevision == navigation.nodesPresentationRevision,
           let map, map.presentationID == presentationID {
          NodeLocationMapView(state: map, rendererRevision: routes.layoutRevision)
        }
      }
    }
    .id(routes.layoutRevision)
    .toolbarVisibility(.visible, for: .navigationBar)
  }

  private func reconcileScreens() {
    routes.synchronize(with: navigation)
    if rootRevision != navigation.nodesPresentationRevision {
      contact?.end()
      discovery?.end()
      history?.end()
      map?.end()
      contact = nil
      discovery = nil
      history = nil
      historyRoute = nil
      map = nil
      rootRevision = navigation.nodesPresentationRevision
      if navigation.nodesShowingDiscovery {
        discovery = DiscoveryState()
        discovery?.prepareRenderer(revision: routes.layoutRevision)
      } else if let selected = navigation.selectedContact {
        contact = ContactDetailState(contact: selected)
        contact?.prepareRenderer(revision: routes.layoutRevision)
        configureContact()
      }
    }
    if routes.sidebarPath.isEmpty || blockedRevision != routes.sidebarRevision {
      blocked?.end()
      blocked = nil
      blockedRevision = routes.sidebarRevision
    }
    if !routes.sidebarPath.isEmpty, blocked == nil {
      blocked = BlockedContactsState()
      blocked?.prepareRenderer(revision: routes.layoutRevision)
    }
    let nextHistory: ContactRoute.TelemetryHistory? = detailPath.first.flatMap {
      if case let .savedHistory(value) = $0 { return value }
      return nil
    }
    if nextHistory != historyRoute {
      history?.end()
      map?.end()
      history = nextHistory.map(TelemetryHistoryOverviewState.init(history:))
      historyRoute = nextHistory
      map = nil
      history?.prepareRenderer(revision: routes.layoutRevision)
      history?.configure(appState: appState)
    }
    if !detailPath.contains(where: {
      if case .locationMap = $0 { return true }
      return false
    }) {
      map?.end()
      map = nil
    }
    updateContactActivity()
  }

  private func configureContact() {
    guard let contact else { return }
    let revision = navigation.nodesPresentationRevision
    contact.configure(appState: appState, isCurrentPresentation: {
      navigation.nodesPresentationRevision == revision
    }) {
      navigation.clearNodesRoot(matchingPresentation: revision)
    }
  }

  private func updateContactActivity() {
    contact?.setActive(
      navigation.selectedTab == AppTab.nodes.rawValue && detailPath.isEmpty,
      appState: appState
    )
  }

  private func openMap(
    snapshots: [NodeStatusSnapshotDTO], selectedID: UUID?, from source: TelemetryHistoryOverviewState
  ) {
    guard history === source, rootRevision == navigation.nodesPresentationRevision,
          detailPath == [.savedHistory(source.history)] else { return }
    map?.end()
    let entry = NodeLocationMapState(snapshots: snapshots, initialSelectedReportID: selectedID)
    entry.prepareRenderer(revision: routes.layoutRevision)
    map = entry
    routes.openMap(presentationID: entry.presentationID, in: navigation)
  }
}

#Preview {
  ContactsListView()
    .environment(\.appState, AppState())
}
