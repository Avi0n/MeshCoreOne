import Foundation

@MainActor
struct AccessoryPickerState {
  private enum Phase {
    case idle
    case awaitingSelection(Attempt, CheckedContinuation<UUID, Error>)
    case callerCancelled(Attempt)
    case awaitingDismissal(Attempt, Settlement)
  }

  private enum Settlement {
    case selected
    case failed
    case cleanupRequested
  }

  private var phase: Phase = .idle

  var attempt: Attempt? {
    switch phase {
    case .idle: nil
    case let .awaitingSelection(attempt, _), let .callerCancelled(attempt), let .awaitingDismissal(attempt, _): attempt
    }
  }

  var isActive: Bool {
    attempt != nil
  }

  @discardableResult
  mutating func begin(
    id: UUID,
    presentedAt: Date,
    filteredDiscovery: Bool,
    continuation: CheckedContinuation<UUID, Error>
  ) -> Bool {
    guard !isActive else {
      continuation.resume(throwing: AccessorySetupKitError.pickerAlreadyActive)
      return false
    }
    let attempt = Attempt(id: id, presentedAt: presentedAt, filteredDiscovery: filteredDiscovery, outcome: .presented)
    phase = .awaitingSelection(attempt, continuation)
    return true
  }

  mutating func accessoryAdded(bluetoothID: UUID?) -> SelectionDisposition {
    switch phase {
    case .idle:
      return .unclaimed
    case let .awaitingSelection(attempt, continuation):
      var attempt = attempt
      attempt.outcome = .selected
      phase = .awaitingDismissal(attempt, .selected)
      if let bluetoothID {
        continuation.resume(returning: bluetoothID)
      } else {
        continuation.resume(throwing: AccessorySetupKitError.noBluetoothIdentifier)
      }
      return .selected
    case var .callerCancelled(attempt):
      attempt.outcome = .orphanedAfterCancellation
      phase = .awaitingDismissal(attempt, .cleanupRequested)
      return .removeAccessory
    case .awaitingDismissal:
      return .ignored
    }
  }

  mutating func cancel(attemptID: UUID) {
    guard attempt?.id == attemptID else { return }
    switch phase {
    case let .awaitingSelection(attempt, continuation):
      var attempt = attempt
      attempt.outcome = .cancelled
      phase = .callerCancelled(attempt)
      continuation.resume(throwing: CancellationError())
    case let .awaitingDismissal(pending, settlement):
      var attempt = pending
      switch settlement {
      case .selected:
        attempt.outcome = .cancelledAfterSelection
        phase = .awaitingDismissal(attempt, .selected)
      case .cleanupRequested:
        break
      case .failed:
        attempt.outcome = .cancelled
        phase = .callerCancelled(attempt)
      }
    case .idle, .callerCancelled:
      break
    }
  }

  mutating func didPresent() {
    guard var attempt else { return }
    attempt.hasPresented = true
    switch phase {
    case let .awaitingSelection(_, continuation):
      phase = .awaitingSelection(attempt, continuation)
    case .callerCancelled:
      phase = .callerCancelled(attempt)
    case let .awaitingDismissal(_, settlement):
      phase = .awaitingDismissal(attempt, settlement)
    case .idle:
      break
    }
  }

  mutating func fail(_ error: AccessorySetupKitError, attemptID: UUID) {
    guard var attempt, attempt.id == attemptID else { return }
    switch phase {
    case .awaitingDismissal(_, .selected), .awaitingDismissal(_, .cleanupRequested):
      return
    default:
      break
    }
    let (outcome, presentationRejected): (Outcome, Bool) = switch error {
    case .pickerRestricted: (.pickerRestricted, true)
    case .pickerAlreadyActive: (.pickerAlreadyActive, true)
    case .discoveryTimeout: (.discoveryTimeout, false)
    case .pairingFailed: (.pairingFailed, false)
    case .pickerDismissed: (.cancelled, false)
    default: (.connectionFailed, false)
    }
    attempt.outcome = outcome
    let previousPhase = phase
    if presentationRejected || !attempt.hasPresented {
      phase = .idle
    } else if case .callerCancelled = previousPhase {
      phase = .callerCancelled(attempt)
    } else {
      phase = .awaitingDismissal(attempt, .failed)
    }
    if case let .awaitingSelection(_, continuation) = previousPhase {
      continuation.resume(throwing: error)
    }
  }

  mutating func userCancelled(attemptID: UUID) {
    guard var attempt, attempt.id == attemptID else { return }
    attempt.outcome = .cancelled
    switch phase {
    case let .awaitingSelection(_, continuation):
      phase = .awaitingSelection(attempt, continuation)
    case .callerCancelled:
      phase = .callerCancelled(attempt)
    case .idle, .awaitingDismissal:
      break
    }
  }

  @discardableResult
  mutating func dismiss() -> Attempt? {
    let dismissedAttempt = attempt
    let previousPhase = phase
    phase = .idle
    if case let .awaitingSelection(_, continuation) = previousPhase {
      continuation.resume(throwing: AccessorySetupKitError.pickerDismissed)
    }
    return dismissedAttempt
  }

  mutating func invalidate() {
    let previousPhase = phase
    phase = .idle
    if case let .awaitingSelection(_, continuation) = previousPhase {
      continuation.resume(throwing: AccessorySetupKitError.sessionInvalidated)
    }
  }
}
