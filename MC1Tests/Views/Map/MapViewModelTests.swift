import Testing
import Foundation
import CoreLocation
import MapKit
@testable import MC1Services
@testable import MC1

@Suite("MapViewModel Focus Tests")
@MainActor
struct MapViewModelFocusTests {

    private static func makeLocatedContact(radioID: UUID) -> ContactDTO {
        ContactDTO(
            id: UUID(),
            radioID: radioID,
            publicKey: Data(repeating: 0xAA, count: 32),
            name: "Located",
            typeRawValue: 0x01,
            flags: 0,
            outPathLength: 0,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 37.0,
            longitude: -122.0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            unreadMentionCount: 0,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }

    @Test("focusOnCoordinate drops a pin and centers the camera")
    func focusDropsPinAndCenters() {
        let viewModel = MapViewModel()
        let coordinate = CLLocationCoordinate2D(latitude: 10, longitude: 20)
        let versionBefore = viewModel.cameraRegionVersion

        viewModel.focusOnCoordinate(coordinate)

        #expect(viewModel.focusedPin != nil)
        #expect(viewModel.mapPoints.contains { $0.pinStyle == .droppedPin })
        #expect(viewModel.cameraRegion?.center.latitude == 10)
        #expect(viewModel.cameraRegion?.center.longitude == 20)
        #expect(viewModel.cameraRegionVersion > versionBefore)
    }

    @Test("clearFocusedPin removes the dropped pin")
    func clearRemovesPin() {
        let viewModel = MapViewModel()
        viewModel.focusOnCoordinate(CLLocationCoordinate2D(latitude: 10, longitude: 20))

        viewModel.clearFocusedPin()

        #expect(viewModel.focusedPin == nil)
        #expect(!viewModel.mapPoints.contains { $0.pinStyle == .droppedPin })
    }

    @Test("focusOnCoordinate overwrites an existing dropped pin (single-pin invariant)")
    func focusOverwritesExistingPin() {
        let viewModel = MapViewModel()
        viewModel.focusOnCoordinate(CLLocationCoordinate2D(latitude: 10, longitude: 20))
        viewModel.focusOnCoordinate(CLLocationCoordinate2D(latitude: 30, longitude: 40))

        let droppedPins = viewModel.mapPoints.filter { $0.pinStyle == .droppedPin }
        #expect(droppedPins.count == 1)
        #expect(viewModel.focusedPin?.coordinate.latitude == 30)
    }

    @Test("loadContactsWithLocation preserves a previously dropped pin")
    func loadPreservesFocusedPin() async throws {
        let radioID = UUID()
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))

        let viewModel = MapViewModel()
        viewModel.configure(dataStore: { dataStore }, radioID: { radioID })
        viewModel.focusOnCoordinate(CLLocationCoordinate2D(latitude: 10, longitude: 20))

        await viewModel.loadContactsWithLocation()

        #expect(viewModel.mapPoints.contains { $0.pinStyle == .droppedPin }, "Refresh must not wipe the dropped pin")
        #expect(viewModel.mapPoints.contains { $0.pinStyle != .droppedPin }, "The contact pin should also be present")
    }

    // MARK: - applyInitialCamera

    private static func makeRegion(lat: CLLocationDegrees, lon: CLLocationDegrees) -> MKCoordinateRegion {
        MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: lat, longitude: lon),
            span: MKCoordinateSpan(latitudeDelta: 0.5, longitudeDelta: 0.5)
        )
    }

    @Test("applyInitialCamera restores a saved region on a fresh map")
    func applyInitialCameraRestoresSaved() {
        let viewModel = MapViewModel()
        let versionBefore = viewModel.cameraRegionVersion

        viewModel.applyInitialCamera(saved: Self.makeRegion(lat: 51, lon: 0), hasPendingFocus: false)

        #expect(viewModel.cameraRegion?.center.latitude == 51)
        #expect(viewModel.cameraRegion?.center.longitude == 0)
        #expect(viewModel.cameraRegionVersion > versionBefore)
    }

    @Test("applyInitialCamera frames all contacts when nothing is saved")
    func applyInitialCameraFramesAllWhenUnsaved() async throws {
        let radioID = UUID()
        let container = try PersistenceStore.createContainer(inMemory: true)
        let dataStore = PersistenceStore(modelContainer: container)
        try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))

        let viewModel = MapViewModel()
        viewModel.configure(dataStore: { dataStore }, radioID: { radioID })
        await viewModel.loadContactsWithLocation()

        viewModel.applyInitialCamera(saved: nil, hasPendingFocus: false)

        #expect(viewModel.cameraRegion != nil, "An unsaved fresh map frames all contacts")
    }

    @Test("applyInitialCamera leaves the camera unset when nothing is saved and there are no contacts")
    func applyInitialCameraEmptyMapStaysUnset() {
        let viewModel = MapViewModel()
        let versionBefore = viewModel.cameraRegionVersion

        viewModel.applyInitialCamera(saved: nil, hasPendingFocus: false)

        #expect(viewModel.cameraRegion == nil, "A cold open with no contacts has nothing to frame")
        #expect(viewModel.cameraRegionVersion == versionBefore, "The empty path must not bump the camera version")
    }

    @Test("applyInitialCamera defers to a pending focus target")
    func applyInitialCameraDefersToPendingFocus() {
        let viewModel = MapViewModel()

        viewModel.applyInitialCamera(saved: Self.makeRegion(lat: 51, lon: 0), hasPendingFocus: true)

        #expect(viewModel.cameraRegion == nil, "A pending focus owns the camera")
    }

    @Test("applyInitialCamera defers to an existing dropped pin")
    func applyInitialCameraDefersToFocusedPin() {
        let viewModel = MapViewModel()
        viewModel.focusOnCoordinate(CLLocationCoordinate2D(latitude: 10, longitude: 20))

        viewModel.applyInitialCamera(saved: Self.makeRegion(lat: 51, lon: 0), hasPendingFocus: false)

        #expect(viewModel.cameraRegion?.center.latitude == 10, "The dropped pin keeps the camera")
    }

    @Test("applyInitialCamera leaves an already-aimed camera untouched")
    func applyInitialCameraLeavesAimedCamera() {
        let viewModel = MapViewModel()
        viewModel.setCameraRegion(Self.makeRegion(lat: 1, lon: 2))

        viewModel.applyInitialCamera(saved: Self.makeRegion(lat: 51, lon: 0), hasPendingFocus: false)

        #expect(viewModel.cameraRegion?.center.latitude == 1, "An aimed camera is not overridden")
    }
}
