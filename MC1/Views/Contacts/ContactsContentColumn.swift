import MC1Services
import SwiftUI

/// Nodes list column. Search, filter, and sheet state live here so they survive
/// column collapse. Selection is `selectedContact` / `nodesShowingDiscovery`.
struct ContactsContentColumn: View {
  @Environment(\.appState) private var appState

  let viewModel: ContactsViewModel

  @State private var searchText = ""
  @State private var selectedSegment: NodeSegment = .contacts
  @AppStorage(AppStorageKey.nodesSortOrder.rawValue) private var sortOrder: NodeSortOrder = .lastHeard
  @State private var syncSuccessTrigger = false
  @State private var showShareMyContact = false
  @State private var showAddContact = false
  @State private var showLocationDeniedAlert = false
  @State private var showOfflineRefreshAlert = false

  private var actions: ContactListActions {
    ContactListActions(viewModel: viewModel, appState: appState, syncSuccessTrigger: $syncSuccessTrigger)
  }

  var body: some View {
    ContactsSidebarContent(
      viewModel: viewModel,
      filteredContacts: actions.filteredContacts(searchText: searchText, segment: selectedSegment, sortOrder: sortOrder),
      isSearching: !searchText.isEmpty,
      searchPrompt: actions.searchPrompt,
      selectedSegment: $selectedSegment,
      selectedContact: appState.navigation.selectedContact,
      searchText: $searchText,
      sortOrder: $sortOrder,
      syncSuccessTrigger: $syncSuccessTrigger,
      showShareMyContact: $showShareMyContact,
      showAddContact: $showAddContact,
      showLocationDeniedAlert: $showLocationDeniedAlert,
      showOfflineRefreshAlert: $showOfflineRefreshAlert,
      onSelect: selectContact,
      onLoadContacts: actions.loadContacts,
      onSyncContacts: actions.syncContacts,
      onAnnounceOfflineStateIfNeeded: actions.announceOfflineStateIfNeeded
    )
    .onChange(of: appState.navigation.selectedContact) { _, newContact in
      if newContact != nil {
        appState.navigation.nodesShowingDiscovery = false
      }
    }
    .onChange(of: viewModel.pendingRemovalIDs) { _, ids in
      if let selected = appState.navigation.selectedContact, ids.contains(selected.id) {
        appState.navigation.clearSelectedContact(matching: selected.id)
      }
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
