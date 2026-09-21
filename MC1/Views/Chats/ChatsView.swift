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

  private var actions: ChatListActions {
    ChatListActions(
      viewModel: viewModel,
      appState: appState,
      roomToDelete: .constant(nil),
      showRoomDeleteAlert: .constant(false),
      channelDeleteFailure: .constant(nil),
      showChannelDeleteFailed: .constant(false),
      roomToAuthenticate: .constant(nil),
      navigate: { selectRoute($0) },
      clearNavigationIfActive: clearNavigationIfActive
    )
  }

  var body: some View {
    NavigationSplitView(
      columnVisibility: $columnVisibility,
      preferredCompactColumn: $preferredCompactColumn
    ) {
      ChatsContentColumn(viewModel: viewModel, observesPendingNavigation: false)
        .sectionSplitColumnChrome()
    } detail: {
      ChatsDetailStack(viewModel: viewModel, path: $nestedPath)
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
    .onChange(of: appState.navigation.chatsSelectedRoute) { oldRoute, newRoute in
      handleSelectedRouteChange(from: oldRoute, to: newRoute)
    }
    .onChange(of: appState.navigation.chatsRootNavigationGeneration) { _, _ in
      nestedPath = NavigationPath()
    }
    .onChange(of: appState.navigation.pendingChatContact) { _, _ in
      actions.handlePendingNavigation()
    }
    .onChange(of: appState.navigation.pendingChannel) { _, _ in
      actions.handlePendingChannelNavigation()
    }
    .onChange(of: appState.navigation.pendingRoomSession) { _, _ in
      actions.handlePendingRoomNavigation()
    }
    .task {
      if appState.navigation.chatsSelectedRoute != nil, sizeClass == .compact {
        preferredCompactColumn = .detail
      }
      actions.handlePendingNavigation()
      actions.handlePendingChannelNavigation()
      actions.handlePendingRoomNavigation()
    }
  }

  private func selectRoute(_ route: ChatRoute) {
    if case let .room(session) = route, !session.isConnected {
      return
    }
    appState.navigation.chatsSelectedRoute = route
  }

  private func clearNavigationIfActive(_ route: ChatRoute) {
    if appState.navigation.chatsSelectedRoute == route {
      appState.navigation.chatsSelectedRoute = nil
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

  private func applySizeClassChange(
    from old: UserInterfaceSizeClass?,
    to new: UserInterfaceSizeClass?
  ) {
    let presentation = ChatsSplitPresentation.presentationForSizeClassChange(
      from: old,
      to: new,
      hasSelection: appState.navigation.chatsSelectedRoute != nil
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
      hasSelection: appState.navigation.chatsSelectedRoute != nil
    ) {
    case .clearRootSelection:
      appState.navigation.chatsSelectedRoute = nil
    case .none:
      break
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
