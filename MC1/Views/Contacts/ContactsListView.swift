import SwiftUI
import MC1Services
import OSLog

private let nodesListLogger = Logger(subsystem: "com.mc1", category: "NodesListView")

/// List of all contacts discovered on the mesh network
struct ContactsListView: View {
    @Environment(\.appState) private var appState

    @State private var viewModel = ContactsViewModel()
    @State private var navigationPath = NavigationPath()
    @State private var searchText = ""
    @State private var selectedSegment: NodeSegment = .contacts
    @AppStorage(AppStorageKey.nodesSortOrder.rawValue) private var sortOrder: NodeSortOrder = .lastHeard
    @State private var showDiscovery = false
    @State private var syncSuccessTrigger = false
    @State private var showShareMyContact = false
    @State private var showAddContact = false
    @State private var showLocationDeniedAlert = false
    @State private var showOfflineRefreshAlert = false

    private var actions: ContactListActions {
        ContactListActions(viewModel: viewModel, appState: appState, syncSuccessTrigger: $syncSuccessTrigger)
    }

    var body: some View {
        NavigationStack(path: $navigationPath) {
            sidebarContent
                .navigationDestination(isPresented: $showDiscovery) {
                    DiscoveryView()
                }
        }
    }

    private var sidebarContent: some View {
        ContactsSidebarContent(
            viewModel: viewModel,
            filteredContacts: actions.filteredContacts(searchText: searchText, segment: selectedSegment, sortOrder: sortOrder),
            isSearching: !searchText.isEmpty,
            searchPrompt: actions.searchPrompt,
            shouldUseSplitView: false,
            selectedSegment: $selectedSegment,
            // Split-only: the compact stack navigates via navigationPath, so it has no selection.
            selectedContact: .constant(nil),
            searchText: $searchText,
            sortOrder: $sortOrder,
            showDiscovery: $showDiscovery,
            syncSuccessTrigger: $syncSuccessTrigger,
            showShareMyContact: $showShareMyContact,
            showAddContact: $showAddContact,
            showLocationDeniedAlert: $showLocationDeniedAlert,
            showOfflineRefreshAlert: $showOfflineRefreshAlert,
            navigationPath: $navigationPath,
            showErrorBinding: actions.showErrorBinding,
            onLoadContacts: actions.loadContacts,
            onSyncContacts: actions.syncContacts,
            onAnnounceOfflineStateIfNeeded: actions.announceOfflineStateIfNeeded
        )
    }
}

#Preview {
    ContactsListView()
        .environment(\.appState, AppState())
}
