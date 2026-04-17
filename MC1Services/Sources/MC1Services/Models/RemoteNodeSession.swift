import Foundation
import SwiftData

/// Represents an authenticated session with a remote node.
/// Used for both room servers and repeater admin connections.
@Model
public final class RemoteNodeSession {
    #Index<RemoteNodeSession>([\.radioID])

    /// Unique session identifier
    @Attribute(.unique)
    public var id: UUID

    /// The companion radio used to access this node
    @Attribute(originalName: "deviceID")
    public var radioID: UUID

    /// 32-byte remote node's public key
    public var publicKey: Data

    /// Human-readable node name
    public var name: String

    /// Raw value of RemoteNodeRole
    public var roleRawValue: UInt8

    /// Node latitude (non-optional, consistent with Device)
    public var latitude: Double

    /// Node longitude
    public var longitude: Double

    /// Whether currently connected/authenticated
    public var isConnected: Bool

    /// Permission level raw value (RoomPermissionLevel)
    public var permissionLevelRawValue: UInt8

    /// Last successful connection date
    public var lastConnectedDate: Date?

    /// Cached battery level from last status
    public var lastBatteryMillivolts: UInt16?

    /// Cached uptime from last status
    public var lastUptimeSeconds: UInt32?

    /// Cached noise floor from last status
    public var lastNoiseFloor: Int16?

    /// Unread message count (room-specific)
    public var unreadCount: Int

    /// Notification level for this room (stored as raw value for SwiftData).
    /// Default is -1 (unmigrated) to enable migration from legacy isMuted property.
    public var notificationLevelRawValue: Int = -1

    /// Legacy isMuted property from V1 schema (maps to old "isMuted" column).
    /// Used for one-time migration to notificationLevelRawValue.
    @Attribute(originalName: "isMuted")
    public var legacyIsMuted: Bool?

    /// Notification level computed property with automatic migration from legacy isMuted
    public var notificationLevel: NotificationLevel {
        get {
            // Check if migration is needed
            if notificationLevelRawValue == -1 {
                // Migrate from legacy isMuted
                let migratedLevel: NotificationLevel = (legacyIsMuted == true) ? .muted : .all
                notificationLevelRawValue = migratedLevel.rawValue
                return migratedLevel
            }
            return NotificationLevel(rawValue: notificationLevelRawValue) ?? .all
        }
        set { notificationLevelRawValue = newValue.rawValue }
    }

    /// Whether this session/node is marked as favorite
    public var isFavorite: Bool = false

    /// Last RX airtime in seconds (repeater-specific)
    public var lastRxAirtimeSeconds: UInt32?

    /// Number of neighbors (repeater-specific)
    public var neighborCount: Int

    /// Timestamp of the last message received from this room.
    /// Used to request only newer messages on reconnect.
    /// Value of 0 means no messages synced yet (request all).
    public var lastSyncTimestamp: UInt32

    /// Device-local date of last message activity (send or receive).
    /// Used for sorting in the chat list. Separate from lastSyncTimestamp
    /// which tracks the sender's clock for sync purposes.
    public var lastMessageDate: Date?

    public init(
        id: UUID = UUID(),
        radioID: UUID,
        publicKey: Data,
        name: String,
        role: RemoteNodeRole,
        latitude: Double = 0,
        longitude: Double = 0,
        isConnected: Bool = false,
        permissionLevel: RoomPermissionLevel = .guest,
        lastConnectedDate: Date? = nil,
        lastBatteryMillivolts: UInt16? = nil,
        lastUptimeSeconds: UInt32? = nil,
        lastNoiseFloor: Int16? = nil,
        unreadCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false,
        lastRxAirtimeSeconds: UInt32? = nil,
        neighborCount: Int = 0,
        lastSyncTimestamp: UInt32 = 0,
        lastMessageDate: Date? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.publicKey = publicKey
        self.name = name
        self.roleRawValue = role.rawValue
        self.latitude = latitude
        self.longitude = longitude
        self.isConnected = isConnected
        self.permissionLevelRawValue = permissionLevel.rawValue
        self.lastConnectedDate = lastConnectedDate
        self.lastBatteryMillivolts = lastBatteryMillivolts
        self.lastUptimeSeconds = lastUptimeSeconds
        self.lastNoiseFloor = lastNoiseFloor
        self.unreadCount = unreadCount
        self.notificationLevelRawValue = notificationLevel.rawValue
        self.isFavorite = isFavorite
        self.lastRxAirtimeSeconds = lastRxAirtimeSeconds
        self.neighborCount = neighborCount
        self.lastSyncTimestamp = lastSyncTimestamp
        self.lastMessageDate = lastMessageDate
    }

    /// Builds a model instance directly from a DTO. Shared by backup batch-insert
    /// paths so model and DTO can't drift on field coverage.
    public convenience init(dto: RemoteNodeSessionDTO) {
        self.init(
            id: dto.id,
            radioID: dto.radioID,
            publicKey: dto.publicKey,
            name: dto.name,
            role: dto.role,
            latitude: dto.latitude,
            longitude: dto.longitude,
            isConnected: dto.isConnected,
            permissionLevel: dto.permissionLevel,
            lastConnectedDate: dto.lastConnectedDate,
            lastBatteryMillivolts: dto.lastBatteryMillivolts,
            lastUptimeSeconds: dto.lastUptimeSeconds,
            lastNoiseFloor: dto.lastNoiseFloor,
            unreadCount: dto.unreadCount,
            notificationLevel: dto.notificationLevel,
            isFavorite: dto.isFavorite,
            lastRxAirtimeSeconds: dto.lastRxAirtimeSeconds,
            neighborCount: dto.neighborCount,
            lastSyncTimestamp: dto.lastSyncTimestamp,
            lastMessageDate: dto.lastMessageDate
        )
    }

    /// Applies all mutable fields from a DTO to this model instance.
    func apply(_ dto: RemoteNodeSessionDTO) {
        radioID = dto.radioID
        publicKey = dto.publicKey
        name = dto.name
        roleRawValue = dto.role.rawValue
        latitude = dto.latitude
        longitude = dto.longitude
        isConnected = dto.isConnected
        permissionLevelRawValue = dto.permissionLevel.rawValue
        lastConnectedDate = dto.lastConnectedDate
        lastBatteryMillivolts = dto.lastBatteryMillivolts
        lastUptimeSeconds = dto.lastUptimeSeconds
        lastNoiseFloor = dto.lastNoiseFloor
        unreadCount = dto.unreadCount
        notificationLevel = dto.notificationLevel
        isFavorite = dto.isFavorite
        lastRxAirtimeSeconds = dto.lastRxAirtimeSeconds
        neighborCount = dto.neighborCount
        lastSyncTimestamp = dto.lastSyncTimestamp
        lastMessageDate = dto.lastMessageDate
    }
}

// MARK: - Computed Properties

public extension RemoteNodeSession {
    /// The node role enum
    var role: RemoteNodeRole {
        RemoteNodeRole(rawValue: roleRawValue) ?? .repeater
    }

    /// The permission level enum
    var permissionLevel: RoomPermissionLevel {
        get { RoomPermissionLevel(rawValue: permissionLevelRawValue) ?? .guest }
        set { permissionLevelRawValue = newValue.rawValue }
    }

    /// Whether this is a room server session
    var isRoom: Bool { role == .roomServer }

    /// Whether this is a repeater session
    var isRepeater: Bool { role == .repeater }

    /// 6-byte public key prefix for addressing
    var publicKeyPrefix: Data { publicKey.prefix(6) }

    /// Hex string representation of full public key
    var publicKeyHex: String {
        publicKey.map { String(format: "%02X", $0) }.joined()
    }

    /// Whether user can post messages (room-specific)
    var canPost: Bool { isRoom && permissionLevel.canPost }

    /// Whether user has admin access
    var isAdmin: Bool { permissionLevel.isAdmin }
}

// MARK: - Sendable DTO

/// A sendable snapshot of RemoteNodeSession for cross-actor transfers
public struct RemoteNodeSessionDTO: Sendable, Equatable, Identifiable, Hashable, Codable {
    public let id: UUID
    public var radioID: UUID
    public let publicKey: Data
    public let name: String
    public let role: RemoteNodeRole
    public let latitude: Double
    public let longitude: Double
    public let isConnected: Bool
    public let permissionLevel: RoomPermissionLevel
    public let lastConnectedDate: Date?
    public let lastBatteryMillivolts: UInt16?
    public let lastUptimeSeconds: UInt32?
    public let lastNoiseFloor: Int16?
    public let unreadCount: Int
    public let notificationLevel: NotificationLevel
    public let isFavorite: Bool

    /// Convenience property for checking if muted
    public var isMuted: Bool { notificationLevel == .muted }
    public let lastRxAirtimeSeconds: UInt32?
    public let neighborCount: Int
    public let lastSyncTimestamp: UInt32
    public let lastMessageDate: Date?

    public init(from model: RemoteNodeSession) {
        self.id = model.id
        self.radioID = model.radioID
        self.publicKey = model.publicKey
        self.name = model.name
        self.role = model.role
        self.latitude = model.latitude
        self.longitude = model.longitude
        self.isConnected = model.isConnected
        self.permissionLevel = model.permissionLevel
        self.lastConnectedDate = model.lastConnectedDate
        self.lastBatteryMillivolts = model.lastBatteryMillivolts
        self.lastUptimeSeconds = model.lastUptimeSeconds
        self.lastNoiseFloor = model.lastNoiseFloor
        self.unreadCount = model.unreadCount
        self.notificationLevel = model.notificationLevel
        self.isFavorite = model.isFavorite
        self.lastRxAirtimeSeconds = model.lastRxAirtimeSeconds
        self.neighborCount = model.neighborCount
        self.lastSyncTimestamp = model.lastSyncTimestamp
        // Backward compat: fall back to sync timestamp for pre-migration data
        self.lastMessageDate = model.lastMessageDate
            ?? (model.lastSyncTimestamp > 0
                ? Date(timeIntervalSince1970: TimeInterval(model.lastSyncTimestamp))
                : nil)
    }

    /// Memberwise initializer for creating DTOs directly
    public init(
        id: UUID = UUID(),
        radioID: UUID,
        publicKey: Data,
        name: String,
        role: RemoteNodeRole,
        latitude: Double = 0,
        longitude: Double = 0,
        isConnected: Bool = false,
        permissionLevel: RoomPermissionLevel = .guest,
        lastConnectedDate: Date? = nil,
        lastBatteryMillivolts: UInt16? = nil,
        lastUptimeSeconds: UInt32? = nil,
        lastNoiseFloor: Int16? = nil,
        unreadCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false,
        lastRxAirtimeSeconds: UInt32? = nil,
        neighborCount: Int = 0,
        lastSyncTimestamp: UInt32 = 0,
        lastMessageDate: Date? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.publicKey = publicKey
        self.name = name
        self.role = role
        self.latitude = latitude
        self.longitude = longitude
        self.isConnected = isConnected
        self.permissionLevel = permissionLevel
        self.lastConnectedDate = lastConnectedDate
        self.lastBatteryMillivolts = lastBatteryMillivolts
        self.lastUptimeSeconds = lastUptimeSeconds
        self.lastNoiseFloor = lastNoiseFloor
        self.unreadCount = unreadCount
        self.notificationLevel = notificationLevel
        self.isFavorite = isFavorite
        self.lastRxAirtimeSeconds = lastRxAirtimeSeconds
        self.neighborCount = neighborCount
        self.lastSyncTimestamp = lastSyncTimestamp
        self.lastMessageDate = lastMessageDate
    }

    /// Returns a copy with only `notificationLevel` changed.
    public func with(notificationLevel: NotificationLevel) -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            id: id, radioID: radioID, publicKey: publicKey, name: name,
            role: role, latitude: latitude, longitude: longitude,
            isConnected: isConnected, permissionLevel: permissionLevel,
            lastConnectedDate: lastConnectedDate,
            lastBatteryMillivolts: lastBatteryMillivolts,
            lastUptimeSeconds: lastUptimeSeconds, lastNoiseFloor: lastNoiseFloor,
            unreadCount: unreadCount, notificationLevel: notificationLevel,
            isFavorite: isFavorite, lastRxAirtimeSeconds: lastRxAirtimeSeconds,
            neighborCount: neighborCount, lastSyncTimestamp: lastSyncTimestamp,
            lastMessageDate: lastMessageDate
        )
    }

    /// Returns a copy with only `isFavorite` changed.
    public func with(isFavorite: Bool) -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            id: id, radioID: radioID, publicKey: publicKey, name: name,
            role: role, latitude: latitude, longitude: longitude,
            isConnected: isConnected, permissionLevel: permissionLevel,
            lastConnectedDate: lastConnectedDate,
            lastBatteryMillivolts: lastBatteryMillivolts,
            lastUptimeSeconds: lastUptimeSeconds, lastNoiseFloor: lastNoiseFloor,
            unreadCount: unreadCount, notificationLevel: notificationLevel,
            isFavorite: isFavorite, lastRxAirtimeSeconds: lastRxAirtimeSeconds,
            neighborCount: neighborCount, lastSyncTimestamp: lastSyncTimestamp,
            lastMessageDate: lastMessageDate
        )
    }

    public var publicKeyPrefix: Data { publicKey.prefix(6) }

    public var publicKeyHex: String {
        publicKey.map { String(format: "%02X", $0) }.joined()
    }

    public var isRoom: Bool { role == .roomServer }

    public var isRepeater: Bool { role == .repeater }

    public var canPost: Bool { isRoom && permissionLevel.canPost }

    public var isAdmin: Bool { permissionLevel.isAdmin }
}
