import CoreLocation
import MapKit
import MapLibre
import MC1Services
import SwiftUI

/// Map of a repeater's neighbors: the repeater at center, each exact-match located neighbor pinned
/// with an SNR-colored link line. Neighbors that can't be placed are counted in a top pill that
/// pushes their list. The content is a pure function of data already in hand, so it carries plain
/// screen-lifetime parameters and holds only camera `@State` rather than a view model.
///
/// Pushed onto the host's navigation stack rather than presented modally: stacking a cover or
/// sheet on the telemetry sheet that hosts it collapses both presentations. The no-location list is
/// likewise a push, not a sheet, for the same reason.
struct NeighborSNRMapView: View {
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme

  let session: RemoteNodeSessionDTO
  let neighbors: [NeighbourInfo]
  let contacts: [ContactDTO]
  let discoveredNodes: [DiscoveredNodeDTO]
  let userLocation: CLLocation?

  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @AppStorage(AppStorageKey.mapColorSchemePreference.rawValue)
  private var mapColorSchemeRaw = AppStorageKey.defaultMapColorSchemePreference

  @State private var cameraRegion: MKCoordinateRegion?

  private var mapIsDark: Bool {
    let preference = AppColorSchemePreference(rawValue: mapColorSchemeRaw) ?? .system
    return resolvedMapIsDark(preference: preference, colorScheme: colorScheme)
  }

  @State private var cameraRegionVersion = 0
  @State private var isCenteredOnUser = false
  @State private var plotted: NeighborSNRMapBuilder.PlottedNeighbors?
  @State private var showingNoLocationList = false
  @State private var isStyleLoaded = false

  var body: some View {
    ZStack(alignment: .bottom) {
      MC1MapView(
        points: plotted?.points ?? [],
        lines: plotted?.lines ?? [],
        mapStyle: mapStyleSelection,
        isDarkMode: mapIsDark,
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
    .overlay(alignment: .top) {
      if let unplottable = plotted?.unplottable, !unplottable.isEmpty {
        noLocationPill(count: unplottable.count)
      }
    }
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.neighborsMapTitle)
    .navigationBarTitleDisplayMode(.inline)
    .navigationDestination(isPresented: $showingNoLocationList) {
      if let unplottable = plotted?.unplottable {
        NeighborsNoLocationList(unplottable: unplottable)
      }
    }
    .onAppear {
      guard plotted == nil else { return }
      let built = NeighborSNRMapBuilder.build(
        session: session,
        neighbors: neighbors,
        contacts: contacts,
        discoveredNodes: discoveredNodes,
        userLocation: userLocation
      )
      withAnimation { plotted = built }
      setCameraRegion(built.region)
    }
    // The map gates camera moves until its style finishes loading, which usually lands after
    // the on-appear fit. Re-issue the fit on that signal so the nodes frame deterministically
    // rather than depending on an incidental re-render.
    .onChange(of: isStyleLoaded) { _, loaded in
      if loaded { setCameraRegion(plotted?.region) }
    }
  }

  /// Glanceable count of neighbors that couldn't be placed; tapping pushes their list. Styled like
  /// the trace path map's top banner so the two maps read the same.
  private func noLocationPill(count: Int) -> some View {
    Button {
      showingNoLocationList = true
    } label: {
      HStack(spacing: 6) {
        Image(systemName: "mappin.slash")
        Text(L10n.RemoteNodes.RemoteNodes.Status.neighborsNotShown(count))
      }
      .font(.subheadline.weight(.medium))
      .padding(.horizontal, 16)
      .padding(.vertical, 10)
      .liquidGlass(in: .capsule)
    }
    .buttonStyle(.plain)
    .safeAreaPadding(.top)
    .transition(.move(edge: .top).combined(with: .opacity))
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

  /// Centers and fits the camera in one step: `MC1MapView` ignores a region whose version
  /// still matches the last applied one, so the region and the version bump must move together.
  private func setCameraRegion(_ region: MKCoordinateRegion?) {
    guard let region else { return }
    cameraRegion = region
    cameraRegionVersion += 1
  }

  private func centerOnMyLocation() {
    isCenteredOnUser = appState.centerOnUserLocation { setCameraRegion($0) }
  }

  /// Re-fits the camera to the repeater and its plotted neighbors. Disabled when nothing is plottable.
  private var centerAllButton: some View {
    Button(L10n.Map.Map.Controls.centerAll, systemImage: "arrow.up.left.and.arrow.down.right") {
      isCenteredOnUser = false
      setCameraRegion(plotted?.region)
    }
    .mapControlButton(tint: plotted?.region == nil ? .secondary : .primary)
    .disabled(plotted?.region == nil)
  }
}

// MARK: - No Location List

/// Pushed list of neighbors that could not be placed reliably (ambiguous, unlocated, or
/// unresolved). Reuses `NeighborRow`, including its "?" fallback-match affordance.
private struct NeighborsNoLocationList: View {
  let unplottable: [NeighborSNRMapBuilder.UnplottableNeighbor]

  var body: some View {
    List(unplottable, id: \.neighbor.publicKeyPrefix) { item in
      NeighborRow(
        neighbor: item.neighbor,
        displayName: item.displayName,
        matchKind: item.matchKind
      )
    }
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.neighborsNotShown(unplottable.count))
    .navigationBarTitleDisplayMode(.inline)
  }
}
