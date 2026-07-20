import CoreLocation
import Foundation
import MC1Services

/// Parses meshcore:// deep link URLs for channel and contact imports.
enum MeshCoreURLParser {
  static let scheme = "meshcore"

  private static let channelHost = "channel"
  private static let channelPath = "/add"
  private static let channelNameKey = "name"
  private static let channelSecretKey = "secret"
  private static let channelRegionScopeKey = "region_scope"
  private static let hashtagPrefix: Character = "#"

  /// Parsed channel data from a meshcore://channel/add URL
  struct ChannelResult: Identifiable {
    let name: String
    let secret: Data
    /// Optional app-side flood-scope region name from `region_scope`. Not a radio field.
    let regionScope: String?

    var id: String {
      secret.uppercaseHexString()
    }

    /// True when `name` is a valid hashtag shape but `secret` is not the public name-hash.
    /// Callers may warn; they must not refuse the join on this alone.
    var hasHashtagSecretMismatch: Bool {
      MeshCoreURLParser.hasHashtagSecretMismatch(name: name, secret: secret)
    }

    init(name: String, secret: Data, regionScope: String? = nil) {
      self.name = name
      self.secret = secret
      self.regionScope = regionScope
    }
  }

  /// Parsed contact data from a meshcore://contact/add URL.
  /// The concrete type lives in MC1Services so the shared share-token utility can return it.
  typealias ContactResult = MC1Services.ContactResult

  /// Parses a meshcore://channel/add URL string.
  /// Returns nil if the string is not a valid channel URL.
  ///
  /// Secret rules (explicit secret always wins over name-hash):
  /// - Valid 32-char hex `secret`: use bytes as-is; never re-derive from name.
  /// - Missing/empty `secret` with `#` name: normalize hashtag and derive
  ///   `ChannelService.hashSecret(name)`. Reject invalid hashtag bodies.
  /// - Missing/empty `secret` without leading `#`: fail (bare names are not hashtags).
  /// - Present but invalid `secret`: fail; do not fall back to name-hash.
  static func parseChannelURL(_ string: String) -> ChannelResult? {
    guard let url = URL(string: string),
          url.scheme == scheme,
          url.host() == channelHost,
          url.path() == channelPath,
          let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let queryItems = components.queryItems else {
      return nil
    }

    let name = queryItems.first(where: { $0.name == channelNameKey })?.value ?? ""
    guard !name.isEmpty else { return nil }

    let secretRaw = queryItems.first(where: { $0.name == channelSecretKey })?.value
    let regionScope = normalizedRegionScope(
      queryItems.first(where: { $0.name == channelRegionScopeKey })?.value
    )

    if let secretRaw, !secretRaw.isEmpty {
      guard let secretData = Data(hexString: secretRaw),
            secretData.count == ProtocolLimits.channelSecretSize else {
        return nil
      }
      return ChannelResult(name: name, secret: secretData, regionScope: regionScope)
    }

    guard name.first == hashtagPrefix else { return nil }

    let body = String(name.dropFirst())
    guard HashtagUtilities.isValidHashtagName(body) else { return nil }

    let normalizedName = String(hashtagPrefix) + body.lowercased()
    let secret = ChannelService.hashSecret(normalizedName)
    return ChannelResult(name: normalizedName, secret: secret, regionScope: regionScope)
  }

  /// True when `name` is a valid hashtag shape and `secret` is not the public name-hash.
  static func hasHashtagSecretMismatch(name: String, secret: Data) -> Bool {
    guard name.first == hashtagPrefix else { return false }
    let body = String(name.dropFirst())
    guard HashtagUtilities.isValidHashtagName(body) else { return false }
    let expected = ChannelService.hashSecret(String(hashtagPrefix) + body.lowercased())
    return secret != expected
  }

  /// Parses a meshcore://contact/add URL string.
  /// Returns nil if the string is not a valid contact URL.
  static func parseContactURL(_ string: String) -> ContactResult? {
    guard let url = URL(string: string),
          url.scheme == "meshcore",
          url.host() == "contact",
          url.path() == "/add",
          let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let queryItems = components.queryItems else {
      return nil
    }

    // Custom scheme, not x-www-form-urlencoded: a literal "+" is a name character, not a
    // space. URLComponents has already percent-decoded the value, so use it verbatim.
    let name = queryItems.first(where: { $0.name == "name" })?.value ?? ""
    let publicKeyHex = queryItems.first(where: { $0.name == "public_key" })?.value ?? ""

    guard !name.isEmpty,
          let keyData = Data(hexString: publicKeyHex),
          keyData.count == ProtocolLimits.publicKeySize else {
      return nil
    }

    let typeValue = queryItems.first(where: { $0.name == "type" })?.value.flatMap { Int($0) } ?? 1
    let contactType = UInt8(exactly: typeValue).flatMap { ContactType(rawValue: $0) } ?? .chat

    return ContactResult(name: name, publicKey: keyData, contactType: contactType)
  }

  /// Parses a meshcore://map?lat=&lon= URL into a coordinate. Returns nil if not a
  /// valid map URL or if the values are out of range or not plain decimal degrees.
  static func parseMapURL(_ string: String) -> CLLocationCoordinate2D? {
    guard let url = URL(string: string),
          url.scheme == scheme,
          url.host() == "map",
          let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let queryItems = components.queryItems else {
      return nil
    }

    guard let latitude = decimalDegree(queryItems.first(where: { $0.name == "lat" })?.value),
          let longitude = decimalDegree(queryItems.first(where: { $0.name == "lon" })?.value),
          (-90.0...90.0).contains(latitude),
          (-180.0...180.0).contains(longitude) else {
      return nil
    }

    return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
  }

  /// Parses a plain decimal-degree string. The format gate rejects hex floats
  /// (`0x1p4`), `inf`, and `nan` — all of which `Double(_:)` accepts and would
  /// otherwise feed into MKCoordinateRegion / openInMaps.
  private static func decimalDegree(_ value: String?) -> Double? {
    guard let value,
          value.range(of: #"^-?\d{1,3}(\.\d+)?$"#, options: .regularExpression) != nil else {
      return nil
    }
    return Double(value)
  }

  /// Trims whitespace and caps to the flood-scope name byte limit. Empty after
  /// trim/cap becomes nil so join never persists a blank region preference.
  static func normalizedRegionScope(_ value: String?) -> String? {
    guard let value else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }
    let capped = trimmed.utf8Prefix(maxBytes: ProtocolLimits.maxDefaultFloodScopeNameBytes)
    return capped.isEmpty ? nil : capped
  }
}
