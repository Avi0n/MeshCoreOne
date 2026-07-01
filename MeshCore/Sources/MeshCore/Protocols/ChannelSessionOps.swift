import Foundation

/// Session operations for reading and configuring channel slots.
public protocol ChannelSessionOps: Actor {
  /// Retrieves information about a channel.
  ///
  /// - Parameter index: The channel index (0-7).
  /// - Returns: A `ChannelInfo` object including the name and secret.
  /// - Throws: `MeshCoreError` if the channel query fails.
  func getChannel(index: UInt8) async throws -> ChannelInfo

  /// Reads multiple channels in a single pipelined exchange where the transport supports it.
  ///
  /// - Parameter indices: The channel indexes to read.
  /// - Returns: `received` channels that answered, and the `missing` indexes whose request
  ///   was dropped and must be reconciled with acknowledged reads.
  /// - Throws: `MeshCoreError` on a hard send failure.
  func getChannels(indices: [UInt8]) async throws -> (received: [ChannelInfo], missing: [UInt8])

  /// Configures a channel's settings.
  ///
  /// - Parameters:
  ///   - index: The channel index (0-7).
  ///   - name: The channel name.
  ///   - secret: The 16-byte channel secret.
  /// - Throws: `MeshCoreError` if the channel configuration fails.
  func setChannel(index: UInt8, name: String, secret: Data) async throws
}
