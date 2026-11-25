import MC1Services
import SwiftUI

/// Arrival capsules, preview map, and hop list as sibling views: they stack in a
/// `VStack` and become separate rows in a `List` section.
struct MessagePathArrivalDetail: View {
  @Environment(\.appState) private var appState
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.mapColorSchemePreference) private var mapColorSchemePreference

  let sender: MessagePathSender
  let arrivals: [MessagePathArrival]
  @Binding var selectedID: UUID?
  let pathViewModel: MessagePathViewModel
  let previewWidth: CGFloat
  let onShowFullMap: () -> Void

  var body: some View {
    if arrivals.count > 1 {
      MessagePathArrivalCapsules(
        arrivals: arrivals,
        selectedID: $selectedID,
        reduceMotion: reduceMotion
      )
      .listRowSeparator(.hidden)
    }

    previewMap
      .listRowSeparator(.hidden)

    if let selectedArrival {
      MessagePathContent(
        sender: sender,
        arrival: selectedArrival,
        viewModel: pathViewModel,
        receiverName: appState.connectedDevice?.nodeName ?? L10n.Chats.Chats.Path.Receiver.you,
        userLocation: appState.bestAvailableLocation
      )
    }
  }

  private var selectedArrival: MessagePathArrival? {
    let selected = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    return arrivals.first { $0.id == selected } ?? arrivals.first
  }

  private var mapBasemapIsDark: Bool {
    resolvedMapIsDark(preference: mapColorSchemePreference, colorScheme: colorScheme)
  }

  private var isOffline: Bool {
    !appState.offlineMapService.isNetworkAvailable
  }

  private var previewMapModel: MessagePathMapView.CanvasModel? {
    MessagePathMapView.canvasModel(
      sender: sender,
      arrivals: arrivals,
      selectedID: selectedArrival?.id,
      pathViewModel: pathViewModel,
      connectedDevice: appState.connectedDevice,
      userLocation: appState.bestAvailableLocation
    )
  }

  private var previewKey: MessagePathPreviewSnapshot.Key? {
    guard let arrivalID = selectedArrival?.id else { return nil }
    return MessagePathPreviewSnapshot.key(
      arrivalID: arrivalID,
      isDark: mapBasemapIsDark,
      isOffline: isOffline,
      previewWidth: previewWidth
    )
  }

  @ViewBuilder
  private var previewMap: some View {
    if let model = previewMapModel, model.showsPathMap, let key = previewKey {
      if let image = pathViewModel.previewImage(for: key) {
        pathPreview(image: image, didFail: false, model: model, onRetry: {})
      } else if pathViewModel.previewFailed(for: key) {
        pathPreview(image: nil, didFail: true, model: model, onRetry: retrySelectedPreview)
      } else if let stale = pathViewModel.lastPreviewImage {
        pathPreview(image: stale, didFail: false, model: model, onRetry: {})
      }
    }
  }

  private func pathPreview(
    image: UIImage?,
    didFail: Bool,
    model: MessagePathMapView.CanvasModel,
    onRetry: @escaping () -> Void
  ) -> MessagePathPreviewMap {
    MessagePathPreviewMap(
      image: image,
      didFail: didFail,
      totalPathDistance: model.selectedCoordinates.totalDistance(),
      isDistanceIncomplete: model.isDistanceIncomplete,
      onExpand: onShowFullMap,
      onRetry: onRetry
    )
  }

  private func retrySelectedPreview() {
    guard let key = previewKey else { return }
    Task {
      await pathViewModel.retryPreview(
        arrivalID: key.arrivalID,
        sender: sender,
        arrivals: arrivals,
        connectedDevice: appState.connectedDevice,
        userLocation: appState.bestAvailableLocation,
        isDark: mapBasemapIsDark,
        isOffline: isOffline,
        previewWidth: previewWidth
      )
    }
  }
}
