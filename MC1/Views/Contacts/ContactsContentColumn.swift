import MC1Services
import SwiftUI

/// Renders the Nodes list from the sidebar state retained by ContactsListView.
struct ContactsContentColumn: View {
  @Environment(\.appState) private var appState
  @State private var ownedState: ContactsSidebarState
  private let suppliedState: ContactsSidebarState?
  private let blockedContactsRoute: NodesRoute?
  private let rendererRevision: Int

  @AppStorage(AppStorageKey.nodesSortOrder.rawValue) private var sortOrder: NodeSortOrder = .lastHeard

  init(viewModel: ContactsViewModel) {
    _ownedState = State(initialValue: ContactsSidebarState(viewModel: viewModel))
    suppliedState = nil
    blockedContactsRoute = nil
    rendererRevision = 0
  }

  init(state: ContactsSidebarState, blockedContactsRoute: NodesRoute?, rendererRevision: Int) {
    _ownedState = State(initialValue: state)
    suppliedState = state
    self.blockedContactsRoute = blockedContactsRoute
    self.rendererRevision = rendererRevision
  }

  private var state: ContactsSidebarState {
    suppliedState ?? ownedState
  }

  private var actions: ContactListActions {
    ContactListActions(
      viewModel: state.viewModel,
      appState: appState,
      syncSuccessTrigger: Binding(
        get: { state.syncSuccessTrigger },
        set: { state.syncSuccessTrigger = $0 }
      )
    )
  }

  var body: some View {
    let list = actions.filteredContacts(
      searchText: state.searchText,
      segment: state.selectedSegment,
      sortOrder: sortOrder
    )
    let content = ContactsSidebarContent(
      state: state,
      filteredContacts: list.contacts,
      timestampsByID: list.timestampsByID,
      searchPrompt: actions.searchPrompt,
      selectedContact: appState.navigation.selectedContact,
      sortOrder: $sortOrder,
      blockedContactsRoute: blockedContactsRoute,
      rendererRevision: rendererRevision,
      onSelect: selectContact
    )
    if suppliedState == nil {
      content.modifier(ContactsSidebarPresenter(state: state))
    } else {
      content
    }
  }

  private func selectContact(_ contact: ContactDTO) {
    appState.navigation.selectedContact = contact
    appState.navigation.nodesShowingDiscovery = false
  }
}

#Preview {
  NavigationStack {
    ContactsContentColumn(viewModel: ContactsViewModel())
  }
  .environment(\.appState, AppState())
}
