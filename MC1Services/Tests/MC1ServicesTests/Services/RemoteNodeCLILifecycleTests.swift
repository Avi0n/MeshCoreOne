import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RemoteNodeService CLI lifecycle", .timeLimit(.minutes(1)))
struct RemoteNodeCLILifecycleTests {
  private static let publicKey = Data(repeating: 0xCC, count: 32)
  private static let otherPublicKey = Data(repeating: 0xDD, count: 32)
  private static let requestTimeout: Duration = .seconds(5)
  private static let sentInfo = MessageSentInfo(route: 0, expectedAck: Data(), suggestedTimeoutMs: 100)

  @Test
  func `cancelling a middle waiter preserves FIFO for the remaining commands`() async throws {
    try await withHarness { harness in
      let first = harness.send("get name")
      try await harness.waitForSubmissions(1)
      let second = harness.send("board")
      try await harness.waitForWaiters(1)
      let cancelled = harness.send("shutdown")
      try await harness.waitForWaiters(2)
      let last = harness.send("ver")
      try await harness.waitForWaiters(3)

      cancelled.cancel()
      await #expect(throws: CancellationError.self) { try await cancelled.value }
      try await harness.waitForWaiters(2)
      #expect(await harness.commands == ["get name"])

      try await harness.reply("Contact", index: 0)
      #expect(try await first.value == "Contact")
      try await harness.waitForSubmissions(2)
      #expect(await harness.commands == ["get name", "board"])
      try await harness.reply("Heltec", index: 1)
      #expect(try await second.value == "Heltec")
      try await harness.waitForSubmissions(3)
      #expect(await harness.commands == ["get name", "board", "ver"])
      try await harness.reply("1.18.0", index: 2)
      #expect(try await last.value == "1.18.0")
    }
  }

  @Test
  func `another destination completes while the first destination is waiting`() async throws {
    try await withHarness { harness in
      let first = harness.send("get name")
      try await harness.waitForSubmissions(1)
      let other = harness.send("board", publicKey: Self.otherPublicKey)
      try await harness.waitForSubmissions(2)

      try await harness.reply("Heltec", index: 1)
      #expect(try await other.value == "Heltec")
      #expect(await harness.session.sendCommandInvocations.map(\.destination) == [
        Self.publicKey, Self.otherPublicKey
      ])
      try await harness.reply("Contact", index: 0)
      #expect(try await first.value == "Contact")
    }
  }

  @Test(arguments: [false, true])
  func `reply during submission wins and late submission completion cannot affect the next request`(
    submissionFails: Bool
  ) async throws {
    try await withHarness { harness in
      await harness.session.setSendCommandResult(
        submissionFails ? .failure(MeshCoreError.timeout) : .success(Self.sentInfo)
      )
      await harness.session.setSendCommandHold { await harness.sendGate.wait() }
      let first = harness.send("get name")
      try await waitUntil("first submission should be suspended") { await harness.sendGate.isWaiting }
      let prefix = Data(Self.publicKey.prefix(6))
      let firstWorker = try #require(await harness.service.cliSlots[prefix]?.pendingRequest?.worker)
      let second = harness.send("board")
      try await harness.waitForWaiters(1)

      await harness.session.setSendCommandHold(nil)
      await harness.session.setSendCommandResult(.success(Self.sentInfo))
      try await harness.reply("Contact", index: 0)
      #expect(try await first.value == "Contact")
      try await harness.waitForSubmissions(2)

      await harness.sendGate.release()
      await firstWorker.value
      try await harness.reply("stale first reply", index: 0)
      try await harness.reply("Heltec", index: 1)
      #expect(try await second.value == "Heltec")
      #expect(await harness.commands == ["get name", "board"])
    }
  }

  @Test
  func `retirement cancels active and queued commands and rejects further commands`() async throws {
    try await withHarness { harness in
      let first = harness.send("get name")
      try await harness.waitForSubmissions(1)
      let second = harness.send("board")
      try await harness.waitForWaiters(1)
      let third = harness.send("shutdown")
      try await harness.waitForWaiters(2)

      await harness.service.retire()
      await #expect(throws: CancellationError.self) { try await first.value }
      await #expect(throws: CancellationError.self) { try await second.value }
      await #expect(throws: CancellationError.self) { try await third.value }
      await #expect {
        try await harness.service.sendRadioCLICommand(
          to: Self.publicKey, command: "ver", timeout: Self.requestTimeout
        )
      } throws: { error in
        guard case RemoteNodeError.notConnected = error else { return false }
        return true
      }
      #expect(await harness.commands == ["get name"])
    }
  }

  @Test
  func `Direct-to-Flood retry keeps its slot ahead of queued commands`() async throws {
    try await withHarness { harness in
      try await harness.dataStore.saveContact(ContactDTO.testContact(
        radioID: harness.radioID, publicKey: Self.publicKey,
        typeRawValue: ContactType.repeater.rawValue, outPathLength: 1, outPath: Data([0x0A])
      ))
      let remoteSession = RemoteNodeSessionDTO.testSession(
        radioID: harness.radioID, publicKey: Self.publicKey, role: .repeater, permissionLevel: .admin
      )
      try await harness.dataStore.saveRemoteNodeSessionDTO(remoteSession)
      await harness.session.setSendCommandResult(.failure(MeshCoreError.timeout))
      await harness.session.setSendCommandHold { await harness.sendGate.wait() }
      let first = Task {
        try await harness.service.sendRawCLICommand(
          sessionID: remoteSession.id, command: "get name", timeout: Self.requestTimeout
        )
      }
      try await waitUntil("Direct submission should be suspended") { await harness.sendGate.isWaiting }
      let second = harness.send("board")
      try await harness.waitForWaiters(1)
      await harness.session.setSendCommandHold(nil)
      await harness.session.setSendCommandResult(.success(Self.sentInfo))
      await harness.sendGate.release()

      try await harness.waitForSubmissions(2)
      #expect(await harness.commands == ["get name", "get name"])
      #expect(await harness.session.resetPathPublicKeys == [Self.publicKey])
      try await harness.reply("stale Direct reply", index: 0)
      try await harness.reply("Contact", index: 1)
      #expect(try await first.value == "Contact")
      try await harness.waitForSubmissions(3)
      #expect(await harness.commands == ["get name", "get name", "board"])
      try await harness.reply("Heltec", index: 2)
      #expect(try await second.value == "Heltec")
    }
  }

  private func withHarness(_ operation: (Harness) async throws -> Void) async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MockMeshCoreSession()
    let service = RemoteNodeService(session: session, dataStore: dataStore, keychainService: KeychainService())
    let harness = Harness(service: service, session: session, dataStore: dataStore, radioID: radioID)
    await service.startEventMonitoring()
    do {
      try await waitUntil("CLI event monitor should subscribe") { await session.eventSubscriptionCount == 1 }
      try await operation(harness)
    } catch {
      await harness.stop()
      throw error
    }
    await harness.stop()
  }

  private struct Harness {
    let service: RemoteNodeService
    let session: MockMeshCoreSession
    let dataStore: PersistenceStore
    let radioID: UUID
    let sendGate = Gate()

    var commands: [String] {
      get async {
        await session.sendCommandInvocations.compactMap { CLIResponse.splitEchoedPrefix($0.command)?.body }
      }
    }

    func send(_ command: String, publicKey: Data = RemoteNodeCLILifecycleTests.publicKey) -> Task<String, Error> {
      Task {
        try await service.sendRadioCLICommand(
          to: publicKey, command: command, timeout: RemoteNodeCLILifecycleTests.requestTimeout
        )
      }
    }

    func waitForSubmissions(_ count: Int) async throws {
      try await waitUntil("expected \(count) CLI submissions") { await session.sendCommandInvocations.count == count }
    }

    func waitForWaiters(_ count: Int) async throws {
      let prefix = Data(RemoteNodeCLILifecycleTests.publicKey.prefix(6))
      try await waitUntil("expected \(count) queued CLI commands") {
        await service.cliSlots[prefix]?.waiterCount == count
      }
    }

    func reply(_ text: String, index: Int) async throws {
      let invocation = await session.sendCommandInvocations[index]
      let command = try #require(CLIResponse.splitEchoedPrefix(invocation.command))
      await session.yieldEvent(.contactMessageReceived(ContactMessage(
        senderPublicKeyPrefix: Data(invocation.destination.prefix(6)),
        pathLength: 0,
        textType: MeshCore.TextType.cliData.rawValue,
        senderTimestamp: Date(),
        signature: nil,
        text: command.prefix + text,
        snr: nil
      )))
    }

    func stop() async {
      await session.setSendCommandHold(nil)
      await sendGate.release()
      await service.retire()
      service.finishEvents()
    }
  }

  private actor Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isReleased = false
    private(set) var isWaiting = false

    func wait() async {
      guard !isReleased else { return }
      await withCheckedContinuation { continuation in
        self.continuation = continuation
        isWaiting = true
      }
    }

    func release() {
      isReleased = true
      continuation?.resume()
      continuation = nil
    }
  }
}
