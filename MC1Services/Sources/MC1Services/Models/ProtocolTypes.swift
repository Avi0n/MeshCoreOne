/// Semantic protocol enums that add iOS-specific value over MeshCore
/// (TextType, RemoteNodeRole, RoomPermissionLevel) plus ContactType re-exports.
///
/// Types that are direct duplicates of MeshCore types have been removed.
/// Use MeshCore types directly: SelfInfo, DeviceCapabilities, ChannelInfo,
/// ContactMessage, ChannelMessage, MessageSentInfo, BatteryInfo,
/// ContactType, ContactFlags.

import Foundation
import MeshCore

// MARK: - Contact Type Re-exports

/// Re-exported from MeshCore for backward compatibility. MeshContact now uses these
/// types directly, eliminating the need for rawValue conversions at the boundary.
public typealias ContactType = MeshCore.ContactType
typealias ContactFlags = MeshCore.ContactFlags

// MARK: - Contact Type UI Extensions

extension ContactType {
    /// Developer-facing English name for logs; UI uses the app target's localized `ContactType.localizedName`.
    public var displayName: String {
        switch self {
        case .chat: return "Contact"
        case .repeater: return "Repeater"
        case .room: return "Room"
        }
    }
}

// MARK: - Text Types

/// Message text type encoding
public enum TextType: UInt8, Sendable, Codable {
    case plain = 0x00
    case cliData = 0x01
    case signedPlain = 0x02
}

// MARK: - Remote Node Types

/// Discriminates between remote node types for role-specific handling
public enum RemoteNodeRole: UInt8, Sendable, Codable {
    case repeater = 0x02
    case roomServer = 0x03

    /// Initialize from ContactType
    public init?(contactType: ContactType) {
        switch contactType {
        case .repeater: self = .repeater
        case .room: self = .roomServer
        case .chat: return nil
        }
    }
}

/// Permission levels for room server access
public enum RoomPermissionLevel: UInt8, Sendable, Comparable, Codable {
    case guest = 0x00
    case readWrite = 0x01
    case admin = 0x02

    public var canPost: Bool { self >= .readWrite }
    public var isAdmin: Bool { self == .admin }

    /// Developer-facing English name for logs; UI uses the app target's localized `RoomPermissionLevel.localizedName`.
    public var displayName: String {
        switch self {
        case .guest: return "Guest"
        case .readWrite: return "Member"
        case .admin: return "Admin"
        }
    }

    public static func < (lhs: RoomPermissionLevel, rhs: RoomPermissionLevel) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}
