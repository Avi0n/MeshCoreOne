import MC1Services
import SwiftUI

/// Nodes tab host. One list model and one native split at every width; nested
/// telemetry lives on the detail stack, Blocked Contacts on the list stack.
struct ContactsListView: View {
  @Environment(\.appState) private var appState
  @Environment(\.horizontalSizeClass) private var sizeClass

  @State private var viewModel = ContactsViewModel()
  @State private var columnVisibility = NavigationSplitViewVisibility.all
  @State private var preferredCompactColumn = NavigationSplitViewColumn.sidebar
  @State private var nestedPath = NavigationPath()

  private var hasSelection: Bool {
    appState.navigation.selectedContact != nil || appState.navigation.nodesShowingDiscovery
  }

  private var tabBarVisibility: Visibility {
    ChatsSplitPresentation.tabBarVisibility(
      sizeClass: sizeClass,
      preferredColumn: preferredCompactColumn,
      hasSelection: hasSelection
    )
  }

  private var nodesDetailIdentity: String? {
    if appState.navigation.nodesShowingDiscovery {
      return "discovery"
    }
    return appState.navigation.selectedContact?.id.uuidString
  }

  var body: some View {
    NavigationSplitView(
      columnVisibility: $columnVisibility,
      preferredCompactColumn: $preferredCompactColumn
    ) {
      NavigationStack {
        ContactsContentColumn(viewModel: viewModel, observesPendingNavigation: false)
          .sectionSplitColumnChrome()
      }
    } detail: {
      NavigationStack(path: $nestedPath) {
        ContactsDetailColumn()
          .sectionSplitColumnChrome()
      }
      .id(nodesDetailIdentity)
    }
    .navigationSplitViewStyle(.balanced)
    .sectionSplitChrome(tabBarVisibility: tabBarVisibility)
    .onChange(of: sizeClass) { old, new in
      applySizeClassChange(from: old, to: new)
    }
    .onChange(of: preferredCompactColumn) { _, _ in
      applyPreferredColumnRecipe()
    }
    .onChange(of: nestedPath.count) { _, _ in
      applyPreferredColumnRecipe()
    }
    .onChange(of: appState.navigation.selectedContact?.id) { _, _ in
      nestedPath = NavigationPath()
      updatePreferredColumnForSelection()
    }
    .onChange(of: appState.navigation.nodesShowingDiscovery) { _, _ in
      nestedPath = NavigationPath()
      updatePreferredColumnForSelection()
    }
    .onChange(of: appState.navigation.nodesRootNavigationGeneration) { _, _ in
      nestedPath = NavigationPath()
    }
    .onChange(of: appState.navigation.pendingDiscoveryNavigation) { _, _ in
      consumePendingNodesNavigation()
    }
    .onChange(of: appState.navigation.pendingContactDetail) { _, _ in
      consumePendingNodesNavigation()
    }
    .task {
      if hasSelection, sizeClass == .compact {
        preferredCompactColumn = .detail
      }
      consumePendingNodesNavigation()
    }
  }

  private func consumePendingNodesNavigation() {
    if appState.navigation.pendingDiscoveryNavigation {
      appState.navigation.clearPendingDiscoveryNavigation()
      preferredCompactColumn = .detail
    }
    if appState.navigation.pendingContactDetail != nil {
      appState.navigation.clearPendingContactDetailNavigation()
      preferredCompactColumn = .detail
    }
  }

  private func updatePreferredColumnForSelection() {
    if hasSelection {
      if sizeClass == .compact {
        preferredCompactColumn = .detail
      }
    } else if sizeClass == .compact {
      preferredCompactColumn = .sidebar
    }
  }

  private func applySizeClassChange(
    from old: UserInterfaceSizeClass?,
    to new: UserInterfaceSizeClass?
  ) {
    let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
      from: old,
      to: new,
      hasSelection: hasSelection
    )
    if let visibility = presentation.columnVisibility {
      columnVisibility = visibility
    }
    if let column = presentation.preferredColumn {
      preferredCompactColumn = column
    }
  }

  private func applyPreferredColumnRecipe() {
    switch ChatsSplitPresentation.preferredColumnAction(
      preferredColumn: preferredCompactColumn,
      sizeClass: sizeClass,
      nestedPathIsEmpty: nestedPath.isEmpty,
      hasSelection: hasSelection
    ) {
    case .clearRootSelection:
      appState.navigation.selectedContact = nil
      appState.navigation.nodesShowingDiscovery = false
    case .none:
      break
    }
  }
}

#Preview {
  ContactsListView()
    .environment(\.appState, AppState())
}
