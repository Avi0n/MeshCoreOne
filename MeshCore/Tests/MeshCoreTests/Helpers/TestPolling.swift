import Foundation

/// Polls a condition at short intervals until it returns `true` or a timeout expires.
func waitUntil(
  timeout: Duration = .seconds(10),
  pollingInterval: Duration = .milliseconds(10),
  _ message: String = "waitUntil timed out",
  condition: @escaping @Sendable () async -> Bool
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
