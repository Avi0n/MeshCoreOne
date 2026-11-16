import Foundation
import os

/// Serializes every command-response exchange that relies on event matching.
///
/// Many MeshCore commands wait for generic events such as `.ok`, `.error`, or a
/// singleton typed response. Binary requests (status, telemetry, owner info, etc.)
/// additionally learn their `expectedAck` from a `.messageSent` event whose tag is
/// not known in advance. Because `EventDispatcher` broadcasts to every live
/// subscription with no per-command correlation, two exchanges in flight at once can
/// consume each other's responses — a unicast send and a binary request both match a
/// bare `.messageSent`, and either can steal the other's tag. Routing every exchange
/// through one serializer guarantees a single request/response is outstanding at a
/// time, which is the only structural defense given the learned (not precomputed) ack.
public actor RequestResponseSerializer {
  private var isRequestInFlight = false
  private var waiters: [CheckedContinuation<Void, Never>] = []
  private var settlementWaiters: [CheckedContinuation<Void, Never>] = []
  private var hasUncertainResponseHistory = false

  func requireIdentityImportResponseAttribution() throws {
    guard !hasUncertainResponseHistory else { throw IdentityImportReconnectionError.uncertainResponseHistory }
  }

  func noteUncertainResponseHistory() {
    hasUncertainResponseHistory = true
  }

  /// ContactService lives outside MeshCore, and the memberwise initializer is internal.
  public init() {}

  public var waiterCountForTest: Int {
    waiters.count
  }

  #if DEBUG
    var quiescenceWaiterCountForTesting: Int {
      settlementWaiters.count
    }
  #endif

  /// Acquires the serializer, waiting if another request/response exchange is active.
  public func acquire() async {
    if !isRequestInFlight {
      isRequestInFlight = true
      return
    }

    await withCheckedContinuation { continuation in
      waiters.append(continuation)
    }
  }

  /// Releases the serializer to the next waiting request.
  public func release() {
    if let next = waiters.first {
      waiters.removeFirst()
      next.resume()
    } else {
      isRequestInFlight = false
      let settled = settlementWaiters
      settlementWaiters.removeAll()
      for waiter in settled {
        waiter.resume()
      }
    }
  }

  /// Joins the exchanges themselves, rather than their cancellable callers.
  public func waitForQuiescence() async {
    guard isRequestInFlight else { return }
    await withCheckedContinuation { settlementWaiters.append($0) }
  }

  func responseAttributionIsUncertain() -> Bool {
    hasUncertainResponseHistory
  }

  /// Holds the slot through reply or timeout so a cancelled caller cannot leave a reply for the next command.
  /// Pass `resumeCallerOnCancel: false` to wait for settlement when acceptance must survive cancellation.
  /// Cancellation before the exchange starts prevents the write.
  public func withSerialization<T: Sendable>(
    resumeCallerOnCancel: Bool = true,
    _ operation: @escaping @Sendable () async throws -> T
  ) async throws -> T {
    await acquire()

    let pending = OSAllocatedUnfairLock<CheckedContinuation<T, Error>?>(initialState: nil)

    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<T, Error>) in
        let cancelledBeforeStart = pending.withLock { stored -> Bool in
          if Task.isCancelled {
            return true
          }
          stored = continuation
          return false
        }

        if cancelledBeforeStart {
          release()
          continuation.resume(throwing: CancellationError())
          return
        }

        // This task is not a child of the caller, so cancelling the caller does
        // not abort it. It holds the slot until the exchange resolves, then
        // releases and hands the result to the caller if it is still waiting.
        Task {
          let outcome: Result<T, Error>
          do {
            outcome = try await .success(operation())
          } catch {
            if let failure = error as? MeshCoreError, case .timeout = failure {
              noteUncertainResponseHistory()
            }
            outcome = .failure(error)
          }
          release()
          let waiting = pending.withLock { stored -> CheckedContinuation<T, Error>? in
            let continuation = stored
            stored = nil
            return continuation
          }
          waiting?.resume(with: outcome)
        }
      }
    } onCancel: {
      guard resumeCallerOnCancel else { return }
      let waiting = pending.withLock { stored -> CheckedContinuation<T, Error>? in
        let continuation = stored
        stored = nil
        return continuation
      }
      waiting?.resume(throwing: CancellationError())
    }
  }
}
