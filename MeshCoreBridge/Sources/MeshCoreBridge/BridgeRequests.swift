import Foundation

/// Tracks Swift command tasks by request ID for native cancellation.
final class BridgeRequests: @unchecked Sendable {
  private struct Entry {
    let token: UUID
    let task: Task<Void, Never>
  }

  private let lock = NSLock()
  private var entries: [Int64: Entry] = [:]

  func dispatch(_ requestID: Int64, operation: @escaping @Sendable () async -> Void) {
    let previous = lock.withLock {
      let token = UUID()
      let previous = entries[requestID]
      let task = Task { [self] in
        await operation()
        finish(requestID, token: token)
      }
      // Completion takes the same lock, so it cannot precede registration.
      entries[requestID] = Entry(token: token, task: task)
      return previous?.task
    }
    previous?.cancel()
  }

  func cancel(_ requestID: Int64) {
    let task = lock.withLock { entries[requestID]?.task }
    task?.cancel()
  }

  func cancelAll() {
    let tasks = lock.withLock { entries.values.map(\.task) }
    for task in tasks {
      task.cancel()
    }
  }

  private func finish(_ requestID: Int64, token: UUID) {
    lock.withLock {
      if entries[requestID]?.token == token { entries[requestID] = nil }
    }
  }
}
