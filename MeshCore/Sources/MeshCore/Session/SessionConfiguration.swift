import Foundation

/// Provides configuration settings for a `MeshCoreSession`.
public struct SessionConfiguration: Sendable {
  /// The default timeout for device operations in seconds.
  public let defaultTimeout: TimeInterval

  /// The client identifier sent to the device during session startup.
  public let clientIdentifier: String

  /// Wall-clock budget for one binary exchange (status, telemetry, and peers).
  /// In-exchange retransmits share this window until a reply or timeout.
  public let binaryRequestOverallTimeout: TimeInterval

  /// Minimum spacing between in-exchange retransmits of a binary request.
  /// `nil` disables retransmits. Live cadence is
  /// `max(this, suggestedTimeoutMs × binaryRetransmitRTTHeadroom)`.
  public let binaryRequestRetransmitInterval: TimeInterval?

  /// Multiplier on firmware `suggestedTimeoutMs` for retransmit spacing.
  /// Firmware's estimate is outbound airtime×path; the return path needs headroom.
  public static let binaryRetransmitRTTHeadroom: TimeInterval = 2.0

  /// Maximum idle gap allowed between contact stream events.
  public let contactStreamInactivityTimeout: TimeInterval

  /// Maximum total duration allowed for a contact stream.
  public let contactStreamHardTimeout: TimeInterval

  /// Maximum number of `CMD_GET_CHANNEL` Write Commands the pipeline keeps outstanding.
  /// Bounded below the nRF52 firmware's 12-deep receive queue to leave drop headroom.
  public let channelPipelineWindow: Int

  /// Maximum idle gap allowed between pipelined channel responses before the remaining
  /// requests are presumed dropped and surfaced as `missing` for reconciliation.
  public let channelPipelineIdleTimeout: TimeInterval

  /// Maximum total duration for a pipelined channel read before it returns what it has.
  public let channelPipelineHardTimeout: TimeInterval

  /// Time to keep draining after the last requested channel arrives, absorbing duplicate or
  /// straggler frames before releasing the serializer so they cannot leak to the next command.
  public let channelPipelinePostDrainGrace: TimeInterval

  /// Initializes a new session configuration.
  ///
  /// - Parameters:
  ///   - defaultTimeout: The timeout for operations. Defaults to 5.0 seconds.
  ///   - clientIdentifier: The identifier for this client. Defaults to "MeshCore-Swift".
  ///   - binaryRequestOverallTimeout: Wall-clock budget for one binary exchange.
  ///   - binaryRequestRetransmitInterval: Retransmit floor, or `nil` to disable resends.
  ///   - contactStreamInactivityTimeout: The idle timeout for contact list progress.
  ///   - contactStreamHardTimeout: The total timeout for a contact list response.
  ///   - channelPipelineWindow: Max outstanding pipelined channel reads.
  ///   - channelPipelineIdleTimeout: Idle gap before remaining pipelined reads are presumed dropped.
  ///   - channelPipelineHardTimeout: Total duration cap for a pipelined channel read.
  ///   - channelPipelinePostDrainGrace: Straggler-drain window after the last response.
  public init(
    defaultTimeout: TimeInterval = 5.0,
    clientIdentifier: String = "MeshCore-Swift",
    binaryRequestOverallTimeout: TimeInterval = 40.0,
    binaryRequestRetransmitInterval: TimeInterval? = 1.0,
    contactStreamInactivityTimeout: TimeInterval = 15.0,
    contactStreamHardTimeout: TimeInterval = 180.0,
    channelPipelineWindow: Int = 8,
    channelPipelineIdleTimeout: TimeInterval = 1.5,
    channelPipelineHardTimeout: TimeInterval = 30.0,
    channelPipelinePostDrainGrace: TimeInterval = 0.05
  ) {
    self.defaultTimeout = defaultTimeout
    self.clientIdentifier = clientIdentifier
    self.binaryRequestOverallTimeout = binaryRequestOverallTimeout
    self.binaryRequestRetransmitInterval = binaryRequestRetransmitInterval
    self.contactStreamInactivityTimeout = contactStreamInactivityTimeout
    self.contactStreamHardTimeout = contactStreamHardTimeout
    self.channelPipelineWindow = channelPipelineWindow
    self.channelPipelineIdleTimeout = channelPipelineIdleTimeout
    self.channelPipelineHardTimeout = channelPipelineHardTimeout
    self.channelPipelinePostDrainGrace = channelPipelinePostDrainGrace
  }

  /// The default configuration instance.
  public static let `default` = SessionConfiguration()

  /// Conversion factor between `MessageSentInfo.suggestedTimeoutMs` and seconds.
  static let millisecondsPerSecond: TimeInterval = 1000

  /// Headroom multiplier applied to the firmware-suggested round-trip time when
  /// waiting for a delivery acknowledgement during retry sends.
  static let retryAckTimeoutMultiplier: Double = 1.2
}

/// Represents errors that can occur during mesh core operations.
public enum MeshCoreError: Error, Sendable {
  /// The operation timed out.
  case timeout

  /// The device returned an error code.
  case deviceError(code: UInt8)

  /// The typed firmware sub-code for a ``deviceError(code:)``, or `nil` for
  /// other cases and for raw codes outside the known ``ErrorCode`` range.
  public var deviceErrorCode: ErrorCode? {
    guard case let .deviceError(code) = self else { return nil }
    return ErrorCode(rawValue: code)
  }

  /// Failed to parse data from the device.
  case parseError(String)

  /// The transport is not connected.
  case notConnected

  /// A command failed on the device.
  case commandFailed(CommandCode, reason: String)

  /// Received an unexpected response from the device.
  case invalidResponse(expected: String, got: String)

  /// Could not find the specified contact.
  case contactNotFound(publicKeyPrefix: Data)

  /// The data exceeds the device's maximum allowed size.
  case dataTooLarge(maxSize: Int, actualSize: Int)

  /// Cryptographic signing failed.
  case signingFailed(reason: String)

  /// Provided input is invalid.
  case invalidInput(String)

  /// An unknown error occurred.
  case unknown(String)

  /// Bluetooth is unavailable on this device.
  case bluetoothUnavailable

  /// App is not authorized to use Bluetooth.
  case bluetoothUnauthorized

  /// Bluetooth is powered off.
  case bluetoothPoweredOff

  /// The connection was lost.
  case connectionLost(underlying: Error?)

  /// The session has not been started.
  case sessionNotStarted

  /// The requested feature is disabled on the device.
  case featureDisabled
}

/// Represents the result of a message fetch operation.
public enum MessageResult: Sendable {
  /// A direct message from a contact.
  case contactMessage(ContactMessage)

  /// A message from a channel.
  case channelMessage(ChannelMessage)

  /// A binary datagram received on a channel (firmware v11+).
  case channelDatagram(ChannelDatagram)

  /// No more messages are available in the device queue.
  case noMoreMessages
}
