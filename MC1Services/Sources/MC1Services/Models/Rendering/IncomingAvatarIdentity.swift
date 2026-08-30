import Foundation

public struct IncomingAvatarIdentity: Sendable, Hashable {
  public let name: String
  /// Unique-name channel match. Local `Contact.id`, never a DM conversation key.
  public let matchedContactID: UUID?
  public let imageRevision: UInt64?

  public init(name: String, matchedContactID: UUID?, imageRevision: UInt64?) {
    self.name = name
    self.matchedContactID = matchedContactID
    self.imageRevision = imageRevision
  }

  public static func initials(name: String) -> IncomingAvatarIdentity {
    IncomingAvatarIdentity(name: name, matchedContactID: nil, imageRevision: nil)
  }

  /// Unique-name photo only when `senderNodeName` is non-empty after trim.
  /// Prefix-resolved `displayName` is initials chrome, never a table key.
  public static func resolve(
    senderNodeName: String?,
    displayName: String,
    table: [String: IncomingAvatarIdentity]
  ) -> IncomingAvatarIdentity {
    if let raw = senderNodeName?.trimmingCharacters(in: .whitespacesAndNewlines),
       !raw.isEmpty,
       let identity = table[raw.lowercased()] {
      return identity
    }
    return .initials(name: displayName)
  }

  /// Process-lifetime token. `Hasher` mixes the JPEG bytes; do not persist.
  public static func revision(of data: Data?) -> UInt64? {
    guard let data, !data.isEmpty else { return nil }
    var hasher = Hasher()
    hasher.combine(data)
    return UInt64(bitPattern: Int64(hasher.finalize()))
  }
}
