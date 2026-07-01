import Foundation

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
      hashBytes = Data([h])
    } else {
      hashBytes = nil
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
