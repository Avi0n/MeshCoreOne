import Foundation
import SwiftData

public extension PersistenceStore {
  func updateWatchlistMembership(
    contactID: UUID,
    radioID: UUID,
    isMember: Bool
  ) throws -> ContactDTO {
    let contact = try repeaterForWatchlistPatch(contactID: contactID, radioID: radioID)
    contact.isMyNode = isMember
    try saveWatchlistPatch()
    return ContactDTO(from: contact)
  }

  /// An empty version records a completed check with no version reported.
  func updateWatchlistFirmware(
    contactID: UUID,
    radioID: UUID,
    version: String
  ) throws -> ContactDTO {
    let contact = try repeaterForWatchlistPatch(contactID: contactID, radioID: radioID)
    contact.myNodeFirmwareVersion = version
    try saveWatchlistPatch()
    return ContactDTO(from: contact)
  }

  /// Records a Watchlist status reading. Unlike other status captures, a newer
  /// reading replaces the status in the current snapshot window.
  func recordWatchlistStatus(nodePublicKey: Data, status: NodeStatusMetrics) throws -> NodeStatusSnapshotDTO {
    try Task.checkCancellation()
    let capturedAt = Date.now
    let latest = try latestSnapshot(
      nodePublicKey: nodePublicKey,
      selfRadioID: nil,
      since: capturedAt.addingTimeInterval(-NodeSnapshotPolicy.minimumInterval)
    )
    let id = try insertOrEnrichSnapshot(
      latest: latest,
      capturedAt: capturedAt,
      nodePublicKey: nodePublicKey,
      selfRadioID: nil,
      status: status,
      telemetry: nil,
      neighbors: nil,
      location: nil,
      replacesStatus: true
    )
    var descriptor = FetchDescriptor<NodeStatusSnapshot>(predicate: #Predicate { $0.id == id })
    descriptor.fetchLimit = 1
    guard let saved = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.invalidData
    }
    return NodeStatusSnapshotDTO(from: saved)
  }

  /// The newest remote snapshot carrying a battery reading, per public key.
  func fetchLatestBatterySnapshots(nodePublicKeys: [Data]) throws -> [Data: NodeStatusSnapshotDTO] {
    var latest: [Data: NodeStatusSnapshotDTO] = [:]
    for key in Set(nodePublicKeys) {
      // Self telemetry rows never carry status, so the newest battery row is the remote one.
      var descriptor = FetchDescriptor<NodeStatusSnapshot>(
        predicate: #Predicate { $0.nodePublicKey == key && $0.batteryMillivolts != nil },
        sortBy: [SortDescriptor(\NodeStatusSnapshot.timestamp, order: .reverse)]
      )
      descriptor.fetchLimit = 1
      if let row = try modelContext.fetch(descriptor).first(where: { $0.selfRadioID == nil }) {
        latest[key] = NodeStatusSnapshotDTO(from: row)
      }
    }
    return latest
  }

  private func repeaterForWatchlistPatch(contactID: UUID, radioID: UUID) throws -> Contact {
    try Task.checkCancellation()
    let contact = try requiredContact(id: contactID)
    guard contact.radioID == radioID else {
      throw PersistenceStoreError.contactNotFound
    }
    guard contact.isRepeater else {
      throw PersistenceStoreError.invalidData
    }
    // Commit buffered RX log entries so a failed patch cannot roll them back.
    try commitDeferredWrites()
    return contact
  }

  private func saveWatchlistPatch() throws {
    do {
      #if DEBUG
        try watchlistSaveFaultInjection?()
      #endif
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
  }
}
