import Foundation

/// Utilities for detecting and processing hashtag channel references in messages
public enum HashtagUtilities {

    static let hashtagPattern = "#[A-Za-z0-9][A-Za-z0-9-]*"

    /// Pre-compiled regex for hashtag matching (avoids recompilation per call)
    static let hashtagRegex: NSRegularExpression? = {
        try? NSRegularExpression(pattern: hashtagPattern)
    }()

    /// Represents a detected hashtag with its location in the source text
    public struct DetectedHashtag: Equatable, Sendable {
        public let name: String
        public let range: Range<String.Index>
    }

    /// Extracts all valid hashtags from text, excluding those within URLs
    /// - Parameter text: The message text to search
    /// - Returns: Array of detected hashtags with their ranges
    static func extractHashtags(from text: String) -> [DetectedHashtag] {
        extractHashtags(from: text, urlRanges: findURLRanges(in: text))
    }

    /// Extracts all valid hashtags from text, using pre-computed URL ranges to skip
    /// - Parameters:
    ///   - text: The message text to search
    ///   - urlRanges: Pre-computed URL ranges (avoids duplicate NSDataDetector scan)
    /// - Returns: Array of detected hashtags with their ranges
    public static func extractHashtags(
        from text: String,
        urlRanges: [Range<String.Index>]
    ) -> [DetectedHashtag] {
        guard !text.isEmpty else { return [] }

        guard let regex = hashtagRegex else { return [] }

        let nsRange = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: nsRange)

        return matches.compactMap { match -> DetectedHashtag? in
            guard let range = Range(match.range, in: text) else { return nil }

            // Skip hashtags that fall within URL ranges
            for urlRange in urlRanges {
                if range.lowerBound >= urlRange.lowerBound && range.upperBound <= urlRange.upperBound {
                    return nil
                }
            }

            let name = String(text[range])
            return DetectedHashtag(name: name, range: range)
        }
    }

    /// Validates that a channel name contains only valid characters
    /// - Parameter name: Channel name without # prefix
    /// - Returns: True if valid (starts with alphanumeric, then lowercase letters, numbers, hyphens only)
    public static func isValidHashtagName(_ name: String) -> Bool {
        guard let first = name.unicodeScalars.first else { return false }
        guard isAllowedHashtagNameScalar(first, allowsHyphen: false) else { return false }
        return name.unicodeScalars.allSatisfy { scalar in
            isAllowedHashtagNameScalar(scalar, allowsHyphen: true)
        }
    }

    public static func sanitizeHashtagNameInput(_ input: String) -> String {
        var result = String()
        result.reserveCapacity(input.count)

        for scalar in input.lowercased().unicodeScalars {
            guard isAllowedHashtagNameScalar(scalar, allowsHyphen: true) else { continue }
            result.unicodeScalars.append(scalar)
        }

        while result.hasPrefix("-") {
            result.removeFirst()
        }

        return result
    }

    /// Normalizes a hashtag name by lowercasing and removing # prefix
    /// - Parameter name: The hashtag name (with or without #)
    /// - Returns: Normalized lowercase name without prefix
    public static func normalizeHashtagName(_ name: String) -> String {
        var normalized = name.lowercased()
        if normalized.hasPrefix("#") {
            normalized.removeFirst()
        }
        return normalized
    }

    // MARK: - Private Helpers

    private static let urlDetector: NSDataDetector? = {
        try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    }()

    private static func isAllowedHashtagNameScalar(_ scalar: UnicodeScalar, allowsHyphen: Bool) -> Bool {
        switch scalar.value {
        case 48...57, 65...90, 97...122:
            return true
        case 45:
            return allowsHyphen
        default:
            return false
        }
    }

    private static func findURLRanges(in text: String) -> [Range<String.Index>] {
        guard let detector = urlDetector else { return [] }

        let nsRange = NSRange(text.startIndex..., in: text)
        let matches = detector.matches(in: text, options: [], range: nsRange)

        return matches.compactMap { match -> Range<String.Index>? in
            guard let url = match.url,
                  let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https" else {
                return nil
            }
            return Range(match.range, in: text)
        }
    }
}
