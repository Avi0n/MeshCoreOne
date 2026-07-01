import SwiftUI

/// Authors the single SwiftUI `AttributedString` from the normalized string and its sorted,
/// non-overlapping `[LinkToken]`. This is the only authored attributed-string representation;
/// the render-time UIKit string is derived from it, never authored in parallel.
enum MessageLinkStyler {
  /// Applies the base color across the whole string, then each token's link, color,
  /// underline, bold, and self-mention background over its span. Tokens are non-overlapping,
  /// so application order does not affect the result.
  static func style(normalized: String, tokens: [LinkToken], baseColor: Color) -> AttributedString {
    var result = AttributedString(normalized)
    result.foregroundColor = baseColor

    for token in tokens {
      guard let attrRange = Range(token.range, in: result) else { continue }

      if let url = token.url {
        result[attrRange].link = url
      }
      result[attrRange].foregroundColor = token.foregroundColor
      if let background = token.backgroundColor {
        result[attrRange].backgroundColor = background
      }
      if token.underline {
        result[attrRange].underlineStyle = .single
      }
      if token.bold {
        result[attrRange].inlinePresentationIntent = .stronglyEmphasized
      }
    }

    return result
  }
}
