import Foundation
import SwiftData

extension PersistenceStore {
  public func captureReceiveScope(radioID: UUID, link: ReceiveScope.Lifetime) async throws -> ReceiveScope? {
    guard link.isCurrent, try !isRadioErasePending(radioID) else { return nil }
    if let partition = receivePartitions[radioID] {
      return ReceiveScope(radioID: radioID, link: link, partition: partition)
    }
    guard try fetchDevice(radioID: radioID) != nil else { return nil }
    try backfillTextRulesIdentities(radioID: radioID)
    let partition = ReceiveScope.Lifetime()
    receivePartitions[radioID] = partition
    return ReceiveScope(radioID: radioID, link: link, partition: partition)
  }

  func retireReceiveScope(radioID: UUID) {
    receivePartitions.removeValue(forKey: radioID)?.retire()
  }

  func fetchPendingRadioErases() throws -> [PendingRadioEraseDTO] {
    try modelContext.fetch(FetchDescriptor<PendingRadioErase>()).map(PendingRadioEraseDTO.init)
  }

  func isRadioErasePending(_ radioID: UUID) throws -> Bool {
    if let pendingEraseRadioIDs {
      return pendingEraseRadioIDs.contains(radioID)
    }
    let pending = try Set(fetchPendingRadioErases().map(\.radioID))
    pendingEraseRadioIDs = pending
    return pending.contains(radioID)
  }

  func beginRadioErase(_ device: DeviceDTO, isFactoryReset: Bool = false) throws -> PendingRadioEraseDTO {
    let radioID = device.radioID
    if let existing = try pendingRadioErase(radioID) {
      return PendingRadioEraseDTO(existing)
    }
    guard try fetchDevice(id: device.id)?.radioID == radioID else {
      throw PersistenceStoreError.deviceNotFound
    }
    let target = PendingRadioEraseDTO(
      radioID: radioID,
      deviceID: device.id,
      pairingID: device.bluetoothPeripheralID ?? device.id,
      radioName: device.nodeName,
      isFactoryReset: isFactoryReset
    )
    try commitPendingRxLogEntries()
    do {
      modelContext.insert(PendingRadioErase(target))
      #if DEBUG
        try beginRadioEraseFaultInjection?()
      #endif
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
    pendingEraseRadioIDs?.insert(radioID)
    retireReceiveScope(radioID: radioID)
    return target
  }

  func cancelRadioErase(radioID: UUID) throws {
    guard let target = try pendingRadioErase(radioID) else { return }
    try commitPendingRxLogEntries()
    do {
      modelContext.delete(target)
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
    pendingEraseRadioIDs?.remove(radioID)
  }

  func completeRadioErase(radioID: UUID) throws {
    guard let target = try pendingRadioErase(radioID) else { return }
    try commitPendingRxLogEntries()
    do {
      try stageDeleteAllDeviceData(radioID: radioID)
      #if DEBUG
        try completeRadioEraseFaultInjection?()
      #endif
      let devices = try modelContext.fetch(FetchDescriptor<Device>(predicate: #Predicate { $0.radioID == radioID }))
      for device in devices {
        modelContext.delete(device)
      }
      modelContext.delete(target)
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
    pendingEraseRadioIDs?.remove(radioID)
    publishRecentlyBlockedChange()
  }

  private func pendingRadioErase(_ radioID: UUID) throws -> PendingRadioErase? {
    var descriptor = FetchDescriptor<PendingRadioErase>(predicate: #Predicate { $0.radioID == radioID })
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first
  }

  #if DEBUG
    func setRadioEraseFaults(
      begin: (@Sendable () throws -> Void)?,
      complete: (@Sendable () throws -> Void)?
    ) {
      beginRadioEraseFaultInjection = begin
      completeRadioEraseFaultInjection = complete
    }
  #endif
}
