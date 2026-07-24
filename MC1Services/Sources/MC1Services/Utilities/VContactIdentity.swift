import CryptoKit
import Foundation

/// Identifies ZephCore's loopback admin "V-contact" (chat CLI over BLE/USB).
///
/// Firmware derives the public key as `SHA256("zc-vcontact" || self_pub_key)` with no private
/// key. The contact is a virtual GET_CONTACTS tail entry and is never a real contact-table slot.
/// MC1 uses this helper for capacity bookkeeping and to disable remove paths that would
/// `CMD_REMOVE` and turn the firmware feature off.
public enum VContactIdentity {
  /// Salt used by ZephCore `CompanionMesh::begin()` (11 bytes, no NUL terminator).
  public static let salt = Data("zc-vcontact".utf8)

  /// Derives the V-contact public key for a companion's own public key.
  ///
  /// - Parameter selfPublicKey: The radio's 32-byte Ed25519 public key.
  /// - Returns: The 32-byte derived key, or `nil` if `selfPublicKey` is not 32 bytes.
  public static func publicKey(forSelfPublicKey selfPublicKey: Data) -> Data? {
    guard selfPublicKey.count == ProtocolLimits.publicKeySize else { return nil }
    var input = Data()
    input.reserveCapacity(salt.count + ProtocolLimits.publicKeySize)
    input.append(salt)
    input.append(selfPublicKey)
    return Data(SHA256.hash(data: input))
  }

  /// Whether `publicKey` is the V-contact for `selfPublicKey`.
  ///
  /// Fail-open: returns `false` if either key is not 32 bytes so real overwrite cleanup
  /// is never skipped due to a missing identity.
  public static func isVContact(publicKey: Data, selfPublicKey: Data) -> Bool {
    guard publicKey.count == ProtocolLimits.publicKeySize else { return false }
    return publicKey == Self.publicKey(forSelfPublicKey: selfPublicKey)
  }
}
