import Foundation
import MeshCore

/// The request/response codec for the synchronous flood-region resolve entry point.
///
/// Like the RX-log decrypt codec, this one travels in both directions in the same call: the caller
/// hands down its known region names plus the packets to attribute, and gets one unique name (or
/// `null`) plus the full match list per packet. Both envelopes are the versioned JSON shape
/// `ENCODING.md` documents, frozen by the golden files under `fixtures/bridge/region/`.
enum RegionResolveCodec {
  static let requestKind = "regionResolveRequest"
  static let responseKind = "regionResolve"
  static let failureKind = "regionResolveFailure"

  static let regionNamesKey = "regionNames"
  static let rowsKey = "rows"
  static let regionsKey = "regions"
  static let matchesKey = "matches"

  /// One row's unique name (or nil) plus every matching name. Ambiguous rows keep
  /// `uniqueName == nil` and a 2+ `matches` list so chips can show `de-hh / de-by`.
  struct Hit {
    let uniqueName: String?
    let matches: [String]
  }

  /// One packet to attribute. `transportCode` is the header field verbatim: only its first two
  /// bytes take part in matching, and knowing that is this side's business.
  struct Row {
    let payload: Data
    let payloadTypeBits: UInt8
    let transportCode: Data
  }

  /// The region names are the caller's own list. A unique match comes back trimmed; none and
  /// ambiguous matches come back as null.
  struct Request {
    let regionNames: [String]
    let rows: [Row]
  }

  // MARK: - Request decoding

  static func decode(request bytes: [UInt8]) throws -> Request {
    guard let object = (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any] else {
      throw MeshCoreError.invalidInput("region resolve request is not a JSON object")
    }
    guard (object[BridgeProtocol.versionKey] as? NSNumber)?.int64Value == BridgeProtocol.version else {
      throw MeshCoreError.invalidInput("unsupported region resolve request version")
    }
    guard object[BridgeProtocol.kindKey] as? String == requestKind else {
      throw MeshCoreError.invalidInput("region resolve request kind must be \"\(requestKind)\"")
    }
    guard let payload = object[BridgeProtocol.payloadKey] as? [String: Any],
          let names = payload[regionNamesKey] as? [String],
          let rows = payload[rowsKey] as? [[String: Any]]
    else {
      throw MeshCoreError.invalidInput("region resolve request missing payload.\(regionNamesKey) or payload.\(rowsKey)")
    }
    return Request(regionNames: names, rows: try rows.map(decode(row:)))
  }

  private static func decode(row: [String: Any]) throws -> Row {
    guard let bits = (row["payloadTypeBits"] as? NSNumber)?.int64Value else {
      throw MeshCoreError.invalidInput("region resolve row.payloadTypeBits missing or not a number")
    }
    return Row(
      payload: try data(row, "payload"),
      // Only the low nibble takes part in the firmware HMAC, so a wider value cannot change the
      // result; truncating keeps a malformed width from failing the whole batch.
      payloadTypeBits: UInt8(truncatingIfNeeded: bits),
      transportCode: try data(row, "transportCode")
    )
  }

  private static func data(_ object: [String: Any], _ key: String) throws -> Data {
    guard let text = object[key] as? String, let value = Data(base64Encoded: text) else {
      throw MeshCoreError.invalidInput("region resolve \(key) missing or not base64")
    }
    return value
  }

  // MARK: - Response encoding

  static func encode(hits: [Hit]) -> JSONValue {
    BridgeProtocol.envelope(
      kind: responseKind,
      payload: .object([
        matchesKey: .array(hits.map { hit in .array(hit.matches.map(JSONValue.string)) }),
        regionsKey: .array(hits.map { JSONValue.optionalString($0.uniqueName) }),
      ])
    )
  }

  static func encode(failure code: BridgeErrorCode, message: String) -> JSONValue {
    BridgeProtocol.envelope(
      kind: failureKind,
      payload: .object(["errorCode": .int(code.rawValue), "message": .string(message)])
    )
  }
}
