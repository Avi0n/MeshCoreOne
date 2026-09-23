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
        ContactsContentColumn(viewModel: viewModel)
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
    .sectionSplitState(
      columnVisibility: $columnVisibility,
      preferredCompactColumn: $preferredCompactColumn,
      nestedPathIsEmpty: nestedPath.isEmpty,
      hasSelection: hasSelection,
      onClearRootSelection: {
        appState.navigation.selectedContact = nil
        appState.navigation.nodesShowingDiscovery = false
      }
    )
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
}

#Preview {
  ContactsListView()
    .environment(\.appState, AppState())
}
