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

  /// On-path pin: with collection on, `PersistentLogger.persist` queues `.info`
  /// and above but not `.debug`.
  @Test
  func `debug does not persist and info does`() async {
    let capturedValue = UserDefaults.standard.object(forKey: AppStorageKey.collectDebugLogs.rawValue)
    UserDefaults.standard.set(true, forKey: AppStorageKey.collectDebugLogs.rawValue)
    defer {
      if let capturedValue {
        UserDefaults.standard.set(capturedValue, forKey: AppStorageKey.collectDebugLogs.rawValue)
      } else {
        UserDefaults.standard.removeObject(forKey: AppStorageKey.collectDebugLogs.rawValue)
      }
      DebugLogBuffer.shared = nil
    }

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

    #expect(!messages.contains("debug-line"))
    #expect(messages.contains("info-line"))
  }

  /// With collection on, an `.info` logged through `PersistentLogger` persists.
  @Test
  func `collection on persists an info line through the logger`() async {
    let capturedValue = UserDefaults.standard.object(forKey: AppStorageKey.collectDebugLogs.rawValue)
    UserDefaults.standard.set(true, forKey: AppStorageKey.collectDebugLogs.rawValue)
    defer {
      if let capturedValue {
        UserDefaults.standard.set(capturedValue, forKey: AppStorageKey.collectDebugLogs.rawValue)
      } else {
        UserDefaults.standard.removeObject(forKey: AppStorageKey.collectDebugLogs.rawValue)
      }
      DebugLogBuffer.shared = nil
    }
    #expect(UserDefaults.standard.bool(forKey: AppStorageKey.collectDebugLogs.rawValue))

    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)

    var messages: [String] = []
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      guard writeAndReadBack(buffer) else { continue }
      DebugLogBuffer.resetPendingStateForTesting()

      let logger = PersistentLogger(subsystem: "test.persist-gate", category: category)
      logger.info("on-info")
      messages = await pollForMessages(
        store,
        buffer: buffer,
        category: category,
        requiredMessage: "on-info"
      )
      if messages.contains("on-info") { break }
    }

    #expect(messages.contains("on-info"))
  }

  /// With collection off, an `.info` logged through `PersistentLogger` does not
  /// persist.
  @Test
  func `collection off drops an info line through the logger`() async {
    let capturedValue = UserDefaults.standard.object(forKey: AppStorageKey.collectDebugLogs.rawValue)
    UserDefaults.standard.set(false, forKey: AppStorageKey.collectDebugLogs.rawValue)
    defer {
      if let capturedValue {
        UserDefaults.standard.set(capturedValue, forKey: AppStorageKey.collectDebugLogs.rawValue)
      } else {
        UserDefaults.standard.removeObject(forKey: AppStorageKey.collectDebugLogs.rawValue)
      }
    }
    #expect(UserDefaults.standard.object(forKey: AppStorageKey.collectDebugLogs.rawValue) != nil)

    let store = MockPersistenceStore()

    // Retry the whole reset-assign-log sequence with a fresh buffer and category per
    // attempt: a foreign suite's `ServiceContainer.init` can reassign the global
    // mid-sequence and swallow entries into its own buffer.
    var sawOffInfo = false
    for _ in 0..<Self.maxReadBackAttempts {
      let buffer = DebugLogBuffer(dataStore: store)
      let category = UUID().uuidString
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()
      guard writeAndReadBack(buffer) else { continue }
      let logger = PersistentLogger(subsystem: "test.persist-gate", category: category)
      logger.info("off-info")
      await buffer.flush()
      try? await Task.sleep(for: .milliseconds(50))
      let messages = await store.debugLogEntries
        .filter { $0.category == category }
        .map(\.message)
      sawOffInfo = messages.contains("off-info")
      if !sawOffInfo { break }
    }
    DebugLogBuffer.shared = nil

    #expect(!sawOffInfo)
  }
}
