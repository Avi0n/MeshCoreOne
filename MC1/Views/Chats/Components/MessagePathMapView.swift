import CoreLocation
import MapKit
import MC1Services
import SwiftUI

struct MessagePathMapView: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme

    let message: MessageDTO
    let pathViewModel: MessagePathViewModel

    @State private var cameraRegion: MKCoordinateRegion? = nil
    @State private var cameraRegionVersion = 0
    @State private var mapStyle: MapStyleSelection = .standard
    @State private var locatedNodes: [(point: MapPoint, coordinate: CLLocationCoordinate2D)] = []

    private var mapPoints: [MapPoint] { locatedNodes.map(\.point) }

    private var mapLines: [MapLine] {
        let coords = locatedNodes.map(\.coordinate)
        guard coords.count >= 2 else { return [] }
        return [MapLine(id: "message-path", coordinates: coords, style: .messagePath, opacity: 1.0)]
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
                            showLabels: true,
                            showsUserLocation: false,
                            isInteractive: true,
                            showsScale: true,
                            cameraRegion: $cameraRegion,
                            cameraRegionVersion: cameraRegionVersion,
                            onPointTap: nil,
                            onMapTap: nil,
                            onCameraRegionChange: { cameraRegion = $0 }
                        )
                        .ignoresSafeArea()

                        Button {
                            mapStyle = mapStyle == .standard ? .satellite : .standard
                        } label: {
                            Image(systemName: mapStyle == .standard ? "globe.americas.fill" : "map")
                                .font(.body.weight(.medium))
                                .padding(10)
                                .background(.regularMaterial, in: .circle)
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle(L10n.Chats.Chats.Path.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.Localizable.Common.done) { dismiss() }
                }
            }
            .onAppear {
                locatedNodes = buildLocatedNodes()
                let coords = locatedNodes.map(\.coordinate)
                if coords.count == 1 {
                    cameraRegion = MKCoordinateRegion(
                        center: coords[0],
                        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
                    )
                } else if let region = coords.boundingRegion(paddingMultiplier: 2.5) {
                    cameraRegion = region
                }
                cameraRegionVersion = 1
            }
        }
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

        // Repeater hops
        if let pathNodes = message.pathNodes {
            let size = message.pathHashSize
            let hops = stride(from: 0, to: pathNodes.count, by: size).map { start -> Data in
                Data(pathNodes[start..<min(start + size, pathNodes.count)])
            }

            for (index, hashBytes) in hops.enumerated() {
                let hopNumber = index + 1
                let resolvedContact: (any RepeaterResolvable)? = RepeaterResolver.bestMatch(for: hashBytes, in: pathViewModel.repeaters, userLocation: appState.bestAvailableLocation)
                let resolvedNode: (any RepeaterResolvable)? = RepeaterResolver.bestMatch(for: hashBytes, in: pathViewModel.discoveredRepeaters, userLocation: appState.bestAvailableLocation)
                let r: (any RepeaterResolvable)? = resolvedContact ?? resolvedNode
                if let r, r.hasLocation {
                    let coord = CLLocationCoordinate2D(latitude: r.latitude, longitude: r.longitude)
                    nodes.append((MapPoint(
                        id: UUID(),
                        coordinate: coord,
                        pinStyle: .repeaterRingWhite,
                        label: r.resolvableName,
                        isClusterable: false,
                        hopIndex: hopNumber,
                        badgeText: nil
                    ), coord))
                }
            }
        }

        // Receiver (this device)
        let receiverLocation: CLLocation?
        if let device = appState.connectedDevice, device.hasLocation {
            receiverLocation = CLLocation(latitude: device.latitude, longitude: device.longitude)
        } else {
            receiverLocation = appState.bestAvailableLocation
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
