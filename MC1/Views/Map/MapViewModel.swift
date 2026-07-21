import MapKit
import MC1Services
import SwiftUI

/// ViewModel for map contact and discovered-node locations
@Observable
@MainActor
final class MapViewModel {
  // MARK: - Properties

  /// All contacts with valid locations
  var contactsWithLocation: [ContactDTO] = []

  /// Located discovered nodes not already present as contacts (public key).
  private(set) var discoveredWithLocation: [DiscoveredNodeDTO] = []

  /// Map points derived from contacts and discovered nodes — stored to avoid reallocation on every body eval.
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

  /// True when Center All should be enabled: any located contact or discovered pin.
  var hasPinsForCenterAll: Bool {
    !contactsWithLocation.isEmpty || !discoveredWithLocation.isEmpty
  }

  // MARK: - Dependencies

  private var dataStoreProvider: @MainActor () -> PersistenceStore? = { nil }
  private var radioIDProvider: @MainActor () -> UUID? = { nil }

  private var dataStore: PersistenceStore? {
    dataStoreProvider()
  }

  private var radioID: UUID? {
    radioIDProvider()
  }

  private static let reloadDebounce: Duration = .milliseconds(50)
  private var reloadTask: Task<Void, Never>?
  /// Latest include flag requested while a coalesced reload is pending.
  private var pendingIncludeDiscovered = false
  /// Bumped so an older in-flight load cannot overwrite a newer one.
  private var loadGeneration = 0

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

  // MARK: - Load Map Data

  /// Load located contacts and, when `includeDiscovered` is true, located
  /// discovered nodes not already present as contacts (public key).
  /// - Parameter showsLoadingChrome: true for first paint / user refresh only.
  ///   Coalesced live reloads pass false so the overlay and refresh spinner stay quiet.
  ///
  /// Loading chrome is owned by the current generation: start sets
  /// `isLoading = showsLoadingChrome`, stale early returns leave it alone, and only
  /// the current generation clears it on exit.
  func loadMapData(includeDiscovered: Bool, showsLoadingChrome: Bool = true) async {
    loadGeneration += 1
    let generation = loadGeneration

    guard let dataStore, let radioID else {
      errorMessage = nil
      isLoading = false
      clearMapPinData()
      return
    }
    // Silent loads clear a spinner left by a superseded chrome load.
    isLoading = showsLoadingChrome
    errorMessage = nil
    do {
      let allContacts = try await dataStore.fetchContacts(radioID: radioID)
      guard generation == loadGeneration else { return }

      let locatedContacts = allContacts.filter(\.hasLocation)

      let locatedDiscovered: [DiscoveredNodeDTO]
      if includeDiscovered {
        // Full-table fetch, then keep only plottable nodes not already contacts.
        let allDiscovered = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
        guard generation == loadGeneration else { return }
        let contactKeys = Set(allContacts.map(\.publicKey))
        locatedDiscovered = allDiscovered.filter { node in
          node.coordinate.isValidFix && !contactKeys.contains(node.publicKey)
        }
      } else {
        locatedDiscovered = []
      }

      guard generation == loadGeneration else { return }
      // Assign both arrays only after both fetches succeed so lookups and Center All
      // never disagree with mapPoints.
      contactsWithLocation = locatedContacts
      discoveredWithLocation = locatedDiscovered
      rebuildMapPoints()
    } catch {
      guard generation == loadGeneration else { return }
      // Keep pin store consistent with the toggle even when contact fetch fails.
      if !includeDiscovered, !discoveredWithLocation.isEmpty {
        discoveredWithLocation = []
        rebuildMapPoints()
      }
      errorMessage = error.userFacingMessage
    }
    if generation == loadGeneration {
      isLoading = false
    }
  }

  /// Schedules a debounced reload so bursts of version bumps trigger one load.
  /// Records the latest `includeDiscovered` so a toggle flip during the debounce
  /// window is not dropped.
  func scheduleCoalescedReload(includeDiscovered: Bool, showsLoadingChrome: Bool = false) {
    pendingIncludeDiscovered = includeDiscovered
    guard reloadTask == nil else { return }
    reloadTask = Task { @MainActor [weak self] in
      try? await Task.sleep(for: Self.reloadDebounce)
      guard let self, !Task.isCancelled else { return }
      self.reloadTask = nil
      let include = self.pendingIncludeDiscovered
      await self.loadMapData(
        includeDiscovered: include,
        showsLoadingChrome: showsLoadingChrome
      )
    }
  }

  /// Cancels any pending coalesced reload (e.g. before a user-driven refresh).
  func cancelPendingReload() {
    reloadTask?.cancel()
    reloadTask = nil
    loadGeneration += 1
  }

  private func clearMapPinData() {
    contactsWithLocation = []
    discoveredWithLocation = []
    rebuildMapPoints()
  }

  // MARK: - Map Points

  private func rebuildMapPoints() {
    var points: [MapPoint] = contactsWithLocation.map { contact in
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
    points += discoveredWithLocation.map { node in
      MapPoint(
        id: node.id,
        coordinate: node.coordinate,
        pinStyle: node.nodeType.pinStyle,
        label: node.name,
        isClusterable: true,
        hopIndex: nil,
        badgeText: nil
      )
    }
    if let focusedPin {
      points.append(focusedPin)
    }
    // Avoid force-assign when equal so MapLibre can skip O(n) GeoJSON rebuild.
    if points != mapPoints {
      mapPoints = points
    }
  }

  // MARK: - Lookup

  func contact(forPointID id: UUID) -> ContactDTO? {
    contactsWithLocation.first { $0.id == id }
  }

  func discovered(forPointID id: UUID) -> DiscoveredNodeDTO? {
    discoveredWithLocation.first { $0.id == id }
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

  /// Center map to show all located contacts and discovered pins.
  func centerOnAllContacts() {
    var coordinates = contactsWithLocation.map(\.coordinate)
    coordinates += discoveredWithLocation.map(\.coordinate)
    guard !coordinates.isEmpty else {
      cameraRegion = nil
      return
    }
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
