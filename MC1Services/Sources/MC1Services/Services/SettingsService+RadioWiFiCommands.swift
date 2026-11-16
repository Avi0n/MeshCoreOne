import Foundation

public extension SettingsService {
  func clearRadioWiFi(
    expectedContextID: UUID, expectedRevision: UInt64
  ) async throws -> RadioWiFiReadResult? {
    let result = try await sendTrackedRadioWiFiCommand(
      "set \(RadioCLIKey.wifiClear)", expectedContextID: expectedContextID,
      expectedRevision: expectedRevision, correlated: true
    )
    try Self.validateRadioCLIWrite(result.reply)
    guard case let .supported(probe) = try await probeRadioWiFiSettings(expectedContextID: expectedContextID) else {
      return nil
    }
    return await readRadioWiFiObservation(probe: probe)
  }

  @discardableResult
  func setRadioWiFiEnabled(
    _ enabled: Bool, expectedContextID: UUID, expectedRevision: UInt64
  ) async throws -> UInt64? {
    let receipt = try await changeRadioWiFiEnabled(
      enabled, expectedContextID: expectedContextID, expectedRevision: expectedRevision
    )
    if let error = receipt.error { throw error }
    return receipt.revision
  }

  func changeRadioWiFiEnabled(
    _ enabled: Bool, expectedContextID: UUID, expectedRevision: UInt64
  ) async throws -> RadioWiFiEnabledReceipt {
    let value = enabled ? RadioWiFiSnapshot.enabledValue : RadioWiFiSnapshot.disabledValue
    let result = try await performTrackedRadioWiFiCommand(
      "set \(RadioCLIKey.wifiEnabled) \(value)", expectedContextID: expectedContextID,
      expectedRevision: expectedRevision, correlated: true
    )
    var failure = result.error
    if failure == nil {
      do {
        guard let reply = result.reply else { throw SettingsServiceError.invalidResponse }
        try Self.validateRadioCLIWrite(reply)
      } catch {
        failure = error
      }
    }
    return RadioWiFiEnabledReceipt(
      contextID: result.contextID, revision: result.revision, outcome: result.outcome, error: failure
    )
  }
}
