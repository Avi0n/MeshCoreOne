import Foundation
@testable import MC1Services
import Testing

/// Covers the connection circuit breaker's full state machine:
/// closed → open on exhausted retries, 30s cooldown → half-open probe,
/// probe failure → open, success → closed, and the `force` bypass.
@Suite("ConnectionManager Circuit Breaker Tests")
@MainActor
struct ConnectionManagerCircuitBreakerTests {
  /// A timestamp safely past the cooldown window.
  private var pastCooldown: Date {
    Date().addingTimeInterval(-60)
  }

  @Test
  func `closed breaker allows connections`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    #expect(manager.shouldAllowConnection(force: false))
  }

  @Test
  func `failure trips the breaker and blocks connections during cooldown`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.recordConnectionFailure()

    #expect(!manager.shouldAllowConnection(force: false))
  }

  @Test
  func `force bypasses an open breaker`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.recordConnectionFailure()

    #expect(manager.shouldAllowConnection(force: true))
  }

  @Test
  func `elapsed cooldown transitions the breaker to half-open and allows a probe`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setCircuitBreakerOpenForTesting(since: pastCooldown)

    #expect(manager.shouldAllowConnection(force: false))
    // Half-open keeps allowing the probe attempt until it resolves.
    #expect(manager.shouldAllowConnection(force: false))
  }

  @Test
  func `failed half-open probe re-opens the breaker`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setCircuitBreakerOpenForTesting(since: pastCooldown)
    #expect(manager.shouldAllowConnection(force: false))

    manager.recordConnectionFailure()

    #expect(!manager.shouldAllowConnection(force: false))
  }

  @Test
  func `successful connection closes the breaker again`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.recordConnectionFailure()
    manager.recordConnectionSuccess()

    #expect(manager.shouldAllowConnection(force: false))
  }

  @Test
  func `repeated failures while open keep the original cooldown start`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setCircuitBreakerOpenForTesting(since: pastCooldown)
    // A stray failure report while already open must not restart the cooldown.
    manager.recordConnectionFailure()

    #expect(manager.shouldAllowConnection(force: false))
  }

  @Test
  func `connect rejects with a typed error while the breaker is open`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.recordConnectionFailure()

    await #expect(throws: BLEError.self) {
      try await manager.connect(to: UUID())
    }
  }
}
