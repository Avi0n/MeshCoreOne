import Foundation

public extension String {
  /// Generates a deterministic UUID from this string using djb2 hash (non-cryptographic).
  ///
  /// Uses two independent hash streams (even/odd bytes) to generate 16 bytes for UUID construction.
  /// The same input string always produces the same UUID output.
  var stableUUID: UUID {
    var hash1: UInt64 = 5381
    var hash2: UInt64 = 5381

    for (index, byte) in utf8.enumerated() {
      if index.isMultiple(of: 2) {
        hash1 = ((hash1 << 5) &+ hash1) &+ UInt64(byte)
      } else {
        hash2 = ((hash2 << 5) &+ hash2) &+ UInt64(byte)
      }
    }

    return UUID(uuid: (
      UInt8(truncatingIfNeeded: hash1),
      UInt8(truncatingIfNeeded: hash1 >> 8),
      UInt8(truncatingIfNeeded: hash1 >> 16),
      UInt8(truncatingIfNeeded: hash1 >> 24),
      UInt8(truncatingIfNeeded: hash1 >> 32),
      UInt8(truncatingIfNeeded: hash1 >> 40),
      UInt8(truncatingIfNeeded: hash1 >> 48),
      UInt8(truncatingIfNeeded: hash1 >> 56),
      UInt8(truncatingIfNeeded: hash2),
      UInt8(truncatingIfNeeded: hash2 >> 8),
      UInt8(truncatingIfNeeded: hash2 >> 16),
      UInt8(truncatingIfNeeded: hash2 >> 24),
      UInt8(truncatingIfNeeded: hash2 >> 32),
      UInt8(truncatingIfNeeded: hash2 >> 40),
      UInt8(truncatingIfNeeded: hash2 >> 48),
      UInt8(truncatingIfNeeded: hash2 >> 56)
    ))
  }
}
