import Testing
import Foundation
import CoreLocation
import MC1Services
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
        viewModel.configure(dataStore: dataStore, radioID: radioID)
        viewModel.focusOnCoordinate(CLLocationCoordinate2D(latitude: 10, longitude: 20))

        await viewModel.loadContactsWithLocation()

        #expect(viewModel.mapPoints.contains { $0.pinStyle == .droppedPin }, "Refresh must not wipe the dropped pin")
        #expect(viewModel.mapPoints.contains { $0.pinStyle != .droppedPin }, "The contact pin should also be present")
    }
}
