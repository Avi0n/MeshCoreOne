import Foundation

public struct LoginResult: Sendable {
  public let success: Bool
  public let isAdmin: Bool
  public let aclPermissions: UInt8?
  public let publicKeyPrefix: Data
  /// The remote node's RTC reading from the login response, if carried.
  public let serverTime: Date?

  public init(
    success: Bool,
    isAdmin: Bool,
    aclPermissions: UInt8?,
    publicKeyPrefix: Data,
    serverTime: Date? = nil
  ) {
    self.success = success
    self.isAdmin = isAdmin
    self.aclPermissions = aclPermissions
    self.publicKeyPrefix = publicKeyPrefix
    self.serverTime = serverTime
  }

  public var permissionLevel: RoomPermissionLevel {
    isAdmin ? .admin : (RoomPermissionLevel(rawValue: aclPermissions ?? 0) ?? .guest)
  }
}
