import Foundation
import MeshCore

/// The power-off attempt no longer owns the link it started on.
public struct PowerOffSuperseded: Error, Sendable {
  public init() {}
}

extension ConnectionManager {
  /// True when this device's early auto-reconnect belongs to the owned power-off.
  func powerOffSuppressesReconnect(for deviceID: UUID) -> Bool {
    powerOffBlocksReconnect && powerOffOperation?.deviceID == deviceID
  }

  /// True while an owned power-off attempt should keep reconnect from starting.
  var powerOffBlocksReconnect: Bool {
    guard let powerOffOperation else { return false }
    return !powerOffOperation.settled && !powerOffOperation.invalidated
  }

  /// Cancel can still stop the frame. False once that frame has been handed to the transport.
  public var powerOffCancelAvailable: Bool {
    _ = powerOffCancelGeneration
    guard let powerOffOperation, !powerOffOperation.settled else { return true }
    return !powerOffOperation.gate.didSubmit
  }

  public func powerOffOwnsCleanup(_ token: UUID) -> Bool {
    powerOffCleanupToken == token
  }

  /// Sends one power-off for the current link. A second caller waits for that attempt.
  /// Success disconnects only the captured link and returns its cleanup token.
  @discardableResult
  public func powerOffCurrentLink() async throws -> UUID {
    if let active = powerOffOperation, !active.settled, let worker = active.worker {
      return try await worker.value
    }
    guard let session, let device = connectedDevice else {
      throw SettingsServiceError.notConnected
    }

    let gate = PowerOffSubmissionGate()
    let operation = PowerOffOperation(
      sessionID: ObjectIdentifier(session),
      deviceID: device.id,
      gate: gate
    )
    powerOffOperation = operation
    gate.onSubmitted = { [weak self] in
      Task { @MainActor in
        self?.powerOffCancelGeneration &+= 1
      }
    }
    stopReconnectionWatchdog()
    cancelWiFiReconnection()

    let worker = Task { @MainActor () throws -> UUID in
      let result = await session.exchangePowerOff(gate: gate)
      try await self.finishPowerOff(operation, result: result)
      return operation.id
    }
    operation.worker = worker

    return try await withTaskCancellationHandler {
      try await worker.value
    } onCancel: {
      _ = gate.cancelIfUnsubmitted()
    }
  }

  /// Records a peripheral or socket loss when its event time is not earlier than submission.
  func notePowerOffRemoteLoss(deviceID: UUID?, eventTime: CFAbsoluteTime) {
    guard let deviceID, let operation = powerOffOperation, !operation.settled, !operation.invalidated else { return }
    guard operation.deviceID == deviceID else { return }
    guard let submittedAt = operation.gate.submissionTime, eventTime >= submittedAt else { return }
    operation.remoteLossAfterSubmission = true
  }

  /// A published session drops pending cleanup. Nil keeps the token so the owned teardown can finish.
  /// A different session also invalidates a worker that has not settled.
  func invalidatePowerOffIfReplaced(newSession: MeshCoreSession?) {
    guard let newSession else { return }
    powerOffCleanupToken = nil
    guard let operation = powerOffOperation, !operation.settled else { return }
    guard ObjectIdentifier(newSession) != operation.sessionID else { return }
    operation.invalidated = true
    _ = operation.gate.cancelIfUnsubmitted()
  }

  /// An admitted connection change drops cleanup even after the worker has settled.
  /// The owned power-off disconnect does not call this.
  func invalidatePowerOffForConnectionChange() {
    powerOffCleanupToken = nil
    guard let operation = powerOffOperation, !operation.settled else { return }
    operation.invalidated = true
    _ = operation.gate.cancelIfUnsubmitted()
  }

  private func finishPowerOff(
    _ operation: PowerOffOperation,
    result: PowerOffExchangeResult
  ) async throws {
    var resumeReconnect = false
    defer { releasePowerOff(operation, resumeReconnect: resumeReconnect) }

    if operation.invalidated {
      throw PowerOffSuperseded()
    }

    switch result {
    case .submitted(.silence):
      try await completeOwnedPowerOff(operation)
    case .submitted(.sendFailed), .submitted(.stopped):
      if operation.remoteLossAfterSubmission, !operation.invalidated {
        try await completeOwnedPowerOff(operation)
      } else {
        resumeReconnect = true
        throw failure(for: result)
      }
    default:
      resumeReconnect = true
      throw failure(for: result)
    }
  }

  private func completeOwnedPowerOff(_ operation: PowerOffOperation) async throws {
    guard !operation.invalidated else { throw PowerOffSuperseded() }
    powerOffCleanupToken = operation.id
    await disconnect(reason: .powerOff)
    if operation.invalidated {
      powerOffCleanupToken = nil
      throw PowerOffSuperseded()
    }
  }

  private func releasePowerOff(_ operation: PowerOffOperation, resumeReconnect: Bool) {
    operation.settled = true
    if powerOffOperation?.id == operation.id {
      powerOffOperation = nil
    }
    powerOffCancelGeneration &+= 1
    guard resumeReconnect, !operation.invalidated else { return }
    guard connectionIntent.wantsConnection else { return }
    if currentTransportType == .wifi, connectionState == .connecting {
      if !isHandlingWiFiDisconnection {
        startWiFiReconnection()
      }
      return
    }
    guard connectionState == .disconnected else { return }
    startReconnectionWatchdog()
  }

  private func failure(for result: PowerOffExchangeResult) -> Error {
    switch result {
    case .submitted(.stillOn):
      SettingsServiceError.powerOffUnsupported
    case let .submitted(.rejected(code)):
      SettingsServiceError.sessionError(.deviceError(code: code))
    case .notSubmitted(.cancelled):
      CancellationError()
    case .submitted(.stopped):
      SettingsServiceError.sessionError(.connectionLost(underlying: nil))
    case .submitted(.sendFailed), .notSubmitted(.notConnected), .notSubmitted(.notRunning), .submitted(.silence):
      SettingsServiceError.sessionError(.notConnected)
    }
  }
}

final class PowerOffOperation {
  let id = UUID()
  let sessionID: ObjectIdentifier
  let deviceID: UUID
  let gate: PowerOffSubmissionGate
  var worker: Task<UUID, Error>?
  var invalidated = false
  var settled = false
  var remoteLossAfterSubmission = false

  init(sessionID: ObjectIdentifier, deviceID: UUID, gate: PowerOffSubmissionGate) {
    self.sessionID = sessionID
    self.deviceID = deviceID
    self.gate = gate
  }
}
