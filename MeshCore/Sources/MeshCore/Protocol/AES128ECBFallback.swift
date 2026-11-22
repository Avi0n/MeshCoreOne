#if !canImport(CommonCrypto)
  import Crypto
  import CryptoExtras
  import Foundation

  /// AES-128-ECB decryption built from CryptoExtras AES-CBC where CommonCrypto is absent.
  /// ECB decrypts each 16-byte block on its own. CBC over one block with a zero IV reduces
  /// to ECB for that block, because the plaintext is the decrypted block XORed with zero.
  ///
  /// Guards match the CommonCrypto path. A short key or a non-block length returns nil,
  /// and empty input returns empty data.
  enum AES128ECBFallback {
    /// AES-128 key length in bytes.
    static let keySize = 16

    /// AES block size in bytes.
    static let blockSize = 16

    static func decrypt(ciphertext: Data, key: Data) -> Data? {
      guard key.count >= keySize else { return nil }
      guard ciphertext.count % blockSize == 0 else { return nil }

      let symmetricKey = SymmetricKey(data: key.prefix(keySize))
      guard let zeroIV = try? AES._CBC.IV(ivBytes: Array(repeating: UInt8(0), count: blockSize)) else {
        return nil
      }

      var plaintext = Data()
      plaintext.reserveCapacity(ciphertext.count)

      var index = ciphertext.startIndex
      while index < ciphertext.endIndex {
        let blockEnd = ciphertext.index(index, offsetBy: blockSize)
        guard let block = try? AES._CBC.decrypt(
          ciphertext[index..<blockEnd],
          using: symmetricKey,
          iv: zeroIV,
          noPadding: true
        ) else {
          return nil
        }
        plaintext.append(block)
        index = blockEnd
      }

      return plaintext
    }
  }
#endif
