import Foundation

/// Represents login success information.
public struct LoginInfo: Sendable, Equatable {
  /// The permissions granted after successful login.
  public let permissions: UInt8
  /// A boolean indicating whether the user has administrator privileges.
  public let isAdmin: Bool
  /// The public key prefix of the node where the login occurred.
  public let publicKeyPrefix: Data
  /// The remote node's RTC reading carried in the login response, when present.
  /// Comparing it against local time exposes clock drift that silently breaks
  /// the node's timestamp-based replay protection.
  public let serverTime: Date?

  /// Initializes a new login information object.
  ///
  /// - Parameters:
  ///   - permissions: The granted permissions.
  ///   - isAdmin: Admin status.
  ///   - publicKeyPrefix: The node's public key prefix.
  ///   - serverTime: The remote node's clock at login, if the response carried it.
  public init(permissions: UInt8, isAdmin: Bool, publicKeyPrefix: Data, serverTime: Date? = nil) {
    self.permissions = permissions
    self.isAdmin = isAdmin
    self.publicKeyPrefix = publicKeyPrefix
    self.serverTime = serverTime
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
