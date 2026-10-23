import MC1Services
import SwiftUI

@Observable
@MainActor
final class BlockedContactsState {
  let id = UUID()
  var contacts: [ContactDTO] = []
  var isLoading = false
  var errorMessage: String?
  var scrollPositionID: UUID?
  private(set) var rendererRevision = 0

  private struct LoadID: Equatable {
    let radioID: UUID?
    let servicesVersion: Int
    let contactsVersion: Int
  }

  @ObservationIgnored private var dataStoreProvider: @MainActor () -> DataStore? = { nil }
  @ObservationIgnored private var radioIDProvider: @MainActor () -> UUID? = { nil }
  @ObservationIgnored private var loadID: LoadID?
  @ObservationIgnored private var loadTask: Task<Void, Never>?
  @ObservationIgnored private var loadGeneration = 0
  @ObservationIgnored private var isEnded = false

  private var dataStore: DataStore? {
    dataStoreProvider()
  }

  private var radioID: UUID? {
    radioIDProvider()
  }

  func configure(appState: AppState) {
    guard !isEnded else { return }
    dataStoreProvider = { [appState] in appState.services?.dataStore }
    radioIDProvider = { [appState] in appState.connectedDevice?.radioID }
  }

  func reloadIfNeeded(appState: AppState) {
    guard !isEnded else { return }
    let nextID = LoadID(
      radioID: appState.connectedDevice?.radioID,
      servicesVersion: appState.servicesVersion,
      contactsVersion: appState.contactsVersion
    )
    guard loadID != nextID else { return }
    loadID = nextID
    loadGeneration += 1
    let generation = loadGeneration
    loadTask?.cancel()
    loadTask = Task { [weak self] in
      guard let self, !Task.isCancelled else { return }
      await load(generation: generation)
    }
  }

  private func load(generation: Int) async {
    guard !isEnded, let dataStore, let radioID else { return }
    isLoading = true
    defer {
      if generation == loadGeneration { isLoading = false }
    }
    do {
      let loaded = try await dataStore.fetchBlockedContacts(radioID: radioID)
      try Task.checkCancellation()
      guard !isEnded, generation == loadGeneration, self.radioID == radioID else { return }
      contacts = loaded
      if let scrollPositionID, !loaded.contains(where: { $0.id == scrollPositionID }) {
        self.scrollPositionID = loaded.first?.id
      }
    } catch is CancellationError {
      return
    } catch {
      guard !isEnded, generation == loadGeneration, self.radioID == radioID else { return }
      errorMessage = error.userFacingMessage
    }
  }

  func prepareRenderer(revision: Int) {
    rendererRevision = revision
  }

  func updateScrollPosition(_ id: UUID?, rendererRevision: Int) {
    guard !isEnded, self.rendererRevision == rendererRevision, let id else { return }
    scrollPositionID = id
  }

  func end() {
    isEnded = true
    loadGeneration += 1
    loadTask?.cancel()
    isLoading = false
  }
}
