import Foundation

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
