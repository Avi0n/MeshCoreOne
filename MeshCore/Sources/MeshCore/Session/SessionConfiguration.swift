import Foundation

/// Provides configuration settings for a `MeshCoreSession`.
public struct SessionConfiguration: Sendable {
    /// The default timeout for device operations in seconds.
    public let defaultTimeout: TimeInterval

    /// The client identifier sent to the device during session startup.
    public let clientIdentifier: String

    /// Maximum idle gap allowed between contact stream events.
    public let contactStreamInactivityTimeout: TimeInterval

    /// Maximum total duration allowed for a contact stream.
    public let contactStreamHardTimeout: TimeInterval

    /// Initializes a new session configuration.
    ///
    /// - Parameters:
    ///   - defaultTimeout: The timeout for operations. Defaults to 5.0 seconds.
    ///   - clientIdentifier: The identifier for this client. Defaults to "MeshCore-Swift".
    ///   - contactStreamInactivityTimeout: The idle timeout for contact list progress.
    ///   - contactStreamHardTimeout: The total timeout for a contact list response.
    public init(
        defaultTimeout: TimeInterval = 5.0,
        clientIdentifier: String = "MeshCore-Swift",
        contactStreamInactivityTimeout: TimeInterval = 15.0,
        contactStreamHardTimeout: TimeInterval = 180.0
    ) {
        self.defaultTimeout = defaultTimeout
        self.clientIdentifier = clientIdentifier
        self.contactStreamInactivityTimeout = contactStreamInactivityTimeout
        self.contactStreamHardTimeout = contactStreamHardTimeout
    }

    /// The default configuration instance.
    public static let `default` = SessionConfiguration()
}

/// Represents errors that can occur during mesh core operations.
public enum MeshCoreError: Error, Sendable {
    /// The operation timed out.
    case timeout

    /// The device returned an error code.
    case deviceError(code: UInt8)

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
