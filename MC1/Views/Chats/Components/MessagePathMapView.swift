import CoreLocation
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

  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.colorScheme) private var colorScheme

  let message: MessageDTO
  let pathViewModel: MessagePathViewModel

  @State private var cameraRegion: MKCoordinateRegion?
  @State private var cameraRegionVersion = 0
  @State private var mapStyle: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @State private var showLabels = true
  @State private var isStyleLoaded = false
  @State private var isCenteredOnUser = false
  @State private var hasInitiallyFit = false
  @State private var locatedNodes: [(point: MapPoint, coordinate: CLLocationCoordinate2D)] = []

  private var mapPoints: [MapPoint] {
    locatedNodes.map(\.point)
  }

  private var mapLines: [MapLine] {
    let coords = locatedNodes.map(\.coordinate)
    guard coords.count >= 2 else { return [] }
    return [MapLine(id: "message-path", coordinates: coords, style: .messagePath, opacity: 1.0)]
  }

  /// Length of the drawn path, over only the nodes we could place. Nil until at
  /// least two nodes resolve to coordinates, so the pill's distance always
  /// matches the polyline in `mapLines`.
  private var totalPathDistance: CLLocationDistance? {
    locatedNodes.map(\.coordinate).totalDistance()
  }

  /// Total intermediate-hop count from the message's path, including hops too
  /// ambiguous to plot. A `nil` `pathNodes` yields 0, which is indistinguishable
  /// between a genuine direct message and firmware that omits path data.
  private var hopCount: Int {
    message.pathHops.count
  }

  var body: some View {
    NavigationStack {
      Group {
        if pathViewModel.isLoading {
          ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if locatedNodes.isEmpty {
          ContentUnavailableView(
            L10n.Chats.Chats.Path.Unavailable.title,
            systemImage: "map",
            description: Text(L10n.Chats.Chats.Path.Unavailable.description)
          )
        } else {
          ZStack(alignment: .bottomTrailing) {
            MC1MapView(
              points: mapPoints,
              lines: mapLines,
              mapStyle: mapStyle,
              isDarkMode: colorScheme == .dark,
              showLabels: showLabels,
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
            .ignoresSafeArea()

            PathDistanceBanner(
              hopCount: hopCount,
              totalPathDistance: totalPathDistance
            )

            VStack {
              Spacer()
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
                  if !locatedNodes.isEmpty {
                    Button(L10n.Chats.Chats.Path.centerOnPath, systemImage: "arrow.up.left.and.arrow.down.right") {
                      isCenteredOnUser = false
                      fitCameraToPath()
                    }
                    .mapControlButton(tint: .primary)
                  }
                }
              }
            }
          }
        }
      }
      .navigationTitle(L10n.Chats.Chats.Path.map)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Localizable.Common.done) { dismiss() }
        }
      }
      .onAppear {
        locatedNodes = buildLocatedNodes()
      }
      .onChange(of: isStyleLoaded) { _, loaded in
        guard loaded, !hasInitiallyFit else { return }
        hasInitiallyFit = true
        fitCameraToPath()
      }
    }
  }

  private func fitCameraToPath() {
    let coords = locatedNodes.map(\.coordinate)
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

  private func buildLocatedNodes() -> [(point: MapPoint, coordinate: CLLocationCoordinate2D)] {
    var nodes: [(MapPoint, CLLocationCoordinate2D)] = []

    // Sender
    if let keyPrefix = message.senderKeyPrefix,
       let sender = pathViewModel.contacts.first(where: { $0.publicKeyPrefix == keyPrefix }),
       sender.hasLocation {
      let coord = CLLocationCoordinate2D(latitude: sender.latitude, longitude: sender.longitude)
      nodes.append((MapPoint(
        id: sender.id,
        coordinate: coord,
        pinStyle: .pointA,
        label: sender.displayName,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ), coord))
    }

    // Repeater hops. A 1-byte path hash can't tell apart repeaters sharing a
    // first key byte, so only plot hops that resolve to a single, unambiguous
    // repeater (exact match). Ambiguous hops — where several known repeaters
    // share the hash — are skipped rather than guessed at by proximity, which
    // is what produced the criss-crossing pile-ups.
    if let pathNodes = message.pathNodes {
      let size = message.pathHashSize
      let hops = stride(from: 0, to: pathNodes.count, by: size).map { start -> Data in
        Data(pathNodes[start..<min(start + size, pathNodes.count)])
      }

      var seenKeys = Set<Data>()
      for (index, hashBytes) in hops.enumerated() {
        let hopNumber = index + 1
        let resolvedContact = RepeaterResolver.resolve(for: hashBytes, in: pathViewModel.repeaters, userLocation: appState.bestAvailableLocation)
        let resolvedNode = RepeaterResolver.resolve(for: hashBytes, in: pathViewModel.discoveredRepeaters, userLocation: appState.bestAvailableLocation)
        let resolved: (node: any RepeaterResolvable, matchKind: NodeNameMatchKind)? =
          resolvedContact.map { ($0.node, $0.matchKind) } ?? resolvedNode.map { ($0.node, $0.matchKind) }
        guard let resolved, resolved.matchKind == .exact else { continue }
        let r = resolved.node
        if r.hasLocation, seenKeys.insert(r.publicKey).inserted {
          let coord = CLLocationCoordinate2D(latitude: r.latitude, longitude: r.longitude)
          nodes.append((MapPoint(
            id: UUID(),
            coordinate: coord,
            pinStyle: .repeaterHop,
            label: r.resolvableName,
            isClusterable: false,
            hopIndex: hopNumber,
            badgeText: nil
          ), coord))
        }
      }
    }

    // Receiver (this device)
    let receiverLocation: CLLocation? = if let device = appState.connectedDevice, device.hasLocation {
      CLLocation(latitude: device.latitude, longitude: device.longitude)
    } else {
      appState.bestAvailableLocation
    }

    if let loc = receiverLocation {
      let coord = loc.coordinate
      nodes.append((MapPoint(
        id: UUID(),
        coordinate: coord,
        pinStyle: .pointB,
        label: appState.connectedDevice?.nodeName,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ), coord))
    }

    return nodes
  }
}
