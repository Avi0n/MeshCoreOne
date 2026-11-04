import Foundation

/// A captured identity-reapplication choice, without private-key material.
public struct IdentityImportRecovery: Equatable, Sendable {
  public let receiptID: UUID
  public let originalDeviceID: UUID
  public let originalRadioID: UUID
  public let sourceName: String
  public let capturedConnectionMethod: ConnectionMethod
  /// Equality includes the request so a newer connection invalidates a prepared confirmation.
  public let currentRequestID: UUID?
}
