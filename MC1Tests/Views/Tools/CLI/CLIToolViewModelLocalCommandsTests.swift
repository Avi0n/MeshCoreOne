import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("CLIToolViewModel local radio commands")
@MainActor
struct CLIToolViewModelLocalCommandsTests {
  // MARK: - Reads

  @Test
  func `clock prints the device time in UTC`() async {
    // 1_700_000_000 = 2023-11-14 22:13:20 UTC
    let mock = MockConfigurationSession(deviceTime: Date(timeIntervalSince1970: 1_700_000_000))
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "clock")

    #expect(lastResponse(viewModel) == "22:13 - 14/11/2023 UTC")
  }

  @Test
  func `clock sync sets the device time and prints OK`() async throws {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "clock sync")

    #expect(await mock.setTimeCalls.count == 1)
    let response = try #require(lastResponse(viewModel))
    #expect(response.hasPrefix("OK - clock set:"))
  }

  @Test
  func `get name prints the device name`() async {
    let mock = MockConfigurationSession(name: "FieldNode")
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get name")

    #expect(lastResponse(viewModel) == "> FieldNode")
  }

  @Test
  func `get bat prints millivolts`() async {
    let mock = MockConfigurationSession(batteryMillivolts: 3921)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get bat")

    #expect(lastResponse(viewModel) == "> 3921 mV")
  }

  @Test
  func `get radio prints comma-separated params`() async {
    let mock = MockConfigurationSession(
      radioFrequency: 869.525, radioBandwidth: 250, radioSpreadingFactor: 11, radioCodingRate: 5
    )
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get radio")

    #expect(lastResponse(viewModel) == "> 869.525,250,11,5")
  }

  @Test
  func `get public key prints uppercase hex`() async {
    let mock = MockConfigurationSession(publicKey: Data([0xAB, 0x01, 0xFF]))
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get public.key")

    #expect(lastResponse(viewModel) == "> AB01FF")
  }

  @Test
  func `get lat prints the latitude at six-decimal precision`() async {
    // Six decimals distinguishes the coordinate formatter from the 3-decimal
    // `decimal` used by get radio.
    let mock = MockConfigurationSession(latitude: 37.774929)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get lat")

    #expect(lastResponse(viewModel) == "> 37.774929")
  }

  @Test
  func `get lon prints the longitude trimming trailing zeros`() async {
    let mock = MockConfigurationSession(longitude: -122.41941)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get lon")

    #expect(lastResponse(viewModel) == "> -122.41941")
  }

  @Test
  func `ver prints version and build`() async {
    let mock = MockConfigurationSession(version: "1.13.0", firmwareBuild: "deadbeef")
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "ver")

    #expect(lastResponse(viewModel) == "1.13.0 (Build: deadbeef)")
  }

  @Test
  func `board prints the model`() async {
    let mock = MockConfigurationSession(model: "Heltec V3")
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "board")

    #expect(lastResponse(viewModel) == "Heltec V3")
  }

  // MARK: - Writes

  @Test
  func `set tx round-trips and prints OK`() async {
    let mock = MockConfigurationSession(txPower: 5)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set tx 22")

    #expect(await mock.setTxPowerCalls == [22])
    #expect(await mock.setCustomVarCalls.isEmpty) // typed key never falls through
    #expect(lastResponse(viewModel) == "OK")
  }

  @Test
  func `set radio converts units and prints OK`() async throws {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    // set radio requires confirmation
    await run(viewModel, "set radio 869.525,250,11,5")
    await run(viewModel, "y")

    let call = try #require(await mock.setRadioCalls.first)
    #expect(abs(call.frequency - 869.525) < 0.0001)
    #expect(abs(call.bandwidth - 250) < 0.0001)
    #expect(call.spreadingFactor == 11)
    #expect(call.codingRate == 5)
    #expect(lastResponse(viewModel) == "OK")
  }

  @Test
  func `set freq changes frequency and keeps other radio params`() async throws {
    let mock = MockConfigurationSession(
      radioFrequency: 869.525, radioBandwidth: 250, radioSpreadingFactor: 11, radioCodingRate: 5
    )
    let viewModel = makeViewModel(mock: mock)

    // set freq requires confirmation
    await run(viewModel, "set freq 869.9")
    await run(viewModel, "y")

    let call = try #require(await mock.setRadioCalls.first)
    #expect(abs(call.frequency - 869.9) < 0.0001)
    #expect(abs(call.bandwidth - 250) < 0.0001)
    #expect(call.spreadingFactor == 11)
    #expect(call.codingRate == 5)
    #expect(lastResponse(viewModel) == "OK")
  }

  @Test
  func `set lat combines with the seeded longitude`() async throws {
    // San Francisco
    let mock = MockConfigurationSession(latitude: 0, longitude: -122.4194)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set lat 37.7749")

    let call = try #require(await mock.setCoordinatesCalls.first)
    #expect(abs(call.latitude - 37.7749) < 0.0001)
    #expect(abs(call.longitude - -122.4194) < 0.0001)
    #expect(lastResponse(viewModel) == "OK")
  }

  @Test
  func `set lon combines with the seeded latitude`() async throws {
    // San Francisco
    let mock = MockConfigurationSession(latitude: 37.7749, longitude: 0)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set lon -122.4194")

    let call = try #require(await mock.setCoordinatesCalls.first)
    #expect(abs(call.latitude - 37.7749) < 0.0001)
    #expect(abs(call.longitude - -122.4194) < 0.0001)
    #expect(lastResponse(viewModel) == "OK")
  }

  @Test
  func `set multi acks uses the connected device as base`() async {
    let mock = MockConfigurationSession(manualAddContacts: true)
    let device = makeDevice(manualAddContacts: true, multiAcks: 0)
    let viewModel = makeViewModel(mock: mock, device: device)

    await run(viewModel, "set multi.acks 1")

    #expect(await mock.setOtherParamsCalls.map(\.multiAcks) == [1])
    #expect(lastResponse(viewModel) == "OK")
  }

  @Test
  func `set path hash mode prints OK`() async {
    let mock = MockConfigurationSession(pathHashMode: 0)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set path.hash.mode 2")

    #expect(await mock.setPathHashModeCalls == [2])
    #expect(lastResponse(viewModel) == "OK")
  }

  @Test
  func `set name refreshes the prompt name`() async {
    let mock = MockConfigurationSession(name: "Old")
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set name New Name")

    #expect(await mock.setNameCalls == ["New Name"])
    #expect(viewModel.localDeviceName == "New Name")
    #expect(viewModel.promptText.hasPrefix("New Name"))
  }

  // MARK: - Confirmation flow

  @Test
  func `reboot requires confirmation and executes on yes`() async {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "reboot")
    #expect(viewModel.pendingConfirmation == .reboot)
    #expect(viewModel.promptText.contains("confirm reboot?"))

    await run(viewModel, "y")

    #expect(await mock.rebootCalled)
    #expect(lastResponse(viewModel) == "OK - rebooting")
    #expect(viewModel.pendingConfirmation == nil)
  }

  @Test
  func `reboot is cancelled on no`() async {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "reboot")
    await run(viewModel, "no")

    #expect(await mock.rebootCalled == false)
    #expect(viewModel.pendingConfirmation == nil)
    #expect(viewModel.outputLines.contains { $0.text.contains("cancelled") })
  }

  @Test
  func `confirmation answer is not added to history`() async {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "reboot")
    await run(viewModel, "y")

    #expect(viewModel.commandHistory.contains("reboot"))
    #expect(!viewModel.commandHistory.contains("y"))
  }

  @Test
  func `cancel while pending clears the confirmation`() async {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "reboot")
    #expect(viewModel.pendingConfirmation == .reboot)

    viewModel.cancelCurrentCommand()

    #expect(viewModel.pendingConfirmation == nil)
    #expect(await mock.rebootCalled == false)
    #expect(viewModel.outputLines.contains { $0.text.contains("cancelled") })
  }

  // MARK: - Advert closure

  @Test
  func `advert sends zero-hop`() async {
    let mock = MockConfigurationSession()
    let floods = FloodRecorder()
    let viewModel = makeViewModel(mock: mock, floods: floods)

    await run(viewModel, "advert")

    #expect(floods.recorded == [false])
    #expect(lastResponse(viewModel) == "OK - zero-hop advert sent")
  }

  @Test
  func `floodadv sends flood`() async {
    let mock = MockConfigurationSession()
    let floods = FloodRecorder()
    let viewModel = makeViewModel(mock: mock, floods: floods)

    await run(viewModel, "floodadv")

    #expect(floods.recorded == [true])
    #expect(lastResponse(viewModel) == "OK - flood advert sent")
  }

  // MARK: - Errors

  @Test
  func `verification failure renders an error line`() async {
    let mock = MockConfigurationSession(txPower: 5)
    await mock.setSuppressWrites(true)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set tx 22")

    #expect(viewModel.outputLines.last?.type == .error)
  }

  @Test
  func `set radio bad arguments prints usage`() async throws {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set radio 869.525")

    let last = try #require(viewModel.outputLines.last)
    #expect(last.type == .error)
    #expect(last.text.contains("set radio"))
  }

  // MARK: - Custom variables (bare get/set)

  @Test
  func `get custom prints a sorted var dictionary`() async {
    let mock = MockConfigurationSession(customVars: ["gps_interval": "60", "gps": "0"])
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get custom")

    #expect(lastResponse(viewModel) == "2 vars\ngps=0\ngps_interval=60")
  }

  @Test
  func `get custom with no vars prints the empty string`() async {
    let mock = MockConfigurationSession(customVars: [:])
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get custom")

    #expect(lastResponse(viewModel) == "no custom var")
  }

  @Test
  func `bare get of a custom var prints the value or reports it unknown`() async {
    let mock = MockConfigurationSession(customVars: ["gps": "1"])
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get gps")
    #expect(lastResponse(viewModel) == "> 1")

    await run(viewModel, "get nope")
    #expect(lastResponse(viewModel) == "Unknown var nope")
  }

  @Test
  func `a stripped-key miss reports the stripped name`() async {
    // MC1 strips the leading `_` before the runner sees the key, so the miss
    // names `nope`, not `_nope` (a documented divergence from meshcore-cli).
    let mock = MockConfigurationSession(customVars: [:])
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get _nope")

    #expect(lastResponse(viewModel) == "Unknown var nope")
  }

  @Test
  func `wifi_ssid round-trips through a bare set then get`() async {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set wifi_ssid MyNetwork")

    let calls = await mock.setCustomVarCalls
    #expect(calls.count == 1)
    #expect(calls.first?.key == "wifi_ssid")
    #expect(calls.first?.value == "MyNetwork")
    #expect(lastResponse(viewModel) == "Var wifi_ssid set to MyNetwork")
    #expect(viewModel.pendingConfirmation == nil)

    await run(viewModel, "get wifi_ssid")
    #expect(lastResponse(viewModel) == "> MyNetwork")
  }

  @Test
  func `a set custom var mapping an illegal-argument device error prints the parity string`() async {
    let mock = MockConfigurationSession()
    await mock.failNextSetCustomVar(code: ErrorCode.illegalArgument.rawValue)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set bogus 1")

    #expect(lastResponse(viewModel) == "can't find custom var")
  }

  @Test
  func `a set custom var surfaces a non-illegal-argument device error via the error path`() async throws {
    let mock = MockConfigurationSession()
    await mock.failNextSetCustomVar(code: 1) // unsupported command
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "set gps 1")

    let last = try #require(viewModel.outputLines.last)
    #expect(last.type == .error)
    #expect(last.text != "can't find custom var")
  }

  @Test
  func `old firmware that cannot enumerate vars errors instead of faking a miss`() async throws {
    let mock = MockConfigurationSession(customVars: [:])
    await mock.failNextGetCustomVars(code: 1)
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get nope")

    let last = try #require(viewModel.outputLines.last)
    #expect(last.type == .error)
    #expect(last.text != "Unknown var nope")
  }

  @Test
  func `a successful custom-var fetch feeds completion keys`() async {
    let mock = MockConfigurationSession(customVars: ["gps": "0", "gps_interval": "60"])
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "get custom")

    #expect(Set(viewModel.completionEngine.customVarKeys) == ["gps", "gps_interval"])
  }

  @Test
  func `the connection prefetch populates completion keys`() async {
    let mock = MockConfigurationSession(customVars: ["gps": "0", "wifi_ssid": "off"])
    let viewModel = makeViewModel(mock: mock)

    await viewModel.updateCustomVarKeysForCompletion()

    #expect(Set(viewModel.completionEngine.customVarKeys) == ["gps", "wifi_ssid"])
  }

  @Test
  func `disconnect flushes learned custom-var completion keys`() async {
    // A live admin service marks the connection up; reconfiguring with none is
    // the disconnect that must flush keys learned from the prior radio, so they
    // don't leak into the next radio's local completion.
    let admin = makeAdminService()
    let mock = MockConfigurationSession(customVars: ["gps": "0", "wifi_ssid": "off"])
    let settings = SettingsService(session: mock)
    let viewModel = CLIToolViewModel()
    viewModel.configure(
      dependencies: CLIToolViewModel.Dependencies(
        repeaterAdminService: { admin },
        remoteNodeService: { nil },
        settingsService: { settings },
        dataStore: { nil },
        radioID: { UUID() },
        connectedDevice: { nil }
      ),
      localDeviceName: "TestDevice",
      sendSelfAdvert: { _ in }
    )
    viewModel.activeSession = .local(deviceName: "TestDevice")

    await viewModel.updateCustomVarKeysForCompletion()
    #expect(!viewModel.completionEngine.customVarKeys.isEmpty)

    viewModel.configure(
      dependencies: CLIToolViewModel.Dependencies(
        repeaterAdminService: { nil },
        remoteNodeService: { nil },
        settingsService: { nil },
        dataStore: { nil },
        radioID: { nil },
        connectedDevice: { nil }
      ),
      localDeviceName: "TestDevice",
      sendSelfAdvert: { _ in }
    )

    #expect(viewModel.completionEngine.customVarKeys.isEmpty)
  }

  @Test
  func `get custom on a remote session does not touch the settings service`() async {
    let mock = MockConfigurationSession(customVars: ["gps": "0"])
    let viewModel = makeViewModel(mock: mock)
    viewModel.activeSession = .remote(id: UUID(), name: "Repeater", pathLength: 1)

    await run(viewModel, "get custom")

    #expect(await mock.readCount == 0)
  }

  // MARK: - Disconnected

  @Test(arguments: [
    "clock", "ver", "board", "get name", "set tx 5", "reboot",
    "get custom", "get gps", "set wifi_ssid x",
  ])
  func `commands report not connected when disconnected`(line: String) async {
    let viewModel = CLIToolViewModel()
    viewModel.configure(
      dependencies: CLIToolViewModel.Dependencies(
        repeaterAdminService: { nil },
        remoteNodeService: { nil },
        settingsService: { nil },
        dataStore: { nil },
        radioID: { nil },
        connectedDevice: { nil }
      ),
      localDeviceName: "TestDevice",
      sendSelfAdvert: { _ in }
    )
    viewModel.activeSession = .local(deviceName: "TestDevice")

    await run(viewModel, line)
    // reboot needs a confirm before it reaches the guard
    if viewModel.pendingConfirmation != nil {
      await run(viewModel, "y")
    }

    #expect(viewModel.outputLines.last?.type == .error)
  }

  // MARK: - Remote passthrough regression

  @Test
  func `local get on a remote session does not touch the settings service`() async {
    let mock = MockConfigurationSession(name: "FieldNode")
    let viewModel = makeViewModel(mock: mock)
    viewModel.activeSession = .remote(id: UUID(), name: "Repeater", pathLength: 1)

    await run(viewModel, "get name")

    #expect(await mock.readCount == 0)
    #expect(!viewModel.outputLines.contains { $0.text == "> FieldNode" })
  }

  @Test
  func `ghost text is suppressed while a confirmation is pending`() async {
    let mock = MockConfigurationSession()
    let viewModel = makeViewModel(mock: mock)

    await run(viewModel, "reboot")
    #expect(viewModel.pendingConfirmation == .reboot)

    viewModel.currentInput = "y"
    viewModel.updateGhostText(cursorAtEnd: true)

    #expect(viewModel.ghostText == "")
  }

  // MARK: - Helpers

  private func makeViewModel(
    mock: MockConfigurationSession,
    device: DeviceDTO? = nil,
    floods: FloodRecorder? = nil
  ) -> CLIToolViewModel {
    let service = SettingsService(session: mock)
    let viewModel = CLIToolViewModel()
    viewModel.configure(
      dependencies: CLIToolViewModel.Dependencies(
        repeaterAdminService: { nil },
        remoteNodeService: { nil },
        settingsService: { service },
        dataStore: { nil },
        radioID: { UUID() },
        connectedDevice: { device }
      ),
      localDeviceName: "TestDevice",
      sendSelfAdvert: { flood in floods?.record(flood) }
    )
    viewModel.activeSession = .local(deviceName: "TestDevice")
    return viewModel
  }

  /// A live `RepeaterAdminService` whose collaborators are never driven; the
  /// disconnect test only compares its identity across two `configure` calls.
  private func makeAdminService() -> RepeaterAdminService {
    let session = MeshCoreSession(transport: MockTransport())
    let store = ParkingContactStore()
    let remoteNode = RemoteNodeService(session: session, dataStore: store, keychainService: KeychainService())
    return RepeaterAdminService(session: session, remoteNodeService: remoteNode, dataStore: store)
  }

  private func run(_ viewModel: CLIToolViewModel, _ line: String) async {
    viewModel.executeCommand(line)
    for _ in 0..<200 where viewModel.isWaitingForResponse {
      await Task.yield()
    }
  }

  private func lastResponse(_ viewModel: CLIToolViewModel) -> String? {
    viewModel.outputLines.last { $0.type == .response }?.text
  }

  private func makeDevice(manualAddContacts: Bool, multiAcks: UInt8) -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "TestDevice",
      firmwareVersion: 9,
      firmwareVersionString: "1.13.0",
      manufacturerName: "Test",
      buildDate: "",
      maxContacts: 100,
      maxChannels: 16,
      frequency: 869_525,
      bandwidth: 250_000,
      spreadingFactor: 11,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 22,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      clientRepeat: false,
      pathHashMode: 0,
      manualAddContacts: manualAddContacts,
      autoAddConfig: 0,
      autoAddMaxHops: 0,
      multiAcks: multiAcks,
      telemetryModeBase: 0,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: []
    )
  }
}

// MARK: - Flood Recorder

@MainActor
final class FloodRecorder {
  private(set) var recorded: [Bool] = []
  func record(_ flood: Bool) {
    recorded.append(flood)
  }
}
