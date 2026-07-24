import MapKit
import MapLibre
import MC1Services
import SwiftUI

/// Map controls toolbar for trace path map view (location, filter, labels, style)
struct TracePathMapToolbarView: View {
  @Environment(\.appState) private var appState
  @Bindable var mapViewModel: TracePathMapViewModel
  @Binding var mapStyleSelection: MapStyleSelection
  @Binding var showLabels: Bool
  @Binding var isNorthLocked: Bool
  @Binding var isCenteredOnUser: Bool
  var filter: MapFilterControl

  var body: some View {
    VStack {
      Spacer()
      HStack {
        Spacer()
        MapControlsToolbar(
          onLocationTap: {
            isCenteredOnUser = appState.centerOnUserLocation { mapViewModel.setCameraRegion($0) }
          },
          isCenteredOnUser: isCenteredOnUser,
          isNorthLocked: $isNorthLocked,
          showLabels: $showLabels,
          mapStyleSelection: $mapStyleSelection,
          viewportBounds: mapViewModel.cameraRegion?.toMLNCoordinateBounds(),
          filter: filter
        ) {
          // Center on path
          if mapViewModel.hasPath {
            Button(L10n.Contacts.Contacts.Trace.Map.centerOnPath, systemImage: "arrow.up.left.and.arrow.down.right") {
              isCenteredOnUser = false
              mapViewModel.centerOnPath()
            }
            .mapControlButton(tint: .primary)
          }
        }
      }
    }
  }
}
