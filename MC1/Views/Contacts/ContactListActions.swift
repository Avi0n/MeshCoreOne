import MC1Services
import SwiftUI

/// Computes actions from live Nodes list state so sync feedback reaches the retained sidebar.
@MainActor
struct ContactListActions {
  let viewModel: ContactsViewModel
  let appState: AppState
  let syncSuccessTrigger: Binding<Bool>

  /// Keeps row dates with their ordered contacts so lazy rendering cannot change the selected date.
  func filteredContacts(
    searchText: String,
    segment: NodeSegment,
    sortOrder: NodeSortOrder,
    referenceDate: Date = Date()
  ) -> (contacts: [ContactDTO], timestampsByID: [UUID: UInt32]) {
    let effectiveSortOrder = (sortOrder == .distance && appState.bestAvailableLocation == nil)
      ? .lastHeard
      : sortOrder

    let contacts = viewModel.filteredContacts(
      searchText: searchText,
      segment: segment,
      sortOrder: effectiveSortOrder,
      userLocation: appState.bestAvailableLocation,
      referenceDate: referenceDate
    )
    let timestampsByID = contacts.reduce(into: [UUID: UInt32]()) { timestamps, contact in
      timestamps[contact.id] = contact.nodeListTimestamp(relativeTo: referenceDate)
    }
    return (contacts, timestampsByID)
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
      dataStore: { [appState] in appState.offlineDataStore },
      contactService: { [appState] in appState.services?.contactService },
      advertisementService: { [appState] in appState.services?.advertisementService }
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
