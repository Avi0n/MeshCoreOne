import SwiftUI
import MC1Services

/// A Text view that formats message content with tappable links and styled mentions
struct MessageText: View {
    let text: String
    let baseColor: Color
    let isOutgoing: Bool
    let currentUserName: String?
    let precomputedText: AttributedString?

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

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
        Self.buildFormattedText(
            text: text,
            isOutgoing: isOutgoing,
            currentUserName: currentUserName,
            isHighContrast: colorSchemeContrast == .increased
        )
    }

    /// Builds an AttributedString with mention, URL, and hashtag formatting.
    /// Static so it can be called from both the view and the ViewModel cache.
    static func buildFormattedText(
        text: String,
        isOutgoing: Bool,
        currentUserName: String?,
        isHighContrast: Bool
    ) -> AttributedString {
        let baseColor: Color = isOutgoing ? .white : .primary
        var result = AttributedString(text)
        result.foregroundColor = baseColor

        applyMentionFormatting(
            &result,
            text: text,
            baseColor: baseColor,
            isOutgoing: isOutgoing,
            currentUserName: currentUserName,
            isHighContrast: isHighContrast
        )

        let (urlRanges, currentString) = applyURLFormatting(&result, baseColor: baseColor)

        applyHashtagFormatting(&result, isOutgoing: isOutgoing, urlRanges: urlRanges, currentString: currentString)

        applyMeshCoreLinkFormatting(&result, baseColor: baseColor, urlRanges: urlRanges, currentString: currentString)

        return result
    }

    // MARK: - Mention Formatting

    private static func applyMentionFormatting(
        _ attributedString: inout AttributedString,
        text: String,
        baseColor: Color,
        isOutgoing: Bool,
        currentUserName: String?,
        isHighContrast: Bool
    ) {
        guard let regex = MentionUtilities.mentionRegex else { return }

        let nsRange = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: nsRange)

        // Process matches in reverse to preserve indices
        for match in matches.reversed() {
            guard let matchRange = Range(match.range, in: text),
                  let nameRange = Range(match.range(at: 1), in: text),
                  let attrMatchRange = Range(matchRange, in: attributedString) else { continue }

            // Get the name without brackets
            let name = String(text[nameRange])

            // Check if this is a self-mention
            let isSelfMention = currentUserName.map {
                name.localizedCaseInsensitiveCompare($0) == .orderedSame
            } ?? false

            // Replace @[name] with @name, styled appropriately for bubble color
            var replacement = AttributedString("@\(name)")
            replacement.underlineStyle = .single

            if isOutgoing {
                // On dark bubbles: use white text, with background only for self-mentions
                replacement.foregroundColor = .white
                if isSelfMention {
                    replacement.backgroundColor = Color.white.opacity(0.3)
                }
            } else {
                // On light bubbles: use sender color for the mentioned name
                let mentionColor = AppColors.NameColor.color(
                    for: name,
                    highContrast: isHighContrast
                )
                replacement.foregroundColor = mentionColor
                if isSelfMention {
                    replacement.backgroundColor = mentionColor.opacity(0.15)
                }
            }

            attributedString.replaceSubrange(attrMatchRange, with: replacement)
        }
    }

    // MARK: - URL Formatting

    private static let urlDetector: NSDataDetector? = {
        try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    }()

    /// Applies URL formatting and returns the detected URL ranges + current string for reuse
    private static func applyURLFormatting(
        _ attributedString: inout AttributedString,
        baseColor: Color
    ) -> (urlRanges: [Range<String.Index>], currentString: String) {
        guard let detector = urlDetector else { return ([], "") }

        // Collect ranges already styled as mentions (have underline style)
        // URLs within these ranges should not be converted to links
        var mentionRanges: [Range<AttributedString.Index>] = []
        for run in attributedString.runs {
            if run.underlineStyle == .single {
                mentionRanges.append(run.range)
            }
        }

        // Get the current string content (may have been modified by mention formatting)
        let currentString = String(attributedString.characters)
        let nsRange = NSRange(currentString.startIndex..., in: currentString)
        let matches = detector.matches(in: currentString, options: [], range: nsRange)

        var urlRanges: [Range<String.Index>] = []

        // Process matches in reverse to preserve indices
        for match in matches.reversed() {
            guard let url = match.url,
                  let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https",
                  let matchRange = Range(match.range, in: currentString),
                  let attrRange = Range(matchRange, in: attributedString) else { continue }

            urlRanges.append(matchRange)

            // Skip URLs that overlap with mention ranges
            let overlapsWithMention = mentionRanges.contains { mentionRange in
                attrRange.overlaps(mentionRange)
            }
            if overlapsWithMention {
                continue
            }

            attributedString[attrRange].link = url
            attributedString[attrRange].foregroundColor = baseColor
            attributedString[attrRange].underlineStyle = .single
        }

        return (urlRanges, currentString)
    }

    // MARK: - MeshCore Link Formatting

    private static let meshCoreLinkRegex = try? NSRegularExpression(pattern: #"meshcore://[^\s<>"]+"#)

    private static func applyMeshCoreLinkFormatting(
        _ attributedString: inout AttributedString,
        baseColor: Color,
        urlRanges: [Range<String.Index>],
        currentString: String
    ) {
        guard let regex = meshCoreLinkRegex else { return }

        let nsRange = NSRange(currentString.startIndex..., in: currentString)
        let matches = regex.matches(in: currentString, range: nsRange)

        for match in matches.reversed() {
            guard var matchRange = Range(match.range, in: currentString) else { continue }

            // Strip trailing punctuation the regex may over-capture
            while let last = currentString[matchRange].last, ".,;:!?)".contains(last) {
                matchRange = matchRange.lowerBound..<currentString.index(before: matchRange.upperBound)
                if matchRange.isEmpty { break }
            }
            if matchRange.isEmpty { continue }

            // Skip ranges already covered by the URL pass
            let overlapsWithURL = urlRanges.contains { $0.overlaps(matchRange) }
            if overlapsWithURL { continue }

            guard let attrRange = Range(matchRange, in: attributedString),
                  let url = URL(string: String(currentString[matchRange])),
                  url.host() == "contact" || url.host() == "channel" else { continue }

            attributedString[attrRange].link = url
            attributedString[attrRange].foregroundColor = baseColor
            attributedString[attrRange].underlineStyle = .single
        }
    }

    // MARK: - Hashtag Formatting

    private static func applyHashtagFormatting(
        _ attributedString: inout AttributedString,
        isOutgoing: Bool,
        urlRanges: [Range<String.Index>],
        currentString: String
    ) {
        let hashtags = HashtagUtilities.extractHashtags(from: currentString, urlRanges: urlRanges)

        // Process in reverse to preserve indices
        for hashtag in hashtags.reversed() {
            guard let attrRange = Range(hashtag.range, in: attributedString) else { continue }

            // Format: meshcoreone://hashtag/channelname
            let channelName = HashtagUtilities.normalizeHashtagName(hashtag.name)
            if let url = URL(string: "meshcoreone://hashtag/\(channelName)") {
                attributedString[attrRange].link = url
                // Hashtags: bold + cyan (or white on dark bubbles), no underline
                // This distinguishes them from URLs which remain underlined
                attributedString[attrRange].foregroundColor = isOutgoing ? .white : .cyan
                attributedString[attrRange].inlinePresentationIntent = .stronglyEmphasized
            }
        }
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
