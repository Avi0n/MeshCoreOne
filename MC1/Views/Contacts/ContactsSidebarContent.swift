import SwiftUI
import MC1Services
import OSLog

private let sidebarLogger = Logger(subsystem: "com.mc1", category: "NodesListView")

struct ContactsSidebarContent: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    let viewModel: ContactsViewModel
    let filteredContacts: [ContactDTO]
    let isSearching: Bool
    let searchPrompt: String
    let shouldUseSplitView: Bool

    @Binding var selectedSegment: NodeSegment
    @Binding var selectedContact: ContactDTO?
    @Binding var searchText: String
    @Binding var sortOrder: NodeSortOrder
    @Binding var showDiscovery: Bool
    @Binding var syncSuccessTrigger: Bool
    @Binding var showShareMyContact: Bool
    @Binding var showAddContact: Bool
    @Binding var showLocationDeniedAlert: Bool
    @Binding var showOfflineRefreshAlert: Bool
    @Binding var navigationPath: NavigationPath

    let showErrorBinding: Binding<Bool>

    let onLoadContacts: () async -> Void
    let onSyncContacts: () async -> Void
    let onAnnounceOfflineStateIfNeeded: () -> Void

    var body: some View {
        Group {
            if !viewModel.hasLoadedOnce {
                List {
                    PinnedFilterHeader {
                        NodeSegmentPicker(selection: $selectedSegment, isSearching: isSearching)
                    }
                }
                .listStyle(.plain)
                .themedCanvas(theme)
                .overlay {
                    ProgressView()
                }
            } else if shouldUseSplitView {
                ContactsSplitList(
                    selectedSegment: $selectedSegment,
                    selectedContact: $selectedContact,
                    isSearching: isSearching,
                    searchText: searchText,
                    filteredContacts: filteredContacts,
                    viewModel: viewModel
                )
            } else {
                ContactsCompactList(
                    selectedSegment: $selectedSegment,
                    isSearching: isSearching,
                    searchText: searchText,
                    filteredContacts: filteredContacts,
                    viewModel: viewModel
                )
            }
        }
        .navigationTitle(L10n.Contacts.Contacts.List.title)
        .searchable(text: $searchText, prompt: searchPrompt)
        .toolbar {
            bleStatusToolbarItem(isVisible: !shouldUseSplitView || appState.navigation.isSidebarCollapsed)

            ToolbarItem(placement: .automatic) {
                Menu {
                    ForEach(NodeSortOrder.allCases, id: \.self) { order in
                        Button {
                            sortOrder = order
                        } label: {
                            if sortOrder == order {
                                Label(order.localizedTitle, systemImage: "checkmark")
                            } else {
                                Text(order.localizedTitle)
                            }
                        }
                    }
                } label: {
                    Label(L10n.Contacts.Contacts.List.sort, systemImage: "arrow.up.arrow.down")
                }
            }

            ToolbarItem(placement: .automatic) {
                Menu {
                    NavigationLink {
                        BlockedContactsView()
                    } label: {
                        Label(L10n.Contacts.Contacts.List.blockedContacts, systemImage: "hand.raised.fill")
                    }

                    Divider()

                    Button {
                        showShareMyContact = true
                    } label: {
                        Label(L10n.Contacts.Contacts.List.shareMyContact, systemImage: "square.and.arrow.up")
                    }

                    Button {
                        showAddContact = true
                    } label: {
                        Label(L10n.Contacts.Contacts.List.addContact, systemImage: "plus")
                    }

                    Divider()

                    Button {
                        if shouldUseSplitView {
                            selectedContact = nil
                        }
                        showDiscovery = true
                    } label: {
                        Label(L10n.Contacts.Contacts.List.discover, systemImage: "antenna.radiowaves.left.and.right")
                    }

                    Divider()

                    Button {
                        Task {
                            if appState.connectionState != .ready {
                                showOfflineRefreshAlert = true
                            } else {
                                await onSyncContacts()
                            }
                        }
                    } label: {
                        Label(L10n.Contacts.Contacts.List.syncNodes, systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(viewModel.isSyncing)
                } label: {
                    Label(L10n.Contacts.Contacts.List.options, systemImage: "ellipsis.circle")
                }
            }
        }
        .refreshable {
            if appState.connectionState != .ready {
                showOfflineRefreshAlert = true
            } else {
                await onSyncContacts()
            }
        }
        .alert(L10n.Contacts.Contacts.List.cannotRefresh, isPresented: $showOfflineRefreshAlert) {
            Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) { }
        } message: {
            Text(L10n.Contacts.Contacts.List.connectToSync)
        }
        .sensoryFeedback(.success, trigger: syncSuccessTrigger)
        .task {
            sidebarLogger.info("NodesListView: task started, services=\(appState.services != nil)")
            viewModel.configure(appState: appState)
            await onLoadContacts()
            sidebarLogger.info("NodesListView: loaded, contacts=\(viewModel.contacts.count)")
            onAnnounceOfflineStateIfNeeded()

            // Request location for distance display (only if already authorized)
            if appState.locationService.isAuthorized {
                appState.locationService.requestLocation()
            }
        }
        .task(id: sortOrder) {
            if sortOrder == .distance {
                if appState.locationService.isAuthorized {
                    appState.locationService.requestLocation()
                } else if appState.locationService.isLocationDenied {
                    showLocationDeniedAlert = true
                } else {
                    appState.locationService.requestPermissionIfNeeded()
                }
            }
        }
        .onChange(of: appState.servicesVersion) { _, _ in
            Task {
                await onLoadContacts()
            }
        }
        .onChange(of: appState.contactsVersion) { _, _ in
            Task {
                await onLoadContacts()
            }
        }
        .onChange(of: appState.navigation.pendingDiscoveryNavigation, initial: true) { _, shouldNavigate in
            if shouldNavigate {
                showDiscovery = true
                appState.navigation.clearPendingDiscoveryNavigation()
            }
        }
        .onChange(of: appState.navigation.pendingContactDetail, initial: true) { _, contact in
            guard let contact else { return }

            if shouldUseSplitView {
                selectedContact = contact
            } else {
                navigationPath.removeLast(navigationPath.count)
                navigationPath.append(contact)
            }

            appState.navigation.clearPendingContactDetailNavigation()
        }
        .onChange(of: appState.locationService.authorizationStatus) { _, status in
            if sortOrder == .distance {
                switch status {
                case .authorizedWhenInUse, .authorizedAlways:
                    appState.locationService.requestLocation()
                case .denied, .restricted:
                    showLocationDeniedAlert = true
                default:
                    break
                }
            }
        }
        .sheet(isPresented: $showShareMyContact) {
            if let device = appState.connectedDevice {
                ContactQRShareSheet(
                    contactName: device.nodeName,
                    publicKey: device.publicKey,
                    contactType: .chat
                )
            }
        }
        .sheet(isPresented: $showAddContact) {
            AddContactSheet()
        }
        .alert(L10n.Contacts.Contacts.List.locationUnavailable, isPresented: $showLocationDeniedAlert) {
            Button(L10n.Contacts.Contacts.List.openSettings) {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) { }
        } message: {
            Text(L10n.Contacts.Contacts.List.distanceRequiresLocation)
        }
        .alert(L10n.Contacts.Contacts.Common.error, isPresented: showErrorBinding) {
            Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) {
                viewModel.errorMessage = nil
            }
        } message: {
            Text(viewModel.errorMessage ?? L10n.Contacts.Contacts.Common.errorOccurred)
        }
    }
}
