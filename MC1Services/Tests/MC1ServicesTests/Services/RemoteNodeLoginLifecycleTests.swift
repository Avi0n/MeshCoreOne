import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RemoteNodeService login lifecycle", .timeLimit(.minutes(1)))
@MainActor
struct RemoteNodeLoginLifecycleTests {
  @Test
  func `reply before submission finishes completes login despite a later send failure`() async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await harness.session.setSendLoginResults([.failure(MeshCoreError.timeout)])
      await harness.session.setSendLoginHold { await harness.sendGate.wait() }
      let login = Task { try await harness.login() }
      try await waitUntil("login submission should be suspended") { await harness.sendGate.isWaiting }

      await harness.deliverSuccess(isAdmin: true)
      let result = try await login.value
      #expect(result.success)
      #expect(result.isAdmin)
      #expect(await harness.session.completedSendLoginCount == 0)
      #expect(try await harness.store.fetchRemoteNodeSession(id: harness.membership.id)?.permissionLevel == .admin)

      await harness.sendGate.release()
      try await waitUntil("the old submission should return its error") {
        await harness.session.completedSendLoginCount == 1
      }
      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
    }
  }

  @Test
  func `duplicate reply while result persistence is suspended cannot claim login twice`() async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await harness.holdResultPersistence()
      let login = Task { try await harness.login() }
      try await harness.waitForSubmissions(1)
      let firstReply = Task { await harness.deliverSuccess(isAdmin: true) }
      try await waitUntil("the first reply should be persisting") { await harness.storeGate.isWaiting }

      await harness.deliverSuccess(isAdmin: false)
      #expect(await harness.store.updatedSessionConnections.count == 1)

      await harness.storeGate.release()
      await firstReply.value
      #expect(try await login.value.isAdmin)
      #expect(try await harness.store.fetchRemoteNodeSession(id: harness.membership.id)?.permissionLevel == .admin)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
    }
  }

  @Test(arguments: [false, true])
  func `old result persistence cannot finish or disconnect a replacement login`(cancelFirst: Bool) async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await harness.holdResultPersistence()
      let original = Task { try await harness.login() }
      try await harness.waitForSubmissions(1)
      let originalReply = Task { await harness.deliverSuccess(isAdmin: true) }
      try await waitUntil("the old reply should be persisting") { await harness.storeGate.isWaiting }

      if cancelFirst { original.cancel() }
      let replacement = Task { try await harness.login() }
      try await harness.waitForSubmissions(2)
      await harness.storeGate.release()
      await originalReply.value
      await expectCancelled(original)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)

      await harness.deliverSuccess(isAdmin: false)
      let result = try await replacement.value
      #expect(result.success)
      #expect(!result.isAdmin)
      #expect(await harness.store.updatedSessionConnections.count == 2)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
      #expect(try await harness.store.fetchRemoteNodeSession(id: harness.membership.id)?.permissionLevel == .guest)
    }
  }

  @Test
  func `cancelled result persistence compensates its late connection write without a replacement`() async throws {
    try await withHarness(expectedConnectedEvents: 0) { harness in
      await harness.holdResultPersistence()
      let login = Task { try await harness.login() }
      try await harness.waitForSubmissions(1)
      let reply = Task { await harness.deliverSuccess(isAdmin: true) }
      try await waitUntil("the reply should be persisting before cancellation") { await harness.storeGate.isWaiting }

      await harness.service.cancelPendingLogin(for: harness.prefix)
      await harness.storeGate.release()
      await reply.value
      await expectCancelled(login)

      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs == [harness.membership.id])
    }
  }

  @Test
  func `submission failure ignores late reply and permits another login`() async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await harness.session.setSendLoginResults([
        .failure(MeshCoreError.deviceError(code: ProtocolError.tableFull.rawValue)),
        .success(Harness.sentInfo)
      ])
      await #expect {
        _ = try await harness.login()
      } throws: { error in
        guard let error = error as? RemoteNodeError,
              case let .sessionError(.deviceError(code)) = error else { return false }
        return code == ProtocolError.tableFull.rawValue
      }

      await harness.deliverSuccess(isAdmin: true)
      #expect(await harness.store.updatedSessionConnections.isEmpty)

      let retry = Task { try await harness.login() }
      try await harness.waitForSubmissions(2)
      await harness.deliverSuccess(isAdmin: false)
      #expect(try await retry.value.success)
      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
    }
  }

  @Test
  func `response timeout ignores late reply and permits another login`() async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await #expect {
        _ = try await harness.login()
      } throws: { error in
        guard let error = error as? RemoteNodeError, case .timeout = error else { return false }
        return true
      }
      let submissions = await harness.session.sendLoginInvocations.count
      #expect(submissions > 1)
      await harness.deliverSuccess(isAdmin: true)
      #expect(await harness.store.updatedSessionConnections.isEmpty)

      await harness.session.setSendLoginResults([.success(Harness.sentInfo)])
      let retry = Task { try await harness.login() }
      try await harness.waitForSubmissions(submissions + 1)
      await harness.deliverSuccess(isAdmin: false)
      #expect(try await retry.value.success)
      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
    }
  }

  private func expectCancelled(_ task: Task<LoginResult, Error>) async {
    await #expect {
      _ = try await task.value
    } throws: { error in
      guard let error = error as? RemoteNodeError, case .cancelled = error else { return false }
      return true
    }
  }

  private func withHarness(
    expectedConnectedEvents: Int,
    _ body: (Harness) async throws -> Void
  ) async throws {
    let session = MockMeshCoreSession()
    await session.setSendLoginResults(Array(repeating: .success(Harness.sentInfo), count: 8))
    let store = MockPersistenceStore()
    let membership = RemoteNodeSessionDTO.testSession(radioID: UUID())
    try await store.saveRemoteNodeSessionDTO(membership)
    let service = RemoteNodeService(session: session, dataStore: store, keychainService: KeychainService())
    let stream = service.events()
    let events = Task {
      var received: [RemoteNodeEvent] = []
      for await event in stream {
        received.append(event)
      }
      return received
    }
    let harness = Harness(service: service, session: session, store: store, membership: membership)
    do {
      try await body(harness)
    } catch {
      await harness.stop()
      _ = await events.value
      throw error
    }
    await harness.stop()
    let received = await events.value
    #expect(received.count == expectedConnectedEvents)
    for case let .sessionStateChanged(sessionID, isConnected) in received {
      #expect(sessionID == membership.id)
      #expect(isConnected)
    }
  }

  private struct Harness: Sendable {
    static let sentInfo = MessageSentInfo(route: 0, expectedAck: Data([1, 2, 3, 4]), suggestedTimeoutMs: 100)
    let service: RemoteNodeService
    let session: MockMeshCoreSession
    let store: MockPersistenceStore
    let membership: RemoteNodeSessionDTO
    let sendGate = Gate()
    let storeGate = Gate()

    var prefix: Data {
      Data(membership.publicKey.prefix(6))
    }

    func login() async throws -> LoginResult {
      try await service.login(sessionID: membership.id, password: "test-password")
    }

    func deliverSuccess(isAdmin: Bool) async {
      await service.handleLoginResult(
        LoginResult(success: true, isAdmin: isAdmin, aclPermissions: 0, publicKeyPrefix: prefix),
        fromPublicKeyPrefix: prefix
      )
    }

    func holdResultPersistence() async {
      await store.setUpdateRemoteNodeSessionConnectionHold { await storeGate.wait() }
    }

    @MainActor
    func waitForSubmissions(_ count: Int) async throws {
      try await waitUntil("expected login submission was not sent") {
        await session.sendLoginInvocations.count == count
      }
    }

    func stop() async {
      await session.setSendLoginHold(nil)
      await store.setUpdateRemoteNodeSessionConnectionHold(nil)
      await sendGate.release()
      await storeGate.release()
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
