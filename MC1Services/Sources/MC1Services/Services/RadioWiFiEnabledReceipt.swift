import Foundation

/// A typed enabled change carries the settlement of its captured WiFi context.
public struct RadioWiFiEnabledReceipt: Sendable {
  public let contextID: UUID
  public let revision: UInt64?
  public let outcome: RadioWiFiState.MutationOutcome
  public let error: (any Error)?
}
