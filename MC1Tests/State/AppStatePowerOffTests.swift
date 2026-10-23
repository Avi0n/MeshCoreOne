import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("AppState power off")
@MainActor
struct AppStatePowerOffTests {
  @Test
  func `a reply leaves the radio connected`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let appState = try await makeAppState(session: session)
    defer { appState.shutdown() }
    try await startSession(session, transport: transport)
    defer { Task { await session.stop() } }

    let task = Task { try await appState.powerOffRadio() }
    try await waitUntil("poweroff should be sent") {
      await transport.sentData.count == 2
    }
    let sent = await transport.sentData[1]
    #expect(sent.first == 0x42)
    #expect(Data(sent.dropFirst()) == Data(PacketBuilder.powerOffCommand.utf8))
    await transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))

    do {
      try await task.value
      Issue.record("expected powerOffUnsupported")
    } catch let error as SettingsServiceError {
      guard case .powerOffUnsupported = error else {
        Issue.record("expected powerOffUnsupported, got \(error)")
        return
      }
    } catch {
      Issue.record("expected SettingsServiceError, got \(error)")
    }

    #expect(appState.connectionManager.connectionIntent.wantsConnection)
    #expect(appState.connectionState == .ready)
  }

  @Test
  func `silence disconnects as a user power off`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let appState = try await makeAppState(session: session)
    defer { appState.shutdown() }
    try await startSession(session, transport: transport)

    try await appState.powerOffRadio()

    #expect(appState.connectionManager.connectionIntent.isUserDisconnected)
    #expect(appState.connectionState == .disconnected)
  }

  @Test
  func `a replacement connection survives a power off that was already waiting`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let appState = try await makeAppState(session: session)
    defer { appState.shutdown() }
    try await startSession(session, transport: transport)

    await transport.holdNextSend()
    let task = Task { try await appState.powerOffRadio() }
    try await waitUntil("poweroff write should park") { await transport.sendIsWaiting }

    let replacementTransport = MockTransport()
    let replacement = MeshCoreSession(transport: replacementTransport)
    let replacementDevice = makeDevice(firmwareVersion: 14)
    let replacementServices = try await ServiceContainer.forTesting(session: replacement)
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: replacementServices,
      session: replacement,
      connectedDevice: replacementDevice,
      connectionIntent: .wantsConnection()
    )
    await transport.failHeldSend()
    await transport.releaseHeldSend()

    await #expect(throws: PowerOffSuperseded.self) { try await task.value }
    #expect(appState.connectionManager.session === replacement)
    #expect(appState.connectionManager.connectionIntent.wantsConnection)
    #expect(appState.connectionState == .ready)
    #expect(appState.connectionManager.connectedDevice?.id == replacementDevice.id)
  }

  @Test
  func `replacement during activity teardown leaves the new connection's session state`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let appState = try await makeAppState(session: session)
    defer { appState.shutdown() }
    try await startSession(session, transport: transport)

    let probe = CleanupProbe()
    appState.powerOffEndActivityOverride = {
      probe.endActivityStarted = true
      await probe.gate.wait()
    }
    let task = Task {
      do {
        try await appState.powerOffRadio()
        probe.outcome = .success(())
      } catch {
        probe.outcome = .failure(error)
      }
    }

    try await waitUntil("power-off should reach activity teardown") { probe.endActivityStarted }
    let replacement = try await installReplacementConnection(on: appState)
    defer {
      replacement.eventTask.cancel()
      Task { await replacement.services.tearDown() }
    }
    await probe.gate.open()
    await task.value

    #expect(isPowerOffSuperseded(probe.outcome))
    #expect(replacement.eventTask.isCancelled == false)
    #expect(appState.settingsEventsTask != nil)
    #expect(appState.remoteAdminWorkspaces.repeater(for: replacement.node).settings === replacement.settings)
    #expect(appState.connectionManager.session === replacement.session)
  }

  @Test
  func `a same-session installation during teardown revokes cleanup before the guard`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let appState = try await makeAppState(session: session)
    let teardownGate = Gate()
    defer {
      appState.shutdown()
      Task {
        await teardownGate.open()
        await session.stop()
      }
    }
    try await startSession(session, transport: transport)
    let device = try #require(appState.connectedDevice)

    let probe = CleanupProbe()
    var teardownStarted = false
    appState.connectionManager.testConnectionTeardownHandler = {
      teardownStarted = true
      await teardownGate.wait()
    }
    appState.powerOffEndActivityOverride = {
      probe.endActivityStarted = true
      await probe.gate.wait()
    }
    let task = Task {
      do {
        try await appState.powerOffRadio()
        probe.outcome = .success(())
      } catch {
        probe.outcome = .failure(error)
      }
    }

    try await waitUntil("owned disconnect should park") { teardownStarted }
    let services = try await ServiceContainer.forTesting(session: session, radioID: device.radioID)
    let eventTask = makeOpenEventTask()
    let node = makeRemoteSession(radioID: device.radioID)
    let settings = appState.remoteAdminWorkspaces.repeater(for: node).settings
    appState.settingsEventsTask = eventTask
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: services,
      session: session,
      connectedDevice: device,
      connectionIntent: .wantsConnection()
    )
    defer {
      eventTask.cancel()
      Task { await services.tearDown() }
    }

    await teardownGate.open()
    try await waitUntil("cleanup should pass the guard or reach activity teardown") {
      probe.endActivityStarted || probe.outcome != nil
    }
    if probe.endActivityStarted, probe.outcome == nil {
      await probe.gate.open()
    }
    await task.value

    #expect(probe.endActivityStarted == false)
    #expect(isPowerOffSuperseded(probe.outcome))
    #expect(eventTask.isCancelled == false)
    #expect(appState.settingsEventsTask != nil)
    #expect(appState.remoteAdminWorkspaces.repeater(for: node).settings === settings)
  }

  @Test
  func `owned cleanup still clears session resources`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let appState = try await makeAppState(session: session)
    defer { appState.shutdown() }
    try await startSession(session, transport: transport)

    let eventTask = makeOpenEventTask()
    defer { eventTask.cancel() }
    let node = makeRemoteSession()
    let settings = appState.remoteAdminWorkspaces.repeater(for: node).settings
    appState.settingsEventsTask = eventTask
    appState.powerOffEndActivityOverride = {}

    try await appState.powerOffRadio()

    #expect(appState.settingsEventsTask == nil)
    #expect(eventTask.isCancelled)
    #expect(appState.remoteAdminWorkspaces.repeater(for: node).settings !== settings)
    #expect(appState.connectionManager.connectionIntent.isUserDisconnected)
  }

  private func makeAppState(session: MeshCoreSession) async throws -> AppState {
    let suite = "test.poweroff.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      isPlaceholder: true,
      defaults: defaults
    )
    let services = try await ServiceContainer.forTesting(session: session)
    appState.connectionManager.setTestState(
      connectionState: .ready,
      services: services,
      session: session,
      connectedDevice: makeDevice(firmwareVersion: 14),
      connectionIntent: .wantsConnection()
    )
    return appState
  }
}

private func makeDevice(firmwareVersion: UInt8) -> DeviceDTO {
  DeviceDTO(
    id: UUID(),
    radioID: UUID(),
    publicKey: Data(repeating: 0x01, count: 32),
    nodeName: "TestDevice",
    firmwareVersion: firmwareVersion,
    firmwareVersionString: "1.18.0",
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
    manualAddContacts: false,
    autoAddConfig: 0,
    autoAddMaxHops: 0,
    multiAcks: 0,
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

private func startSession(
  _ session: MeshCoreSession,
  transport: MockTransport
) async throws {
  let startTask = Task { try await session.start() }
  try await waitUntil("transport should send appStart before session starts") {
    await transport.sentData.count == 1
  }
  await transport.simulateReceive(makeSelfInfoPacket())
  try await startTask.value
}

private func makeSelfInfoPacket() -> Data {
  var payload = Data([ResponseCode.selfInfo.rawValue])
  payload.append(1)
  payload.append(UInt8(bitPattern: 22))
  payload.append(UInt8(bitPattern: 22))
  payload.append(Data(repeating: 0x01, count: 32))
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(0)
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) })
  payload.append(11)
  payload.append(5)
  return payload
}

@MainActor
private final class CleanupProbe {
  var endActivityStarted = false
  var outcome: Result<Void, Error>?
  let gate = Gate()
}

private struct ReplacementConnection {
  let session: MeshCoreSession
  let services: ServiceContainer
  let eventTask: Task<Void, Never>
  let node: RemoteNodeSessionDTO
  let settings: RepeaterSettingsViewModel
}

@MainActor
private func installReplacementConnection(on appState: AppState) async throws -> ReplacementConnection {
  let session = MeshCoreSession(transport: MockTransport())
  let device = makeDevice(firmwareVersion: 14)
  let services = try await ServiceContainer.forTesting(session: session, radioID: device.radioID)
  let eventTask = makeOpenEventTask()
  let node = makeRemoteSession(radioID: device.radioID)
  let settings = appState.remoteAdminWorkspaces.repeater(for: node).settings
  appState.settingsEventsTask = eventTask
  appState.connectionManager.setTestState(
    connectionState: .ready,
    services: services,
    session: session,
    connectedDevice: device,
    connectionIntent: .wantsConnection()
  )
  return ReplacementConnection(
    session: session,
    services: services,
    eventTask: eventTask,
    node: node,
    settings: settings
  )
}

private func makeRemoteSession(radioID: UUID = UUID()) -> RemoteNodeSessionDTO {
  RemoteNodeSessionDTO(
    radioID: radioID,
    publicKey: Data(repeating: 0xAB, count: 32),
    name: "Repeater",
    role: .repeater
  )
}

private func makeOpenEventTask() -> Task<Void, Never> {
  Task {
    while !Task.isCancelled {
      try? await Task.sleep(for: .seconds(60))
    }
  }
}

private func isPowerOffSuperseded(_ result: Result<Void, Error>?) -> Bool {
  guard case let .failure(error) = result else { return false }
  return error is PowerOffSuperseded
}

private actor Gate {
  private var waiter: CheckedContinuation<Void, Never>?
  private var isOpen = false

  func wait() async {
    if isOpen { return }
    await withCheckedContinuation { waiter = $0 }
  }

  func open() {
    isOpen = true
    waiter?.resume()
    waiter = nil
  }
}
