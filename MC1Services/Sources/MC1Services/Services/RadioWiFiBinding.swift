import Foundation

/// An immutable certificate namespace for one SettingsService WiFi context.
public struct RadioWiFiBinding: Sendable {
  public let contextID: UUID
  public let state: RadioWiFiState

  init(state: RadioWiFiState) {
    contextID = UUID()
    self.state = state
  }
}
