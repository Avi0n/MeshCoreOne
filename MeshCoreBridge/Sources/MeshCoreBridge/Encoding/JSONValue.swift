import Foundation

/// A minimal JSON model with a deterministic serializer.
///
/// The bridge boundary is a frozen contract (golden-file tested on both the
/// Swift and Kotlin sides), so the byte output must be reproducible: object keys
/// are always emitted in sorted order and there is exactly one spelling for every
/// scalar. `JSONSerialization` does not guarantee key order, and MeshCore's value
/// types are not `Codable`, so this hand-rolled emitter is the single source of
/// truth for how a `payload` becomes bytes.
enum JSONValue: Equatable {
  case null
  case bool(Bool)
  case integer(Int64)
  case double(Double)
  case string(String)
  case array([JSONValue])
  case object([String: JSONValue])
}

extension JSONValue {
  /// Serializes to canonical UTF-8 bytes: object keys sorted lexicographically,
  /// no insignificant whitespace, forward slashes unescaped.
  func serialized() -> [UInt8] {
    var out = String()
    write(into: &out)
    return Array(out.utf8)
  }

  func serializedString() -> String {
    var out = String()
    write(into: &out)
    return out
  }

  private func write(into out: inout String) {
    switch self {
    case .null:
      out += "null"
    case let .bool(value):
      out += value ? "true" : "false"
    case let .integer(value):
      out += String(value)
    case let .double(value):
      out += Self.encodeDouble(value)
    case let .string(value):
      Self.writeString(value, into: &out)
    case let .array(values):
      out += "["
      for (index, element) in values.enumerated() {
        if index > 0 { out += "," }
        element.write(into: &out)
      }
      out += "]"
    case let .object(members):
      out += "{"
      for (index, key) in members.keys.sorted().enumerated() {
        if index > 0 { out += "," }
        Self.writeString(key, into: &out)
        out += ":"
        members[key]!.write(into: &out)
      }
      out += "}"
    }
  }

  /// Non-finite doubles have no JSON representation; they collapse to `null`
  /// rather than emit invalid tokens. All MeshCore doubles (SNR, coordinates,
  /// frequencies) are finite, so this only guards against corruption.
  private static func encodeDouble(_ value: Double) -> String {
    guard value.isFinite else { return "null" }
    // Swift's shortest round-trippable description is stable across platforms
    // and always carries a decimal point for whole values (e.g. "5.0"), which
    // keeps a value typed as a floating-point number on the Kotlin side.
    return String(value)
  }

  private static func writeString(_ value: String, into out: inout String) {
    out += "\""
    for scalar in value.unicodeScalars {
      switch scalar {
      case "\"": out += "\\\""
      case "\\": out += "\\\\"
      case "\n": out += "\\n"
      case "\r": out += "\\r"
      case "\t": out += "\\t"
      case "\u{08}": out += "\\b"
      case "\u{0C}": out += "\\f"
      default:
        if scalar.value < 0x20 {
          out += String(format: "\\u%04x", scalar.value)
        } else {
          out.unicodeScalars.append(scalar)
        }
      }
    }
    out += "\""
  }
}

// MARK: - Scalar conveniences

extension JSONValue {
  /// Wraps optional data as base64, or `null`.
  static func data(_ value: Data) -> JSONValue { .string(value.base64EncodedString()) }

  static func data(_ value: Data?) -> JSONValue {
    guard let value else { return .null }
    return .string(value.base64EncodedString())
  }

  /// Encodes a `Date` as integer seconds since the Unix epoch (UTC), matching
  /// the backup envelope's `.secondsSince1970` convention. Device timestamps
  /// are whole seconds, so no fractional formatting is involved.
  static func date(_ value: Date) -> JSONValue { .integer(Int64(value.timeIntervalSince1970.rounded())) }

  /// Encodes an optional `Date` as integer epoch seconds, or `null` when absent.
  static func date(_ value: Date?) -> JSONValue {
    guard let value else { return .null }
    return .integer(Int64(value.timeIntervalSince1970.rounded()))
  }

  /// The canonical integer factory. Every integer width funnels through here so
  /// call sites never touch the raw `.integer` case.
  static func int(_ value: some BinaryInteger) -> JSONValue { .integer(Int64(value)) }

  static func optionalInt(_ value: (some BinaryInteger)?) -> JSONValue {
    guard let value else { return .null }
    return .integer(Int64(value))
  }

  static func optionalDouble(_ value: Double?) -> JSONValue {
    guard let value else { return .null }
    return .double(value)
  }

  static func optionalString(_ value: String?) -> JSONValue {
    guard let value else { return .null }
    return .string(value)
  }
}
