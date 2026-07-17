import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// Captures responses delivered through the salvage router.
private actor SalvageCapture {
  private(set) var responses: [RemoteNodeService.SalvagedBinaryResponse] = []

  func append(_ response: RemoteNodeService.SalvagedBinaryResponse) {
    responses.append(response)
  }
}

/// Covers salvage of late binary responses: a status reply arriving after its
/// request timed out is delivered through the router instead of discarded,
/// while unsolicited responses with no recorded timeout are ignored.
@Suite("RemoteNodeService late binary response salvage")
struct RemoteNodeSalvageTests {
  private static let publicKey = Data(repeating: 0xCC, count: 32)

  private struct Harness {
    let service: RemoteNodeService
    let session: MockMeshCoreSession
    let sessionID: UUID
    let capture: SalvageCapture
  }

  private func makeHarness() async throws -> Harness {
    let radioID = UUID()
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
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
    let capture = SalvageCapture()
    await service.setSalvagedResponseRouter { salvaged in
      await capture.append(salvaged)
    }
    await service.startEventMonitoring()
    try await waitUntil("event monitor never subscribed") {
      await session.eventSubscriptionCount == 1
    }
    return Harness(service: service, session: session, sessionID: remoteSession.id, capture: capture)
  }

  private func makeStatusResponse() -> StatusResponse {
    StatusResponse(
      publicKeyPrefix: Self.publicKey.prefix(6),
      battery: 3850,
      txQueueLength: 0,
      noiseFloor: -120,
      lastRSSI: -87,
      packetsReceived: 1000,
      packetsSent: 500,
      airtime: 100,
      uptime: 3600,
      sentFlood: 0,
      sentDirect: 0,
      receivedFlood: 0,
      receivedDirect: 0,
      fullEvents: 0,
      lastSNR: 8.5,
      directDuplicates: 0,
      floodDuplicates: 0,
      rxAirtime: 100,
      receiveErrors: 0
    )
  }

  @Test
  func `status response arriving after a timeout is salvaged through the router`() async throws {
    let harness = try await makeHarness()
    await harness.session.setRequestStatusResult(.failure(MeshCoreError.timeout))

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestStatus(sessionID: harness.sessionID)
    }

    await harness.session.yieldEvent(.statusResponse(makeStatusResponse()))
    try await waitUntil("late status response was never salvaged") {
      await !harness.capture.responses.isEmpty
    }

    let salvaged = await harness.capture.responses.first
    guard case let .status(response) = salvaged else {
      Issue.record("expected a salvaged status response, got \(String(describing: salvaged))")
      return
    }
    #expect(response.publicKeyPrefix == Self.publicKey.prefix(6))
  }

  @Test
  func `unsolicited status response with no recorded timeout is ignored`() async throws {
    let harness = try await makeHarness()

    await harness.session.yieldEvent(.statusResponse(makeStatusResponse()))
    // Give the event monitor a beat to process before asserting nothing arrived.
    try await Task.sleep(for: .milliseconds(100))
    #expect(await harness.capture.responses.isEmpty)
  }

  @Test
  func `a salvaged response is delivered once, not for every duplicate`() async throws {
    let harness = try await makeHarness()
    await harness.session.setRequestStatusResult(.failure(MeshCoreError.timeout))

    await #expect(throws: RemoteNodeError.self) {
      _ = try await harness.service.requestStatus(sessionID: harness.sessionID)
    }

    await harness.session.yieldEvent(.statusResponse(makeStatusResponse()))
    await harness.session.yieldEvent(.statusResponse(makeStatusResponse()))
    try await waitUntil("late status response was never salvaged") {
      await !harness.capture.responses.isEmpty
    }
    try await Task.sleep(for: .milliseconds(100))
    #expect(await harness.capture.responses.count == 1)
  }
}
