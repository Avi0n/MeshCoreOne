import CoreLocation
import MapKit
import SwiftUI
import MC1Services
import os.log

private let logger = Logger(subsystem: "com.mc1", category: "TracePathMap")

/// View model for map-specific state in trace path map view
@MainActor @Observable
final class TracePathMapViewModel {

    // MARK: - Map State

    var cameraRegion: MKCoordinateRegion?
    /// Incremented when code intentionally moves the camera (not from user gesture sync)
    private(set) var cameraRegionVersion = 0
    var showLabels: Bool = true {
        didSet { rebuildMapPoints() }
    }
    var isNorthLocked = false
    var showingLayersMenu: Bool = false

    /// Tracks whether initial centering on repeaters has been performed
    private(set) var hasInitiallyCenteredOnRepeaters = false

    // MARK: - Path Overlays

    private(set) var mapLines: [MapLine] = []
    private(set) var badgePoints: [MapPoint] = []
    private(set) var mapPoints: [MapPoint] = []

    // MARK: - Dependencies

    private weak var traceViewModel: TracePathViewModel?
    private var userLocation: CLLocation?
    private var lastRebuildLocation: CLLocation?

    // MARK: - Path State

    struct RepeaterPathInfo {
        let inPath: Bool
        let hopIndex: Int?
        let isLastHop: Bool
    }

    /// Pre-computed path membership for all repeaters, keyed by repeater ID.
    /// Stored to avoid reallocation on every body eval. Rebuilt via `rebuildPathState()`.
    private(set) var pathState: [UUID: RepeaterPathInfo] = [:]

    // MARK: - Computed Properties

    /// Repeaters and rooms to display on map
    var repeatersWithLocation: [ContactDTO] {
        traceViewModel?.availableNodes.filter { $0.hasLocation } ?? []
    }

    /// Whether a path has been built (at least one hop)
    var hasPath: Bool {
        !(traceViewModel?.outboundPath.isEmpty ?? true)
    }

    /// Whether trace can be run (when connected)
    var canRunTrace: Bool {
        traceViewModel?.canRunTraceWhenConnected ?? false
    }

    /// Whether trace is currently running
    var isRunning: Bool {
        traceViewModel?.isRunning ?? false
    }

    /// Whether a successful result exists that can be saved
    var canSave: Bool {
        traceViewModel?.canSavePath ?? false
    }

    /// Current trace result
    var result: TraceResult? {
        traceViewModel?.result
    }

    // MARK: - Configuration

    func configure(traceViewModel: TracePathViewModel, userLocation: CLLocation?) {
        self.traceViewModel = traceViewModel
        self.userLocation = userLocation
    }

    func updateUserLocation(_ location: CLLocation?) {
        self.userLocation = location

        // Only rebuild if the path is non-empty and user moved meaningfully
        guard traceViewModel?.outboundPath.isEmpty == false else { return }
        if let location, let last = lastRebuildLocation, location.distance(from: last) < 10 { return }
        lastRebuildLocation = location
        rebuildOverlays()
    }

    // MARK: - Path State Rebuild

    /// Rebuilds stored `pathState` and `mapPoints`. Call when path, available nodes, or user location changes.
    func rebuildPathState() {
        let repeaters = repeatersWithLocation

        var pathLookup: [UUID: (index: Int, isLast: Bool)] = [:]
        if let path = traceViewModel?.outboundPath {
            for (index, hop) in path.enumerated() {
                if let repeater = findRepeater(for: hop) {
                    pathLookup[repeater.id] = (index: index + 1, isLast: index == path.count - 1)
                }
            }
        }

        var state: [UUID: RepeaterPathInfo] = [:]
        state.reserveCapacity(repeaters.count)
        for repeater in repeaters {
            if let info = pathLookup[repeater.id] {
                state[repeater.id] = RepeaterPathInfo(inPath: true, hopIndex: info.index, isLastHop: info.isLast)
            } else {
                state[repeater.id] = RepeaterPathInfo(inPath: false, hopIndex: nil, isLastHop: false)
            }
        }
        pathState = state
        rebuildMapPoints(repeaters: repeaters)
    }

    private func rebuildMapPoints(repeaters: [ContactDTO]? = nil) {
        let nodes = repeaters ?? repeatersWithLocation
        var points: [MapPoint] = []
        for repeater in nodes {
            let info = pathState[repeater.id]
            let inPath = info?.inPath ?? false
            points.append(MapPoint(
                id: repeater.id,
                coordinate: repeater.coordinate,
                pinStyle: inPath ? .repeaterRingWhite : .repeater,
                label: showLabels ? repeater.displayName : nil,
                isClusterable: false,
                hopIndex: info?.hopIndex,
                badgeText: nil
            ))
        }
        points.append(contentsOf: badgePoints)
        mapPoints = points
    }

    // MARK: - Path Building

    /// Find the repeater or room for a hop using full public key or RepeaterResolver fallback.
    private func findRepeater(for hop: PathHop) -> ContactDTO? {
        RepeaterResolver.bestMatch(for: hop, in: traceViewModel?.availableNodes ?? [], userLocation: userLocation)
    }

    enum RepeaterTapResult {
        case added
        case removed
        case rejectedMiddleHop
        case ignored
    }

    /// Handle tap on a repeater, returns the result of the tap action
    @discardableResult
    func handleRepeaterTap(_ repeater: ContactDTO) -> RepeaterTapResult {
        guard let traceViewModel else { return .ignored }

        let info = pathState[repeater.id]
        let result: RepeaterTapResult
        if info?.isLastHop == true {
            if let lastIndex = traceViewModel.outboundPath.indices.last {
                traceViewModel.removeRepeater(at: lastIndex)
            }
            result = .removed
        } else if info?.inPath != true {
            traceViewModel.addNode(repeater)
            result = .added
        } else {
            result = .rejectedMiddleHop
        }

        rebuildOverlays()
        return result
    }

    /// Clear the path
    func clearPath() {
        traceViewModel?.clearPath()
        clearOverlays()
        rebuildPathState()
    }

    // MARK: - Trace Execution

    func runTrace() async {
        centerOnPath()
        traceViewModel?.batchEnabled = false
        await traceViewModel?.runTrace()
    }

    func savePath(name: String) async -> Bool {
        await traceViewModel?.savePath(name: name) ?? false
    }

    func generatePathName() -> String {
        traceViewModel?.generatePathName() ?? L10n.Contacts.Contacts.Trace.Map.defaultPathName
    }

    // MARK: - Overlay Management

    /// Rebuild map lines based on current path
    func rebuildOverlays() {
        clearOverlays()
        rebuildPathState()

        guard let traceViewModel,
              !traceViewModel.outboundPath.isEmpty else { return }

        var previousCoordinate: CLLocationCoordinate2D?
        if let userLocation {
            previousCoordinate = userLocation.coordinate
        }

        for (index, hop) in traceViewModel.outboundPath.enumerated() {
            guard let repeater = findRepeater(for: hop),
                  repeater.hasLocation else { continue }

            let hopCoordinate = CLLocationCoordinate2D(
                latitude: repeater.latitude,
                longitude: repeater.longitude
            )

            guard CLLocationCoordinate2DIsValid(hopCoordinate) else { continue }

            if let prevCoord = previousCoordinate, CLLocationCoordinate2DIsValid(prevCoord) {
                mapLines.append(MapLine(
                    id: "trace-\(index)",
                    coordinates: [prevCoord, hopCoordinate],
                    style: .traceUntraced,
                    opacity: 1.0,
                    pathIndex: index
                ))
            }

            previousCoordinate = hopCoordinate
        }
    }

    /// Update lines with trace results and add badge points at segment midpoints
    func updateOverlaysWithResults() {
        guard let result = traceViewModel?.result, result.success else { return }

        badgePoints.removeAll()

        var updatedLines: [MapLine] = []
        for line in mapLines {
            guard let pathIndex = line.pathIndex else {
                updatedLines.append(line)
                continue
            }
            let hopIndex = pathIndex + 1
            if hopIndex < result.hops.count {
                let hop = result.hops[hopIndex]
                let style = lineStyle(for: hop.snr)

                updatedLines.append(MapLine(
                    id: line.id,
                    coordinates: line.coordinates,
                    style: style,
                    opacity: 1.0,
                    pathIndex: pathIndex
                ))

                // Badge at midpoint
                if line.coordinates.count >= 2 {
                    let mid = CLLocationCoordinate2D(
                        latitude: (line.coordinates[0].latitude + line.coordinates[1].latitude) / 2,
                        longitude: (line.coordinates[0].longitude + line.coordinates[1].longitude) / 2
                    )
                    let distance = CLLocation(latitude: line.coordinates[0].latitude, longitude: line.coordinates[0].longitude)
                        .distance(from: CLLocation(latitude: line.coordinates[1].latitude, longitude: line.coordinates[1].longitude))
                    let distFormatted = Measurement(value: distance, unit: UnitLength.meters)
                        .formatted(.measurement(width: .abbreviated, usage: .road))
                    let snrFormatted = hop.snr.formatted(.number.precision(.fractionLength(1)))

                    badgePoints.append(MapPoint(
                        id: UUID(hopIndex: hopIndex),
                        coordinate: mid,
                        pinStyle: .badge,
                        label: nil,
                        isClusterable: false,
                        hopIndex: nil,
                        badgeText: "\(distFormatted) · \(snrFormatted) dB"
                    ))
                }
            } else {
                updatedLines.append(line)
            }
        }

        mapLines = updatedLines
        rebuildMapPoints()
    }

    // MARK: - Signal Quality

    private func lineStyle(for snr: Double?) -> MapLine.LineStyle {
        switch SNRQuality(snr: snr) {
        case .excellent, .good: .traceGood
        case .fair: .traceMedium
        case .poor: .traceWeak
        case .unknown: .traceUntraced
        }
    }

    /// Clear all overlays
    func clearOverlays() {
        mapLines.removeAll()
        badgePoints.removeAll()
    }

    // MARK: - Camera

    /// Center map on all path points
    func centerOnPath() {
        var coordinates: [CLLocationCoordinate2D] = []

        if let userLocation {
            coordinates.append(userLocation.coordinate)
        }

        for line in mapLines {
            coordinates.append(contentsOf: line.coordinates)
        }

        setCameraRegion(fitting: coordinates)
    }

    /// Center map to show all repeaters
    func centerOnAllRepeaters() {
        let repeaters = repeatersWithLocation
        guard !repeaters.isEmpty else {
            cameraRegion = nil
            return
        }

        let coordinates = repeaters.map(\.coordinate)
        setCameraRegion(fitting: coordinates)
        hasInitiallyCenteredOnRepeaters = true
    }

    /// Perform initial centering based on current state
    /// Centers on path if one exists, otherwise centers on all repeaters
    func performInitialCentering() {
        if hasPath {
            centerOnPathRepeaters()
        } else {
            centerOnAllRepeaters()
        }
    }

    /// Center map on path repeaters directly (doesn't depend on overlays)
    private func centerOnPathRepeaters() {
        guard let traceViewModel else {
            centerOnAllRepeaters()
            return
        }

        var coordinates: [CLLocationCoordinate2D] = []

        if let userLocation {
            coordinates.append(userLocation.coordinate)
        }

        for hop in traceViewModel.outboundPath {
            guard let repeater = findRepeater(for: hop),
                  repeater.hasLocation else {
                continue
            }

            let coord = CLLocationCoordinate2D(
                latitude: repeater.latitude,
                longitude: repeater.longitude
            )
            if CLLocationCoordinate2DIsValid(coord) {
                coordinates.append(coord)
            }
        }

        guard !coordinates.isEmpty else {
            centerOnAllRepeaters()
            return
        }

        setCameraRegion(fitting: coordinates)
        hasInitiallyCenteredOnRepeaters = true
    }

    func setCameraRegion(_ region: MKCoordinateRegion) {
        cameraRegion = region
        cameraRegionVersion += 1
    }

    private func setCameraRegion(fitting coordinates: [CLLocationCoordinate2D]) {
        guard let region = coordinates.boundingRegion() else { return }
        setCameraRegion(region)
    }
}

private extension UUID {
    /// Deterministic UUID for badge points keyed by hop index.
    init(hopIndex: Int) {
        let hex = String(hopIndex, radix: 16)
        let padded = String(repeating: "0", count: max(0, 12 - hex.count)) + hex
        self = UUID(uuidString: "00000000-0000-0000-0000-\(padded)") ?? UUID()
    }
}
