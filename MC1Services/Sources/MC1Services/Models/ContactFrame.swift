import Foundation
import MeshCore

/// Contact information frame from device
public struct ContactFrame: Sendable, Equatable {
  public let publicKey: Data
  public let type: ContactType
  /// The raw 1-byte type value as it appears on the wire. Normally equal to `type.rawValue`;
  /// preserved separately so a contact carrying a type byte not modeled by ``ContactType``
  /// round-trips to the device verbatim instead of being coerced.
  public let typeRawValue: UInt8
  public let flags: UInt8
  public let outPathLength: UInt8
  public let outPath: Data
  public let name: String
  public let lastAdvertTimestamp: UInt32
  public let latitude: Double
  public let longitude: Double
  public let lastModified: UInt32

  public init(
    publicKey: Data,
    type: ContactType,
    typeRawValue: UInt8? = nil,
    flags: UInt8,
    outPathLength: UInt8,
    outPath: Data,
    name: String,
    lastAdvertTimestamp: UInt32,
    latitude: Double,
    longitude: Double,
    lastModified: UInt32
  ) {
    self.publicKey = publicKey
    self.type = type
    self.typeRawValue = typeRawValue ?? type.rawValue
    self.flags = flags
    self.outPathLength = outPathLength
    self.outPath = outPath
    self.name = name
    self.lastAdvertTimestamp = lastAdvertTimestamp
    self.latitude = latitude
    self.longitude = longitude
    self.lastModified = lastModified
  }
}
