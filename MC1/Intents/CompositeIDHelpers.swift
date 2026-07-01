import CryptoKit
import Foundation
import MC1Services

/// Number of leading SHA256 bytes kept as a channel's stable id digest. Wide
/// enough that an accidental collision across a radio's handful of channels is
/// negligible, while never embedding the raw 16-byte channel secret (the live
/// symmetric encryption key) in a framework-persisted shortcut id.
private let channelDigestByteCount = 16

/// Domain separator prefixed to the channel-secret digest input. Its `.v1`
/// suffix versions the digest scheme, the seam to bump if the scheme ever changes.
private let channelDigestDomain = "MC1.ChannelEntity.id.v1"

/// Separator between the radio scope, the kind, and the per-entity key in a
/// composite id.
private let compositeIDSeparator: Character = "/"

/// Formats a `"<radioID>/<kind>/<keyOrDigestHex>"` composite id. The leading
/// radio scope keeps a saved shortcut bound to the radio it was created against,
/// and the kind segment lets resolution route a contact id (public key) apart
/// from a channel id (secret digest) without trusting an in-memory field.
func formatCompositeID(radioID: UUID, kind: MessageTargetKind, keyHex: String) -> String {
  "\(radioID.uuidString)\(compositeIDSeparator)\(kind.rawValue)\(compositeIDSeparator)\(keyHex)"
}

/// Parses a composite id back into its radio scope, kind, and key hex, returning
/// nil for any malformed value so a corrupt saved-shortcut id fails safe to "no
/// entity" rather than resolving against the wrong radio or kind. Splits on the
/// first two separators only; the key hex never contains a separator.
func parseCompositeID(_ id: String) -> (radioID: UUID, kind: MessageTargetKind, keyHex: String)? {
  guard let firstSeparator = id.firstIndex(of: compositeIDSeparator) else { return nil }
  let radioPart = String(id[..<firstSeparator])
  let afterRadio = id[id.index(after: firstSeparator)...]
  guard let secondSeparator = afterRadio.firstIndex(of: compositeIDSeparator) else { return nil }
  let kindPart = String(afterRadio[..<secondSeparator])
  let keyPart = String(afterRadio[afterRadio.index(after: secondSeparator)...])
  guard let radioID = UUID(uuidString: radioPart),
        let kind = MessageTargetKind(rawValue: kindPart),
        !keyPart.isEmpty else { return nil }
  return (radioID, kind, keyPart)
}

/// Non-reversible digest of a channel secret, domain-separated and radio-scoped.
/// The raw secret never leaves the device inside a framework-persisted id.
func channelSecretDigestHex(radioID: UUID, secret: Data) -> String {
  var input = Data(channelDigestDomain.utf8)
  withUnsafeBytes(of: radioID.uuid) { input.append(contentsOf: $0) }
  input.append(secret)
  let digest = SHA256.hash(data: input)
  return Data(digest.prefix(channelDigestByteCount)).hexString
}
