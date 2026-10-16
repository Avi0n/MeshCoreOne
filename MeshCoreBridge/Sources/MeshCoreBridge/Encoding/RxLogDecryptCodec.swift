import Foundation
import MeshCore

/// The request/response codec for the synchronous RX-log decrypt entry point.
///
/// Unlike every other boundary value, this one travels in both directions in the same call: the
/// caller hands down a batch of rows plus the key material to try, and gets one outcome per row
/// back. Both envelopes are the versioned JSON shape `ENCODING.md` documents, and both are frozen
/// by the golden files under `fixtures/bridge/rxlog/`.
enum RxLogDecryptCodec {
  static let requestKind = "rxLogDecryptRequest"
  static let responseKind = "rxLogDecrypt"
  static let failureKind = "rxLogDecryptFailure"

  static let channelRowKind = "channel"
  static let directRowKind = "direct"

  /// A candidate key paired with the caller's own identifier for it: a channel slot index for a
  /// channel row, a position in the candidate list for a direct-message row. The identifier comes
  /// straight back on a match so the caller never has to re-derive which key worked.
  struct Key {
    let index: Int64
    let bytes: Data
  }

  /// One row to decrypt. The payload is the packet payload verbatim, framing byte included; the
  /// framing is this side's business, so no caller has to know the wire layout to ask.
  enum Row {
    case channel(payload: Data, keys: [Key])
    case direct(payload: Data, privateKey: Data, keys: [Key])
  }

  /// One row's outcome.
  enum RowResult {
    case success(keyIndex: Int64, senderTimestamp: UInt32, text: String?)
    case noMatchingKey
    case payloadTooShort
  }

  // MARK: - Request decoding

  static func decode(request bytes: [UInt8]) throws -> [Row] {
    guard let object = (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any] else {
      throw MeshCoreError.invalidInput("rx-log decrypt request is not a JSON object")
    }
    guard (object[BridgeProtocol.versionKey] as? NSNumber)?.int64Value == BridgeProtocol.version else {
      throw MeshCoreError.invalidInput("unsupported rx-log decrypt request version")
    }
    guard object[BridgeProtocol.kindKey] as? String == requestKind else {
      throw MeshCoreError.invalidInput("rx-log decrypt request kind must be \"\(requestKind)\"")
    }
    guard let payload = object[BridgeProtocol.payloadKey] as? [String: Any],
          let rows = payload["rows"] as? [[String: Any]]
    else {
      throw MeshCoreError.invalidInput("rx-log decrypt request missing payload.rows")
    }
    return try rows.map(decode(row:))
  }

  private static func decode(row: [String: Any]) throws -> Row {
    let keys = try (row["keys"] as? [[String: Any]] ?? []).map(decode(key:))
    let payload = try data(row, "payload")
    switch row["kind"] as? String {
    case channelRowKind:
      return .channel(payload: payload, keys: keys)
    case directRowKind:
      return .direct(payload: payload, privateKey: try data(row, "privateKey"), keys: keys)
    case let other:
      throw MeshCoreError.invalidInput("unknown rx-log decrypt row kind \"\(other ?? "nil")\"")
    }
  }

  private static func decode(key: [String: Any]) throws -> Key {
    guard let index = (key["index"] as? NSNumber)?.int64Value else {
      throw MeshCoreError.invalidInput("rx-log decrypt key.index missing or not a number")
    }
    return Key(index: index, bytes: try data(key, "bytes"))
  }

  private static func data(_ object: [String: Any], _ key: String) throws -> Data {
    guard let text = object[key] as? String, let value = Data(base64Encoded: text) else {
      throw MeshCoreError.invalidInput("rx-log decrypt \(key) missing or not base64")
    }
    return value
  }

  // MARK: - Response encoding

  static func encode(results: [RowResult]) -> JSONValue {
    BridgeProtocol.envelope(
      kind: responseKind,
      payload: .object(["results": .array(results.map(encode(result:)))])
    )
  }

  static func encode(failure code: BridgeErrorCode, message: String) -> JSONValue {
    BridgeProtocol.envelope(
      kind: failureKind,
      payload: .object(["errorCode": .int(code.rawValue), "message": .string(message)])
    )
  }

  private static func encode(result: RowResult) -> JSONValue {
    switch result {
    case let .success(keyIndex, senderTimestamp, text):
      return .object([
        "keyIndex": .int(keyIndex),
        "outcome": .string("success"),
        "senderTimestamp": .int(senderTimestamp),
        "text": .optionalString(text)
      ])
    case .noMatchingKey:
      return emptyResult(outcome: "noMatchingKey")
    case .payloadTooShort:
      return emptyResult(outcome: "payloadTooShort")
    }
  }

  private static func emptyResult(outcome: String) -> JSONValue {
    .object(["keyIndex": .null, "outcome": .string(outcome), "senderTimestamp": .null, "text": .null])
  }
}
