import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Node config RF ordering")
@MainActor
struct NodeConfigRFOrderingTests {
  @Test
  func `import admitted after a preset publishes the import tuple last`() async throws {
    let eu = try preset("eu-narrow")
    let usa = try preset("us-ca")
    let stack = try await RFStack.make(radio: .defaults)
    defer { stack.stop() }
    let log = RadioEventLog()
    let collect = await log.collect(from: stack.settings)
    defer { collect.cancel() }
    let gate = SuspendGate()
    await stack.service.setOnPostIdentityImport {
      await gate.park()
      return nil
    }

    let importing = Task {
      try await stack.service.importConfig(
        radioConfig(eu, includesPrivateKey: true),
        sections: radioSections(identity: true),
        radioID: UUID()
      )
    }
    try await waitUntil("import should reach the post-identity callback with the session idle") {
      gate.isParked
    }
    _ = try await stack.settings.applyRadioPresetVerified(usa)
    gate.release()
    try await importing.value

    #expect(await stack.radio.frequencyKHz == eu.frequencyKHz)
    #expect(await stack.radio.spreadingFactor == eu.spreadingFactor)
    #expect(await stack.radio.codingRate == eu.codingRate)
    try await waitUntil("the import observation is the last radio event") {
      await log.radioChanges.count >= 2
    }
    let changes = await log.radioChanges
    expectRadio(changes[0], matches: usa, stamp: .set(usa.id))
    expectRadio(changes[1], matches: eu, stamp: .reconcile)
  }

  @Test
  func `a preset queued during import RF is admitted last`() async throws {
    let eu = try preset("eu-narrow")
    let usa = try preset("us-ca")
    let stack = try await RFStack.make(radio: .defaults)
    defer { stack.stop() }
    let log = RadioEventLog()
    let collect = await log.collect(from: stack.settings)
    defer { collect.cancel() }
    await stack.radio.armSetRadioHold()

    let importing = Task {
      try await stack.service.importConfig(
        radioConfig(eu),
        sections: radioSections(identity: false),
        radioID: UUID()
      )
    }
    try await waitUntil("import should be inside its radio write") {
      await stack.radio.setRadioIsHeld
    }
    let applying = Task {
      try await stack.settings.applyRadioPresetVerified(usa)
    }
    try await waitUntil("the preset should wait on the RF owner") {
      await stack.settings.radioSerializer.waiterCountForTest == 1
    }
    #expect(await stack.radio.setRadioCount == 1)

    await stack.radio.releaseSetRadio()
    try await importing.value
    _ = try await applying.value

    #expect(await stack.radio.frequencyKHz == usa.frequencyKHz)
    #expect(await stack.radio.setRadioCount == 2)
    try await waitUntil("the preset observation is the last radio event") {
      await log.radioChanges.count >= 2
    }
    let changes = await log.radioChanges
    expectRadio(changes[0], matches: eu, stamp: .reconcile)
    expectRadio(changes[1], matches: usa, stamp: .set(usa.id))
  }

  @Test
  func `an initially matching import applies its tuple after an intervening preset`() async throws {
    let eu = try preset("eu-narrow")
    let usa = try preset("us-ca")
    let stack = try await RFStack.make(radio: .preset(eu))
    defer { stack.stop() }
    let gate = SuspendGate()
    await stack.service.setOnPostIdentityImport {
      await gate.park()
      return nil
    }

    let importing = Task {
      try await stack.service.importConfig(
        radioConfig(eu, includesPrivateKey: true),
        sections: radioSections(identity: true),
        radioID: UUID()
      )
    }
    try await waitUntil("import should park after the early radio read") {
      gate.isParked
    }
    _ = try await stack.settings.applyRadioPresetVerified(usa)
    #expect(await stack.radio.frequencyKHz == usa.frequencyKHz)
    gate.release()
    try await importing.value

    #expect(await stack.radio.frequencyKHz == eu.frequencyKHz)
    #expect(await stack.radio.spreadingFactor == eu.spreadingFactor)
    #expect(await stack.radio.codingRate == eu.codingRate)
    #expect(await stack.radio.setRadioCount == 2)
  }

  @Test
  func `an unchanged import sends no radio command and reconciles`() async throws {
    let eu = try preset("eu-narrow")
    let stack = try await RFStack.make(radio: .preset(eu))
    defer { stack.stop() }
    let log = RadioEventLog()
    let collect = await log.collect(from: stack.settings)
    defer { collect.cancel() }

    try await stack.service.importConfig(
      radioConfig(eu),
      sections: radioSections(identity: false),
      radioID: UUID()
    )

    #expect(await stack.radio.setRadioCount == 0)
    try await waitUntil("an unchanged import still publishes the admitted tuple") {
      await log.radioChanges.count == 1
    }
    await expectRadio(log.radioChanges[0], matches: eu, stamp: .reconcile)
  }

  @Test
  func `import reconcile keeps a matching catalog alias and clears a mismatch`() async throws {
    let usa = try preset("us-ca")
    let eu = try preset("eu-narrow")
    let (manager, _) = try ConnectionManager.createForTesting()
    let matching = DeviceDTO.testDevice(
      frequency: Device.Defaults.frequency,
      bandwidth: Device.Defaults.bandwidth,
      spreadingFactor: Device.Defaults.spreadingFactor,
      codingRate: Device.Defaults.codingRate
    ).copy { $0.appliedRadioPresetID = "ca" }
    try await manager.persistenceStore.saveDevice(matching)
    manager.updateDevice(with: matching)
    let stack = try await RFStack.make(radio: .preset(usa))
    defer { stack.stop() }
    let services = ServiceContainer(
      session: stack.session,
      dataStore: manager.persistenceStore,
      radioID: matching.radioID
    )
    manager.setTestState(services: services)
    let log = RadioEventLog()
    let collect = await log.collect(from: stack.settings)
    defer { collect.cancel() }
    try await stack.service.importConfig(
      radioConfig(usa),
      sections: radioSections(identity: false),
      radioID: matching.radioID
    )
    try await waitUntil("matching import publishes reconcile") {
      await log.radioChanges.count == 1
    }
    for change in await log.radioChanges {
      await manager.applyRadio(change)
    }
    let kept = try #require(manager.connectedDevice)
    #expect(kept.appliedRadioPresetID == "ca")
    #expect(kept.frequency == usa.frequencyKHz)
    let keptRow = try #require(try await manager.persistenceStore.fetchDevice(radioID: matching.radioID))
    #expect(keptRow.appliedRadioPresetID == "ca")
    #expect(keptRow.frequency == usa.frequencyKHz)

    let mismatching = matching.copy {
      $0.appliedRadioPresetID = "hu"
      $0.frequency = Device.Defaults.frequency
    }
    try await manager.persistenceStore.saveDevice(mismatching)
    manager.updateDevice(with: mismatching)
    let mismatchLog = RadioEventLog()
    let mismatchCollect = await mismatchLog.collect(from: stack.settings)
    defer { mismatchCollect.cancel() }
    // The first subscription was replaced. Import the other tuple on the same radio.
    await stack.radio.setRadio(
      frequencyKHz: Device.Defaults.frequency,
      bandwidthHz: Device.Defaults.bandwidth,
      spreadingFactor: Device.Defaults.spreadingFactor,
      codingRate: Device.Defaults.codingRate
    )
    try await stack.service.importConfig(
      radioConfig(eu),
      sections: radioSections(identity: false),
      radioID: mismatching.radioID
    )
    try await waitUntil("mismatching import publishes reconcile") {
      await mismatchLog.radioChanges.count == 1
    }
    for change in await mismatchLog.radioChanges {
      await manager.applyRadio(change)
    }
    let cleared = try #require(manager.connectedDevice)
    #expect(cleared.appliedRadioPresetID == nil)
    #expect(cleared.frequency == eu.frequencyKHz)
    let clearedRow = try #require(try await manager.persistenceStore.fetchDevice(radioID: mismatching.radioID))
    #expect(clearedRow.appliedRadioPresetID == nil)
    #expect(clearedRow.frequency == eu.frequencyKHz)
  }

  @Test
  func `radio success followed by TX failure still publishes the RF event`() async throws {
    let eu = try preset("eu-narrow")
    let stack = try await RFStack.make(radio: .defaults)
    defer { stack.stop() }
    let log = RadioEventLog()
    let collect = await log.collect(from: stack.settings)
    defer { collect.cancel() }
    await stack.radio.failNextTxPower()

    await #expect(throws: SettingsServiceError.self) {
      try await stack.service.importConfig(
        radioConfig(eu, txPower: 10),
        sections: radioSections(identity: false),
        radioID: UUID()
      )
    }

    #expect(await stack.radio.setRadioCount == 1)
    #expect(await stack.radio.frequencyKHz == eu.frequencyKHz)
    #expect(await stack.radio.txPower == Device.Defaults.txPower)
    try await waitUntil("the RF event is published before TX fails") {
      await log.radioChanges.count == 1
    }
    await expectRadio(log.radioChanges[0], matches: eu, stamp: .reconcile)
  }

  @Test
  func `cancelling a queued raw radio write sends no radio command`() async throws {
    let eu = try preset("eu-narrow")
    let usa = try preset("us-ca")
    let stack = try await RFStack.make(radio: .defaults)
    defer { stack.stop() }
    await stack.radio.armSetRadioHold()

    let first = Task {
      try await stack.settings.setRadioParams(
        frequencyKHz: eu.frequencyKHz,
        bandwidthKHz: eu.bandwidthHz,
        spreadingFactor: eu.spreadingFactor,
        codingRate: eu.codingRate
      )
    }
    try await waitUntil("the first raw write should be held") {
      await stack.radio.setRadioIsHeld
    }
    let second = Task {
      try await stack.settings.setRadioParams(
        frequencyKHz: usa.frequencyKHz,
        bandwidthKHz: usa.bandwidthHz,
        spreadingFactor: usa.spreadingFactor,
        codingRate: usa.codingRate
      )
    }
    try await waitUntil("the queued raw write should wait on the RF owner") {
      await stack.settings.radioSerializer.waiterCountForTest == 1
    }
    second.cancel()
    await stack.radio.releaseSetRadio()
    try await first.value
    await #expect(throws: CancellationError.self) {
      try await second.value
    }
    #expect(await stack.radio.setRadioCount == 1)
    #expect(await stack.radio.frequencyKHz == eu.frequencyKHz)
  }

  @Test
  func `a cancelled admitted radio write drains and the next write proceeds`() async throws {
    let eu = try preset("eu-narrow")
    let usa = try preset("us-ca")
    let stack = try await RFStack.make(radio: .defaults)
    defer { stack.stop() }
    await stack.radio.armSetRadioHold()

    let first = Task {
      try await stack.settings.setRadioParams(
        frequencyKHz: eu.frequencyKHz,
        bandwidthKHz: eu.bandwidthHz,
        spreadingFactor: eu.spreadingFactor,
        codingRate: eu.codingRate
      )
    }
    try await waitUntil("the admitted write should be held in its send") {
      await stack.radio.setRadioIsHeld
    }
    first.cancel()
    await #expect(throws: CancellationError.self) {
      try await first.value
    }
    await stack.radio.releaseSetRadio()
    try await stack.settings.setRadioParams(
      frequencyKHz: usa.frequencyKHz,
      bandwidthKHz: usa.bandwidthHz,
      spreadingFactor: usa.spreadingFactor,
      codingRate: usa.codingRate
    )

    #expect(await stack.radio.setRadioCount == 2)
    #expect(await stack.radio.frequencyKHz == usa.frequencyKHz)
  }
}

private func preset(_ id: String) throws -> RadioPreset {
  try #require(RadioPresets.all.first { $0.id == id })
}

private func radioSections(identity: Bool) -> ConfigSections {
  ConfigSections(nodeIdentity: identity, radioSettings: true)
}

private func radioConfig(
  _ preset: RadioPreset,
  txPower: Int8 = Device.Defaults.txPower,
  includesPrivateKey: Bool = false
) -> MeshCoreNodeConfig {
  MeshCoreNodeConfig(
    privateKey: includesPrivateKey ? String(repeating: "ab", count: 64) : nil,
    radioSettings: .init(
      frequency: preset.frequencyKHz,
      bandwidth: preset.bandwidthHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate,
      txPower: txPower
    )
  )
}

private func expectRadio(
  _ change: SettingsEvent.RadioChange,
  matches preset: RadioPreset,
  stamp: SettingsEvent.CatalogStamp
) {
  #expect(change.frequencyKHz == preset.frequencyKHz)
  #expect(change.bandwidthHz == preset.bandwidthHz)
  #expect(change.spreadingFactor == preset.spreadingFactor)
  #expect(change.codingRate == preset.codingRate)
  #expect(change.catalogStamp == stamp)
}

private struct RFStack {
  let radio: ScriptedRadio
  let session: MeshCoreSession
  let settings: SettingsService
  let service: NodeConfigService

  static func make(radio initial: ScriptedRadio.State) async throws -> RFStack {
    let radio = ScriptedRadio(state: initial)
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    let settings = SettingsService(session: session)
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let channels = ChannelService(session: session, dataStore: store, rxLogService: nil)
    let service = NodeConfigService(
      session: session,
      settingsService: settings,
      channelService: channels,
      dataStore: store,
      syncCoordinator: nil
    )
    return RFStack(radio: radio, session: session, settings: settings, service: service)
  }

  func stop() {
    let session = session
    let radio = radio
    Task {
      await radio.releaseSetRadio()
      await session.stop()
    }
  }
}

private actor RadioEventLog {
  private(set) var events: [SettingsEvent] = []

  func record(_ event: SettingsEvent) {
    events.append(event)
  }

  var radioChanges: [SettingsEvent.RadioChange] {
    events.compactMap { event in
      guard case let .radioUpdated(change) = event else { return nil }
      return change
    }
  }

  func collect(from service: SettingsService) -> Task<Void, Never> {
    Task {
      for await event in await service.events() {
        self.record(event)
      }
    }
  }
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

actor ScriptedRadio: MeshTransport {
  struct State: Sendable {
    var frequencyKHz: UInt32
    var bandwidthHz: UInt32
    var spreadingFactor: UInt8
    var codingRate: UInt8
    var txPower: Int8

    static var defaults: State {
      State(
        frequencyKHz: Device.Defaults.frequency,
        bandwidthHz: Device.Defaults.bandwidth,
        spreadingFactor: Device.Defaults.spreadingFactor,
        codingRate: Device.Defaults.codingRate,
        txPower: Device.Defaults.txPower
      )
    }

    static func preset(_ preset: RadioPreset) -> State {
      State(
        frequencyKHz: preset.frequencyKHz,
        bandwidthHz: preset.bandwidthHz,
        spreadingFactor: preset.spreadingFactor,
        codingRate: preset.codingRate,
        txPower: Device.Defaults.txPower
      )
    }
  }

  private let inner = MockTransport()
  private let setRadioGate = SuspendGate()
  private var state: State
  private var holdNextSetRadio = false
  private var failTxPower = false
  private var failNextAppStart = false
  private var dropNextDeviceInfo = false
  private(set) var setRadioCount = 0
  private(set) var deviceQueryCount = 0

  var frequencyKHz: UInt32 {
    state.frequencyKHz
  }

  var bandwidthHz: UInt32 {
    state.bandwidthHz
  }

  var spreadingFactor: UInt8 {
    state.spreadingFactor
  }

  var codingRate: UInt8 {
    state.codingRate
  }

  var txPower: Int8 {
    state.txPower
  }

  var setRadioIsHeld: Bool {
    setRadioGate.isParked
  }

  init(state: State) {
    self.state = state
  }

  func armSetRadioHold() {
    holdNextSetRadio = true
  }

  func releaseSetRadio() {
    setRadioGate.release()
  }

  func failNextTxPower() {
    failTxPower = true
  }

  func armFailNextAppStart() {
    failNextAppStart = true
  }

  func armDropNextDeviceInfo() {
    dropNextDeviceInfo = true
  }

  func setRadio(
    frequencyKHz: UInt32,
    bandwidthHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8
  ) {
    state.frequencyKHz = frequencyKHz
    state.bandwidthHz = bandwidthHz
    state.spreadingFactor = spreadingFactor
    state.codingRate = codingRate
  }

  func connect() async throws {
    try await inner.connect()
  }

  func disconnect() async {
    await inner.disconnect()
  }

  var isConnected: Bool {
    get async { await inner.isConnected }
  }

  var receivedData: AsyncStream<Data> {
    get async { await inner.receivedData }
  }

  func send(_ data: Data) async throws {
    try await inner.send(data)
    guard let code = data.first else { return }
    switch CommandCode(rawValue: code) {
    case .appStart:
      if failNextAppStart {
        failNextAppStart = false
        await inner.simulateError(code: ErrorCode.illegalArgument.rawValue)
        return
      }
      await inner.simulateReceive(selfInfoPacket())
    case .deviceQuery:
      deviceQueryCount += 1
      if dropNextDeviceInfo {
        dropNextDeviceInfo = false
        return
      }
      await inner.simulateReceive(deviceInfoPacket())
    case .setRadio:
      applySetRadio(data)
      setRadioCount += 1
      if holdNextSetRadio {
        holdNextSetRadio = false
        await setRadioGate.park()
      }
      await inner.simulateOK()
    case .setTxPower:
      if failTxPower {
        failTxPower = false
        await inner.simulateError(code: ErrorCode.illegalArgument.rawValue)
        return
      }
      if data.count > 1 {
        state.txPower = Int8(bitPattern: data[1])
      }
      await inner.simulateOK()
    case .importPrivateKey, .setPathHashMode, .setName:
      await inner.simulateOK()
    default:
      await inner.simulateError(code: ErrorCode.unsupportedCommand.rawValue)
    }
  }

  private func applySetRadio(_ data: Data) {
    guard data.count >= 11 else { return }
    state.frequencyKHz = uint32(data, at: 1)
    state.bandwidthHz = uint32(data, at: 5)
    state.spreadingFactor = data[9]
    state.codingRate = data[10]
  }

  private func selfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(UInt8(bitPattern: state.txPower))
    payload.append(22)
    payload.append(Data(repeating: 0x11, count: 32))
    payload.append(contentsOf: int32LE(0))
    payload.append(contentsOf: int32LE(0))
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(contentsOf: uint32LE(state.frequencyKHz))
    payload.append(contentsOf: uint32LE(state.bandwidthHz))
    payload.append(state.spreadingFactor)
    payload.append(state.codingRate)
    payload.append(contentsOf: "Node".utf8)
    return Data([ResponseCode.selfInfo.rawValue]) + payload
  }

  private func deviceInfoPacket() -> Data {
    var payload = Data(count: 81)
    payload[0] = 10
    payload[1] = 50
    payload[2] = 8
    return Data([ResponseCode.deviceInfo.rawValue]) + payload
  }
}

private func uint32(_ data: Data, at offset: Int) -> UInt32 {
  UInt32(data[offset])
    | UInt32(data[offset + 1]) << 8
    | UInt32(data[offset + 2]) << 16
    | UInt32(data[offset + 3]) << 24
}

private func uint32LE(_ value: UInt32) -> [UInt8] {
  [
    UInt8(value & 0xFF),
    UInt8((value >> 8) & 0xFF),
    UInt8((value >> 16) & 0xFF),
    UInt8((value >> 24) & 0xFF),
  ]
}

private func int32LE(_ value: Int32) -> [UInt8] {
  uint32LE(UInt32(bitPattern: value))
}
