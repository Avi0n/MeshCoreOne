import Foundation
import MeshCore

/// Protocol for ChannelService to enable testability of SyncCoordinator.
///
/// This protocol abstracts the channel sync operations used by SyncCoordinator,
/// allowing it to be tested with mock implementations.
///
/// ## Usage
///
/// Services can accept this protocol type for dependency injection:
/// ```swift
/// actor MyCoordinator {
///     private let channelService: any ChannelServiceProtocol
///
///     init(channelService: any ChannelServiceProtocol) {
///         self.channelService = channelService
///     }
/// }
/// ```
public protocol ChannelServiceProtocol: Actor {

    // MARK: - Channel Sync

    /// Fetches all channels for a device from the remote device.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - maxChannels: Maximum number of channels to fetch (from device capacity)
    ///   - usePipelinedRead: When `true`, reads channels via the bounded-window pipeline
    ///     (nRF52 over BLE, ESP32 over WiFi); when `false`, uses the serial acknowledged path.
    /// - Returns: Sync result with number of channels synced
    func syncChannels(radioID: UUID, maxChannels: UInt8, usePipelinedRead: Bool) async throws -> ChannelSyncResult

    /// Retries syncing only the channels that previously failed.
    /// - Parameters:
    ///   - radioID: The device UUID
    ///   - indices: Channel indices to retry
    /// - Returns: Sync result for the retried channels
    func retryFailedChannels(radioID: UUID, indices: [UInt8]) async throws -> ChannelSyncResult
}

public extension ChannelServiceProtocol {
    /// Convenience that defaults to the serial acknowledged read path. A default argument on
    /// the protocol requirement itself is illegal and ignored by witness matching, so the
    /// 2-arg form lives here while conformers implement only the 3-arg requirement.
    func syncChannels(radioID: UUID, maxChannels: UInt8) async throws -> ChannelSyncResult {
        try await syncChannels(radioID: radioID, maxChannels: maxChannels, usePipelinedRead: false)
    }
}
