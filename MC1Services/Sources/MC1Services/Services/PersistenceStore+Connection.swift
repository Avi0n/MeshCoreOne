import Foundation
import SwiftData

extension PersistenceStore {
  /// Applies an accepted field update without yielding between the fresh read and save.
  @discardableResult
  func updateConnectionDevice(id: UUID, update: @Sendable (DeviceDTO) -> DeviceDTO?) throws -> Bool {
    guard let current = try fetchDevice(id: id), let updated = update(current) else { return false }
    try saveDevice(updated)
    return true
  }

  /// Registers authenticated radio observations while preserving the store's current local state.
  func registerConnectionDevice(_ observation: DeviceDTO, bluetoothPeripheralID: UUID?) throws -> DeviceDTO {
    let current = try fetchDevice(id: observation.id) ?? fetchDevice(publicKey: observation.publicKey)
    guard try !isRadioErasePending(current?.radioID ?? observation.radioID) else {
      throw ConnectionError.deviceNotFound
    }
    let merged: DeviceDTO = if let current {
      current.copy {
        $0.id = observation.id
        if let bluetoothPeripheralID {
          $0.connectionMethods = $0.connectionMethods.map { method in
            guard case let .bluetooth(_, displayName) = method else { return method }
            return .bluetooth(peripheralUUID: bluetoothPeripheralID, displayName: displayName)
          }
        }
        $0.publicKey = observation.publicKey
        $0.nodeName = observation.nodeName
        $0.firmwareVersion = observation.firmwareVersion
        $0.firmwareVersionString = observation.firmwareVersionString
        $0.manufacturerName = observation.manufacturerName
        $0.buildDate = observation.buildDate
        $0.maxContacts = observation.maxContacts
        $0.maxChannels = observation.maxChannels
        $0.frequency = observation.frequency
        $0.bandwidth = observation.bandwidth
        $0.spreadingFactor = observation.spreadingFactor
        $0.codingRate = observation.codingRate
        $0.txPower = observation.txPower
        $0.maxTxPower = observation.maxTxPower
        $0.latitude = observation.latitude
        $0.longitude = observation.longitude
        $0.blePin = observation.blePin
        $0.manualAddContacts = observation.manualAddContacts
        $0.autoAddConfig = observation.autoAddConfig
        $0.autoAddMaxHops = observation.autoAddMaxHops
        $0.multiAcks = observation.multiAcks
        $0.telemetryModeBase = observation.telemetryModeBase
        $0.telemetryModeLoc = observation.telemetryModeLoc
        $0.telemetryModeEnv = observation.telemetryModeEnv
        $0.advertLocationPolicy = observation.advertLocationPolicy
        $0.clientRepeat = observation.clientRepeat
        $0.pathHashMode = observation.pathHashMode
        if $0.ocvPreset == nil { $0.ocvPreset = observation.ocvPreset }
        if !observation.clientRepeat {
          $0.preRepeatFrequency = nil
          $0.preRepeatBandwidth = nil
          $0.preRepeatSpreadingFactor = nil
          $0.preRepeatCodingRate = nil
        }
      }
    } else {
      observation.copy {
        $0.isActive = false
        $0.connectionMethods = []
      }
    }
    try saveDevice(merged)
    if let current, current.id != merged.id { try deleteDevice(id: current.id) }
    return merged
  }

  /// Selects from a fresh row so a delayed connection cannot restore old preferences or endpoints.
  func selectConnectionDevice(id: UUID, method: ConnectionMethod?) throws -> DeviceDTO {
    guard let current = try fetchDevice(id: id) else { throw PersistenceStoreError.deviceNotFound }
    guard try !isRadioErasePending(current.radioID) else { throw ConnectionError.deviceNotFound }
    let selected = current.copy {
      if let method {
        $0.connectionMethods.removeAll { $0.isBluetooth == method.isBluetooth }
        $0.connectionMethods.append(method)
      }
    }
    try saveDevice(selected)
    try setActiveDevice(id: id)
    return try fetchDevice(id: id) ?? selected
  }

  func clearActiveConnection() throws {
    for device in try modelContext.fetch(FetchDescriptor<Device>()) {
      device.isActive = false
    }
    try modelContext.save()
  }
}
