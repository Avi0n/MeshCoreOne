import Foundation

// MARK: - Channel Sync Configuration

extension DevicePlatform {

    /// If channels were synced more recently than this, skip channel re-sync on resync.
    /// Only enabled for ESP32 where channel re-sync wastes scarce connection time.
    /// Channel skipping is a correctness tradeoff (not just performance), so it stays
    /// disabled for unknown platforms until field evidence warrants it.
    var channelSyncSkipWindow: Duration {
        switch self {
        case .esp32: .seconds(30)
        case .nrf52, .unknown: .zero
        }
    }

    /// Builds a channel sync config for a sync operation.
    func channelSyncConfig(lastCleanChannelSync: Date?, lastAttemptedChannelSync: Date? = nil) -> ChannelSyncConfig {
        ChannelSyncConfig(
            channelSyncSkipWindow: channelSyncSkipWindow,
            lastCleanChannelSync: lastCleanChannelSync,
            lastAttemptedChannelSync: lastAttemptedChannelSync
        )
    }
}
