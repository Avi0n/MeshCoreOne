/// Defines the command codes sent from the app to the mesh device.
public enum CommandCode: UInt8, Sendable {
    /// Starts the application session on the device.
    case appStart = 0x01
    /// Sends a direct message to a specific contact.
    case sendMessage = 0x02
    /// Sends a message to a specific channel index.
    case sendChannelMessage = 0x03
    /// Requests the list of stored contacts from the device.
    case getContacts = 0x04
    /// Requests the current system time from the device.
    case getTime = 0x05
    /// Sets the system time on the device.
    case setTime = 0x06
    /// Triggers a manual advertisement broadcast.
    case sendAdvertisement = 0x07
    /// Sets the device name.
    case setName = 0x08
    /// Updates an existing contact record.
    case updateContact = 0x09
    /// Fetches the next available message from the device's buffer.
    case getMessage = 0x0A
    /// Configures the radio parameters.
    case setRadio = 0x0B
    /// Sets the transmit power level.
    case setTxPower = 0x0C
    /// Resets the routing path to a specific contact.
    case resetPath = 0x0D
    /// Sets the GPS coordinates for the device.
    case setCoordinates = 0x0E
    /// Removes a contact from the device.
    case removeContact = 0x0F
    /// Shares a contact with other nodes in the mesh.
    case shareContact = 0x10
    /// Generates a URI for exporting a contact.
    case exportContact = 0x11
    /// Imports a contact from a provided URI or data.
    case importContact = 0x12
    /// Reboots the device hardware.
    case reboot = 0x13
    /// Requests battery and storage status.
    case getBattery = 0x14
    /// Sets internal tuning parameters.
    case setTuning = 0x15
    /// Queries hardware capabilities and version info.
    case deviceQuery = 0x16
    /// Exports the node's private key.
    case exportPrivateKey = 0x17
    /// Imports a private key to the node.
    case importPrivateKey = 0x18
    /// Sends raw data through the mesh.
    case sendRawData = 0x19
    /// Initiates a remote node login.
    case sendLogin = 0x1A
    /// Requests status information from a remote node.
    case sendStatusRequest = 0x1B
    /// Checks if a connection exists to a specific node.
    case hasConnection = 0x1C
    /// Logs out from a remote node.
    case sendLogout = 0x1D
    /// Retrieves a contact by public key.
    case getContactByKey = 0x1E
    /// Requests channel configuration for a specific index.
    case getChannel = 0x1F
    /// Sets channel configuration for a specific index.
    case setChannel = 0x20
    /// Begins a cryptographic signing operation.
    case signStart = 0x21
    /// Provides data for a signing operation.
    case signData = 0x22
    /// Completes a signing operation and retrieves the signature.
    case signFinish = 0x23
    /// Sends trace data for debugging.
    case sendTrace = 0x24
    /// Sets the Bluetooth pairing PIN.
    case setDevicePin = 0x25
    /// Sets miscellaneous device parameters.
    case setOtherParams = 0x26
    /// Requests self-telemetry data.
    case getSelfTelemetry = 0x27
    /// Requests current custom variable values.
    case getCustomVars = 0x28
    /// Sets a custom variable value.
    case setCustomVar = 0x29
    /// Retrieves the advertisement path to a contact.
    case getAdvertPath = 0x2A
    /// Retrieves tuning parameters.
    case getTuningParams = 0x2B
    /// Initiates a binary data request.
    case binaryRequest = 0x32
    /// Performs a factory reset of the device.
    case factoryReset = 0x33
    /// Initiates a path discovery process to a remote node.
    case pathDiscovery = 0x34
    /// Sets the current (session-scoped) flood routing key.
    ///
    /// Firmware v1.15.0 renamed the on-device symbol `CMD_SET_FLOOD_SCOPE` to
    /// `CMD_SET_FLOOD_SCOPE_KEY`; the opcode is unchanged. Use ``setDefaultFloodScope``
    /// to persist a scope across reboots (Firmware v11+).
    case setFloodScope = 0x36
    /// Sends raw control data.
    case sendControlData = 0x37
    /// Requests device statistics.
    case getStats = 0x38
    /// Sends an anonymous request to a remote node.
    case sendAnonReq = 0x39
    /// Sets the auto-add configuration bitmask.
    case setAutoAddConfig = 0x3A
    /// Gets the current auto-add configuration bitmask.
    case getAutoAddConfig = 0x3B
    /// Gets the allowed frequency ranges for client repeat mode (v9+).
    case getRepeatFreq = 0x3C
    /// Sets the path hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
    case setPathHashMode = 0x3D
    /// Sends a binary datagram to a channel. Firmware v11+ (MeshCore v1.15.0+).
    case sendChannelData = 0x3E
    /// Sets the persisted default flood scope (name + 16-byte key). Firmware v11+ (MeshCore v1.15.0+).
    case setDefaultFloodScope = 0x3F
    /// Gets the persisted default flood scope. Firmware v11+ (MeshCore v1.15.0+).
    case getDefaultFloodScope = 0x40
}

/// Defines the response codes received from the mesh device.
public enum ResponseCode: UInt8, Sendable {
    /// Command executed successfully.
    case ok = 0x00
    /// Command execution failed.
    case error = 0x01
    /// Indicates the start of a contact list transfer.
    case contactStart = 0x02
    /// Contains a single contact record.
    case contact = 0x03
    /// Indicates the end of a contact list transfer.
    case contactEnd = 0x04
    /// Contains device configuration info.
    case selfInfo = 0x05
    /// Confirms that a message was successfully queued for transmission.
    case messageSent = 0x06
    /// Indicates a direct message was received from a contact.
    case contactMessageReceived = 0x07
    /// Indicates a message was received on a channel.
    case channelMessageReceived = 0x08
    /// Contains the current system time.
    case currentTime = 0x09
    /// Indicates no more messages are available in the buffer.
    case noMoreMessages = 0x0A
    /// Contains a contact export URI.
    case contactURI = 0x0B
    /// Contains battery and storage status.
    case battery = 0x0C
    /// Contains hardware and firmware version info.
    case deviceInfo = 0x0D
    /// Contains the exported private key.
    case privateKey = 0x0E
    /// Indicates a feature is disabled.
    case disabled = 0x0F
    /// Indicates a V3 format direct message was received.
    case contactMessageReceivedV3 = 0x10
    /// Indicates a V3 format channel message was received.
    case channelMessageReceivedV3 = 0x11
    /// Contains channel configuration details.
    case channelInfo = 0x12
    /// Confirms the start of a signing operation.
    case signStart = 0x13
    /// Contains the generated signature.
    case signature = 0x14
    /// Contains custom variable values.
    case customVars = 0x15
    /// Contains advertisement path information.
    case advertPath = 0x16
    /// Contains tuning parameters.
    case tuningParams = 0x17
    /// Contains device statistics.
    case stats = 0x18
    /// Contains the auto-add configuration bitmask.
    case autoAddConfig = 0x19
    /// Contains the allowed frequency ranges for client repeat mode (v9+).
    case allowedRepeatFreq = 0x1A
    /// A binary datagram was received on a channel. Firmware v11+ (MeshCore v1.15.0+).
    case channelDataReceived = 0x1B
    /// Contains the persisted default flood scope. Firmware v11+ (MeshCore v1.15.0+).
    case defaultFloodScope = 0x1C

    // Push notifications (0x80+)
    /// Indicates a node advertisement was received.
    case advertisement = 0x80
    /// Indicates a routing path update occurred.
    case pathUpdate = 0x81
    /// Indicates a message acknowledgment was received.
    case ack = 0x82
    /// Indicates messages are waiting to be fetched.
    case messagesWaiting = 0x83
    /// Contains raw protocol data.
    case rawData = 0x84
    /// Indicates a remote login was successful.
    case loginSuccess = 0x85
    /// Indicates a remote login failed.
    case loginFailed = 0x86
    /// Contains a response to a status request.
    case statusResponse = 0x87
    /// Contains raw log output.
    case logData = 0x88
    /// Contains trace debugging data.
    case traceData = 0x89
    /// Indicates a new node was discovered.
    case newAdvertisement = 0x8A
    /// Contains telemetry data from a remote node.
    case telemetryResponse = 0x8B
    /// Contains binary data requested from a remote node.
    case binaryResponse = 0x8C
    /// Contains the result of a path discovery operation.
    case pathDiscoveryResponse = 0x8D
    /// Contains raw control data.
    case controlData = 0x8E
    /// Indicates a contact was automatically deleted (overwritten by auto-add).
    case contactDeleted = 0x8F
    /// Indicates the device's contact storage is full.
    case contactsFull = 0x90
}

/// Defines the types of binary requests used in asynchronous operations.
public enum BinaryRequestType: UInt8, Sendable {
    /// Requests status information.
    case status = 0x01
    /// Sends a keep-alive signal.
    case keepAlive = 0x02
    /// Requests telemetry data.
    case telemetry = 0x03
    /// Requests Min/Max/Average data.
    case mma = 0x04
    /// Requests Access Control List data.
    case acl = 0x05
    /// Requests the list of visible neighbor nodes.
    case neighbours = 0x06
    /// Requests owner information from a repeater.
    case ownerInfo = 0x07
}

/// Defines the types of anonymous requests that can be sent to remote nodes.
public enum AnonRequestType: UInt8, Sendable {
    /// Requests the list of allowed regions from a repeater.
    case regions = 0x01
    /// Requests owner information from a repeater.
    case owner = 0x02
    /// Requests basic information (clock, features) from a repeater.
    case basic = 0x03
}

/// Defines the types of control data packets.
public enum ControlType: UInt8, Sendable {
    /// Requests node discovery.
    case nodeDiscoverRequest = 0x80
    /// Provides a response to a node discovery request.
    case nodeDiscoverResponse = 0x90
}

/// Defines the categories of statistics that can be requested from the device.
public enum StatsType: UInt8, Sendable {
    /// Core system statistics.
    case core = 0x00
    /// Radio hardware statistics.
    case radio = 0x01
    /// Packet processing statistics.
    case packets = 0x02
}

/// Defines the encoding type for messages.
public enum TextType: UInt8, Sendable {
    /// Plain UTF-8 text.
    case plainText = 0x00
    /// Raw binary data.
    case binary = 0x01
    /// Cryptographically signed message.
    case signed = 0x02
}

// MARK: - Response Categories

/// Categorizes response codes for routing to specialized domain parsers.
public enum ResponseCategory: Sendable {
    /// Basic success or error responses.
    case simple
    /// Device-related status and configuration.
    case device
    /// Contact list management responses.
    case contact
    /// Messaging and buffer status.
    case message
    /// Asynchronous push notifications.
    case push
    /// Remote authentication responses.
    case login
    /// Cryptographic signing results.
    case signing
    /// Miscellaneous data and logs.
    case misc
}

extension ResponseCode {
    /// Determines the category for this response code to facilitate routing.
    public var category: ResponseCategory {
        switch self {
        case .ok, .error:
            return .simple
        case .selfInfo, .deviceInfo, .battery, .currentTime, .privateKey, .disabled, .advertPath, .tuningParams,
             .autoAddConfig, .allowedRepeatFreq, .defaultFloodScope:
            return .device
        case .contactStart, .contact, .contactEnd, .contactURI:
            return .contact
        case .messageSent, .contactMessageReceived, .contactMessageReceivedV3,
             .channelMessageReceived, .channelMessageReceivedV3, .channelDataReceived, .noMoreMessages:
            return .message
        case .advertisement, .pathUpdate, .ack, .messagesWaiting, .newAdvertisement,
             .statusResponse, .telemetryResponse, .binaryResponse, .pathDiscoveryResponse,
             .controlData, .contactDeleted, .contactsFull:
            return .push
        case .loginSuccess, .loginFailed:
            return .login
        case .signStart, .signature:
            return .signing
        case .stats, .customVars, .channelInfo, .rawData, .logData, .traceData:
            return .misc
        }
    }
}
