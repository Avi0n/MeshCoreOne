import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("Settings snapshot writes")
struct SettingsSnapshotWriteTests {
  @Test
  func `sequential other-params siblings survive both orders`() async throws {
    let fields = OtherField.allCases
    for first in fields {
      for second in fields where first != second {
        let session = SnapshotConfigurationSession()
        let service = SettingsService(session: session)
        try await first.apply(to: service)
        try await second.apply(to: service)
        let info = try await service.getSelfInfo()
        #expect(first.isApplied(info), "\(first.rawValue) then \(second.rawValue)")
        #expect(second.isApplied(info), "\(first.rawValue) then \(second.rawValue)")
        let intents = await session.appliedIntents
        #expect(intents.count == 2)
        #expect(first.matchesOnly(intents[0]))
        #expect(second.matchesOnly(intents[1]))
      }
    }
  }

  @Test
  func `empty partial and full imports send only the named fields`() async throws {
    let harness = try await ImportHarness.make()

    try await harness.service.importOtherParams(MeshCoreNodeConfig.OtherSettings())
    #expect(await harness.session.appliedIntents.isEmpty)
    #expect(await harness.session.otherParamsWriteCount == 0)

    try await harness.service.importOtherParams(MeshCoreNodeConfig.OtherSettings(
      advertLocationPolicy: 99,
      multiAcks: 4
    ))
    var info = try await harness.settings.getSelfInfo()
    #expect(info.advertisementLocationPolicy == 99)
    #expect(info.multiAcks == 4)
    #expect(info.telemetryModeBase == 0)
    #expect(info.manualAddContacts == false)
    var intent = try #require(await harness.session.appliedIntents.last)
    #expect(intent.advertisementLocationPolicy == 99)
    #expect(intent.multiAcks == 4)
    #expect(intent.telemetryModeBase == nil)
    #expect(intent.manualAddContacts == nil)

    try await harness.service.importOtherParams(MeshCoreNodeConfig.OtherSettings(
      manualAddContacts: 1,
      advertLocationPolicy: 2,
      telemetryModeBase: 1,
      telemetryModeLocation: 2,
      telemetryModeEnvironment: 3,
      multiAcks: 5,
      advertisementType: 4
    ))
    info = try await harness.settings.getSelfInfo()
    #expect(info.manualAddContacts)
    #expect(info.advertisementLocationPolicy == 2)
    #expect(info.telemetryModeBase == 1)
    #expect(info.telemetryModeLocation == 2)
    #expect(info.telemetryModeEnvironment == 3)
    #expect(info.multiAcks == 5)
    intent = try #require(await harness.session.appliedIntents.last)
    #expect(intent.manualAddContacts == true)
    #expect(intent.advertisementLocationPolicy == 2)
    #expect(intent.telemetryModeBase == 1)
    #expect(intent.telemetryModeLocation == 2)
    #expect(intent.telemetryModeEnvironment == 3)
    #expect(intent.multiAcks == 5)
  }

  @Test
  @MainActor
  func `a held preset lets name and tx power finish`() async throws {
    let session = SnapshotConfigurationSession(firmwareVersion: 10)
    let service = SettingsService(session: session)
    let preset = try #require(RadioPresets.all.first { $0.id == "hu" })
    let finished = OSAllocatedUnfairLock(initialState: false)
    await session.setPathHashHold {
      _ = try await service.setNodeNameVerified("During")
      _ = try await service.setTxPowerVerified(11)
      finished.withLock { $0 = true }
    }

    _ = try await service.applyRadioPresetVerified(preset)

    #expect(finished.withLock { $0 })
    let info = try await service.getSelfInfo()
    #expect(info.name == "During")
    #expect(info.txPower == 11)
    #expect(abs(info.radioFrequency - preset.frequencyMHz) < 0.001)
  }

  @Test
  @MainActor
  func `a newer radio preset waits for the held preset and wins`() async throws {
    let session = SnapshotConfigurationSession(firmwareVersion: 10)
    let service = SettingsService(session: session)
    let hungary = try #require(RadioPresets.all.first { $0.id == "hu" })
    let usa = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    let (collector, collectTask) = await collectSettingsEvents(from: service)
    defer { collectTask.cancel() }
    let gate = SuspendGate()
    await session.setPathHashHold { await gate.park() }

    let first = Task { try await service.applyRadioPresetVerified(hungary) }
    try await waitUntil("Hungary should be parked on its path-hash write") {
      gate.isParked
    }
    let second = Task { try await service.applyRadioPresetVerified(usa) }
    try await waitUntil("the later preset should be waiting on the RF owner") {
      await service.radioSerializer.waiterCountForTest == 1
    }
    #expect(await session.setRadioCallCount == 1)

    gate.release()
    _ = try await first.value
    _ = try await second.value

    try await waitUntil("the later preset stamps its catalog id last") {
      await collector.catalogIDs == ["hu", "us-ca"]
    }
    let info = try await service.getSelfInfo()
    #expect(abs(info.radioFrequency - usa.frequencyMHz) < 0.001)
    #expect(await session.setRadioCallCount == 2)
  }

  @Test
  @MainActor
  func `cancelling a queued radio preset leaves the held preset's catalog id`() async throws {
    let session = SnapshotConfigurationSession(firmwareVersion: 10)
    let service = SettingsService(session: session)
    let hungary = try #require(RadioPresets.all.first { $0.id == "hu" })
    let usa = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    let (collector, collectTask) = await collectSettingsEvents(from: service)
    defer { collectTask.cancel() }
    let gate = SuspendGate()
    await session.setPathHashHold { await gate.park() }

    let first = Task { try await service.applyRadioPresetVerified(hungary) }
    try await waitUntil("Hungary should be parked on its path-hash write") {
      gate.isParked
    }
    let second = Task { try await service.applyRadioPresetVerified(usa) }
    try await waitUntil("the later preset should be waiting on the RF owner") {
      let pathHashCalls = await session.pathHashCallCount
      let radioCalls = await session.setRadioCallCount
      return pathHashCalls == 1 && radioCalls == 1
    }
    second.cancel()
    gate.release()
    _ = try await first.value
    await #expect(throws: CancellationError.self) {
      try await second.value
    }

    try await waitUntil("the held preset still publishes its catalog id") {
      await collector.catalogIDs == ["hu"]
    }
    #expect(await session.setRadioCallCount == 1)
    let info = try await service.getSelfInfo()
    #expect(abs(info.radioFrequency - hungary.frequencyMHz) < 0.001)
  }

  @Test
  func `a field update does not replace the contact watermark or identity`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let device = DeviceDTO.testDevice(nodeName: "Old", txPower: 20, lastContactSync: 42)
    try await store.saveDevice(device)

    try await store.updateDeviceNodeName(radioID: device.radioID, name: "New")
    try await store.updateDeviceTxPower(radioID: device.radioID, txPower: 9)
    try await store.updateDeviceOtherParams(
      radioID: device.radioID,
      intent: OtherParamsIntent(multiAcks: 5)
    )

    let loaded = try #require(await store.fetchDevice(radioID: device.radioID))
    #expect(loaded.nodeName == "New")
    #expect(loaded.txPower == 9)
    #expect(loaded.multiAcks == 5)
    #expect(loaded.telemetryModeBase == device.telemetryModeBase)
    #expect(loaded.lastContactSync == 42)
    #expect(loaded.publicKey == device.publicKey)
    #expect(loaded.isActive == device.isActive)

    await #expect(throws: PersistenceStoreError.self) {
      try await store.updateDeviceNodeName(radioID: UUID(), name: "Missing")
    }
  }

  @Test
  @MainActor
  func `name tx and radio applies keep every field`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let preset = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    manager.updateDevice(with: DeviceDTO.testDevice(nodeName: "Old", txPower: 4))

    await manager.applyNodeName("Named")
    await manager.applyTxPower(12)
    await manager.applyRadio(SettingsEvent.RadioChange(
      frequencyKHz: preset.frequencyKHz,
      bandwidthHz: preset.bandwidthHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate,
      catalogStamp: .set(preset.id)
    ))

    let device = try #require(manager.connectedDevice)
    #expect(device.nodeName == "Named")
    #expect(device.txPower == 12)
    #expect(device.frequency == preset.frequencyKHz)
    #expect(device.bandwidth == preset.bandwidthHz)
    #expect(device.spreadingFactor == preset.spreadingFactor)
    #expect(device.codingRate == preset.codingRate)
    #expect(device.appliedRadioPresetID == preset.id)
  }
}

private enum OtherField: String, CaseIterable {
  case manualAdd
  case base
  case location
  case environment
  case policy
  case multiAcks

  func apply(to service: SettingsService) async throws {
    switch self {
    case .manualAdd:
      try await service.setOtherParamsVerified(autoAddContacts: false)
    case .base:
      try await service.setOtherParamsVerified(telemetryModeBase: 1)
    case .location:
      try await service.setOtherParamsVerified(telemetryModeLocation: 2)
    case .environment:
      try await service.setOtherParamsVerified(telemetryModeEnvironment: 3)
    case .policy:
      try await service.setOtherParamsVerified(advertLocationPolicyRaw: 2)
    case .multiAcks:
      try await service.setOtherParamsVerified(multiAcks: 4)
    }
  }

  func isApplied(_ info: SelfInfo) -> Bool {
    switch self {
    case .manualAdd: info.manualAddContacts
    case .base: info.telemetryModeBase == 1
    case .location: info.telemetryModeLocation == 2
    case .environment: info.telemetryModeEnvironment == 3
    case .policy: info.advertisementLocationPolicy == 2
    case .multiAcks: info.multiAcks == 4
    }
  }

  func matchesOnly(_ intent: OtherParamsIntent) -> Bool {
    let manual = self == .manualAdd ? intent.manualAddContacts == true : intent.manualAddContacts == nil
    let base = self == .base ? intent.telemetryModeBase == 1 : intent.telemetryModeBase == nil
    let location = self == .location ? intent.telemetryModeLocation == 2 : intent.telemetryModeLocation == nil
    let environment = self == .environment ? intent.telemetryModeEnvironment == 3 : intent.telemetryModeEnvironment == nil
    let policy = self == .policy ? intent.advertisementLocationPolicy == 2 : intent.advertisementLocationPolicy == nil
    let multiAcks = self == .multiAcks ? intent.multiAcks == 4 : intent.multiAcks == nil
    return manual && base && location && environment && policy && multiAcks
  }
}

private struct ImportHarness {
  let session: SnapshotConfigurationSession
  let settings: SettingsService
  let service: NodeConfigService

  static func make() async throws -> ImportHarness {
    let session = SnapshotConfigurationSession()
    let settings = SettingsService(session: session)
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let mesh = MockMeshCoreSession()
    let channels = ChannelService(session: mesh, dataStore: store, rxLogService: nil)
    let service = NodeConfigService(
      session: mesh,
      settingsService: settings,
      channelService: channels,
      dataStore: store,
      syncCoordinator: nil
    )
    return ImportHarness(session: session, settings: settings, service: service)
  }
}

private actor SettingsEventCollector {
  private(set) var events: [SettingsEvent] = []

  func record(_ event: SettingsEvent) {
    events.append(event)
  }

  var catalogIDs: [String] {
    events.compactMap { event in
      guard case let .radioUpdated(change) = event, case let .set(id) = change.catalogStamp else { return nil }
      return id
    }
  }
}

private func collectSettingsEvents(
  from service: SettingsService
) async -> (SettingsEventCollector, Task<Void, Never>) {
  let stream = await service.events()
  let collector = SettingsEventCollector()
  let task = Task {
    for await event in stream {
      await collector.record(event)
    }
  }
  return (collector, task)
}

private final class SuspendGate: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: CheckedContinuation<Void, Never>?
  private var parked = false

  var isParked: Bool {
    lock.lock()
    defer { lock.unlock() }
    return parked
  }

  func park() async {
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      lock.lock()
      parked = true
      self.continuation = continuation
      lock.unlock()
    }
  }

  func release() {
    lock.lock()
    let continuation = continuation
    self.continuation = nil
    parked = false
    lock.unlock()
    continuation?.resume()
  }
}

/// Configuration state used by service intent and RF ordering tests.
private actor SnapshotConfigurationSession: ConfigurationSessionOps {
  private var selfInfo: SelfInfo
  private var capabilities: DeviceCapabilities
  private var pathHashHold: (@Sendable () async throws -> Void)?
  private(set) var appliedIntents: [OtherParamsIntent] = []
  private(set) var otherParamsWriteCount = 0
  private(set) var setRadioCallCount = 0
  private(set) var pathHashCallCount = 0

  init(firmwareVersion: UInt8 = 10) {
    selfInfo = SelfInfo(
      advertisementType: 0,
      txPower: 20,
      maxTxPower: 30,
      publicKey: Data(repeating: 0x01, count: 32),
      latitude: 0,
      longitude: 0,
      multiAcks: 0,
      advertisementLocationPolicy: 0,
      telemetryModeEnvironment: 0,
      telemetryModeLocation: 0,
      telemetryModeBase: 0,
      manualAddContacts: false,
      radioFrequency: 915,
      radioBandwidth: 250,
      radioSpreadingFactor: 10,
      radioCodingRate: 5,
      name: "Snapshot"
    )
    capabilities = DeviceCapabilities(
      firmwareVersion: firmwareVersion,
      maxContacts: 100,
      maxChannels: 8,
      blePin: 0,
      firmwareBuild: "test",
      model: "TestBoard",
      version: "1.13.0",
      clientRepeat: false,
      pathHashMode: 0
    )
  }

  func setPathHashHold(_ hold: @escaping @Sendable () async throws -> Void) {
    pathHashHold = hold
  }

  func sendAppStart() async throws -> SelfInfo {
    selfInfo
  }

  func queryDevice() async throws -> DeviceCapabilities {
    capabilities
  }

  func getBattery() async throws -> BatteryInfo {
    BatteryInfo(level: 3700)
  }

  func getTime() async throws -> Date {
    Date(timeIntervalSince1970: 0)
  }

  func setTime(_ date: Date) async throws {}

  func setName(_ name: String) async throws {
    selfInfo = patched(name: name)
  }

  func setCoordinates(latitude: Double, longitude: Double) async throws {
    selfInfo = patched(latitude: latitude, longitude: longitude)
  }

  func setTxPower(_ power: Int8) async throws {
    selfInfo = patched(txPower: power)
  }

  func setRadio(
    frequency: Double,
    bandwidth: Double,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    clientRepeat: Bool?
  ) async throws {
    setRadioCallCount += 1
    selfInfo = patched(
      radioFrequency: frequency,
      radioBandwidth: bandwidth,
      radioSpreadingFactor: spreadingFactor,
      radioCodingRate: codingRate
    )
    if let clientRepeat {
      capabilities = replacingCapabilities(clientRepeat: clientRepeat)
    }
  }

  func getRepeatFreq() async throws -> [FrequencyRange] {
    []
  }

  func setOtherParams(
    manualAddContacts: Bool,
    telemetryModeEnvironment: UInt8,
    telemetryModeLocation: UInt8,
    telemetryModeBase: UInt8,
    advertisementLocationPolicy: UInt8,
    multiAcks: UInt8?
  ) async throws {
    selfInfo = patched(
      multiAcks: multiAcks,
      manualAddContacts: manualAddContacts,
      telemetryModeEnvironment: telemetryModeEnvironment,
      telemetryModeLocation: telemetryModeLocation,
      telemetryModeBase: telemetryModeBase,
      advertisementLocationPolicy: advertisementLocationPolicy
    )
  }

  func applyOtherParams(_ intent: OtherParamsIntent) throws {
    appliedIntents.append(intent)
    guard !intent.isEmpty else { return }
    let updated = intent.applied(to: OtherParamsConfig(from: selfInfo))
    guard updated != OtherParamsConfig(from: selfInfo) else { return }
    otherParamsWriteCount += 1
    selfInfo = patched(
      multiAcks: updated.multiAcks,
      manualAddContacts: updated.manualAddContacts,
      telemetryModeEnvironment: updated.telemetryModeEnvironment,
      telemetryModeLocation: updated.telemetryModeLocation,
      telemetryModeBase: updated.telemetryModeBase,
      advertisementLocationPolicy: updated.advertisementLocationPolicy
    )
  }

  func setDevicePin(_ pin: UInt32) async throws {}
  func getAutoAddConfig() async throws -> AutoAddConfig {
    AutoAddConfig(bitmask: 0, maxHops: 0)
  }

  func setAutoAddConfig(_ config: AutoAddConfig) async throws {}

  func setPathHashMode(_ mode: UInt8) async throws {
    pathHashCallCount += 1
    try await pathHashHold?()
    capabilities = replacingCapabilities(pathHashMode: mode)
  }

  func setDefaultFloodScope(name: String, scope: FloodScope) async throws {}
  func getDefaultFloodScope() async throws -> DefaultFloodScope? {
    nil
  }

  func reboot() async throws {}
  func powerOff() async throws -> Bool {
    true
  }

  func factoryReset() async throws {}
  func getStatsCore() async throws -> CoreStats {
    CoreStats(batteryMV: 0, uptimeSeconds: 0, errors: 0, queueLength: 0)
  }

  func getStatsRadio() async throws -> RadioStats {
    RadioStats(noiseFloor: 0, lastRSSI: 0, lastSNR: 0, txAirtimeSeconds: 0, rxAirtimeSeconds: 0)
  }

  func getStatsPackets() async throws -> PacketStats {
    PacketStats(received: 0, sent: 0, floodTx: 0, directTx: 0, floodRx: 0, directRx: 0)
  }

  func getCustomVars() async throws -> [String: String] {
    [:]
  }

  func runCliCommand(_ command: String) async throws -> String {
    "OK"
  }

  func setCustomVar(key: String, value: String) async throws {}
  func exportPrivateKey() async throws -> Data {
    Data()
  }

  func importPrivateKey(_ key: Data) async throws {}
  func sign(_ data: Data, chunkSize: Int, timeout: TimeInterval?) async throws -> Data {
    Data()
  }

  private func patched(
    txPower: Int8? = nil,
    latitude: Double? = nil,
    longitude: Double? = nil,
    multiAcks: UInt8? = nil,
    manualAddContacts: Bool? = nil,
    telemetryModeEnvironment: UInt8? = nil,
    telemetryModeLocation: UInt8? = nil,
    telemetryModeBase: UInt8? = nil,
    advertisementLocationPolicy: UInt8? = nil,
    radioFrequency: Double? = nil,
    radioBandwidth: Double? = nil,
    radioSpreadingFactor: UInt8? = nil,
    radioCodingRate: UInt8? = nil,
    name: String? = nil
  ) -> SelfInfo {
    SelfInfo(
      advertisementType: selfInfo.advertisementType,
      txPower: txPower ?? selfInfo.txPower,
      maxTxPower: selfInfo.maxTxPower,
      publicKey: selfInfo.publicKey,
      latitude: latitude ?? selfInfo.latitude,
      longitude: longitude ?? selfInfo.longitude,
      multiAcks: multiAcks ?? selfInfo.multiAcks,
      advertisementLocationPolicy: advertisementLocationPolicy ?? selfInfo.advertisementLocationPolicy,
      telemetryModeEnvironment: telemetryModeEnvironment ?? selfInfo.telemetryModeEnvironment,
      telemetryModeLocation: telemetryModeLocation ?? selfInfo.telemetryModeLocation,
      telemetryModeBase: telemetryModeBase ?? selfInfo.telemetryModeBase,
      manualAddContacts: manualAddContacts ?? selfInfo.manualAddContacts,
      radioFrequency: radioFrequency ?? selfInfo.radioFrequency,
      radioBandwidth: radioBandwidth ?? selfInfo.radioBandwidth,
      radioSpreadingFactor: radioSpreadingFactor ?? selfInfo.radioSpreadingFactor,
      radioCodingRate: radioCodingRate ?? selfInfo.radioCodingRate,
      name: name ?? selfInfo.name
    )
  }

  private func replacingCapabilities(
    clientRepeat: Bool? = nil,
    pathHashMode: UInt8? = nil
  ) -> DeviceCapabilities {
    DeviceCapabilities(
      firmwareVersion: capabilities.firmwareVersion,
      maxContacts: capabilities.maxContacts,
      maxChannels: capabilities.maxChannels,
      blePin: capabilities.blePin,
      firmwareBuild: capabilities.firmwareBuild,
      model: capabilities.model,
      version: capabilities.version,
      clientRepeat: clientRepeat ?? capabilities.clientRepeat,
      pathHashMode: pathHashMode ?? capabilities.pathHashMode
    )
  }
}
