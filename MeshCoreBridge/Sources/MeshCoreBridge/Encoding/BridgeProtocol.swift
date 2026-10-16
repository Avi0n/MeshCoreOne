import Foundation

/// Versioning and shared field names for the bridge boundary encoding.
///
/// Every event and result crossing the JNI boundary is a JSON object
/// `{"v":1,"kind":"<name>","payload":{…}}`. The leading `v` field lets a future
/// binary v2 slot in behind the same dispatch without breaking v1 consumers.
/// `ENCODING.md` documents every `kind` and its payload fields.
enum BridgeProtocol {
  /// Current boundary encoding version. Bumping this is a cross-platform format
  /// change and requires updating the Kotlin decoder and both golden suites.
  static let version: Int64 = 1

  static let versionKey = "v"
  static let kindKey = "kind"
  static let payloadKey = "payload"

  /// Wraps a kind + payload in the versioned envelope.
  static func envelope(kind: String, payload: JSONValue) -> JSONValue {
    .object([
      versionKey: .int(version),
      kindKey: .string(kind),
      payloadKey: payload
    ])
  }

  /// Wraps a kind carrying no payload fields (e.g. `ok`, `noMoreMessages`).
  static func envelope(kind: String) -> JSONValue {
    envelope(kind: kind, payload: .object([:]))
  }
}
