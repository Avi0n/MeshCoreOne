import Foundation
@testable import MeshCoreBridgeKit

/// A thread-safe test double implementing every bridge listener.
///
/// The bridge invokes listeners synchronously from arbitrary tasks, so recording
/// goes through a lock rather than an actor (an actor hop would reorder relative
/// to the synchronous `receiveBytes`/`sendBytes` path this test relies on).
final class BridgeRecorder: BridgeSendListener, BridgeResultListener, BridgeEventListener, BridgeLogListener,
  @unchecked Sendable {
  private let lock = NSLock()
  private var _sends: [[UInt8]] = []
  private var _successes: [(id: Int64, bytes: [UInt8])] = []
  private var _failures: [(id: Int64, code: Int32, message: String)] = []
  private var _events: [[UInt8]] = []
  private var _logs: [String] = []

  var sends: [[UInt8]] { lock.withLock { _sends } }
  var successes: [(id: Int64, bytes: [UInt8])] { lock.withLock { _successes } }
  var failures: [(id: Int64, code: Int32, message: String)] { lock.withLock { _failures } }
  var events: [[UInt8]] { lock.withLock { _events } }
  var logs: [String] { lock.withLock { _logs } }

  func sendBytes(_ bytes: [UInt8], withoutResponse: Bool) {
    lock.withLock { _sends.append(bytes) }
  }

  var advertisesWriteWithoutResponse: Bool { false }
  var supportsPipelinedReads: Bool { advertisesWriteWithoutResponse }

  func onSuccess(requestID: Int64, resultBytes: [UInt8]) {
    lock.withLock { _successes.append((requestID, resultBytes)) }
  }

  func onFailure(requestID: Int64, errorCode: Int32, message: String) {
    lock.withLock { _failures.append((requestID, errorCode, message)) }
  }

  func onEvent(eventBytes: [UInt8]) {
    lock.withLock { _events.append(eventBytes) }
  }

  func onLog(line: String) {
    lock.withLock { _logs.append(line) }
  }

  /// Polls `condition` until it holds or the deadline passes.
  func wait(timeout: TimeInterval = 5.0, for condition: @Sendable () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
      if condition() { return true }
      try? await Task.sleep(for: .milliseconds(5))
    }
    return condition()
  }
}
