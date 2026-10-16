import CryptoKit
import Foundation

/// Utilities for deriving stable device identity from cryptographic keys.
enum DeviceIdentity {
  /// Derives a stable UUID from a device's Ed25519 public key.
  /// Uses SHA256 hash of the public key, taking first 16 bytes as UUID.
  static func deriveUUID(from publicKey: Data) -> UUID {
    let hash = SHA256.hash(data: publicKey)
    let hashBytes = Array(hash)

    return UUID(uuid: (
      hashBytes[0], hashBytes[1], hashBytes[2], hashBytes[3],
      hashBytes[4], hashBytes[5], hashBytes[6], hashBytes[7],
      hashBytes[8], hashBytes[9], hashBytes[10], hashBytes[11],
      hashBytes[12], hashBytes[13], hashBytes[14], hashBytes[15]
    ))
  }
}
