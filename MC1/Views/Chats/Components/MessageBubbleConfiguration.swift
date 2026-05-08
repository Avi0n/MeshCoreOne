import SwiftUI
import MC1Services

/// Configuration for message bubble appearance and behavior
struct MessageBubbleConfiguration: Sendable {
    let accentColor: Color
    let showSenderName: Bool
    let isChannel: Bool
    let senderNameResolver: (@Sendable (MessageDTO) -> NodeNameResolution)?

    static let directMessage = MessageBubbleConfiguration(
        accentColor: .blue,
        showSenderName: false,
        isChannel: false,
        senderNameResolver: nil
    )

    static func channel(isPublic: Bool, contacts: [ContactDTO]) -> MessageBubbleConfiguration {
        MessageBubbleConfiguration(
            accentColor: isPublic ? .green : .blue,
            showSenderName: true,
            isChannel: true,
            senderNameResolver: { message in
                resolveSenderName(for: message, contacts: contacts)
            }
        )
    }

    private static func resolveSenderName(for message: MessageDTO, contacts: [ContactDTO]) -> NodeNameResolution {
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
