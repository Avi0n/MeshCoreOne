#if canImport(Synchronization)
  import Synchronization
#endif

/// Replies dispatched before `begin` do not prove the write that opens this window.
final class SubmittedCommandWindow: Sendable {
  private let open = Mutex(false)

  func begin() {
    open.withLock { $0 = true }
  }

  var isOpen: Bool {
    open.withLock { $0 }
  }
}
