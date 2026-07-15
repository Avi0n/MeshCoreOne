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
  /// A small breathing margin around the multi-fix bounding box. Unlike
  /// `MessagePathMapView` (which inflates the span 2.5x and passes no edge padding),
  /// this map passes `cameraBottomSheetFraction: 0`, so `setVisibleCoordinateBounds`
  /// already insets the fit by the safe-area padding that clears the nav bar and
  /// controls. A large multiplier on top of that padding double-margins the fit and
  /// leaves the path filling a fraction of the screen, so keep this just above 1.
  private static let pathBoundingPaddingMultiplier: Double = 1.3

  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme

  let points: [MapPoint]
  let lines: [MapLine]
  /// Pin id → its report, driving the tap callout.
  let reports: [UUID: LocationPathMapBuilder.LocationReport]
  let title: String
  /// The source snapshot id to auto-select once the map fits, or nil when opened
  /// from the preview's expand button (which selects nothing).
  let initialSelectionID: UUID?

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
  @State private var displayLines: [MapLine] = []
  @State private var selectedReport: SelectedReport?
  @State private var selectedPointScreenPosition: CGPoint?
  // Programmatic selection routed to the map coordinator: the resolved point id plus
  // a version bumped once, after the first fit, to fire the auto-select.
  @State private var pendingSelectionPointID: UUID?
  @State private var selectionRequestVersion = 0

  var body: some View {
    ZStack(alignment: .bottom) {
      MC1MapView(
        points: displayPoints,
        lines: displayLines,
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
        selectionRequestID: pendingSelectionPointID,
        selectionRequestVersion: selectionRequestVersion,
        onPointTap: { point, screenPosition in
          guard let report = reports[point.id] else { return }
          selectedReport = SelectedReport(id: point.id, report: report)
          selectedPointScreenPosition = screenPosition
        },
        onMapTap: { _ in clearCallout() },
        onCameraRegionChange: { region in
          cameraRegion = region
          if selectedReport != nil { clearCallout() }
        },
        isStyleLoaded: $isStyleLoaded,
        isCenteredOnUser: $isCenteredOnUser
      )
      // Popover attached before ignoresSafeArea, matching the main map (MapCanvasView
      // applies ignoresSafeArea outside MapContentView's popover). When ignoresSafeArea
      // sits between the map and the popover, the anchor rect resolves in the safe-area
      // inset space while the coordinator reports full-screen coordinates, and the top
      // inset shoves the anchor down and the callout flips below the pin.
      .popover(
        item: $selectedReport,
        attachmentAnchor: .rect(.rect(CGRect(
          origin: selectedPointScreenPosition ?? .zero,
          size: CGSize(width: 1, height: 1)
        ))),
        arrowEdge: .bottom
      ) { selection in
        LocationReportCallout(report: selection.report)
          .presentationCompactAdaptation(.popover)
      }
      .ignoresSafeArea()

      toolbarOverlay
    }
    .navigationTitle(title)
    .navigationBarTitleDisplayMode(.inline)
    .onAppear {
      guard !hasSnapshotted else { return }
      hasSnapshotted = true
      displayPoints = points
      displayLines = lines
    }
    // The map gates camera moves until its style loads, so fit once on that signal,
    // matching MessagePathMapView, rather than issuing a fit the map would drop.
    .onChange(of: isStyleLoaded) { _, loaded in
      guard loaded, !hasInitiallyFit else { return }
      hasInitiallyFit = true
      fitCameraToContent()
      // Auto-select the report the row tap targeted. Resolve its snapshot id to the
      // plotted point id and bump the request version so the map projects and pops
      // the callout once the fit above has been applied.
      if let initialSelectionID,
         let pointID = reports.first(where: { $0.value.id == initialSelectionID })?.key {
        pendingSelectionPointID = pointID
        selectionRequestVersion += 1
      }
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

  private func clearCallout() {
    selectedReport = nil
    selectedPointScreenPosition = nil
  }

  /// A tapped report pin, wrapped for `.popover(item:)`. `id` is the pin's id, so
  /// tapping a different pin re-presents the callout.
  private struct SelectedReport: Identifiable {
    let id: UUID
    let report: LocationPathMapBuilder.LocationReport
  }
}
