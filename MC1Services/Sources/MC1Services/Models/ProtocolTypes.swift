/// Protocol types that add iOS-specific value over MeshCore.
///
/// MeshCore provides protocol-level types for parsing and command building.
/// This file provides:
/// - Semantic enums with helper methods (ContactType, TextType, etc.)
/// - ContactFrame for creating contacts (vs MeshContact which is parsed)
/// - Protocol constants and limits
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
public typealias ContactFlags = MeshCore.ContactFlags

// MARK: - Contact Type UI Extensions

extension ContactType {
    /// Human-readable name for display in UI
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

// MARK: - Contact Frame (for creating contacts)

/// Contact information frame from device
public struct ContactFrame: Sendable, Equatable {
    public let publicKey: Data
    public let type: ContactType
    public let flags: UInt8
    public let outPathLength: UInt8
    public let outPath: Data
    public let name: String
    public let lastAdvertTimestamp: UInt32
    public let latitude: Double
    public let longitude: Double
    public let lastModified: UInt32

    public init(
        publicKey: Data,
        type: ContactType,
        flags: UInt8,
        outPathLength: UInt8,
        outPath: Data,
        name: String,
        lastAdvertTimestamp: UInt32,
        latitude: Double,
        longitude: Double,
        lastModified: UInt32
    ) {
        self.publicKey = publicKey
        self.type = type
        self.flags = flags
        self.outPathLength = outPathLength
        self.outPath = outPath
        self.name = name
        self.lastAdvertTimestamp = lastAdvertTimestamp
        self.latitude = latitude
        self.longitude = longitude
        self.lastModified = lastModified
    }
}

// MARK: - Protocol Constants

/// Error codes returned by the device
public enum ProtocolError: UInt8, Sendable, Error {
    case unsupportedCommand = 0x01
    case notFound = 0x02
    case tableFull = 0x03
    case badState = 0x04
    case fileIOError = 0x05
    case illegalArgument = 0x06
}

extension ProtocolError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unsupportedCommand: "Command not supported by device firmware."
        case .notFound: "Item not found on device."
        case .tableFull: "Device storage is full."
        case .badState: "Device is in an invalid state for this operation."
        case .fileIOError: "Device file system error."
        case .illegalArgument: "Invalid parameter sent to device."
        }
    }
}

/// Protocol size limits and constants
public enum ProtocolLimits {
    public static let publicKeySize = 32
    public static let privateKeySize = 64
    public static let maxPathSize = 64
    public static let maxFrameSize = 172
    public static let signatureSize = 64
    public static let maxPacketPayload = 184
    public static let cipherMacSize = 2
    public static let maxHashSize = 8
    public static let cipherKeySize = 16
    public static let maxContacts = 100
    public static let offlineQueueSize = 16
    public static let maxNameLength = 32
    public static let channelSecretSize = 16
    public static let maxMessageLength = 160

    /// Maximum usable bytes for names (firmware char[32] minus null terminator)
    public static let maxUsableNameBytes = 31

    /// Maximum UTF-8 bytes for the default flood scope name field. The firmware field is 31
    /// bytes with zero padding and accepts `0 < strlen(name) < 31`, so the effective cap is
    /// 30. Longer inputs must be truncated before send; otherwise the stored display and the
    /// full-name-derived scope key would disagree.
    public static let maxDefaultFloodScopeNameBytes = 30

    /// Maximum bytes for direct messages (app-enforced limit per MeshCore spec)
    public static let maxDirectMessageLength = 150

    /// Total limit for channel messages including "NodeName: " prefix
    public static let maxChannelMessageTotalLength = 147

    /// Max user text bytes for channel messages, accounting for node name prefix
    public static func maxChannelMessageLength(nodeNameByteCount: Int) -> Int {
        max(0, maxChannelMessageTotalLength - nodeNameByteCount - 2)
    }
}

// MARK: - Channel Message Format

/// Utilities for parsing the "NodeName: MessageText" format used in channel messages.
/// The firmware prepends the sender's node name before encryption.
public enum ChannelMessageFormat {
    /// Parses "NodeName: MessageText" format from decrypted channel messages.
    /// - Parameter text: The full decrypted channel message text
    /// - Returns: Tuple of (senderName, messageText) or nil if format doesn't match
    public static func parse(_ text: String) -> (senderName: String, messageText: String)? {
        guard let colonIndex = text.firstIndex(of: ":"),
              colonIndex != text.startIndex else {
            return nil
        }

        let senderName = String(text[..<colonIndex])
        let afterColon = text.index(after: colonIndex)

        guard afterColon < text.endIndex else {
            return (senderName, "")
        }

        let messageText = String(text[afterColon...]).trimmingCharacters(in: .whitespaces)
        return (senderName, messageText)
    }
}
