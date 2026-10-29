import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Settings refresh propagation")
@MainActor
struct SettingsRefreshPropagationTests {
  @Test
  func `an explicit refresh updates the live device and the existing row`() async throws {
    let usa = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    let original = try savedDevice(catalogID: "ca")
    var state = PropagationRadio.State()
    state.publicKey = Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
    state.nodeName = "Renamed"
    state.latitude = 45
    state.longitude = -120
    state.frequencyKHz = usa.frequencyKHz
    state.bandwidthHz = usa.bandwidthHz
    state.spreadingFactor = usa.spreadingFactor
    state.codingRate = usa.codingRate
    state.txPower = 10
    state.multiAcks = 3
    state.advertLocationPolicy = 1
    state.telemetryModeBase = 1
    state.telemetryModeLocation = 2
    state.telemetryModeEnvironment = 0
    state.manualAddContacts = true
    let fixture = try await RefreshFixture.make(device: original, state: state)
    defer { fixture.stop() }

    try await fixture.services.settingsService.refreshDeviceInfo()
    try await waitUntil("the refresh should reach the live device") {
      fixture.appState.connectedDevice?.nodeName == "Renamed"
    }

    let live = try #require(fixture.appState.connectedDevice)
    let stored = try #require(try await fixture.store.fetchDevice(radioID: original.radioID))
    for device in [live, stored] {
      #expect(device.publicKey == state.publicKey)
      #expect(device.nodeName == "Renamed")
      #expect(device.latitude == 45)
      #expect(device.longitude == -120)
      #expect(device.frequency == usa.frequencyKHz)
      #expect(device.bandwidth == usa.bandwidthHz)
      #expect(device.spreadingFactor == usa.spreadingFactor)
      #expect(device.codingRate == usa.codingRate)
      #expect(device.txPower == 10)
      #expect(device.manualAddContacts)
      #expect(device.multiAcks == 3)
      #expect(device.telemetryModeBase == 1)
      #expect(device.telemetryModeLoc == 2)
      #expect(device.telemetryModeEnv == 0)
      #expect(device.advertLocationPolicy == 1)
      #expect(device.appliedRadioPresetID == "ca")
      expectPreservedMetadata(device, matches: original)
    }

    let exported = try await AppBackupService().export(persistenceStore: fixture.store)
    let envelope = try parseBackup(data: exported.data)
    let backedUp = try #require(envelope.devices.first { $0.radioID == original.radioID })
    #expect(backedUp.publicKey == state.publicKey)
    #expect(backedUp.radioID == original.radioID)
    #expect(backedUp.id != original.id)
  }

  @Test
  func `a refresh reconciles a catalog id that no longer matches`() async throws {
    let eu = try #require(RadioPresets.all.first { $0.id == "eu-narrow" })
    let original = try savedDevice(catalogID: "hu")
    var state = PropagationRadio.State()
    state.frequencyKHz = eu.frequencyKHz
    state.bandwidthHz = eu.bandwidthHz
    state.spreadingFactor = eu.spreadingFactor
    state.codingRate = eu.codingRate
    let fixture = try await RefreshFixture.make(device: original, state: state)
    defer { fixture.stop() }

    try await fixture.services.settingsService.refreshDeviceInfo()
    try await waitUntil("the mismatched catalog id should clear") {
      fixture.appState.connectedDevice?.appliedRadioPresetID == nil
        && fixture.appState.connectedDevice?.frequency == eu.frequencyKHz
    }
    let stored = try #require(try await fixture.store.fetchDevice(radioID: original.radioID))
    #expect(stored.appliedRadioPresetID == nil)
    #expect(stored.frequency == eu.frequencyKHz)
    #expect(stored.radioID == original.radioID)
  }

  @Test
  func `a refresh does not recreate a missing row`() async throws {
    let original = try savedDevice(catalogID: nil)
    let fixture = try await RefreshFixture.make(device: original, state: PropagationRadio.State())
    defer { fixture.stop() }
    try await fixture.store.deleteDevice(id: original.id)

    try await fixture.services.settingsService.refreshDeviceInfo()
    await Task.yield()

    #expect(try await fixture.store.fetchDevice(radioID: original.radioID) == nil)
    #expect(try await fixture.store.fetchDevices().isEmpty)
    #expect(fixture.appState.connectedDevice?.nodeName == original.nodeName)
    #expect(fixture.appState.connectedDevice?.id == original.id)
  }

  @Test
  func `a replaced producer cannot apply a refresh to the successor device`() async throws {
    let original = try savedDevice(catalogID: nil)
    var state = PropagationRadio.State()
    state.nodeName = "Renamed"
    state.publicKey = Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
    let fixture = try await RefreshFixture.make(device: original, state: state)
    defer { fixture.stop() }

    let successorRadioID = UUID()
    var successor = DeviceDTO(from: Device(
      radioID: successorRadioID,
      publicKey: Data(repeating: 0x33, count: ProtocolLimits.publicKeySize),
      nodeName: "Successor"
    ))
    successor.lastContactSync = 7
    try await fixture.store.saveDevice(successor)
    let successorRadio = PropagationRadio(state: .init())
    let successorSession = MeshCoreSession(transport: successorRadio)
    try await successorSession.start()
    let successorServices = ServiceContainer(
      session: successorSession,
      dataStore: fixture.store,
      radioID: successorRadioID
    )
    defer {
      Task {
        await successorServices.tearDown()
        await successorSession.stop()
      }
    }

    await fixture.radio.armHoldNextAppStart()
    let refresh = Task { try await fixture.services.settingsService.refreshDeviceInfo() }
    try await waitUntil("the refresh should be held before its observation") {
      await fixture.radio.isHoldingAppStart
    }
    fixture.appState.connectionManager.setTestState(
      services: successorServices,
      connectedDevice: successor
    )
    await fixture.radio.releaseAppStart()
    try await refresh.value
    for _ in 0..<5 {
      await Task.yield()
    }

    #expect(fixture.appState.connectedDevice?.id == successor.id)
    #expect(fixture.appState.connectedDevice?.radioID == successorRadioID)
    #expect(fixture.appState.connectedDevice?.nodeName == "Successor")
    #expect(fixture.appState.connectedDevice?.publicKey == successor.publicKey)
    let successorRow = try #require(try await fixture.store.fetchDevice(radioID: successorRadioID))
    #expect(successorRow.nodeName == "Successor")
    #expect(successorRow.publicKey == successor.publicKey)
    #expect(successorRow.lastContactSync == 7)
    let originalRow = try #require(try await fixture.store.fetchDevice(radioID: original.radioID))
    #expect(originalRow.nodeName == original.nodeName)
    #expect(originalRow.publicKey == original.publicKey)
  }

  @Test
  func `importing a key publishes the radio public key without changing Radio ID`() async throws {
    let fixture = try await RefreshFixture.make(device: savedDevice(catalogID: nil), state: .init())
    defer { fixture.stop() }
    let viewModel = ImportKeyViewModel()
    viewModel.configure(settingsService: { fixture.services.settingsService })
    let generated = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
    viewModel.hexInput = generated.expandedPrivateKey.map { String(format: "%02X", $0) }.joined()
    viewModel.validateAndConfirm()
    #expect(viewModel.showingReplaceAlert)
    #expect(viewModel.errorMessage == nil)

    #expect(await viewModel.importKey())
    try await waitUntil("the imported public key should reach the live device") {
      fixture.appState.connectedDevice?.publicKey == Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
    }
    #expect(viewModel.successTrigger == 1)
    #expect(viewModel.errorMessage == nil)
    let stored = try #require(try await fixture.store.fetchDevice(radioID: fixture.device.radioID))
    #expect(stored.publicKey == Data(repeating: 0x22, count: ProtocolLimits.publicKeySize))
    #expect(stored.radioID == fixture.device.radioID)
    #expect(stored.id == fixture.device.id)
  }

  @Test
  func `regenerating an identity publishes the radio public key`() async throws {
    let fixture = try await RefreshFixture.make(device: savedDevice(catalogID: nil), state: .init())
    defer { fixture.stop() }
    let viewModel = RegenerateIdentityViewModel()
    viewModel.configure(settingsService: { fixture.services.settingsService })
    viewModel.generateKey()
    try await waitUntil("a replacement key should be generated") {
      viewModel.generatedKey != nil
    }

    #expect(await viewModel.replaceIdentity())
    try await waitUntil("the regenerated public key should reach the live device") {
      fixture.appState.connectedDevice?.publicKey == Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
    }
    #expect(viewModel.successTrigger == 1)
    #expect(viewModel.errorMessage == nil)
    let stored = try #require(try await fixture.store.fetchDevice(radioID: fixture.device.radioID))
    #expect(stored.radioID == fixture.device.radioID)
    #expect(stored.id == fixture.device.id)
  }

  @Test
  func `a failed refresh after key import keeps the existing error and public key`() async throws {
    let fixture = try await RefreshFixture.make(device: savedDevice(catalogID: nil), state: .init())
    defer { fixture.stop() }
    await fixture.radio.armFailSecondAppStartAfterImport()
    let viewModel = ImportKeyViewModel()
    viewModel.configure(settingsService: { fixture.services.settingsService })
    let generated = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
    viewModel.hexInput = generated.expandedPrivateKey.map { String(format: "%02X", $0) }.joined()
    viewModel.validateAndConfirm()

    #expect(await viewModel.importKey() == false)
    #expect(viewModel.errorMessage != nil)
    #expect(viewModel.successTrigger == 0)
    #expect(fixture.appState.connectedDevice?.publicKey == fixture.device.publicKey)
  }

  @Test
  func `config import completion publishes name RF and TX without a second reconciliation`() async throws {
    let eu = try #require(RadioPresets.all.first { $0.id == "eu-narrow" })
    let fixture = try await RefreshFixture.make(device: savedDevice(catalogID: "hu"), state: .init())
    defer { fixture.stop() }
    let callbacks = CallbackCount()
    let services = fixture.services
    let manager = fixture.appState.connectionManager
    let deviceID = fixture.device.id
    await services.nodeConfigService.setOnPostIdentityImport {
      await callbacks.increment()
      return try await manager.reconcileIdentity(expectedServices: services, deviceID: deviceID)
    }
    let viewModel = configuredImport(
      fixture: fixture,
      name: "Imported",
      radio: eu,
      txPower: 10
    )

    viewModel.applyConfig(
      nodeConfigService: fixture.services.nodeConfigService,
      settingsService: fixture.services.settingsService,
      radioID: fixture.device.radioID
    )
    try await waitUntil("the import refresh should publish name, RF, and TX") {
      fixture.appState.connectedDevice?.nodeName == "Imported"
        && fixture.appState.connectedDevice?.frequency == eu.frequencyKHz
        && fixture.appState.connectedDevice?.txPower == 10
        && fixture.appState.connectedDevice?.publicKey == Data(repeating: 0x22, count: ProtocolLimits.publicKeySize)
    }

    #expect(viewModel.errorMessage == nil)
    #expect(await callbacks.value == 1)
    let stored = try #require(try await fixture.store.fetchDevice(radioID: fixture.device.radioID))
    #expect(stored.nodeName == "Imported")
    #expect(stored.frequency == eu.frequencyKHz)
    #expect(stored.bandwidth == eu.bandwidthHz)
    #expect(stored.spreadingFactor == eu.spreadingFactor)
    #expect(stored.codingRate == eu.codingRate)
    #expect(stored.txPower == 10)
    #expect(stored.publicKey == Data(repeating: 0x22, count: ProtocolLimits.publicKeySize))
    #expect(stored.radioID == fixture.device.radioID)
    #expect(stored.id == fixture.device.id)
  }

  @Test(arguments: [UInt8(0), 1, 2, 3, 4, 255])
  func `full and partial telemetry agree on the wire and the device`(raw: UInt8) async throws {
    let expected = raw & OtherParamsIntent.telemetryModeMask
    var device = try savedDevice(catalogID: nil)
    device.telemetryModeBase = 2
    device.telemetryModeLoc = 1
    device.telemetryModeEnv = 0
    device.multiAcks = 2
    var state = PropagationRadio.State()
    state.telemetryModeBase = 2
    state.telemetryModeLocation = 1
    state.telemetryModeEnvironment = 0
    state.multiAcks = 2
    let fixture = try await RefreshFixture.make(device: device, state: state)
    defer { fixture.stop() }

    var partial = OtherParamsIntent(telemetryModeBase: 1)
    partial.telemetryModeBase = raw
    try await fixture.services.settingsService.setOtherParamsVerified(
      telemetryModeBase: partial.telemetryModeBase
    )
    try await waitUntil("the partial telemetry write should reach the device") {
      fixture.appState.connectedDevice?.telemetryModeBase == expected
    }
    if expected == 2 {
      #expect(await fixture.radio.otherParamsTelemetry.isEmpty)
    } else {
      let partialWire = try #require(await fixture.radio.otherParamsTelemetry.last)
      #expect(partialWire & 0b11 == expected)
      #expect((partialWire >> 2) & 0b11 == 1)
      #expect((partialWire >> 4) & 0b11 == 0)
    }
    let partialRadio = await fixture.radio.snapshot()
    #expect(partialRadio.telemetryModeBase == expected)
    #expect(partialRadio.telemetryModeLocation == 1)
    #expect(partialRadio.telemetryModeEnvironment == 0)
    #expect(partialRadio.multiAcks == 2)
    let partialLive = try #require(fixture.appState.connectedDevice)
    let partialStored = try #require(try await fixture.store.fetchDevice(radioID: device.radioID))
    for current in [partialLive, partialStored] {
      #expect(current.telemetryModeBase == expected)
      #expect(current.telemetryModeLoc == 1)
      #expect(current.telemetryModeEnv == 0)
      #expect(current.multiAcks == 2)
    }

    var modes = TelemetryModes(base: 1, location: 1, environment: 1)
    modes.base = raw
    modes.location = raw
    modes.environment = raw
    _ = try await fixture.services.settingsService.setOtherParamsVerified(
      autoAddContacts: true,
      telemetryModes: modes,
      advertLocationPolicy: .none,
      multiAcks: 2
    )
    try await waitUntil("the full telemetry write should reach the device") {
      fixture.appState.connectedDevice?.telemetryModeLoc == expected
        && fixture.appState.connectedDevice?.telemetryModeEnv == expected
    }
    let packed = expected | (expected << 2) | (expected << 4)
    let fullWire = try #require(await fixture.radio.otherParamsTelemetry.last)
    #expect(fullWire == packed)
    let fullRadio = await fixture.radio.snapshot()
    #expect(fullRadio.telemetryModeBase == expected)
    #expect(fullRadio.telemetryModeLocation == expected)
    #expect(fullRadio.telemetryModeEnvironment == expected)
    #expect(fullRadio.multiAcks == 2)
    let fullLive = try #require(fixture.appState.connectedDevice)
    let fullStored = try #require(try await fixture.store.fetchDevice(radioID: device.radioID))
    for current in [fullLive, fullStored] {
      #expect(current.telemetryModeBase == expected)
      #expect(current.telemetryModeLoc == expected)
      #expect(current.telemetryModeEnv == expected)
      #expect(current.multiAcks == 2)
      #expect(current.advertLocationPolicy == AdvertLocationPolicy.none.rawValue)
    }
  }

  @Test
  func `empty and failed other-params writes leave the device unchanged`() async throws {
    var device = try savedDevice(catalogID: nil)
    device.telemetryModeBase = 2
    device.multiAcks = 2
    var state = PropagationRadio.State()
    state.telemetryModeBase = 2
    state.multiAcks = 2
    let fixture = try await RefreshFixture.make(device: device, state: state)
    defer { fixture.stop() }

    try await fixture.services.settingsService.setOtherParamsVerified()
    await fixture.radio.armFailNextOtherParams()
    await #expect(throws: SettingsServiceError.self) {
      try await fixture.services.settingsService.setOtherParamsVerified(telemetryModeBase: 4)
    }
    try await fixture.services.settingsService.setOtherParamsVerified(multiAcks: 3)
    try await waitUntil("the later multi-acks write should reach the device") {
      fixture.appState.connectedDevice?.multiAcks == 3
    }

    #expect(await fixture.radio.otherParamsTelemetry.count == 1)
    let wire = try #require(await fixture.radio.otherParamsTelemetry.first)
    #expect(wire & 0b11 == 2)
    #expect(await fixture.radio.snapshot().telemetryModeBase == 2)
    #expect(fixture.appState.connectedDevice?.telemetryModeBase == 2)
    let stored = try #require(try await fixture.store.fetchDevice(radioID: device.radioID))
    #expect(stored.telemetryModeBase == 2)
    #expect(stored.multiAcks == 3)
  }

  @Test
  func `an unknown advertisement policy is stored as the raw byte`() async throws {
    var device = try savedDevice(catalogID: nil)
    device.telemetryModeBase = 2
    device.telemetryModeLoc = 1
    var state = PropagationRadio.State()
    state.telemetryModeBase = 2
    state.telemetryModeLocation = 1
    let fixture = try await RefreshFixture.make(device: device, state: state)
    defer { fixture.stop() }

    try await fixture.services.settingsService.setOtherParamsVerified(advertLocationPolicyRaw: 99)
    try await waitUntil("the policy byte should reach the device") {
      fixture.appState.connectedDevice?.advertLocationPolicy == 99
    }

    let radio = await fixture.radio.snapshot()
    #expect(radio.advertLocationPolicy == 99)
    #expect(radio.telemetryModeBase == 2)
    #expect(radio.telemetryModeLocation == 1)
    let live = try #require(fixture.appState.connectedDevice)
    let stored = try #require(try await fixture.store.fetchDevice(radioID: device.radioID))
    for current in [live, stored] {
      #expect(current.advertLocationPolicy == 99)
      #expect(current.telemetryModeBase == 2)
      #expect(current.telemetryModeLoc == 1)
    }
  }

  @Test
  func `an external config with telemetry mode 4 persists zero`() async throws {
    var device = try savedDevice(catalogID: nil)
    device.telemetryModeBase = 2
    device.telemetryModeLoc = 1
    device.telemetryModeEnv = 0
    device.multiAcks = 2
    var state = PropagationRadio.State()
    state.telemetryModeBase = 2
    state.telemetryModeLocation = 1
    state.telemetryModeEnvironment = 0
    state.multiAcks = 2
    let fixture = try await RefreshFixture.make(device: device, state: state)
    defer { fixture.stop() }

    let url = FileManager.default.temporaryDirectory
      .appendingPathComponent("telemetry-mode-4-\(UUID().uuidString).json")
    let json = """
    {"other_settings":{"telemetry_mode_base":4,"telemetry_mode_location":4,"telemetry_mode_environment":4}}
    """
    try Data(json.utf8).write(to: url)
    defer { try? FileManager.default.removeItem(at: url) }

    let viewModel = NodeConfigImportViewModel()
    viewModel.parseFile(at: url)
    try await waitUntil("the config should parse") {
      viewModel.importedConfig?.otherSettings?.telemetryModeBase == 4
        && viewModel.sections.otherSettings
        && viewModel.errorMessage == nil
    }
    #expect(viewModel.importedConfig?.otherSettings?.telemetryModeLocation == 4)
    #expect(viewModel.importedConfig?.otherSettings?.telemetryModeEnvironment == 4)

    viewModel.prepareConfirmation(nodeConfigService: fixture.services.nodeConfigService)
    try await waitUntil("preview should accept the config") {
      viewModel.showConfirmation || viewModel.errorMessage != nil
    }
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.showConfirmation)

    viewModel.applyConfig(
      nodeConfigService: fixture.services.nodeConfigService,
      settingsService: fixture.services.settingsService,
      radioID: fixture.device.radioID
    )
    try await waitUntil("imported telemetry mode 4 should persist as 0") {
      fixture.appState.connectedDevice?.telemetryModeBase == 0
        && fixture.appState.connectedDevice?.telemetryModeLoc == 0
        && fixture.appState.connectedDevice?.telemetryModeEnv == 0
    }

    let wire = try #require(await fixture.radio.otherParamsTelemetry.last)
    #expect(wire & 0b11 == 0)
    #expect((wire >> 2) & 0b11 == 0)
    #expect((wire >> 4) & 0b11 == 0)
    let radio = await fixture.radio.snapshot()
    #expect(radio.telemetryModeBase == 0)
    #expect(radio.telemetryModeLocation == 0)
    #expect(radio.telemetryModeEnvironment == 0)
    #expect(radio.multiAcks == 2)
    let stored = try #require(try await fixture.store.fetchDevice(radioID: device.radioID))
    #expect(stored.telemetryModeBase == 0)
    #expect(stored.telemetryModeLoc == 0)
    #expect(stored.telemetryModeEnv == 0)
    #expect(stored.multiAcks == 2)
  }
}

@MainActor
private struct RefreshFixture {
  let preferences: IsolatedUserDefaults
  let appState: AppState
  let store: PersistenceStore
  let radio: PropagationRadio
  let session: MeshCoreSession
  let services: ServiceContainer
  let device: DeviceDTO

  static func make(device: DeviceDTO, state: PropagationRadio.State) async throws -> RefreshFixture {
    let preferences = IsolatedUserDefaults()
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      isPlaceholder: true,
      defaults: preferences.defaults
    )
    let store = appState.connectionManager.persistenceStore
    try await store.saveDevice(device)
    let radio = PropagationRadio(state: state)
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    let services = ServiceContainer(session: session, dataStore: store, radioID: device.radioID)
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: services,
      connectedDevice: device
    )
    await appState.wireSettingsEventStream(services: services)
    return RefreshFixture(
      preferences: preferences,
      appState: appState,
      store: store,
      radio: radio,
      session: session,
      services: services,
      device: device
    )
  }

  func stop() {
    let session = session
    let services = services
    let radio = radio
    appState.shutdown()
    Task {
      await radio.releaseAppStart()
      await services.tearDown()
      await session.stop()
    }
  }
}

private func savedDevice(catalogID: String?) throws -> DeviceDTO {
  let peripheralID = UUID()
  var device = DeviceDTO(from: Device(
    id: peripheralID,
    radioID: UUID(),
    publicKey: Data(repeating: 0x11, count: ProtocolLimits.publicKeySize),
    nodeName: "Old",
    blePin: 123_456
  ))
  device.lastContactSync = 4242
  device.isActive = true
  device.ocvPreset = OCVPreset.liIon.rawValue
  device.connectionMethods = [.wifi(host: "10.0.0.8", port: 5000, displayName: nil)]
  device.knownRegions = ["pdx"]
  device.pathHashMode = 2
  device.defaultFloodScopeName = "local"
  device.clientRepeat = false
  device.appliedRadioPresetID = catalogID
  return device
}

private func expectPreservedMetadata(_ device: DeviceDTO, matches original: DeviceDTO) {
  #expect(device.id == original.id)
  #expect(device.radioID == original.radioID)
  #expect(device.lastContactSync == original.lastContactSync)
  #expect(device.isActive == original.isActive)
  #expect(device.ocvPreset == original.ocvPreset)
  #expect(device.connectionMethods == original.connectionMethods)
  #expect(device.knownRegions == original.knownRegions)
  #expect(device.blePin == original.blePin)
  #expect(device.pathHashMode == original.pathHashMode)
  #expect(device.defaultFloodScopeName == original.defaultFloodScopeName)
  #expect(device.clientRepeat == original.clientRepeat)
}

@MainActor
private func configuredImport(
  fixture: RefreshFixture,
  name: String,
  radio: RadioPreset,
  txPower: Int8
) -> NodeConfigImportViewModel {
  let viewModel = NodeConfigImportViewModel()
  viewModel.importedConfig = MeshCoreNodeConfig(
    name: name,
    privateKey: String(repeating: "ab", count: 64),
    radioSettings: .init(
      frequency: radio.frequencyKHz,
      bandwidth: radio.bandwidthHz,
      spreadingFactor: radio.spreadingFactor,
      codingRate: radio.codingRate,
      txPower: txPower
    )
  )
  viewModel.sections.nodeIdentity = true
  viewModel.sections.radioSettings = true
  return viewModel
}

private actor CallbackCount {
  private(set) var value = 0
  func increment() {
    value += 1
  }
}

private actor PropagationRadio: MeshTransport {
  struct State: Sendable {
    var publicKey = Data(repeating: 0x11, count: 32)
    var publicKeyAfterImport = Data(repeating: 0x22, count: 32)
    var nodeName = "Old"
    var latitude: Double = 0
    var longitude: Double = 0
    var frequencyKHz = Device.Defaults.frequency
    var bandwidthHz = Device.Defaults.bandwidth
    var spreadingFactor = Device.Defaults.spreadingFactor
    var codingRate = Device.Defaults.codingRate
    var txPower = Device.Defaults.txPower
    var multiAcks: UInt8 = 0
    var advertLocationPolicy: UInt8 = 0
    var telemetryModeBase: UInt8 = 0
    var telemetryModeLocation: UInt8 = 0
    var telemetryModeEnvironment: UInt8 = 0
    var manualAddContacts = false
  }

  private let inner = MockTransport()
  private var state: State
  private var holdNextAppStart = false
  private var holdContinuation: CheckedContinuation<Void, Never>?
  private var failSecondAppStartAfterImport = false
  private var failNextOtherParams = false
  private var appStartsSinceImport = 0
  private(set) var isHoldingAppStart = false
  private(set) var otherParamsTelemetry: [UInt8] = []

  init(state: State) {
    self.state = state
  }

  func armHoldNextAppStart() {
    holdNextAppStart = true
  }

  func releaseAppStart() {
    isHoldingAppStart = false
    holdContinuation?.resume()
    holdContinuation = nil
  }

  func armFailSecondAppStartAfterImport() {
    failSecondAppStartAfterImport = true
  }

  func armFailNextOtherParams() {
    failNextOtherParams = true
  }

  func snapshot() -> State {
    state
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
    switch data.first.flatMap(CommandCode.init(rawValue:)) {
    case .appStart:
      appStartsSinceImport += 1
      if failSecondAppStartAfterImport, appStartsSinceImport == 2 {
        failSecondAppStartAfterImport = false
        await inner.simulateError(code: ErrorCode.illegalArgument.rawValue)
        return
      }
      if holdNextAppStart {
        holdNextAppStart = false
        isHoldingAppStart = true
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
          holdContinuation = continuation
        }
      }
      await inner.simulateReceive(selfInfoPacket())
    case .deviceQuery:
      var payload = Data(count: 81)
      payload[0] = 10
      payload[1] = 50
      payload[2] = 8
      await inner.simulateReceive(Data([ResponseCode.deviceInfo.rawValue]) + payload)
    case .setRadio:
      if data.count >= 11 {
        state.frequencyKHz = uint32(data, at: 1)
        state.bandwidthHz = uint32(data, at: 5)
        state.spreadingFactor = data[9]
        state.codingRate = data[10]
      }
      await inner.simulateOK()
    case .setTxPower:
      if data.count > 1 {
        state.txPower = Int8(bitPattern: data[1])
      }
      await inner.simulateOK()
    case .setName:
      state.nodeName = String(data: data.dropFirst(), encoding: .utf8) ?? state.nodeName
      await inner.simulateOK()
    case .importPrivateKey:
      appStartsSinceImport = 0
      state.publicKey = state.publicKeyAfterImport
      await inner.simulateOK()
    case .setPathHashMode:
      await inner.simulateOK()
    case .setOtherParams:
      if failNextOtherParams {
        failNextOtherParams = false
        await inner.simulateError(code: ErrorCode.illegalArgument.rawValue)
        return
      }
      if data.count >= 4 {
        state.manualAddContacts = data[1] != 0
        let telemetry = data[2]
        otherParamsTelemetry.append(telemetry)
        state.telemetryModeBase = telemetry & 0b11
        state.telemetryModeLocation = (telemetry >> 2) & 0b11
        state.telemetryModeEnvironment = (telemetry >> 4) & 0b11
        state.advertLocationPolicy = data[3]
        if data.count >= 5 {
          state.multiAcks = data[4]
        }
      }
      await inner.simulateOK()
    default:
      await inner.simulateError(code: ErrorCode.unsupportedCommand.rawValue)
    }
  }

  private func selfInfoPacket() -> Data {
    let telemetry = (state.telemetryModeBase & 0b11)
      | ((state.telemetryModeLocation & 0b11) << 2)
      | ((state.telemetryModeEnvironment & 0b11) << 4)
    var payload = Data()
    payload.append(1)
    payload.append(UInt8(bitPattern: state.txPower))
    payload.append(22)
    payload.append(state.publicKey)
    payload.append(contentsOf: int32Bytes(Int32((state.latitude * 1_000_000).rounded())))
    payload.append(contentsOf: int32Bytes(Int32((state.longitude * 1_000_000).rounded())))
    payload.append(state.multiAcks)
    payload.append(state.advertLocationPolicy)
    payload.append(telemetry)
    payload.append(state.manualAddContacts ? 1 : 0)
    payload.append(contentsOf: uint32Bytes(state.frequencyKHz))
    payload.append(contentsOf: uint32Bytes(state.bandwidthHz))
    payload.append(state.spreadingFactor)
    payload.append(state.codingRate)
    payload.append(contentsOf: state.nodeName.utf8)
    return Data([ResponseCode.selfInfo.rawValue]) + payload
  }
}

private func uint32(_ data: Data, at offset: Int) -> UInt32 {
  UInt32(data[offset])
    | UInt32(data[offset + 1]) << 8
    | UInt32(data[offset + 2]) << 16
    | UInt32(data[offset + 3]) << 24
}

private func uint32Bytes(_ value: UInt32) -> [UInt8] {
  [
    UInt8(value & 0xFF),
    UInt8((value >> 8) & 0xFF),
    UInt8((value >> 16) & 0xFF),
    UInt8((value >> 24) & 0xFF),
  ]
}

private func int32Bytes(_ value: Int32) -> [UInt8] {
  uint32Bytes(UInt32(bitPattern: value))
}
