import Foundation

/// Acknowledged effects are kept even when a later credential command fails.
public struct RadioWiFiMutationReceipt: Sendable {
  public let contextID: UUID
  public let ssidAccepted: Bool
  public let passwordAccepted: Bool
  public let revision: UInt64?
  public let uncertain: Bool
  public let error: (any Error)?
  /// Kept only by the current editor; never emitted as a settings event.
  public let updatedBaseline: RadioWiFiCredentialBaseline?
}
