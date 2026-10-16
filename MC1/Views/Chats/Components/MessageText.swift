import CoreLocation
import MC1Services
import SwiftUI

/// A Text view that formats message content with tappable links and styled mentions
struct MessageText: View {
  let text: String
  let baseColor: Color
  let isOutgoing: Bool
  let currentUserName: String?
  let precomputedText: AttributedString?

  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.appTheme) private var theme

  init(
    _ text: String,
    baseColor: Color = .primary,
    isOutgoing: Bool = false,
    currentUserName: String? = nil,
    precomputedText: AttributedString? = nil
  ) {
    self.text = text
    self.baseColor = baseColor
    self.isOutgoing = isOutgoing
    self.currentUserName = currentUserName
    self.precomputedText = precomputedText
  }

  var body: some View {
    Text(precomputedText ?? formattedText)
  }

  /// Exposes formatted text for testing
  var testableFormattedText: AttributedString {
    formattedText
  }

  private var formattedText: AttributedString {
    MessageText.buildFormattedText(
      text: text,
      isOutgoing: isOutgoing,
      currentUserName: currentUserName,
      isHighContrast: colorSchemeContrast == .increased,
      outgoingTextColor: theme.outgoingTextColor,
      hashtagColor: theme.hashtagColor,
      identityGamut: theme.identityGamut,
      identityBackgroundLuminances: theme.avatarSurfaceLuminances(
        colorScheme: colorScheme,
        contrast: colorSchemeContrast
      )
    ).text
  }

  /// Builds an AttributedString with mention, contact-share, URL, meshcore-link, hashtag,
  /// and coordinate formatting in three stages: a string-shrinking pre-pass
  /// (`MessageTextNormalizer`), single-pass detection into a sorted non-overlapping token
  /// stream (`MessageLinkTokenizer`), then one styling step (`MessageLinkStyler`). Static so
  /// it can be called from both the view and the ViewModel cache; the `(text:mapCoordinate:)`
  /// return shape is the contract `makeBuildInputs` depends on.
  static func buildFormattedText(
    text: String,
    isOutgoing: Bool,
    currentUserName: String?,
    isHighContrast: Bool,
    outgoingTextColor: Color,
    hashtagColor: Color,
    identityGamut: IdentityGamut,
    identityBackgroundLuminances: [Double]
  ) -> (text: AttributedString, mapCoordinate: CLLocationCoordinate2D?) {
    let baseColor: Color = isOutgoing ? outgoingTextColor : .primary

    let normalized = MessageTextNormalizer.normalize(
      text,
      context: MessageTextNormalizer.StyleContext(
        baseColor: baseColor,
        isOutgoing: isOutgoing,
        currentUserName: currentUserName,
        isHighContrast: isHighContrast,
        outgoingTextColor: outgoingTextColor,
        identityGamut: identityGamut,
        identityBackgroundLuminances: identityBackgroundLuminances
      )
    )

    let tokenized = MessageLinkTokenizer.tokenize(
      normalized: normalized.string,
      preSpans: normalized.spans,
      context: MessageLinkTokenizer.StyleContext(
        baseColor: baseColor,
        isOutgoing: isOutgoing,
        outgoingTextColor: outgoingTextColor,
        hashtagColor: hashtagColor
      )
    )

    let styled = MessageLinkStyler.style(
      normalized: normalized.string,
      tokens: tokenized.tokens,
      baseColor: baseColor
    )

    return (styled, tokenized.mapCoordinate)
  }

  /// Strips invisible and control Unicode scalars from an inbound contact name. Shared
  /// entry point for the mention-tap path, which sanitizes the same way the linkifier does.
  static func displayName(for name: String) -> String {
    MessageTextNormalizer.displayName(for: name)
  }
}

#Preview("Plain text") {
  MessageText("Hello, world!")
    .padding()
}

#Preview("With mention") {
  MessageText("Hey @[Alice], check this out!")
    .padding()
}

#Preview("With self-mention") {
  MessageText("Hey @[Me], you were mentioned!", currentUserName: "Me")
    .padding()
}

#Preview("With link") {
  MessageText("Check out https://apple.com for more info")
    .padding()
}

#Preview("With mention and link") {
  MessageText("@[Bob] look at https://example.com/article")
    .padding()
}

#Preview("Outgoing message") {
  MessageText("Visit https://github.com", baseColor: .white, isOutgoing: true)
    .padding()
    .background(.blue)
}

#Preview("Outgoing with mention") {
  MessageText("Hey @[Alice], check this out!", baseColor: .white, isOutgoing: true)
    .padding()
    .background(.blue)
}

#Preview("Outgoing with self-mention") {
  MessageText("@[MyDevice] check this!", baseColor: .white, isOutgoing: true, currentUserName: "MyDevice")
    .padding()
    .background(.blue)
}

#Preview("With hashtag") {
  MessageText("Join #general for updates")
    .padding()
}

#Preview("With hashtag and URL") {
  MessageText("Check https://example.com#anchor and #general")
    .padding()
}
