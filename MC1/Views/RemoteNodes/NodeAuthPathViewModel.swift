import CoreLocation
import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class NodeAuthPathViewModel {
    /// A single routing hop resolved to a repeater name, ready to display.
    struct ResolvedHop: Identifiable {
        let id: Int
        let hex: String
        let resolution: NodeNameResolution
    }

    private(set) var hops: [ResolvedHop] = []

    private let logger = Logger(subsystem: "com.mc1", category: "NodeAuthPathViewModel")

    func load(
        contact: ContactDTO,
        services: ServiceContainer?,
        radioID: UUID,
        userLocation: CLLocation?
    ) async {
        guard let services else {
            hops = []
            return
        }

        do {
            let repeaters = try await services.dataStore.fetchContacts(radioID: radioID)
                .filter { $0.type == .repeater }
            let discoveredRepeaters = try await services.dataStore.fetchDiscoveredNodes(radioID: radioID)
                .filter { $0.nodeType == .repeater }

            hops = contact.pathHops.enumerated().map { index, hop in
                let resolution = NeighborNameResolver.resolve(
                    for: hop.data,
                    contacts: repeaters,
                    discoveredNodes: discoveredRepeaters,
                    userLocation: userLocation
                ) ?? NodeNameResolution(
                    displayName: L10n.RemoteNodes.RemoteNodes.Auth.pathHopUnknown,
                    matchKind: .unresolved
                )
                return ResolvedHop(id: index, hex: hop.hex, resolution: resolution)
            }
        } catch {
            logger.error("Failed to resolve path hops: \(error.localizedDescription)")
            hops = []
        }
    }
}
