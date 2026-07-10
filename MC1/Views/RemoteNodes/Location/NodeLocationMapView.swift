import CoreLocation
import MapKit
import MapLibre
import MC1Services
import SwiftUI

/// Full-screen map of a node's location: a single pin for the live fix, or a
/// decimated pin set plus a polyline for the historical path. Content is a pure
/// function of the points/line passed in, so it holds only camera `@State`.
///
/// Pushed onto the host's navigation stack, never presented modally: the node
/// status/telemetry surfaces that reach it are themselves sheets, and stacking a
/// sheet or cover on them collapses both, matching `NeighborSNRMapView`.
struct NodeLocationMapView: View {
  /// Span used when the path resolves to a single fix, with no box to fit.
  private static let singleFixSpanDelta: CLLocationDegrees = 0.05
  /// Padding around the multi-fix bounding region, keeping the path clear of the
  /// floating map controls, matching `MessagePathMapView`.
  private static let pathBoundingPaddingMultiplier: Double = 2.5

  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme

  let points: [MapPoint]
  let line: MapLine?
  let title: String

  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked

  @State private var cameraRegion: MKCoordinateRegion?
  @State private var cameraRegionVersion = 0
  @State private var isCenteredOnUser = false
  @State private var isStyleLoaded = false
  @State private var hasInitiallyFit = false
  // Snapshotted exactly once per appearance lifecycle so re-renders of the
  // enclosing screen (e.g. a telemetry refresh behind this pushed map) can't
  // re-mint MapPoint identities and churn MapLibre annotations, mirroring
  // MessagePathMapView.locatedNodes. A dedicated flag rather than an empty-array
  // check so a genuinely empty snapshot still counts as taken.
  @State private var hasSnapshotted = false
  @State private var displayPoints: [MapPoint] = []
  @State private var displayLine: MapLine?

  private var lines: [MapLine] {
    displayLine.map { [$0] } ?? []
  }

  var body: some View {
    ZStack(alignment: .bottom) {
      MC1MapView(
        points: displayPoints,
        lines: lines,
        mapStyle: mapStyleSelection,
        isDarkMode: colorScheme == .dark,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: showLabels,
        showsUserLocation: true,
        isInteractive: true,
        showsScale: true,
        isNorthLocked: isNorthLocked,
        cameraRegion: $cameraRegion,
        cameraRegionVersion: cameraRegionVersion,
        cameraBottomSheetFraction: 0,
        onPointTap: nil,
        onMapTap: nil,
        onCameraRegionChange: { cameraRegion = $0 },
        isStyleLoaded: $isStyleLoaded,
        isCenteredOnUser: $isCenteredOnUser
      )
      .ignoresSafeArea()

      toolbarOverlay
    }
    .navigationTitle(title)
    .navigationBarTitleDisplayMode(.inline)
    .onAppear {
      guard !hasSnapshotted else { return }
      hasSnapshotted = true
      displayPoints = points
      displayLine = line
    }
    // The map gates camera moves until its style loads, so fit once on that signal,
    // matching MessagePathMapView, rather than issuing a fit the map would drop.
    .onChange(of: isStyleLoaded) { _, loaded in
      guard loaded, !hasInitiallyFit else { return }
      hasInitiallyFit = true
      fitCameraToContent()
    }
  }

  /// Fixed bottom-right map controls.
  private var toolbarOverlay: some View {
    HStack {
      Spacer()
      MapControlsToolbar(
        onLocationTap: centerOnMyLocation,
        isCenteredOnUser: isCenteredOnUser,
        isNorthLocked: $isNorthLocked,
        showLabels: $showLabels,
        mapStyleSelection: $mapStyleSelection,
        viewportBounds: cameraRegion?.toMLNCoordinateBounds()
      ) {
        centerAllButton
      }
    }
  }

  /// Re-fits the camera to the displayed pin(s). Disabled when nothing is plottable.
  private var centerAllButton: some View {
    Button(L10n.Map.Map.Controls.centerAll, systemImage: "arrow.up.left.and.arrow.down.right") {
      isCenteredOnUser = false
      fitCameraToContent()
    }
    .mapControlButton(tint: displayPoints.isEmpty ? .secondary : .primary)
    .disabled(displayPoints.isEmpty)
  }

  private func fitCameraToContent() {
    let coords = displayPoints.map(\.coordinate)
    if coords.count == 1 {
      cameraRegion = MKCoordinateRegion(
        center: coords[0],
        span: MKCoordinateSpan(
          latitudeDelta: Self.singleFixSpanDelta,
          longitudeDelta: Self.singleFixSpanDelta
        )
      )
    } else if let region = coords.boundingRegion(paddingMultiplier: Self.pathBoundingPaddingMultiplier) {
      cameraRegion = region
    } else {
      return
    }
    cameraRegionVersion += 1
  }

  private func centerOnMyLocation() {
    isCenteredOnUser = appState.centerOnUserLocation { region in
      cameraRegion = region
      cameraRegionVersion += 1
    }
  }
}
