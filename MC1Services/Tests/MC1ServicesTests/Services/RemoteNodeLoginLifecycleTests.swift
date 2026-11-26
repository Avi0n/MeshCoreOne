import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RemoteNodeService login lifecycle", .timeLimit(.minutes(1)))
@MainActor
struct RemoteNodeLoginLifecycleTests {
  @Test
  func `login persists session connection and emits sessionStateChanged`() async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await harness.session.setStubbedLoginResults([
        .success(LoginExchangeResult(
          info: LoginInfo(permissions: 0, isAdmin: true, publicKeyPrefix: harness.prefix),
          mayAnswerEarlierLogin: false
        )),
      ])

      let result = try await harness.login()
      #expect(result.success)
      #expect(result.isAdmin)
      #expect(!result.mayAnswerEarlierLogin)
      #expect(try await harness.store.fetchRemoteNodeSession(id: harness.membership.id)?.isConnected == true)
      #expect(try await harness.store.fetchRemoteNodeSession(id: harness.membership.id)?.permissionLevel == .admin)
      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
    }
  }

  @Test
  func `cancelled result persistence compensates its late connection write`() async throws {
    try await withHarness(expectedConnectedEvents: 0) { harness in
      await harness.holdResultPersistence()
      await harness.session.setStubbedLoginResults([
        .success(LoginExchangeResult(
          info: LoginInfo(permissions: 0, isAdmin: true, publicKeyPrefix: harness.prefix),
          mayAnswerEarlierLogin: false
        )),
      ])

      let login = Task { try await harness.login() }
      try await waitUntil("result should be persisting before cancellation") { await harness.storeGate.isWaiting }

      login.cancel()
      await harness.storeGate.release()
      await #expect(throws: CancellationError.self) { _ = try await login.value }

      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs == [harness.membership.id])
    }
  }

  @Test
  func `device error on login permits another login`() async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await harness.session.setStubbedLoginResults([
        .failure(MeshCoreError.deviceError(code: ProtocolError.tableFull.rawValue)),
        .success(LoginExchangeResult(
          info: LoginInfo(permissions: 0, isAdmin: false, publicKeyPrefix: harness.prefix),
          mayAnswerEarlierLogin: false
        )),
      ])
      await #expect {
        _ = try await harness.login()
      } throws: { error in
        guard let error = error as? RemoteNodeError,
              case let .sessionError(.deviceError(code)) = error else { return false }
        return code == ProtocolError.tableFull.rawValue
      }
      #expect(await harness.store.updatedSessionConnections.isEmpty)

      let retry = try await harness.login()
      #expect(retry.success)
      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
    }
  }

  @Test
  func `response timeout maps to RemoteNodeError timeout and permits another login`() async throws {
    try await withHarness(expectedConnectedEvents: 1) { harness in
      await harness.session.setStubbedLoginResults([
        .failure(MeshCoreError.timeout),
        .success(LoginExchangeResult(
          info: LoginInfo(permissions: 0, isAdmin: false, publicKeyPrefix: harness.prefix),
          mayAnswerEarlierLogin: false
        )),
      ])
      await #expect {
        _ = try await harness.login()
      } throws: { error in
        guard let error = error as? RemoteNodeError, case .timeout = error else { return false }
        return true
      }
      #expect(await harness.store.updatedSessionConnections.isEmpty)

      let retry = try await harness.login()
      #expect(retry.success)
      #expect(await harness.store.updatedSessionConnections.count == 1)
      #expect(await harness.store.markedDisconnectedSessionIDs.isEmpty)
    }
  }

  private func withHarness(
    expectedConnectedEvents: Int,
    _ body: (Harness) async throws -> Void
  ) async throws {
    let session = MockMeshCoreSession()
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
    let service: RemoteNodeService
    let session: MockMeshCoreSession
    let store: MockPersistenceStore
    let membership: RemoteNodeSessionDTO
    let storeGate = Gate()

    var prefix: Data {
      Data(membership.publicKey.prefix(6))
    }

    func login() async throws -> LoginResult {
      try await service.login(sessionID: membership.id, password: "test-password", priority: .interactive)
    }

    func holdResultPersistence() async {
      await store.setUpdateRemoteNodeSessionConnectionHold { await storeGate.wait() }
    }

    func stop() async {
      await session.setSendLoginHold(nil)
      await store.setUpdateRemoteNodeSessionConnectionHold(nil)
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
