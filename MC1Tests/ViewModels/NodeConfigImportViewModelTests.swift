import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("NodeConfigImportViewModel error localization")
@MainActor
struct NodeConfigImportViewModelTests {
  @Test
  func `A radio-out-of-range error maps to the localized field label and template`() {
    let message = NodeConfigServiceError.invalidRadioSettings(field: .frequency).userFacingMessage
    #expect(message == L10n.Settings.ConfigImport.Error.radioOutOfRange(
      L10n.Settings.ConfigImport.Field.frequency
    ))
  }

  @Test
  func `A position-coordinate error maps to the localized position template`() {
    let message = NodeConfigServiceError.invalidCoordinate(field: .positionLatitude).userFacingMessage
    #expect(message == L10n.Settings.ConfigImport.Error.positionInvalid(
      L10n.Settings.ConfigImport.Field.latitude
    ))
  }

  @Test
  func `A contact-coordinate error maps to the localized contact template with the contact name`() {
    let message = NodeConfigServiceError.invalidCoordinate(field: .contactLongitude(name: "C1")).userFacingMessage
    #expect(message == L10n.Settings.ConfigImport.Error.contactCoordinateInvalid(
      "C1", L10n.Settings.ConfigImport.Field.longitude
    ))
  }

  @Test
  func `A non-NodeConfigServiceError falls through to its localizedDescription`() {
    let error: any Error = URLError(.timedOut)
    #expect(error.userFacingMessage == error.localizedDescription)
  }

  @Test
  func `An invalid-out-path error maps to the localized template with the contact name`() {
    let message = NodeConfigServiceError.invalidOutPath(name: "X").userFacingMessage
    #expect(message == L10n.Settings.ConfigImport.Error.invalidOutPath("X"))
    #expect(message.contains("X"))
  }

  @Test
  func `A contact-capacity error maps to the localized template with the needed and available counts`() {
    let message = NodeConfigServiceError.contactCapacityExceeded(needed: 3, available: 1).userFacingMessage
    #expect(message == L10n.Settings.ConfigImport.Error.contactCapacityExceeded(3, 1))
  }

  @Test
  func `A cancellation message distinguishes a partial cancel from a clean one`() {
    #expect(NodeConfigImportViewModel.cancellationMessage(didApplyAnyWrite: true)
      == L10n.Settings.ConfigImport.cancelledPartial)
    #expect(NodeConfigImportViewModel.cancellationMessage(didApplyAnyWrite: false)
      == L10n.Settings.ConfigImport.cancelled)
  }

  @Test
  func `A failure message wraps the localized description only when a write already landed`() {
    let error = DummyError()
    let clean = error.userFacingMessage
    #expect(NodeConfigImportViewModel.failureMessage(for: error, didApplyAnyWrite: true)
      == L10n.Settings.ConfigImport.failedPartial(clean))
    #expect(NodeConfigImportViewModel.failureMessage(for: error, didApplyAnyWrite: false) == clean)
  }

  @Test
  func `Leaving the screen resets a loaded preview back to the file picker`() {
    let viewModel = NodeConfigImportViewModel()
    viewModel.importedConfig = MeshCoreNodeConfig(name: "Node", channels: [])
    viewModel.errorMessage = "stale error"
    viewModel.sections.channels = true
    viewModel.showConfirmation = true

    viewModel.handleDismissal()

    #expect(viewModel.importedConfig == nil)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.sections == ConfigSections())
    #expect(!viewModel.showConfirmation)
  }

  @Test
  func `Leaving the screen mid-import preserves the in-flight import state`() {
    let viewModel = NodeConfigImportViewModel()
    viewModel.importedConfig = MeshCoreNodeConfig(name: "Node", channels: [])
    viewModel.isApplying = true
    viewModel.applyProgress = 0.5

    viewModel.handleDismissal()

    #expect(viewModel.importedConfig != nil)
    #expect(viewModel.isApplying)
    #expect(viewModel.applyProgress == 0.5)
  }

  @Test
  func `a dismissed import and a preset leave live and stored RF on the final operation`() async throws {
    let eu = try #require(RadioPresets.all.first { $0.id == "eu-narrow" })
    let usa = try #require(RadioPresets.all.first { $0.id == "us-ca" })
    let preferences = IsolatedUserDefaults()
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      isPlaceholder: true,
      defaults: preferences.defaults
    )
    let radioID = UUID()
    let store = appState.connectionManager.persistenceStore
    var device = DeviceDTO(from: Device(
      radioID: radioID,
      publicKey: Data(repeating: 0x11, count: ProtocolLimits.publicKeySize),
      nodeName: "Fixture"
    ))
    device.appliedRadioPresetID = "hu"
    try await store.saveDevice(device)

    let radio = ImportScriptedRadio()
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: services,
      connectedDevice: device
    )
    await appState.wireSettingsEventStream(services: services)
    defer {
      appState.shutdown()
      Task {
        await services.tearDown()
        await session.stop()
      }
    }

    let gate = ImportSuspendGate()
    await services.nodeConfigService.setOnPostIdentityImport {
      await gate.park()
      return nil
    }
    let viewModel = NodeConfigImportViewModel()
    viewModel.importedConfig = MeshCoreNodeConfig(
      privateKey: String(repeating: "ab", count: 64),
      radioSettings: .init(
        frequency: eu.frequencyKHz,
        bandwidth: eu.bandwidthHz,
        spreadingFactor: eu.spreadingFactor,
        codingRate: eu.codingRate,
        txPower: Device.Defaults.txPower
      )
    )
    viewModel.sections.nodeIdentity = true
    viewModel.sections.radioSettings = true

    viewModel.applyConfig(
      nodeConfigService: services.nodeConfigService,
      settingsService: services.settingsService,
      radioID: radioID
    )
    try await waitUntil("import should park after its early radio read") {
      gate.isParked && viewModel.isApplying
    }
    viewModel.handleDismissal()
    #expect(viewModel.isApplying)
    #expect(viewModel.importedConfig != nil)

    _ = try await services.settingsService.applyRadioPresetVerified(usa)
    try await waitUntil("the preset event should reach the live device before import continues") {
      appState.connectedDevice?.frequency == usa.frequencyKHz
        && appState.connectedDevice?.appliedRadioPresetID == usa.id
    }
    gate.release()

    try await waitUntil("the import's radio observation should replace the preset") {
      if viewModel.errorMessage != nil { return true }
      return appState.connectedDevice?.frequency == eu.frequencyKHz
        && appState.connectedDevice?.appliedRadioPresetID == nil
        && !viewModel.isApplying
    }
    #expect(viewModel.errorMessage == nil)
    let stored = try #require(try await store.fetchDevice(radioID: radioID))
    #expect(stored.frequency == eu.frequencyKHz)
    #expect(stored.bandwidth == eu.bandwidthHz)
    #expect(stored.spreadingFactor == eu.spreadingFactor)
    #expect(stored.codingRate == eu.codingRate)
    #expect(stored.appliedRadioPresetID == nil)
    #expect(await radio.frequencyKHzSnapshot == eu.frequencyKHz)
  }
}

private actor ImportScriptedRadio: MeshTransport {
  private let inner = MockTransport()
  private var frequencyKHz = Device.Defaults.frequency
  private var bandwidthHz = Device.Defaults.bandwidth
  private var spreadingFactor = Device.Defaults.spreadingFactor
  private var codingRate = Device.Defaults.codingRate
  private var txPower = Device.Defaults.txPower

  var frequencyKHzSnapshot: UInt32 {
    frequencyKHz
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
      await inner.simulateReceive(selfInfoPacket())
    case .deviceQuery:
      var payload = Data(count: 81)
      payload[0] = 10
      payload[1] = 50
      payload[2] = 8
      await inner.simulateReceive(Data([ResponseCode.deviceInfo.rawValue]) + payload)
    case .setRadio:
      if data.count >= 11 {
        frequencyKHz = uint32LE(data, at: 1)
        bandwidthHz = uint32LE(data, at: 5)
        spreadingFactor = data[9]
        codingRate = data[10]
      }
      await inner.simulateOK()
    case .setTxPower, .importPrivateKey, .setPathHashMode, .setName:
      await inner.simulateOK()
    default:
      await inner.simulateError(code: ErrorCode.unsupportedCommand.rawValue)
    }
  }

  private func selfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(UInt8(bitPattern: txPower))
    payload.append(22)
    payload.append(Data(repeating: 0x11, count: 32))
    payload.append(contentsOf: [0, 0, 0, 0, 0, 0, 0, 0])
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(contentsOf: uint32Bytes(frequencyKHz))
    payload.append(contentsOf: uint32Bytes(bandwidthHz))
    payload.append(spreadingFactor)
    payload.append(codingRate)
    payload.append(contentsOf: "Fixture".utf8)
    return Data([ResponseCode.selfInfo.rawValue]) + payload
  }
}

private func uint32LE(_ data: Data, at offset: Int) -> UInt32 {
  UInt32(data[offset])
    | UInt32(data[offset + 1]) << 8
    | UInt32(data[offset + 2]) << 16
    | UInt32(data[offset + 3]) << 24
}

private func uint32Bytes(_ value: UInt32) -> [UInt8] {
  [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8((value >> 24) & 0xFF)]
}

private final class ImportSuspendGate: @unchecked Sendable {
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

private struct DummyError: Error {}
