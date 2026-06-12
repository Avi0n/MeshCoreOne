import SwiftUI
import MC1Services

/// Layout-independent Nodes-list derived state and actions shared by the compact `ContactsListView`
/// (stack) and the iPad `ContactsContentColumn` (split). Both compute the same filtered list and run
/// the same load/sync sequences; only selection plumbing differs, so that stays in each view. Built
/// fresh per body evaluation; `syncSuccessTrigger` points at each view's own `@State`.
@MainActor
struct ContactListActions {
    let viewModel: ContactsViewModel
    let appState: AppState
    let syncSuccessTrigger: Binding<Bool>

    /// Filters and sorts contacts, falling back to lastHeard sort when distance is selected but no
    /// location is available.
    func filteredContacts(searchText: String, segment: NodeSegment, sortOrder: NodeSortOrder) -> [ContactDTO] {
        let effectiveSortOrder = (sortOrder == .distance && appState.bestAvailableLocation == nil)
            ? .lastHeard
            : sortOrder

        return viewModel.filteredContacts(
            searchText: searchText,
            segment: segment,
            sortOrder: effectiveSortOrder,
            userLocation: appState.bestAvailableLocation
        )
    }

    var searchPrompt: String {
        let count = viewModel.contacts.count
        return count > 0
            ? L10n.Contacts.Contacts.List.searchPromptWithCount(count)
            : L10n.Contacts.Contacts.List.searchPrompt
    }

    func loadContacts() async {
        guard let deviceID = appState.currentRadioID else { return }
        viewModel.configure(
            dataStore: appState.offlineDataStore,
            contactService: appState.services?.contactService,
            advertisementService: appState.services?.advertisementService
        )
        await viewModel.loadContacts(radioID: deviceID)
    }

    func syncContacts() async {
        guard let deviceID = appState.currentRadioID else { return }
        await viewModel.syncContacts(radioID: deviceID)
        syncSuccessTrigger.wrappedValue.toggle()
    }

    func announceOfflineStateIfNeeded() {
        guard appState.connectionState == .disconnected,
              appState.currentRadioID != nil else { return }

        AccessibilityNotification.Announcement(L10n.Contacts.Contacts.List.offlineAnnouncement).post()
    }
}
