import MC1Services
import MeshCore
import SwiftUI

@Observable
@MainActor
final class TelemetryHistoryOverviewViewModel {
  struct LoadDependencies {
    let snapshots: @MainActor () async throws -> [NodeStatusSnapshotDTO]
    let contact: @MainActor () async throws -> ContactDTO?
    let contacts: @MainActor () async throws -> [ContactDTO]
    let discoveredNodes: @MainActor () async throws -> [DiscoveredNodeDTO]
  }

  // MARK: - State

  private(set) var snapshots: [NodeStatusSnapshotDTO] = []
  private(set) var ocvArray: [Int] = OCVPreset.liIon.ocvArray
  private(set) var contacts: [ContactDTO] = []
  private(set) var discoveredNodes: [DiscoveredNodeDTO] = []
  var timeRange: HistoryTimeRange = .default
  private var loadGeneration = 0

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
    await loadData(dependencies: LoadDependencies(
      snapshots: { try await dataStore.fetchNodeStatusSnapshots(nodePublicKey: publicKey, since: nil) },
      contact: { try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) },
      contacts: { try await dataStore.fetchContacts(radioID: radioID) },
      discoveredNodes: { try await dataStore.fetchDiscoveredNodes(radioID: radioID) }
    ))
  }

  func loadData(dependencies: LoadDependencies) async {
    guard !Task.isCancelled else { return }
    loadGeneration += 1
    let generation = loadGeneration
    let loadedSnapshots: [NodeStatusSnapshotDTO]
    do {
      loadedSnapshots = try await dependencies.snapshots()
    } catch {
      loadedSnapshots = []
    }
    guard !Task.isCancelled, generation == loadGeneration else { return }

    var loadedOCVArray = ocvArray
    do {
      if let contact = try await dependencies.contact() {
        loadedOCVArray = contact.activeOCVArray
      }
    } catch {
      // Keep default liIon
    }
    guard !Task.isCancelled, generation == loadGeneration else { return }

    let loadedContacts = await (try? dependencies.contacts()) ?? []
    guard !Task.isCancelled, generation == loadGeneration else { return }
    let loadedDiscoveredNodes = await (try? dependencies.discoveredNodes()) ?? []
    guard !Task.isCancelled, generation == loadGeneration else { return }

    snapshots = loadedSnapshots
    ocvArray = loadedOCVArray
    contacts = loadedContacts
    discoveredNodes = loadedDiscoveredNodes
  }

  func cancelLoad() {
    loadGeneration += 1
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
