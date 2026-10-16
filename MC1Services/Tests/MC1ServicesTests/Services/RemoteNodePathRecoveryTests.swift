import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// Binary admin (status / telemetry / owner) does not reset the contact path on
/// mesh timeout; those waits use `performBinaryExchange`. CLI still uses
/// `performWithDirectPathFloodRecovery`.
@Suite("RemoteNodeService binary admin path policy")
struct RemoteNodePathRecoveryTests {
  private static let publicKey = Data(repeating: 0xCD, count: 32)
  private static let directPath = Data([0x0A, 0x0B, 0x0C])

  private struct Harness {
    let service: RemoteNodeService
    let session: MockMeshCoreSession
    let dataStore: PersistenceStore
    let sessionID: UUID
    let radioID: UUID
  }

  private func makeHarness(outPathLength: UInt8, outPath: Data) async throws -> Harness {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: Self.publicKey,
      typeRawValue: ContactType.repeater.rawValue,
      outPathLength: outPathLength,
      outPath: outPath
    )
    try await dataStore.saveContact(contact)

    let remoteSession = RemoteNodeSessionDTO.testSession(
      radioID: radioID,
      publicKey: Self.publicKey,
      role: .repeater,
      permissionLevel: .admin
    )
    try await dataStore.saveRemoteNodeSessionDTO(remoteSession)

    let session = MockMeshCoreSession()
    let service = RemoteNodeService(
      session: session,
      dataStore: dataStore,
      keychainService: KeychainService()
    )
    return Harness(
      service: service,
      session: session,
      dataStore: dataStore,
      sessionID: remoteSession.id,
      radioID: radioID
    )
  }

  private func makeStatusResponse() -> StatusResponse {
    StatusResponse(
      publicKeyPrefix: Self.publicKey.prefix(6),
      battery: 4100,
      txQueueLength: 0,
      noiseFloor: -100,
      lastRSSI: -70,
      packetsReceived: 10,
      packetsSent: 5,
      airtime: 1,
      uptime: 100,
      sentFlood: 0,
      sentDirect: 0,
      receivedFlood: 0,
      receivedDirect: 0,
      fullEvents: 0,
      lastSNR: 9.0,
      directDuplicates: 0,
      floodDuplicates: 0,
      rxAirtime: 1,
      receiveErrors: 0
    )
  }

  @Test
  func `status timeout does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)
    await harness.session.setRequestStatusResult(.failure(MeshCoreError.timeout))

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestStatus(sessionID: harness.sessionID, timeout: .seconds(5))
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)

    let contact = try await harness.dataStore.fetchContact(
      radioID: harness.radioID,
      publicKey: Self.publicKey
    )
    #expect(contact?.isFloodRouted == false)
  }

  @Test
  func `repeated status timeouts still do not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)
    await harness.session.setRequestStatusResults([
      .failure(MeshCoreError.timeout),
      .failure(MeshCoreError.timeout),
      .failure(MeshCoreError.timeout),
    ])

    for _ in 0..<3 {
      await #expect(throws: RemoteNodeError.self) {
        _ = try await harness.service.requestStatus(sessionID: harness.sessionID, timeout: .seconds(5))
      }
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `status success does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 3, outPath: Self.directPath)
    await harness.session.setRequestStatusResult(.success(makeStatusResponse()))

    let response = try await harness.service.requestStatus(
      sessionID: harness.sessionID,
      timeout: .seconds(5)
    )
    #expect(response.battery == 4100)
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `status timeout maps to RemoteNodeError timeout`() async throws {
    let harness = try await makeHarness(outPathLength: 2, outPath: Self.directPath)
    await harness.session.setRequestStatusResult(.failure(MeshCoreError.timeout))

    let error = await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestStatus(
        sessionID: harness.sessionID,
        timeout: .seconds(5)
      )
    }
    guard case .timeout? = error else {
      Issue.record("Expected RemoteNodeError.timeout, got \(String(describing: error))")
      return
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `telemetry timeout does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 2, outPath: Self.directPath)
    await harness.session.setRequestTelemetryResults([.failure(MeshCoreError.timeout)])

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestTelemetry(sessionID: harness.sessionID, timeout: .seconds(5))
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }

  @Test
  func `owner info timeout does not reset the path`() async throws {
    let harness = try await makeHarness(outPathLength: 1, outPath: Data([0xFF]))
    await harness.session.setRequestOwnerInfoResults([.failure(MeshCoreError.timeout)])

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestOwnerInfo(sessionID: harness.sessionID, timeout: .seconds(5))
    }
    #expect(await harness.session.resetPathPublicKeys.isEmpty)
  }
}
