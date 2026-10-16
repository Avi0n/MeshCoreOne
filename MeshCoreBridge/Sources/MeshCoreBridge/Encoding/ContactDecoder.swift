import Foundation
import MeshCore

/// Decodes a `MeshContact` from a bridge contact envelope.
///
/// A few session commands (`addContact`, `changeContactFlags`, `requestRegions`)
/// take a whole `MeshContact` rather than a public key. The Kotlin side already
/// holds the encoded contact — it received it as a `contact` event or inside a
/// `contacts` result — so it hands the same envelope back down. This decoder is
/// the exact inverse of ``PayloadEncoder/contact(_:)`` wrapped in the versioned
/// envelope, keeping the boundary symmetric. Any malformed field throws
/// `MeshCoreError.invalidInput`, which surfaces as `BridgeErrorCode.invalidInput`.
enum ContactDecoder {
  static func decode(envelope bytes: [UInt8]) throws -> MeshContact {
    guard let object = (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any] else {
      throw MeshCoreError.invalidInput("contact envelope is not a JSON object")
    }
    guard (object[BridgeProtocol.versionKey] as? NSNumber)?.int64Value == BridgeProtocol.version else {
      throw MeshCoreError.invalidInput("unsupported contact envelope version")
    }
    guard object[BridgeProtocol.kindKey] as? String == "contact" else {
      throw MeshCoreError.invalidInput("contact envelope kind must be \"contact\"")
    }
    guard let payload = object[BridgeProtocol.payloadKey] as? [String: Any] else {
      throw MeshCoreError.invalidInput("contact envelope missing payload")
    }
    return try decode(payload: payload)
  }

  static func decode(payload: [String: Any]) throws -> MeshContact {
    let typeRawValue = try byte(payload, "typeRawValue")
    return MeshContact(
      id: try string(payload, "id"),
      publicKey: try data(payload, "publicKey"),
      type: ContactType(rawValue: typeRawValue) ?? .chat,
      typeRawValue: typeRawValue,
      flags: ContactFlags(rawValue: try byte(payload, "flags")),
      outPathLength: try byte(payload, "outPathLength"),
      outPath: try data(payload, "outPath"),
      advertisedName: try string(payload, "advertisedName"),
      lastAdvertisement: try date(payload, "lastAdvertisement"),
      latitude: try double(payload, "latitude"),
      longitude: try double(payload, "longitude"),
      lastModified: try date(payload, "lastModified")
    )
  }

  // MARK: - Field extraction

  private static func string(_ object: [String: Any], _ key: String) throws -> String {
    guard let value = object[key] as? String else {
      throw MeshCoreError.invalidInput("contact.\(key) missing or not a string")
    }
    return value
  }

  private static func data(_ object: [String: Any], _ key: String) throws -> Data {
    guard let value = Data(base64Encoded: try string(object, key)) else {
      throw MeshCoreError.invalidInput("contact.\(key) is not base64")
    }
    return value
  }

  private static func number(_ object: [String: Any], _ key: String) throws -> NSNumber {
    guard let value = object[key] as? NSNumber else {
      throw MeshCoreError.invalidInput("contact.\(key) missing or not a number")
    }
    return value
  }

  private static func byte(_ object: [String: Any], _ key: String) throws -> UInt8 {
    let value = try number(object, key).intValue
    guard let byte = UInt8(exactly: value) else {
      throw MeshCoreError.invalidInput("contact.\(key) out of byte range")
    }
    return byte
  }

  private static func double(_ object: [String: Any], _ key: String) throws -> Double {
    try number(object, key).doubleValue
  }

  private static func date(_ object: [String: Any], _ key: String) throws -> Date {
    Date(timeIntervalSince1970: try number(object, key).doubleValue)
  }
}
