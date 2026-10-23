import MC1Services
import SwiftUI

@Observable
@MainActor
final class DiscoveryState {
  let id = UUID()
  let viewModel = DiscoveryViewModel()
  var searchText = "" {
    didSet {
      guard !isEnded else { return }
      if oldValue.isEmpty, !searchText.isEmpty {
        AccessibilityNotification.Announcement(L10n.Contacts.Contacts.Discovery.searchingAllTypes).post()
      }
      refreshVisibleNodes()
    }
  }

  var selectedSegment: DiscoverSegment = .all {
    didSet { refreshVisibleNodes() }
  }

  var scrollPositionID: UUID?
  var isSearchFocused = false
  var addingNodeID: UUID?
  var showClearConfirmation = false
  private(set) var rendererRevision = 0

  private struct LoadID: Equatable {
    let radioID: UUID?
    let servicesVersion: Int
    let contactsVersion: Int
  }

  @ObservationIgnored private var appState: AppState?
  @ObservationIgnored private var sortOrder: NodeSortOrder = .lastHeard
  @ObservationIgnored private var loadID: LoadID?
  @ObservationIgnored private var initialLoadTask: Task<Void, Never>?
  @ObservationIgnored private var addTask: Task<Void, Never>?
  @ObservationIgnored private var clearTask: Task<Void, Never>?
  @ObservationIgnored private var deleteTasks: [UUID: Task<Void, Never>] = [:]
  @ObservationIgnored private var isEnded = false

  var consumesLocation: Bool {
    sortOrder == .distance || sortOrder == .hops
  }

  func configure(appState: AppState) {
    guard !isEnded else { return }
    self.appState = appState
    viewModel.configure(
      dataStore: { [appState] in appState.offlineDataStore },
      radioID: { [appState] in appState.connectedDevice?.radioID }
    )
  }

  func reloadIfNeeded(appState: AppState) {
    guard !isEnded else { return }
    let nextID = LoadID(
      radioID: appState.connectedDevice?.radioID,
      servicesVersion: appState.servicesVersion,
      contactsVersion: appState.contactsVersion
    )
    guard loadID != nextID else { return }
    let isInitial = loadID == nil
    let contextChanged = loadID?.radioID != nextID.radioID
      || loadID?.servicesVersion != nextID.servicesVersion
    loadID = nextID
    if isInitial {
      initialLoadTask = Task { [weak self] in
        guard let self else { return }
        await viewModel.loadDiscoveredNodes()
      }
    } else {
      initialLoadTask?.cancel()
      if contextChanged { viewModel.cancelReloads() }
      viewModel.scheduleCoalescedReload()
    }
  }

  func updateSortOrder(_ sortOrder: NodeSortOrder) {
    self.sortOrder = sortOrder
    refreshVisibleNodes()
  }

  func refreshVisibleNodes() {
    guard !isEnded, let appState else { return }
    let effectiveSortOrder = sortOrder == .distance && appState.bestAvailableLocation == nil
      ? .lastHeard : sortOrder
    viewModel.updateVisibleNodes(
      searchText: searchText,
      segment: selectedSegment,
      sortOrder: effectiveSortOrder,
      userLocation: appState.bestAvailableLocation
    )
  }

  func add(_ node: DiscoveredNodeDTO) {
    guard !isEnded, addingNodeID == nil,
          let appState, let contactService = appState.services?.contactService else { return }
    addingNodeID = node.id
    addTask = Task { [weak self] in
      guard let self else { return }
      defer {
        if !isEnded { addingNodeID = nil }
      }
      do {
        try await contactService.addOrUpdateContact(radioID: node.radioID, contact: node.makeContactFrame())
        guard isCurrent(radioID: node.radioID) else { return }
        await viewModel.loadDiscoveredNodes()
      } catch ContactServiceError.contactTableFull {
        guard isCurrent(radioID: node.radioID) else { return }
        if let maxContacts = appState.connectedDevice?.maxContacts {
          viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts))
        } else {
          viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFullSimple
        }
      } catch {
        guard isCurrent(radioID: node.radioID) else { return }
        viewModel.errorMessage = error.userFacingMessage
      }
    }
  }

  func delete(_ node: DiscoveredNodeDTO) {
    guard !isEnded, deleteTasks[node.id] == nil else { return }
    deleteTasks[node.id] = Task { [weak self] in
      guard let self else { return }
      await viewModel.deleteDiscoveredNode(node)
      deleteTasks[node.id] = nil
    }
  }

  func clearAll() {
    guard !isEnded, clearTask == nil else { return }
    let radioID = appState?.connectedDevice?.radioID
    clearTask = Task { [weak self] in
      guard let self else { return }
      defer { clearTask = nil }
      await viewModel.clearAllDiscoveredNodes()
      guard !Task.isCancelled, !isEnded, appState?.connectedDevice?.radioID == radioID else { return }
      AccessibilityNotification.Announcement(L10n.Contacts.Contacts.Discovery.clearedAllNodes).post()
    }
  }

  private func isCurrent(radioID: UUID) -> Bool {
    !Task.isCancelled && !isEnded && appState?.connectedDevice?.radioID == radioID
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

  func end() {
    isEnded = true
    initialLoadTask?.cancel()
    addTask?.cancel()
    clearTask?.cancel()
    for task in deleteTasks.values {
      task.cancel()
    }
    deleteTasks.removeAll()
    viewModel.cancelReloads()
  }
}
