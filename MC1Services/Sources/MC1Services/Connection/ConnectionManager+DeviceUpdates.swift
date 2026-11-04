import Foundation
import MeshCore

public extension ConnectionManager {
  func updateDevice(from selfInfo: MeshCore.SelfInfo, appliedRadioPresetID: String?) {
    applyDeviceUpdate { device in
      var updated = device.updating(from: selfInfo)
      if let appliedRadioPresetID {
        updated.appliedRadioPresetID = appliedRadioPresetID
      } else if !device.clientRepeat {
        let matches = RadioPresets.matchingPresets(
          frequencyKHz: updated.frequency, bandwidthKHz: updated.bandwidth,
          spreadingFactor: updated.spreadingFactor, codingRate: updated.codingRate
        )
        if let current = updated.appliedRadioPresetID, !matches.contains(where: { $0.id == current }) {
          updated.appliedRadioPresetID = nil
        }
      }
      return updated
    }
  }

  func applyDeviceObservation(_ info: MeshCore.SelfInfo, from producer: ServiceContainer) async {
    guard services === producer, let device = connectedDevice else { return }
    let radio = NodeConfigService.buildRadioSettings(from: info)
    var mapped = device.updating(from: info)
    mapped.frequency = radio.frequency
    mapped.bandwidth = radio.bandwidth
    mapped.spreadingFactor = radio.spreadingFactor
    mapped.codingRate = radio.codingRate
    mapped.txPower = radio.txPower
    mapped.appliedRadioPresetID = catalogID(for: .init(
      frequencyKHz: radio.frequency, bandwidthHz: radio.bandwidth,
      spreadingFactor: radio.spreadingFactor, codingRate: radio.codingRate, catalogStamp: .reconcile
    ), on: device)
    let snapshot = mapped
    let source = installedLink
    let work = enqueueMetadata { store in
      try await store.updateConnectionDevice(id: device.id) { current in
        guard current.id == device.id, current.publicKey == device.publicKey,
              current.radioID == device.radioID else { return nil }
        return current.applyingRefresh(snapshot)
      }
    }
    do {
      guard try await work.value else { return }
    } catch {
      logger.error("Failed to persist device observation: \(error.localizedDescription)")
      return
    }
    guard let source, owns(source), services === producer, let current = connectedDevice,
          current.id == device.id, current.publicKey == device.publicKey,
          current.radioID == device.radioID else { return }
    setPublishedDevice(current.applyingRefresh(snapshot))
  }

  func applyNodeName(_ name: String) async {
    await applySettingsUpdate { $0.copy { $0.nodeName = name } }
  }

  func applyCoordinates(latitude: Double, longitude: Double) async {
    await applySettingsUpdate { $0.copy { $0.latitude = latitude; $0.longitude = longitude } }
  }

  func applyRadio(_ change: SettingsEvent.RadioChange) async {
    guard let device = connectedDevice else { return }
    let presetID = catalogID(for: change, on: device)
    await applySettingsUpdate { $0.copy {
      $0.frequency = change.frequencyKHz
      $0.bandwidth = change.bandwidthHz
      $0.spreadingFactor = change.spreadingFactor
      $0.codingRate = change.codingRate
      $0.appliedRadioPresetID = presetID
    } }
  }

  func applyTxPower(_ power: Int8) async {
    await applySettingsUpdate { $0.copy { $0.txPower = power } }
  }

  func applyOtherParams(_ intent: OtherParamsIntent) async {
    await applySettingsUpdate { $0.copy {
      if let value = intent.manualAddContacts { $0.manualAddContacts = value }
      if let value = intent.telemetryModeBase { $0.telemetryModeBase = value }
      if let value = intent.telemetryModeLocation { $0.telemetryModeLoc = value }
      if let value = intent.telemetryModeEnvironment { $0.telemetryModeEnv = value }
      if let value = intent.advertisementLocationPolicy { $0.advertLocationPolicy = value }
      if let value = intent.multiAcks { $0.multiAcks = value }
    } }
  }

  private func catalogID(for change: SettingsEvent.RadioChange, on device: DeviceDTO) -> String? {
    switch change.catalogStamp {
    case let .set(id): return id
    case .reconcile:
      if device.clientRepeat { return device.appliedRadioPresetID }
      let matches = RadioPresets.matchingPresets(
        frequencyKHz: change.frequencyKHz, bandwidthKHz: change.bandwidthHz,
        spreadingFactor: change.spreadingFactor, codingRate: change.codingRate
      )
      guard let current = device.appliedRadioPresetID, matches.contains(where: { $0.id == current }) else { return nil }
      return current
    }
  }

  func updateDevice(with device: DeviceDTO) {
    if let current = installedLink?.device {
      guard device.id == current.id, device.radioID == current.radioID,
            device.publicKey == current.publicKey else { return }
    }
    setPublishedDevice(device)
  }

  func updateAutoAddConfig(_ config: MeshCore.AutoAddConfig) {
    applyDeviceUpdate { device in device.copy {
      $0.autoAddConfig = config.bitmask
      $0.autoAddMaxHops = config.maxHops
    } }
  }

  func updateClientRepeat(_ enabled: Bool) {
    applyDeviceUpdate { $0.copy { $0.clientRepeat = enabled } }
  }

  func updatePathHashMode(_ mode: UInt8) {
    applyDeviceUpdate { $0.copy { $0.pathHashMode = mode } }
  }

  func updateDefaultFloodScopeName(_ name: String?) {
    applyDeviceUpdate { $0.copy { $0.defaultFloodScopeName = name } }
  }

  func addKnownRegion(_ region: String) {
    guard let device = connectedDevice, !device.knownRegions.contains(region), let services else { return }
    let source = installedLink
    let updated = device.copy { $0.knownRegions.append(region) }
    setPublishedDevice(updated)
    let work = enqueueMetadata { store in try await store.addDeviceKnownRegion(radioID: updated.radioID, region: region) }
    Task {
      _ = await work.result
      guard let source, self.owns(source) else { return }
      await services.rxLogService.updateKnownRegions(updated.knownRegions)
    }
  }

  func removeKnownRegion(_ region: String) {
    guard let device = connectedDevice, let services else { return }
    let source = installedLink
    let wasDefault = device.defaultFloodScopeName == region
    let updated = device.copy {
      $0.knownRegions.removeAll { $0 == region }
      if wasDefault { $0.defaultFloodScopeName = nil }
    }
    setPublishedDevice(updated)
    let work = enqueueMetadata { store in try await store.removeDeviceKnownRegion(radioID: updated.radioID, region: region) }
    Task {
      _ = await work.result
      guard let source, self.owns(source) else { return }
      if wasDefault {
        do { _ = try await services.settingsService.setDefaultFloodScopeVerified(name: nil) } catch {
          self.logger.error("Failed to clear default flood scope after region removal: \(error)")
        }
      }
      guard self.owns(source) else { return }
      await services.rxLogService.updateKnownRegions(updated.knownRegions)
    }
  }

  func savePreRepeatSettings() {
    applyDeviceUpdate { $0.savingPreRepeatSettings() }
  }

  func clearPreRepeatSettings() {
    applyDeviceUpdate { $0.clearingPreRepeatSettings() }
  }
}

private extension ConnectionManager {
  func applySettingsUpdate(_ update: @escaping @Sendable (DeviceDTO) -> DeviceDTO) async {
    guard let device = connectedDevice else { return }
    setPublishedDevice(update(device))
    let work = enqueueMetadata { store in
      try await store.updateConnectionDevice(id: device.id) { current in
        guard current.id == device.id, current.publicKey == device.publicKey,
              current.radioID == device.radioID else { return nil }
        return update(current)
      }
    }
    _ = await work.result
  }

  func applyDeviceUpdate(_ update: @escaping @Sendable (DeviceDTO) -> DeviceDTO) {
    guard let device = connectedDevice else { return }
    setPublishedDevice(update(device))
    _ = enqueueMetadata { store in
      try await store.updateConnectionDevice(id: device.id) { current in
        guard current.id == device.id, current.publicKey == device.publicKey,
              current.radioID == device.radioID else { return nil }
        return update(current)
      }
    }
  }
}
