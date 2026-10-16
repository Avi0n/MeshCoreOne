import CommonCrypto
import CryptoKit
import Foundation
import MeshCore
import Testing
@testable import MeshCoreBridgeKit

/// Freezes the RX-log decrypt boundary against real vectors.
///
/// The channel row is a packet captured off a live radio; the direct row is built from the RFC 7748
/// §6.1 Alice/Bob key pair, so its shared secret is a published constant rather than something this
/// code derived for itself. Neither vector comes from `ChannelCrypto` or `DirectMessageCrypto` — the
/// generator below encrypts with CommonCrypto and CryptoKit directly, so a fault in the code under
/// test cannot make its own fixture agree with it.
struct RxLogDecryptTests {
  // MARK: - Vectors

  /// Channel secret and payload captured from a live radio, with its known plaintext.
  static let channelSecret = hex("8b3387e9c5cdea6ac9e5edbaa115cd72")
  static let channelPayload = hex("a0e6215565cf0555d9e4a5e4824dc70018a6021e8232bb0509500bdf22a90e97978f")
  static let channelPlaintext = "Alice: Hello mesh!"

  /// The channel-hash byte the wire puts in front of the payload. Its value does not take part in
  /// the decrypt; carrying it proves the framing is stripped below the boundary.
  static let channelHashByte: UInt8 = 0xAB

  /// A secret that decrypts nothing, so the fixture exercises the try-every-key walk.
  static let wrongSecret = Data(repeating: 0x5A, count: 16)

  /// RFC 7748 §6.1: Bob's private key, Alice's public key, and the shared secret they agree on.
  static let bobPrivateKey = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
  static let alicePublicKey = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
  static let rfc7748SharedSecret = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

  static let directTimestamp: UInt32 = 1_700_000_000
  static let directPlaintext = "Hello mesh!"
  static let directDestHash: UInt8 = 0x11
  static let directSrcHash: UInt8 = 0x22

  // MARK: - Sample request

  /// The four rows the fixtures cover: a channel hit behind a miss, a direct hit, a payload too
  /// short to hold one AES block, and a channel row no key opens.
  static var sampleRows: [RxLogDecryptCodec.Row] {
    [
      .channel(
        payload: Data([channelHashByte]) + channelPayload,
        keys: [
          RxLogDecryptCodec.Key(index: 1, bytes: wrongSecret),
          RxLogDecryptCodec.Key(index: 3, bytes: channelSecret)
        ]
      ),
      .direct(
        payload: directPayload,
        privateKey: bobPrivateKey,
        keys: [RxLogDecryptCodec.Key(index: 0, bytes: alicePublicKey)]
      ),
      .channel(payload: Data([channelHashByte, 0x00, 0x01]), keys: []),
      .channel(
        payload: Data([channelHashByte]) + channelPayload,
        keys: [RxLogDecryptCodec.Key(index: 7, bytes: wrongSecret)]
      )
    ]
  }

  // MARK: - Tests

  @Test("the sample request encodes to its golden file, and that file decodes back")
  func requestGolden() throws {
    let bytes = Self.encode(request: Self.sampleRows).serialized()
    try GoldenFixtureTests.compareOrRegenerate(bytes, file: Self.fixture("decrypt-request.json"))
    let stored = try Data(contentsOf: Self.fixture("decrypt-request.json"))
    let rows = try RxLogDecryptCodec.decode(request: Array(Self.trimmingNewline(stored)))
    #expect(rows.count == Self.sampleRows.count)
  }

  @Test("the sample request decrypts to its golden response")
  func responseGolden() throws {
    let request = Self.encode(request: Self.sampleRows).serialized()
    let bytes = RxLogDecryptService.decrypt(requestBytes: request)
    try GoldenFixtureTests.compareOrRegenerate(bytes, file: Self.fixture("decrypt-response.json"))
  }

  @Test("the live-radio channel vector decrypts to its known plaintext")
  func channelVectorDecrypts() throws {
    let rows = try RxLogDecryptCodec.decode(request: Self.encode(request: Self.sampleRows).serialized())
    guard case let .success(keyIndex, _, text) = RxLogDecryptService.decrypt(row: rows[0]) else {
      Issue.record("channel row did not decrypt")
      return
    }
    #expect(keyIndex == 3, "the matching key's own index must come back, not its position")
    #expect(text == Self.channelPlaintext)
  }

  @Test("the RFC 7748 direct vector decrypts to its known plaintext")
  func directVectorDecrypts() throws {
    let rows = try RxLogDecryptCodec.decode(request: Self.encode(request: Self.sampleRows).serialized())
    guard case let .success(keyIndex, timestamp, text) = RxLogDecryptService.decrypt(row: rows[1]) else {
      Issue.record("direct row did not decrypt")
      return
    }
    #expect(keyIndex == 0)
    #expect(timestamp == Self.directTimestamp)
    #expect(text == Self.directPlaintext)
  }

  @Test("a malformed request answers with a failure envelope rather than trapping")
  func malformedRequestFails() {
    let bytes = RxLogDecryptService.decrypt(requestBytes: Array("not json".utf8))
    let text = String(decoding: bytes, as: UTF8.self)
    #expect(text.contains("\"kind\":\"rxLogDecryptFailure\""))
    #expect(text.contains("\"errorCode\":\(BridgeErrorCode.invalidInput.rawValue)"))
  }

  @Test("an unknown row kind fails the whole batch")
  func unknownRowKindFails() {
    let request = """
    {"kind":"rxLogDecryptRequest","payload":{"rows":[{"keys":[],"kind":"telepathy","payload":"AAAA"}]},"v":1}
    """
    let text = String(decoding: RxLogDecryptService.decrypt(requestBytes: Array(request.utf8)), as: UTF8.self)
    #expect(text.contains("\"kind\":\"rxLogDecryptFailure\""))
  }

  // MARK: - Generator

  /// Builds the direct-message payload `[destHash][srcHash][MAC:2][ciphertext]` from the RFC 7748
  /// shared secret, using CommonCrypto and CryptoKit rather than the code under test.
  static var directPayload: Data {
    var plaintext = Data()
    withUnsafeBytes(of: directTimestamp.littleEndian) { plaintext.append(contentsOf: $0) }
    plaintext.append(0) // typeAttempt
    plaintext.append(contentsOf: Array(directPlaintext.utf8))
    let ciphertext = encryptAES128ECB(plaintext: plaintext, key: rfc7748SharedSecret)
    let mac = HMAC<SHA256>.authenticationCode(
      for: ciphertext,
      using: SymmetricKey(data: rfc7748SharedSecret)
    )
    return Data([directDestHash, directSrcHash]) + Data(mac.prefix(2)) + ciphertext
  }

  /// Zero-pads to the AES block size and encrypts in ECB mode, which is how the firmware builds a
  /// message body. Traps on failure: a broken generator must never silently produce a fixture.
  static func encryptAES128ECB(plaintext: Data, key: Data) -> Data {
    let blockSize = kCCBlockSizeAES128
    var padded = plaintext
    while padded.count % blockSize != 0 { padded.append(0) }
    var encrypted = Data(count: padded.count)
    var written: size_t = 0
    let status = encrypted.withUnsafeMutableBytes { out in
      padded.withUnsafeBytes { input in
        key.prefix(kCCKeySizeAES128).withUnsafeBytes { keyBytes in
          CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode),
            keyBytes.baseAddress, kCCKeySizeAES128,
            nil,
            input.baseAddress, padded.count,
            out.baseAddress, padded.count,
            &written
          )
        }
      }
    }
    precondition(status == kCCSuccess, "fixture generator could not encrypt")
    return Data(encrypted.prefix(written))
  }

  /// The request encoder. It lives in the test rather than the bridge because Kotlin is the producer
  /// of record for this direction; this side only has to agree with it byte for byte.
  static func encode(request rows: [RxLogDecryptCodec.Row]) -> JSONValue {
    BridgeProtocol.envelope(
      kind: RxLogDecryptCodec.requestKind,
      payload: .object(["rows": .array(rows.map(encode(row:)))])
    )
  }

  private static func encode(row: RxLogDecryptCodec.Row) -> JSONValue {
    switch row {
    case let .channel(payload, keys):
      return .object([
        "keys": .array(keys.map(encode(key:))),
        "kind": .string(RxLogDecryptCodec.channelRowKind),
        "payload": .data(payload),
        "privateKey": .null
      ])
    case let .direct(payload, privateKey, keys):
      return .object([
        "keys": .array(keys.map(encode(key:))),
        "kind": .string(RxLogDecryptCodec.directRowKind),
        "payload": .data(payload),
        "privateKey": .data(privateKey)
      ])
    }
  }

  private static func encode(key: RxLogDecryptCodec.Key) -> JSONValue {
    .object(["bytes": .data(key.bytes), "index": .int(key.index)])
  }

  // MARK: - Helpers

  static func fixture(_ name: String) -> URL {
    GoldenFixtureTests.fixturesDir.appendingPathComponent("rxlog").appendingPathComponent(name)
  }

  static func trimmingNewline(_ data: Data) -> Data {
    data.last == 0x0A ? data.dropLast() : data
  }

  static func hex(_ text: String) -> Data {
    var bytes = [UInt8]()
    var index = text.startIndex
    while index < text.endIndex {
      let next = text.index(index, offsetBy: 2)
      bytes.append(UInt8(text[index ..< next], radix: 16)!)
      index = next
    }
    return Data(bytes)
  }
}
