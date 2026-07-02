import MapKit
import MapLibre
import MC1Services
import SwiftUI

/// Map controls toolbar for trace path map view (location, labels, layers)
struct TracePathMapToolbarView: View {
  @Environment(\.appState) private var appState
  @Bindable var mapViewModel: TracePathMapViewModel
  @Binding var mapStyleSelection: MapStyleSelection
  @Binding var showLabels: Bool
  @Binding var isNorthLocked: Bool
  @Binding var isCenteredOnUser: Bool

  var body: some View {
    VStack {
      Spacer()
      HStack {
        Spacer()
        MapControlsToolbar(
          onLocationTap: {
            if let location = appState.bestAvailableLocation {
              mapViewModel.setCameraRegion(MKCoordinateRegion(
                center: location.coordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
              ))
              isCenteredOnUser = true
            } else {
              appState.locationService.requestLocation()
            }
          },
          isCenteredOnUser: isCenteredOnUser,
          isNorthLocked: $isNorthLocked,
          showLabels: $showLabels,
          mapStyleSelection: $mapStyleSelection,
          viewportBounds: mapViewModel.cameraRegion?.toMLNCoordinateBounds()
        ) {
          // Center on path
          if mapViewModel.hasPath {
            Button(L10n.Contacts.Contacts.Trace.Map.centerOnPath, systemImage: "arrow.up.left.and.arrow.down.right") {
              mapViewModel.centerOnPath()
            }
            .mapControlButton(tint: .primary)
          }
        }
      }
    }
  }
}
