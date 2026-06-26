import SwiftUI
import MapKit
import MC1Services

/// ViewModel for map contact locations
@Observable
@MainActor
final class MapViewModel {

    // MARK: - Properties

    /// All contacts with valid locations
    var contactsWithLocation: [ContactDTO] = []

    /// Map points derived from contacts — stored to avoid reallocation on every body eval.
    private(set) var mapPoints: [MapPoint] = []

    /// A user-dropped pin from a chat coordinate tap. Folded into `mapPoints` so it
    /// survives contact refreshes. Exactly one exists at a time (single-pin invariant).
    private(set) var focusedPin: MapPoint?

    /// Loading state
    var isLoading = false

    /// Error message if any
    var errorMessage: String?

    /// Camera region for map centering
    var cameraRegion: MKCoordinateRegion?

    /// Version counter for the camera region, incremented to signal a new camera target
    private(set) var cameraRegionVersion = 0

    /// Whether the map bearing is locked to true north
    var isNorthLocked = false

    /// Whether the layers menu is showing
    var showingLayersMenu = false

    // MARK: - Dependencies

    private var dataStoreProvider: @MainActor () -> PersistenceStore? = { nil }
    private var radioIDProvider: @MainActor () -> UUID? = { nil }

    private var dataStore: PersistenceStore? { dataStoreProvider() }
    private var radioID: UUID? { radioIDProvider() }

    // MARK: - Initialization

    /// Stable id so the dropped pin does not churn across rebuilds and `onPointTap` can identify it.
    private static let focusedPinID = UUID()

    /// Span for "exactly here" framing (about 1 km across).
    private static let focusSpan = 0.01

    init() {}

    /// Configure with the data store and radio this view model uses; a provider returning nil mirrors a disconnected state.
    func configure(
        dataStore: @escaping @MainActor () -> PersistenceStore?,
        radioID: @escaping @MainActor () -> UUID?
    ) {
        dataStoreProvider = dataStore
        radioIDProvider = radioID
    }

    // MARK: - Load Contacts

    /// Load contacts with valid locations from the database
    func loadContactsWithLocation() async {
        guard let dataStore, let radioID else { return }

        isLoading = true
        errorMessage = nil

        do {
            let allContacts = try await dataStore.fetchContacts(radioID: radioID)
            contactsWithLocation = allContacts.filter(\.hasLocation)
            rebuildMapPoints()
        } catch {
            errorMessage = error.userFacingMessage
        }

        isLoading = false
    }

    // MARK: - Map Points

    private func rebuildMapPoints() {
        mapPoints = contactsWithLocation.map { contact in
            MapPoint(
                id: contact.id,
                coordinate: contact.coordinate,
                pinStyle: contact.type.pinStyle,
                label: contact.displayName,
                isClusterable: true,
                hopIndex: nil,
                badgeText: nil
            )
        }
        if let focusedPin {
            mapPoints.append(focusedPin)
        }
    }

    // MARK: - Map Interaction

    func setCameraRegion(_ region: MKCoordinateRegion?) {
        cameraRegion = region
        cameraRegionVersion += 1
    }

    /// Drop a distinct pin at `coordinate`, fold it into `mapPoints`, and center the camera on it.
    func focusOnCoordinate(_ coordinate: CLLocationCoordinate2D) {
        focusedPin = MapPoint(
            id: Self.focusedPinID,
            coordinate: coordinate,
            pinStyle: .droppedPin,
            label: nil,
            isClusterable: false,
            hopIndex: nil,
            badgeText: nil
        )
        rebuildMapPoints()
        let span = MKCoordinateSpan(latitudeDelta: Self.focusSpan, longitudeDelta: Self.focusSpan)
        setCameraRegion(MKCoordinateRegion(center: coordinate, span: span))
    }

    /// Remove the dropped pin.
    func clearFocusedPin() {
        focusedPin = nil
        rebuildMapPoints()
    }

    /// Center map to show all contacts
    func centerOnAllContacts() {
        guard !contactsWithLocation.isEmpty else {
            cameraRegion = nil
            return
        }

        let coordinates = contactsWithLocation.map(\.coordinate)
        setCameraRegion(coordinates.boundingRegion())
    }

    /// On entering the map, restore the user's saved camera if present, otherwise frame all
    /// contacts. A focus or dropped-pin target sets `cameraRegion` synchronously before this
    /// runs, so a non-nil region or an existing pin means a target already owns the framing.
    func applyInitialCamera(saved: MKCoordinateRegion?, hasPendingFocus: Bool) {
        guard !hasPendingFocus, focusedPin == nil, cameraRegion == nil else { return }
        if let saved {
            setCameraRegion(saved)
        } else {
            centerOnAllContacts()
        }
    }
}
