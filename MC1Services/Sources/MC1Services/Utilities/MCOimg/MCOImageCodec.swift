import Foundation

/// Dispatches the two text envelopes without conflating their Base91 input layers.
public enum MCOImageCodec {
  public static let v3Subtype: UInt8 = 0x13
  public static let v4Subtype: UInt8 = 0x14
  private static let v3Version = 3
  private static let v4Version = 4
  private static let maximumVersionDigits = 2
  private static let textPrefixLength = 2
  private static let subtype: UInt8 = 1
  private static let versionMask: UInt8 = 0x0f
  private static let subtypeShift = 4

  public static func isImageText(_ text: String) -> Bool {
    version(in: text.trimmingCharacters(in: .whitespacesAndNewlines)) != nil
  }

  /// A channel row whose text is MCOimg. Group data and a channel text packet
  /// with an `im3:` or `im4:` body are the same stored shape.
  public static func isChannelImage(channelIndex: UInt8?, text: String) -> Bool {
    channelIndex != nil && isImageText(text)
  }

  public static func content(from text: String) -> MCOImageContent? {
    let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let version = version(in: text) else { return nil }
    guard version == v3Version || version == v4Version else { return .unsupported }
    do {
      let payload = try appPayload(fromText: text)
      let body = Data(payload.dropFirst())
      switch version {
      case v3Version: return .raster(try MCOImageV3Codec.decodeBody(body))
      case v4Version: return .document(try MCOImageV4Codec.decodeBody(body))
      default: return .unsupported
      }
    } catch MCOImageCodecError.unsupported {
      return .unsupported
    } catch {
      return .damaged
    }
  }

  public static func text(fromAppPayload payload: Data) throws -> String {
    guard let header = payload.first, header >> subtypeShift == subtype else {
      throw MCOImageCodecError.damaged
    }
    let version = header & versionMask
    let bytes = header == v4Subtype ? Data(payload.dropFirst()) : payload
    return "im\(version):" + MCOImageBase91.encode(bytes)
  }

  public static func appPayload(fromText text: String) throws -> Data {
    let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let version = version(in: text), let colon = text.firstIndex(of: ":") else {
      throw MCOImageCodecError.damaged
    }
    let decoded = try MCOImageBase91.decode(String(text[text.index(after: colon)...]))
    guard !decoded.isEmpty else { throw MCOImageCodecError.damaged }
    if version == v4Version { return Data([v4Subtype]) + decoded }
    guard decoded.first == (subtype << subtypeShift | UInt8(version)) else {
      throw MCOImageCodecError.damaged
    }
    return decoded
  }

  public static func refreshNonce(in text: String) throws -> String {
    var payload = try appPayload(fromText: text)
    guard payload.count > 1 else { throw MCOImageCodecError.damaged }
    payload[1] = UInt8.random(in: .min ... .max)
    return try self.text(fromAppPayload: payload)
  }

  public static func channelAppPayload(fromText text: String) throws -> Data {
    let payload = try appPayload(fromText: text)
    let body = Data(payload.dropFirst())
    switch payload.first {
    case v3Subtype: return try Data([v4Subtype]) + MCOImageV4Codec.wrappingV3Body(body)
    case v4Subtype:
      _ = try MCOImageV4Codec.decodeBody(body)
      return payload
    default: throw MCOImageCodecError.unsupported
    }
  }

  private static func version(in text: String) -> Int? {
    guard text.hasPrefix("im"), let colon = text.firstIndex(of: ":") else { return nil }
    let digits = text[text.index(text.startIndex, offsetBy: textPrefixLength)..<colon]
    guard !digits.isEmpty, digits.count <= maximumVersionDigits, digits.allSatisfy(\.isNumber),
          let version = Int(digits), (0...Int(versionMask)).contains(version) else { return nil }
    return version
  }
}
