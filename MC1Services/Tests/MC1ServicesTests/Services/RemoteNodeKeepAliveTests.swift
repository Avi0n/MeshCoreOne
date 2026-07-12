import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RemoteNodeService keep-alive retry logic")
struct RemoteNodeKeepAliveTests {
  // MARK: - Transient failures

  @Test
  func `single transient failure retries without disconnecting`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.sessionError(.timeout),
      consecutiveFailures: &failures
    )
    #expect(failures == 1)
    #expect(action == .retryNextInterval)
  }

  @Test
  func `two consecutive transient failures triggers disconnect`() {
    var failures = 1
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.sessionError(.timeout),
      consecutiveFailures: &failures
    )
    #expect(failures == 2)
    #expect(action == .disconnect)
  }

  @Test
  func `success resets failure counter`() {
    var failures = 1
    KeepAliveRetryPolicy.recordSuccess(consecutiveFailures: &failures)
    #expect(failures == 0)
  }

  // MARK: - Terminal failures

  @Test
  func `sessionNotFound disconnects immediately without incrementing counter`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.sessionNotFound,
      consecutiveFailures: &failures
    )
    #expect(failures == 0)
    #expect(action == .disconnectNow)
  }

  @Test
  func `contactNotFound disconnects immediately without incrementing counter`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.contactNotFound,
      consecutiveFailures: &failures
    )
    #expect(failures == 0)
    #expect(action == .disconnectNow)
  }

  // MARK: - Transient error variants

  @Test
  func `deviceError is treated as transient failure`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.sessionError(.deviceError(code: 7)),
      consecutiveFailures: &failures
    )
    #expect(failures == 1)
    #expect(action == .retryNextInterval)
  }

  @Test
  func `notConnected is treated as transient failure`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.sessionError(.notConnected),
      consecutiveFailures: &failures
    )
    #expect(failures == 1)
    #expect(action == .retryNextInterval)
  }

  // MARK: - Skip and stop

  @Test
  func `floodRouted is not counted as a failure`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.floodRouted,
      consecutiveFailures: &failures
    )
    #expect(failures == 0)
    #expect(action == .skip)
  }

  @Test
  func `CancellationError stops the loop quietly`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: CancellationError(),
      consecutiveFailures: &failures
    )
    #expect(failures == 0)
    #expect(action == .stop)
  }

  @Test
  func `RemoteNodeError.cancelled stops the loop quietly`() {
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: RemoteNodeError.cancelled,
      consecutiveFailures: &failures
    )
    #expect(failures == 0)
    #expect(action == .stop)
  }

  @Test
  func `unknown non-RemoteNodeError disconnects immediately`() {
    struct PersistenceError: Error {}
    var failures = 0
    let action = KeepAliveRetryPolicy.evaluate(
      error: PersistenceError(),
      consecutiveFailures: &failures
    )
    #expect(failures == 0)
    #expect(action == .disconnectNow)
  }

  // MARK: - Failure reasons

  @Test
  func `failure reason describes timeout`() {
    let reason = KeepAliveRetryPolicy.failureReason(
      for: RemoteNodeError.sessionError(.timeout)
    )
    #expect(reason == "timeout")
  }

  @Test
  func `failure reason describes device error with code`() {
    let reason = KeepAliveRetryPolicy.failureReason(
      for: RemoteNodeError.sessionError(.deviceError(code: 42))
    )
    #expect(reason == "device error (code: 42)")
  }

  @Test
  func `failure reason describes transport not connected`() {
    let reason = KeepAliveRetryPolicy.failureReason(
      for: RemoteNodeError.sessionError(.notConnected)
    )
    #expect(reason == "transport not connected")
  }

  @Test
  func `failure reason describes session not found`() {
    let reason = KeepAliveRetryPolicy.failureReason(
      for: RemoteNodeError.sessionNotFound
    )
    #expect(reason == "session not found")
  }

  @Test
  func `failure reason describes contact not found`() {
    let reason = KeepAliveRetryPolicy.failureReason(
      for: RemoteNodeError.contactNotFound
    )
    #expect(reason == "contact not found")
  }
}
