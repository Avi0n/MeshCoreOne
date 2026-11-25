import MC1Services
import SwiftUI

struct MessagePathPreviewRow: View {
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.mapColorSchemePreference) private var mapColorSchemePreference

  let sender: MessagePathSender
  let arrival: MessagePathArrival?
  let pathViewModel: MessagePathViewModel
  let previewWidth: CGFloat
  let onShowFullMap: () -> Void

  @State private var retryRequest: RetryRequest?

  var body: some View {
    if !pathViewModel.isLoading,
       let arrival,
       let model = previewMapModel(for: arrival),
       model.showsPathMap {
      let key = previewContext.key(for: arrival.id)
      let image = pathViewModel.previewImage(for: key)
      let didFail = image == nil && pathViewModel.previewFailed(for: key)
      MessagePathPreviewMap(
        image: image,
        didFail: didFail,
        totalPathDistance: model.selectedCoordinates.totalDistance(),
        isDistanceIncomplete: model.isDistanceIncomplete,
        onExpand: onShowFullMap,
        onRetry: { retryRequest = RetryRequest(id: UUID(), arrivalID: arrival.id) }
      )
      .task(id: RetryTaskID(selectedArrivalID: arrival.id, request: retryRequest)) {
        guard let request = retryRequest, request.arrivalID == arrival.id else { return }
        defer {
          if retryRequest == request {
            retryRequest = nil
          }
        }
        await pathViewModel.retryPreview(for: arrival, context: previewContext)
      }
    }
  }

  private var previewContext: MessagePathPreviewSnapshot.Context {
    MessagePathPreviewSnapshot.Context(
      sender: sender,
      connectedDevice: appState.connectedDevice,
      userLocation: appState.bestAvailableLocation,
      isDark: resolvedMapIsDark(preference: mapColorSchemePreference, colorScheme: colorScheme),
      isOffline: !appState.offlineMapService.isNetworkAvailable,
      previewWidth: previewWidth
    )
  }

  private func previewMapModel(for arrival: MessagePathArrival) -> MessagePathMapView.CanvasModel? {
    MessagePathMapView.canvasModel(
      sender: sender,
      arrival: arrival,
      pathViewModel: pathViewModel,
      connectedDevice: appState.connectedDevice,
      userLocation: appState.bestAvailableLocation
    )
  }

  private struct RetryRequest: Equatable {
    let id: UUID
    let arrivalID: UUID
  }

  private struct RetryTaskID: Equatable {
    let selectedArrivalID: UUID
    let request: RetryRequest?
  }
}
