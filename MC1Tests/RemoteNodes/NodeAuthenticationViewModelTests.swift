import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Node authentication view model", .serialized, .timeLimit(.minutes(1)))
struct NodeAuthenticationViewModelTests {
  @Test
  @MainActor
  func `failed flood fallback restores direct path, preserves toggle off, and allows retry after window`() async throws {
    let fallbackKey = AppStorageKey.remoteFloodFallback.rawValue
    let previousFallback = UserDefaults.standard.object(forKey: fallbackKey)
    UserDefaults.standard.set(true, forKey: fallbackKey)
    defer {
      if let previousFallback {
        UserDefaults.standard.set(previousFallback, forKey: fallbackKey)
      } else {
        UserDefaults.standard.removeObject(forKey: fallbackKey)
      }
    }

    let radioID = UUID()
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(transport: transport, clock: clock)
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    let repeaterKey = Data(repeating: 0x6A, count: 32)
    let directPath = Data([0x12, 0x34])

    let contact = ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: repeaterKey,
      name: "Ridge",
      typeRawValue: ContactType.repeater.rawValue,
      outPathLength: 2,
      outPath: directPath,
      lastHeardTimestamp: 0
    ))
    try await store.saveContact(contact)

    let startTask = Task { try await session.start() }
    try await waitUntil("appStart sent") { await transport.sentData.contains { $0.first == CommandCode.appStart.rawValue } }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value

    await services.startEventMonitoring(radioID: radioID)
    defer { Task { await session.stop() } }

    let generation = UUID()
    let viewModel = NodeAuthenticationViewModel(contact: contact, role: .repeater, requireAdmin: true)
    viewModel.password = "wrong"
    viewModel.configure(.init(
      roomServerService: { services.roomServerService },
      remoteNodeService: { services.remoteNodeService },
      repeaterAdminService: { services.repeaterAdminService },
      contactService: { services.contactService },
      dataStore: { services.dataStore },
      connectionGeneration: { generation },
      bestAvailableLocation: { nil }
    ))

    var successCount = 0
    #expect(!viewModel.isWaitingForRadio)
    viewModel.authenticate { _ in successCount += 1 }
    #expect(viewModel.isWaitingForRadio)

    try await waitUntil("direct sendLogin sent") {
      await transport.sentData.filter { $0.first == CommandCode.sendLogin.rawValue }.count == 1
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 15000))
    try await waitUntil("direct login timer parked") {
      viewModel.authSecondsRemaining != nil && clock.sleeperCount > 0
    }
    #expect(!viewModel.isWaitingForRadio)
    clock.advance(by: .seconds(25))

    try await waitUntil("fallback snapshot getContactByKey sent") {
      await transport.sentData.filter { $0.first == CommandCode.getContactByKey.rawValue }.count == 1
    }
    await transport.simulateReceive(makeContactPacket(
      publicKey: repeaterKey, outPathLength: 2, outPath: directPath, name: "Ridge"
    ))
    try await waitUntil("fallback resetPath sent") {
      await transport.sentData.filter { $0.first == CommandCode.resetPath.rawValue }.count == 1
    }
    await transport.simulateReceive(Data([ResponseCode.ok.rawValue]))

    try await waitUntil("flood sendLogin sent") {
      await transport.sentData.filter { $0.first == CommandCode.sendLogin.rawValue }.count == 2
    }
    #expect(viewModel.isRetryingViaFlood)
    #expect(!viewModel.isWaitingForRadio)
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x05, 0x06, 0x07, 0x08]), suggestedTimeoutMs: 15000))
    try await waitUntil("flood login timer parked") {
      viewModel.authSecondsRemaining != nil && clock.sleeperCount > 0
    }
    clock.advance(by: .seconds(25))

    try await waitUntil("restore still-flood getContactByKey sent") {
      await transport.sentData.filter { $0.first == CommandCode.getContactByKey.rawValue }.count == 2
    }
    await transport.simulateReceive(makeContactPacket(
      publicKey: repeaterKey, outPathLength: PacketBuilder.floodPathSentinel, outPath: Data(), name: "Ridge"
    ))
    try await waitUntil("setPath getContactByKey sent") {
      await transport.sentData.filter { $0.first == CommandCode.getContactByKey.rawValue }.count == 3
    }
    await transport.simulateReceive(makeContactPacket(
      publicKey: repeaterKey, outPathLength: PacketBuilder.floodPathSentinel, outPath: Data(), name: "Ridge"
    ))
    try await waitUntil("restore updateContact sent") {
      await transport.sentData.filter { $0.first == CommandCode.updateContact.rawValue }.count == 1
    }
    await transport.simulateReceive(Data([ResponseCode.ok.rawValue]))

    try await waitUntil("auth finishes") { !viewModel.isAuthenticating }
    #expect(!viewModel.isWaitingForRadio)

    #expect(successCount == 0)
    #expect(viewModel.errorMessage == L10n.RemoteNodes.RemoteNodes.Auth.noReply)
    #expect(!viewModel.useFloodRouting)
    #expect(!viewModel.didManualReset)
    #expect(viewModel.currentContact.outPath == directPath)
    #expect(viewModel.currentContact.outPathLength == 2)
    #expect(!viewModel.currentContact.isFloodRouted)

    let footer = signInPathFooter(
      hasStoredRoute: !viewModel.currentContact.isFloodRouted,
      floodRouting: viewModel.useFloodRouting,
      routeCleared: viewModel.didManualReset,
      fallbackEnabled: true,
      isAuthenticating: viewModel.isAuthenticating
    )
    #expect(!footer.showsFloodRoutingOn)
    #expect(footer.canChangeFloodRouting)
    #expect(footer.showsSavedHops)

    clock.advance(by: RemoteOperationTimeoutPolicy.failedRepairWindow + .seconds(1))
    viewModel.authenticate { _ in successCount += 1 }

    try await waitUntil("second direct sendLogin sent") {
      await transport.sentData.filter { $0.first == CommandCode.sendLogin.rawValue }.count == 3
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x11, 0x12, 0x13, 0x14]), suggestedTimeoutMs: 15000))
    try await waitUntil("second direct login timer parked") {
      viewModel.authSecondsRemaining != nil && clock.sleeperCount > 0
    }
    clock.advance(by: .seconds(25))

    try await waitUntil("second fallback getContactByKey sent") {
      await transport.sentData.filter { $0.first == CommandCode.getContactByKey.rawValue }.count == 4
    }
    await transport.simulateReceive(makeContactPacket(
      publicKey: repeaterKey, outPathLength: 2, outPath: directPath, name: "Ridge"
    ))
    try await waitUntil("second fallback resetPath sent") {
      await transport.sentData.filter { $0.first == CommandCode.resetPath.rawValue }.count == 2
    }
    await transport.simulateReceive(Data([ResponseCode.ok.rawValue]))

    try await waitUntil("second flood sendLogin sent") {
      await transport.sentData.filter { $0.first == CommandCode.sendLogin.rawValue }.count == 4
    }
    await transport.simulateReceive(makeMessageSentPacket(expectedAck: Data([0x15, 0x16, 0x17, 0x18]), suggestedTimeoutMs: 1000))
    await transport.simulateReceive(makeLoginSuccessPacket(publicKeyPrefix: Data(repeaterKey.prefix(6)), isAdmin: true))

    try await waitUntil("second auth succeeds") { successCount == 1 }
    #expect(!viewModel.isAuthenticating)
  }
}

private func makeSelfInfoPacket() -> Data {
  var payload = Data([1, UInt8(bitPattern: 22), UInt8(bitPattern: 22)])
  payload.append(Data(repeating: 0x01, count: 32))
  payload.append(contentsOf: [0, 0, 0, 0, 0, 0, 0, 0])
  payload.append(contentsOf: [0, 0, 0, 0])
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
  payload.append(contentsOf: [7, 5])
  payload.append(contentsOf: "Test".utf8)
  return Data([ResponseCode.selfInfo.rawValue]) + payload
}

private func makeContactPacket(publicKey: Data, outPathLength: UInt8, outPath: Data, name: String) -> Data {
  var packet = Data([ResponseCode.contact.rawValue])
  packet.append(publicKey)
  packet.append(ContactType.repeater.rawValue)
  packet.append(0)
  packet.append(outPathLength)
  let paddedPath = outPath.prefix(PathEncoding.maxPathBytes)
  packet.append(paddedPath)
  if paddedPath.count < PathEncoding.maxPathBytes {
    packet.append(Data(repeating: 0, count: PathEncoding.maxPathBytes - paddedPath.count))
  }
  let nameBytes = Array(name.utf8.prefix(31))
  packet.append(contentsOf: nameBytes)
  packet.append(0)
  if nameBytes.count < 31 {
    packet.append(Data(repeating: 0, count: 31 - nameBytes.count))
  }
  packet.append(contentsOf: [0, 0, 0, 0])
  packet.append(contentsOf: [0, 0, 0, 0])
  packet.append(contentsOf: [0, 0, 0, 0])
  packet.append(contentsOf: [0, 0, 0, 0])
  return packet
}

private func makeMessageSentPacket(expectedAck: Data, suggestedTimeoutMs: UInt32) -> Data {
  var packet = Data([ResponseCode.messageSent.rawValue, 0])
  packet.append(expectedAck)
  packet.append(contentsOf: withUnsafeBytes(of: suggestedTimeoutMs.littleEndian) { Array($0) })
  return packet
}

private func makeLoginSuccessPacket(publicKeyPrefix: Data, isAdmin: Bool) -> Data {
  var packet = Data([ResponseCode.loginSuccess.rawValue, isAdmin ? 0x01 : 0x00])
  packet.append(publicKeyPrefix)
  packet.append(contentsOf: [0, 0, 0, 0])
  packet.append(0x00)
  packet.append(0x02)
  return packet
}
