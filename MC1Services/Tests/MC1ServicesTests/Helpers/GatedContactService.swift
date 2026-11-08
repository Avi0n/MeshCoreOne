import Foundation
@testable import MC1Services

/// Holds a contact sync open while a test exercises another sync path.
actor GatedContactService: ContactServiceProtocol {
  private var hasStarted = false
  private var startWaiters: [CheckedContinuation<Void, Never>] = []
  private var gate: CheckedContinuation<Void, Never>?
  private var isReleased = false

  func waitForSyncStart() async {
    if hasStarted { return }
    await withCheckedContinuation { startWaiters.append($0) }
  }

  func release() {
    isReleased = true
    gate?.resume()
    gate = nil
  }

  func syncContacts(radioID _: UUID, since _: Date?) async throws -> ContactSyncResult {
    hasStarted = true
    while !startWaiters.isEmpty {
      startWaiters.removeFirst().resume()
    }
    if !isReleased {
      await withCheckedContinuation { gate = $0 }
    }
    return ContactSyncResult(contactsReceived: 0, lastSyncTimestamp: 0, isIncremental: true)
  }
}
