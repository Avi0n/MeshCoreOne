import Foundation

/// Utilities for working with MeshCore mention format: @[nodeContactName]
public enum MentionUtilities {
    /// The regex pattern for matching mentions: @[name]
    public static let mentionPattern = #"@\[([^\]]+)\]"#

    /// Pre-compiled regex for mention matching (avoids recompilation per call)
    public static let mentionRegex: NSRegularExpression? = {
        try? NSRegularExpression(pattern: mentionPattern)
    }()

    /// Creates a mention string from a node contact name
    /// - Parameter name: The mesh network contact name (not nickname)
    /// - Returns: Formatted mention string "@[name]"
    public static func createMention(for name: String) -> String {
        "@[\(name)]"
    }

    /// Appends a mention to the existing composer draft, preserving it. Inserts a
    /// separating space only when the draft does not already end in whitespace, and
    /// always leaves a trailing space.
    public static func appendMention(for name: String, to draft: String) -> String {
        let mention = createMention(for: name)
        guard !draft.isEmpty else { return mention + " " }
        let separator = draft.last?.isWhitespace == true ? "" : " "
        return draft + separator + mention + " "
    }

    /// Extracts all mentions from message text
    /// - Parameter text: The message text to parse
    /// - Returns: Array of mentioned contact names (without @[] wrapper)
    public static func extractMentions(from text: String) -> [String] {
        guard let regex = mentionRegex else { return [] }

        let range = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: range)

        return matches.compactMap { match in
            guard let captureRange = Range(match.range(at: 1), in: text) else {
                return nil
            }
            return String(text[captureRange])
        }
    }

    /// Detects an active mention query from input text.
    /// Returns the search query (text after @) if user is typing a mention, nil otherwise.
    /// Triggers when @ is at start or after whitespace. Returns empty string for standalone @.
    public static func detectActiveMention(in text: String) -> String? {
        guard !text.isEmpty else { return nil }

        // Find the last @ that could start a mention
        var searchStart = text.endIndex

        while let atIndex = text[..<searchStart].lastIndex(of: "@") {
            // Check if @ is at start or preceded by whitespace
            let isAtStart = atIndex == text.startIndex
            let isAfterWhitespace = !isAtStart && text[text.index(before: atIndex)].isWhitespace

            guard isAtStart || isAfterWhitespace else {
                // @ is mid-word (like email@), try earlier @
                searchStart = atIndex
                continue
            }

            // Get text after @
            let afterAt = text[text.index(after: atIndex)...]

            // Standalone @ returns empty query to show all contacts
            guard !afterAt.isEmpty else { return "" }

            // If first char after @ is whitespace or another @, not a mention
            guard let firstChar = afterAt.first, !firstChar.isWhitespace, firstChar != "@" else { return nil }

            // Check if this is a bracketed mention @[...]
            if afterAt.hasPrefix("[") {
                if let closeBracket = afterAt.firstIndex(of: "]") {
                    // Completed mention, check for more text after
                    let afterMention = afterAt[afterAt.index(after: closeBracket)...]
                    if afterMention.isEmpty {
                        return nil
                    }
                    // Continue searching for another @
                    searchStart = atIndex
                    continue
                } else {
                    // Unclosed bracket - user is typing a manual mention, don't show suggestions
                    return nil
                }
            }

            // Extract query until space or end
            let query = afterAt.prefix(while: { !$0.isWhitespace })
            return String(query)
        }

        return nil
    }

    /// Filters contacts for mention suggestions.
    /// - Parameters:
    ///   - contacts: All available contacts
    ///   - query: Search query (text after @)
    ///   - senderOrder: Sender name → timestamp map for recency sorting (nil = alphabetical)
    /// - Returns: Chat-type contacts matching query, sorted by recency then alphabetically
    public static func filterContacts(
        _ contacts: [ContactDTO],
        query: String,
        senderOrder: [String: UInt32]? = nil
    ) -> [ContactDTO] {
        let filtered = contacts
            .filter { $0.type == .chat }
            .filter { query.isEmpty || $0.displayName.localizedStandardContains(query) }

        guard let senderOrder, !senderOrder.isEmpty else {
            return filtered.sorted {
                $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
            }
        }

        return filtered.sorted { a, b in
            let aTimestamp = senderOrder[a.name]
            let bTimestamp = senderOrder[b.name]

            switch (aTimestamp, bTimestamp) {
            case let (aT?, bT?):
                // Both have timestamps: most recent first
                return aT > bT
            case (_?, nil):
                // Only a has a timestamp: a comes first
                return true
            case (nil, _?):
                // Only b has a timestamp: b comes first
                return false
            case (nil, nil):
                // Neither has a timestamp: alphabetical
                return a.displayName.localizedCaseInsensitiveCompare(b.displayName) == .orderedAscending
            }
        }
    }

    /// Checks if text contains a mention of the specified user name
    /// - Parameters:
    ///   - text: The message text to check
    ///   - selfName: The current user's node name
    /// - Returns: true if text contains @[selfName]
    public static func containsSelfMention(in text: String, selfName: String) -> Bool {
        let mentions = extractMentions(from: text)
        return mentions.contains { $0.caseInsensitiveCompare(selfName) == .orderedSame }
    }

    /// Pre-compiled regex for stripping a leading mention from reply text
    private static let leadingMentionRegex: NSRegularExpression? = {
        try? NSRegularExpression(pattern: #"^@\[[^\]]+\]\s*"#)
    }()

    /// Builds reply text with a mention and quoted preview of the original message.
    /// Strips any leading mention from the message text before generating the preview.
    public static func buildReplyText(mentionName: String, messageText: String) -> String {
        let previewSource: String
        if let regex = leadingMentionRegex,
           let match = regex.firstMatch(in: messageText, range: NSRange(messageText.startIndex..., in: messageText)),
           let matchRange = Range(match.range, in: messageText) {
            previewSource = String(messageText[matchRange.upperBound...])
        } else {
            previewSource = messageText
        }
        let preview = String(previewSource.prefix(10))
        let suffix = previewSource.count > 10 ? ".." : ""
        let mention = createMention(for: mentionName)
        return "\(mention)\n>\(preview)\(suffix)\n"
    }
}
