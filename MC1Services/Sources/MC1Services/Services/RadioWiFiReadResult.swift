import Foundation

/// Confirmed nonsecret fields survive a later failed CLI read in the same context.
public struct RadioWiFiReadResult: Sendable {
  public let contextID: UUID
  public let radioID: UUID
  public let revision: UInt64
  public let enabled: Bool
  public let networkName: String?
  public let networkNameWasRead: Bool
  public let associated: Bool?
  public let ipAddress: String?
  public let ipAddressWasRead: Bool
  public let error: (any Error)?
}
