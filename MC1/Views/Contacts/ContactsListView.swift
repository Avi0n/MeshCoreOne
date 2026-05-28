import SwiftUI
import MC1Services
import OSLog

private let nodesListLogger = Logger(subsystem: "com.mc1", category: "NodesListView")

/// List of all contacts discovered on the mesh network
struct ContactsListView: View {
    @Environment(\.appState) private var appState
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var viewModel = ContactsViewModel()
    @State private var navigationPath = NavigationPath()
    @State private var selectedContact: ContactDTO?
    @State private var searchText = ""
    @State private var selectedSegment: NodeSegment = .contacts
    @AppStorage("nodesSortOrder") private var sortOrder: NodeSortOrder = .lastHeard
    @State private var showDiscovery = false
    @State private var syncSuccessTrigger = false
    @State private var showShareMyContact = false
    @State private var showAddContact = false
    @State private var showLocationDeniedAlert = false
    @State private var showOfflineRefreshAlert = false

    private var filteredContacts: [ContactDTO] {
        // Fall back to lastHeard sort when distance is selected but location unavailable
        let effectiveSortOrder = (sortOrder == .distance && appState.bestAvailableLocation == nil)
            ? .lastHeard
            : sortOrder

        return viewModel.filteredContacts(
            searchText: searchText,
            segment: selectedSegment,
            sortOrder: effectiveSortOrder,
            userLocation: appState.bestAvailableLocation
        )
    }

    private var isSearching: Bool {
        !searchText.isEmpty
    }

    private var searchPrompt: String {
        let count = viewModel.contacts.count
        if count > 0 {
            return L10n.Contacts.Contacts.List.searchPromptWithCount(count)
        }
        return L10n.Contacts.Contacts.List.searchPrompt
    }

    private var shouldUseSplitView: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        if shouldUseSplitView {
            NavigationSplitView {
                NavigationStack {
                    sidebarContent
                }
            } detail: {
                NavigationStack {
                    if showDiscovery {
                        DiscoveryView()
                    } else if let selectedContact {
                        ContactDetailView(contact: selectedContact)
                            .id(selectedContact.id)
                    } else {
                        ContentUnavailableView(L10n.Contacts.Contacts.List.selectNode, systemImage: "flipphone")
                    }
                }
            }
            .onChange(of: selectedContact) { _, newContact in
                if newContact != nil {
                    showDiscovery = false
                }
            }
        } else {
            NavigationStack(path: $navigationPath) {
                sidebarContent
                    .navigationDestination(isPresented: $showDiscovery) {
                        DiscoveryView()
                    }
                    .navigationDestination(for: ContactDTO.self) { contact in
                        ContactDetailView(contact: contact)
                            .id(contact.id)
                    }
            }
        }
    }

    private var sidebarContent: some View {
        ContactsSidebarContent(
            viewModel: viewModel,
            filteredContacts: filteredContacts,
            isSearching: isSearching,
            searchPrompt: searchPrompt,
            shouldUseSplitView: shouldUseSplitView,
            selectedSegment: $selectedSegment,
            selectedContact: $selectedContact,
            searchText: $searchText,
            sortOrder: $sortOrder,
            showDiscovery: $showDiscovery,
            syncSuccessTrigger: $syncSuccessTrigger,
            showShareMyContact: $showShareMyContact,
            showAddContact: $showAddContact,
            showLocationDeniedAlert: $showLocationDeniedAlert,
            showOfflineRefreshAlert: $showOfflineRefreshAlert,
            navigationPath: $navigationPath,
            showErrorBinding: showErrorBinding,
            onLoadContacts: loadContacts,
            onSyncContacts: syncContacts,
            onAnnounceOfflineStateIfNeeded: announceOfflineStateIfNeeded
        )
    }

    private var showErrorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.errorMessage = nil } }
        )
    }

    // MARK: - Actions

    private func loadContacts() async {
        guard let deviceID = appState.currentRadioID else { return }
        viewModel.configure(appState: appState)
        await viewModel.loadContacts(radioID: deviceID)
    }

    private func announceOfflineStateIfNeeded() {
        guard appState.connectionState == .disconnected,
              appState.currentRadioID != nil else { return }

        AccessibilityNotification.Announcement(L10n.Contacts.Contacts.List.offlineAnnouncement).post()
    }

    private func syncContacts() async {
        guard let deviceID = appState.currentRadioID else { return }
        await viewModel.syncContacts(radioID: deviceID)
        syncSuccessTrigger.toggle()
    }
}

#Preview {
    ContactsListView()
        .environment(\.appState, AppState())
}
