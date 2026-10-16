import MC1Services
import SwiftUI

/// The string-shrinking pre-pass of the linkifier. Two transforms rewrite the raw
/// message body to a shorter display string before any range-based detection runs:
///
/// - `@[name]` mention tokens become `@name`.
/// - `<pubkey:type:name>` contact-share tokens become the sanitized display name.
///
/// Both produce a styled, linkable span. Freezing the display string here means every
/// later detector (URL, hashtag, meshcore, coordinate) runs on one stable string, so the
/// index-desync trap of the old interleaved passes is gone by construction.
enum MessageTextNormalizer {
  /// Style inputs threaded from the live theme/contrast environment, used to resolve a
  /// mention's identity color at normalization time so downstream stages carry only
  /// resolved colors.
  struct StyleContext {
    let baseColor: Color
    let isOutgoing: Bool
    let currentUserName: String?
    let isHighContrast: Bool
    let outgoingTextColor: Color
    let identityGamut: IdentityGamut
    let identityBackgroundLuminances: [Double]
  }

  /// The normalized display string plus the spans produced by the two rewrites, with
  /// ranges into the normalized string.
  struct Result {
    let string: String
    let spans: [LinkToken]
  }

  /// A pending substitution discovered on the original string, carrying the replacement
  /// text and the span attributes to emit once normalized ranges are known.
  private struct Replacement {
    let originalRange: Range<String.Index>
    let replacement: String
    let kind: LinkToken.Kind
    let url: URL?
    let foregroundColor: Color
    let backgroundColor: Color?
  }

  static func normalize(_ text: String, context: StyleContext) -> Result {
    let contactTokenRanges = contactShareTokenRanges(in: text)

    var replacements: [Replacement] = []
    replacements.append(contentsOf: mentionReplacements(
      in: text,
      context: context,
      excludedRanges: contactTokenRanges
    ))
    replacements.append(contentsOf: contactShareReplacements(in: text, context: context))

    // A mention can never sit inside a contact token (those mentions are excluded
    // above) and contact tokens never overlap each other, so sorting by start index
    // yields a clean left-to-right rewrite with no overlapping originals.
    replacements.sort { $0.originalRange.lowerBound < $1.originalRange.lowerBound }

    guard !replacements.isEmpty else {
      return Result(string: text, spans: [])
    }

    var normalized = ""
    var spans: [LinkToken] = []
    var cursor = text.startIndex

    for replacement in replacements {
      normalized += text[cursor..<replacement.originalRange.lowerBound]

      let spanStart = normalized.endIndex
      normalized += replacement.replacement
      let spanEnd = normalized.endIndex

      spans.append(LinkToken(
        range: spanStart..<spanEnd,
        kind: replacement.kind,
        url: replacement.url,
        foregroundColor: replacement.foregroundColor,
        backgroundColor: replacement.backgroundColor,
        underline: true,
        bold: false
      ))

      cursor = replacement.originalRange.upperBound
    }
    normalized += text[cursor...]

    return Result(string: normalized, spans: spans)
  }

  // MARK: - Mentions

  private static func mentionReplacements(
    in text: String,
    context: StyleContext,
    excludedRanges: [Range<String.Index>]
  ) -> [Replacement] {
    guard let regex = MentionUtilities.mentionRegex else { return [] }

    let nsRange = NSRange(text.startIndex..., in: text)
    return regex.matches(in: text, range: nsRange).compactMap { match in
      guard let matchRange = Range(match.range, in: text),
            let nameRange = Range(match.range(at: 1), in: text) else { return nil }

      // A contact share token's name is attacker-controlled and may itself contain
      // `@[name]`; skip mentions inside one so the contact-share rewrite owns that text.
      if excludedRanges.contains(where: { $0.overlaps(matchRange) }) { return nil }

      let name = String(text[nameRange])
      let isSelfMention = context.currentUserName.map {
        name.localizedCaseInsensitiveCompare($0) == .orderedSame
      } ?? false

      let foreground: Color
      var background: Color?
      if context.isOutgoing {
        foreground = context.baseColor
        if isSelfMention {
          background = context.baseColor.opacity(0.3)
        }
      } else {
        let mentionColor = context.identityGamut.color(
          forName: name,
          backgroundLuminances: context.identityBackgroundLuminances,
          highContrast: context.isHighContrast
        )
        foreground = mentionColor
        if isSelfMention {
          background = mentionColor.opacity(0.15)
        }
      }

      return Replacement(
        originalRange: matchRange,
        replacement: "@\(name)",
        kind: .mention,
        url: MentionDeeplinkSupport.url(forName: name),
        foregroundColor: foreground,
        backgroundColor: background
      )
    }
  }

  // MARK: - Contact share

  /// Opening delimiter of a contact share token; gates the cheap fast-path skip.
  private static let tokenOpen = "<"

  private static func contactShareTokenRanges(in text: String) -> [Range<String.Index>] {
    guard text.contains(tokenOpen), let regex = ContactShareUtilities.shareTokenRegex else { return [] }
    let nsRange = NSRange(text.startIndex..., in: text)
    return regex.matches(in: text, range: nsRange).compactMap { Range($0.range, in: text) }
  }

  private static func contactShareReplacements(in text: String, context: StyleContext) -> [Replacement] {
    guard text.contains(tokenOpen), let regex = ContactShareUtilities.shareTokenRegex else { return [] }

    let nsRange = NSRange(text.startIndex..., in: text)
    return regex.matches(in: text, range: nsRange).compactMap { match in
      guard let matchRange = Range(match.range, in: text),
            let result = ContactShareUtilities.parseShare(String(text[matchRange])) else { return nil }

      // Sanitize once and carry the cleaned name through both the visible chip and
      // the link URL. If sanitizing leaves nothing, keep the literal token rather
      // than emit an empty, invisible chip.
      let cleanName = displayName(for: result.name)
      guard !cleanName.isEmpty,
            let url = URL(string: ContactService.exportContactURI(
              name: cleanName,
              publicKey: result.publicKey,
              type: result.contactType
            )) else { return nil }

      return Replacement(
        originalRange: matchRange,
        replacement: cleanName,
        kind: .contactShare,
        url: url,
        foregroundColor: context.baseColor,
        backgroundColor: nil
      )
    }
  }

  /// Strips invisible and control Unicode scalars from an inbound contact name. The name
  /// is attacker-controlled, so the cleaned form is used for both the visible chip and the
  /// add-contact link URL, keeping the confirmation sheet and the persisted contact free
  /// of bidi overrides, zero-width joiners, and line breaks that could hide or reorder the
  /// visible identity.
  static func displayName(for name: String) -> String {
    String(String.UnicodeScalarView(name.unicodeScalars.filter { !isStrippableScalar($0) }))
  }

  private static func isStrippableScalar(_ scalar: Unicode.Scalar) -> Bool {
    if scalar.properties.isBidiControl || scalar.properties.isDefaultIgnorableCodePoint {
      return true
    }
    switch scalar.properties.generalCategory {
    case .control, .format, .lineSeparator, .paragraphSeparator:
      return true
    default:
      return false
    }
  }
}
