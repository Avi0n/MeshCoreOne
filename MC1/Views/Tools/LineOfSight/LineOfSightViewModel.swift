import CoreLocation
import MapKit
import MC1Services
import SwiftUI
import os.log

private let logger = Logger(subsystem: "com.mc1", category: "LineOfSight")

// MARK: - Point Identification

/// Identifies which point for editing in the UI
enum PointID: Hashable {
    case pointA
    case pointB
    case repeater
}

// MARK: - PointID Identifiable Conformance

extension PointID: Identifiable {
    var id: Self { self }
}

// MARK: - Repeater Point

/// A repeater point for relay analysis.
/// Can be on-path (from slider, uses cached profile) or off-path (relocated, needs fresh profiles).
struct RepeaterPoint: Equatable {
    /// The repeater's location
    var coordinate: CLLocationCoordinate2D

    /// Ground elevation at coordinate (fetched async)
    var groundElevation: Double?

    /// Additional height above ground in meters
    var additionalHeight: Double

    /// True if repeater is on the A-B path (from slider), false if relocated off-path
    var isOnPath: Bool

    /// Path fraction (only meaningful when isOnPath is true)
    /// Used for terrain profile slider positioning
    var pathFraction: Double {
        didSet {
            let clamped = pathFraction.clamped(to: 0.05...0.95)
            if clamped != pathFraction { pathFraction = clamped }
        }
    }

    init(coordinate: CLLocationCoordinate2D, groundElevation: Double? = nil, additionalHeight: Double = 10, isOnPath: Bool = true, pathFraction: Double = 0.5) {
        self.coordinate = coordinate
        self.groundElevation = groundElevation
        self.additionalHeight = additionalHeight
        self.isOnPath = isOnPath
        self.pathFraction = pathFraction.clamped(to: 0.05...0.95)
    }

    static func == (lhs: RepeaterPoint, rhs: RepeaterPoint) -> Bool {
        lhs.coordinate.latitude == rhs.coordinate.latitude &&
        lhs.coordinate.longitude == rhs.coordinate.longitude &&
        lhs.groundElevation == rhs.groundElevation &&
        lhs.additionalHeight == rhs.additionalHeight &&
        lhs.isOnPath == rhs.isOnPath &&
        lhs.pathFraction == rhs.pathFraction
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

// MARK: - Selected Point

/// A selected point for line of sight analysis
struct SelectedPoint: Identifiable, Equatable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
    let contact: ContactDTO?
    var groundElevation: Double?
    var additionalHeight: Double = 7

    var totalHeight: Double? {
        groundElevation.map { $0 + additionalHeight }
    }

    var displayName: String {
        contact?.displayName ?? L10n.Tools.Tools.LineOfSight.droppedPin
    }

    var isLoadingElevation: Bool {
        groundElevation == nil
    }

    static func == (lhs: SelectedPoint, rhs: SelectedPoint) -> Bool {
        lhs.id == rhs.id &&
        lhs.additionalHeight == rhs.additionalHeight &&
        lhs.groundElevation == rhs.groundElevation
    }
}

// MARK: - Analysis Status

/// Current status of path analysis
enum AnalysisStatus: Equatable {
    case idle
    case result(PathAnalysisResult)
    case relayResult(RelayPathAnalysisResult)
    case error(String)
}

// MARK: - Repeater Selection Info

/// Pre-computed selection state for a repeater annotation
struct LOSRepeaterSelectionInfo {
    let selectedAs: PointID?
}

// MARK: - View Model

@Observable
@MainActor
final class LineOfSightViewModel {

    // MARK: - Stable Map IDs

    let pointAMapID = UUID()
    let pointBMapID = UUID()
    let repeaterTargetMapID = UUID()

    // MARK: - Point Selection State

    var pointA: SelectedPoint? {
        didSet { rebuildSelectionState() }
    }
    var pointB: SelectedPoint? {
        didSet { rebuildSelectionState() }
    }
    var relocatingPoint: PointID? {
        didSet { rebuildMapLines() }
    }
    var shouldAutoZoomOnNextResult = false

    // MARK: - Camera State (MKMapView)

    var cameraRegion: MKCoordinateRegion?
    private(set) var cameraRegionVersion = 0

    // MARK: - RF Parameters

    /// Operating frequency in MHz - call `commitFrequencyChange()` after editing
    var frequencyMHz: Double = 906.0

    /// Refraction k-factor - auto-triggers re-analysis on change
    var refractionK: Double = 1.0 {
        didSet {
            if oldValue != refractionK {
                reanalyzeWithCachedProfileIfNeeded()
            }
        }
    }

    /// Commits frequency change and triggers re-analysis with cached profile
    func commitFrequencyChange() {
        reanalyzeWithCachedProfileIfNeeded()
    }

    // MARK: - Frequency Parsing

    /// Parses a user-entered frequency string into a positive MHz value.
    ///
    /// Accepts both "." and "," as the decimal separator so comma-decimal
    /// locales round-trip, and parses with a fixed separator rather than the
    /// current locale's. Returns nil for empty, unparseable, or non-positive
    /// input so callers can ignore it instead of feeding analysis a bad value.
    func parseFrequency(_ text: String) -> Double? {
        let normalized = text.replacing(",", with: ".")
        guard let value = Double(normalized), value > 0 else { return nil }
        return value
    }

    /// Renders a frequency value for editing using a locale-stable format,
    /// so the displayed text always round-trips back through `parseFrequency`.
    func formatFrequencyForEditing(_ value: Double) -> String {
        if value.truncatingRemainder(dividingBy: 1) == 0 {
            return String(Int(value))
        }
        return String(format: "%.1f", value)
    }

    // MARK: - Map Display State

    var isNorthLocked = false
    var showLabels: Bool = true {
        didSet { rebuildMapPoints() }
    }
    private(set) var mapPoints: [MapPoint] = []
    private(set) var mapLines: [MapLine] = []

    // MARK: - Repeaters State

    private(set) var repeatersWithLocation: [ContactDTO] = [] {
        didSet { rebuildSelectionState() }
    }

    // MARK: - Repeater State

    /// The active repeater point (nil when not in use)
    var repeaterPoint: RepeaterPoint? {
        didSet {
            rebuildMapPoints()
            rebuildMapLines()
        }
    }

    /// Whether repeater row should be visible (analysis shows marginal or worse)
    var shouldShowRepeaterRow: Bool {
        // Always show if repeater exists (even after relocation clears results)
        if repeaterPoint != nil {
            return true
        }
        // Show placeholder when direct analysis is marginal or worse
        return shouldShowRepeaterPlaceholder
    }

    /// Whether to show the "Add Repeater" placeholder (no repeater exists, but analysis suggests one would help)
    var shouldShowRepeaterPlaceholder: Bool {
        guard case .result(let result) = analysisStatus else {
            return false
        }
        // Only show if there are obstruction points (required by addRepeater())
        return result.clearanceStatus != .clear && !result.obstructionPoints.isEmpty
    }

    /// Ground elevation at repeater position.
    /// For on-path: interpolated from cached A→B profile.
    /// For off-path: uses the fetched elevation stored in repeaterPoint.
    var repeaterGroundElevation: Double? {
        guard let repeaterPoint else { return nil }
        if repeaterPoint.isOnPath {
            return elevationAt(pathFraction: repeaterPoint.pathFraction)
        } else {
            return repeaterPoint.groundElevation
        }
    }

    /// Path fraction for repeater visualization in terrain profile
    /// For on-path: uses pathFraction directly
    /// For off-path: computes from A→R distance / total distance
    var repeaterVisualizationPathFraction: Double? {
        guard let repeaterPoint else { return nil }
        if repeaterPoint.isOnPath {
            return repeaterPoint.pathFraction
        }
        // For off-path, compute from stored profiles
        guard let arLast = elevationProfileAR.last,
              let rbLast = elevationProfileRB.last,
              rbLast.distanceFromAMeters > 0 else { return nil }
        return arLast.distanceFromAMeters / rbLast.distanceFromAMeters
    }

    /// Segment A→R distance in meters (nil when on-path or no repeater)
    var segmentARDistanceMeters: Double? {
        guard let repeaterPoint, !repeaterPoint.isOnPath else { return nil }
        guard case .relayResult(let result) = analysisStatus else { return nil }
        return result.segmentAR.distanceMeters
    }

    /// Segment R→B distance in meters (nil when on-path or no repeater)
    var segmentRBDistanceMeters: Double? {
        guard let repeaterPoint, !repeaterPoint.isOnPath else { return nil }
        guard case .relayResult(let result) = analysisStatus else { return nil }
        return result.segmentRB.distanceMeters
    }

    // MARK: - Analysis State

    private(set) var analysisStatus: AnalysisStatus = .idle {
        didSet {
            rebuildMapPoints()
            rebuildMapLines()
        }
    }
    private(set) var isAnalyzing = false
    private(set) var elevationProfile: [ElevationSample] = []

    /// Profile samples for primary segment (A→B or A→R when repeater active)
    private(set) var profileSamples: [ProfileSample] = []

    /// Profile samples for R→B segment (empty when no repeater)
    private(set) var profileSamplesRB: [ProfileSample] = []

    /// Elevation profile A→R for off-path repeater
    private(set) var elevationProfileAR: [ElevationSample] = []

    /// Elevation profile R→B for off-path repeater
    private(set) var elevationProfileRB: [ElevationSample] = []

    /// Tracks whether any point elevation fetch failed (using sea level fallback)
    private(set) var elevationFetchFailed = false

    // MARK: - Task Management

    private var analysisTask: Task<Void, Never>?
    private var pointAElevationTask: Task<Void, Never>?
    private var pointBElevationTask: Task<Void, Never>?
    private var repeaterElevationTask: Task<Void, Never>?

    isolated deinit {
        analysisTask?.cancel()
        pointAElevationTask?.cancel()
        pointBElevationTask?.cancel()
        repeaterElevationTask?.cancel()
    }

    // MARK: - Dependencies

    private let elevationService: ElevationServiceProtocol
    private var dataStore: (any PersistenceStoreProtocol)?
    private var radioID: UUID?

    // MARK: - Computed Properties

    var canAnalyze: Bool {
        pointA?.groundElevation != nil && pointB?.groundElevation != nil
    }

    /// Pre-computed selection state for all repeaters. Rebuilt via `rebuildSelectionState()`.
    private(set) var selectionState: [UUID: LOSRepeaterSelectionInfo] = [:]

    private func rebuildSelectionState() {
        var result = [UUID: LOSRepeaterSelectionInfo]()
        result.reserveCapacity(repeatersWithLocation.count)

        let pointAContactID = pointA?.contact?.id
        let pointBContactID = pointB?.contact?.id

        for contact in repeatersWithLocation {
            let selectedAs: PointID?
            if contact.id == pointAContactID {
                selectedAs = .pointA
            } else if contact.id == pointBContactID {
                selectedAs = .pointB
            } else {
                selectedAs = nil
            }
            result[contact.id] = LOSRepeaterSelectionInfo(selectedAs: selectedAs)
        }
        selectionState = result
        rebuildMapPoints()
        rebuildMapLines()
    }

    // MARK: - Map Data Rebuild

    func rebuildMapPoints() {
        var points: [MapPoint] = []

        for repeater in repeatersWithLocation {
            let selectedAs = selectionState[repeater.id]?.selectedAs
            let style: MapPoint.PinStyle = switch selectedAs {
            case .pointA: .repeaterRingBlue
            case .pointB: .repeaterRingGreen
            case .repeater, nil: .repeater
            }
            points.append(MapPoint(
                id: repeater.id,
                coordinate: repeater.coordinate,
                pinStyle: style,
                label: showLabels ? repeater.displayName : nil,
                isClusterable: selectedAs == nil,
                hopIndex: nil,
                badgeText: nil
            ))
        }

        if let pointA, pointA.contact == nil {
            points.append(MapPoint(
                id: pointAMapID,
                coordinate: pointA.coordinate,
                pinStyle: .pointA,
                label: nil,
                isClusterable: false,
                hopIndex: nil,
                badgeText: nil
            ))
        }

        if let pointB, pointB.contact == nil {
            points.append(MapPoint(
                id: pointBMapID,
                coordinate: pointB.coordinate,
                pinStyle: .pointB,
                label: nil,
                isClusterable: false,
                hopIndex: nil,
                badgeText: nil
            ))
        }

        if let target = repeaterPoint {
            points.append(MapPoint(
                id: repeaterTargetMapID,
                coordinate: target.coordinate,
                pinStyle: .crosshair,
                label: nil,
                isClusterable: false,
                hopIndex: nil,
                badgeText: nil
            ))
        }

        if repeaterPoint == nil,
           case .result(let result) = analysisStatus,
           result.clearanceStatus != .clear {
            for obstruction in result.peakObstructionPerRegion {
                let pathFraction = obstruction.distanceFromAMeters / result.distanceMeters
                if let coordinate = coordinateAt(pathFraction: pathFraction) {
                    points.append(MapPoint(
                        id: obstruction.id,
                        coordinate: coordinate,
                        pinStyle: .obstruction,
                        label: nil,
                        isClusterable: false,
                        hopIndex: nil,
                        badgeText: nil
                    ))
                }
            }
        }

        mapPoints = points
    }

    func rebuildMapLines() {
        guard let a = pointA?.coordinate,
              let b = pointB?.coordinate else {
            mapLines = []
            return
        }

        let activeOpacity = 0.7
        let dimOpacity = 0.3

        if let r = repeaterPoint?.coordinate {
            let opacityAR = relocatingPoint == .pointA ? dimOpacity : activeOpacity
            let opacityRB = relocatingPoint == .pointB ? dimOpacity : activeOpacity
            mapLines = [
                MapLine(id: "los-ar", coordinates: [a, r],
                        style: .los, opacity: relocatingPoint == .repeater ? dimOpacity : opacityAR),
                MapLine(id: "los-rb", coordinates: [r, b],
                        style: .los, opacity: relocatingPoint == .repeater ? dimOpacity : opacityRB)
            ]
        } else {
            let opacity = relocatingPoint != nil ? dimOpacity : activeOpacity
            mapLines = [MapLine(id: "los-ab", coordinates: [a, b], style: .los, opacity: opacity)]
        }
    }

    // MARK: - Camera Methods

    func centerOnAllRepeaters() {
        let coordinates = repeatersWithLocation.map(\.coordinate)
        setCameraRegion(fitting: coordinates)
    }

    func zoomToShowBothPoints() {
        guard let pointA, let pointB else { return }
        setCameraRegion(fitting: [pointA.coordinate, pointB.coordinate])
    }

    func setCameraRegion(_ region: MKCoordinateRegion) {
        cameraRegion = region
        cameraRegionVersion += 1
    }

    private func setCameraRegion(fitting coordinates: [CLLocationCoordinate2D]) {
        guard let region = coordinates.boundingRegion() else { return }
        setCameraRegion(region)
    }

    /// Returns the elevation profile to display in terrain visualization.
    /// For on-path or no repeater: returns cached A-B profile.
    /// For off-path: returns concatenated A→R→B profiles.
    var terrainElevationProfile: [ElevationSample] {
        guard let repeaterPoint, !repeaterPoint.isOnPath else {
            return elevationProfile
        }

        // For off-path repeater, concatenate A→R and R→B profiles
        // Note: elevationProfileRB already has distances adjusted (offset by AR distance)
        // from analyzeWithRepeaterOffPath(), so we use it directly without re-adjusting
        guard !elevationProfileAR.isEmpty, !elevationProfileRB.isEmpty else {
            return elevationProfile  // Fallback if off-path profiles not yet loaded
        }

        // dropFirst() removes the duplicate point at R (already in elevationProfileAR)
        return elevationProfileAR + Array(elevationProfileRB.dropFirst())
    }

    // MARK: - Initialization

    init(elevationService: ElevationServiceProtocol = ElevationService()) {
        self.elevationService = elevationService
    }

    convenience init(preselectedContact: ContactDTO?) {
        self.init()
        if let contact = preselectedContact, contact.hasLocation {
            let coordinate = CLLocationCoordinate2D(
                latitude: contact.latitude,
                longitude: contact.longitude
            )
            setPointA(coordinate: coordinate, contact: contact)
        }
    }

    // MARK: - Configuration

    /// Configure with the data store and radio this view model uses; nil mirrors a disconnected state.
    /// Pass the connected device's frequency in kHz to seed the analysis frequency.
    func configure(
        dataStore: (any PersistenceStoreProtocol)?,
        radioID: UUID?,
        deviceFrequencyKHz: UInt32? = nil
    ) {
        self.dataStore = dataStore
        self.radioID = radioID

        // Device frequency is stored in kHz; analysis works in MHz
        if let deviceFrequencyKHz {
            self.frequencyMHz = Double(deviceFrequencyKHz) / 1000.0
        }
    }

    // MARK: - Load Repeaters

    func loadRepeaters() async {
        guard let dataStore, let radioID else { return }

        do {
            let allContacts = try await dataStore.fetchContacts(radioID: radioID)
            repeatersWithLocation = allContacts.filter { $0.hasLocation && $0.type == .repeater }
        } catch {
            logger.error("Failed to load repeaters: \(error.localizedDescription)")
        }
    }

    // MARK: - Point Selection

    /// Auto-assigns coordinate to A if empty, then B if A exists
    func selectPoint(at coordinate: CLLocationCoordinate2D, from contact: ContactDTO? = nil) {
        if pointA == nil {
            setPointA(coordinate: coordinate, contact: contact)
        } else if pointB == nil {
            setPointB(coordinate: coordinate, contact: contact)
        } else {
            // Both points set, replace B
            setPointB(coordinate: coordinate, contact: contact)
        }
    }

    func setPointA(coordinate: CLLocationCoordinate2D, contact: ContactDTO? = nil) {
        // Cancel any pending elevation fetch for point A
        pointAElevationTask?.cancel()
        pointAElevationTask = nil

        // Reset analysis when points change
        invalidateAnalysis()

        pointA = SelectedPoint(
            coordinate: coordinate,
            contact: contact,
            groundElevation: nil
        )

        // Fetch elevation asynchronously
        pointAElevationTask = Task {
            await fetchElevationForPointA()
        }
    }

    func setPointB(coordinate: CLLocationCoordinate2D, contact: ContactDTO? = nil) {
        // Check if B is same location as A
        if let pointA = pointA,
           pointA.coordinate.latitude == coordinate.latitude,
           pointA.coordinate.longitude == coordinate.longitude {
            logger.warning("Cannot set point B to same location as point A")
            return
        }

        // Cancel any pending elevation fetch for point B
        pointBElevationTask?.cancel()
        pointBElevationTask = nil

        // Reset analysis when points change
        invalidateAnalysis()

        pointB = SelectedPoint(
            coordinate: coordinate,
            contact: contact,
            groundElevation: nil
        )

        // Fetch elevation asynchronously
        pointBElevationTask = Task {
            await fetchElevationForPointB()
        }
    }

    // MARK: - Height Adjustment

    func updateAdditionalHeight(for point: PointID, meters: Double) {
        let clampedHeight = max(0.0, meters)

        switch point {
        case .pointA:
            guard pointA != nil else { return }
            pointA?.additionalHeight = clampedHeight
        case .pointB:
            guard pointB != nil else { return }
            pointB?.additionalHeight = clampedHeight
        case .repeater:
            // Repeater height is handled separately via updateRepeaterHeight
            updateRepeaterHeight(meters: clampedHeight)
            return
        }

        // Height change invalidates analysis
        invalidateAnalysis()
    }

    // MARK: - Contact Toggle Selection

    /// Toggle a contact as a selected point
    /// - If contact is already selected as A or B, clear that point
    /// - Otherwise, auto-assign to A (if empty) or B
    func toggleContact(_ contact: ContactDTO) {
        let coordinate = contact.coordinate

        // Check if already selected as point A
        if let pointA, pointA.contact?.id == contact.id {
            clearPointA()
            return
        }

        // Check if already selected as point B
        if let pointB, pointB.contact?.id == contact.id {
            clearPointB()
            return
        }

        // Auto-assign using existing logic
        selectPoint(at: coordinate, from: contact)
    }

    // MARK: - Clear Methods

    func clear() {
        pointAElevationTask?.cancel()
        pointBElevationTask?.cancel()
        analysisTask?.cancel()
        repeaterElevationTask?.cancel()

        pointAElevationTask = nil
        pointBElevationTask = nil
        analysisTask = nil
        repeaterElevationTask = nil

        pointA = nil
        pointB = nil
        repeaterPoint = nil
        elevationFetchFailed = false
        isAnalyzing = false
        analysisStatus = .idle
        elevationProfile = []
        elevationProfileAR = []
        elevationProfileRB = []
    }

    func clearPointA() {
        pointAElevationTask?.cancel()
        pointAElevationTask = nil

        pointA = nil
        repeaterPoint = nil
        invalidateAnalysis()
    }

    func clearPointB() {
        pointBElevationTask?.cancel()
        pointBElevationTask = nil

        pointB = nil
        repeaterPoint = nil
        invalidateAnalysis()
    }

    // MARK: - Repeater Methods

    /// Adds repeater at the worst obstruction point
    func addRepeater() {
        guard case .result(let result) = analysisStatus,
              let worstPoint = result.worstObstructionPoint else {
            return
        }

        let pathFraction = worstPoint.distanceFromAMeters / result.distanceMeters

        // Get coordinate and elevation from cached profile
        guard let coordinate = coordinateAt(pathFraction: pathFraction),
              let elevation = elevationAt(pathFraction: pathFraction) else { return }

        repeaterPoint = RepeaterPoint(
            coordinate: coordinate,
            groundElevation: elevation,
            additionalHeight: 10,
            isOnPath: true,
            pathFraction: pathFraction
        )
    }

    /// Updates repeater position along the path (for on-path repeaters)
    func updateRepeaterPosition(pathFraction: Double) {
        guard var repeater = repeaterPoint, repeater.isOnPath else { return }

        // Update path fraction and derive coordinate/elevation from cached profile
        repeater.pathFraction = pathFraction
        if let coordinate = coordinateAt(pathFraction: pathFraction) {
            repeater.coordinate = coordinate
        }
        if let elevation = elevationAt(pathFraction: pathFraction) {
            repeater.groundElevation = elevation
        }

        repeaterPoint = repeater
    }

    /// Updates repeater height above ground
    func updateRepeaterHeight(meters: Double) {
        guard repeaterPoint != nil else { return }
        repeaterPoint?.additionalHeight = max(0.0, meters)
    }

    /// Sets repeater to an off-path location
    /// - Parameter coordinate: The new coordinate for the repeater
    func setRepeaterOffPath(coordinate: CLLocationCoordinate2D) {
        let existingHeight = repeaterPoint?.additionalHeight ?? 10

        repeaterPoint = RepeaterPoint(
            coordinate: coordinate,
            groundElevation: nil,  // Will be fetched
            additionalHeight: existingHeight,
            isOnPath: false,
            pathFraction: 0.5  // Not used for off-path
        )

        // Invalidate cached off-path profiles (coordinates changed)
        elevationProfileAR = []
        elevationProfileRB = []

        // Fetch elevation for the new coordinate
        repeaterElevationTask?.cancel()
        repeaterElevationTask = Task {
            do {
                let elevation = try await elevationService.fetchElevation(at: coordinate)
                guard !Task.isCancelled else { return }
                repeaterPoint?.groundElevation = elevation
            } catch {
                guard !Task.isCancelled else { return }
                logger.error("Failed to fetch repeater elevation: \(error.localizedDescription)")
            }
        }
    }

    /// Removes repeater and reverts to single-path analysis
    func clearRepeater() {
        // Cancel any in-flight off-path elevation fetch so it can't write a stale
        // coordinate's elevation into a repeater added after this clear.
        repeaterElevationTask?.cancel()
        repeaterElevationTask = nil

        repeaterPoint = nil
        reanalyzeWithCachedProfileIfNeeded()
    }

    /// Analyzes the path with the current repeater position
    func analyzeWithRepeater() {
        guard let repeaterPoint,
              let pointA,
              let pointB else { return }

        if repeaterPoint.isOnPath {
            analyzeWithRepeaterOnPath()
        } else if !elevationProfileAR.isEmpty, !elevationProfileRB.isEmpty {
            // Cached profiles exist — no network fetch needed
            applyRelayAnalysis(
                profileAR: elevationProfileAR,
                profileRB: elevationProfileRB,
                pointAHeight: pointA.additionalHeight,
                repeaterHeight: repeaterPoint.additionalHeight,
                pointBHeight: pointB.additionalHeight,
                frequencyMHz: frequencyMHz,
                refractionK: refractionK
            )
        } else {
            analysisTask?.cancel()
            analysisTask = Task {
                await analyzeWithRepeaterOffPath()
            }
        }
    }

    /// On-path analysis using cached elevation profile
    private func analyzeWithRepeaterOnPath() {
        guard let repeaterPoint,
              let pointA,
              let pointB,
              elevationProfile.count >= 2 else { return }

        let pathFraction = repeaterPoint.pathFraction

        // Calculate split index
        let splitIndex = Int(pathFraction * Double(elevationProfile.count - 1))
        guard splitIndex > 0, splitIndex < elevationProfile.count - 1 else { return }

        applyRelayAnalysis(
            profileAR: Array(elevationProfile[0...splitIndex]),
            profileRB: Array(elevationProfile[splitIndex...]),
            pointAHeight: pointA.additionalHeight,
            repeaterHeight: repeaterPoint.additionalHeight,
            pointBHeight: pointB.additionalHeight,
            frequencyMHz: frequencyMHz,
            refractionK: refractionK
        )
    }

    /// Off-path analysis - fetches A→R and R→B profiles
    private func analyzeWithRepeaterOffPath() async {
        guard let repeaterPoint,
              let pointA,
              let pointB else { return }

        isAnalyzing = true

        do {
            let pointACoord = pointA.coordinate
            let repeaterCoord = repeaterPoint.coordinate
            let pointBCoord = pointB.coordinate

            // Fetch A→R profile
            let distanceAR = RFCalculator.distance(from: pointACoord, to: repeaterCoord)
            let sampleCountAR = ElevationService.optimalSampleCount(distanceMeters: distanceAR)
            let sampleCoordsAR = ElevationService.sampleCoordinates(
                from: pointACoord,
                to: repeaterCoord,
                sampleCount: sampleCountAR
            )
            // Fetch R→B profile (computed before async let to avoid capture issues)
            let distanceRB = RFCalculator.distance(from: repeaterCoord, to: pointBCoord)
            let sampleCountRB = ElevationService.optimalSampleCount(distanceMeters: distanceRB)
            let sampleCoordsRB = ElevationService.sampleCoordinates(
                from: repeaterCoord,
                to: pointBCoord,
                sampleCount: sampleCountRB
            )

            // Fetch both elevation profiles in parallel
            async let profileARTask = elevationService.fetchElevations(along: sampleCoordsAR)
            async let profileRBTask = elevationService.fetchElevations(along: sampleCoordsRB)
            let (profileAR, profileRB) = try await (profileARTask, profileRBTask)

            // A superseded task must not overwrite newer results
            if Task.isCancelled {
                isAnalyzing = false
                return
            }

            // Offset R→B profile distances to continue from A→R endpoint
            // (fetchElevations returns distances relative to segment start, not global A)
            let profileRBAdjusted = profileRB.map { sample in
                ElevationSample(
                    coordinate: sample.coordinate,
                    elevation: sample.elevation,
                    distanceFromAMeters: sample.distanceFromAMeters + distanceAR
                )
            }

            applyRelayAnalysis(
                profileAR: profileAR,
                profileRB: profileRBAdjusted,
                pointAHeight: pointA.additionalHeight,
                repeaterHeight: repeaterPoint.additionalHeight,
                pointBHeight: pointB.additionalHeight,
                frequencyMHz: frequencyMHz,
                refractionK: refractionK
            )

            // Store profiles for terrain visualization
            elevationProfileAR = profileAR
            elevationProfileRB = profileRBAdjusted

            isAnalyzing = false

        } catch {
            isAnalyzing = false
            // A cancelled fetch is not a user-facing analysis failure
            if Task.isCancelled { return }
            analysisStatus = .error(error.localizedDescription)
            logger.error("Off-path analysis failed: \(error.localizedDescription)")
        }
    }

    /// Shared relay analysis: analyzes both segments and updates profile samples and status
    private func applyRelayAnalysis(
        profileAR: [ElevationSample],
        profileRB: [ElevationSample],
        pointAHeight: Double,
        repeaterHeight: Double,
        pointBHeight: Double,
        frequencyMHz: Double,
        refractionK: Double
    ) {
        let arResult = RFCalculator.analyzePathSegment(
            elevationProfile: profileAR[...],
            startHeightMeters: pointAHeight,
            endHeightMeters: repeaterHeight,
            frequencyMHz: frequencyMHz,
            refractionK: refractionK
        )

        let rbResult = RFCalculator.analyzePathSegment(
            elevationProfile: profileRB[...],
            startHeightMeters: repeaterHeight,
            endHeightMeters: pointBHeight,
            frequencyMHz: frequencyMHz,
            refractionK: refractionK
        )

        let relayResult = RelayPathAnalysisResult(
            segmentAR: SegmentAnalysisResult(
                startLabel: "A",
                endLabel: "R",
                clearanceStatus: arResult.clearanceStatus,
                distanceMeters: arResult.distanceMeters,
                worstClearancePercent: arResult.worstClearancePercent
            ),
            segmentRB: SegmentAnalysisResult(
                startLabel: "R",
                endLabel: "B",
                clearanceStatus: rbResult.clearanceStatus,
                distanceMeters: rbResult.distanceMeters,
                worstClearancePercent: rbResult.worstClearancePercent
            )
        )

        profileSamples = FresnelZoneRenderer.buildProfileSamples(
            from: profileAR,
            pointAHeight: pointAHeight,
            pointBHeight: repeaterHeight,
            frequencyMHz: frequencyMHz,
            refractionK: refractionK
        )
        profileSamplesRB = FresnelZoneRenderer.buildProfileSamples(
            from: profileRB,
            pointAHeight: repeaterHeight,
            pointBHeight: pointBHeight,
            frequencyMHz: frequencyMHz,
            refractionK: refractionK
        )

        analysisStatus = .relayResult(relayResult)
    }

    // MARK: - Analysis

    /// Clears analysis results without clearing points
    func clearAnalysisResults() {
        isAnalyzing = false
        analysisStatus = .idle
        shouldAutoZoomOnNextResult = false
    }

    func analyze() {
        guard let pointA = pointA,
              let pointB = pointB,
              pointA.groundElevation != nil,
              pointB.groundElevation != nil else {
            logger.warning("Cannot analyze: missing point elevations")
            return
        }

        // Cancel any existing analysis
        analysisTask?.cancel()

        isAnalyzing = true

        // Capture values for use in task
        let pointACoord = pointA.coordinate
        let pointBCoord = pointB.coordinate
        let pointAHeight = pointA.additionalHeight
        let pointBHeight = pointB.additionalHeight
        let freq = frequencyMHz
        let k = refractionK

        analysisTask = Task {
            do {
                let distance = RFCalculator.distance(from: pointACoord, to: pointBCoord)
                let sampleCount = ElevationService.optimalSampleCount(distanceMeters: distance)
                let sampleCoordinates = ElevationService.sampleCoordinates(
                    from: pointACoord,
                    to: pointBCoord,
                    sampleCount: sampleCount
                )
                let profile = try await elevationService.fetchElevations(along: sampleCoordinates)
                if Task.isCancelled { return }

                let result = await Task.detached {
                    RFCalculator.analyzePath(
                        elevationProfile: profile,
                        pointAHeightMeters: pointAHeight,
                        pointBHeightMeters: pointBHeight,
                        frequencyMHz: freq,
                        refractionK: k
                    )
                }.value

                if Task.isCancelled { return }

                elevationProfile = profile
                profileSamples = FresnelZoneRenderer.buildProfileSamples(
                    from: profile,
                    pointAHeight: pointAHeight,
                    pointBHeight: pointBHeight,
                    frequencyMHz: freq,
                    refractionK: k
                )
                profileSamplesRB = []
                isAnalyzing = false
                analysisStatus = .result(result)
                logger.info("Analysis complete: \(result.clearanceStatus.rawValue), \(result.distanceKm)km")

            } catch {
                if Task.isCancelled { return }
                isAnalyzing = false
                analysisStatus = .error(error.localizedDescription)
                logger.error("Analysis failed: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Private Methods

    /// Invalidates analysis results but preserves cached elevation profile
    /// Use when RF settings change (frequency, k-factor)
    private func invalidateAnalysisOnly() {
        analysisTask?.cancel()
        analysisTask = nil
        isAnalyzing = false
        analysisStatus = .idle
        shouldAutoZoomOnNextResult = false
    }

    /// Invalidates analysis and clears cached elevation profile
    /// Use when points change (requires new elevation data)
    private func invalidateAnalysis() {
        invalidateAnalysisOnly()
        repeaterElevationTask?.cancel()
        repeaterElevationTask = nil
        elevationProfile = []
        elevationProfileAR = []
        elevationProfileRB = []
        profileSamples = []
        profileSamplesRB = []
        elevationFetchFailed = false
        repeaterPoint = nil  // Repeater is invalid without profile
    }

    /// Re-runs analysis using cached elevation profile when RF settings change
    private func reanalyzeWithCachedProfileIfNeeded() {
        // Only re-analyze if we have a cached profile and both points.
        // Clear isAnalyzing on the bail path so a flag left set by a now-stale
        // analysis (this method runs from a mid-analysis RF-setting change) can't
        // strand the spinner.
        guard !elevationProfile.isEmpty,
              let pointA = pointA,
              let pointB = pointB,
              pointA.groundElevation != nil,
              pointB.groundElevation != nil else {
            isAnalyzing = false
            return
        }

        // If repeater is active, use relay analysis to preserve mode.
        // Clear isAnalyzing first: the on-path/cached relay paths resolve
        // synchronously, and the off-path path re-sets the flag itself.
        if repeaterPoint != nil {
            isAnalyzing = false
            analyzeWithRepeater()
            return
        }

        // Cancel any existing analysis; this task takes over the isAnalyzing flag
        analysisTask?.cancel()
        isAnalyzing = true

        // Capture values for use in task
        let profile = elevationProfile
        let pointAHeight = pointA.additionalHeight
        let pointBHeight = pointB.additionalHeight
        let freq = frequencyMHz
        let k = refractionK

        analysisTask = Task {
            defer { isAnalyzing = false }

            let result = await Task.detached {
                RFCalculator.analyzePath(
                    elevationProfile: profile,
                    pointAHeightMeters: pointAHeight,
                    pointBHeightMeters: pointBHeight,
                    frequencyMHz: freq,
                    refractionK: k
                )
            }.value

            if Task.isCancelled { return }

            profileSamples = FresnelZoneRenderer.buildProfileSamples(
                from: profile,
                pointAHeight: pointAHeight,
                pointBHeight: pointBHeight,
                frequencyMHz: freq,
                refractionK: k
            )
            profileSamplesRB = []
            analysisStatus = .result(result)
            logger.debug("Re-analyzed with cached profile: \(freq) MHz, k=\(k)")
        }
    }

    private func fetchElevationForPointA() async {
        guard let coordinate = pointA?.coordinate else { return }

        do {
            let elevation = try await elevationService.fetchElevation(at: coordinate)
            if Task.isCancelled { return }
            pointA?.groundElevation = elevation
            logger.debug("Point A elevation: \(elevation)m")
        } catch {
            if Task.isCancelled { return }
            logger.error("Failed to fetch point A elevation: \(error.localizedDescription)")
            // Set to 0 as fallback so analysis can proceed (sea level approximation)
            pointA?.groundElevation = 0
            elevationFetchFailed = true
        }
    }

    private func fetchElevationForPointB() async {
        guard let coordinate = pointB?.coordinate else { return }

        do {
            let elevation = try await elevationService.fetchElevation(at: coordinate)
            if Task.isCancelled { return }
            pointB?.groundElevation = elevation
            logger.debug("Point B elevation: \(elevation)m")
        } catch {
            if Task.isCancelled { return }
            logger.error("Failed to fetch point B elevation: \(error.localizedDescription)")
            // Set to 0 as fallback so analysis can proceed (sea level approximation)
            pointB?.groundElevation = 0
            elevationFetchFailed = true
        }
    }

    // MARK: - Elevation Interpolation

    /// Returns interpolation indices and factor for a given path fraction
    /// - Parameter pathFraction: Position along path (0.0 = A, 1.0 = B), clamped to valid range
    /// - Returns: Tuple of (lowerIndex, upperIndex, interpolationFactor) or nil if profile has fewer than 2 samples
    private func interpolationIndices(for pathFraction: Double) -> (lower: Int, upper: Int, t: Double)? {
        guard elevationProfile.count >= 2 else { return nil }

        let clamped = pathFraction.clamped(to: 0.0...1.0)
        let index = clamped * Double(elevationProfile.count - 1)
        let lowerIndex = Int(index)
        let upperIndex = min(lowerIndex + 1, elevationProfile.count - 1)
        let t = index - Double(lowerIndex)

        return (lowerIndex, upperIndex, t)
    }

    /// Interpolates ground elevation at a given path fraction
    /// - Parameter pathFraction: Position along path (0.0 = A, 1.0 = B), clamped to valid range
    /// - Returns: Interpolated ground elevation in meters, or nil if profile has fewer than 2 samples
    func elevationAt(pathFraction: Double) -> Double? {
        guard let indices = interpolationIndices(for: pathFraction) else { return nil }

        let lowerElevation = elevationProfile[indices.lower].elevation
        let upperElevation = elevationProfile[indices.upper].elevation

        return lowerElevation + indices.t * (upperElevation - lowerElevation)
    }

    /// Interpolates coordinate at a given path fraction
    /// - Parameter pathFraction: Position along path (0.0 = A, 1.0 = B), clamped to valid range
    /// - Returns: Interpolated coordinate, or nil if profile has fewer than 2 samples
    func coordinateAt(pathFraction: Double) -> CLLocationCoordinate2D? {
        guard let indices = interpolationIndices(for: pathFraction) else { return nil }

        let lower = elevationProfile[indices.lower].coordinate
        let upper = elevationProfile[indices.upper].coordinate

        return CLLocationCoordinate2D(
            latitude: lower.latitude + indices.t * (upper.latitude - lower.latitude),
            longitude: lower.longitude + indices.t * (upper.longitude - lower.longitude)
        )
    }

    // MARK: - Testing Helpers

    #if DEBUG
    /// Testing helper to set analysis status directly
    func setAnalysisStatusForTesting(_ result: PathAnalysisResult) {
        analysisStatus = .result(result)
    }

    /// Testing helper to set elevation profile directly
    func setElevationProfileForTesting(_ profile: [ElevationSample]) {
        elevationProfile = profile
    }
    #endif
}
