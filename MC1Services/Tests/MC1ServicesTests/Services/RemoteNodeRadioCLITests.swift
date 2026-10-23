import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// Radio CLI to a chat contact. The harness saves no remote-node session.
@Suite("RemoteNodeService radio CLI")
struct RemoteNodeRadioCLITests {
  private static let publicKey = Data(repeating: 0xCC, count: 32)
  private static let cliTextType: UInt8 = 0x01

  private struct Harness {
    let service: RemoteNodeService
    let session: MockMeshCoreSession
  }

  private struct SendFailed: Error {}

  private func makeHarness() async throws -> Harness {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
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
    return Harness(service: service, session: session)
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

  private func sentWirePrefix(of session: MockMeshCoreSession) async throws -> String {
    let sent = await session.sendCommandInvocations.last?.command ?? ""
    let split = try #require(CLIResponse.splitEchoedPrefix(sent))
    return split.prefix
  }

  @Test
  func `sends txt_type 3 with the wire prefix and needs no remote session`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "get name",
        timeout: .milliseconds(300)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let sent = try #require(await harness.session.sendCommandInvocations.last)
    #expect(sent.textType == .cliCommand)
    let split = try #require(CLIResponse.splitEchoedPrefix(sent.command))
    #expect(split.body == "get name")

    _ = try? await commandTask.value
  }

  @Test(arguments: [
    ("  get name", "get name"),
    ("  set name Peer  ", "set name Peer  "),
  ])
  func `removes only leading ASCII spaces before adding the wire prefix`(
    command: String,
    expectedBody: String
  ) async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: command,
        timeout: .seconds(2)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let sent = try #require(await harness.session.sendCommandInvocations.last)
    let split = try #require(CLIResponse.splitEchoedPrefix(sent.command))
    #expect(split.body == expectedBody)

    await yieldReply(split.prefix + "OK", to: harness.session)
    _ = try await commandTask.value
  }

  @Test
  func `resolves on the echoed prefix and strips it`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "get name",
        timeout: .seconds(2)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let prefix = try await sentWirePrefix(of: harness.session)
    await yieldReply(prefix + "Peer", to: harness.session)
    #expect(try await commandTask.value == "Peer")
  }

  @Test
  func `ignores a reply with another prefix`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "get name",
        timeout: .seconds(2)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    let prefix = try await sentWirePrefix(of: harness.session)
    let foreign = prefix == "A7|" ? "B8|" : "A7|"
    await yieldReply(foreign + "stale", to: harness.session)
    await yieldReply(prefix + "Peer", to: harness.session)
    #expect(try await commandTask.value == "Peer")
  }

  @Test
  func `times out without calling resetPath`() async throws {
    let harness = try await makeHarness()

    let commandTask = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "get name",
        timeout: .milliseconds(300)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    do {
      let response = try await commandTask.value
      Issue.record("expected timeout, got response '\(response)'")
    } catch RemoteNodeError.timeout {}
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `a send error throws and releases the CLI slot`() async throws {
    let harness = try await makeHarness()
    await harness.session.setSendCommandResult(.failure(SendFailed()))

    let failed = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "ver",
        timeout: .seconds(2)
      )
    }
    try await waitUntil("failed command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }
    do {
      _ = try await failed.value
      Issue.record("expected the send to throw")
    } catch is RemoteNodeError {}

    await harness.session.setSendCommandResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data(), suggestedTimeoutMs: 100)
    ))
    let commandTask = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "board",
        timeout: .seconds(2)
      )
    }
    try await waitUntil("later command was never sent") {
      await harness.session.sendCommandInvocations.count == 2
    }
    let prefix = try await sentWirePrefix(of: harness.session)
    await yieldReply(prefix + "Heltec", to: harness.session)
    #expect(try await commandTask.value == "Heltec")
  }

  @Test
  func `cancelling a sent radio command releases the CLI slot`() async throws {
    let harness = try await makeHarness()
    let first = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "get name",
        timeout: .seconds(30)
      )
    }
    try await waitUntil("command was never sent") {
      await harness.session.sendCommandInvocations.count == 1
    }

    first.cancel()
    do {
      _ = try await first.value
      Issue.record("expected cancellation")
    } catch is CancellationError {}

    let second = Task {
      try await harness.service.sendRadioCLICommand(
        to: Self.publicKey,
        command: "board",
        timeout: .seconds(2)
      )
    }
    try await waitUntil("later command was never sent") {
      await harness.session.sendCommandInvocations.count == 2
    }
    let prefix = try await sentWirePrefix(of: harness.session)
    await yieldReply(prefix + "Heltec", to: harness.session)
    #expect(try await second.value == "Heltec")
  }
}
