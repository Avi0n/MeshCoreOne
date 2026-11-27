import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RemoteNodeService teardown")
struct RemoteNodeTeardownTests {
  @Test
  func `retire stops in-flight login from persisting connected state`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let session = MockMeshCoreSession()
    let remoteSession = RemoteNodeSessionDTO.testSession(radioID: radioID)
    try await dataStore.saveRemoteNodeSessionDTO(remoteSession)
    let prefix = Data(remoteSession.publicKey.prefix(6))
    await session.setStubbedLoginResults([
      .success(LoginExchangeResult(
        info: LoginInfo(permissions: 0, isAdmin: true, publicKeyPrefix: prefix),
        mayAnswerEarlierLogin: false
      )),
    ])
    let gate = SimpleGate()
    await session.setSendLoginHold { await gate.wait() }

    let service = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: KeychainService()
    )

    let loginTask = Task {
      try await service.login(sessionID: remoteSession.id, password: "test-password", priority: .interactive)
    }
    try await waitUntil("login reached mock session") {
      await session.loginInvocations.count == 1
    }

    await service.retire()
    await gate.release()

    await #expect(throws: RemoteNodeError.self) {
      _ = try await loginTask.value
    }
    #expect(try await dataStore.fetchRemoteNodeSession(id: remoteSession.id)?.isConnected == false)
  }

  @Test
  func `keep-alive loop does not retain the service after the last strong reference is dropped`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MockMeshCoreSession()

    let remoteSession = RemoteNodeSessionDTO.testSession(radioID: radioID)
    try await dataStore.saveRemoteNodeSessionDTO(remoteSession)

    // A flood-routed contact makes each keep-alive tick a skip, so the
    // loop stays parked in its inter-tick sleep without touching the session.
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: remoteSession.publicKey,
      outPathLength: 0xFF
    )
    try await dataStore.saveContact(contact)

    var service: RemoteNodeService? = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: KeychainService()
    )
    weak var weakService = service

    await service?.startSessionKeepAlive(
      sessionID: remoteSession.id,
      publicKey: remoteSession.publicKey
    )
    service = nil

    try await waitUntil("keep-alive task must not keep the service alive") {
      weakService == nil
    }
  }
}

private actor SimpleGate {
  private var continuation: CheckedContinuation<Void, Never>?
  private var released = false

  func wait() async {
    guard !released else { return }
    await withCheckedContinuation { continuation = $0 }
  }

  func release() {
    released = true
    continuation?.resume()
    continuation = nil
  }
}
