import CoreLocation
import CryptoKit
import MapKit
import MapLibre
import MC1Services
import SwiftUI

struct MessagePathMapView: View {
  /// Span used when the path resolves to a single node, with no bounding box to fit.
  private static let singleNodeSpanDelta: CLLocationDegrees = 0.05
  /// Padding around the multi-node bounding region, wider than `boundingRegion`'s
  /// 1.5 default to keep the path clear of the floating map-controls toolbar.
  private static let pathBoundingPaddingMultiplier: Double = 2.5
  /// Stable annotation identity for the receiver pin across canvas rebuilds.
  private static let receiverPointID = pointID(namespace: "message-path-receiver", bytes: Data())
  private static let capsuleBarInnerHorizontalPadding: CGFloat = 12
  private static let capsuleBarInnerVerticalPadding: CGFloat = 8
  private static let capsuleBarCornerRadius: CGFloat = 24
  private static let capsuleBarOuterHorizontalPadding: CGFloat = 16
  private static let capsuleBarOuterBottomPadding: CGFloat = 8

  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  let message: MessageDTO
  let arrivals: [MessagePathArrival]
  @Binding var selectedID: UUID?
  let pathViewModel: MessagePathViewModel

  @State private var cameraRegion: MKCoordinateRegion?
  @State private var cameraRegionVersion = 0
  @State private var mapStyle: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @State private var showLabels = true
  @State private var isStyleLoaded = false
  @State private var isCenteredOnUser = false
  @State private var hasInitiallyFit = false

  struct CanvasModel {
    let points: [MapPoint]
    let lines: [MapLine]
    let cameraRegion: MKCoordinateRegion?
    let locatedCount: Int
    let selectedCoordinates: [CLLocationCoordinate2D]
    let hopCount: Int
    let isDistanceIncomplete: Bool
  }

  private var canvas: CanvasModel {
    Self.canvasModel(
      message: message,
      arrivals: arrivals,
      selectedID: selectedID,
      pathViewModel: pathViewModel,
      connectedDevice: appState.connectedDevice,
      userLocation: appState.bestAvailableLocation
    )
  }

  var body: some View {
    NavigationStack {
      Group {
        if pathViewModel.isLoading {
          ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if canvas.locatedCount == 0 {
          ContentUnavailableView(
            L10n.Chats.Chats.Path.Unavailable.title,
            systemImage: "map",
            description: Text(L10n.Chats.Chats.Path.Unavailable.description)
          )
          .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
          mapCanvas
        }
      }
      .toolbar {
        if canvas.locatedCount > 0 {
          ToolbarItem(placement: .principal) {
            PathDistanceBanner(
              hopCount: canvas.hopCount,
              totalPathDistance: canvas.selectedCoordinates.totalDistance(),
              isDistanceIncomplete: canvas.isDistanceIncomplete
            )
          }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.done) { dismiss() }
        }
      }
      .navigationBarTitleDisplayMode(.inline)
      .onChange(of: pathViewModel.isLoading) { _, isLoading in
        guard !isLoading else { return }
        completeInitialFitIfNeeded()
      }
      .onChange(of: isStyleLoaded) { _, loaded in
        guard loaded else { return }
        completeInitialFitIfNeeded()
      }
      .onChange(of: canvas.selectedCoordinates.count) { _, _ in
        completeInitialFitIfNeeded()
      }
    }
  }

  private var mapCanvas: some View {
    ZStack(alignment: .bottom) {
      MC1MapView(
        points: canvas.points,
        lines: canvas.lines,
        mapStyle: mapStyle,
        isDarkMode: colorScheme == .dark,
        showLabels: showLabels,
        clusteringEnabled: false,
        showsUserLocation: false,
        isInteractive: true,
        showsScale: true,
        isNorthLocked: isNorthLocked,
        cameraRegion: $cameraRegion,
        cameraRegionVersion: cameraRegionVersion,
        onPointTap: nil,
        onMapTap: nil,
        onCameraRegionChange: { cameraRegion = $0 },
        isStyleLoaded: $isStyleLoaded,
        isCenteredOnUser: $isCenteredOnUser
      )
      // Path swaps must not run inside the capsule selection animation.
      .transaction { $0.animation = nil }
      .ignoresSafeArea()

      VStack(spacing: 0) {
        HStack {
          Spacer()
          MapControlsToolbar(
            onLocationTap: centerOnUserLocation,
            isCenteredOnUser: isCenteredOnUser,
            isNorthLocked: $isNorthLocked,
            showLabels: $showLabels,
            mapStyleSelection: $mapStyle,
            viewportBounds: cameraRegion?.toMLNCoordinateBounds()
          ) {
            if canvas.locatedCount > 0 {
              Button(L10n.Chats.Chats.Path.centerOnPath, systemImage: "arrow.up.left.and.arrow.down.right") {
                isCenteredOnUser = false
                fitCameraToSelectedPath()
              }
              .mapControlButton(tint: .primary)
            }
          }
        }
        if arrivals.count > 1 {
          MessagePathArrivalCapsules(
            arrivals: arrivals,
            selectedID: $selectedID,
            reduceMotion: reduceMotion,
            chrome: .glass
          )
          .padding(.horizontal, Self.capsuleBarInnerHorizontalPadding)
          .padding(.vertical, Self.capsuleBarInnerVerticalPadding)
          .frame(maxWidth: .infinity, alignment: .leading)
          .liquidGlass(in: .rect(cornerRadius: Self.capsuleBarCornerRadius))
          .padding(.horizontal, Self.capsuleBarOuterHorizontalPadding)
          .padding(.bottom, Self.capsuleBarOuterBottomPadding)
        }
      }
      .safeAreaPadding(.bottom)
    }
  }

  /// Frames the selected path once after style load. Later path taps leave the camera.
  private func completeInitialFitIfNeeded() {
    guard !hasInitiallyFit, isStyleLoaded else { return }
    guard !canvas.selectedCoordinates.isEmpty else { return }
    hasInitiallyFit = true
    fitCameraToSelectedPath()
  }

  private func fitCameraToSelectedPath() {
    let coords = canvas.selectedCoordinates
    if coords.count == 1 {
      cameraRegion = MKCoordinateRegion(
        center: coords[0],
        span: MKCoordinateSpan(
          latitudeDelta: Self.singleNodeSpanDelta,
          longitudeDelta: Self.singleNodeSpanDelta
        )
      )
    } else if let region = coords.boundingRegion(paddingMultiplier: Self.pathBoundingPaddingMultiplier) {
      cameraRegion = region
    } else {
      return
    }
    cameraRegionVersion += 1
  }

  private func centerOnUserLocation() {
    guard let location = appState.bestAvailableLocation else {
      appState.locationService.requestLocation()
      return
    }
    isCenteredOnUser = true
    cameraRegion = MKCoordinateRegion(
      center: location.coordinate,
      span: MKCoordinateSpan(
        latitudeDelta: Self.singleNodeSpanDelta,
        longitudeDelta: Self.singleNodeSpanDelta
      )
    )
    cameraRegionVersion += 1
  }

  static func canvasModel(
    message: MessageDTO,
    arrivals: [MessagePathArrival],
    selectedID: UUID?,
    pathViewModel: MessagePathViewModel,
    connectedDevice: DeviceDTO?,
    userLocation: CLLocation?
  ) -> CanvasModel {
    let selected = MessagePathArrivals.resolvedSelection(preferred: selectedID, arrivals: arrivals)
    let sender = pathViewModel.locatedSender(for: message)
    let receiverLocation: CLLocation? = if let device = connectedDevice, device.hasLocation {
      CLLocation(latitude: device.latitude, longitude: device.longitude)
    } else {
      userLocation
    }

    var points: [MapPoint] = []
    var seenKeys = Set<Data>()
    let start: CLLocationCoordinate2D?
    if message.isOutgoing, let device = connectedDevice, device.hasLocation {
      let coord = CLLocationCoordinate2D(latitude: device.latitude, longitude: device.longitude)
      start = coord
      points.append(MapPoint(
        id: device.id,
        coordinate: coord,
        pinStyle: .pointA,
        label: device.nodeName,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ))
      seenKeys.insert(device.publicKey)
    } else if let sender {
      let coord = CLLocationCoordinate2D(latitude: sender.latitude, longitude: sender.longitude)
      start = coord
      points.append(MapPoint(
        id: sender.id,
        coordinate: coord,
        pinStyle: .pointA,
        label: sender.displayName,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ))
      seenKeys.insert(sender.publicKey)
    } else {
      start = nil
    }

    var lines: [MapLine] = []
    var selectedCoordinates: [CLLocationCoordinate2D] = []
    var selectedHopCount = 0
    var isDistanceIncomplete = false

    if let selectedArrival = arrivals.first(where: { $0.id == selected }) {
      let path = locatedPath(
        arrival: selectedArrival,
        start: start,
        pathViewModel: pathViewModel,
        receiverLocation: receiverLocation,
        userLocation: userLocation
      )
      selectedCoordinates = path.coordinates
      selectedHopCount = selectedArrival.hopCount
      isDistanceIncomplete = path.isDistanceIncomplete
      if path.coordinates.count >= 2 {
        lines.append(MapLine(
          id: "message-path-\(selectedArrival.id)",
          coordinates: path.coordinates,
          style: .messagePath,
          opacity: 1.0
        ))
      }
      for point in path.hopPoints where seenKeys.insert(point.key).inserted {
        points.append(point.point)
      }
    }

    if let loc = receiverLocation {
      let coord = loc.coordinate
      points.append(MapPoint(
        id: receiverPointID,
        coordinate: coord,
        pinStyle: .pointB,
        label: connectedDevice?.nodeName,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ))
    }

    let camera: MKCoordinateRegion? = if selectedCoordinates.count == 1 {
      MKCoordinateRegion(
        center: selectedCoordinates[0],
        span: MKCoordinateSpan(
          latitudeDelta: singleNodeSpanDelta,
          longitudeDelta: singleNodeSpanDelta
        )
      )
    } else {
      selectedCoordinates.boundingRegion(paddingMultiplier: pathBoundingPaddingMultiplier)
    }

    return CanvasModel(
      points: points,
      lines: lines,
      cameraRegion: camera,
      locatedCount: points.count,
      selectedCoordinates: selectedCoordinates,
      hopCount: selectedHopCount,
      isDistanceIncomplete: isDistanceIncomplete
    )
  }

  private struct LocatedPath {
    let coordinates: [CLLocationCoordinate2D]
    let hopPoints: [(key: Data, point: MapPoint)]
    let isDistanceIncomplete: Bool
  }

  private static func locatedPath(
    arrival: MessagePathArrival,
    start: CLLocationCoordinate2D?,
    pathViewModel: MessagePathViewModel,
    receiverLocation: CLLocation?,
    userLocation: CLLocation?
  ) -> LocatedPath {
    var coordinates: [CLLocationCoordinate2D] = []
    var hopPoints: [(key: Data, point: MapPoint)] = []

    if let start {
      coordinates.append(start)
    }

    var seenKeys = Set<Data>()
    var isDistanceIncomplete = false
    for (index, hop) in arrival.pathHops.enumerated() {
      let hopNumber = index + 1
      let resolvedContact = RepeaterResolver.resolve(
        for: hop.data,
        in: pathViewModel.repeaters,
        userLocation: userLocation
      )
      let resolvedNode = RepeaterResolver.resolve(
        for: hop.data,
        in: pathViewModel.discoveredRepeaters,
        userLocation: userLocation
      )
      let resolved: (node: any RepeaterResolvable, matchKind: NodeNameMatchKind)? =
        resolvedContact.map { ($0.node, $0.matchKind) } ?? resolvedNode.map { ($0.node, $0.matchKind) }
      guard let resolved, resolved.matchKind == .exact, resolved.node.hasLocation else {
        isDistanceIncomplete = true
        continue
      }
      let r = resolved.node
      if seenKeys.insert(r.publicKey).inserted {
        let coord = CLLocationCoordinate2D(latitude: r.latitude, longitude: r.longitude)
        coordinates.append(coord)
        hopPoints.append((r.publicKey, MapPoint(
          id: hopPointID(publicKey: r.publicKey),
          coordinate: coord,
          pinStyle: .repeaterHop,
          label: r.resolvableName,
          isClusterable: false,
          hopIndex: hopNumber,
          badgeText: nil
        )))
      }
    }

    if let loc = receiverLocation {
      let coord = loc.coordinate
      let differsFromLast = coordinates.last.map {
        $0.latitude != coord.latitude || $0.longitude != coord.longitude
      } ?? true
      if differsFromLast {
        coordinates.append(coord)
      }
    }

    return LocatedPath(
      coordinates: coordinates,
      hopPoints: hopPoints,
      isDistanceIncomplete: isDistanceIncomplete
    )
  }

  private static func hopPointID(publicKey: Data) -> UUID {
    pointID(namespace: "message-path-hop", bytes: publicKey)
  }

  private static func pointID(namespace: String, bytes: Data) -> UUID {
    let digest = Array(SHA256.hash(data: Data(namespace.utf8) + bytes))
    return UUID(uuid: (
      digest[0], digest[1], digest[2], digest[3],
      digest[4], digest[5], digest[6], digest[7],
      digest[8], digest[9], digest[10], digest[11],
      digest[12], digest[13], digest[14], digest[15]
    ))
  }
}
