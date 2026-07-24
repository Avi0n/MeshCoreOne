import MapKit
import MC1Services
import SwiftUI

/// ViewModel for map contact and discovered-node locations
@Observable
@MainActor
final class MapViewModel {
  // MARK: - Properties

  /// Unfiltered located contacts (all types / favorites). Display pins apply filter.
  private(set) var allLocatedContacts: [ContactDTO] = []

  /// Unfiltered plottable discovered (valid fix, not already contacts).
  private(set) var allLocatedDiscovered: [DiscoveredNodeDTO] = []

  /// Filter-visible contacts shown as pins (callout lookup + Center All).
  private(set) var visibleContacts: [ContactDTO] = []

  /// Filter-visible discovered nodes shown as pins.
  private(set) var visibleDiscovered: [DiscoveredNodeDTO] = []

  /// Map points derived from visible contacts and discovered nodes — stored to avoid reallocation on every body eval.
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

  var hasPinsForCenterAll: Bool {
    !visibleContacts.isEmpty || !visibleDiscovered.isEmpty
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
  /// Latest filter requested while a coalesced reload is pending.
  private var pendingFilter = MapFilterState()
  /// Bumped so an older in-flight load cannot overwrite a newer one.
  private var loadGeneration = 0
  private var currentFilter = MapFilterState()
  /// After the first successful (or empty) load, type toggles re-filter without a full fetch.
  private(set) var hasCompletedInitialLoad = false
  /// Unit-test seam: next `loadMapData` throws after filter latches so the error path is covered.
  var simulateLoadFailureForTesting = false
  /// Unit-test seam: read `loadGeneration` so concurrent-load tests can wait until a load has entered.
  var loadGenerationForTesting: Int {
    loadGeneration
  }

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

  /// Load unfiltered located contacts and discovered rows, then apply `filter` for display pins.
  /// - Parameter showsLoadingChrome: true for first paint / user refresh only.
  ///   Coalesced live reloads pass false so the overlay and refresh spinner stay quiet.
  ///
  /// Loading chrome is owned by the current generation: start sets
  /// `isLoading = showsLoadingChrome`, stale early returns leave it alone, and only
  /// the current generation clears it on exit.
  func loadMapData(filter: MapFilterState, showsLoadingChrome: Bool = true) async {
    loadGeneration += 1
    let generation = loadGeneration
    let sanitized = filter.sanitized(for: .mainMap)
    // Latch so a later warm filter flip during this load wins at rebuild.
    pendingFilter = sanitized
    currentFilter = sanitized

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
      if simulateLoadFailureForTesting {
        simulateLoadFailureForTesting = false
        throw MapLoadSimulationError()
      }
      let allContacts = try await dataStore.fetchContacts(radioID: radioID)
      guard generation == loadGeneration else { return }

      let locatedContacts = allContacts.filter(\.hasLocation)

      // Always load discovered into the unfiltered cache so type/favorites re-filter is free.
      let allDiscovered = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
      guard generation == loadGeneration else { return }
      let contactKeys = Set(allContacts.map(\.publicKey))
      let locatedDiscovered = allDiscovered.filter { node in
        node.coordinate.isValidFix && !contactKeys.contains(node.publicKey)
      }

      guard generation == loadGeneration else { return }
      allLocatedContacts = locatedContacts
      allLocatedDiscovered = locatedDiscovered
      hasCompletedInitialLoad = true
      currentFilter = pendingFilter
      rebuildDisplayPins()
    } catch {
      guard generation == loadGeneration else { return }
      currentFilter = pendingFilter
      rebuildDisplayPins()
      errorMessage = error.userFacingMessage
    }
    if generation == loadGeneration {
      isLoading = false
    }
  }

  /// Re-filter unfiltered caches without a SwiftData fetch.
  func applyFilter(_ filter: MapFilterState) {
    let sanitized = filter.sanitized(for: .mainMap)
    pendingFilter = sanitized
    currentFilter = sanitized
    rebuildDisplayPins()
  }

  /// Re-filter when caches are warm; full load when the first fetch has not completed.
  /// Always latches `pendingFilter` so a coalesced reload cannot overwrite a newer selection.
  func scheduleFilterChange(_ filter: MapFilterState) {
    let sanitized = filter.sanitized(for: .mainMap)
    pendingFilter = sanitized
    if !hasCompletedInitialLoad {
      scheduleCoalescedReload(filter: sanitized)
      return
    }
    applyFilter(sanitized)
  }

  /// Schedules a debounced full reload so bursts of version bumps trigger one load.
  /// Records the latest filter so a multi-dimension flip during the debounce
  /// window is not dropped.
  func scheduleCoalescedReload(filter: MapFilterState, showsLoadingChrome: Bool = false) {
    pendingFilter = filter.sanitized(for: .mainMap)
    guard reloadTask == nil else { return }
    reloadTask = Task { @MainActor [weak self] in
      try? await Task.sleep(for: Self.reloadDebounce)
      guard let self, !Task.isCancelled else { return }
      self.reloadTask = nil
      let next = self.pendingFilter
      await self.loadMapData(
        filter: next,
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
    allLocatedContacts = []
    allLocatedDiscovered = []
    visibleContacts = []
    visibleDiscovered = []
    hasCompletedInitialLoad = false
    rebuildMapPoints()
  }

  // MARK: - Display pin algebra

  private func rebuildDisplayPins() {
    let filter = currentFilter

    let contacts: [ContactDTO] = if filter.favoritesOnly {
      allLocatedContacts.filter(\.isFavorite)
    } else {
      allLocatedContacts.filter { filter.allowsContactType($0.type) }
    }

    let discovered: [DiscoveredNodeDTO] = if filter.effectiveShowDiscovered {
      allLocatedDiscovered.filter { filter.allowsContactType($0.nodeType) }
    } else {
      []
    }

    visibleContacts = contacts
    visibleDiscovered = discovered
    rebuildMapPoints()
  }

  // MARK: - Map Points

  private func rebuildMapPoints() {
    var points: [MapPoint] = visibleContacts.map { contact in
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
    points += visibleDiscovered.map { node in
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
    visibleContacts.first { $0.id == id }
  }

  func discovered(forPointID id: UUID) -> DiscoveredNodeDTO? {
    visibleDiscovered.first { $0.id == id }
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

  func centerOnAllContacts() {
    var coordinates = visibleContacts.map(\.coordinate)
    coordinates += visibleDiscovered.map(\.coordinate)
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

/// Thrown only when `simulateLoadFailureForTesting` is set (unit tests).
private struct MapLoadSimulationError: Error, LocalizedError {
  var errorDescription: String? {
    "Simulated map load failure"
  }
}
