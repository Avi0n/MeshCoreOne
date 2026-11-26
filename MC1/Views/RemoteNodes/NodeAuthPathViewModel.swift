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
    dataStore: PersistenceStore?,
    radioID: UUID,
    userLocation: CLLocation?,
    isCurrent: @MainActor () -> Bool
  ) async {
    guard let dataStore else {
      hops = []
      return
    }

    do {
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
      guard !Task.isCancelled, isCurrent() else { return }
      let discoveredNodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
      guard !Task.isCancelled, isCurrent() else { return }

      hops = NeighborNameResolver.resolvePath(
        contact.pathHops,
        contacts: contacts,
        discoveredNodes: discoveredNodes,
        userLocation: userLocation
      )
    } catch {
      guard !Task.isCancelled, isCurrent() else { return }
      logger.error("Failed to resolve path hops: \(error.localizedDescription)")
      hops = []
    }
  }
}
