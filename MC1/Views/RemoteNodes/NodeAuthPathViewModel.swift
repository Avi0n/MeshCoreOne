import CoreLocation
import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class NodeAuthPathViewModel {
    private(set) var hops: [ResolvedPathHop] = []

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
            let contacts = try await services.dataStore.fetchContacts(radioID: radioID)
            let discoveredNodes = try await services.dataStore.fetchDiscoveredNodes(radioID: radioID)

            hops = NeighborNameResolver.resolvePath(
                contact.pathHops,
                contacts: contacts,
                discoveredNodes: discoveredNodes,
                userLocation: userLocation
            )
        } catch {
            logger.error("Failed to resolve path hops: \(error.localizedDescription)")
            hops = []
        }
    }
}
