import Foundation

/// Polls a condition at short intervals until it returns `true` or a timeout expires.
/// Replaces fixed `Task.sleep` calls in tests where we wait for an asynchronous state change.
///
/// - Parameters:
///   - timeout: Maximum time to wait before failing.
///   - pollingInterval: How often to re-check the condition.
///   - message: Failure message if the timeout expires.
///   - condition: An async closure returning `true` when the expected state is reached.
func waitUntil(
  timeout: Duration = .seconds(10),
  pollingInterval: Duration = .milliseconds(10),
  _ message: String = "waitUntil timed out",
  condition: @escaping @MainActor () async -> Bool
) async throws {
  let deadline = ContinuousClock.now + timeout
  while ContinuousClock.now < deadline {
    if await condition() { return }
    try await Task.sleep(for: pollingInterval)
  }
  if await condition() { return }
  struct WaitTimeoutError: Error, CustomStringConvertible {
    let description: String
  }
  throw WaitTimeoutError(description: message)
}
