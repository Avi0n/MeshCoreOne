import Foundation
import MeshCore

/// Store operations for channel rows, channel unread state, and channel mention tracking.
public protocol ChannelPersisting: Actor {

    // MARK: - Channel Operations

    /// Fetch all channels for a device
    func fetchChannels(radioID: UUID) async throws -> [ChannelDTO]

    /// Fetch a channel by index
    func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO?

    /// Fetch a channel by ID
    func fetchChannel(id: UUID) async throws -> ChannelDTO?

    /// Save or update a channel from ChannelInfo
    @discardableResult
    func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID

    /// Save or update a channel from DTO
    func saveChannel(_ dto: ChannelDTO) async throws

    /// Persists a full channel-sync pass in a single transaction: upserts each configured
    /// `ChannelInfo` (matched by `(radioID, index)`), deletes stale local rows at
    /// `unconfiguredIndices`, and (when `pruneBeyond` is non-nil) deletes orphaned rows
    /// whose index is `>= pruneBeyond`. Rows at indices that are neither configured nor
    /// unconfigured (e.g. skipped by the circuit breaker) are left untouched. Returns all
    /// channels for the radio after the write, sorted by index.
    func batchSaveChannels(
        radioID: UUID,
        configured: [ChannelInfo],
        unconfiguredIndices: [UInt8],
        pruneBeyond maxChannels: UInt8?
    ) async throws -> [ChannelDTO]

    /// Delete a channel
    func deleteChannel(id: UUID) async throws

    /// Delete all messages for a channel
    func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws

    /// Update channel's last message info (nil clears the date)
    func updateChannelLastMessage(channelID: UUID, date: Date?) async throws

    /// Increment unread count for a channel
    func incrementChannelUnreadCount(channelID: UUID) async throws

    /// Clear unread count for a channel
    func clearChannelUnreadCount(channelID: UUID) async throws

    /// Clear unread count for a channel by radioID and index
    /// More efficient than fetching the full channel DTO when only clearing unread
    func clearChannelUnreadCount(radioID: UUID, index: UInt8) async throws

    /// Sets the notification level for a channel
    func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws

    /// Sets the notification level for a remote node session
    func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws

    // MARK: - Channel Mention Tracking

    /// Increment unread mention count for a channel
    func incrementChannelUnreadMentionCount(channelID: UUID) async throws

    /// Decrement unread mention count for a channel
    func decrementChannelUnreadMentionCount(channelID: UUID) async throws

    /// Clear unread mention count for a channel
    func clearChannelUnreadMentionCount(channelID: UUID) async throws

    /// Fetch unseen mention message IDs for a channel, ordered oldest-first
    func fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UInt8) async throws -> [UUID]
}

// MARK: - Default Parameter Values

public extension ChannelPersisting {
    /// Default channel-sync persistence built from the per-item operations. The concrete
    /// `PersistenceStore` overrides this with a single-transaction implementation; this
    /// fallback keeps lightweight test stubs conforming without their own batch logic.
    func batchSaveChannels(
        radioID: UUID,
        configured: [ChannelInfo],
        unconfiguredIndices: [UInt8],
        pruneBeyond maxChannels: UInt8?
    ) async throws -> [ChannelDTO] {
        for info in configured {
            _ = try await saveChannel(radioID: radioID, from: info)
        }
        for index in unconfiguredIndices {
            if let stale = try await fetchChannel(radioID: radioID, index: index) {
                try await deleteChannel(id: stale.id)
            }
        }
        if let maxChannels {
            for channel in try await fetchChannels(radioID: radioID) where channel.index >= maxChannels {
                try await deleteChannel(id: channel.id)
            }
        }
        return try await fetchChannels(radioID: radioID)
    }
}
