import SwiftUI
import CoreLocation
import MapKit
import MC1Services

struct NeighborMapView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.appState) private var appState

    @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard

    let repeaterSession: RemoteNodeSessionDTO
    let neighbors: [NeighbourInfo]
    let contacts: [ContactDTO]
    let discoveredNodes: [DiscoveredNodeDTO]
    let userLocation: CLLocation?

    @State private var cameraRegion: MKCoordinateRegion? = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
        span: MKCoordinateSpan(latitudeDelta: 0.1, longitudeDelta: 0.1)
    )
    @State private var cameraRegionVersion = 0
    @State private var isStyleLoaded = false

    private var mapData: (points: [MapPoint], lines: [MapLine]) {
        var points: [MapPoint] = []
        var lines: [MapLine] = []

        let centerCoord = CLLocationCoordinate2D(latitude: repeaterSession.latitude, longitude: repeaterSession.longitude)

        // Add repeater point
        points.append(MapPoint(
            id: repeaterSession.id,
            coordinate: centerCoord,
            pinStyle: .repeater,
            label: repeaterSession.name,
            isClusterable: false,
            hopIndex: nil,
            badgeText: nil
        ))

        // Add neighbors and lines
        for neighbor in neighbors {
            let resolution = NeighborNameResolver.resolve(
                for: neighbor.publicKeyPrefix,
                contacts: contacts,
                discoveredNodes: discoveredNodes,
                userLocation: userLocation
            )

            // Try to find the actual node to get location
            let neighborCoord: CLLocationCoordinate2D? = {
                if let contact = RepeaterResolver.resolve(for: neighbor.publicKeyPrefix, in: contacts, userLocation: userLocation)?.node {
                    return CLLocationCoordinate2D(latitude: contact.latitude, longitude: contact.longitude)
                }
                if let discovered = RepeaterResolver.resolve(for: neighbor.publicKeyPrefix, in: discoveredNodes, userLocation: userLocation)?.node {
                    return CLLocationCoordinate2D(latitude: discovered.latitude, longitude: discovered.longitude)
                }
                return nil
            }()

            if let neighborCoord = neighborCoord, neighborCoord.latitude != 0, neighborCoord.longitude != 0 {
                let neighborId = UUID() // Or a deterministic UUID based on public key
                let name = resolution?.displayName ?? L10n.RemoteNodes.RemoteNodes.Status.unknown

                points.append(MapPoint(
                    id: neighborId,
                    coordinate: neighborCoord,
                    pinStyle: .contactRepeater,
                    label: name,
                    isClusterable: false,
                    hopIndex: nil,
                    badgeText: nil
                ))

                // Midpoint for the SNR label
                let midLat = (centerCoord.latitude + neighborCoord.latitude) / 2.0
                let midLon = (centerCoord.longitude + neighborCoord.longitude) / 2.0
                let midCoord = CLLocationCoordinate2D(latitude: midLat, longitude: midLon)
                
                points.append(MapPoint(
                    id: UUID(),
                    coordinate: midCoord,
                    pinStyle: .badge,
                    label: nil,
                    isClusterable: false,
                    hopIndex: nil,
                    badgeText: String(format: "%.1f dB", neighbor.snr)
                ))

                let lineStyle: MapLine.LineStyle
                if neighbor.snr > 10 {
                    lineStyle = .traceGood
                } else if neighbor.snr > 0 {
                    lineStyle = .traceMedium
                } else {
                    lineStyle = .traceWeak
                }

                lines.append(MapLine(
                    id: neighbor.publicKeyPrefix.hexString,
                    coordinates: [centerCoord, neighborCoord],
                    style: lineStyle,
                    opacity: 1.0,
                    pathIndex: nil
                ))
            }
        }

        return (points, lines)
    }

    var body: some View {
        let data = mapData

        MC1MapView(
            points: data.points,
            lines: data.lines,
            mapStyle: mapStyleSelection,
            isDarkMode: colorScheme == .dark,
            isOffline: !appState.offlineMapService.isNetworkAvailable,
            showLabels: true,
            showsUserLocation: true,
            isInteractive: true,
            showsScale: true,
            isNorthLocked: false,
            cameraRegion: $cameraRegion,
            cameraRegionVersion: cameraRegionVersion,
            onPointTap: { _, _ in },
            onMapTap: { _ in },
            onCameraRegionChange: { _ in },
            isStyleLoaded: $isStyleLoaded
        )
        .overlay {
            if !isStyleLoaded {
                ProgressView()
                    .scaleEffect(1.5)
            }
        }
        .ignoresSafeArea(edges: .bottom)
        .onAppear {
            if repeaterSession.latitude != 0 && repeaterSession.longitude != 0 {
                cameraRegion = MKCoordinateRegion(
                    center: CLLocationCoordinate2D(latitude: repeaterSession.latitude, longitude: repeaterSession.longitude),
                    span: MKCoordinateSpan(latitudeDelta: 0.1, longitudeDelta: 0.1)
                )
                cameraRegionVersion += 1
            }
        }
        .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.neighborMapTitle)
        .navigationBarTitleDisplayMode(.inline)
    }
}
