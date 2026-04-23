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

// MARK: - Supporting Types for MeshEvent Associated Values

/// Provides information returned when a message is successfully queued for sending.
///
/// This struct is returned by message-sending methods and contains information
/// needed to wait for delivery acknowledgement.
public struct MessageSentInfo: Sendable, Equatable {
    /// The type of the sent message.
    public let type: UInt8
    /// The expected acknowledgement data for correlation.
    public let expectedAck: Data
    /// The suggested timeout in milliseconds to wait for acknowledgement.
    public let suggestedTimeoutMs: UInt32

    /// Initializes a new message sent information object.
    /// 
    /// - Parameters:
    ///   - type: The message type.
    ///   - expectedAck: The expected acknowledgement data.
    ///   - suggestedTimeoutMs: The suggested timeout in milliseconds.
    public init(type: UInt8, expectedAck: Data, suggestedTimeoutMs: UInt32) {
        self.type = type
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

/// Response from a `REQ_TYPE_GET_OWNER_INFO` (0x07) binary request.
///
/// The firmware responds with a UTF-8 string: `"<firmware_ver>\n<node_name>\n<owner_info>"`.
public struct OwnerInfoResponse: Sendable {
    public let firmwareVersion: String
    public let nodeName: String
    public let ownerInfo: String

    public init(firmwareVersion: String, nodeName: String, ownerInfo: String) {
        self.firmwareVersion = firmwareVersion
        self.nodeName = nodeName
        self.ownerInfo = ownerInfo
    }
}

/// Represents a status response from a remote node.
///
/// Note on offset logic (per Python parsing.py):
/// - Binary request responses: offset=0, fields start immediately after response code
/// - Push notification responses: offset=8, pubkey_prefix at bytes 2-8, fields follow
/// The parser must handle both cases based on whether this is a solicited vs unsolicited response
public struct StatusResponse: Sendable, Equatable {
    /// Describes which firmware status layout was used to decode the payload.
    public enum Layout: Sendable, Equatable {
        /// Standard repeater / legacy status layout.
        case repeater
        /// Room server layout used by room-server firmware.
        case roomServer
    }

    /// The decoded status layout.
    public let layout: Layout
    /// The public key prefix of the responding node.
    public let publicKeyPrefix: Data
    /// The battery level in millivolts.
    public let battery: Int
    /// The current length of the transmit queue.
    public let txQueueLength: Int
    /// The noise floor in dBm.
    public let noiseFloor: Int
    /// The last received signal strength indicator.
    public let lastRSSI: Int
    /// Total packets received by the node.
    public let packetsReceived: UInt32
    /// Total packets sent by the node.
    public let packetsSent: UInt32
    /// Total transmit airtime in seconds.
    public let airtime: UInt32
    /// The node's uptime in seconds.
    public let uptime: UInt32
    /// Total flood packets sent.
    public let sentFlood: UInt32
    /// Total direct packets sent.
    public let sentDirect: UInt32
    /// Total flood packets received.
    public let receivedFlood: UInt32
    /// Total direct packets received.
    public let receivedDirect: UInt32
    /// Total full events recorded.
    public let fullEvents: Int
    /// The last recorded signal-to-noise ratio.
    public let lastSNR: Double
    /// Total direct duplicates received.
    public let directDuplicates: Int
    /// Total flood duplicates received.
    public let floodDuplicates: Int
    /// Total receive airtime in seconds.
    public let rxAirtime: UInt32
    /// Total receive errors (v1.12+, 0 for older firmware).
    public let receiveErrors: UInt32
    /// Total messages posted to the room server.
    public let roomServerPostedCount: UInt16?
    /// Total room-server post push attempts.
    public let roomServerPostPushCount: UInt16?

    /// Initializes a new status response object.
    public init(
        layout: Layout = .repeater,
        publicKeyPrefix: Data,
        battery: Int,
        txQueueLength: Int,
        noiseFloor: Int,
        lastRSSI: Int,
        packetsReceived: UInt32,
        packetsSent: UInt32,
        airtime: UInt32,
        uptime: UInt32,
        sentFlood: UInt32,
        sentDirect: UInt32,
        receivedFlood: UInt32,
        receivedDirect: UInt32,
        fullEvents: Int,
        lastSNR: Double,
        directDuplicates: Int,
        floodDuplicates: Int,
        rxAirtime: UInt32,
        receiveErrors: UInt32 = 0,
        roomServerPostedCount: UInt16? = nil,
        roomServerPostPushCount: UInt16? = nil
    ) {
        self.layout = layout
        self.publicKeyPrefix = publicKeyPrefix
        self.battery = battery
        self.txQueueLength = txQueueLength
        self.noiseFloor = noiseFloor
        self.lastRSSI = lastRSSI
        self.packetsReceived = packetsReceived
        self.packetsSent = packetsSent
        self.airtime = airtime
        self.uptime = uptime
        self.sentFlood = sentFlood
        self.sentDirect = sentDirect
        self.receivedFlood = receivedFlood
        self.receivedDirect = receivedDirect
        self.fullEvents = fullEvents
        self.lastSNR = lastSNR
        self.directDuplicates = directDuplicates
        self.floodDuplicates = floodDuplicates
        self.rxAirtime = rxAirtime
        self.receiveErrors = receiveErrors
        self.roomServerPostedCount = roomServerPostedCount
        self.roomServerPostPushCount = roomServerPostPushCount
    }
}

/// Represents core device statistics.
/// 
/// Core stats (9 bytes payload, little-endian per Python reader.py):
/// - Bytes 0-1: UInt16 - battery_mv
/// - Bytes 2-5: UInt32 - uptime_secs
/// - Bytes 6-7: UInt16 - errors
/// - Byte 8: UInt8 - queue_len
public struct CoreStats: Sendable, Equatable {
    /// The battery level in millivolts.
    public let batteryMV: UInt16
    /// The device uptime in seconds.
    public let uptimeSeconds: UInt32
    /// Total count of errors encountered.
    public let errors: UInt16
    /// The current length of the transmit queue.
    public let queueLength: UInt8

    /// Initializes a new core statistics object.
    /// 
    /// - Parameters:
    ///   - batteryMV: The battery level.
    ///   - uptimeSeconds: The uptime in seconds.
    ///   - errors: The error count.
    ///   - queueLength: The queue length.
    public init(batteryMV: UInt16, uptimeSeconds: UInt32, errors: UInt16, queueLength: UInt8) {
        self.batteryMV = batteryMV
        self.uptimeSeconds = uptimeSeconds
        self.errors = errors
        self.queueLength = queueLength
    }
}

/// Represents radio statistics.
/// 
/// Radio stats (12 bytes payload, little-endian per Python reader.py):
/// - Bytes 0-1: Int16 - noise_floor (dBm)
/// - Byte 2: Int8 - last_rssi (dBm)
/// - Byte 3: Int8 - last_snr (raw, divide by 4.0 for dB)
/// - Bytes 4-7: UInt32 - tx_air_secs
/// - Bytes 8-11: UInt32 - rx_air_secs
public struct RadioStats: Sendable, Equatable {
    /// The noise floor in dBm.
    public let noiseFloor: Int16
    /// The last received signal strength indicator in dBm.
    public let lastRSSI: Int8
    /// The last recorded signal-to-noise ratio.
    public let lastSNR: Double
    /// Total transmit airtime in seconds.
    public let txAirtimeSeconds: UInt32
    /// Total receive airtime in seconds.
    public let rxAirtimeSeconds: UInt32

    /// Initializes a new radio statistics object.
    /// 
    /// - Parameters:
    ///   - noiseFloor: The noise floor.
    ///   - lastRSSI: The last RSSI.
    ///   - lastSNR: The last SNR.
    ///   - txAirtimeSeconds: Transmit airtime.
    ///   - rxAirtimeSeconds: Receive airtime.
    public init(
        noiseFloor: Int16,
        lastRSSI: Int8,
        lastSNR: Double,
        txAirtimeSeconds: UInt32,
        rxAirtimeSeconds: UInt32
    ) {
        self.noiseFloor = noiseFloor
        self.lastRSSI = lastRSSI
        self.lastSNR = lastSNR
        self.txAirtimeSeconds = txAirtimeSeconds
        self.rxAirtimeSeconds = rxAirtimeSeconds
    }
}

/// Represents packet statistics.
/// 
/// Packet stats (24 bytes payload, little-endian per Python reader.py):
/// - Bytes 0-3: UInt32 - recv
/// - Bytes 4-7: UInt32 - sent
/// - Bytes 8-11: UInt32 - flood_tx
/// - Bytes 12-15: UInt32 - direct_tx
/// - Bytes 16-19: UInt32 - flood_rx
/// - Bytes 20-23: UInt32 - direct_rx
/// - Bytes 24-27: UInt32 - recv_errors (optional, present when frame >= 28 bytes)
public struct PacketStats: Sendable, Equatable {
    /// Total packets received.
    public let received: UInt32
    /// Total packets sent.
    public let sent: UInt32
    /// Total flood packets transmitted.
    public let floodTx: UInt32
    /// Total direct packets transmitted.
    public let directTx: UInt32
    /// Total flood packets received.
    public let floodRx: UInt32
    /// Total direct packets received.
    public let directRx: UInt32
    /// Total RadioLib receive errors (CRC failures, malformed packets).
    public let receiveErrors: UInt32

    /// Initializes a new packet statistics object.
    public init(
        received: UInt32,
        sent: UInt32,
        floodTx: UInt32,
        directTx: UInt32,
        floodRx: UInt32,
        directRx: UInt32,
        receiveErrors: UInt32 = 0
    ) {
        self.received = received
        self.sent = sent
        self.floodTx = floodTx
        self.directTx = directTx
        self.floodRx = floodRx
        self.directRx = directRx
        self.receiveErrors = receiveErrors
    }
}

/// Represents trace route information.
public struct TraceInfo: Sendable, Equatable {
    /// The tag for request correlation.
    public let tag: UInt32
    /// The authentication code for the trace request.
    public let authCode: UInt32
    /// Configuration flags for the trace.
    public let flags: UInt8
    /// The length of the recorded path.
    public let pathLength: UInt8
    /// The list of nodes in the trace path.
    public let path: [TraceNode]

    /// Initializes a new trace information object.
    public init(tag: UInt32, authCode: UInt32, flags: UInt8, pathLength: UInt8, path: [TraceNode]) {
        self.tag = tag
        self.authCode = authCode
        self.flags = flags
        self.pathLength = pathLength
        self.path = path
    }
}

/// Represents a node in a trace path.
public struct TraceNode: Sendable, Equatable {
    /// The hash bytes of the node's public key, if available.
    /// Size depends on path_sz flag: 1, 2, 4, or 8 bytes.
    /// Nil for destination node or if hash is 0xFF (single-byte mode).
    public let hashBytes: Data?

    /// The signal-to-noise ratio at this hop.
    public let snr: Double

    /// Legacy accessor: first byte of hash, or nil if no hash.
    /// Use hashBytes for multi-byte hashes (path_sz > 0).
    public var hash: UInt8? {
        guard let bytes = hashBytes, !bytes.isEmpty else { return nil }
        return bytes[0]
    }

    /// Initializes a new trace node with hash bytes.
    ///
    /// - Parameters:
    ///   - hashBytes: The hash bytes (nil for destination).
    ///   - snr: The signal-to-noise ratio.
    public init(hashBytes: Data?, snr: Double) {
        self.hashBytes = hashBytes
        self.snr = snr
    }

    /// Legacy initializer for single-byte hashes.
    ///
    /// - Parameters:
    ///   - hash: Single-byte hash (nil for destination).
    ///   - snr: The signal-to-noise ratio.
    public init(hash: UInt8?, snr: Double) {
        if let h = hash {
            self.hashBytes = Data([h])
        } else {
            self.hashBytes = nil
        }
        self.snr = snr
    }
}

/// Represents path discovery information.
public struct PathInfo: Sendable, Equatable {
    /// The public key prefix of the node for which the path was discovered.
    public let publicKeyPrefix: Data
    /// Raw outbound `path_len` byte as received on the wire. Upper 2 bits encode
    /// the hash mode, lower 6 bits encode the hop count.
    public let outPathLength: UInt8
    /// The outbound path data.
    public let outPath: Data
    /// Raw inbound `path_len` byte as received on the wire.
    public let inPathLength: UInt8
    /// The inbound path data.
    public let inPath: Data

    /// Hop count decoded from ``outPathLength``. Returns `nil` when the byte uses
    /// the reserved hash-size mode (upper 2 bits == `11`) so callers can handle
    /// unknown encodings explicitly instead of defaulting to "direct".
    public var outHopCount: Int? {
        decodePathLen(outPathLength)?.hopCount
    }

    /// Hop count decoded from ``inPathLength``.
    public var inHopCount: Int? {
        decodePathLen(inPathLength)?.hopCount
    }

    /// Initializes a new path information object.
    ///
    /// - Parameters:
    ///   - publicKeyPrefix: The node's public key prefix.
    ///   - outPathLength: Raw `out_path_len` byte from the wire.
    ///   - outPath: The outbound path bytes. Length must equal the byte length
    ///     decoded from ``outPathLength`` (`0` when the byte uses the reserved
    ///     mode).
    ///   - inPathLength: Raw `in_path_len` byte from the wire.
    ///   - inPath: The inbound path bytes. Same contract as `outPath`.
    public init(
        publicKeyPrefix: Data,
        outPathLength: UInt8,
        outPath: Data,
        inPathLength: UInt8,
        inPath: Data
    ) {
        let outByteLength = decodePathLen(outPathLength)?.byteLength ?? 0
        let inByteLength = decodePathLen(inPathLength)?.byteLength ?? 0
        precondition(
            outPath.count == outByteLength,
            "PathInfo.outPath size \(outPath.count) does not match outPathLength byte length \(outByteLength)"
        )
        precondition(
            inPath.count == inByteLength,
            "PathInfo.inPath size \(inPath.count) does not match inPathLength byte length \(inByteLength)"
        )
        self.publicKeyPrefix = publicKeyPrefix
        self.outPathLength = outPathLength
        self.outPath = outPath
        self.inPathLength = inPathLength
        self.inPath = inPath
    }
}

/// Represents login success information.
public struct LoginInfo: Sendable, Equatable {
    /// The permissions granted after successful login.
    public let permissions: UInt8
    /// A boolean indicating whether the user has administrator privileges.
    public let isAdmin: Bool
    /// The public key prefix of the node where the login occurred.
    public let publicKeyPrefix: Data

    /// Initializes a new login information object.
    /// 
    /// - Parameters:
    ///   - permissions: The granted permissions.
    ///   - isAdmin: Admin status.
    ///   - publicKeyPrefix: The node's public key prefix.
    public init(permissions: UInt8, isAdmin: Bool, publicKeyPrefix: Data) {
        self.permissions = permissions
        self.isAdmin = isAdmin
        self.publicKeyPrefix = publicKeyPrefix
    }
}

/// Represents a telemetry response from a remote node.
public struct TelemetryResponse: Sendable, Equatable {
    /// The public key prefix of the responding node.
    public let publicKeyPrefix: Data
    /// The optional tag for request correlation.
    public let tag: Data?
    /// The raw telemetry data payload.
    public let rawData: Data

    /// Returns the parsed LPP data points from the raw telemetry data.
    public var dataPoints: [LPPDataPoint] {
        LPPDecoder.decode(rawData)
    }

    /// Initializes a new telemetry response object.
    /// 
    /// - Parameters:
    ///   - publicKeyPrefix: The node's public key prefix.
    ///   - tag: The correlation tag.
    ///   - rawData: The raw payload.
    public init(publicKeyPrefix: Data, tag: Data?, rawData: Data) {
        self.publicKeyPrefix = publicKeyPrefix
        self.tag = tag
        self.rawData = rawData
    }
}

/// Represents a MMA (Min/Max/Average) response.
public struct MMAResponse: Sendable, Equatable {
    /// The public key prefix of the responding node.
    public let publicKeyPrefix: Data
    /// The tag for request correlation.
    public let tag: Data
    /// The list of MMA entries.
    public let data: [MMAEntry]

    /// Initializes a new MMA response object.
    public init(publicKeyPrefix: Data, tag: Data, data: [MMAEntry]) {
        self.publicKeyPrefix = publicKeyPrefix
        self.tag = tag
        self.data = data
    }
}

/// Represents an entry in MMA response data.
public struct MMAEntry: Sendable, Equatable {
    /// The sensor channel associated with this entry.
    public let channel: UInt8
    /// The type of data recorded.
    public let type: String
    /// The minimum recorded value.
    public let min: Double
    /// The maximum recorded value.
    public let max: Double
    /// The average recorded value.
    public let avg: Double

    /// Initializes a new MMA entry object.
    public init(channel: UInt8, type: String, min: Double, max: Double, avg: Double) {
        self.channel = channel
        self.type = type
        self.min = min
        self.max = max
        self.avg = avg
    }
}

/// Represents an ACL (Access Control List) response.
public struct ACLResponse: Sendable, Equatable {
    /// The public key prefix of the responding node.
    public let publicKeyPrefix: Data
    /// The tag for request correlation.
    public let tag: Data
    /// The list of ACL entries.
    public let entries: [ACLEntry]

    /// Initializes a new ACL response object.
    public init(publicKeyPrefix: Data, tag: Data, entries: [ACLEntry]) {
        self.publicKeyPrefix = publicKeyPrefix
        self.tag = tag
        self.entries = entries
    }
}

/// Represents an entry in ACL response data.
public struct ACLEntry: Sendable, Equatable {
    /// The public key prefix affected by this ACL entry.
    public let keyPrefix: Data
    /// The permissions granted to the key prefix.
    public let permissions: UInt8

    /// Initializes a new ACL entry object.
    public init(keyPrefix: Data, permissions: UInt8) {
        self.keyPrefix = keyPrefix
        self.permissions = permissions
    }
}

/// Represents a neighbours response from a remote node.
/// 
/// Note: Parser context must include `pubkey_prefix_length` for proper neighbour parsing
/// (typically 6 bytes, but configurable in some firmware versions).
public struct NeighboursResponse: Sendable, Equatable {
    /// The public key prefix of the responding node.
    public let publicKeyPrefix: Data
    /// The tag for request correlation.
    public let tag: Data
    /// The total number of neighbours known to the node.
    public let totalCount: Int
    /// The list of neighbours returned in this response.
    public let neighbours: [Neighbour]

    /// Initializes a new neighbours response object.
    public init(publicKeyPrefix: Data, tag: Data, totalCount: Int, neighbours: [Neighbour]) {
        self.publicKeyPrefix = publicKeyPrefix
        self.tag = tag
        self.totalCount = totalCount
        self.neighbours = neighbours
    }
}

/// Represents a neighbour node.
public struct Neighbour: Sendable, Equatable {
    /// The public key prefix of the neighbour node.
    public let publicKeyPrefix: Data
    /// How many seconds ago the neighbour was last seen.
    public let secondsAgo: Int
    /// The signal-to-noise ratio of the last communication with this neighbour.
    public let snr: Double

    /// Initializes a new neighbour object.
    public init(publicKeyPrefix: Data, secondsAgo: Int, snr: Double) {
        self.publicKeyPrefix = publicKeyPrefix
        self.secondsAgo = secondsAgo
        self.snr = snr
    }
}

/// Represents raw data received from the device.
public struct RawDataInfo: Sendable, Equatable {
    /// The signal-to-noise ratio of the received packet.
    public let snr: Double
    /// The received signal strength indicator in dBm.
    public let rssi: Int
    /// The raw payload data.
    public let payload: Data

    /// Initializes a new raw data info object.
    public init(snr: Double, rssi: Int, payload: Data) {
        self.snr = snr
        self.rssi = rssi
        self.payload = payload
    }
}

/// Represents log data received from the device.
public struct LogDataInfo: Sendable, Equatable {
    /// The optional signal-to-noise ratio associated with the log entry.
    public let snr: Double?
    /// The optional received signal strength indicator associated with the log entry.
    public let rssi: Int?
    /// The raw log payload data.
    public let payload: Data

    /// Initializes a new log data info object.
    public init(snr: Double?, rssi: Int?, payload: Data) {
        self.snr = snr
        self.rssi = rssi
        self.payload = payload
    }
}

/// Represents control protocol data received from the device.
public struct ControlDataInfo: Sendable, Equatable {
    /// The signal-to-noise ratio of the received packet.
    public let snr: Double
    /// The received signal strength indicator in dBm.
    public let rssi: Int
    /// The path length the control packet travelled.
    public let pathLength: UInt8
    /// The type of control protocol payload.
    public let payloadType: UInt8
    /// The raw payload data.
    public let payload: Data

    /// Initializes a new control data info object.
    public init(snr: Double, rssi: Int, pathLength: UInt8, payloadType: UInt8, payload: Data) {
        self.snr = snr
        self.rssi = rssi
        self.pathLength = pathLength
        self.payloadType = payloadType
        self.payload = payload
    }
}

/// Represents a node discovery response.
public struct DiscoverResponse: Sendable, Equatable {
    /// The type of the discovered node.
    public let nodeType: UInt8
    /// The inbound signal-to-noise ratio.
    public let snrIn: Double
    /// The signal-to-noise ratio.
    public let snr: Double
    /// The received signal strength indicator in dBm.
    public let rssi: Int
    /// The path length to the discovered node.
    public let pathLength: UInt8
    /// The tag for request correlation.
    public let tag: Data
    /// The full public key of the discovered node.
    public let publicKey: Data

    /// Initializes a new discovery response object.
    public init(
        nodeType: UInt8,
        snrIn: Double,
        snr: Double,
        rssi: Int,
        pathLength: UInt8,
        tag: Data,
        publicKey: Data
    ) {
        self.nodeType = nodeType
        self.snrIn = snrIn
        self.snr = snr
        self.rssi = rssi
        self.pathLength = pathLength
        self.tag = tag
        self.publicKey = publicKey
    }
}

/// Auto-add configuration received from the device.
///
/// Bundles the bitmask (which node types to auto-add) with the max hops filter.
public struct AutoAddConfig: Sendable, Equatable {
    /// Bitmask controlling auto-add behavior (0x01=overwrite, 0x02=contacts, 0x04=repeaters, 0x08=rooms).
    public let bitmask: UInt8
    /// Maximum hops for auto-add filtering. 0 = no limit, 1 = direct only, N = up to N-1 hops (max 64).
    public let maxHops: UInt8

    public init(bitmask: UInt8, maxHops: UInt8 = 0) {
        self.bitmask = bitmask
        self.maxHops = maxHops
    }
}

/// Represents an allowed frequency range for client repeat mode.
public struct FrequencyRange: Sendable, Equatable {
    /// The lower bound of the frequency range in kHz.
    public let lowerKHz: UInt32
    /// The upper bound of the frequency range in kHz.
    public let upperKHz: UInt32

    /// Initializes a new frequency range.
    public init(lowerKHz: UInt32, upperKHz: UInt32) {
        self.lowerKHz = lowerKHz
        self.upperKHz = upperKHz
    }
}

/// Represents an advertisement path response.
///
/// Contains the path data received in response to an advertisement path query.
public struct AdvertPathResponse: Sendable, Equatable {
    /// The timestamp when the advertisement was received.
    public let recvTimestamp: UInt32
    /// The length of the path in bytes.
    public let pathLength: UInt8
    /// The raw path data.
    public let path: Data

    /// Initializes a new advertisement path response.
    ///
    /// - Parameters:
    ///   - recvTimestamp: The receive timestamp.
    ///   - pathLength: The path length.
    ///   - path: The path data.
    public init(recvTimestamp: UInt32, pathLength: UInt8, path: Data) {
        self.recvTimestamp = recvTimestamp
        self.pathLength = pathLength
        self.path = path
    }
}

/// Represents a tuning parameters response.
///
/// Contains radio tuning parameters used for adaptive timing calculations.
public struct TuningParamsResponse: Sendable, Equatable {
    /// The base delay for receive operations in milliseconds.
    public let rxDelayBase: Double
    /// The airtime scaling factor.
    public let airtimeFactor: Double

    /// Initializes a new tuning parameters response.
    ///
    /// - Parameters:
    ///   - rxDelayBase: The RX delay base in milliseconds.
    ///   - airtimeFactor: The airtime factor.
    public init(rxDelayBase: Double, airtimeFactor: Double) {
        self.rxDelayBase = rxDelayBase
        self.airtimeFactor = airtimeFactor
    }
}

// MARK: - Connection State

/// Represents the current connection state of a MeshCore session.
///
/// Use this enum to update your UI based on connection status. Subscribe to
/// state changes via ``MeshCoreSession/connectionState``.
///
/// ## Example
///
/// ```swift
/// for await state in session.connectionState {
///     switch state {
///     case .connected:
///         showConnectedUI()
///     case .connecting:
///         showLoadingIndicator()
///     case .reconnecting(let attempt):
///         showReconnecting(attempt: attempt)
///     case .failed(let error):
///         showError(error)
///     case .disconnected:
///         showDisconnectedUI()
///     }
/// }
/// ```
public enum ConnectionState: Sendable, Equatable {
    /// Indicates the session is disconnected.
    case disconnected
    /// Indicates the session is attempting to connect.
    case connecting
    /// Indicates the session is successfully connected.
    case connected
    /// Indicates the session is attempting to reconnect after a failure.
    case reconnecting(attempt: Int)
    /// Indicates the session connection failed with a specific error.
    case failed(MeshTransportError)
}

/// Represents errors that can occur at the transport layer.
///
/// These errors indicate problems with the underlying transport connection
/// (e.g., Bluetooth LE), rather than protocol-level errors.
public enum MeshTransportError: Error, Sendable, Equatable {
    /// Indicates the transport is not connected.
    case notConnected
    /// Indicates a connection attempt failed with a specific reason.
    case connectionFailed(String)
    /// Indicates sending data failed with a specific reason.
    case sendFailed(String)
    /// Indicates the target device could not be found.
    case deviceNotFound
    /// Indicates a required service was not found on the device.
    case serviceNotFound
    /// Indicates a required characteristic was not found on the device.
    case characteristicNotFound
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
                "type": info.type,
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
