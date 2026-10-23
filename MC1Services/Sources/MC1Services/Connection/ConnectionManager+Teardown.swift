import Foundation

extension ConnectionManager {
  func ownsConnectionAttempt(deviceID: UUID, attemptID: UUID) -> Bool {
    connectingDeviceID == deviceID && connectingAttemptID == attemptID
  }

  func beginConnectionTeardown() {
    connectionTeardownCount += 1
  }

  func endConnectionTeardown() {
    connectionTeardownCount -= 1
    guard connectionTeardownCount == 0 else { return }
    let waiters = connectionTeardownWaiters
    connectionTeardownWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  /// A replacement connect waits here until teardown has stopped the old session and closed its transport.
  func waitForConnectionTeardown() async throws {
    #if DEBUG
      if connectionTeardownCount > 0 {
        testConnectionTeardownWaiterCount += 1
      }
      let didWait = connectionTeardownCount > 0
      defer {
        if didWait { testConnectionTeardownWaiterCount -= 1 }
      }
    #endif
    while connectionTeardownCount > 0 {
      await withCheckedContinuation { connectionTeardownWaiters.append($0) }
    }
    try Task.checkCancellation()
  }
}
