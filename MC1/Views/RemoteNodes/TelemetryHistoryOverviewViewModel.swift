import MC1Services
import MeshCore
import SwiftUI

@Observable
@MainActor
final class TelemetryHistoryOverviewViewModel {
  // MARK: - State

  private(set) var snapshots: [NodeStatusSnapshotDTO] = []
  private(set) var ocvArray: [Int] = OCVPreset.liIon.ocvArray
  private(set) var contacts: [ContactDTO] = []
  private(set) var discoveredNodes: [DiscoveredNodeDTO] = []
  var timeRange: HistoryTimeRange = .default

  // MARK: - Computed

  var filteredSnapshots: [NodeStatusSnapshotDTO] {
    guard let start = timeRange.startDate else { return snapshots }
    return snapshots.filter { $0.timestamp >= start }
  }

  var hasSnapshots: Bool {
    !snapshots.isEmpty
  }

  var hasNeighborData: Bool {
    hasNeighborData(in: filteredSnapshots)
  }

  var hasTelemetryData: Bool {
    hasTelemetryData(in: filteredSnapshots)
  }

  var channelGroups: [ChannelGroup] {
    ChannelGroup.groups(from: filteredSnapshots)
  }

  func hasNeighborData(in snapshots: [NodeStatusSnapshotDTO]) -> Bool {
    snapshots.contains { $0.neighborSnapshots?.isEmpty == false }
  }

  func hasTelemetryData(in snapshots: [NodeStatusSnapshotDTO]) -> Bool {
    snapshots.contains { $0.telemetryEntries?.isEmpty == false }
  }

  func hasRadioData(in snapshots: [NodeStatusSnapshotDTO]) -> Bool {
    snapshots.contains {
      $0.batteryMillivolts != nil || $0.lastSNR != nil ||
        $0.lastRSSI != nil || $0.noiseFloor != nil ||
        $0.packetsSent != nil || $0.packetsReceived != nil ||
        $0.receiveErrors != nil ||
        $0.sentDirect != nil || $0.sentFlood != nil ||
        $0.receivedDirect != nil || $0.receivedFlood != nil ||
        $0.directDuplicates != nil || $0.floodDuplicates != nil ||
        $0.postedCount != nil || $0.postPushCount != nil
    }
  }

  // MARK: - Loading

  func loadData(dataStore: PersistenceStore, publicKey: Data, radioID: UUID) async {
    do {
      snapshots = try await dataStore.fetchNodeStatusSnapshots(
        nodePublicKey: publicKey, since: nil
      )
    } catch {
      snapshots = []
    }

    do {
      if let contact = try await dataStore.fetchContact(
        radioID: radioID, publicKey: publicKey
      ) {
        ocvArray = contact.activeOCVArray
      }
    } catch {
      // Keep default liIon
    }

    contacts = await (try? dataStore.fetchContacts(radioID: radioID)) ?? []
    discoveredNodes = await (try? dataStore.fetchDiscoveredNodes(radioID: radioID)) ?? []
  }

  func resolveNeighborName(prefix: Data) -> String? {
    NeighborNameResolver.resolveName(
      for: prefix,
      contacts: contacts,
      discoveredNodes: discoveredNodes,
      userLocation: nil
    )
  }
}
