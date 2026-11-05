import Foundation
import MeshCore

extension SettingsService {
  enum RadioWiFiWorkResult: Sendable {
    case raw(RadioWiFiCommandResult)
    case credentials(RadioWiFiMutationReceipt)
    case reboot
  }

  func sendTrackedRadioWiFiCommand(
    _ command: String, expectedContextID: UUID? = nil, expectedRevision: UInt64? = nil,
    replyPrefix: String? = nil, correlated: Bool = false
  ) async throws -> (reply: String, revision: UInt64?) {
    let result = try await performTrackedRadioWiFiCommand(
      command, expectedContextID: expectedContextID, expectedRevision: expectedRevision,
      replyPrefix: replyPrefix, correlated: correlated
    )
    if let error = result.error { throw error }
    guard let reply = result.reply else { throw SettingsServiceError.invalidResponse }
    return (reply, result.revision)
  }

  func performTrackedRadioWiFiCommand(
    _ command: String, expectedContextID: UUID? = nil, expectedRevision: UInt64? = nil,
    replyPrefix: String? = nil, correlated: Bool = false
  ) async throws -> RadioWiFiCommandResult {
    try Task.checkCancellation()
    let binding = radioWiFiBinding
    let capturedSession = session
    guard !radioWiFiOperationsRetired,
          expectedContextID == nil || expectedContextID == binding.contextID,
          let token = await binding.state.beginMutation(expectedRevision: expectedRevision) else {
      throw CancellationError()
    }
    guard !radioWiFiOperationsRetired else {
      await binding.state.settle(token, outcome: .rejected)
      throw CancellationError()
    }
    let worker = Task {
      try await self.performRawRadioWiFiCommand(
        command, token: token, binding: binding, capturedSession: capturedSession,
        requiresUncontended: expectedRevision != nil, replyPrefix: replyPrefix, correlated: correlated
      )
    }
    radioWiFiWorkers[token.id] = worker
    await emitRadioWiFiChanged()
    guard case let .raw(result) = try await worker.value else { throw SettingsServiceError.invalidResponse }
    return result
  }

  public func saveRadioWiFiCredentials(
    ssid: String,
    password: String,
    baseline: RadioWiFiCredentialBaseline,
    editorID: UUID,
    ownerIsCurrent: @escaping @MainActor @Sendable () -> Bool
  ) async throws -> RadioWiFiMutationReceipt {
    try Task.checkCancellation()
    let binding = radioWiFiBinding
    let capturedSession = session
    guard !radioWiFiOperationsRetired,
          baseline.contextID == binding.contextID,
          baseline.radioID == binding.state.radioID,
          baseline.editorID == editorID,
          radioWiFiEditorRead?.id == baseline.readID,
          radioWiFiEditorRead?.revision == baseline.revision,
          radioWiFiEditorID == editorID else { throw CancellationError() }
    guard RadioWiFiCredentialBaseline.validCredentials(ssid: ssid, password: password) else {
      throw SettingsServiceError.invalidResponse
    }
    guard let token = await binding.state.beginMutation(expectedRevision: baseline.revision) else {
      throw CancellationError()
    }
    guard !radioWiFiOperationsRetired else {
      await binding.state.settle(token, outcome: .rejected)
      throw CancellationError()
    }
    let worker: Task<RadioWiFiWorkResult, any Error> = Task {
      await self.performRadioWiFiSave(
        ssid: ssid, password: password, baseline: baseline, token: token,
        binding: binding, capturedSession: capturedSession, ownerIsCurrent: ownerIsCurrent
      )
    }
    radioWiFiWorkers[token.id] = worker
    await emitRadioWiFiChanged()
    guard case let .credentials(receipt) = try await worker.value else {
      throw SettingsServiceError.invalidResponse
    }
    return receipt
  }

  @discardableResult
  public func confirmExpectedRadioWiFiReboot(
    id: UUID,
    expectedRadioID: UUID,
    ownerIsCurrent: @MainActor @Sendable () -> Bool
  ) async -> Bool {
    let binding = radioWiFiBinding
    let capturedSession = session
    guard !radioWiFiOperationsRetired,
          binding.state.radioID == expectedRadioID,
          let revision = await binding.state.stableRevision(),
          await radioWiFiReadIsCurrent(revision: revision) else { return false }
    let observation = await readRadioWiFiResetObservation(using: capturedSession)
    guard await radioWiFiReadIsCurrent(revision: revision) else { return false }
    let cleared = await MainActor.run {
      ownerIsCurrent() && binding.state.clearExpectedReboot(
        id: id, contextID: binding.contextID, readRevision: revision, observation: observation
      )
    }
    if cleared { await emitRadioWiFiChanged() }
    return cleared
  }

  private func performRawRadioWiFiCommand(
    _ command: String,
    token: RadioWiFiState.MutationToken,
    binding: RadioWiFiBinding,
    capturedSession: any ConfigurationSessionOps,
    requiresUncontended: Bool,
    replyPrefix: String?,
    correlated: Bool
  ) async throws -> RadioWiFiWorkResult {
    var attempted = false
    do {
      guard !radioWiFiOperationsRetired else { throw CancellationError() }
      if requiresUncontended {
        guard await binding.state.isUncontended(token), !radioWiFiOperationsRetired else {
          throw CancellationError()
        }
      }
      attempted = true
      let reply = if correlated {
        try await sendCorrelatedRadioCLI(command, using: capturedSession)
      } else {
        try await sendCapturedRadioCLI(command, using: capturedSession)
      }
      let classificationText: String = if let replyPrefix, reply.hasPrefix(replyPrefix) {
        String(reply.dropFirst(replyPrefix.count))
      } else {
        reply
      }
      let outcome: RadioWiFiState.MutationOutcome = switch RadioCLIReply(text: classificationText) {
      case .ok, .value: .accepted
      case .error, .unknownCommand: .rejected
      case .other: .uncertain
      }
      let revision = await binding.state.settle(token, outcome: outcome)
      await emitRadioWiFiChanged()
      radioWiFiWorkers[token.id] = nil
      return .raw(RadioWiFiCommandResult(
        contextID: binding.contextID, reply: reply, revision: revision, outcome: outcome, error: nil
      ))
    } catch {
      let rejected = !attempted || Self.radioCLIWasRejectedBeforeAcceptance(error)
      let outcome: RadioWiFiState.MutationOutcome = rejected ? .rejected : .uncertain
      let revision = await binding.state.settle(token, outcome: outcome)
      await emitRadioWiFiChanged()
      radioWiFiWorkers[token.id] = nil
      return .raw(RadioWiFiCommandResult(
        contextID: binding.contextID, reply: nil, revision: revision, outcome: outcome, error: error
      ))
    }
  }

  private func performRadioWiFiSave(
    ssid: String,
    password: String,
    baseline: RadioWiFiCredentialBaseline,
    token: RadioWiFiState.MutationToken,
    binding: RadioWiFiBinding,
    capturedSession: any ConfigurationSessionOps,
    ownerIsCurrent: @MainActor @Sendable () -> Bool
  ) async -> RadioWiFiWorkResult {
    var ssidAccepted = false
    var passwordAccepted = false
    var uncertain = false
    var failure: (any Error)?
    var attempted = false
    do {
      try await requireRadioWiFiSaveOwner(baseline: baseline, token: token, binding: binding, ownerIsCurrent: ownerIsCurrent)
      let passwordChanged = !RadioWiFiCredentialBaseline.hasSameBytes(password, baseline.password)
      if !RadioWiFiCredentialBaseline.hasSameBytes(ssid, baseline.ssid) || passwordChanged {
        attempted = true
        let reply = try await sendCorrelatedRadioCLI("set \(RadioCLIKey.wifiSSID) \(ssid)", using: capturedSession)
        try Self.validateRadioCLIWrite(reply)
        attempted = false
        ssidAccepted = true
      }
      if passwordChanged {
        try await requireRadioWiFiSaveOwner(baseline: baseline, token: token, binding: binding, ownerIsCurrent: ownerIsCurrent)
        attempted = true
        let reply = try await sendCorrelatedRadioCLI("set \(RadioCLIKey.wifiPassword) \(password)", using: capturedSession)
        try Self.validateRadioCLIWrite(reply)
        attempted = false
        passwordAccepted = true
      }
    } catch {
      failure = error
      if Self.radioCLIWasRejectedBeforeAcceptance(error) {
        uncertain = false
      } else {
        uncertain = attempted
      }
    }
    let outcome: RadioWiFiState.MutationOutcome = uncertain ? .uncertain
      : (ssidAccepted || passwordAccepted ? .accepted : .rejected)
    let revision = await binding.state.settle(token, outcome: outcome)
    var updatedBaseline: RadioWiFiCredentialBaseline?
    if radioWiFiEditorID == baseline.editorID, radioWiFiEditorRead?.id == baseline.readID {
      if let revision, !uncertain {
        radioWiFiEditorRead = (id: baseline.readID, revision: revision)
        updatedBaseline = baseline.updating(
          ssid: ssidAccepted ? ssid : baseline.ssid,
          password: passwordAccepted ? password : baseline.password,
          revision: revision
        )
      } else {
        radioWiFiEditorRead = nil
      }
    }
    await emitRadioWiFiChanged()
    radioWiFiWorkers[token.id] = nil
    return .credentials(RadioWiFiMutationReceipt(
      contextID: binding.contextID,
      ssidAccepted: ssidAccepted,
      passwordAccepted: passwordAccepted,
      revision: revision,
      uncertain: uncertain,
      error: failure,
      updatedBaseline: updatedBaseline
    ))
  }

  private func requireRadioWiFiSaveOwner(
    baseline: RadioWiFiCredentialBaseline,
    token: RadioWiFiState.MutationToken,
    binding: RadioWiFiBinding,
    ownerIsCurrent: @MainActor @Sendable () -> Bool
  ) async throws {
    let current = await MainActor.run { ownerIsCurrent() && binding.state.isUncontended(token) }
    guard current,
          !radioWiFiOperationsRetired,
          radioWiFiEditorID == baseline.editorID,
          radioWiFiEditorRead?.id == baseline.readID else { throw CancellationError() }
  }

  static func radioCLIWasRejectedBeforeAcceptance(_ error: any Error) -> Bool {
    guard let error = error as? SettingsServiceError else { return false }
    switch error {
    case .radioCLIRejected, .sessionError(.invalidInput): return true
    default: return false
    }
  }

  func sendCorrelatedRadioCLI(_ command: String, using capturedSession: any ConfigurationSessionOps) async throws -> String {
    do {
      return try await capturedSession.runCorrelatedCliCommand(command)
    } catch let error as MeshCoreError {
      throw SettingsServiceError.sessionError(error)
    }
  }

  func sendCapturedRadioCLI(_ command: String, using capturedSession: any ConfigurationSessionOps) async throws -> String {
    do {
      return try await capturedSession.runCliCommand(command)
    } catch let error as MeshCoreError {
      throw SettingsServiceError.sessionError(error)
    }
  }

  func emitRadioWiFiChanged() async {
    let revision = await radioWiFiState.revision
    eventContinuation?.yield(.radioWiFiChanged(revision: revision))
  }

  public func retireRadioWiFiOperations() async {
    radioWiFiOperationsRetired = true
    radioWiFiEditorID = nil
    radioWiFiEditorRead = nil
    let workers = Array(radioWiFiWorkers.values)
    for worker in workers {
      _ = try? await worker.value
    }
  }
}
