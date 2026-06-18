import Foundation

/// Represents events emitted by a MeshCore device during communication.
///
/// `MeshEvent` encapsulates all possible events that can be received from a MeshCore
/// mesh networking device. These events are delivered through the ``MeshCoreSession/events()``
/// asynchronous stream.
///
/// ## Event Categories
///
/// Events fall into several categories:
///
/// - **Connection**: Session lifecycle and connection state changes
/// - **Command Responses**: Success/error responses to commands
/// - **Contacts**: Contact list updates and discovery
/// - **Messages**: Incoming messages and send confirmations
/// - **Network**: Advertisements, path updates, and routing events
/// - **Telemetry**: Sensor data and device statistics
///
/// ## Usage
///
/// ```swift
/// for await event in await session.events() {
///     switch event {
///     case .contactMessageReceived(let message):
///         handleMessage(message)
///     case .advertisement(let publicKey):
///         print("Saw node: \(publicKey.hexString)")
///     case .connectionStateChanged(let state):
///         updateUI(for: state)
///     default:
///         break
///     }
/// }
/// ```
public enum MeshEvent: Sendable {
    // MARK: - Connection Lifecycle

    /// Indicates that the connection state has changed.
    ///
    /// Emitted when the transport connection state changes (connecting, connected, disconnected, etc.).
    /// Subscribe to ``MeshCoreSession/connectionState`` for a dedicated state stream.
    case connectionStateChanged(ConnectionState)

    // MARK: - Command Responses

    /// Indicates that a command completed successfully.
    ///
    /// Emitted when a command sent to the device completes successfully.
    /// 
    /// - Parameter value: An optional success value returned by the command.
    case ok(value: UInt32?)

    /// Indicates that a command failed with an error.
    ///
    /// Emitted when a command sent to the device fails.
    /// 
    /// - Parameter code: A device-specific error code, if available.
    case error(code: UInt8?)

    // MARK: - Device Information

    /// Indicates that device self-information was received.
    ///
    /// Emitted after calling ``MeshCoreSession/start()`` with the device's identity and configuration.
    case selfInfo(SelfInfo)

    /// Indicates that device capabilities were received.
    ///
    /// Emitted in response to ``MeshCoreSession/queryDevice()`` with hardware capabilities.
    case deviceInfo(DeviceCapabilities)

    /// Indicates that battery status was received.
    ///
    /// Emitted in response to ``MeshCoreSession/getBattery()``.
    case battery(BatteryInfo)

    /// Indicates that the current device time was received.
    ///
    /// Emitted in response to ``MeshCoreSession/getTime()``.
    case currentTime(Date)

    /// Indicates that custom variables were received.
    ///
    /// Emitted in response to ``MeshCoreSession/getCustomVars()``.
    case customVars([String: String])

    /// Indicates that channel configuration was received.
    ///
    /// Emitted in response to ``MeshCoreSession/getChannel(index:)``.
    case channelInfo(ChannelInfo)

    /// Indicates that core statistics were received.
    ///
    /// Emitted in response to ``MeshCoreSession/getStatsCore()``.
    case statsCore(CoreStats)

    /// Indicates that radio statistics were received.
    ///
    /// Emitted in response to ``MeshCoreSession/getStatsRadio()``.
    case statsRadio(RadioStats)

    /// Indicates that packet statistics were received.
    ///
    /// Emitted in response to ``MeshCoreSession/getStatsPackets()``.
    case statsPackets(PacketStats)

    /// Indicates that auto-add configuration was received.
    ///
    /// Emitted in response to ``MeshCoreSession/getAutoAddConfig()``.
    case autoAddConfig(AutoAddConfig)

    /// Contains the persisted default flood scope.
    ///
    /// Firmware v11+ (MeshCore v1.15.0+). `nil` means no default scope is persisted.
    /// Emitted in response to ``MeshCoreSession/getDefaultFloodScope()``.
    case defaultFloodScope(DefaultFloodScope?)

    /// Indicates that allowed repeat frequency ranges were received.
    ///
    /// Emitted in response to ``MeshCoreSession/getRepeatFreq()`` (v9+ firmware).
    case allowedRepeatFreq([FrequencyRange])

    // MARK: - Contact Management

    /// Indicates that a contact list transfer has started.
    ///
    /// Emitted at the start of a contact list transfer, indicating the total count.
    /// 
    /// - Parameter count: The total number of contacts to be received.
    case contactsStart(count: Int)

    /// Indicates that a contact was received.
    ///
    /// Emitted for each contact during a contact list transfer.
    case contact(MeshContact)

    /// Indicates that a contact list transfer has completed.
    ///
    /// Emitted at the end of a contact list transfer.
    /// 
    /// - Parameter lastModified: The timestamp of the most recently modified contact.
    case contactsEnd(lastModified: Date)

    /// Indicates that a new contact was discovered.
    ///
    /// Emitted when a new contact is added to the device's contact list.
    case newContact(MeshContact)

    /// Indicates a contact was automatically deleted by the device.
    ///
    /// Emitted when auto-add overwrites a contact due to storage limits.
    ///
    /// - Parameter publicKey: The 32-byte public key of the deleted contact.
    case contactDeleted(publicKey: Data)

    /// Indicates that the device's contact storage is full.
    ///
    /// Emitted when the device cannot add more contacts due to storage limits.
    case contactsFull

    /// Indicates that a contact URI was received.
    ///
    /// Emitted in response to ``MeshCoreSession/exportContact(publicKey:)`` with a shareable contact URI.
    case contactURI(String)

    // MARK: - Messaging

    /// Indicates that a message was queued for sending.
    ///
    /// Emitted when a message is successfully queued for transmission.
    /// Wait for an ``acknowledgement(code:)`` event to confirm delivery.
    case messageSent(MessageSentInfo)

    /// Indicates that a direct message was received from a contact.
    ///
    /// Emitted when a private message is received from another node.
    case contactMessageReceived(ContactMessage)

    /// Indicates that a channel broadcast message was received.
    ///
    /// Emitted when a message is received on a subscribed channel.
    case channelMessageReceived(ChannelMessage)

    /// Indicates that a binary datagram was received on a channel.
    ///
    /// Firmware v11+ (MeshCore v1.15.0+). Emitted when a `PAYLOAD_TYPE_GRP_DATA`
    /// packet is received. The payload is the raw (encrypted) ciphertext bytes —
    /// higher layers decrypt with the channel's shared key.
    case channelDataReceived(ChannelDatagram)

    /// Indicates that no more messages are waiting.
    ///
    /// Emitted by ``MeshCoreSession/getMessage()`` when the message queue is empty.
    case noMoreMessages

    /// Indicates that messages are waiting to be fetched.
    ///
    /// Emitted when the device has pending messages in its queue.
    /// Use ``MeshCoreSession/getMessage()`` to fetch them, or enable
    /// ``MeshCoreSession/startAutoMessageFetching()`` for automatic handling.
    case messagesWaiting

    // MARK: - Network Events

    /// Indicates that an advertisement was received from a node.
    ///
    /// Emitted when the device receives an advertisement broadcast from another mesh node.
    /// 
    /// - Parameter publicKey: The public key of the advertising node.
    case advertisement(publicKey: Data)

    /// Indicates that a routing path was updated.
    ///
    /// Emitted when the device learns a new or updated routing path to a node.
    /// 
    /// - Parameter publicKey: The public key of the destination node.
    case pathUpdate(publicKey: Data)

    /// Indicates a message delivery acknowledgement.
    ///
    /// Emitted when the device receives confirmation that a sent message was delivered.
    /// Match against ``MessageSentInfo/expectedAck`` to correlate with sent messages.
    ///
    /// - Parameters:
    ///   - code: The acknowledgement code to match against the expected value.
    ///   - tripTime: Firmware-measured radio round-trip time in milliseconds, if available.
    case acknowledgement(code: Data, tripTime: UInt32? = nil)

    /// Indicates that trace route data was received.
    ///
    /// Emitted in response to ``MeshCoreSession/sendTrace(tag:authCode:flags:path:)``
    /// with path information.
    case traceData(TraceInfo)

    /// Indicates a path discovery response.
    ///
    /// Emitted in response to ``MeshCoreSession/sendPathDiscovery(to:)`` with routing paths.
    case pathResponse(PathInfo)

    // MARK: - Authentication

    /// Indicates that login succeeded.
    ///
    /// Emitted when authentication to a remote node succeeds.
    case loginSuccess(LoginInfo)

    /// Indicates that login failed.
    ///
    /// Emitted when authentication to a remote node fails.
    /// 
    /// - Parameter publicKeyPrefix: The public key prefix of the target node, if available.
    case loginFailed(publicKeyPrefix: Data?)

    // MARK: - Binary Protocol Responses

    /// Indicates a status response from a remote node.
    ///
    /// Emitted in response to ``MeshCoreSession/requestStatus(from:)``.
    case statusResponse(StatusResponse)

    /// Indicates a telemetry response from a remote node.
    ///
    /// Emitted in response to ``MeshCoreSession/requestTelemetry(from:)`` or
    /// ``MeshCoreSession/getSelfTelemetry()``.
    case telemetryResponse(TelemetryResponse)

    /// Indicates a generic binary protocol response.
    ///
    /// Emitted for binary protocol responses that do not have specific event types.
    /// 
    /// - Parameters:
    ///   - tag: The request correlation tag.
    ///   - data: The response payload.
    case binaryResponse(tag: Data, data: Data)

    /// Indicates a Min/Max/Average telemetry response.
    ///
    /// Emitted in response to ``MeshCoreSession/requestMMA(from:start:end:)``.
    case mmaResponse(MMAResponse)

    /// Indicates an access control list response.
    ///
    /// Emitted in response to ``MeshCoreSession/requestACL(from:)``.
    case aclResponse(ACLResponse)

    /// Indicates a neighbours list response.
    ///
    /// Emitted in response to ``MeshCoreSession/requestNeighbours(from:count:offset:orderBy:pubkeyPrefixLength:)``.
    case neighboursResponse(NeighboursResponse)

    // MARK: - Cryptographic Signing

    /// Indicates that a signing session has started.
    ///
    /// Emitted in response to ``MeshCoreSession/signStart()`` with the maximum data size.
    /// 
    /// - Parameter maxLength: The maximum number of bytes that can be signed.
    case signStart(maxLength: Int)

    /// Indicates that a cryptographic signature was generated.
    ///
    /// Emitted in response to ``MeshCoreSession/signFinish(timeout:)`` with the signature.
    case signature(Data)

    /// Indicates that a feature is disabled.
    ///
    /// Emitted when a requested feature is disabled on the device.
    /// 
    /// - Parameter reason: A human-readable reason for the disabled feature.
    case disabled(reason: String)

    // MARK: - Raw Data and Logging

    /// Indicates that raw radio data was received.
    ///
    /// Emitted when the device forwards raw radio packets.
    case rawData(RawDataInfo)

    /// Indicates log data from the device.
    ///
    /// Emitted when the device sends diagnostic log data.
    case logData(LogDataInfo)

    /// Indicates parsed RF log data.
    ///
    /// Emitted when the device sends low-level radio log data that has been
    /// parsed into structured packet information including route type, payload type,
    /// path nodes, and packet payload.
    case rxLogData(ParsedRxLogData)

    /// Indicates that control protocol data was received.
    ///
    /// Emitted when control protocol messages are received.
    case controlData(ControlDataInfo)

    /// Indicates a node discovery response.
    ///
    /// Emitted in response to ``MeshCoreSession/sendNodeDiscoverRequest(filter:prefixOnly:tag:since:)``.
    case discoverResponse(DiscoverResponse)

    /// Indicates an advertisement path response.
    ///
    /// Emitted in response to advertisement path queries (0x16).
    case advertPathResponse(AdvertPathResponse)

    /// Indicates a tuning parameters response.
    ///
    /// Emitted in response to tuning parameters queries (0x17).
    case tuningParamsResponse(TuningParamsResponse)

    // MARK: - Key Management

    /// Indicates that a private key was exported.
    ///
    /// Emitted in response to ``MeshCoreSession/exportPrivateKey()`` with the device's private key.
    case privateKey(Data)

    // MARK: - Debug and Diagnostics

    /// Indicates that packet parsing failed.
    ///
    /// Emitted when the session receives data it cannot parse.
    /// This is a diagnostic event for debugging protocol issues.
    /// 
    /// - Parameters:
    ///   - data: The raw data that failed to parse.
    ///   - reason: A human-readable reason for the parse failure.
    case parseFailure(data: Data, reason: String)
}

// MARK: - Event Attributes for Filtering

extension MeshEvent {
    /// Returns attributes for event filtering.
    ///
    /// Provides a dictionary of key-value pairs that can be used to filter events.
    /// This enables type-safe filtering via ``EventFilter`` without runtime type checking.
    ///
    /// - Note: Not all events have attributes. Events without filterable properties
    ///   return an empty dictionary.
    public var attributes: [String: AnyHashable] {
        switch self {
        case .contactMessageReceived(let msg):
            return [
                "publicKeyPrefix": msg.senderPublicKeyPrefix,
                "textType": msg.textType
            ]
        case .channelMessageReceived(let msg):
            return [
                "channelIndex": msg.channelIndex,
                "textType": msg.textType
            ]
        case .acknowledgement(let code, let tripTime):
            var result: [String: AnyHashable] = ["code": code]
            if let tripTime { result["tripTime"] = tripTime }
            return result
        case .messageSent(let info):
            return [
                "route": info.route,
                "expectedAck": info.expectedAck
            ]
        case .statusResponse(let resp):
            return ["publicKeyPrefix": resp.publicKeyPrefix]
        case .telemetryResponse(let resp):
            return ["publicKeyPrefix": resp.publicKeyPrefix]
        case .advertisement(let pubKey):
            return ["publicKeyPrefix": pubKey.prefix(6)]
        case .pathUpdate(let pubKey):
            return ["publicKeyPrefix": pubKey.prefix(6)]
        case .newContact(let contact):
            return ["publicKey": contact.publicKey]
        case .contact(let contact):
            return ["publicKey": contact.publicKey]
        case .error(let code):
            return ["code": code as AnyHashable]
        case .ok(let value):
            return ["value": value as AnyHashable]
        default:
            return [:]
        }
    }
}

extension MeshEvent {
    /// Stable, low-cardinality string identifier for the case (no associated values).
    /// Used for observability output where dumping the full payload would balloon
    /// log/signpost storage.
    ///
    /// For `.acknowledgement(code:, tripTime:)` this yields `"acknowledgement"`;
    /// for `.contactsFull` it yields `"contactsFull"`. Stays correct as
    /// `MeshEvent` evolves — adding a new case won't fall through to
    /// `"unknown"` the way a manual switch would.
    public var caseName: String {
        let full = String(describing: self)
        if let paren = full.firstIndex(of: "(") {
            return String(full[..<paren])
        }
        return full
    }
}

extension MeshEvent {
    /// The typed device error sub-code for an ``error(code:)`` event.
    ///
    /// Returns `nil` for non-error events, when the firmware omitted the
    /// sub-code byte, or when the raw byte falls outside the known
    /// ``ErrorCode`` range. The raw byte remains available on the
    /// associated value of ``error(code:)`` for forward compatibility.
    public var errorCode: ErrorCode? {
        guard case .error(let code) = self, let code else { return nil }
        return ErrorCode(rawValue: code)
    }
}
