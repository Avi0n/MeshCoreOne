import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Connection Device update provenance")
@MainActor
struct ConnectionDeviceUpdateProvenanceTests {
  @Test(arguments: MetadataPeer.allCases)
  func `accepted peers merge unrelated fresh metadata`(peer: MetadataPeer) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = BLEReconnectionTestHarness.Gate()
    var predecessor: Task<Void, Error>?
    var bodyError: (any Error)?
    do {
      _ = try await env.connect(.bluetooth)
      guard let source = env.manager.connectedDevice else {
        throw NSError(domain: "ConnectionDeviceUpdateProvenance", code: 1)
      }
      let seeded = source.copy {
        $0.preRepeatFrequency = 900_000
        $0.preRepeatBandwidth = 62500
        $0.preRepeatSpreadingFactor = 12
        $0.preRepeatCodingRate = 8
      }
      try await env.manager.persistenceStore.saveDevice(seeded)
      env.manager.updateDevice(with: seeded)
      predecessor = env.manager.enqueueMetadata { store in
        await gate.wait()
        try await store.addDeviceKnownRegion(radioID: source.radioID, region: Self.freshRegion)
      }
      peer.apply(to: env.manager, source: seeded)
      let peerWasPublished = env.manager.connectedDevice.map { peer.matches($0, source: seeded) } == true
      try #require(peerWasPublished)
      gate.open()
      try await predecessor?.value
      await env.manager.metadataCommitTask?.value
      let saved = try await env.manager.persistenceStore.fetchDevice(id: source.id)
      let acceptedPeerWasSaved = saved.map { peer.matches($0, source: seeded) } == true
      let unrelatedFreshFieldSurvives = saved?.knownRegions == [Self.freshRegion]
      let originalTupleSurvives = saved?.id == source.id && saved?.publicKey == source.publicKey
        && saved?.radioID == source.radioID
      #expect(acceptedPeerWasSaved)
      #expect(unrelatedFreshFieldSurvives)
      #expect(originalTupleSurvives)
      let laterTime = try await env.manager.services?.settingsService.getTime()
      #expect(laterTime != nil)
    } catch {
      bodyError = error
    }
    gate.open()
    _ = await predecessor?.result
    await env.close()
    if let bodyError { throw bodyError }
  }

  @Test(arguments: [IdentityChange.publicKey, .radioID, .remintedDeviceID, .removed])
  func `accepted transform cannot cross changed or missing identity`(change: IdentityChange) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = BLEReconnectionTestHarness.Gate()
    let newRadioID = UUID()
    let newDeviceID = UUID()
    var predecessor: Task<DeviceDTO?, Error>?
    var bodyError: (any Error)?
    do {
      _ = try await env.connect(.bluetooth)
      guard let source = env.manager.connectedDevice else {
        throw NSError(domain: "ConnectionDeviceUpdateProvenance", code: 1)
      }
      let admittedPredecessor: Task<DeviceDTO?, Error> = env.manager.enqueueMetadata { store in
        await gate.wait()
        guard let fresh = try await store.fetchDevice(id: source.id) else { return nil }
        if change == .removed {
          try await store.deleteDevice(id: source.id)
          return nil
        }
        let changed = fresh.copy {
          $0.knownRegions = [Self.freshRegion]
          switch change {
          case .publicKey: $0.publicKey = Self.replacementPublicKey
          case .radioID: $0.radioID = newRadioID
          case .remintedDeviceID: $0.id = newDeviceID
          case .removed: break
          }
        }
        if change == .remintedDeviceID { try await store.deleteDevice(id: source.id) }
        try await store.saveDevice(changed)
        try await store.addDeviceKnownRegion(radioID: changed.radioID, region: Self.freshRegion)
        let committed = try await store.fetchDevice(id: changed.id)
        let intendedFixtureWasCommitted = committed == changed
        try #require(intendedFixtureWasCommitted)
        return changed
      }
      predecessor = admittedPredecessor
      env.manager.updateDefaultFloodScopeName(Self.acceptedScope)
      let updateWasAccepted = env.manager.connectedDevice?.defaultFloodScopeName == Self.acceptedScope
      try #require(updateWasAccepted)
      gate.open()
      let predecessorResult = try await admittedPredecessor.value
      await env.manager.metadataCommitTask?.value
      let storedID = change == .remintedDeviceID ? newDeviceID : source.id
      let saved = try await env.manager.persistenceStore.fetchDevice(id: storedID)
      let changedIdentityAndFieldsRemainUntouched = saved == predecessorResult
      let oldHandle = try await env.manager.persistenceStore.fetchDevice(id: source.id)
      let oldHandleWasNotRecreated = change != .remintedDeviceID || oldHandle == nil
      #expect(changedIdentityAndFieldsRemainUntouched)
      #expect(oldHandleWasNotRecreated)
      let laterTime = try await env.manager.services?.settingsService.getTime()
      #expect(laterTime != nil)
    } catch {
      bodyError = error
    }
    gate.open()
    _ = await predecessor?.result
    await env.close()
    if let bodyError { throw bodyError }
  }

  @Test
  func `settings refresh cannot restore a replaced public key`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let gate = BLEReconnectionTestHarness.Gate()
    var predecessor: Task<Void, Error>?
    do {
      _ = try await env.connect(.bluetooth)
      let source = try #require(env.manager.connectedDevice)
      let services = try #require(env.manager.services)
      predecessor = env.manager.enqueueMetadata { store in
        await gate.wait()
        let current = try #require(try await store.fetchDevice(id: source.id))
        try await store.saveDevice(current.copy { $0.publicKey = Self.replacementPublicKey })
      }
      let release = Task { @MainActor in
        await Task.yield()
        gate.open()
      }
      await env.manager.applyDeviceObservation(MetadataPeer.info(from: source), from: services)
      await release.value
      try await predecessor?.value
      let stored = try #require(try await env.manager.persistenceStore.fetchDevice(id: source.id))
      #expect(stored.publicKey == Self.replacementPublicKey)
      #expect(stored.nodeName == source.nodeName)
      #expect(env.manager.connectedDevice == source)
    } catch {
      gate.open()
      _ = await predecessor?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `persisted DTO callback only publishes the installed identity`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.bluetooth)
      guard let source = env.manager.connectedDevice else {
        throw NSError(domain: "ConnectionDeviceUpdateProvenance", code: 1)
      }
      let wrongKey = source.copy {
        $0.publicKey = Self.replacementPublicKey
        $0.nodeName = "Rejected key"
      }
      let wrongRadioID = source.copy {
        $0.radioID = UUID()
        $0.nodeName = "Rejected Radio ID"
      }
      let wrongDeviceID = source.copy {
        $0.id = UUID()
        $0.nodeName = "Rejected Device ID"
      }
      env.manager.updateDevice(with: wrongKey)
      env.manager.updateDevice(with: wrongRadioID)
      env.manager.updateDevice(with: wrongDeviceID)
      let mismatchedCallbacksWereRejected = env.manager.connectedDevice == source
      #expect(mismatchedCallbacksWereRejected)
      let persisted = source.copy {
        $0.ocvPreset = "custom"
        $0.customOCVArrayString = "4100,3900"
      }
      try await env.manager.persistenceStore.saveDevice(persisted)
      env.manager.updateDevice(with: persisted)
      let matchingCallbackWasPublished = env.manager.connectedDevice == persisted
      #expect(matchingCallbackWasPublished)
      await env.manager.metadataCommitTask?.value
      let saved = try await env.manager.persistenceStore.fetchDevice(id: source.id)
      let persistedFieldsRemainUntouched = saved == persisted
      #expect(persistedFieldsRemainUntouched)
      let laterTime = try await env.manager.services?.settingsService.getTime()
      #expect(laterTime != nil)
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  enum MetadataPeer: CaseIterable, Sendable {
    case selfInfo, autoAddConfig, clientRepeat, pathHashMode, defaultFloodScope, savePreRepeat, clearPreRepeat

    @MainActor
    func apply(to manager: ConnectionManager, source: DeviceDTO) {
      switch self {
      case .selfInfo:
        manager.updateDevice(from: Self.info(from: source), appliedRadioPresetID: nil)
      case .autoAddConfig: manager.updateAutoAddConfig(AutoAddConfig(bitmask: 7, maxHops: 5))
      case .clientRepeat: manager.updateClientRepeat(true)
      case .pathHashMode: manager.updatePathHashMode(2)
      case .defaultFloodScope: manager.updateDefaultFloodScopeName(ConnectionDeviceUpdateProvenanceTests.acceptedScope)
      case .savePreRepeat: manager.savePreRepeatSettings()
      case .clearPreRepeat: manager.clearPreRepeatSettings()
      }
    }

    @MainActor
    func matches(_ device: DeviceDTO, source: DeviceDTO) -> Bool {
      switch self {
      case .selfInfo: device.nodeName == "Accepted Settings name" && device.telemetryModeBase == 1
      case .autoAddConfig: device.autoAddConfig == 7 && device.autoAddMaxHops == 5
      case .clientRepeat: device.clientRepeat
      case .pathHashMode: device.pathHashMode == 2
      case .defaultFloodScope: device.defaultFloodScopeName == ConnectionDeviceUpdateProvenanceTests.acceptedScope
      case .savePreRepeat:
        device.preRepeatFrequency == source.frequency && device.preRepeatBandwidth == source.bandwidth
          && device.preRepeatSpreadingFactor == source.spreadingFactor && device.preRepeatCodingRate == source.codingRate
      case .clearPreRepeat:
        device.preRepeatFrequency == nil && device.preRepeatBandwidth == nil
          && device.preRepeatSpreadingFactor == nil && device.preRepeatCodingRate == nil
      }
    }

    static func info(from device: DeviceDTO) -> MeshCore.SelfInfo {
      MeshCore.SelfInfo(
        advertisementType: 1, txPower: device.txPower, maxTxPower: device.maxTxPower,
        publicKey: device.publicKey, latitude: device.latitude, longitude: device.longitude,
        multiAcks: device.multiAcks, advertisementLocationPolicy: device.advertLocationPolicy,
        telemetryModeEnvironment: device.telemetryModeEnv, telemetryModeLocation: device.telemetryModeLoc,
        telemetryModeBase: 1, manualAddContacts: device.manualAddContacts,
        radioFrequency: Double(device.frequency) / 1000, radioBandwidth: Double(device.bandwidth) / 1000,
        radioSpreadingFactor: device.spreadingFactor, radioCodingRate: device.codingRate, name: "Accepted Settings name"
      )
    }
  }

  enum IdentityChange: Sendable { case publicKey, radioID, remintedDeviceID, removed }

  private static let acceptedScope = "Accepted scope"
  private static let freshRegion = "Fresh local region"
  private static let replacementPublicKey = Data(repeating: 0xB9, count: ProtocolLimits.publicKeySize)
}
