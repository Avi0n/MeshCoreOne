import CoreLocation
import MapKit
import MC1Services
import SwiftUI

/// The "Location" section of the telemetry history screens: a live inline
/// `MC1MapView` that pushes the full historical path on tap, or the "not
/// captured" row when no valid fix falls in the range. The preview plots either
/// the whole path (pins plus polyline) or just the latest fix.
struct LocationHistorySection: View {
  /// Span for the single-fix preview, tighter than the full-screen map's fit.
  private static let previewSpanDelta: CLLocationDegrees = 0.02
  /// Padding around the multi-fix bounding region, snug since the inline preview
  /// carries no floating controls to clear.
  private static let previewPaddingMultiplier: Double = 1.3
  private static let previewHeight: CGFloat = 200
  private static let previewCornerRadius: CGFloat = 12
  /// Placeholder for the camera binding before the first fit; never applied,
  /// since the version starts at the map's own initial value.
  private static let fallbackRegion = MKCoordinateRegion(
    center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
    span: MKCoordinateSpan(latitudeDelta: previewSpanDelta, longitudeDelta: previewSpanDelta)
  )

  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme

  let snapshots: [NodeStatusSnapshotDTO]
  let showsFullPath: Bool
  /// Drives the full-screen map push. Owned by the host so its
  /// `navigationDestination` sits outside this lazy `List` row.
  @Binding var showsMap: Bool

  // Built once per coordinate set so re-renders (theme, connectivity, a telemetry
  // refresh behind the screen) can't re-mint MapPoint identities and churn the
  // map's annotation source. Style inputs are passed live to MC1MapView instead.
  @State private var path: LocationPathMapBuilder.PlottedPath?
  @State private var cameraVersion = 0

  /// The coordinates plotted for the current mode: the whole path, or just the
  /// latest fix. Empty when nothing is plottable.
  private var plottedCoordinates: [CLLocationCoordinate2D] {
    showsFullPath
      ? snapshots.compactMap(\.validCoordinate)
      : LocationPathMapBuilder.latestFix(from: snapshots).map { [$0] } ?? []
  }

  /// Rebuild identity: the plotted coordinates only. Style is not part of it, so a
  /// dark-mode or connectivity change never rebuilds the path.
  private var coordinateKey: [CoordinateKey] {
    plottedCoordinates.map { CoordinateKey(latitude: $0.latitude, longitude: $0.longitude) }
  }

  private var currentRegion: MKCoordinateRegion {
    region(for: plottedCoordinates) ?? Self.fallbackRegion
  }

  var body: some View {
    Section {
      if plottedCoordinates.isEmpty {
        Text(L10n.RemoteNodes.RemoteNodes.History.sectionNotCaptured(
          L10n.RemoteNodes.RemoteNodes.History.locationSection
        ))
        .font(.subheadline)
        .foregroundStyle(.secondary)
      } else {
        mapPreview
      }
    } header: {
      Text(L10n.RemoteNodes.RemoteNodes.History.locationSection)
    }
    .themedRowBackground(theme)
    .onChange(of: coordinateKey, initial: true) { _, _ in rebuild() }
  }

  private var mapPreview: some View {
    ZStack(alignment: .topTrailing) {
      MC1MapView(
        points: path?.points ?? [],
        lines: path?.line.map { [$0] } ?? [],
        mapStyle: .standard,
        isDarkMode: colorScheme == .dark,
        isOffline: !appState.offlineMapService.isNetworkAvailable,
        showLabels: false,
        showsUserLocation: false,
        isInteractive: false,
        showsScale: false,
        cameraRegion: .constant(currentRegion),
        cameraRegionVersion: cameraVersion,
        onPointTap: nil,
        onMapTap: nil,
        onCameraRegionChange: nil
      )
      // Non-interactive: no pan or zoom, and every tap callback is nil with no
      // clusterable pins, so tapping the map does nothing. Hit testing stays on so
      // MapLibre's attribution button works; only the expand button pushes the map.

      Button {
        showsMap = true
      } label: {
        Image(systemName: "arrow.up.left.and.arrow.down.right")
          .font(.caption.weight(.semibold))
          .padding(6)
          .background(.regularMaterial, in: .rect(cornerRadius: 6))
          .contentShape(.rect)
      }
      .buttonStyle(.borderless)
      .padding(8)
      .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Status.Accessibility.viewLocationOnMap)
    }
    .frame(height: Self.previewHeight)
    .clipShape(.rect(cornerRadius: Self.previewCornerRadius))
    .listRowInsets(EdgeInsets())
    .listRowBackground(Color.clear)
    .padding(.bottom, 8)
    .listRowSeparator(.hidden)
  }

  private func rebuild() {
    let coordinates = plottedCoordinates
    guard let first = coordinates.first else {
      path = nil
      return
    }
    path = showsFullPath
      ? LocationPathMapBuilder.build(from: snapshots)
      : singleFixPath(at: first)
    cameraVersion += 1
  }

  private func region(for coordinates: [CLLocationCoordinate2D]) -> MKCoordinateRegion? {
    guard let first = coordinates.first else { return nil }
    if coordinates.count == 1 {
      return MKCoordinateRegion(
        center: first,
        span: MKCoordinateSpan(
          latitudeDelta: Self.previewSpanDelta,
          longitudeDelta: Self.previewSpanDelta
        )
      )
    }
    return coordinates.boundingRegion(paddingMultiplier: Self.previewPaddingMultiplier)
  }

  private func singleFixPath(at coordinate: CLLocationCoordinate2D) -> LocationPathMapBuilder.PlottedPath {
    LocationPathMapBuilder.PlottedPath(
      points: [MapPoint(
        id: UUID(),
        coordinate: coordinate,
        pinStyle: .droppedPin,
        label: nil,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      )],
      line: nil
    )
  }

  private struct CoordinateKey: Hashable {
    let latitude: Double
    let longitude: Double
  }
}

extension View {
  /// Registers the location history map push destination, building the path from
  /// the given snapshots. Shared so both telemetry history screens define it once.
  func locationMapDestination(isPresented: Binding<Bool>, snapshots: [NodeStatusSnapshotDTO]) -> some View {
    navigationDestination(isPresented: isPresented) {
      let built = LocationPathMapBuilder.build(from: snapshots)
      NodeLocationMapView(
        points: built.points,
        line: built.line,
        title: L10n.RemoteNodes.RemoteNodes.Status.locationMapTitle
      )
    }
  }
}
