import CoreLocation
import Foundation
import MC1Services

struct ResolvedNode<T: RepeaterResolvable>: Sendable {
    let node: T
    let matchKind: NodeNameMatchKind
}

/// A single routing hop (hash bytes shown as hex) resolved to a repeater name, ready to display.
struct ResolvedPathHop: Identifiable, Sendable {
    let id: Int
    let hex: String
    let resolution: NodeNameResolution
}

/// Resolves repeater collisions by proximity and recency.
enum RepeaterResolver {
    private static let exactPrefixLength = 6

    /// Match using a PathHop: exact public key match first, then hash bytes fallback.
    static func bestMatch<T: RepeaterResolvable>(
        for hop: PathHop,
        in nodes: [T],
        userLocation: CLLocation?
    ) -> T? {
        resolve(for: hop, in: nodes, userLocation: userLocation)?.node
    }

    static func resolve<T: RepeaterResolvable>(
        for hop: PathHop,
        in nodes: [T],
        userLocation: CLLocation?
    ) -> ResolvedNode<T>? {
        if let key = hop.publicKey,
           let exact = nodes.first(where: { $0.publicKey == key }) {
            return ResolvedNode(node: exact, matchKind: .exact)
        }
        return resolve(for: hop.hashBytes, in: nodes, userLocation: userLocation)
    }

    /// Match using hash bytes (1-3 byte prefix)
    static func bestMatch<T: RepeaterResolvable>(
        for hashBytes: Data,
        in nodes: [T],
        userLocation: CLLocation?
    ) -> T? {
        resolve(for: hashBytes, in: nodes, userLocation: userLocation)?.node
    }

    static func resolve<T: RepeaterResolvable>(
        for hashBytes: Data,
        in nodes: [T],
        userLocation: CLLocation?
    ) -> ResolvedNode<T>? {
        guard !hashBytes.isEmpty else { return nil }

        let prefixLen = hashBytes.count
        let candidates = nodes.compactMap { node -> (T, Double?)? in
            guard node.publicKey.prefix(prefixLen) == hashBytes else { return nil }

            let distance: Double?
            if let userLocation, node.hasLocation {
                let nodeLocation = CLLocation(latitude: node.latitude, longitude: node.longitude)
                distance = userLocation.distance(from: nodeLocation)
            } else {
                distance = nil
            }

            return (node, distance)
        }

        guard !candidates.isEmpty else { return nil }

        let sorted = candidates.sorted { lhs, rhs in
            switch (lhs.1, rhs.1) {
            case let (left?, right?):
                if left != right { return left < right }
            case (.some, .none):
                return true
            case (.none, .some):
                return false
            case (.none, .none):
                break
            }

            if lhs.0.lastAdvertTimestamp != rhs.0.lastAdvertTimestamp {
                return lhs.0.lastAdvertTimestamp > rhs.0.lastAdvertTimestamp
            }

            if lhs.0.recencyDate != rhs.0.recencyDate {
                return lhs.0.recencyDate > rhs.0.recencyDate
            }

            return lhs.0.resolvableName.localizedStandardCompare(rhs.0.resolvableName) == .orderedAscending
        }

        guard let node = sorted.first?.0 else { return nil }
        let matchingPublicKeys = Set(candidates.map { $0.0.publicKey })
        let matchKind: NodeNameMatchKind = prefixLen >= exactPrefixLength || matchingPublicKeys.count == 1
            ? .exact
            : .fallback
        return ResolvedNode(node: node, matchKind: matchKind)
    }
}

enum NeighborNameResolver {
    static func resolve(
        for prefix: Data,
        contacts: [ContactDTO],
        discoveredNodes: [DiscoveredNodeDTO],
        userLocation: CLLocation?
    ) -> NodeNameResolution? {
        if let contact = RepeaterResolver.resolve(for: prefix, in: contacts, userLocation: userLocation) {
            return NodeNameResolution(
                displayName: contact.node.resolvableName,
                matchKind: matchKind(for: prefix, resolvedMatchKind: contact.matchKind, contacts: contacts, discoveredNodes: discoveredNodes)
            )
        }

        if let node = RepeaterResolver.resolve(for: prefix, in: discoveredNodes, userLocation: userLocation) {
            return NodeNameResolution(
                displayName: node.node.resolvableName,
                matchKind: matchKind(for: prefix, resolvedMatchKind: node.matchKind, contacts: contacts, discoveredNodes: discoveredNodes)
            )
        }

        return nil
    }

    private static func matchKind(
        for prefix: Data,
        resolvedMatchKind: NodeNameMatchKind,
        contacts: [ContactDTO],
        discoveredNodes: [DiscoveredNodeDTO]
    ) -> NodeNameMatchKind {
        guard resolvedMatchKind != .unresolved, prefix.count < 6 else {
            return resolvedMatchKind
        }

        let matchingContactKeys = contacts
            .filter { $0.publicKey.prefix(prefix.count) == prefix }
            .map(\.publicKey)
        let matchingDiscoveredKeys = discoveredNodes
            .filter { $0.publicKey.prefix(prefix.count) == prefix }
            .map(\.publicKey)

        return Set(matchingContactKeys + matchingDiscoveredKeys).count > 1 ? .fallback : .exact
    }

    static func resolveName(
        for prefix: Data,
        contacts: [ContactDTO],
        discoveredNodes: [DiscoveredNodeDTO],
        userLocation: CLLocation?
    ) -> String? {
        resolve(
            for: prefix,
            contacts: contacts,
            discoveredNodes: discoveredNodes,
            userLocation: userLocation
        )?.displayName
    }

    static func fallbackName(for prefix: Data) -> String {
        prefix.prefix(4).uppercaseHexString()
    }

    /// Resolves every hop of a node's stored path to a repeater name, falling back to a placeholder
    /// when no repeater matches a hop. Only repeaters can relay, so contacts and discovered nodes are
    /// narrowed to repeaters before matching, matching the login sheet's path display.
    static func resolvePath(
        _ hops: [(data: Data, hex: String)],
        contacts: [ContactDTO],
        discoveredNodes: [DiscoveredNodeDTO],
        userLocation: CLLocation?
    ) -> [ResolvedPathHop] {
        let repeaters = contacts.filter { $0.type == .repeater }
        let discoveredRepeaters = discoveredNodes.filter { $0.nodeType == .repeater }

        return hops.enumerated().map { index, hop in
            let resolution = resolve(
                for: hop.data,
                contacts: repeaters,
                discoveredNodes: discoveredRepeaters,
                userLocation: userLocation
            ) ?? NodeNameResolution(
                displayName: L10n.RemoteNodes.RemoteNodes.Auth.pathHopUnknown,
                matchKind: .unresolved
            )
            return ResolvedPathHop(id: index, hex: hop.hex, resolution: resolution)
        }
    }
}
