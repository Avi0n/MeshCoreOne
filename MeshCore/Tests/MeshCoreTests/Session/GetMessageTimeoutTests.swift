@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("MeshCoreSession getMessage timeout")
struct GetMessageTimeoutTests {
  @Test
  func `getMessage times out when no response arrives`() async throws {
    let session = try await startedSession(defaultTimeout: 0.02)

    await #expect {
      _ = try await session.getMessage()
    } throws: { error in
      isTimeout(error)
    }
    await session.stop()
  }

  @Test
  func `getMessage timeout override can be shorter than the session default`() async throws {
    let session = try await startedSession(defaultTimeout: 2.0)
    let clock = ContinuousClock()
    let start = clock.now

    await #expect {
      _ = try await session.getMessage(timeout: 0.02)
    } throws: { error in
      isTimeout(error)
    }

    let elapsed = start.duration(to: clock.now)
    #expect(elapsed < .seconds(1))
    await session.stop()
  }

  @Test
  func `getMessage timeout override can extend beyond the session default`() async throws {
    let session = try await startedSession(defaultTimeout: 0.02)
    let clock = ContinuousClock()
    let start = clock.now

    await #expect {
      _ = try await session.getMessage(timeout: 0.12)
    } throws: { error in
      isTimeout(error)
    }

    let elapsed = start.duration(to: clock.now)
    #expect(elapsed >= .milliseconds(80))
    await session.stop()
  }

  private func startedSession(defaultTimeout: Double) async throws -> MeshCoreSession {
    let configuration = SessionConfiguration(defaultTimeout: defaultTimeout, clientIdentifier: "MeshCore-Tests")
    let session = MeshCoreSession(transport: HandshakeOnlyTransport(), configuration: configuration)
    try await session.start()
    return session
  }

  private func isTimeout(_ error: any Error) -> Bool {
    if case MeshCoreError.timeout = error { return true }
    return false
  }
}
