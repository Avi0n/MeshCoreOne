import Foundation

public struct LoginResult: Sendable {
  public let success: Bool
  public let isAdmin: Bool
  public let aclPermissions: UInt8?
  public let publicKeyPrefix: Data

  public init(success: Bool, isAdmin: Bool, aclPermissions: UInt8?, publicKeyPrefix: Data) {
    self.success = success
    self.isAdmin = isAdmin
    self.aclPermissions = aclPermissions
    self.publicKeyPrefix = publicKeyPrefix
  }

  public var permissionLevel: RoomPermissionLevel {
    isAdmin ? .admin : (RoomPermissionLevel(rawValue: aclPermissions ?? 0) ?? .guest)
  }
}
