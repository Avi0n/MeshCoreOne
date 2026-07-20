import CoreLocation
import MC1Services
import SwiftUI

/// Segment for the discovery picker
enum DiscoverSegment: String, CaseIterable {
  case all
  case contacts
  case repeaters
  case rooms

  var localizedTitle: String {
    switch self {
    case .all: L10n.Contacts.Contacts.Discovery.Segment.all
    case .contacts: L10n.Contacts.Contacts.Discovery.Segment.contacts
    case .repeaters: L10n.Contacts.Contacts.Discovery.Segment.repeaters
    case .rooms: L10n.Contacts.Contacts.Discovery.Segment.rooms
    }
  }
}

/// ViewModel for discovery view
@Observable
@MainActor
final class DiscoveryViewModel {
  // MARK: - Properties

  /// Discovered nodes from the mesh network
  var discoveredNodes: [DiscoveredNodeDTO] = []

  /// Public keys of contacts that have been added
  var addedPublicKeys: Set<Data> = []

  /// Nodes matching the current search/segment/sort, recomputed once per input or data change.
  private(set) var visibleNodes: [DiscoveredNodeDTO] = []

  /// Loading state
  var isLoading = false

  /// Whether data has been loaded at least once (prevents empty state flash)
  var hasLoadedOnce = false

  /// Error message to display
  var errorMessage: String?

  // MARK: - Last Filter Inputs

  /// Last filter inputs, re-applied whenever the underlying data changes.
  private var lastSearchText: String = ""
  private var lastSegment: DiscoverSegment = .all
  private var lastSortOrder: NodeSortOrder = .lastHeard
  private var lastUserLocation: CLLocation?

  // MARK: - Dependencies

  private var dataStoreProvider: @MainActor () -> DataStore? = { nil }
  private var dataStore: DataStore? {
    dataStoreProvider()
  }

  private var radioIDProvider: @MainActor () -> UUID? = { nil }
  private var radioID: UUID? {
    radioIDProvider()
  }

  /// Discover reload trace (Logger category `discover-trace`).
  private let discoverTrace = PersistentLogger(subsystem: "com.mc1", category: "discover-trace")

  private static let reloadDebounce: Duration = .milliseconds(50)
  private var reloadTask: Task<Void, Never>?

  // MARK: - Initialization

  init() {}

  /// Configure with the data store and connected radio this view model uses;
  /// providers returning nil mirror a disconnected state.
  func configure(
    dataStore: @escaping @MainActor () -> DataStore?,
    radioID: @escaping @MainActor () -> UUID?
  ) {
    dataStoreProvider = dataStore
    radioIDProvider = radioID
  }

  // MARK: - Load Nodes

  func loadDiscoveredNodes() async {
    guard let dataStore, let radioID else { return }

    isLoading = true
    errorMessage = nil

    do {
      let nodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)

      // One batch query for all contact public keys (not one round-trip per node).
      let addedKeys = try await dataStore.fetchContactPublicKeys(radioID: radioID)

      discoveredNodes = nodes
      addedPublicKeys = addedKeys
      discoverTrace.info("B4 view reload loaded=\(nodes.count) addedKeys=\(addedKeys.count) radio=\(radioID)")
    } catch {
      errorMessage = error.userFacingMessage
      discoverTrace.error("B4 view reload FAILED radio=\(radioID): \(error.localizedDescription)")
    }

    hasLoadedOnce = true
    isLoading = false
    applyFilter()
  }

  /// Schedules a debounced reload so bursts of contactsVersion bumps trigger
  /// one load instead of one per event. No-ops if a reload is already pending.
  func scheduleCoalescedReload() {
    guard reloadTask == nil else { return }
    reloadTask = Task { @MainActor [weak self] in
      try? await Task.sleep(for: Self.reloadDebounce)
      guard let self else { return }
      reloadTask = nil
      await loadDiscoveredNodes()
    }
  }

  // MARK: - Added State

  /// Check if a node has already been added as a contact
  func isAdded(_ node: DiscoveredNodeDTO) -> Bool {
    addedPublicKeys.contains(node.publicKey)
  }

  // MARK: - Delete

  func deleteDiscoveredNode(_ node: DiscoveredNodeDTO) async {
    guard let dataStore else { return }

    discoveredNodes.removeAll { $0.id == node.id }
    applyFilter()

    do {
      try await dataStore.deleteDiscoveredNode(id: node.id)
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  func clearAllDiscoveredNodes() async {
    guard let dataStore, let radioID else { return }

    do {
      try await dataStore.clearDiscoveredNodes(radioID: radioID)
      discoveredNodes = []
      applyFilter()
    } catch {
      errorMessage = error.userFacingMessage
    }
  }

  // MARK: - Filtering

  /// Recomputes `visibleNodes` from the given filter inputs. Called by the view on input changes only.
  func updateVisibleNodes(
    searchText: String,
    segment: DiscoverSegment,
    sortOrder: NodeSortOrder,
    userLocation: CLLocation?
  ) {
    lastSearchText = searchText
    lastSegment = segment
    lastSortOrder = sortOrder
    lastUserLocation = userLocation
    applyFilter()
  }

  private func applyFilter() {
    visibleNodes = filteredNodes(
      searchText: lastSearchText,
      segment: lastSegment,
      sortOrder: lastSortOrder,
      userLocation: lastUserLocation
    )
  }

  func filteredNodes(
    searchText: String,
    segment: DiscoverSegment,
    sortOrder: NodeSortOrder,
    userLocation: CLLocation?
  ) -> [DiscoveredNodeDTO] {
    var result = discoveredNodes

    if searchText.isEmpty {
      switch segment {
      case .all:
        break
      case .contacts:
        result = result.filter { $0.nodeType == .chat }
      case .repeaters:
        result = result.filter { $0.nodeType == .repeater }
      case .rooms:
        result = result.filter { $0.nodeType == .room }
      }
    } else {
      let query = searchText.lowercased()
      result = result.filter { node in
        node.name.localizedStandardContains(searchText)
          || node.publicKey.hexString.hasPrefix(query)
      }
    }

    return sorted(result, by: sortOrder, userLocation: userLocation)
  }

  // MARK: - Sorting

  private func sorted(
    _ nodes: [DiscoveredNodeDTO],
    by order: NodeSortOrder,
    userLocation: CLLocation?
  ) -> [DiscoveredNodeDTO] {
    switch order {
    case .lastHeard:
      nodes.sorted { $0.lastHeard > $1.lastHeard }
    case .name:
      nodes.sorted {
        $0.name.localizedCompare($1.name) == .orderedAscending
      }
    case .distance:
      sortedByDistanceThenName(nodes, from: userLocation)
    case .hops:
      sortedByHopsThenDistance(nodes, from: userLocation)
    }
  }

  /// Precomputes distances once (decorate-sort-undecorate) so the comparator does not
  /// allocate two `CLLocation`s per comparison.
  private func sortedByDistanceThenName(
    _ nodes: [DiscoveredNodeDTO],
    from userLocation: CLLocation?
  ) -> [DiscoveredNodeDTO] {
    guard let userLocation else {
      return nodes.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    }

    // Located nodes first (distance finite), unlocated last (distance infinite).
    let decorated: [(node: DiscoveredNodeDTO, distance: Double)] = nodes.map { node in
      if node.hasLocation {
        let distance = CLLocation(latitude: node.latitude, longitude: node.longitude)
          .distance(from: userLocation)
        return (node, distance)
      }
      return (node, .infinity)
    }

    return decorated
      .sorted { lhs, rhs in
        if lhs.distance != rhs.distance {
          return lhs.distance < rhs.distance
        }
        return lhs.node.name.localizedCompare(rhs.node.name) == .orderedAscending
      }
      .map(\.node)
  }

  private func sortedByHopsThenDistance(
    _ nodes: [DiscoveredNodeDTO],
    from userLocation: CLLocation?
  ) -> [DiscoveredNodeDTO] {
    guard let userLocation else {
      return nodes.sorted { lhs, rhs in
        let lhsHops = lhs.displayedHopCount
        let rhsHops = rhs.displayedHopCount
        if (lhsHops == nil) != (rhsHops == nil) {
          return lhsHops != nil
        }
        if let lhsHops, let rhsHops, lhsHops != rhsHops {
          return lhsHops < rhsHops
        }
        return lhs.name.localizedCompare(rhs.name) == .orderedAscending
      }
    }

    let decorated: [(node: DiscoveredNodeDTO, hops: Int?, distance: Double)] = nodes.map { node in
      let distance: Double = if node.hasLocation {
        CLLocation(latitude: node.latitude, longitude: node.longitude)
          .distance(from: userLocation)
      } else {
        .infinity
      }
      return (node, node.displayedHopCount, distance)
    }

    return decorated
      .sorted { lhs, rhs in
        // A nil hop count (flood-routed and never heard via advert) sorts to the bottom.
        if (lhs.hops == nil) != (rhs.hops == nil) {
          return lhs.hops != nil
        }
        if let lhsHops = lhs.hops, let rhsHops = rhs.hops, lhsHops != rhsHops {
          return lhsHops < rhsHops
        }
        if lhs.distance != rhs.distance {
          return lhs.distance < rhs.distance
        }
        return lhs.node.name.localizedCompare(rhs.node.name) == .orderedAscending
      }
      .map(\.node)
  }
}
