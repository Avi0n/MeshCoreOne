import CoreLocation
import MapKit
import MC1Services
import SwiftUI

struct NeighborMapView: View {
    @Environment(\.appState) private var appState
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss

    let session: RemoteNodeSessionDTO
    let neighbors: [NeighbourInfo]
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let userLocation: CLLocation?

    @State private var mapPoints: [MapPoint] = []
    @State private var mapLines: [MapLine] = []
    @State private var cameraRegion: MKCoordinateRegion?
    @State private var cameraRegionVersion = 0
    @State private var mapStyle: MapStyleSelection = .standard
    @State private var isNorthLocked = false
    @State private var showLabels = true
    @State private var showingLayersMenu = false
    @State private var isStyleLoaded = false
    @State private var hasInitiallyFit = false
    @State private var didLoad = false

    private static let singleNodeSpanDelta: CLLocationDegrees = 0.05
    private static let boundingPaddingMultiplier: Double = 2.0

    var body: some View {
        NavigationStack {
            Group {
                if !didLoad {
                    Color.clear
                } else if mapPoints.isEmpty {
                    ContentUnavailableView(
                        L10n.RemoteNodes.RemoteNodes.Status.NeighborMap.Unavailable.title,
                        systemImage: "map",
                        description: Text(L10n.RemoteNodes.RemoteNodes.Status.NeighborMap.Unavailable.description)
                    )
                } else {
                    ZStack(alignment: .bottomTrailing) {
                        MC1MapView(
                            points: mapPoints,
                            lines: mapLines,
                            mapStyle: mapStyle,
                            isDarkMode: colorScheme == .dark,
                            isOffline: !appState.offlineMapService.isNetworkAvailable,
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
                            isStyleLoaded: $isStyleLoaded
                        )
                        .ignoresSafeArea()

                        VStack {
                            Spacer()
                            HStack {
                                Spacer()
                                MapControlsToolbar(
                                    onLocationTap: centerOnUserLocation,
                                    isNorthLocked: $isNorthLocked,
                                    showLabels: $showLabels,
                                    showingLayersMenu: $showingLayersMenu
                                ) {
                                    Button(L10n.Map.Map.Controls.centerAll, systemImage: "arrow.up.left.and.arrow.down.right") {
                                        fitCamera()
                                    }
                                    .mapControlButton(tint: .primary)
                                }
                            }
                        }
                        .overlay(alignment: .bottomTrailing) {
                            if showingLayersMenu {
                                LayersMenu(
                                    selection: $mapStyle,
                                    isPresented: $showingLayersMenu,
                                    viewportBounds: cameraRegion?.toMLNCoordinateBounds()
                                )
                                .padding(.trailing, 16)
                                .padding(.bottom, 160)
                                .transition(.scale.combined(with: .opacity))
                            }
                        }
                        .animation(.spring(response: 0.3), value: showingLayersMenu)
                    }
                }
            }
            .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.neighborMap)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.Localizable.Common.done) { dismiss() }
                }
            }
            .onAppear {
                buildOverlays()
                didLoad = true
            }
            .onChange(of: isStyleLoaded) { _, loaded in
                guard loaded, !hasInitiallyFit else { return }
                hasInitiallyFit = true
                fitCamera()
            }
        }
    }

    // MARK: - Overlay Building

    private func buildOverlays() {
        var points: [MapPoint] = []
        var lines: [MapLine] = []
        var repeaterCoordinate: CLLocationCoordinate2D?

        if session.latitude != 0 || session.longitude != 0 {
            let coord = CLLocationCoordinate2D(latitude: session.latitude, longitude: session.longitude)
            if CLLocationCoordinate2DIsValid(coord) {
                repeaterCoordinate = coord
                points.append(MapPoint(
                    id: session.id,
                    coordinate: coord,
                    pinStyle: .repeaterRingBlue,
                    label: session.name,
                    isClusterable: false,
                    hopIndex: nil,
                    badgeText: nil
                ))
            }
        }

        for neighbor in neighbors {
            let resolution = NeighborNameResolver.resolve(
                for: neighbor.publicKeyPrefix,
                contacts: contacts,
                discoveredNodes: discoveredNodes,
                userLocation: userLocation
            )
            guard let displayName = resolution?.displayName else { continue }
            guard let neighborCoord = resolvedCoordinate(for: neighbor.publicKeyPrefix) else { continue }

            points.append(MapPoint(
                id: UUID(neighborPin: neighbor.publicKeyPrefix),
                coordinate: neighborCoord,
                pinStyle: .repeater,
                label: displayName,
                isClusterable: false,
                hopIndex: nil,
                badgeText: nil
            ))

            if let origin = repeaterCoordinate {
                lines.append(MapLine(
                    id: "neighbor-\(neighbor.publicKeyPrefix.uppercaseHexString())",
                    coordinates: [origin, neighborCoord],
                    style: lineStyle(for: neighbor.snr),
                    opacity: 1.0
                ))

                let mid = CLLocationCoordinate2D(
                    latitude: (origin.latitude + neighborCoord.latitude) / 2,
                    longitude: (origin.longitude + neighborCoord.longitude) / 2
                )
                let snrText = neighbor.snr.formatted(.number.precision(.fractionLength(1))) + " dB"
                points.append(MapPoint(
                    id: UUID(neighborBadge: neighbor.publicKeyPrefix),
                    coordinate: mid,
                    pinStyle: .badge,
                    label: nil,
                    isClusterable: false,
                    hopIndex: nil,
                    badgeText: snrText
                ))
            }
        }

        mapPoints = points
        mapLines = lines
    }

    private func resolvedCoordinate(for prefix: Data) -> CLLocationCoordinate2D? {
        if let contact = RepeaterResolver.bestMatch(for: prefix, in: contacts, userLocation: userLocation),
           contact.hasLocation {
            return CLLocationCoordinate2D(latitude: contact.latitude, longitude: contact.longitude)
        }
        if let node = RepeaterResolver.bestMatch(for: prefix, in: discoveredNodes, userLocation: userLocation),
           node.hasLocation {
            return CLLocationCoordinate2D(latitude: node.latitude, longitude: node.longitude)
        }
        return nil
    }

    private func lineStyle(for snr: Double) -> MapLine.LineStyle {
        switch SNRQuality(snr: snr) {
        case .excellent, .good: .traceGood
        case .fair: .traceMedium
        case .poor: .traceWeak
        case .unknown: .traceUntraced
        }
    }

    // MARK: - Camera

    private func fitCamera() {
        let coords = mapPoints.filter { $0.pinStyle != .badge }.map(\.coordinate)
        if coords.count == 1 {
            cameraRegion = MKCoordinateRegion(
                center: coords[0],
                span: MKCoordinateSpan(
                    latitudeDelta: Self.singleNodeSpanDelta,
                    longitudeDelta: Self.singleNodeSpanDelta
                )
            )
        } else if let region = coords.boundingRegion(paddingMultiplier: Self.boundingPaddingMultiplier) {
            cameraRegion = region
        }
        cameraRegionVersion += 1
    }

    private func centerOnUserLocation() {
        guard let location = appState.bestAvailableLocation else { return }
        cameraRegion = MKCoordinateRegion(
            center: location.coordinate,
            span: MKCoordinateSpan(
                latitudeDelta: Self.singleNodeSpanDelta,
                longitudeDelta: Self.singleNodeSpanDelta
            )
        )
        cameraRegionVersion += 1
    }
}

// MARK: - Deterministic UUIDs

private extension UUID {
    init(neighborPin prefix: Data) {
        let hex = String(prefix.prefix(6).uppercaseHexString().prefix(12))
        let padded = String(repeating: "0", count: max(0, 12 - hex.count)) + hex
        self = UUID(uuidString: "11111111-1111-1111-1111-\(padded)") ?? UUID()
    }

    init(neighborBadge prefix: Data) {
        let hex = String(prefix.prefix(6).uppercaseHexString().prefix(12))
        let padded = String(repeating: "0", count: max(0, 12 - hex.count)) + hex
        self = UUID(uuidString: "22222222-2222-2222-2222-\(padded)") ?? UUID()
    }
}
