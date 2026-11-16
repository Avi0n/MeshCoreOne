import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@MainActor
final class RadioWiFiContractHarness {
  private(set) var transport = RadioWiFiContractTransport()
  let clock = TestClock()
  let viewModel = RadioWiFiViewModel()
  private(set) var session: MeshCoreSession
  var settings: SettingsService?
  var connectionState: DeviceConnectionState = .ready
  var transportType: TransportType? = .bluetooth
  var device: DeviceDTO
  private var workers: [Task<Void, Never>] = []
  private var tools: [CLIToolViewModel] = []

  private init(scope: RadioCLICorrelationScope) {
    session = MeshCoreSession(
      transport: transport, configuration: SessionConfiguration(defaultTimeout: 30), clock: clock,
      radioCLICorrelationScope: scope
    )
    device = DeviceDTO(
      id: UUID(), radioID: UUID(), publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize),
      nodeName: "Contract Radio", firmwareVersion: 14, firmwareVersionString: "1.18.0",
      manufacturerName: "Test", buildDate: "2026-10-18", maxContacts: 100, maxChannels: 1,
      frequency: 915_000, bandwidth: 250_000, spreadingFactor: 7, codingRate: 5,
      txPower: 22, maxTxPower: 22, latitude: 0, longitude: 0, blePin: 0, manualAddContacts: false,
      multiAcks: 0, telemetryModeBase: 0, telemetryModeLoc: 0, telemetryModeEnv: 0,
      advertLocationPolicy: 0, lastConnected: Date(), lastContactSync: 0, isActive: true,
      ocvPreset: nil, customOCVArrayString: nil
    )
    settings = SettingsService(session: session, radioWiFiState: RadioWiFiState(radioID: device.radioID))
    viewModel.configure(
      settingsService: { self.settings }, connectionState: { self.connectionState },
      connectedDevice: { self.device }, currentTransportType: { self.transportType }
    )
  }

  func start(_ body: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
    let task = Task { await body() }
    workers.append(task)
    return task
  }

  func makeTools() -> CLIToolViewModel {
    let tools = CLIToolViewModel()
    tools.configure(
      dependencies: CLIToolViewModel.Dependencies(
        repeaterAdminService: { nil }, remoteNodeService: { nil }, settingsService: { self.settings },
        dataStore: { nil }, radioID: { self.device.radioID }, connectedDevice: { self.device },
        powerOffRadio: {}, powerOffCancelAvailable: { true }
      ), localDeviceName: "Contract Radio", sendSelfAdvert: { _ in }
    )
    tools.activeSession = .local(deviceName: "Contract Radio")
    self.tools.append(tools)
    return tools
  }

  func replaceSettingsRuntime() async throws -> SettingsService {
    await settings?.retireRadioWiFiOperations()
    await session.stop()
    await session.waitForQuiescence()
    #expect(await session.subscriberCountForTest == 0)
    #expect(clock.sleeperCount == 0)
    #expect(await transport.isConnected == false)
    transport = RadioWiFiContractTransport()
    session = MeshCoreSession(
      transport: transport, configuration: SessionConfiguration(defaultTimeout: 30), clock: clock
    )
    try await session.start()
    let successor = SettingsService(session: session, radioWiFiState: RadioWiFiState(radioID: device.radioID))
    settings = successor
    return successor
  }

  func close() async {
    await transport.releaseAll()
    for tool in tools {
      tool.cleanup()
    }
    tools.removeAll()
    await session.stop()
    for task in workers {
      await task.value
    }
    workers.removeAll()
    await settings?.retireRadioWiFiOperations()
    await session.waitForQuiescence()
    viewModel.configure(
      settingsService: { nil }, connectionState: { .disconnected },
      connectedDevice: { nil }, currentTransportType: { nil }
    )
    await viewModel.noteSessionReadiness()
    settings = nil
    #expect(await session.subscriberCountForTest == 0)
    #expect(clock.sleeperCount == 0)
    #expect(await transport.isConnected == false)
  }

  static func withHarness(
    scope: RadioCLICorrelationScope = RadioCLICorrelationScope(),
    _ body: (RadioWiFiContractHarness) async throws -> Void
  ) async throws {
    let harness = RadioWiFiContractHarness(scope: scope)
    do {
      try await harness.session.start()
      try await body(harness)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }
}
