import CoreLocation
import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class MessagePathViewModel {
  var contacts: [ContactDTO] = []
  var repeaters: [ContactDTO] = []
  var discoveredRepeaters: [DiscoveredNodeDTO] = []
  var isLoading = true

  private let logger = Logger(subsystem: "com.mc1", category: "MessagePathViewModel")

  func loadContacts(services: ServiceContainer?, radioID: UUID) async {
    isLoading = true
    guard let services else {
      isLoading = false
      return
    }

    do {
      let fetched = try await services.dataStore.fetchContacts(radioID: radioID)
      contacts = fetched
      repeaters = fetched.filter { $0.type == .repeater }
      let nodes = try await services.dataStore.fetchDiscoveredNodes(radioID: radioID)
      discoveredRepeaters = nodes.filter { $0.nodeType == .repeater }
    } catch {
      logger.error("Failed to load contacts: \(error.localizedDescription)")
      contacts = []
      repeaters = []
      discoveredRepeaters = []
    }

    isLoading = false
  }

  func senderResolution(for message: MessageDTO) -> NodeNameResolution {
    if message.isChannelMessage, let nodeName = message.senderNodeName {
      return NodeNameResolution(displayName: nodeName, matchKind: .exact)
    }

    if let keyPrefix = message.senderKeyPrefix,
       let result = NeighborNameResolver.resolve(
         for: keyPrefix,
         contacts: contacts,
         discoveredNodes: [],
         userLocation: nil
       ) {
      return result
    }

    return NodeNameResolution(
      displayName: L10n.Chats.Chats.Path.Hop.unknown,
      matchKind: .unresolved
    )
  }

  func senderName(for message: MessageDTO) -> String {
    senderResolution(for: message).displayName
  }

  func senderNodeID(for message: MessageDTO) -> String? {
    guard let keyPrefix = message.senderKeyPrefix,
          let firstByte = keyPrefix.first else { return nil }
    return String(format: "%02X", firstByte)
  }

  func repeaterResolution(for hashBytes: Data, userLocation: CLLocation?) -> NodeNameResolution {
    NeighborNameResolver.resolve(
      for: hashBytes,
      contacts: repeaters,
      discoveredNodes: discoveredRepeaters,
      userLocation: userLocation
    ) ?? NodeNameResolution(
      displayName: L10n.Chats.Chats.Path.Hop.unknown,
      matchKind: .unresolved
    )
  }

  func repeaterName(for hashBytes: Data, userLocation: CLLocation?) -> String {
    repeaterResolution(for: hashBytes, userLocation: userLocation).displayName
  }
}
