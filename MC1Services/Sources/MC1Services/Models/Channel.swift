import Foundation
import SwiftData

/// Represents a channel (group) for broadcast messaging.
/// Max number of channels depends on the device, with slot 0 being the public channel.
@Model
public final class Channel {
    #Index<Channel>(
        [\.radioID],
        [\.radioID, \.index]
    )

    /// Unique identifier
    @Attribute(.unique)
    public var id: UUID

    /// The device this channel belongs to
    @Attribute(originalName: "deviceID")
    public var radioID: UUID

    /// Channel slot index
    public var index: UInt8

    /// Channel name
    public var name: String

    /// Channel secret (16 bytes, SHA-256 hashed from passphrase)
    public var secret: Data

    /// Whether this channel is enabled/active
    public var isEnabled: Bool

    /// Last message timestamp for this channel
    public var lastMessageDate: Date?

    /// Unread message count
    public var unreadCount: Int

    /// Unread mention count (mentions of current user not yet seen)
    public var unreadMentionCount: Int = 0

    /// Notification level for this channel (stored as raw value for SwiftData).
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

    /// Whether this channel is marked as favorite
    public var isFavorite: Bool = false

    /// Region code this channel is scoped to (nil = no region filter)
    public var regionScope: String?

    public init(
        id: UUID = UUID(),
        radioID: UUID,
        index: UInt8,
        name: String,
        secret: Data = Data(repeating: 0, count: 16),
        isEnabled: Bool = true,
        lastMessageDate: Date? = nil,
        unreadCount: Int = 0,
        unreadMentionCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false,
        regionScope: String? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.index = index
        self.name = name
        self.secret = secret
        self.isEnabled = isEnabled
        self.lastMessageDate = lastMessageDate
        self.unreadCount = unreadCount
        self.unreadMentionCount = unreadMentionCount
        self.notificationLevelRawValue = notificationLevel.rawValue
        self.isFavorite = isFavorite
        self.regionScope = regionScope
    }

    /// Builds a model instance directly from a DTO. Shared by `saveChannel` and
    /// backup batch-insert paths so they can't drift on field coverage.
    public convenience init(dto: ChannelDTO) {
        self.init(
            id: dto.id,
            radioID: dto.radioID,
            index: dto.index,
            name: dto.name,
            secret: dto.secret,
            isEnabled: dto.isEnabled,
            lastMessageDate: dto.lastMessageDate,
            unreadCount: dto.unreadCount,
            unreadMentionCount: dto.unreadMentionCount,
            notificationLevel: dto.notificationLevel,
            isFavorite: dto.isFavorite,
            regionScope: dto.regionScope
        )
    }

    /// Applies all mutable fields from a DTO to this model instance.
    func apply(_ dto: ChannelDTO) {
        name = dto.name
        secret = dto.secret
        isEnabled = dto.isEnabled
        lastMessageDate = dto.lastMessageDate
        unreadCount = dto.unreadCount
        unreadMentionCount = dto.unreadMentionCount
        notificationLevel = dto.notificationLevel
        isFavorite = dto.isFavorite
        regionScope = dto.regionScope
    }

    /// Creates a Channel from a protocol ChannelInfo
    public convenience init(radioID: UUID, from info: ChannelInfo) {
        self.init(
            radioID: radioID,
            index: info.index,
            name: info.name,
            secret: info.secret
        )
    }
}

// MARK: - Computed Properties

public extension Channel {
    /// Whether this is the public channel (slot 0)
    var isPublicChannel: Bool {
        index == 0
    }

    /// Whether this channel has a non-empty secret
    var hasSecret: Bool {
        !secret.allSatisfy { $0 == 0 }
    }

    /// Whether this channel uses meaningful encryption (private channels only).
    /// Public channels (index 0) and hashtag channels use publicly-derivable keys.
    var isEncryptedChannel: Bool {
        !isPublicChannel && !name.hasPrefix("#")
    }

    /// Updates from a protocol ChannelInfo
    func update(from info: ChannelInfo) {
        self.name = info.name
        self.secret = info.secret
    }

    /// Converts to a protocol ChannelInfo
    func toChannelInfo() -> ChannelInfo {
        ChannelInfo(index: index, name: name, secret: secret)
    }
}

// MARK: - Sendable DTO

/// A sendable snapshot of Channel for cross-actor transfers
public struct ChannelDTO: Sendable, Equatable, Identifiable, Hashable, Codable {
    public let id: UUID
    public var radioID: UUID
    public let index: UInt8
    public let name: String
    public let secret: Data
    public let isEnabled: Bool
    public let lastMessageDate: Date?
    public let unreadCount: Int
    public let unreadMentionCount: Int
    public let notificationLevel: NotificationLevel
    public let isFavorite: Bool
    public let regionScope: String?

    /// Convenience property for checking if muted
    public var isMuted: Bool { notificationLevel == .muted }

    public init(from channel: Channel) {
        self.id = channel.id
        self.radioID = channel.radioID
        self.index = channel.index
        self.name = channel.name
        self.secret = channel.secret
        self.isEnabled = channel.isEnabled
        self.lastMessageDate = channel.lastMessageDate
        self.unreadCount = channel.unreadCount
        self.unreadMentionCount = channel.unreadMentionCount
        self.notificationLevel = channel.notificationLevel
        self.isFavorite = channel.isFavorite
        self.regionScope = channel.regionScope
    }

    /// Memberwise initializer for creating DTOs directly
    public init(
        id: UUID,
        radioID: UUID,
        index: UInt8,
        name: String,
        secret: Data,
        isEnabled: Bool,
        lastMessageDate: Date?,
        unreadCount: Int,
        unreadMentionCount: Int = 0,
        notificationLevel: NotificationLevel = .all,
        isFavorite: Bool = false,
        regionScope: String? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.index = index
        self.name = name
        self.secret = secret
        self.isEnabled = isEnabled
        self.lastMessageDate = lastMessageDate
        self.unreadCount = unreadCount
        self.unreadMentionCount = unreadMentionCount
        self.notificationLevel = notificationLevel
        self.isFavorite = isFavorite
        self.regionScope = regionScope
    }

    /// Returns a copy with only `notificationLevel` changed.
    public func with(notificationLevel: NotificationLevel) -> ChannelDTO {
        ChannelDTO(
            id: id, radioID: radioID, index: index, name: name,
            secret: secret, isEnabled: isEnabled, lastMessageDate: lastMessageDate,
            unreadCount: unreadCount, unreadMentionCount: unreadMentionCount,
            notificationLevel: notificationLevel, isFavorite: isFavorite,
            regionScope: regionScope
        )
    }

    /// Returns a copy with only `isFavorite` changed.
    public func with(isFavorite: Bool) -> ChannelDTO {
        ChannelDTO(
            id: id, radioID: radioID, index: index, name: name,
            secret: secret, isEnabled: isEnabled, lastMessageDate: lastMessageDate,
            unreadCount: unreadCount, unreadMentionCount: unreadMentionCount,
            notificationLevel: notificationLevel, isFavorite: isFavorite,
            regionScope: regionScope
        )
    }

    /// Returns a copy with only `regionScope` changed.
    public func with(regionScope: String?) -> ChannelDTO {
        ChannelDTO(
            id: id, radioID: radioID, index: index, name: name,
            secret: secret, isEnabled: isEnabled, lastMessageDate: lastMessageDate,
            unreadCount: unreadCount, unreadMentionCount: unreadMentionCount,
            notificationLevel: notificationLevel, isFavorite: isFavorite,
            regionScope: regionScope
        )
    }

    public var isPublicChannel: Bool {
        index == 0
    }

    public var hasSecret: Bool {
        !secret.allSatisfy { $0 == 0 }
    }

    /// Whether this channel uses meaningful encryption (private channels only).
    /// Public channels (index 0) and hashtag channels use publicly-derivable keys.
    public var isEncryptedChannel: Bool {
        !isPublicChannel && !name.hasPrefix("#")
    }
}
