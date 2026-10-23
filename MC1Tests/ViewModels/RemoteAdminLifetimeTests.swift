import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@MainActor
private final class ReplyGate {
  private(set) var started = false
  private var continuation: CheckedContinuation<String, Error>?

  func wait() async throws -> String {
    started = true
    return try await withCheckedThrowingContinuation { continuation = $0 }
  }

  func succeed(_ value: String) {
    continuation?.resume(returning: value)
    continuation = nil
  }
}

@Suite("Remote admin lifetime")
@MainActor
struct RemoteAdminLifetimeTests {
  private func session(radioID: UUID = UUID(), publicKey: Data, name: String) -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      radioID: radioID,
      publicKey: publicKey,
      name: name,
      role: .repeater,
      permissionLevel: .admin
    )
  }

  private func contact(publicKey: Data, name: String) -> ContactDTO {
    let model = Contact(
      id: UUID(),
      radioID: UUID(),
      publicKey: publicKey,
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: 0
    )
    return ContactDTO(from: model)
  }

  @Test
  func `configure does not write the session display name`() async throws {
    let viewModel = RepeaterSettingsViewModel()
    let node = session(publicKey: Data(repeating: 0x11, count: 32), name: "North")
    let services = try ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true)),
      radioID: node.radioID
    )
    await viewModel.configure(
      repeaterAdminService: { services.repeaterAdminService },
      session: node
    )
    #expect(viewModel.helper.name == nil)
    #expect(viewModel.helper.originalName == nil)
  }

  @Test
  func `a load reply does not overwrite a name the user changed`() {
    let helper = NodeSettingsViewModel()
    helper.setNodeInfo(firmwareVersion: nil, name: "Radio", ownerInfo: nil)
    #expect(helper.name == "Radio")
    helper.name = "Edited"
    helper.setNodeInfo(firmwareVersion: "1.2", name: "Radio", ownerInfo: "owner")
    #expect(helper.name == "Edited")
    #expect(helper.originalName == "Radio")
  }

  @Test
  func `the same radio and public key share one model`() {
    let store = RemoteAdminWorkspaces()
    let radioID = UUID()
    let key = Data(repeating: 0x22, count: 32)
    let first = store.repeater(for: session(radioID: radioID, publicKey: key, name: "North"))
    first.settings.helper.name = "North"
    let again = store.repeater(for: session(radioID: radioID, publicKey: key, name: "North"))
    let other = store.repeater(for: session(radioID: radioID, publicKey: Data(repeating: 0x33, count: 32), name: "South"))

    #expect(again.settings === first.settings)
    #expect(again.settings.helper.name == "North")
    #expect(other.settings !== first.settings)
    #expect(other.settings.helper.name == nil)
  }

  @Test
  func `a repeat reply applies when a later query is cancelled`() async throws {
    let viewModel = RepeaterSettingsViewModel()
    let node = session(publicKey: Data(repeating: 0x44, count: 32), name: "North")
    let gate = ReplyGate()
    viewModel.helper.configure(
      session: node,
      sendCommand: { _, command, _ in
        if command == "get repeat" {
          return try await gate.wait()
        }
        throw CancellationError()
      },
      sendRawCommand: { _, _, _ in throw CancellationError() }
    )
    viewModel.helper.registerLateRecovery(query: "get repeat") { _ in }

    let fetch = Task { await viewModel.fetchBehaviorSettings() }
    try await waitUntil(timeout: .seconds(1), "behavior fetch should send") { gate.started }
    gate.succeed("on")
    await fetch.value

    #expect(viewModel.repeaterEnabled == true)
    #expect(viewModel.behaviorError == false)
  }

  @Test
  func `dismiss during an apply keeps a dirty field and the success alert`() async throws {
    let helper = NodeSettingsViewModel()
    let node = session(publicKey: Data(repeating: 0x55, count: 32), name: "North")
    let gate = ReplyGate()
    helper.configure(
      session: node,
      sendCommand: { _, _, _ in try await gate.wait() },
      sendRawCommand: { _, _, _ in "ok" }
    )
    helper.frequency = 910
    helper.bandwidth = 250
    helper.spreadingFactor = 10
    helper.codingRate = 5
    helper.radioSettingsModified = true

    let apply = Task { await helper.applyRadioSettings() }
    try await waitUntil(timeout: .seconds(1), "apply should send") { gate.started }
    helper.frequency = 915
    gate.succeed("OK")
    await apply.value

    #expect(helper.showSuccessAlert)
    #expect(helper.radioSettingsModified)
    #expect(helper.errorMessage == nil)
    try await Task.sleep(for: .milliseconds(50))
    #expect(helper.showSuccessAlert)
    helper.showSuccessAlert = false
    #expect(helper.showSuccessAlert == false)
  }

  @Test
  func `reset drops the workspace and a late apply does not publish`() async throws {
    let store = RemoteAdminWorkspaces()
    let node = session(publicKey: Data(repeating: 0x66, count: 32), name: "North")
    let models = store.repeater(for: node)
    let gate = ReplyGate()
    models.settings.helper.configure(
      session: node,
      sendCommand: { _, _, _ in try await gate.wait() },
      sendRawCommand: { _, _, _ in "ok" }
    )
    models.settings.helper.frequency = 910
    models.settings.helper.bandwidth = 250
    models.settings.helper.spreadingFactor = 10
    models.settings.helper.codingRate = 5

    let apply = Task { await models.settings.helper.applyRadioSettings() }
    try await waitUntil(timeout: .seconds(1), "apply should send") { gate.started }
    let stale = models.settings
    store.reset()
    gate.succeed("OK")
    await apply.value

    #expect(stale.helper.errorMessage == nil)
    #expect(stale.helper.showSuccessAlert == false)
    let replacement = store.repeater(for: node)
    #expect(replacement.settings !== stale)
    #expect(replacement.settings.helper.name == nil)
  }

  @Test
  func `a mismatched prefix does not write and the caller still applies`() async throws {
    let services = try ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true)),
      radioID: UUID()
    )
    let radioID = UUID()
    let key = Data(repeating: 0x77, count: 32)
    let node = session(radioID: radioID, publicKey: key, name: "North")
    try await services.dataStore.saveRemoteNodeSessionDTO(node)
    let viewModel = RepeaterSettingsViewModel()
    let gate = ReplyGate()
    await viewModel.configure(
      repeaterAdminService: { services.repeaterAdminService },
      session: node
    )
    viewModel.helper.configure(
      session: node,
      sendCommand: { _, command, _ in
        if command == "get repeat" { return try await gate.wait() }
        throw CancellationError()
      },
      sendRawCommand: { _, _, _ in throw CancellationError() }
    )

    let other = RepeaterSettingsViewModel()
    let otherNode = session(radioID: node.radioID, publicKey: Data(repeating: 0x88, count: 32), name: "South")
    await other.configure(
      repeaterAdminService: { services.repeaterAdminService },
      session: otherNode
    )

    let fetch = Task { await viewModel.fetchBehaviorSettings() }
    try await waitUntil(timeout: .seconds(1), "fetch should be waiting") { gate.started }
    let message = ContactMessage(
      senderPublicKeyPrefix: key.prefix(6),
      pathLength: 0,
      textType: 0,
      senderTimestamp: Date(),
      signature: nil,
      text: "on",
      snr: nil
    )
    await services.repeaterAdminService.invokeCLIHandler(message, fromContact: contact(publicKey: key, name: "North"))
    try await Task.sleep(for: .milliseconds(40))
    #expect(other.repeaterEnabled == nil)

    gate.succeed("on")
    await fetch.value
    #expect(viewModel.repeaterEnabled == true)
    #expect(viewModel.behaviorError == false)
  }

  @Test
  func `dismissed neighbor discovery keeps its countdown and does not poll`() async throws {
    let services = try ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true)),
      radioID: UUID()
    )
    let node = session(radioID: UUID(), publicKey: Data(repeating: 0x99, count: 32), name: "North")
    let first = RepeaterStatusViewModel()
    let second = RepeaterStatusViewModel()
    let gate = PauseGate()
    first.configure(
      repeaterAdminService: { services.repeaterAdminService },
      contactService: { nil },
      nodeSnapshotService: { nil },
      deviceHashSize: { nil }
    )
    second.configure(
      repeaterAdminService: { services.repeaterAdminService },
      contactService: { nil },
      nodeSnapshotService: { nil },
      deviceHashSize: { nil }
    )
    first.discoveryTickForTesting = .milliseconds(15)
    first.pollIntervalTicksForTesting = 1
    first.fetchAllNeighborsForTesting = { await gate.pause() }
    second.discoveryTickForTesting = .milliseconds(15)
    second.pollIntervalTicksForTesting = 1
    second.fetchAllNeighborsForTesting = { await gate.pause() }

    first.startDiscovery(for: node)
    try await waitUntil(timeout: .seconds(1), "first poll should enter") {
      first.requestNeighborsEntryCountForTesting == 1
    }
    let countdown = first.discoverySecondsRemaining
    #expect(countdown > 0)
    first.pauseDiscoveryPolls()
    gate.resume()
    try await Task.sleep(for: .milliseconds(80))
    #expect(first.discoverySecondsRemaining > 0)
    #expect(first.requestNeighborsEntryCountForTesting == 1)

    second.startDiscovery(for: node)
    second.pauseDiscoveryPolls()
    try await Task.sleep(for: .milliseconds(80))
    #expect(second.requestNeighborsEntryCountForTesting == 0)

    first.stopDiscovery()
    #expect(first.discoverySecondsRemaining == 0)
  }

  @Test
  func `session stop does not publish a timeout and the replacement reply applies`() async throws {
    let transport = MockTransport()
    let mesh = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(
        defaultTimeout: 0.4,
        binaryRequestOverallTimeout: 0.4,
        binaryRequestRetransmitInterval: 0.05
      )
    )
    try await startSession(mesh, transport: transport)
    let radioID = UUID()
    let services = try ServiceContainer(
      session: mesh,
      dataStore: PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true)),
      radioID: radioID
    )
    let key = Data(repeating: 0xAB, count: 32)
    let node = session(radioID: radioID, publicKey: key, name: "North")
    try await services.dataStore.saveRemoteNodeSessionDTO(node)

    let settings = RepeaterSettingsViewModel()
    let status = RepeaterStatusViewModel()
    await settings.configure(
      repeaterAdminService: { services.repeaterAdminService },
      session: node
    )
    status.configure(
      repeaterAdminService: { services.repeaterAdminService },
      contactService: { nil },
      nodeSnapshotService: { nil },
      deviceHashSize: { nil }
    )
    status.helper.session = node
    await status.registerHandlers()

    let behavior = Task { await settings.fetchBehaviorSettings() }
    let statusTask = Task { await status.requestStatus(for: node) }
    try await waitUntil(timeout: .seconds(1), "exchange should send") {
      await transport.sentData.count > 1
    }
    let sentAtStop = await transport.sentData.count
    await mesh.stop()
    try await Task.sleep(for: .milliseconds(120))
    #expect(await transport.sentData.count == sentAtStop)
    await behavior.value
    await statusTask.value
    #expect(settings.behaviorError == false)
    #expect(settings.helper.errorMessage == nil)
    #expect(status.helper.statusSectionError == nil)

    let replacementTransport = MockTransport()
    let replacementSession = MeshCoreSession(transport: replacementTransport)
    let replacement = try ServiceContainer(
      session: replacementSession,
      dataStore: services.dataStore,
      radioID: radioID
    )
    await settings.configure(
      repeaterAdminService: { replacement.repeaterAdminService },
      session: node
    )
    status.configure(
      repeaterAdminService: { replacement.repeaterAdminService },
      contactService: { nil },
      nodeSnapshotService: { nil },
      deviceHashSize: { nil }
    )
    await status.registerHandlers()
    let message = ContactMessage(
      senderPublicKeyPrefix: key.prefix(6),
      pathLength: 0,
      textType: 0,
      senderTimestamp: Date(),
      signature: nil,
      text: "on",
      snr: nil
    )
    await replacement.repeaterAdminService.invokeCLIHandler(
      message,
      fromContact: contact(publicKey: key, name: "North")
    )
    #expect(settings.repeaterEnabled == true)

    let statusReply = StatusResponse(
      publicKeyPrefix: key.prefix(6),
      battery: 80,
      txQueueLength: 0,
      noiseFloor: -100,
      lastRSSI: -70,
      packetsReceived: 1,
      packetsSent: 1,
      airtime: 1,
      uptime: 10,
      sentFlood: 0,
      sentDirect: 0,
      receivedFlood: 0,
      receivedDirect: 0,
      fullEvents: 0,
      lastSNR: 5,
      directDuplicates: 0,
      floodDuplicates: 0,
      rxAirtime: 1
    )
    await replacement.repeaterAdminService.invokeStatusHandler(statusReply)
    #expect(status.helper.status != nil)
  }

  private func startSession(_ session: MeshCoreSession, transport: MockTransport) async throws {
    let startTask = Task { try await session.start() }
    try await waitUntil(timeout: .seconds(1), "app start should send") {
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
}

@MainActor
private final class PauseGate {
  private var continuation: CheckedContinuation<Void, Never>?

  func pause() async {
    await withCheckedContinuation { continuation = $0 }
  }

  func resume() {
    continuation?.resume()
    continuation = nil
  }
}
