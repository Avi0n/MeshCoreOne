import CoreLocation
import SwiftUI
@testable import MC1Services
@testable import MC1

/// Verbatim snapshot of the pre-refactor `MessageText.buildFormattedText` multi-pass linkifier,
/// frozen here as the golden reference for `MessageLinkifierEquivalenceTests`. Its output is the
/// behavior the decomposed normalizer/tokenizer/styler pipeline must reproduce exactly. This is
/// test-only and must not change; it captures the old behavior, not the new design.
enum LegacyLinkifierReference {

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
        var result = AttributedString(text)
        result.foregroundColor = baseColor

        let contactTokenRanges = contactShareTokenRanges(in: text)

        applyMentionFormatting(
            &result,
            text: text,
            baseColor: baseColor,
            isOutgoing: isOutgoing,
            currentUserName: currentUserName,
            isHighContrast: isHighContrast,
            identityGamut: identityGamut,
            identityBackgroundLuminances: identityBackgroundLuminances,
            excludedRanges: contactTokenRanges
        )

        applyContactShareFormatting(&result, baseColor: baseColor)

        let (urlRanges, currentString) = applyURLFormatting(&result, baseColor: baseColor)

        applyHashtagFormatting(&result, isOutgoing: isOutgoing, outgoingTextColor: outgoingTextColor, hashtagColor: hashtagColor, urlRanges: urlRanges, currentString: currentString)

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
        identityGamut: IdentityGamut,
        identityBackgroundLuminances: [Double],
        excludedRanges: [Range<String.Index>]
    ) {
        guard let regex = MentionUtilities.mentionRegex else { return }

        let nsRange = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: nsRange)

        for match in matches.reversed() {
            guard let matchRange = Range(match.range, in: text),
                  let nameRange = Range(match.range(at: 1), in: text),
                  let attrMatchRange = Range(matchRange, in: attributedString) else { continue }

            if excludedRanges.contains(where: { $0.overlaps(matchRange) }) { continue }

            let name = String(text[nameRange])

            let isSelfMention = currentUserName.map {
                name.localizedCaseInsensitiveCompare($0) == .orderedSame
            } ?? false

            var replacement = AttributedString("@\(name)")
            replacement.underlineStyle = .single

            if isOutgoing {
                replacement.foregroundColor = baseColor
                if isSelfMention {
                    replacement.backgroundColor = baseColor.opacity(0.3)
                }
            } else {
                let mentionColor = identityGamut.color(
                    forName: name,
                    backgroundLuminances: identityBackgroundLuminances,
                    highContrast: isHighContrast
                )
                replacement.foregroundColor = mentionColor
                if isSelfMention {
                    replacement.backgroundColor = mentionColor.opacity(0.15)
                }
            }

            if let url = MentionDeeplinkSupport.url(forName: name) {
                replacement.link = url
            }

            attributedString.replaceSubrange(attrMatchRange, with: replacement)
        }
    }

    // MARK: - Contact Share Formatting

    private static let tokenOpen = "<"

    private static func contactShareTokenRanges(in text: String) -> [Range<String.Index>] {
        guard text.contains(tokenOpen), let regex = ContactShareUtilities.shareTokenRegex else { return [] }
        let nsRange = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: nsRange).compactMap { Range($0.range, in: text) }
    }

    private static func applyContactShareFormatting(_ attributedString: inout AttributedString, baseColor: Color) {
        let text = String(attributedString.characters)
        guard text.contains(tokenOpen) else { return }
        guard let regex = ContactShareUtilities.shareTokenRegex else { return }

        let nsRange = NSRange(text.startIndex..., in: text)
        for match in regex.matches(in: text, range: nsRange).reversed() {
            guard let matchRange = Range(match.range, in: text),
                  let attrRange = Range(matchRange, in: attributedString),
                  let result = ContactShareUtilities.parseShare(String(text[matchRange])) else { continue }

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

    private static func displayName(for name: String) -> String {
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

    private static func applyURLFormatting(
        _ attributedString: inout AttributedString,
        baseColor: Color
    ) -> (urlRanges: [Range<String.Index>], currentString: String) {
        guard let detector = urlDetector else { return ([], "") }

        var mentionRanges: [Range<AttributedString.Index>] = []
        for run in attributedString.runs {
            if run.underlineStyle == .single {
                mentionRanges.append(run.range)
            }
        }

        let currentString = String(attributedString.characters)
        let nsRange = NSRange(currentString.startIndex..., in: currentString)
        let matches = detector.matches(in: currentString, options: [], range: nsRange)

        var urlRanges: [Range<String.Index>] = []

        for match in matches.reversed() {
            guard let url = match.url,
                  let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https",
                  let matchRange = Range(match.range, in: currentString),
                  let attrRange = Range(matchRange, in: attributedString) else { continue }

            urlRanges.append(matchRange)

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

            while let last = currentString[matchRange].last, ".,;:!?)".contains(last) {
                matchRange = matchRange.lowerBound..<currentString.index(before: matchRange.upperBound)
                if matchRange.isEmpty { break }
            }
            if matchRange.isEmpty { continue }

            let overlapsWithURL = urlRanges.contains { $0.overlaps(matchRange) }
            if overlapsWithURL { continue }

            guard let attrRange = Range(matchRange, in: attributedString),
                  let url = URL(string: String(currentString[matchRange])),
                  url.host() == "contact" || url.host() == "channel" else { continue }

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
        outgoingTextColor: Color,
        hashtagColor: Color,
        urlRanges: [Range<String.Index>],
        currentString: String
    ) {
        let hashtags = HashtagUtilities.extractHashtags(from: currentString, urlRanges: urlRanges)
        let linkedRanges = linkRanges(in: attributedString)

        for hashtag in hashtags.reversed() {
            guard let attrRange = Range(hashtag.range, in: attributedString) else { continue }

            if linkedRanges.contains(where: { $0.overlaps(attrRange) }) { continue }

            let channelName = HashtagUtilities.normalizeHashtagName(hashtag.name)
            if let url = URL(string: "meshcoreone://hashtag/\(channelName)") {
                attributedString[attrRange].link = url
                attributedString[attrRange].foregroundColor = isOutgoing ? outgoingTextColor : hashtagColor
                attributedString[attrRange].inlinePresentationIntent = .stronglyEmphasized
            }
        }
    }

    // MARK: - Coordinate Formatting

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

        for match in matches.reversed() {
            guard let attrRange = Range(match.range, in: attributedString) else { continue }

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
