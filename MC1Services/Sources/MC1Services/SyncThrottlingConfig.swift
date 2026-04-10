import Foundation

/// Controls whether channel re-sync is skipped on resync.
/// Computed from `DevicePlatform` and `lastCleanChannelSync` state at sync start.
public struct ChannelSyncConfig: Sendable {
    /// If channels were synced more recently than this window, skip channel re-sync.
    public let channelSyncSkipWindow: Duration

    /// Timestamp of the last fully-clean channel sync for the current device.
    public let lastCleanChannelSync: Date?

    public init(
        channelSyncSkipWindow: Duration = .zero,
        lastCleanChannelSync: Date? = nil
    ) {
        self.channelSyncSkipWindow = channelSyncSkipWindow
        self.lastCleanChannelSync = lastCleanChannelSync
    }

    /// No skip — used for WiFi connections.
    public static let none = ChannelSyncConfig()
}
