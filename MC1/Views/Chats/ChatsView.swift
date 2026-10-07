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
    SectionSplitPresentation.tabBarVisibility(
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
      onClearRootSelection: { appState.navigation.setChatsRoute(nil) }
    )
    .onChange(of: appState.navigation.chatsSelectedRoute) { oldRoute, newRoute in
      if oldRoute != newRoute {
        nestedPath = NavigationPath()
      }
    }
    .onChange(of: appState.navigation.chatsRootNavigationGeneration) { _, _ in
      nestedPath = NavigationPath()
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
