import CoreLocation
import SwiftUI
import MC1Services

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

    /// Loading state
    var isLoading = false

    /// Whether data has been loaded at least once (prevents empty state flash)
    var hasLoadedOnce = false

    /// Error message to display
    var errorMessage: String?

    // MARK: - Dependencies

    private var dataStore: DataStore?

    // MARK: - Initialization

    init() {}

    /// Configure with the data store this view model uses; nil mirrors a disconnected state.
    func configure(dataStore: DataStore?) {
        self.dataStore = dataStore
    }

    // MARK: - Load Nodes

    func loadDiscoveredNodes(radioID: UUID) async {
        guard let dataStore else { return }

        isLoading = true
        errorMessage = nil

        do {
            let nodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)

            // Single batch query for all contact public keys (O(1) vs O(N))
            let addedKeys = try await dataStore.fetchContactPublicKeys(radioID: radioID)

            discoveredNodes = nodes
            addedPublicKeys = addedKeys
        } catch {
            errorMessage = error.localizedDescription
        }

        hasLoadedOnce = true
        isLoading = false
    }

    // MARK: - Added State

    /// Check if a node has already been added as a contact
    func isAdded(_ node: DiscoveredNodeDTO) -> Bool {
        addedPublicKeys.contains(node.publicKey)
    }

    // MARK: - Delete

    func deleteDiscoveredNode(_ node: DiscoveredNodeDTO) async {
        guard let dataStore else { return }

        // Remove from UI immediately
        discoveredNodes.removeAll { $0.id == node.id }

        do {
            try await dataStore.deleteDiscoveredNode(id: node.id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearAllDiscoveredNodes(radioID: UUID) async {
        guard let dataStore else { return }

        do {
            try await dataStore.clearDiscoveredNodes(radioID: radioID)
            discoveredNodes = []
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Filtering

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
            result = result.filter { node in
                node.name.localizedStandardContains(searchText)
                    || node.publicKey.uppercaseHexString().hasPrefix(searchText.uppercased())
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
            return nodes.sorted { $0.lastAdvertTimestamp > $1.lastAdvertTimestamp }
        case .name:
            return nodes.sorted {
                $0.name.localizedCompare($1.name) == .orderedAscending
            }
        case .distance:
            guard let userLocation else {
                return sorted(nodes, by: .name, userLocation: nil)
            }
            return nodes.sorted { lhs, rhs in
                let lhsHasLocation = lhs.hasLocation
                let rhsHasLocation = rhs.hasLocation

                if lhsHasLocation != rhsHasLocation {
                    return lhsHasLocation
                }

                guard lhsHasLocation && rhsHasLocation else {
                    return lhs.name.localizedCompare(rhs.name) == .orderedAscending
                }

                let lhsLocation = CLLocation(latitude: lhs.latitude, longitude: lhs.longitude)
                let rhsLocation = CLLocation(latitude: rhs.latitude, longitude: rhs.longitude)

                return lhsLocation.distance(from: userLocation) < rhsLocation.distance(from: userLocation)
            }
        }
    }
}
