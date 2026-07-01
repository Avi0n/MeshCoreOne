import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// A transport whose `send` succeeds but that never yields session bytes, so any
/// command awaiting a device response stays parked. Models a radio that accepted
/// the write but went silent, which is the state `stopAllKeepAlives` must unwind.
private actor SilentTransport: MeshTransport {
  private let dataStream: AsyncStream<Data>
  private let dataContinuation: AsyncStream<Data>.Continuation

  init() {
    var continuation: AsyncStream<Data>.Continuation!
    dataStream = AsyncStream { continuation = $0 }
    dataContinuation = continuation
  }

  var receivedData: AsyncStream<Data> {
    dataStream
  }

  var isConnected: Bool {
    true
  }

  func connect() async throws {}
  func disconnect() async {}
  func send(_ data: Data) async throws {}
}

@Suite("RemoteNodeService teardown")
struct RemoteNodeTeardownTests {
  @Test
  func `stopAllKeepAlives resumes a parked login continuation`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)

    // Long timeout keeps `sendLogin` blocked on the silent transport, so the
    // login continuation stays parked in `pendingLogins` for the duration.
    let session = MeshCoreSession(
      transport: SilentTransport(),
      configuration: SessionConfiguration(defaultTimeout: 30.0, clientIdentifier: "MCTst")
    )

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
      await service.pendingLoginCount == 1
    }

    await service.stopAllKeepAlives()

    await #expect(throws: RemoteNodeError.self) {
      _ = try await loginTask.value
    }
    #expect(await service.pendingLoginCount == 0)
  }

  @Test
  func `keep-alive loop does not retain the service after the last strong reference is dropped`() async throws {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let session = MeshCoreSession(
      transport: SilentTransport(),
      configuration: SessionConfiguration(defaultTimeout: 30.0, clientIdentifier: "MCTst")
    )

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
