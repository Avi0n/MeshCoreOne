import Foundation

/// Provides information returned when a message is successfully queued for sending.
///
/// This struct is returned by message-sending methods and contains information
/// needed to wait for delivery acknowledgement.
public struct MessageSentInfo: Sendable, Equatable {
  /// Route flag from the firmware: 1 = flood, 0 = direct.
  public let route: UInt8
  /// The expected acknowledgement data for correlation.
  public let expectedAck: Data
  /// The suggested timeout in milliseconds to wait for acknowledgement.
  public let suggestedTimeoutMs: UInt32

  /// Initializes a new message sent information object.
  ///
  /// - Parameters:
  ///   - route: Route flag from the firmware: 1 = flood, 0 = direct.
  ///   - expectedAck: The expected acknowledgement data.
  ///   - suggestedTimeoutMs: The suggested timeout in milliseconds.
  public init(route: UInt8, expectedAck: Data, suggestedTimeoutMs: UInt32) {
    self.route = route
    self.expectedAck = expectedAck
    self.suggestedTimeoutMs = suggestedTimeoutMs
  }
}

/// Represents a message received from a mesh contact.
///
/// Contact messages are private messages sent directly to your device from
/// another node in the mesh network.
public struct ContactMessage: Sendable, Equatable {
  /// The public key prefix of the sender.
  public let senderPublicKeyPrefix: Data
  /// The length of the path the message travelled.
  public let pathLength: UInt8
  /// The type of text content.
  public let textType: UInt8
  /// The timestamp from the sender.
  public let senderTimestamp: Date
  /// The cryptographic signature of the message, if available.
  public let signature: Data?
  /// The actual text content of the message.
  public let text: String
  /// The signal-to-noise ratio of the received packet.
  public let snr: Double?

  /// Initializes a new contact message.
  ///
  /// - Parameters:
  ///   - senderPublicKeyPrefix: The sender's public key prefix.
  ///   - pathLength: The path length.
  ///   - textType: The text type.
  ///   - senderTimestamp: The sender's timestamp.
  ///   - signature: The signature.
  ///   - text: The message text.
  ///   - snr: The signal-to-noise ratio.
  public init(
    senderPublicKeyPrefix: Data,
    pathLength: UInt8,
    textType: UInt8,
    senderTimestamp: Date,
    signature: Data?,
    text: String,
    snr: Double?
  ) {
    self.senderPublicKeyPrefix = senderPublicKeyPrefix
    self.pathLength = pathLength
    self.textType = textType
    self.senderTimestamp = senderTimestamp
    self.signature = signature
    self.text = text
    self.snr = snr
  }
}

/// Represents a message received on a broadcast channel.
///
/// Channel messages are broadcast messages visible to all nodes subscribed
/// to the same channel.
public struct ChannelMessage: Sendable, Equatable {
  /// The index of the channel on which the message was received.
  public let channelIndex: UInt8
  /// The length of the path the message travelled.
  public let pathLength: UInt8
  /// The type of text content.
  public let textType: UInt8
  /// The timestamp from the sender.
  public let senderTimestamp: Date
  /// The actual text content of the message.
  public let text: String
  /// The signal-to-noise ratio of the received packet.
  public let snr: Double?

  /// Initializes a new channel message.
  ///
  /// - Parameters:
  ///   - channelIndex: The channel index.
  ///   - pathLength: The path length.
  ///   - textType: The text type.
  ///   - senderTimestamp: The sender's timestamp.
  ///   - text: The message text.
  ///   - snr: The signal-to-noise ratio.
  public init(
    channelIndex: UInt8,
    pathLength: UInt8,
    textType: UInt8,
    senderTimestamp: Date,
    text: String,
    snr: Double?
  ) {
    self.channelIndex = channelIndex
    self.pathLength = pathLength
    self.textType = textType
    self.senderTimestamp = senderTimestamp
    self.text = text
    self.snr = snr
  }
}

/// Represents a binary datagram received on a broadcast channel.
///
/// Channel datagrams carry arbitrary application data (`data_type` namespaces
/// the schema) rather than plain text. Firmware v11+ (MeshCore v1.15.0+).
public struct ChannelDatagram: Sendable, Equatable {
  /// The index of the channel on which the datagram was received.
  public let channelIndex: UInt8
  /// Encoded path-length byte from the RF packet header.
  ///
  /// - `0xFF`: the datagram arrived via direct route; upstream path is unknown to firmware.
  /// - Otherwise: flood-accumulated path encoding. Upper 2 bits = hash size (1, 2, or 3 bytes
  ///   per hop); lower 6 bits = hop count. Decode with ``decodePathLen(_:)`` into a
  ///   ``PathLenDecoded`` for inspecting hops.
  public let pathLength: UInt8
  /// Application data-type namespace (see firmware `number_allocations.md`).
  public let dataType: UInt16
  /// The raw binary payload (up to 163 bytes).
  public let data: Data
  /// The signal-to-noise ratio of the received packet in dB.
  ///
  /// `RESP_CODE_CHANNEL_DATA_RECV` always carries SNR at offset 0, so this value
  /// is always present (unlike ``ChannelMessage/snr`` which is optional because
  /// v1-era push codes omitted it).
  public let snr: Double

  /// Initializes a new channel datagram.
  public init(
    channelIndex: UInt8,
    pathLength: UInt8,
    dataType: UInt16,
    data: Data,
    snr: Double
  ) {
    self.channelIndex = channelIndex
    self.pathLength = pathLength
    self.dataType = dataType
    self.data = data
    self.snr = snr
  }
}
