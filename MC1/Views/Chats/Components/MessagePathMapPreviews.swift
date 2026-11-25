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
        guard let selected = selectedArrival else { return }
        await pathViewModel.renderSelectedPreview(for: selected, context: previewContext)
      }
      .task(id: renderID(arrivalIDs: arrivals.map(\.id))) {
        await pathViewModel.prefetchPreviews(
          for: arrivals,
          skipping: selectedArrival?.id,
          context: previewContext
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

  private var selectedArrival: MessagePathArrival? {
    MessagePathArrivals.selectedArrival(preferred: selectedID, in: arrivals)
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

  private func renderID(arrivalIDs: [UUID]) -> RenderID {
    RenderID(
      selectedID: selectedArrival?.id,
      arrivalIDs: arrivalIDs,
      isLoading: pathViewModel.isLoading,
      isDark: previewContext.isDark,
      isOffline: previewContext.isOffline,
      widthBucket: previewContext.widthBucket
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
