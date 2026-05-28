import CoreLocation
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
        MessageText.buildFormattedText(
            text: text,
            isOutgoing: isOutgoing,
            currentUserName: currentUserName,
            isHighContrast: colorSchemeContrast == .increased
        ).text
    }

    /// Builds an AttributedString with mention, URL, and hashtag formatting.
    /// Static so it can be called from both the view and the ViewModel cache.
    static func buildFormattedText(
        text: String,
        isOutgoing: Bool,
        currentUserName: String?,
        isHighContrast: Bool
    ) -> (text: AttributedString, mapCoordinate: CLLocationCoordinate2D?) {
        let baseColor: Color = isOutgoing ? .white : .primary
        var result = AttributedString(text)
        result.foregroundColor = baseColor

        // A contact share token's name is attacker-controlled and may itself contain `@[name]`.
        // The mention pass runs first (and rewrites on the original text), so exclude token
        // ranges from it; otherwise it corrupts the name the contact-share pass later parses.
        let contactTokenRanges = contactShareTokenRanges(in: text)

        applyMentionFormatting(
            &result,
            text: text,
            baseColor: baseColor,
            isOutgoing: isOutgoing,
            currentUserName: currentUserName,
            isHighContrast: isHighContrast,
            excludedRanges: contactTokenRanges
        )

        applyContactShareFormatting(&result, baseColor: baseColor)

        let (urlRanges, currentString) = applyURLFormatting(&result, baseColor: baseColor)

        applyHashtagFormatting(&result, isOutgoing: isOutgoing, urlRanges: urlRanges, currentString: currentString)

        applyMeshCoreLinkFormatting(&result, baseColor: baseColor, urlRanges: urlRanges, currentString: currentString)

        let mapCoordinate = applyCoordinateFormatting(&result, baseColor: baseColor)

        return (result, mapCoordinate)
    }

    // MARK: - Mention Formatting

    private static func applyMentionFormatting(
        _ attributedString: inout AttributedString,
        text: String,
        baseColor: Color,
        isOutgoing: Bool,
        currentUserName: String?,
        isHighContrast: Bool,
        excludedRanges: [Range<String.Index>]
    ) {
        guard let regex = MentionUtilities.mentionRegex else { return }

        let nsRange = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: nsRange)

        // Process matches in reverse to preserve indices
        for match in matches.reversed() {
            guard let matchRange = Range(match.range, in: text),
                  let nameRange = Range(match.range(at: 1), in: text),
                  let attrMatchRange = Range(matchRange, in: attributedString) else { continue }

            // Skip mentions that fall inside a contact share token's name
            if excludedRanges.contains(where: { $0.overlaps(matchRange) }) { continue }

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

    // MARK: - Contact Share Formatting

    /// Opening delimiter of a contact share token; gates the cheap fast-path skip.
    private static let tokenOpen = "<"

    /// Ranges of every contact share token in the original text, used to keep earlier passes
    /// (which run on the original string) from rewriting characters inside a token's name.
    private static func contactShareTokenRanges(in text: String) -> [Range<String.Index>] {
        guard text.contains(tokenOpen), let regex = ContactShareUtilities.shareTokenRegex else { return [] }
        let nsRange = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: nsRange).compactMap { Range($0.range, in: text) }
    }

    /// Replaces inbound contact share tokens (`<64hex:type:name>`) with the parsed contact
    /// name, rendered as a tappable link to the canonical add-contact deep link. Runs after
    /// mention formatting and before the URL pass so the shorter replacement does not shift
    /// indices out from under the snapshot the later passes rely on.
    private static func applyContactShareFormatting(_ attributedString: inout AttributedString, baseColor: Color) {
        let text = String(attributedString.characters)
        guard text.contains(tokenOpen) else { return }
        guard let regex = ContactShareUtilities.shareTokenRegex else { return }

        let nsRange = NSRange(text.startIndex..., in: text)
        // Process matches in reverse so replacing earlier tokens does not invalidate later ranges
        for match in regex.matches(in: text, range: nsRange).reversed() {
            guard let matchRange = Range(match.range, in: text),
                  let attrRange = Range(matchRange, in: attributedString),
                  let result = ContactShareUtilities.parseShare(String(text[matchRange])) else { continue }

            // Sanitize once and carry the cleaned name through both the visible chip and the
            // link URL, so the confirmation sheet and persisted contact see it. If sanitizing
            // leaves nothing, keep the literal token rather than emit an empty, invisible chip.
            let cleanName = displayName(for: result.name)
            guard !cleanName.isEmpty else { continue }
            guard let url = URL(string: ContactService.exportContactURI(
                name: cleanName,
                publicKey: result.publicKey,
                type: result.contactType
            )) else { continue }

            var replacement = AttributedString(cleanName)
            replacement.link = url
            replacement.foregroundColor = baseColor
            replacement.underlineStyle = .single
            attributedString.replaceSubrange(attrRange, with: replacement)
        }
    }

    /// Strips invisible and control Unicode scalars from an inbound contact name.
    /// The name is attacker-controlled, so the cleaned form is used for both the visible
    /// chip and the add-contact link URL, keeping the confirmation sheet and the persisted
    /// contact free of bidi overrides, zero-width joiners, and line breaks that could hide or
    /// reorder the visible identity.
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

    /// Ranges of runs already carrying a `.link` (contact chips and detected URLs). Later passes
    /// skip these so a `#tag` or `meshcore://` substring inside a chip is not re-linked.
    private static func linkRanges(in attributedString: AttributedString) -> [Range<AttributedString.Index>] {
        attributedString.runs.compactMap { $0.link == nil ? nil : $0.range }
    }

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
        let linkedRanges = linkRanges(in: attributedString)

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

            // Skip ranges inside an existing link, e.g. a contact chip whose name contains a URL
            if linkedRanges.contains(where: { $0.overlaps(attrRange) }) { continue }

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
        let linkedRanges = linkRanges(in: attributedString)

        // Process in reverse to preserve indices
        for hashtag in hashtags.reversed() {
            guard let attrRange = Range(hashtag.range, in: attributedString) else { continue }

            // Skip hashtags inside an existing link, e.g. a contact chip whose name contains a #tag
            if linkedRanges.contains(where: { $0.overlaps(attrRange) }) { continue }

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

    // MARK: - Coordinate Formatting

    /// Linkifies every detected coordinate as a `meshcore://map` URL (skipping
    /// ranges already carrying a link) and returns the first coordinate in
    /// document order that was actually linkified — i.e. one that passed the
    /// already-linked skip. That coordinate (not a raw regex hit) drives the
    /// map-preview thumbnail, so a coordinate sitting inside a contact chip does
    /// not spawn a card.
    @discardableResult
    private static func applyCoordinateFormatting(
        _ attributedString: inout AttributedString,
        baseColor: Color
    ) -> CLLocationCoordinate2D? {
        let text = String(attributedString.characters)
        let matches = ChatCoordinateDetector.matches(in: text)
        guard !matches.isEmpty else { return nil }

        let linkedRanges = linkRanges(in: attributedString)
        var firstLinked: (lowerBound: String.Index, coordinate: CLLocationCoordinate2D)?

        // Process matches in reverse to preserve indices while mutating.
        for match in matches.reversed() {
            guard let attrRange = Range(match.range, in: attributedString) else { continue }

            // Skip ranges already carrying a link (contact chips, URLs, meshcore links).
            if linkedRanges.contains(where: { $0.overlaps(attrRange) }) { continue }

            guard let url = mapURL(
                latitude: match.coordinate.latitude,
                longitude: match.coordinate.longitude
            ) else { continue }

            attributedString[attrRange].link = url
            attributedString[attrRange].foregroundColor = baseColor
            attributedString[attrRange].underlineStyle = .single

            if firstLinked == nil || match.range.lowerBound < firstLinked!.lowerBound {
                firstLinked = (match.range.lowerBound, match.coordinate)
            }
        }
        return firstLinked?.coordinate
    }

    /// Builds `meshcore://map?lat=&lon=` with locale-independent `%.6f` values so the
    /// link round-trips through `MeshCoreURLParser.parseMapURL` on every locale. A
    /// comma-decimal locale's `.formatted()` would emit `37,334900`, which the parser's
    /// decimal-format gate rejects.
    private static func mapURL(latitude: Double, longitude: Double) -> URL? {
        var components = URLComponents()
        components.scheme = MeshCoreURLParser.scheme
        components.host = "map"
        components.queryItems = [
            URLQueryItem(name: "lat", value: String(format: "%.6f", latitude)),
            URLQueryItem(name: "lon", value: String(format: "%.6f", longitude)),
        ]
        return components.url
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
