import Foundation
@testable import MeshCore
import Testing

/// Verifies `MeshCoreSession.stop(disconnectTransport:)`: a session teardown
/// over a transport the caller does not own (a reconnect cycle still riding on
/// it) must leave the link open, while the default severs it.
@Suite("MeshCoreSession Stop Transport Tests")
struct MeshCoreSessionStopTests {
  @Test
  func `stop with disconnectTransport false leaves the transport connected`() async {
    let transport = MockMeshTransport()
    let session = MeshCoreSession(transport: transport)

    await session.stop(disconnectTransport: false)

    #expect(await transport.disconnectInvocations == 0)
  }

  @Test
  func `stop by default disconnects the transport`() async {
    let transport = MockMeshTransport()
    let session = MeshCoreSession(transport: transport)

    await session.stop()

    #expect(await transport.disconnectInvocations == 1)
  }
}
