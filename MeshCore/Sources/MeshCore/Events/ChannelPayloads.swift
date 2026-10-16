import Foundation

/// Represents the device's persisted default flood scope.
///
/// Firmware v11+ (MeshCore v1.15.0+). The default scope is applied automatically
/// when sending flood packets if no session-scoped key has been set.
public struct DefaultFloodScope: Sendable, Equatable {
  /// Display name (up to 30 UTF-8 bytes on-device).
  public let name: String
  /// The 16-byte scope key.
  public let scopeKey: Data

  public init(name: String, scopeKey: Data) {
    self.name = name
    self.scopeKey = scopeKey
  }
}

/// Defines configuration information for a broadcast channel.
///
/// Channels allow broadcast messaging to all nodes sharing the same channel
/// name and secret key.
public struct ChannelInfo: Sendable, Equatable {
  /// The index of the channel configuration.
  public let index: UInt8
  /// The human-readable name of the channel.
  public let name: String
  /// The secret key data used for channel communication.
  public let secret: Data

  /// Initializes a new channel information object.
  ///
  /// - Parameters:
  ///   - index: The channel index.
  ///   - name: The channel name.
  ///   - secret: The channel secret data.
  public init(index: UInt8, name: String, secret: Data) {
    self.index = index
    self.name = name
    self.secret = secret
  }
}
