import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RemoteNodeService teardown")
struct RemoteNodeTeardownTests {
  @Test
  func `stopAllKeepAlives resumes a parked login continuation`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

    let session = MockMeshCoreSession()
    await session.setSendLoginResults([.success(MessageSentInfo(
      route: 0,
      expectedAck: Data([1, 2, 3, 4]),
      suggestedTimeoutMs: 30000
    ))])

    let remoteSession = RemoteNodeSessionDTO.testSession(radioID: radioID)
    try await dataStore.saveRemoteNodeSessionDTO(remoteSession)

    let service = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: KeychainService()
    )

    let loginTask = Task {
      try await service.login(sessionID: remoteSession.id, password: "test-password")
    }

    // Wait until the continuation is registered before tearing down.
    try await waitUntil("login continuation was never parked") {
      let sent = await session.sendLoginInvocations.count == 1
      let pending = await service.pendingLoginCount
      return sent && pending == 1
    }

    await service.stopAllKeepAlives()

    await #expect {
      _ = try await loginTask.value
    } throws: { error in
      guard let error = error as? RemoteNodeError, case .cancelled = error else { return false }
      return true
    }
    #expect(await service.pendingLoginCount == 0)
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
