import SwiftUI
import MC1Services

/// The iPad sidebar's Nodes content column. It mirrors the regular-width (split) path of
/// `ContactsListView`, hosting `ContactsSidebarContent` with the same toolbar, searchable,
/// sheets, and handlers. The compact (stack) path stays solely in `ContactsListView`.
///
/// Selection is driven through `appState.navigation.selectedContact` rather than view-local
/// state so the detail column can read it. `ContactsSidebarContent`'s
/// `pendingContactDetail` bridge writes through that binding; its `initial: true` resolves a
/// deep link (e.g. a notification tap) the first time Nodes is entered, and this view is
/// instantiated whenever Nodes is selected.
struct ContactsContentColumn: View {
    @Environment(\.appState) private var appState

    @State private var viewModel = ContactsViewModel()
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
        @Bindable var navigation = appState.navigation

        ContactsSidebarContent(
            viewModel: viewModel,
            filteredContacts: actions.filteredContacts(searchText: searchText, segment: selectedSegment, sortOrder: sortOrder),
            isSearching: !searchText.isEmpty,
            searchPrompt: actions.searchPrompt,
            shouldUseSplitView: true,
            selectedSegment: $selectedSegment,
            selectedContact: $navigation.selectedContact,
            searchText: $searchText,
            sortOrder: $sortOrder,
            showDiscovery: $navigation.nodesShowingDiscovery,
            syncSuccessTrigger: $syncSuccessTrigger,
            showShareMyContact: $showShareMyContact,
            showAddContact: $showAddContact,
            showLocationDeniedAlert: $showLocationDeniedAlert,
            showOfflineRefreshAlert: $showOfflineRefreshAlert,
            // Compact-only navigation, unused on the split path which drives selection via selectedContact.
            navigationPath: .constant(NavigationPath())
        )
        .onChange(of: appState.navigation.selectedContact) { _, newContact in
            if newContact != nil {
                appState.navigation.nodesShowingDiscovery = false
            }
        }
    }
}

#Preview {
    NavigationStack {
        ContactsContentColumn()
    }
    .environment(\.appState, AppState())
}
