import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Radio WiFi reset evidence")
@MainActor
struct RadioWiFiResetEvidenceTests {
  @Test(arguments: [false, true])
  func `consumed reboot permits a fresh WiFi session only while runtime WiFi is enabled`(enabled: Bool) async throws {
    let firmware = RadioWiFiResetFirmware(scenario: .consumed)
    let session = MeshCoreSession(transport: firmware)
    let successor = MeshCoreSession(transport: firmware)
    let settings = SettingsService(session: session)
    let state = settings.radioWiFiState
    let outcome: Result<Void, any Error>
    do {
      try await session.start(disconnectTransportOnFailure: false)
      try #require(await firmware.savedEnabled)
      try #require(await firmware.runtimeEnabled)
      let initialRevision = state.revision
      let revision = try #require(try await settings.setRadioWiFiEnabled(
        enabled, expectedContextID: settings.radioWiFiContextID, expectedRevision: initialRevision
      ))
      let setter = "set wifi.enabled \(enabled ? 1 : 0)"
      let setterFrames = await firmware.sentData.filter { data in
        guard data.first == CommandCode.runCliCommand.rawValue else { return false }
        let command = String(decoding: data.dropFirst(), as: UTF8.self)
        return ConnectionTestRadioTransport.splitCLICommand(command).command == setter
      }
      try #require(setterFrames.count == 1)
      try #require(state.rebootPending)
      try #require(revision > initialRevision)
      try #require(state.stableRevision() == revision)
      try #require(await firmware.savedEnabled == enabled)
      try #require(await firmware.runtimeEnabled)
      try await settings.reboot(expectedContextID: settings.radioWiFiContextID, expectedRevision: revision)
      let rebootFrameWasSubmitted = await firmware.sentData.contains(PacketBuilder.reboot())
      try #require(rebootFrameWasSubmitted)
      try #require(await firmware.rebootSubmissions == 1)
      try #require(await firmware.savedEnabled == enabled)
      try #require(await firmware.runtimeEnabled == enabled)
      await settings.retireRadioWiFiOperations()
      await session.stop()
      await session.waitForQuiescence()
      try #require(await !firmware.isConnected)
      let framesBeforeReconnect = await firmware.sentData.count

      if enabled {
        try await successor.start(disconnectTransportOnFailure: false)
        let time = try await successor.getTime()
        #expect(time.timeIntervalSince1970 > 0)
        #expect(await firmware.isConnected)
        let laterTimeFrameWasSubmitted = await firmware.sentData.dropFirst(framesBeforeReconnect).contains(
          PacketBuilder.getTime()
        )
        #expect(laterTimeFrameWasSubmitted)
      } else {
        let error = await #expect(throws: MeshTransportError.self) {
          try await successor.start(disconnectTransportOnFailure: false)
        }
        if let error { #expect(error == .notConnected) }
        #expect(await firmware.isConnected == false)
        #expect(await firmware.sentData.count == framesBeforeReconnect)
      }
      #expect(await firmware.savedEnabled == enabled)
      #expect(await firmware.runtimeEnabled == enabled)
      #expect(await firmware.rebootSubmissions == 1)
      outcome = .success(())
    } catch {
      outcome = .failure(error)
    }
    await firmware.releaseResetStats()
    await settings.retireRadioWiFiOperations()
    await session.stop()
    await successor.stop()
    await firmware.disconnect()
    await session.waitForQuiescence()
    await successor.waitForQuiescence()
    #expect(await firmware.isConnected == false)
    try outcome.get()
  }

  @Test(arguments: ResetScenario.allCases)
  func `same-request socket recovery clears apply evidence only when firmware actually reset`(
    scenario: ResetScenario
  ) async throws {
    let suite = "test.reset-evidence.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let model = try PersistenceStore.createContainer(inMemory: true)
    let firmware = RadioWiFiResetFirmware(scenario: scenario)
    let pairing = MockAccessorySetupKitService()
    pairing.isSessionActive = false
    let manager = ConnectionManager(
      modelContainer: model, defaults: defaults, stateMachine: MockBLEStateMachine(),
      transport: ConnectionTestRadioTransport(),
      pairing: AccessorySetupPairingService(accessorySetupKit: pairing), makeWiFiTransport: { firmware }
    )
    manager.testWiFiReconnectCooldown = 0
    do {
      _ = try await manager.connectViaWiFi(host: "reset.local", port: 4242)
      let first = try #require(manager.currentConnection)
      let requestID = first.requestID
      let settings = first.services.settingsService
      let state = settings.radioWiFiState
      let enabled = scenario.consumesReboot
      let initialRevision = state.revision
      let revision = try #require(try await settings.setRadioWiFiEnabled(
        enabled, expectedContextID: settings.radioWiFiContextID, expectedRevision: initialRevision
      ))
      let setter = "set wifi.enabled \(enabled ? 1 : 0)"
      let setterFrames = await firmware.sentData.filter { data in
        guard data.first == CommandCode.runCliCommand.rawValue else { return false }
        let command = String(decoding: data.dropFirst(), as: UTF8.self)
        return ConnectionTestRadioTransport.splitCLICommand(command).command == setter
      }
      try #require(setterFrames.count == 1)
      try #require(state.rebootPending)
      try #require(revision > initialRevision)
      try #require(state.stableRevision() == revision)
      try #require(await firmware.savedEnabled == enabled)
      try #require(await firmware.runtimeEnabled)
      try await settings.reboot(expectedContextID: settings.radioWiFiContextID, expectedRevision: revision)
      #expect(await firmware.rebootSubmissions == 1)
      #expect(await firmware.runtimeEnabled)
      await firmware.loseSocket()
      try await waitUntil("same request should recover to a new fully completed context") {
        manager.currentConnection?.generation != first.generation
          && manager.installedLink?.didCompleteSync == true && manager.connectionState == .ready
      }
      #expect(manager.currentConnection?.requestID == requestID)
      #expect(manager.services?.settingsService.radioWiFiState === state)
      #expect(state.rebootPending == !scenario.provesReset)
      if scenario == .consumedWithFailedDisplayReads {
        #expect(await firmware.displayReads.isEmpty)
      }
      #expect(try await manager.services?.settingsService.getTime() != nil)
      #expect(await firmware.savedEnabled == enabled)
      #expect(await firmware.runtimeEnabled)
      if scenario == .notConsumed {
        let replacement = try #require(manager.services?.settingsService)
        guard case let .supported(probe) = try await replacement.probeRadioWiFiSettings() else {
          throw SettingsServiceError.invalidResponse
        }
        let observation = try #require(await replacement.readRadioWiFiObservation(probe: probe))
        #expect(observation.error == nil)
        #expect(observation.networkNameWasRead)
        #expect(observation.associated != nil)
        #expect(observation.ipAddressWasRead)
        #expect(!observation.enabled)
        #expect(observation.associated == true)
        #expect(state.rebootPending)
      }
    } catch {
      _ = await manager.disconnect(reason: .userInitiated)
      await manager.retirementTask?.value
      UserDefaults().removePersistentDomain(forName: suite)
      throw error
    }
    _ = await manager.disconnect(reason: .userInitiated)
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    UserDefaults().removePersistentDomain(forName: suite)
    #expect(manager.services == nil)
    #expect(await firmware.isConnected == false)
  }

  @Test(arguments: HeldStatsInterference.allCases)
  func `old reset proof cannot clear evidence after a mutation or replacement request`(
    interference: HeldStatsInterference
  ) async throws {
    let suite = "test.reset-evidence-held.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let model = try PersistenceStore.createContainer(inMemory: true)
    let firmware = RadioWiFiResetFirmware(scenario: .consumed)
    let pairing = MockAccessorySetupKitService()
    pairing.isSessionActive = false
    let manager = ConnectionManager(
      modelContainer: model, defaults: defaults, stateMachine: MockBLEStateMachine(),
      transport: ConnectionTestRadioTransport(),
      pairing: AccessorySetupPairingService(accessorySetupKit: pairing), makeWiFiTransport: { firmware }
    )
    manager.testWiFiReconnectCooldown = 0
    var mutation: Task<UInt64?, any Error>?
    var replacement: Task<ConnectionResult, any Error>?
    do {
      _ = try await manager.connectViaWiFi(host: "reset.local", port: 4242)
      let first = try #require(manager.currentConnection)
      let settings = first.services.settingsService
      let state = settings.radioWiFiState
      let initialRevision = state.revision
      let revision = try #require(try await settings.setRadioWiFiEnabled(
        true, expectedContextID: settings.radioWiFiContextID, expectedRevision: initialRevision
      ))
      let setterFrames = await firmware.sentData.filter { data in
        guard data.first == CommandCode.runCliCommand.rawValue else { return false }
        let command = String(decoding: data.dropFirst(), as: UTF8.self)
        return ConnectionTestRadioTransport.splitCLICommand(command).command == "set wifi.enabled 1"
      }
      try #require(setterFrames.count == 1)
      try #require(state.rebootPending)
      try #require(revision > initialRevision)
      try #require(state.stableRevision() == revision)
      try #require(await firmware.savedEnabled)
      try #require(await firmware.runtimeEnabled)
      try await settings.reboot(expectedContextID: settings.radioWiFiContextID, expectedRevision: revision)
      try #require(await firmware.rebootSubmissions == 1)
      try #require(await firmware.savedEnabled)
      try #require(await firmware.runtimeEnabled)
      await firmware.holdResetStats()
      await firmware.loseSocket()
      try await waitUntil("post-recovery reset proof should hold its stats reply") { await firmware.resetStatsHeld }
      let recovered = try #require(manager.installedLink)
      let completion = try #require(recovered.completionWorker)
      #expect(recovered.generation != first.generation)
      #expect(state.expectedRebootID != nil)
      switch interference {
      case .mutation:
        let currentSettings = recovered.services.settingsService
        mutation = Task {
          try await currentSettings.setRadioWiFiEnabled(
            false, expectedContextID: currentSettings.radioWiFiContextID, expectedRevision: state.revision
          )
        }
        try await waitUntil("new WiFi mutation should revoke the captured proof before its reply") {
          state.stableRevision() == nil
        }
      case .replacementRequest:
        replacement = Task { try await manager.connectViaWiFi(host: "replacement.local", port: 4242) }
        try await waitUntil("explicit replacement should supersede the proof owner") {
          manager.connectionRequestID != first.requestID
        }
      }
      await firmware.releaseResetStats()
      _ = try await mutation?.value
      _ = try await replacement?.value
      _ = await completion.value
      #expect(state.rebootPending)
      #expect(state.expectedRebootID == nil)
      #expect(manager.services?.settingsService.radioWiFiState === state)
      #expect(try await manager.services?.settingsService.getTime() != nil)
    } catch {
      await firmware.releaseResetStats()
      _ = await mutation?.result
      _ = await replacement?.result
      _ = await manager.disconnect(reason: .userInitiated)
      await manager.retirementTask?.value
      UserDefaults().removePersistentDomain(forName: suite)
      throw error
    }
    _ = await manager.disconnect(reason: .userInitiated)
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    UserDefaults().removePersistentDomain(forName: suite)
    #expect(await firmware.isConnected == false)
  }

  enum HeldStatsInterference: Sendable, CaseIterable {
    case mutation, replacementRequest
  }

  enum ResetScenario: Sendable, CaseIterable {
    case notConsumed, consumed, consumedWithFailedDisplayReads, shortUptime
    case normalProgression, rolloverCompatible, unsupportedBefore, failedAfter

    var consumesReboot: Bool {
      self == .consumed || self == .consumedWithFailedDisplayReads || self == .shortUptime
        || self == .rolloverCompatible || self == .unsupportedBefore || self == .failedAfter
    }

    var provesReset: Bool {
      self == .consumed || self == .consumedWithFailedDisplayReads
    }

    var before: UInt32 {
      switch self {
      case .shortUptime: 1
      case .rolloverCompatible: 4_294_967
      default: 1000
      }
    }

    var after: UInt32 {
      switch self {
      case .consumed, .consumedWithFailedDisplayReads, .shortUptime, .unsupportedBefore, .failedAfter: 1
      case .rolloverCompatible: 0
      case .normalProgression: 1001
      case .notConsumed: 1000
      }
    }
  }
}

private actor RadioWiFiResetFirmware: WiFiMeshTransport {
  private let wire = ConnectionTestRadioTransport()
  private let scenario: RadioWiFiResetEvidenceTests.ResetScenario
  private(set) var savedEnabled = true
  private(set) var runtimeEnabled = true
  private(set) var rebootSubmissions = 0
  private(set) var displayReads: [String] = []
  private(set) var resetStatsHeld = false
  private var holdsResetStats = false
  private var uptime: UInt32

  init(scenario: RadioWiFiResetEvidenceTests.ResetScenario) {
    self.scenario = scenario
    uptime = scenario.before
  }

  var connectionInfo: (host: String, port: UInt16)? {
    get async { await wire.connectionInfo }
  }

  var isConnected: Bool {
    get async { await wire.isConnected }
  }

  var sentData: [Data] {
    get async { await wire.sentData }
  }

  var receivedData: AsyncStream<Data> {
    get async { await wire.receivedData }
  }

  var supportsPipelinedReads: Bool {
    true
  }

  func connect() async throws {
    guard runtimeEnabled else { throw MeshTransportError.notConnected }
    try await wire.connect()
  }

  func disconnect() async {
    await wire.disconnect()
  }

  func setConnectionInfo(host: String, port: UInt16) async {
    await wire.setConnectionInfo(host: host, port: port)
  }

  func setDisconnectionHandler(_ handler: @escaping @Sendable (Error?, CFAbsoluteTime) -> Void) async {
    await wire.setDisconnectionHandler(handler)
  }

  func loseSocket() async {
    await wire.simulateRemoteLoss()
  }

  func holdResetStats() async {
    holdsResetStats = true
    await wire.holdReplies(for: .getStats)
  }

  func releaseResetStats() async {
    holdsResetStats = false
    resetStatsHeld = false
    await wire.releaseReplies(for: .getStats)
  }

  func send(_ data: Data) async throws {
    guard let code = data.first else { return }
    if code == CommandCode.runCliCommand.rawValue {
      let original = String(decoding: data.dropFirst(), as: UTF8.self)
      let (prefix, command) = ConnectionTestRadioTransport.splitCLICommand(original)
      let isDisplayRead = ["get wifi.enabled", "get wifi.ssid", "get wifi.status", "get wifi.ip"].contains(command)
      if isDisplayRead { displayReads.append(command) }
      let reply: String
      if isDisplayRead, scenario == .consumedWithFailedDisplayReads {
        reply = "Error: display read unavailable"
      } else if command == "set wifi.enabled 0" || command == "set wifi.enabled 1" {
        savedEnabled = command == "set wifi.enabled 1"
        reply = "> wifi.enabled is now \(savedEnabled ? 1 : 0) (reboot to apply)"
      } else if command == "get wifi.enabled" {
        reply = savedEnabled ? "> 1" : "> 0"
      } else if command == "get wifi.status" {
        reply = runtimeEnabled ? "> connected" : "> disconnected"
      } else if command == "get wifi.ssid" {
        reply = "> synthetic-network"
      } else if command == "get wifi.ip" {
        reply = runtimeEnabled ? "> 192.0.2.1" : "> (not connected)"
      } else {
        reply = command.hasPrefix("get ") ? "> 0" : "OK"
      }
      await wire.setCLIResponse(original, response: prefix + reply)
    } else if code == CommandCode.reboot.rawValue {
      rebootSubmissions += 1
      if scenario.consumesReboot {
        runtimeEnabled = savedEnabled
      }
      uptime = scenario.after
      await wire.setResponsePackets(for: .reboot, packets: [])
    } else if code == CommandCode.getStats.rawValue {
      if scenario == .unsupportedBefore, rebootSubmissions == 0 {
        await wire.setResponsePackets(for: .getStats, packets: [Data([ResponseCode.error.rawValue, 1])])
        try await wire.send(data)
        return
      }
      if scenario == .failedAfter, rebootSubmissions > 0 {
        await wire.setResponsePackets(for: .getStats, packets: [Data([ResponseCode.error.rawValue, 5])])
        try await wire.send(data)
        return
      }
      var packet = Data([ResponseCode.stats.rawValue, 0, 0x74, 0x0E])
      packet.append(withUnsafeBytes(of: uptime.littleEndian) { Data($0) })
      packet.append(contentsOf: [0, 0, 0])
      await wire.setResponsePackets(for: .getStats, packets: [packet])
    }
    try await wire.send(data)
    if code == CommandCode.getStats.rawValue, rebootSubmissions > 0, holdsResetStats {
      resetStatsHeld = true
    }
  }
}
