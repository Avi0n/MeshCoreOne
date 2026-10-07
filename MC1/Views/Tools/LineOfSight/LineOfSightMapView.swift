import CoreLocation
import MapKit
import MC1Services
import SwiftUI

/// Map canvas for one Line of Sight workspace. Camera ownership stays on the
/// view model so a layout change can restore the retained region.
struct LineOfSightMapView: View {
  @Bindable var viewModel: LineOfSightViewModel
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme

  var mapOverlayBottomPadding: CGFloat
  var cameraBottomSheetFraction: CGFloat
  var onRepeaterTap: (ContactDTO) -> Void
  var onMapTap: (CLLocationCoordinate2D) -> Void
  var onMapLongPress: (CLLocationCoordinate2D) -> Void

  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapClusteringEnabled.rawValue)
  private var clusteringEnabled = AppStorageKey.defaultMapClusteringEnabled
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @AppStorage(AppStorageKey.mapColorSchemePreference.rawValue)
  private var mapColorSchemeRaw = AppStorageKey.defaultMapColorSchemePreference

  @State private var isCenteredOnUser = false

  private var mapIsDark: Bool {
    let preference = AppColorSchemePreference(rawValue: mapColorSchemeRaw) ?? .system
    return resolvedMapIsDark(preference: preference, colorScheme: colorScheme)
  }

  var body: some View {
    ZStack {
      MC1MapView(
        points: viewModel.mapPoints,
        lines: viewModel.mapLines,
        mapStyle: mapStyleSelection,
        isDarkMode: mapIsDark,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: showLabels,
        clusteringEnabled: clusteringEnabled,
        showsUserLocation: true,
        isInteractive: true,
        showsScale: true,
        isNorthLocked: isNorthLocked,
        cameraRegion: $viewModel.cameraRegion,
        cameraRegionVersion: viewModel.cameraRegionVersion,
        cameraBottomSheetFraction: cameraBottomSheetFraction,
        onPointTap: { point, _ in
          if let repeater = viewModel.repeatersWithLocation.first(where: { $0.id == point.id }) {
            onRepeaterTap(repeater)
          }
        },
        onMapTap: onMapTap,
        onMapLongPress: onMapLongPress,
        onCameraRegionChange: { region in
          viewModel.acceptUserCameraRegion(region)
        },
        isCenteredOnUser: $isCenteredOnUser
      )
      .ignoresSafeArea()

      VStack {
        Spacer()
        HStack {
          Spacer()
          MapControlsToolbar(
            onLocationTap: {
              Task {
                if let location = try? await appState.locationService.requestCurrentLocation() {
                  viewModel.setCameraRegion(MKCoordinateRegion(
                    center: location.coordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                  ))
                  isCenteredOnUser = true
                }
              }
            },
            isCenteredOnUser: isCenteredOnUser,
            isNorthLocked: $isNorthLocked,
            showLabels: $showLabels,
            clusteringEnabled: $clusteringEnabled,
            mapStyleSelection: $mapStyleSelection,
            viewportBounds: viewModel.cameraRegion?.toMLNCoordinateBounds()
          ) {
            EmptyView()
          }
        }
      }
      .padding(.bottom, mapOverlayBottomPadding)
    }
    .onAppear {
      viewModel.showLabels = showLabels
    }
    .onChange(of: showLabels) { _, newValue in
      viewModel.showLabels = newValue
    }
  }
}
