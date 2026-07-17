import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// Covers CLI response correlation: one command in flight per node, replies
/// that don't match the pending command's shape are dropped, and queued
/// commands wait for the slot instead of racing for replies.
@Suite("RemoteNodeService CLI correlation")
struct RemoteNodeCLICorrelationTests {
  private static let publicKey = Data(repeating: 0xCC, count: 32)
  private static let cliTextType: UInt8 = 0x01

  private struct Harness {
    let service: RemoteNodeService
    let session: MockMeshCoreSession
    let sessionID: UUID
  }

  private func makeHarness() async throws -> Harness {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let remoteSession = RemoteNodeSessionDTO.testSession(
      radioID: radioID,
      publicKey: Self.publicKey,
      permissionLevel: .admin
    )
    try await dataStore.saveRemoteNodeSessionDTO(remoteSession)

    let session = MockMeshCoreSession()
    let service = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: KeychainService()
    )
    await service.startEventMonitoring()
    try await waitUntil("event monitor never subscribed") {
      await session.eventSubscriptionCount == 1
    }
    return Harness(service: service, session: session, sessionID: remoteSession.id)
  }

  private func yieldReply(_ text: String, to session: MockMeshCoreSession) async {
    await session.yieldEvent(.contactMessageReceived(ContactMessage(
      senderPublicKeyPrefix: Data(Self.publicKey.prefix(6)),
      pathLength: 0,
      textType: Self.cliTextType,
      senderTimestamp: Date(),
      signature: nil,
      text: text,
      snr: nil
    )))
  }

  @Test
  func `matching reply resolves the pending command`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendCLICommand(sessionID: harness.sessionID, command: "get tx")
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    await yieldReply("> 22", to: harness.session)
    #expect(try await commandTask.value == "> 22")
  }

  @Test
  func `radio CSV reply never resolves a pending get tx`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendCLICommand(sessionID: harness.sessionID, command: "get tx")
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    // A stale "get radio" reply must be dropped, not adopted as 910 dBm.
    await yieldReply("> 910.525,62.500,7,7", to: harness.session)
    await yieldReply("> 22", to: harness.session)
    #expect(try await commandTask.value == "> 22")
  }

  @Test
  func `mismatched reply is dropped and the command times out`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendCLICommand(
        sessionID: harness.sessionID,
        command: "get tx",
        timeout: .milliseconds(300)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    await yieldReply("> 910.525,62.500,7,7", to: harness.session)

    do {
      let response = try await commandTask.value
      Issue.record("expected timeout, got response '\(response)'")
    } catch RemoteNodeError.timeout {
      // Expected: the mismatched reply must not satisfy the request.
    }
  }

  @Test
  func `second command waits for the slot until the first resolves`() async throws {
    let harness = try await makeHarness()

    let firstTask = Task {
      try await harness.service.sendCLICommand(sessionID: harness.sessionID, command: "get tx")
    }
    try await waitUntil("first command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let secondTask = Task {
      try await harness.service.sendCLICommand(sessionID: harness.sessionID, command: "get radio")
    }

    // The second command must not reach the radio while the first is pending.
    try await Task.sleep(for: .milliseconds(100))
    #expect(await harness.session.sendCommandInvocations.count == 1)

    await yieldReply("> 22", to: harness.session)
    #expect(try await firstTask.value == "> 22")

    try await waitUntil("second command was never sent") {
      await harness.session.sendCommandInvocations.count == 2
    }
    await yieldReply("> 915.000,250.0,10,5", to: harness.session)
    #expect(try await secondTask.value == "> 915.000,250.0,10,5")
  }

  @Test
  func `raw command accepts a free-form reply`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendRawCLICommand(sessionID: harness.sessionID, command: "region")
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    await yieldReply("US/CA^\n  local F", to: harness.session)
    #expect(try await commandTask.value == "US/CA^\n  local F")
  }

  // MARK: - Wire prefix echo

  /// The wire prefix of the most recently sent command.
  private func sentWirePrefix(of session: MockMeshCoreSession) async throws -> String {
    let sent = await session.sendCommandInvocations.last?.command ?? ""
    let split = try #require(CLIResponse.splitEchoedPrefix(sent))
    return split.prefix
  }

  @Test
  func `command is sent with a hex wire prefix ahead of the command text`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendCLICommand(
        sessionID: harness.sessionID,
        command: "get tx",
        timeout: .milliseconds(300)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let sent = await harness.session.sendCommandInvocations.last?.command ?? ""
    let split = try #require(CLIResponse.splitEchoedPrefix(sent))
    #expect(split.body == "get tx")

    _ = try? await commandTask.value
  }

  @Test
  func `reply echoing the wire prefix resolves and is delivered stripped`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendCLICommand(sessionID: harness.sessionID, command: "get tx")
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let prefix = try await sentWirePrefix(of: harness.session)
    await yieldReply(prefix + "> 22", to: harness.session)
    #expect(try await commandTask.value == "> 22")
  }

  @Test
  func `prefixed echo is authoritative even when the reply shape looks wrong`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendCLICommand(sessionID: harness.sessionID, command: "get tx")
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    // A CSV would fail get tx shape validation, but the echoed prefix proves
    // the reply answers this command, so it must be delivered.
    let prefix = try await sentWirePrefix(of: harness.session)
    await yieldReply(prefix + "> 910.525,62.500,7,7", to: harness.session)
    #expect(try await commandTask.value == "> 910.525,62.500,7,7")
  }

  @Test
  func `reply echoing a foreign prefix is dropped even for raw commands`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendRawCLICommand(sessionID: harness.sessionID, command: "region")
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let prefix = try await sentWirePrefix(of: harness.session)
    let foreign = prefix == "A7|" ? "B8|" : "A7|"
    await yieldReply(foreign + "stale reply to an earlier command", to: harness.session)
    await yieldReply(prefix + "US/CA^", to: harness.session)
    #expect(try await commandTask.value == "US/CA^")
  }
}
