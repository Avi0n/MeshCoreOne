import Foundation

/// Polls a condition until it becomes true or a timeout expires.
func waitUntil(
  timeout: Duration = .seconds(2),
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
