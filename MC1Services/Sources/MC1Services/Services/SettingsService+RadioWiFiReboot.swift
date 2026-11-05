import Foundation
import MeshCore

extension SettingsService {
  /// Reboot the device.
  public func reboot() async throws {
    try await reboot(expectedContextID: nil, expectedRevision: nil)
  }

  public func reboot(expectedContextID: UUID, expectedRevision: UInt64) async throws {
    try await reboot(expectedContextID: Optional(expectedContextID), expectedRevision: Optional(expectedRevision))
  }

  private func reboot(expectedContextID: UUID?, expectedRevision: UInt64?) async throws {
    try Task.checkCancellation()
    let binding = radioWiFiBinding
    guard !radioWiFiOperationsRetired,
          expectedContextID == nil || expectedContextID == binding.contextID else { throw CancellationError() }
    let capturedSession = session
    let workerID = UUID()
    let worker = Task {
      try await self.performRadioWiFiReboot(
        workerID: workerID, binding: binding, capturedSession: capturedSession, expectedRevision: expectedRevision
      )
    }
    radioWiFiWorkers[workerID] = worker
    guard case .reboot = try await worker.value else { throw SettingsServiceError.invalidResponse }
  }

  private func performRadioWiFiReboot(
    workerID: UUID, binding: RadioWiFiBinding, capturedSession: any ConfigurationSessionOps,
    expectedRevision: UInt64?
  ) async throws -> RadioWiFiWorkResult {
    defer { radioWiFiWorkers[workerID] = nil }
    guard !radioWiFiOperationsRetired else { throw CancellationError() }
    let requiresProof = await binding.state.rebootPending
    let observation = requiresProof ? await readRadioWiFiResetObservation(using: capturedSession) : nil
    guard !radioWiFiOperationsRetired else { throw CancellationError() }
    let candidate = await binding.state.prepareExpectedReboot(
      contextID: binding.contextID, expectedRevision: expectedRevision, observation: observation
    )
    do {
      guard !radioWiFiOperationsRetired, expectedRevision == nil || candidate != nil else {
        throw CancellationError()
      }
      try await capturedSession.reboot()
      if let candidate { await binding.state.completeRebootSubmission(id: candidate, succeeded: true) }
      await emitRadioWiFiChanged()
      return .reboot
    } catch let error as MeshCoreError {
      if let candidate { await binding.state.completeRebootSubmission(id: candidate, succeeded: false) }
      await emitRadioWiFiChanged()
      throw SettingsServiceError.sessionError(error)
    } catch {
      if let candidate { await binding.state.completeRebootSubmission(id: candidate, succeeded: false) }
      await emitRadioWiFiChanged()
      throw error
    }
  }

  func readRadioWiFiResetObservation(using capturedSession: any ConfigurationSessionOps) async -> RadioWiFiResetObservation? {
    let started = ContinuousClock.now
    guard let stats = try? await capturedSession.getStatsCore() else { return nil }
    return RadioWiFiResetObservation(uptimeSeconds: stats.uptimeSeconds, startedAt: started, completedAt: .now)
  }
}
