import Foundation
import MeshCore

/// Decrypts RX-log rows against caller-supplied key material, synchronously and without a session.
///
/// The RX log lists raw received packets, so a row is decrypted long after the radio delivered it,
/// and again every time a channel secret, contact key, or the device key arrives. That re-derivation
/// is a pure function of the row and the key material, which is why this entry point takes neither a
/// session nor a callback: the caller blocks for the length of a few AES blocks and gets the whole
/// batch back.
///
/// Batching is the point of the shape. A late secret re-derives every held row at once, so the
/// caller sends the whole log in one call rather than paying a boundary crossing per row.
enum RxLogDecryptService {
  /// The channel-hash byte the wire places in front of a group payload's MAC.
  private static let channelHashPrefixSize = 1

  /// One AES block, the smallest ciphertext a message can occupy.
  private static let aesBlockSize = 16

  /// Decrypts every row in an encoded request, returning an encoded response. A malformed request
  /// answers with a failure envelope rather than trapping: the boundary has no other channel to
  /// report on, and a caller that sent nonsense must not take the process down with it.
  static func decrypt(requestBytes: [UInt8]) -> [UInt8] {
    do {
      let rows = try RxLogDecryptCodec.decode(request: requestBytes)
      return RxLogDecryptCodec.encode(results: rows.map(decrypt(row:))).serialized()
    } catch {
      let classified = BridgeErrorCode.classify(error)
      return RxLogDecryptCodec.encode(failure: classified.code, message: classified.message).serialized()
    }
  }

  static func decrypt(row: RxLogDecryptCodec.Row) -> RxLogDecryptCodec.RowResult {
    switch row {
    case let .channel(payload, keys):
      return decryptChannel(payload: payload, keys: keys)
    case let .direct(payload, privateKey, keys):
      return decryptDirect(payload: payload, privateKey: privateKey, keys: keys)
    }
  }

  private static func decryptChannel(payload: Data, keys: [RxLogDecryptCodec.Key]) -> RxLogDecryptCodec.RowResult {
    guard payload.count >= channelHashPrefixSize + ChannelCrypto.macSize + aesBlockSize else {
      return .payloadTooShort
    }
    let encrypted = Data(payload.dropFirst(channelHashPrefixSize))
    for key in keys {
      if case let .success(timestamp, _, text) = ChannelCrypto.decrypt(payload: encrypted, secret: key.bytes) {
        return .success(keyIndex: key.index, senderTimestamp: timestamp, text: text)
      }
    }
    return .noMatchingKey
  }

  private static func decryptDirect(
    payload: Data,
    privateKey: Data,
    keys: [RxLogDecryptCodec.Key]
  ) -> RxLogDecryptCodec.RowResult {
    guard payload.count >= DirectMessageCrypto.minPacketSize else { return .payloadTooShort }
    for key in keys {
      let result = DirectMessageCrypto.decrypt(
        payload: payload,
        myPrivateKey: privateKey,
        senderPublicKey: key.bytes
      )
      if case let .success(timestamp, _, text) = result {
        return .success(keyIndex: key.index, senderTimestamp: timestamp, text: text)
      }
    }
    return .noMatchingKey
  }
}
