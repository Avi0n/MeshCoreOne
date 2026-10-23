import MapKit
import MC1Services
import SwiftUI

struct ContactFullMapView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  let contact: ContactDTO

  @State private var cameraRegion: MKCoordinateRegion?
  @State private var cameraRegionVersion = 0

  var body: some View {
    NavigationStack {
      MC1MapView(
        points: [MapPoint(
          id: contact.id,
          coordinate: contact.coordinate,
          pinStyle: contact.type.pinStyle,
          label: contact.displayName,
          isClusterable: false,
          hopIndex: nil,
          badgeText: nil
        )],
        lines: [],
        mapStyle: .standard,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: true,
        showsUserLocation: true,
        isInteractive: true,
        showsScale: true,
        cameraRegion: $cameraRegion,
        cameraRegionVersion: cameraRegionVersion,
        onPointTap: nil,
        onMapTap: nil,
        onCameraRegionChange: { cameraRegion = $0 }
      )
      .ignoresSafeArea()
      .navigationTitle(contact.displayName)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.done) { dismiss() }
        }
      }
      .onAppear {
        cameraRegion = MKCoordinateRegion(
          center: contact.coordinate,
          span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        )
        cameraRegionVersion = 1
      }
    }
  }
}
