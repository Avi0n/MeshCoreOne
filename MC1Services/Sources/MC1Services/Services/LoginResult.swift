import Foundation

public struct LoginResult: Sendable {
  public let success: Bool
  public let isAdmin: Bool
  public let aclPermissions: UInt8?
  public let publicKeyPrefix: Data
  /// The remote node's RTC reading from the login response, if carried.
  public let serverTime: Date?
  /// The remote node's `FIRMWARE_VER_LEVEL`, if the response carried it.
  public let firmwareLevel: UInt8?

  public init(
    success: Bool,
    isAdmin: Bool,
    aclPermissions: UInt8?,
    publicKeyPrefix: Data,
    serverTime: Date? = nil,
    firmwareLevel: UInt8? = nil
  ) {
    self.success = success
    self.isAdmin = isAdmin
    self.aclPermissions = aclPermissions
    self.publicKeyPrefix = publicKeyPrefix
    self.serverTime = serverTime
    self.firmwareLevel = firmwareLevel
  }

  public func permissionLevel(for role: RemoteNodeRole) -> RoomPermissionLevel {
    role == .sensor ? (isAdmin ? .admin : .guest) : permissionLevel
  }

  public var permissionLevel: RoomPermissionLevel {
    isAdmin ? .admin : (RoomPermissionLevel(rawValue: aclPermissions ?? 0) ?? .guest)
  }
}
