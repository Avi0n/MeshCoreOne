import Foundation

/// Controls whether channel re-sync is skipped on resync.
/// Computed from `DevicePlatform` and `lastCleanChannelSync` state at sync start.
public struct ChannelSyncConfig: Sendable {
    /// If channels were synced more recently than this window, skip channel re-sync.
    public let channelSyncSkipWindow: Duration

    /// Timestamp of the last fully-clean channel sync for the current device.
    public let lastCleanChannelSync: Date?

    /// Timestamp of the last attempted channel sync, even if it was partial.
    public let lastAttemptedChannelSync: Date?

    /// Whether channel reads should use the windowed read pipeline — nRF52 over BLE (Write
    /// Commands) or ESP32 over WiFi (TCP back-to-back sends). `false` for ESP32 over BLE.
    public let usePipelinedChannelRead: Bool

    public init(
        channelSyncSkipWindow: Duration = .zero,
        lastCleanChannelSync: Date? = nil,
        lastAttemptedChannelSync: Date? = nil,
        usePipelinedChannelRead: Bool = false
    ) {
        self.channelSyncSkipWindow = channelSyncSkipWindow
        self.lastCleanChannelSync = lastCleanChannelSync
        self.lastAttemptedChannelSync = lastAttemptedChannelSync
        self.usePipelinedChannelRead = usePipelinedChannelRead
    }

    /// No skip — used for WiFi connections.
    public static let none = ChannelSyncConfig()
}
