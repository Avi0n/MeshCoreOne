import Foundation
import CryptoKit

// MARK: - Crockford Base32

/// Crockford Base32 alphabet (excludes I, L, O, U to avoid ambiguity)
private let crockfordAlphabet = Array("0123456789abcdefghjkmnpqrstvwxyz")

/// Crockford Base32 decode table (maps ASCII to 5-bit values, -1 for invalid)
private let crockfordDecodeTable: [Int8] = {
    var table = [Int8](repeating: -1, count: 128)
    for (index, char) in crockfordAlphabet.enumerated() {
        table[Int(char.asciiValue!)] = Int8(index)
        if let upper = Character(char.uppercased()).asciiValue {
            table[Int(upper)] = Int8(index)
        }
    }
    // Handle common substitutions (both cases)
    table[Int(Character("O").asciiValue!)] = 0  // O -> 0
    table[Int(Character("o").asciiValue!)] = 0
    table[Int(Character("I").asciiValue!)] = 1  // I -> 1
    table[Int(Character("i").asciiValue!)] = 1
    table[Int(Character("L").asciiValue!)] = 1  // L -> 1
    table[Int(Character("l").asciiValue!)] = 1
    return table
}()

/// Encodes 5 bytes (40 bits) to 8 Crockford Base32 characters
private func encodeCrockfordBase32(_ bytes: some Collection<UInt8>) -> String {
    precondition(bytes.count == 5)
    let byteArray = Array(bytes)

    // Pack 5 bytes into a 40-bit value
    var bits: UInt64 = 0
    for byte in byteArray {
        bits = (bits << 8) | UInt64(byte)
    }

    // Extract 8 groups of 5 bits, MSB first
    var result = ""
    result.reserveCapacity(8)
    for shift in stride(from: 35, through: 0, by: -5) {
        let index = Int((bits >> shift) & 0x1F)
        result.append(crockfordAlphabet[index])
    }
    return result
}

/// Validates a string contains only valid Crockford Base32 characters
private func isValidCrockfordBase32(_ string: String) -> Bool {
    for char in string {
        guard let ascii = char.asciiValue, ascii < 128, crockfordDecodeTable[Int(ascii)] >= 0 else {
            return false
        }
    }
    return true
}

/// Normalizes a Crockford Base32 string to lowercase canonical form
private func normalizeCrockfordBase32(_ string: String) -> String {
    var result = ""
    result.reserveCapacity(string.count)
    for char in string {
        guard let ascii = char.asciiValue, ascii < 128 else { continue }
        let value = crockfordDecodeTable[Int(ascii)]
        if value >= 0 {
            result.append(crockfordAlphabet[Int(value)])
        }
    }
    return result
}

/// Parsed reaction data extracted from wire format
public struct ParsedReaction: Sendable, Equatable {
    public let emoji: String
    public let targetSender: String
    public let messageHash: String  // 8 Crockford Base32 chars (lowercase)

    public init(
        emoji: String,
        targetSender: String,
        messageHash: String
    ) {
        self.emoji = emoji
        self.targetSender = targetSender
        self.messageHash = messageHash
    }
}

/// Parsed DM reaction data (shorter format without sender)
public struct ParsedDMReaction: Sendable, Equatable {
    public let emoji: String
    public let messageHash: String  // 8 Crockford Base32 chars (lowercase)

    public init(emoji: String, messageHash: String) {
        self.emoji = emoji
        self.messageHash = messageHash
    }
}

/// Parses reaction wire format using end-to-start strategy.
/// Format: `{emoji}@[{sender}]\nxxxxxxxx`
public enum ReactionParser {

    /// Returns true if the text matches any known reaction format (PocketMesh or meshcore-open).
    public static func isReactionText(_ text: String, isDM: Bool) -> Bool {
        if MeshCoreOpenReactionParser.parse(text) != nil { return true }
        if MeshCoreOpenReactionParser.parseV1(text) != nil { return true }
        return isDM ? parseDM(text) != nil : parse(text) != nil
    }

    /// Parses reaction text, returns nil if format doesn't match
    public static func parse(_ text: String) -> ParsedReaction? {
        // Step 1: Split on last newline to get hash
        guard let newlineIndex = text.lastIndex(of: "\n") else {
            return nil
        }

        let rawHash = String(text[text.index(after: newlineIndex)...])
        guard rawHash.count == 8, isValidCrockfordBase32(rawHash) else {
            return nil
        }
        let messageHash = normalizeCrockfordBase32(rawHash)

        // Remove hash suffix (everything before the newline)
        let withoutHash = String(text[..<newlineIndex])

        // Step 2: Find `@[` to locate sender start
        guard let atBracketIndex = withoutHash.range(of: "@[") else {
            return nil
        }

        let emoji = String(withoutHash[..<atBracketIndex.lowerBound])

        // Validate emoji is not empty and starts with emoji character
        guard !emoji.isEmpty, emoji.first?.isEmoji == true else {
            return nil
        }

        let afterAtBracket = withoutHash[atBracketIndex.upperBound...]

        // Step 3: Extract sender (everything up to closing bracket)
        guard afterAtBracket.hasSuffix("]") else {
            return nil
        }

        let sender = String(afterAtBracket.dropLast())

        guard !sender.isEmpty else {
            return nil
        }

        return ParsedReaction(
            emoji: emoji,
            targetSender: sender,
            messageHash: messageHash
        )
    }

    /// Parses DM reaction text, returns nil if format doesn't match.
    /// Format: `{emoji}\nxxxxxxxx` (no sender field)
    public static func parseDM(_ text: String) -> ParsedDMReaction? {
        // Reject channel format (contains `@[`)
        if text.contains("@[") {
            return nil
        }

        // Split on newline to get hash
        guard let newlineIndex = text.lastIndex(of: "\n") else {
            return nil
        }

        let rawHash = String(text[text.index(after: newlineIndex)...])
        guard rawHash.count == 8, isValidCrockfordBase32(rawHash) else {
            return nil
        }
        let messageHash = normalizeCrockfordBase32(rawHash)

        // Extract emoji (everything before the newline)
        let emoji = String(text[..<newlineIndex])

        // Validate emoji is not empty and starts with emoji character
        guard !emoji.isEmpty, emoji.first?.isEmoji == true else {
            return nil
        }

        return ParsedDMReaction(emoji: emoji, messageHash: messageHash)
    }

    /// Builds DM reaction text in wire format.
    /// Format: `{emoji}\n{hash}`
    public static func buildDMReactionText(
        emoji: String,
        targetText: String,
        targetTimestamp: UInt32
    ) -> String {
        let hash = generateMessageHash(text: targetText, timestamp: targetTimestamp)
        return "\(emoji)\n\(hash)"
    }

    /// Generates message identifier for reaction wire format (8-char Crockford Base32)
    public static func generateMessageHash(text: String, timestamp: UInt32) -> String {
        var data = Data(text.utf8)
        withUnsafeBytes(of: timestamp.littleEndian) { data.append(contentsOf: $0) }
        let digest = SHA256.hash(data: data)
        let bytes = Array(digest.prefix(5))
        return encodeCrockfordBase32(bytes)
    }

    /// Builds summary string from emoji counts, sorted by count descending
    public static func buildSummary(from reactions: [(emoji: String, count: Int)]) -> String {
        reactions
            .sorted { $0.count > $1.count }
            .map { "\($0.emoji):\($0.count)" }
            .joined(separator: ",")
    }

    /// Builds summary string from reaction DTOs.
    /// Sorts by count descending, then by earliest timestamp ascending for tie-breaker.
    public static func buildSummary(from reactions: [ReactionDTO]) -> String {
        let grouped = Dictionary(grouping: reactions, by: \.emoji)
        let sorted = grouped.map { emoji, items in
            (emoji: emoji, count: items.count, earliest: items.map(\.receivedAt).min() ?? Date.distantPast)
        }
        .sorted { lhs, rhs in
            if lhs.count != rhs.count { return lhs.count > rhs.count }
            return lhs.earliest < rhs.earliest
        }
        return sorted.map { "\($0.emoji):\($0.count)" }.joined(separator: ",")
    }

    /// Parses summary string into emoji/count pairs
    public static func parseSummary(_ summary: String?) -> [(emoji: String, count: Int)] {
        guard let summary, !summary.isEmpty else { return [] }

        return summary.split(separator: ",").compactMap { part in
            let components = part.split(separator: ":")
            guard components.count == 2,
                  let count = Int(components[1]) else { return nil }
            return (String(components[0]), count)
        }
    }
}
