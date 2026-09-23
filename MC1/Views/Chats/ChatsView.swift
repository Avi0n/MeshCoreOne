import MC1Services
import SwiftUI

struct ChatsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.horizontalSizeClass) private var sizeClass

  @State private var viewModel = ChatViewModel()
  @State private var columnVisibility = NavigationSplitViewVisibility.all
  @State private var preferredCompactColumn = NavigationSplitViewColumn.sidebar
  @State private var nestedPath = NavigationPath()

  private var tabBarVisibility: Visibility {
    ChatsSplitPresentation.tabBarVisibility(
      sizeClass: sizeClass,
      preferredColumn: preferredCompactColumn,
      hasSelection: appState.navigation.chatsSelectedRoute != nil
    )
  }

  var body: some View {
    NavigationSplitView(
      columnVisibility: $columnVisibility,
      preferredCompactColumn: $preferredCompactColumn
    ) {
      ChatsContentColumn(viewModel: viewModel)
        .sectionSplitColumnChrome()
    } detail: {
      ChatsDetailStack(viewModel: viewModel, path: $nestedPath)
    }
    .navigationSplitViewStyle(.balanced)
    .sectionSplitChrome(tabBarVisibility: tabBarVisibility)
    .sectionSplitState(
      columnVisibility: $columnVisibility,
      preferredCompactColumn: $preferredCompactColumn,
      nestedPathIsEmpty: nestedPath.isEmpty,
      hasSelection: appState.navigation.chatsSelectedRoute != nil,
      onClearRootSelection: { appState.navigation.chatsSelectedRoute = nil }
    )
    .onChange(of: appState.navigation.chatsSelectedRoute) { oldRoute, newRoute in
      handleSelectedRouteChange(from: oldRoute, to: newRoute)
    }
    .onChange(of: appState.navigation.chatsRootNavigationGeneration) { _, _ in
      nestedPath = NavigationPath()
    }
  }

  private func handleSelectedRouteChange(from oldRoute: ChatRoute?, to newRoute: ChatRoute?) {
    if oldRoute != newRoute {
      nestedPath = NavigationPath()
    }
    if newRoute != nil {
      if sizeClass == .compact {
        preferredCompactColumn = .detail
      }
    } else if sizeClass == .compact {
      preferredCompactColumn = .sidebar
    }
  }
}

private struct ChatsDetailStack: View {
  @Environment(\.appState) private var appState

  let viewModel: ChatViewModel
  @Binding var path: NavigationPath

  var body: some View {
    let route = appState.navigation.chatsSelectedRoute
    NavigationStack(path: $path) {
      ChatsSplitDetailContent(viewModel: viewModel, route: route)
        .sectionSplitColumnChrome()
    }
    .id(route?.conversationID)
  }
}

#Preview {
  ChatsView()
    .environment(\.appState, AppState())
}
