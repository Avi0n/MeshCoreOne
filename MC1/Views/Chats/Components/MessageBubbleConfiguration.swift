import MC1Services

/// View-layer formatting flags for a message bubble.
struct MessageBubbleConfiguration {
  var showSenderName: Bool

  static let directMessage = MessageBubbleConfiguration(showSenderName: false)

  static func channel(isPublic: Bool) -> MessageBubbleConfiguration {
    MessageBubbleConfiguration(showSenderName: true)
  }

  /// Builds a `loweredName -> nickname` lookup for channel sender matching.
  /// Only names owned by exactly one contact are included, so an ambiguous name
  /// collision never asserts a specific nickname. Keyed by locale-independent
  /// `lowercased()` because a hashable key needs a fixed fold; this intentionally
  /// diverges from `SenderContactMatcher`'s locale-aware `localizedCaseInsensitiveCompare`,
  /// so on locale-tailored case folding (Turkish dotless-i, German ß) this badge and
  /// the Block/DM actions can disagree. Accepted for the O(1) per-message lookup.
  static func buildNicknameLookup(from contacts: [ContactDTO]) -> [String: String] {
    var counts: [String: Int] = [:]
    for contact in contacts {
      counts[contact.name.lowercased(), default: 0] += 1
    }
    var lookup: [String: String] = [:]
    for contact in contacts {
      let key = contact.name.lowercased()
      guard counts[key] == 1, let nickname = contact.nickname, !nickname.isEmpty else { continue }
      lookup[key] = nickname
    }
    return lookup
  }

  /// Resolves the display name for a message's sender from the contacts list.
  /// Used by `ChatViewModel+ItemBuild` to bake the resolved name into
  /// `MessageItem.envelope.senderResolution` upstream.
  static func resolveSenderName(
    for message: MessageDTO,
    contacts: [ContactDTO],
    nicknamesByLoweredName: [String: String] = [:]
  ) -> NodeNameResolution {
    // First, try parsed sender name from channel message
    if let senderName = message.senderNodeName, !senderName.isEmpty {
      let nickname = nicknamesByLoweredName[senderName.lowercased()]
      return NodeNameResolution(displayName: senderName, matchKind: .exact, unverifiedNickname: nickname)
    }

    // Fallback: key prefix lookup
    guard let prefix = message.senderKeyPrefix else {
      return NodeNameResolution(
        displayName: L10n.Chats.Chats.Message.Sender.unknown,
        matchKind: .unresolved
      )
    }

    // Try to find matching contact
    if let result = NeighborNameResolver.resolve(
      for: prefix,
      contacts: contacts,
      discoveredNodes: [],
      userLocation: nil
    ) {
      return result
    }

    // Fallback to hex representation
    if prefix.count >= 2 {
      return NodeNameResolution(displayName: prefix.prefix(2).uppercaseHexString(), matchKind: .unresolved)
    }
    return NodeNameResolution(
      displayName: L10n.Chats.Chats.Message.Sender.unknown,
      matchKind: .unresolved
    )
  }
}
