import MC1Services
import SwiftUI

/// Renders path map previews and presents the full path map. Attach to a container
/// that outlives the preview row, so the tasks and the sheet are not torn down with it.
struct MessagePathMapPreviews: ViewModifier {
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.mapColorSchemePreference) private var mapColorSchemePreference

  let sender: MessagePathSender
  let arrivals: [MessagePathArrival]
  @Binding var selectedID: UUID?
  @Binding var showFullMap: Bool
  let pathViewModel: MessagePathViewModel
  let previewWidth: CGFloat

  func body(content: Content) -> some View {
    content
      // Keyed without the arrival list: hearing another arrival must not cancel this render.
      .task(id: renderID(arrivalIDs: [])) {
        guard let selected = resolvedSelectedID else { return }
        await pathViewModel.renderSelectedPreview(
          arrivalID: selected,
          sender: sender,
          arrivals: arrivals,
          connectedDevice: appState.connectedDevice,
          userLocation: appState.bestAvailableLocation,
          isDark: mapBasemapIsDark,
          isOffline: isOffline,
          previewWidth: previewWidth
        )
      }
      .task(id: renderID(arrivalIDs: arrivals.map(\.id))) {
        await pathViewModel.prefetchPreviews(
          sender: sender,
          arrivals: arrivals,
          skipping: resolvedSelectedID,
          connectedDevice: appState.connectedDevice,
          userLocation: appState.bestAvailableLocation,
          isDark: mapBasemapIsDark,
          isOffline: isOffline,
          previewWidth: previewWidth
        )
      }
      .sheet(isPresented: $showFullMap) {
        MessagePathMapView(
          sender: sender,
          arrivals: arrivals,
          selectedID: $selectedID,
          pathViewModel: pathViewModel
        )
      }
  }

  private var resolvedSelectedID: UUID? {
    MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
  }

  private var mapBasemapIsDark: Bool {
    resolvedMapIsDark(preference: mapColorSchemePreference, colorScheme: colorScheme)
  }

  private var isOffline: Bool {
    !appState.offlineMapService.isNetworkAvailable
  }

  private func renderID(arrivalIDs: [UUID]) -> RenderID {
    RenderID(
      selectedID: resolvedSelectedID,
      arrivalIDs: arrivalIDs,
      isLoading: pathViewModel.isLoading,
      isDark: mapBasemapIsDark,
      isOffline: isOffline,
      widthBucket: MessagePathPreviewSnapshot.bucketedWidth(previewWidth)
    )
  }

  private struct RenderID: Hashable {
    let selectedID: UUID?
    let arrivalIDs: [UUID]
    let isLoading: Bool
    let isDark: Bool
    let isOffline: Bool
    let widthBucket: Int
  }
}

extension View {
  func messagePathMapPreviews(
    sender: MessagePathSender,
    arrivals: [MessagePathArrival],
    selectedID: Binding<UUID?>,
    showFullMap: Binding<Bool>,
    pathViewModel: MessagePathViewModel,
    previewWidth: CGFloat
  ) -> some View {
    modifier(MessagePathMapPreviews(
      sender: sender,
      arrivals: arrivals,
      selectedID: selectedID,
      showFullMap: showFullMap,
      pathViewModel: pathViewModel,
      previewWidth: previewWidth
    ))
  }
}
