import MC1Services
import SwiftUI

@Observable
@MainActor
final class TelemetryHistoryOverviewState {
  enum ScrollID: Hashable {
    case timeRange, radio, sensors, location, neighbors, retention
    case report(UUID)
  }

  nonisolated static let scrollCoordinateSpace = "telemetry-history-list"

  let history: ContactRoute.TelemetryHistory
  let viewModel = TelemetryHistoryOverviewViewModel()
  var radioExpanded = true
  var sensorsExpanded: Bool
  var neighborsExpanded = false
  var showsLocationMap = false
  var selectedReportID: UUID?
  var visibleScrollID: ScrollID = .timeRange
  private(set) var hasLoaded = false

  @ObservationIgnored private var dataStoreProvider: (@MainActor () -> PersistenceStore?)?
  @ObservationIgnored private var loadTask: Task<Void, Never>?
  @ObservationIgnored private var hasEnded = false
  @ObservationIgnored private var rendererID: UUID?
  @ObservationIgnored private(set) var rendererRevision = 0
  @ObservationIgnored private var mountedRenderers: [UUID] = []

  init(history: ContactRoute.TelemetryHistory) {
    self.history = history
    sensorsExpanded = !history.showNeighbors
  }

  func configure(appState: AppState) {
    dataStoreProvider = { appState.offlineDataStore }
    guard let store = dataStoreProvider?() else { return }
    startLoading { [viewModel, history] in
      await viewModel.loadData(
        dataStore: store, publicKey: history.publicKey, radioID: history.radioID
      )
    }
  }

  func startLoading(_ load: @escaping @MainActor () async -> Void) {
    guard !hasEnded, !hasLoaded, loadTask == nil else { return }
    loadTask = Task { [weak self] in
      guard let self, !Task.isCancelled else { return }
      await load()
      guard !Task.isCancelled, !hasEnded else { return }
      hasLoaded = true
      loadTask = nil
    }
  }

  func cancelLoading() {
    loadTask?.cancel()
    loadTask = nil
    viewModel.cancelLoad()
  }

  func prepareRenderer(revision: Int) {
    guard !hasEnded, rendererRevision != revision else { return }
    rendererRevision = revision
    invalidateRenderer()
  }

  func prepareRenderer(_ id: UUID, revision: Int = 0) {
    guard !hasEnded, rendererRevision == revision else { return }
    mountedRenderers.removeAll { $0 == id }
    mountedRenderers.append(id)
    rendererID = id
  }

  func invalidateRenderer() {
    mountedRenderers = []
    rendererID = nil
  }

  func invalidateRenderer(_ id: UUID, revision: Int = 0) {
    guard rendererRevision == revision else { return }
    mountedRenderers.removeAll { $0 == id }
    if rendererID == id { rendererID = mountedRenderers.last }
  }

  func recordVisiblePosition(_ position: ScrollID, renderer id: UUID) {
    guard !hasEnded, rendererID == id else { return }
    visibleScrollID = position
  }

  func restoredPosition(in snapshots: [NodeStatusSnapshotDTO]) -> ScrollID {
    switch visibleScrollID {
    case let .report(id):
      let reports = snapshots.filter { $0.validCoordinate != nil }
      if reports.contains(where: { $0.id == id }) { return visibleScrollID }
      return reports.last.map { .report($0.id) } ?? .location
    case .radio where !viewModel.hasRadioData(in: snapshots):
      return .timeRange
    case .neighbors where !history.showNeighbors:
      return .timeRange
    default:
      return visibleScrollID
    }
  }

  func end() {
    hasEnded = true
    invalidateRenderer()
    cancelLoading()
  }
}
