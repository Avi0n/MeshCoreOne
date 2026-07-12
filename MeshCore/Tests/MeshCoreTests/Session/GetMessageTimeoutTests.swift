@testable import MeshCore
import Testing

@Suite("MeshCoreSession getMessage timeout")
struct GetMessageTimeoutTests {
  @Test
  func `getMessage times out when no response arrives`() async {
    let transport = MockTransport()
    try? await transport.connect()

    let configuration = SessionConfiguration(defaultTimeout: 0.02, clientIdentifier: "MeshCore-Tests")
    let session = MeshCoreSession(transport: transport, configuration: configuration)

    await #expect(throws: MeshCoreError.self) {
      _ = try await session.getMessage()
    }
  }

  @Test
  func `getMessage timeout override can be shorter than the session default`() async {
    let transport = MockTransport()
    try? await transport.connect()

    let configuration = SessionConfiguration(defaultTimeout: 2.0, clientIdentifier: "MeshCore-Tests")
    let session = MeshCoreSession(transport: transport, configuration: configuration)
    let clock = ContinuousClock()
    let start = clock.now

    await #expect(throws: MeshCoreError.self) {
      _ = try await session.getMessage(timeout: 0.02)
    }

    let elapsed = start.duration(to: clock.now)
    #expect(elapsed < .seconds(1))
  }

  @Test
  func `getMessage timeout override can extend beyond the session default`() async {
    let transport = MockTransport()
    try? await transport.connect()

    let configuration = SessionConfiguration(defaultTimeout: 0.02, clientIdentifier: "MeshCore-Tests")
    let session = MeshCoreSession(transport: transport, configuration: configuration)
    let clock = ContinuousClock()
    let start = clock.now

    await #expect(throws: MeshCoreError.self) {
      _ = try await session.getMessage(timeout: 0.12)
    }

    let elapsed = start.duration(to: clock.now)
    #expect(elapsed >= .milliseconds(80))
  }
}
