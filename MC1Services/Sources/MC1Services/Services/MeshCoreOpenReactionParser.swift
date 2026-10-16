import Foundation

/// Parsed meshcore-open v3 reaction data
struct ParsedMCOReaction: Equatable {
  let emoji: String
  let dartHash: String // 4 lowercase hex chars
}

/// Parsed meshcore-open v1 reaction data (pre-Jan 2026 clients)
///
/// The `senderNameHash` and `textHash` are full Dart VM `String.hashCode` values
/// (30-bit, decimal-encoded on the wire). For DM reactions, `senderNameHash` is
/// not verified during matching since DMs have implicit sender context.
struct ParsedMCOReactionV1: Equatable {
  let emoji: String
  let timestampSeconds: UInt32
  let senderNameHash: UInt32
  let textHash: UInt32

  /// Reconstructs the original v1 messageId, used as the opaque reaction hash for dedup.
  var messageIdHash: String {
    "\(timestampSeconds)_\(senderNameHash)_\(textHash)"
  }
}

/// Parses meshcore-open reaction wire format (receive-only).
///
/// meshcore-open sends reactions as `r:{4-char-hash}:{2-char-emoji-index}`.
/// The hash is computed using the Dart VM's `String.hashCode` algorithm
/// masked to 16 bits.
enum MeshCoreOpenReactionParser {
  // MARK: - Parsing

  /// Parses a meshcore-open reaction string.
  ///
  /// Format: `r:{4-char-hex-hash}:{2-char-hex-emoji-index}`
  /// - Returns: Parsed emoji and dart hash, or nil if format doesn't match.
  static func parse(_ text: String) -> ParsedMCOReaction? {
    guard text.count == 9,
          text.hasPrefix("r:"),
          text[text.index(text.startIndex, offsetBy: 6)] == ":" else {
      return nil
    }

    let hashStart = text.index(text.startIndex, offsetBy: 2)
    let hashEnd = text.index(text.startIndex, offsetBy: 6)
    let indexStart = text.index(text.startIndex, offsetBy: 7)

    let hashStr = String(text[hashStart..<hashEnd])
    let indexStr = String(text[indexStart...])

    // Validate both are lowercase hex
    guard isLowercaseHex(hashStr), isLowercaseHex(indexStr) else {
      return nil
    }

    guard let emojiIndex = UInt8(indexStr, radix: 16),
          Int(emojiIndex) < emojiTable.count else {
      return nil
    }

    return ParsedMCOReaction(
      emoji: emojiTable[Int(emojiIndex)],
      dartHash: hashStr
    )
  }

  /// Parses a meshcore-open v1 reaction string.
  ///
  /// Format: `r:{millis}_{senderNameHash}_{textHash}:{emoji}`
  /// Used by pre-Jan 2026 meshcore-open clients. The hashes are full Dart
  /// `String.hashCode` values (30-bit, decimal-encoded).
  static func parseV1(_ text: String) -> ParsedMCOReactionV1? {
    guard text.hasPrefix("r:") else { return nil }

    // Split on last ":" to separate messageId from emoji
    guard let lastColon = text.lastIndex(of: ":"),
          lastColon > text.index(text.startIndex, offsetBy: 2) else {
      return nil
    }

    let messageId = String(text[text.index(text.startIndex, offsetBy: 2)..<lastColon])
    let emoji = String(text[text.index(after: lastColon)...])
    guard !emoji.isEmpty else { return nil }

    // Split messageId on "_" — expect exactly 3 parts
    let parts = messageId.split(separator: "_")
    guard parts.count == 3 else { return nil }

    guard let timestampMillis = UInt64(parts[0]),
          let senderNameHash = UInt32(parts[1]),
          let textHash = UInt32(parts[2]) else {
      return nil
    }

    let seconds = timestampMillis / 1000
    guard seconds <= UInt64(UInt32.max) else { return nil }

    return ParsedMCOReactionV1(
      emoji: emoji,
      timestampSeconds: UInt32(seconds),
      senderNameHash: senderNameHash,
      textHash: textHash
    )
  }

  // MARK: - Hash Computation

  /// Reimplements the Dart VM's `String.hashCode` algorithm.
  ///
  /// Operates on UTF-16 code units with 30-bit result:
  /// ```
  /// hash = 0
  /// for each code_unit:
  ///     hash += code_unit
  ///     hash += hash << 10
  ///     hash ^= hash >> 6
  /// finalize:
  ///     hash += hash << 3
  ///     hash ^= hash >> 11
  ///     hash += hash << 15
  ///     hash &= (1 << 30) - 1
  ///     if hash == 0: hash = 1
  /// ```
  /// Convenience overload that hashes a String's UTF-16 code units directly.
  static func dartStringHash(_ string: String) -> UInt32 {
    dartStringHash(Array(string.utf16))
  }

  static func dartStringHash(_ codeUnits: [UInt16]) -> UInt32 {
    var hash: UInt32 = 0

    for unit in codeUnits {
      hash = hash &+ UInt32(unit)
      hash = hash &+ (hash &<< 10)
      hash ^= (hash >> 6)
    }

    // Finalize
    hash = hash &+ (hash &<< 3)
    hash ^= (hash >> 11)
    hash = hash &+ (hash &<< 15)

    // Mask to 30 bits
    let kHashBitMask: UInt32 = (1 << 30) - 1
    hash &= kHashBitMask

    if hash == 0 { hash = 1 }
    return hash
  }

  /// Computes the reaction hash used by meshcore-open.
  ///
  /// Builds UTF-16 code units from: `"\(timestamp)" + senderName + first5UTF16(text)`
  /// Then runs `dartStringHash`, masks to 16 bits, and formats as 4 lowercase hex chars.
  ///
  /// - Parameters:
  ///   - timestamp: Message timestamp as UInt32
  ///   - senderName: Sender node name (nil for DM reactions)
  ///   - text: Message text content
  /// - Returns: 4-character lowercase hex hash string
  static func computeReactionHash(
    timestamp: UInt32,
    senderName: String?,
    text: String
  ) -> String {
    var codeUnits: [UInt16] = []

    // Append timestamp as string
    codeUnits.append(contentsOf: String(timestamp).utf16)

    // Append sender name (channel only)
    if let senderName {
      codeUnits.append(contentsOf: senderName.utf16)
    }

    // Append first 5 UTF-16 code units of text
    codeUnits.append(contentsOf: text.utf16.prefix(5))

    let hash = dartStringHash(codeUnits) & 0xFFFF
    return String(format: "%04x", hash)
  }

  // MARK: - Emoji Table

  /// The 184-emoji lookup table from meshcore-open's emoji_picker.dart.
  /// Concatenated in order: quickEmojis + smileys + gestures + hearts + objects.
  static let emojiTable: [String] = [
    // quickEmojis (0x00–0x05)
    "👍", "❤️", "😂", "🎉", "👏", "🔥",
    // smileys (0x06–0x45)
    "😀", "😃", "😄", "😁", "😅", "😂", "🤣", "😊",
    "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘",
    "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪",
    "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "😏",
    "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖",
    "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡",
    "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰",
    "😥", "😓", "🤗", "🤔", "🤭", "🤫", "🤥", "😶",
    // gestures (0x46–0x66)
    "👍", "👎", "👊", "✊", "🤛", "🤜", "🤞", "✌️",
    "🤟", "🤘", "👌", "🤌", "🤏", "👈", "👉", "👆",
    "👇", "☝️", "👋", "🤚", "🖐️", "✋", "🖖", "👏",
    "🙌", "👐", "🤲", "🤝", "🙏", "✍️", "💅", "🤳",
    "💪",
    // hearts (0x67–0x86)
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
    "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗",
    "💖", "💘", "💝", "💟", "💌", "💢", "💥", "💫",
    "💦", "💨", "🕳️", "💬", "👁️‍🗨️", "🗨️", "🗯️", "💭",
    // objects (0x87–0xB7)
    "🎉", "🎊", "🎈", "🎁", "🎀", "🪅", "🪆", "🏆",
    "🥇", "🥈", "🥉", "⚽", "⚾", "🥎", "🏀", "🏐",
    "🏈", "🏉", "🎾", "🥏", "🎳", "🏏", "🏑", "🏒",
    "🥍", "🏓", "🏸", "🥊", "🥋", "🥅", "⛳", "🔥",
    "⭐", "🌟", "✨", "⚡", "💡", "🔦", "🏮", "🪔",
    "📱", "💻", "⌚", "📷", "📺", "📻", "🎵", "🎶",
    "🚀",
  ]

  // MARK: - Private Helpers

  private static func isLowercaseHex(_ string: String) -> Bool {
    string.allSatisfy { $0.isHexDigit && !$0.isUppercase }
  }
}
