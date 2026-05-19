import MC1Services

/// View-layer formatting flags for a message bubble.
struct MessageBubbleConfiguration: Sendable {
    var showSenderName: Bool

    static let directMessage = MessageBubbleConfiguration(showSenderName: false)

    static func channel(isPublic: Bool) -> MessageBubbleConfiguration {
        MessageBubbleConfiguration(showSenderName: true)
    }

    /// Resolves the display name for a message's sender from the contacts list.
    /// Used by `ChatViewModel+ItemBuild` to bake the resolved name into
    /// `MessageItem.envelope.senderResolution` upstream.
    static func resolveSenderName(for message: MessageDTO, contacts: [ContactDTO]) -> NodeNameResolution {
        // First, try parsed sender name from channel message
        if let senderName = message.senderNodeName, !senderName.isEmpty {
            return NodeNameResolution(displayName: senderName, matchKind: .exact)
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
            return NodeNameResolution(displayName: prefix.prefix(2).hexString(), matchKind: .unresolved)
        }
        return NodeNameResolution(
            displayName: L10n.Chats.Chats.Message.Sender.unknown,
            matchKind: .unresolved
        )
    }
}
