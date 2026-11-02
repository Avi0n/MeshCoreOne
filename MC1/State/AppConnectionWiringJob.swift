import Foundation

@MainActor
final class AppConnectionWiringJob {
  let id: UUID
  let generation: UUID
  private var worker: Task<Void, Never>?
  private var waiters: [UUID: CheckedContinuation<Void, Never>] = [:]
  private var isCancelled = false
  private var isFinished = false

  #if DEBUG
    var waitingCallerCount: Int {
      waiters.count
    }
  #endif

  init(id: UUID, generation: UUID, operation: @escaping @MainActor () async -> Void) {
    self.id = id
    self.generation = generation
    worker = Task { [weak self] in
      guard let self else { return }
      await operation()
      isFinished = true
      resumeAllWaiters()
      worker = nil
    }
  }

  /// Native UI awaits can outlive cancellation. Retiring a caller must release
  /// its connection worker while the guarded app operation finishes separately.
  func wait() async {
    let waiterID = UUID()
    await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        if Task.isCancelled || isCancelled || isFinished {
          continuation.resume()
        } else {
          waiters[waiterID] = continuation
        }
      }
    } onCancel: {
      Task { @MainActor [weak self] in
        self?.waiters.removeValue(forKey: waiterID)?.resume()
      }
    }
  }

  func cancel() {
    isCancelled = true
    worker?.cancel()
    resumeAllWaiters()
  }

  private func resumeAllWaiters() {
    let pending = Array(waiters.values)
    waiters.removeAll()
    for waiter in pending {
      waiter.resume()
    }
  }
}
