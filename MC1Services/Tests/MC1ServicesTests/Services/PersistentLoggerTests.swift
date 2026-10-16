import Foundation
@testable import MC1Services
import Testing

/// Serialized because this suite reassigns the process-global `DebugLogBuffer.shared`,
/// and other suites that build a `ServiceContainer` can interleave on that same global.
@Suite("PersistentLogger persist gate", .serialized)
struct PersistentLoggerTests {
  private static let maxReadBackAttempts = 5

  private func writeAndReadBack(_ buffer: DebugLogBuffer, attempts: Int = maxReadBackAttempts) -> Bool {
    for _ in 0..<attempts {
      DebugLogBuffer.shared = buffer
      if DebugLogBuffer.shared === buffer {
        return true
      }
    }
    return false
  }

  private func pollForMessages(
    _ store: MockPersistenceStore,
    buffer: DebugLogBuffer,
    category: String,
    requiredMessage: String,
    attempts: Int = 30,
    delay: Duration = .milliseconds(30)
  ) async -> [String] {
    for _ in 0..<attempts {
      await buffer.flush()
      let messages = await store.debugLogEntries
        .filter { $0.category == category }
        .map(\.message)
      if messages.contains(requiredMessage) {
        return messages
      }
      try? await Task.sleep(for: delay)
    }
    return await store.debugLogEntries
      .filter { $0.category == category }
      .map(\.message)
  }

  @Test
  func `debug does not persist and info does`() async {
    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)

    var messages: [String] = []
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      guard writeAndReadBack(buffer) else { continue }
      DebugLogBuffer.resetPendingStateForTesting()

      let logger = PersistentLogger(subsystem: "test.persist-gate", category: category)
      logger.debug("debug-line")
      logger.info("info-line")
      messages = await pollForMessages(
        store,
        buffer: buffer,
        category: category,
        requiredMessage: "info-line"
      )
      if messages.contains("info-line") { break }
    }
    defer { DebugLogBuffer.shared = nil }

    #expect(!messages.contains("debug-line"))
    #expect(messages.contains("info-line"))
  }
}
