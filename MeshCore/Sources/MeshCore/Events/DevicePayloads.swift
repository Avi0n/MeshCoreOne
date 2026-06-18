import Foundation

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

/// Auto-add configuration received from the device.
///
/// Bundles the bitmask (which node types to auto-add) with the max hops filter.
public struct AutoAddConfig: Sendable, Equatable {
    /// ``bitmask`` bit: overwrite the oldest non-favorite node when storage is full.
    public static let overwriteOldestBit: UInt8 = 0x01
    /// ``bitmask`` bit: auto-add Chat (contact) nodes.
    public static let contactsBit: UInt8 = 0x02
    /// ``bitmask`` bit: auto-add Repeater nodes.
    public static let repeatersBit: UInt8 = 0x04
    /// ``bitmask`` bit: auto-add Room Server nodes.
    public static let roomServersBit: UInt8 = 0x08
    /// ``bitmask`` bit: auto-add Sensor nodes.
    public static let sensorsBit: UInt8 = 0x10

    /// Bitmask controlling auto-add behavior; see the `*Bit` constants for the wire format.
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
