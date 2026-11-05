import Foundation
import MeshCore

public extension SettingsService {
  func probeRadioWiFiSettings(expectedContextID: UUID? = nil) async throws -> RadioWiFiSnapshot.Capability {
    let binding = radioWiFiBinding
    let capturedSession = session
    guard expectedContextID == nil || expectedContextID == binding.contextID,
          !radioWiFiOperationsRetired,
          let revision = await binding.state.stableRevision(),
          await radioWiFiReadIsCurrent(revision: revision) else { return .busy }
    let value = try await getRadioCLIValue(RadioCLIKey.wifiEnabled, session: capturedSession)
    guard await radioWiFiReadIsCurrent(revision: revision) else { return .busy }
    guard let value else { return .unavailable }
    let enabled: Bool
    switch value.trimmingCharacters(in: .whitespacesAndNewlines) {
    case RadioWiFiSnapshot.enabledValue: enabled = true
    case RadioWiFiSnapshot.disabledValue: enabled = false
    default: throw SettingsServiceError.invalidResponse
    }
    return .supported(RadioWiFiSnapshot.Probe(
      enabled: enabled, contextID: binding.contextID, revision: revision
    ))
  }

  func readRadioWiFiObservation(probe: RadioWiFiSnapshot.Probe) async -> RadioWiFiReadResult? {
    let binding = radioWiFiBinding
    let capturedSession = session
    guard probe.contextID == binding.contextID,
          await radioWiFiReadIsCurrent(revision: probe.revision) else { return nil }
    var networkName: String?
    var networkNameWasRead = false
    var associated: Bool?
    var ipAddress: String?
    var ipAddressWasRead = false
    var failure: (any Error)?
    do {
      guard let ssid = try await getRadioCLIValue(RadioCLIKey.wifiSSID, session: capturedSession) else {
        throw SettingsServiceError.invalidResponse
      }
      guard await radioWiFiReadIsCurrent(revision: probe.revision) else { return nil }
      networkName = Self.radioWiFiNetworkName(ssid)
      networkNameWasRead = true
      guard let status = try await getRadioCLIValue(RadioCLIKey.wifiStatus, session: capturedSession) else {
        throw SettingsServiceError.invalidResponse
      }
      guard await radioWiFiReadIsCurrent(revision: probe.revision) else { return nil }
      associated = status.trimmingCharacters(in: .whitespacesAndNewlines) == RadioWiFiSnapshot.connectedStatus
      guard let ip = try await getRadioCLIValue(RadioCLIKey.wifiIP, session: capturedSession) else {
        throw SettingsServiceError.invalidResponse
      }
      guard await radioWiFiReadIsCurrent(revision: probe.revision) else { return nil }
      ipAddress = ip.isEmpty || ip == RadioCLIReply.notConnected ? nil : ip
      ipAddressWasRead = true
    } catch {
      guard await radioWiFiReadIsCurrent(revision: probe.revision) else { return nil }
      failure = error
    }
    return RadioWiFiReadResult(
      contextID: binding.contextID, radioID: binding.state.radioID, revision: probe.revision,
      enabled: probe.enabled, networkName: networkName, networkNameWasRead: networkNameWasRead,
      associated: associated, ipAddress: ipAddress, ipAddressWasRead: ipAddressWasRead, error: failure
    )
  }

  func readRadioWiFiCredentials(
    editorID: UUID, expectedContextID: UUID? = nil
  ) async throws -> RadioWiFiCredentialBaseline? {
    try Task.checkCancellation()
    let binding = radioWiFiBinding
    let capturedSession = session
    guard !radioWiFiOperationsRetired,
          expectedContextID == nil || expectedContextID == binding.contextID else { return nil }
    radioWiFiEditorID = editorID
    radioWiFiEditorRead = nil
    guard let revision = await binding.state.stableRevision(),
          await radioWiFiReadIsCurrent(revision: revision),
          radioWiFiEditorID == editorID else { return nil }
    try Task.checkCancellation()
    let ssid = try await getRadioCLIValue(RadioCLIKey.wifiSSID, session: capturedSession)
    try Task.checkCancellation()
    guard radioWiFiEditorID == editorID,
          await radioWiFiReadIsCurrent(revision: revision) else { return nil }
    let password = try await getRadioCLIValue(RadioCLIKey.wifiPassword, session: capturedSession)
    try Task.checkCancellation()
    guard radioWiFiEditorID == editorID,
          await radioWiFiReadIsCurrent(revision: revision) else { return nil }
    guard let ssid, let password else { throw SettingsServiceError.invalidResponse }
    let readID = UUID()
    radioWiFiEditorRead = (id: readID, revision: revision)
    return RadioWiFiCredentialBaseline(
      contextID: binding.contextID, radioID: binding.state.radioID,
      editorID: editorID, revision: revision, ssid: Self.radioWiFiNetworkName(ssid) ?? "",
      password: password, readID: readID
    )
  }

  func closeRadioWiFiEditor(editorID: UUID) {
    if radioWiFiEditorID == editorID {
      radioWiFiEditorID = nil
      radioWiFiEditorRead = nil
    }
  }

  internal func radioWiFiReadIsCurrent(revision: UInt64) async -> Bool {
    guard !radioWiFiOperationsRetired else { return false }
    let currentRevision = await radioWiFiState.stableRevision()
    return !radioWiFiOperationsRetired && currentRevision == revision
  }

  internal static func radioWiFiNetworkName(_ value: String) -> String? {
    value.isEmpty || value == RadioCLIReply.notSet ? nil : value
  }
}
