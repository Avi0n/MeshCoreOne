import MC1Services
import SwiftUI

@Observable
@MainActor
final class ContactsSidebarState {
  let viewModel: ContactsViewModel
  var searchText = ""
  var selectedSegment: NodeSegment = .contacts
  var scrollPositionID: UUID?
  var isSearchFocused = false
  var syncSuccessTrigger = false
  var showShareMyContact = false
  var showAddContact = false
  var showLocationDeniedAlert = false
  var showOfflineRefreshAlert = false
  private(set) var rendererRevision = 0

  private struct LoadID: Equatable {
    let radioID: UUID?
    let servicesVersion: Int
    let contactsVersion: Int
  }

  @ObservationIgnored private var loadID: LoadID?
  @ObservationIgnored private var loadTask: Task<Void, Never>?
  @ObservationIgnored private var syncTask: Task<Void, Never>?
  @ObservationIgnored private var hasConfigured = false
  @ObservationIgnored private var hasAppliedInitialFavorites = false
  @ObservationIgnored private var isEnded = false

  init(viewModel: ContactsViewModel = ContactsViewModel()) {
    self.viewModel = viewModel
  }

  func configure(appState: AppState) {
    guard !isEnded else { return }
    viewModel.configure(
      dataStore: { [appState] in appState.offlineDataStore },
      contactService: { [appState] in appState.services?.contactService },
      advertisementService: { [appState] in appState.services?.advertisementService }
    )
    guard !hasConfigured else { return }
    hasConfigured = true
    actions(appState: appState, expectedRadioID: appState.currentRadioID).announceOfflineStateIfNeeded()
    if appState.locationService.isAuthorized {
      appState.locationService.requestLocation()
    }
    admitSelection(appState: appState)
  }

  func reloadIfNeeded(appState: AppState) {
    guard !isEnded else { return }
    let nextID = LoadID(
      radioID: appState.currentRadioID,
      servicesVersion: appState.servicesVersion,
      contactsVersion: appState.contactsVersion
    )
    guard loadID != nextID else { return }
    loadID = nextID
    loadTask?.cancel()
    loadTask = Task { [weak self] in
      guard let self, !Task.isCancelled else { return }
      await actions(appState: appState, expectedRadioID: nextID.radioID).loadContacts()
    }
  }

  func synchronize(appState: AppState) async {
    guard !isEnded else { return }
    guard appState.connectionState == .ready else {
      showOfflineRefreshAlert = true
      return
    }
    if let syncTask {
      await syncTask.value
      return
    }
    let radioID = appState.currentRadioID
    let task = Task { [weak self] in
      guard let self, !Task.isCancelled, !isEnded,
            let radioID, appState.currentRadioID == radioID else { return }
      await actions(appState: appState, expectedRadioID: radioID).syncContacts()
    }
    syncTask = task
    await task.value
    syncTask = nil
  }

  func admitSelection(appState: AppState) {
    guard let contact = appState.navigation.selectedContact else { return }
    appState.navigation.nodesShowingDiscovery = false
    viewModel.admitIfAbsent(contact)
  }

  func clearRemovedSelection(appState: AppState) {
    if let selected = appState.navigation.selectedContact,
       viewModel.pendingRemovalIDs.contains(selected.id) {
      appState.navigation.clearSelectedContact(matching: selected.id)
    }
  }

  func firstLoadCompleted() {
    guard viewModel.hasLoadedOnce, !hasAppliedInitialFavorites else { return }
    hasAppliedInitialFavorites = true
    if selectedSegment == .contacts, viewModel.hasFavorites {
      selectedSegment = .favorites
    }
  }

  func updateLocation(for sortOrder: NodeSortOrder, appState: AppState) {
    guard sortOrder == .distance else { return }
    if appState.locationService.isAuthorized {
      appState.locationService.requestLocation()
    } else if appState.locationService.isLocationDenied {
      showLocationDeniedAlert = true
    } else {
      appState.locationService.requestPermissionIfNeeded()
    }
  }

  func prepareRenderer(revision: Int) {
    rendererRevision = revision
  }

  func updateSearchFocus(_ focused: Bool, rendererRevision: Int) {
    guard !isEnded, self.rendererRevision == rendererRevision else { return }
    isSearchFocused = focused
  }

  func updateSearchText(_ text: String, rendererRevision: Int) {
    guard !isEnded, self.rendererRevision == rendererRevision else { return }
    searchText = text
  }

  func updateScrollPosition(_ id: UUID?, rendererRevision: Int) {
    guard !isEnded, self.rendererRevision == rendererRevision, let id else { return }
    scrollPositionID = id
  }

  func clearScrollPosition(rendererRevision: Int) {
    guard !isEnded, self.rendererRevision == rendererRevision else { return }
    scrollPositionID = nil
  }

  func end() {
    isEnded = true
    loadTask?.cancel()
    syncTask?.cancel()
  }

  #if DEBUG
    func waitForLoadForTesting() async {
      await loadTask?.value
    }
  #endif

  private func actions(appState: AppState, expectedRadioID: UUID?) -> ContactListActions {
    ContactListActions(
      viewModel: viewModel,
      appState: appState,
      syncSuccessTrigger: Binding(
        get: { self.syncSuccessTrigger },
        set: {
          guard !Task.isCancelled, !self.isEnded, appState.currentRadioID == expectedRadioID else { return }
          self.syncSuccessTrigger = $0
        }
      )
    )
  }
}
